package br.com.bebidaspdv.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Administrative operations need a server connection. Durable UUIDs survive uncertain responses. */
class FirestoreCashControlRepository(private val db: FirebaseFirestore, context: Context, private val logger: AppLogger = NoOpLogger) : CashControlRepository {
    private val auth get() = FirebaseAuth.getInstance()
    private val preferences = context.getSharedPreferences("cash_control", Context.MODE_PRIVATE)
    private val lock = Mutex()
    override val deviceId: String = synchronized(deviceLock) {
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also { id ->
            check(preferences.edit().putString("device_id", id).commit()) { "Não foi possível identificar este aparelho." }
        }
    }
    private val mutablePending = MutableStateFlow<List<PendingCashOperation>>(emptyList())
    override val pendingOperations = mutablePending.asStateFlow()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPending() }
    private val seenPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        auth.addAuthStateListener { refreshPending() }
        refreshPending()
    }

    private fun refreshPending() {
        val uid = auth.currentUser?.uid
        mutablePending.value = if (uid == null) emptyList() else preferences.all.filterKeys { it.startsWith("pending:$uid:") }.values.mapNotNull { raw ->
            runCatching {
                val op = JSONObject(raw as String)
                val id = op.getString("id")
                if (seenPending.add(id)) logger.log(LogLevel.INFO, LogCategory.SYNC, "CASH_REQUEST_RECOVERED",
                    "Solicitação durável encontrada; precisa de conferência com o servidor.",
                    receiptTrace(op, "refreshPending"),
                    status = LogStatus.PENDING, syncStatus = LogSyncStatus.PENDING)
                PendingCashOperation(op.getString("id"), description(op.getString("type")), Instant.ofEpochMilli(op.getLong("time")))
            }.getOrNull()
        }.sortedBy { it.occurredAt }
    }

    override fun observeSessions(): Flow<Feed<List<CashSession>>> = observe(
        db.collection("CaixaSessoes").whereEqualTo("aparelho_id", deviceId)
            .orderBy("dataHora", Query.Direction.DESCENDING).limit(100), ::session,
    ).map { it.copy(value = it.value.sortedByDescending(CashSession::openedAt)) }

    override fun observeEvents(): Flow<Feed<List<CashEvent>>> = observe(
        db.collection("MovimentosCaixa").whereEqualTo("aparelho_id", deviceId)
            .orderBy("dataHora", Query.Direction.DESCENDING).limit(200), ::event,
    ).map { it.copy(value = it.value.sortedByDescending(CashEvent::occurredAt)) }

    override fun observeReversals(month: YearMonth, zone: ZoneId): Flow<Feed<List<SaleReversal>>> = observe(
        db.collection("Estornos").whereGreaterThanOrEqualTo("dataHora", timestamp(month.atDay(1).atStartOfDay(zone).toInstant()))
            .whereLessThan("dataHora", timestamp(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()))
            .orderBy("dataHora", Query.Direction.DESCENDING), ::reversal,
    )

    override suspend fun openSession(openingCents: Long) {
        require(openingCents in 0..BusinessLimits.MAX_MONEY_CENTS) { "Informe um valor inicial válido." }
        submit(newOperation("ABERTURA").put("amount", openingCents))
    }

    override suspend fun readPaymentSummary(sessionId: String): CashPaymentSummary = withContext(Dispatchers.IO) {
        uuid(sessionId)
        val trace = logger.repositoryTrace(LogCategory.CASH_REGISTER, "CASH_PAYMENT_SUMMARY", "FirestoreCashControlRepository", "readPaymentSummary", entityId = sessionId)
        logger.repositoryStep(trace, LogCategory.DATABASE, "CASH_PAYMENT_SUMMARY", "FirestoreCashControlRepository", "readPaymentSummary", slowAfterMs = 2_000) {
        withTimeout(30_000) {
            val ref = db.collection("CaixaSessoes").document(sessionId)
            val beforeDocument = ref.get(Source.SERVER).await()
            check(!beforeDocument.metadata.hasPendingWrites()) { "Há operações aguardando sincronização. Aguarde e atualize a conferência." }
            val before = session(beforeDocument)
            require(before.deviceId == deviceId) { "Escolha um caixa deste aparelho." }
            val methods = PaymentMethod.entries.filter { it != PaymentMethod.FIADO }
                .associateWith { CashMethodTotals() }.toMutableMap()
            var creditIssued = 0L
            readSessionDocuments("Vendas", sessionId) { document ->
                val sale = FirestoreMapper.movement(document)
                if (sale.type == MovementType.SAIDA) {
                    methods.keys.toList().forEach { method ->
                        val amount = if (method == PaymentMethod.CASH) CashAccounting.saleCash(sale.payments, sale.changeCents)
                            else sale.payments.filter { it.method == method }.fold(0L) { sum, payment -> Math.addExact(sum, payment.amountCents) }
                        val old = methods.getValue(method)
                        methods[method] = old.copy(salesCents = Math.addExact(old.salesCents, amount))
                    }
                    creditIssued = Math.addExact(creditIssued, sale.receivableCents)
                }
            }
            readSessionDocuments("Recebimentos", sessionId) { document ->
                val method = PaymentMethod.valueOf(requireNotNull(document.getString("metodo")))
                val amount = requireNotNull(document.getLong("amount_cents"))
                val old = methods.getValue(method)
                methods[method] = old.copy(receiptsCents = Math.addExact(old.receiptsCents, amount))
            }
            readSessionDocuments("Estornos", sessionId) { document ->
                paymentMap(document.get("refund_by_method_cents")).forEach { (method, amount) ->
                    val old = methods.getValue(method)
                    methods[method] = old.copy(refundsCents = Math.addExact(old.refundsCents, amount))
                }
            }
            val afterDocument = ref.get(Source.SERVER).await()
            check(!afterDocument.metadata.hasPendingWrites()) { "Há operações aguardando sincronização. Aguarde e atualize a conferência." }
            val after = session(afterDocument)
            check(before.revision == after.revision) { "O caixa recebeu uma nova operação durante a consulta. Atualize a conferência." }
            CashPaymentSummary(sessionId, after.revision, methods, creditIssued)
        }
        }
    }

    private suspend fun readSessionDocuments(collection: String, sessionId: String, consume: (DocumentSnapshot) -> Unit) {
        val trace = logger.repositoryTrace(LogCategory.DATABASE, "CASH_SUMMARY_QUERY", "FirestoreCashControlRepository", "readSessionDocuments", entityId = sessionId)
        logger.repositoryStep(trace, LogCategory.DATABASE, "CASH_SUMMARY_${collection.uppercase()}", "FirestoreCashControlRepository", "readSessionDocuments", slowAfterMs = 2_000) {
        val query = db.collection(collection).whereEqualTo("caixa_id", sessionId).orderBy(FieldPath.documentId()).limit(100)
        var cursor: DocumentSnapshot? = null
        do {
            val page = (cursor?.let(query::startAfter) ?: query).get(Source.SERVER).await()
            check(!page.metadata.hasPendingWrites()) { "Há operações aguardando sincronização. Aguarde e atualize a conferência." }
            page.documents.forEach(consume)
            cursor = if (page.size() == 100) page.documents.last() else null
        } while (cursor != null)
        }
    }

    override suspend fun addMovement(sessionId: String, type: CashEventType, amountCents: Long, reason: String) {
        require(type in listOf(CashEventType.SANGRIA, CashEventType.SUPRIMENTO))
        uuid(sessionId)
        require(amountCents in 1..BusinessLimits.MAX_MONEY_CENTS) { "Informe um valor maior que zero." }
        require(reason.trim().length in 3..200) { "Informe o motivo com 3 a 200 caracteres." }
        submit(newOperation(type.name).put("session", sessionId).put("amount", amountCents).put("reason", reason.trim()))
    }

    override suspend fun closeSession(sessionId: String, countedCents: Long, expectedRevision: Long) {
        uuid(sessionId)
        require(countedCents in 0..BusinessLimits.MAX_MONEY_CENTS && expectedRevision >= 0)
        submit(newOperation("FECHAMENTO").put("session", sessionId).put("amount", countedCents).put("revision", expectedRevision))
    }

    override suspend fun previewReversal(saleId: String): ReversalPreview = withContext(Dispatchers.IO) {
        uuid(saleId)
        requireReversalPermission()
        withTimeout(20_000) {
            check(!db.collection("Estornos").document(saleId).get(Source.SERVER).await().exists()) { "Esta venda já foi estornada." }
            val original = db.collection("Vendas").document(saleId).get(Source.SERVER).await()
            check(original.exists()) { "Venda não encontrada. Informe o identificador completo do comprovante." }
            val sale = FirestoreMapper.movement(original)
            val settled = if (sale.receivableCents > 0) {
                val account = db.collection("ContasReceber").document(saleId).get(Source.SERVER).await()
                check(account.exists() && account.getString("estorno_id").isNullOrBlank()) { "Conta da venda indisponível ou já estornada." }
                val totals = paymentMap(account.get("settled_by_method_cents"))
                check(totals.values.sum() == (account.getLong("settled_cents") ?: 0L)) {
                    "Os recebimentos antigos precisam ser conciliados por forma de pagamento antes deste estorno."
                }
                totals
            } else emptyMap()
            CashAccounting.reversalPreview(sale, settled)
        }
    }

    override suspend fun reverseSale(preview: ReversalPreview, reason: String) {
        require(reason.trim().length in 3..200) { "Informe o motivo com 3 a 200 caracteres." }
        requireReversalPermission()
        val current = currentSession()
        check(preview.cashRefundCents <= current.expectedCents) { "O caixa não tem dinheiro suficiente para a devolução. Registre um suprimento antes." }
        submit(newOperation("ESTORNO").put("session", current.id).put("sale", preview.sale.id)
            .put("reason", reason.trim()).put("settled", preview.settledCents).put("cancelled", preview.cancelledReceivableCents)
            .put("refunds", JSONObject(preview.refundByMethod.mapKeys { it.key.name })))
    }

    override suspend fun retryPending(id: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val uid = auth.currentUser?.uid ?: error("Entre na conta para conferir a pendência.")
            val raw = preferences.getString("pending:$uid:$id", null) ?: error("Esta operação já foi conciliada.")
            val trace = receiptTrace(JSONObject(raw), "retryPending")
            logger.log(LogLevel.INFO, LogCategory.SYNC, "SYNC_RETRY", "Operador solicitou a conferência da operação durável.", trace,
                status = LogStatus.RETRY, syncStatus = LogSyncStatus.PENDING)
            withLogTrace(trace) { executeDurable(JSONObject(raw)) }
        }
    }

    private fun newOperation(type: String): JSONObject {
        val user = auth.currentUser ?: error("Entre na conta para operar o caixa.")
        return JSONObject().put("id", UUID.randomUUID().toString()).put("type", type)
            .put("time", System.currentTimeMillis()).put("operator", user.uid)
            .put("name", user.email ?: user.displayName ?: user.uid)
    }

    @SuppressLint("UseKtx")
    private suspend fun submit(operation: JSONObject): Unit = withContext(Dispatchers.IO) {
        val id = operation.getString("id")
        val trace = logger.repositoryTrace(LogCategory.CASH_REGISTER, "CASH_REGISTER_REQUEST", "FirestoreCashControlRepository", "submit", id,
            operation.optString("session").ifBlank { id })
        val event = when (operation.getString("type")) {
            "ABERTURA" -> "CASH_REGISTER_OPEN_REQUEST"
            "FECHAMENTO" -> "CASH_REGISTER_CLOSE_REQUEST"
            else -> "CASH_REGISTER_MOVEMENT_REQUEST"
        }
        logger.info(LogCategory.CASH_REGISTER, event, "Solicitação recebida para confirmação online.", trace)
        operation.put("diagnostic_trace_id", trace.traceId)
        logger.repositoryStep(trace, LogCategory.CASH_REGISTER, "CASH_REGISTER_REQUEST", "FirestoreCashControlRepository", "submit", slowAfterMs = 2_000) {
        lock.withLock {
            check(mutablePending.value.isEmpty()) { "Confira a operação pendente antes de iniciar outra operação de caixa." }
            check(auth.currentUser?.uid == operation.getString("operator")) { "A conta mudou. Confira a operação." }
            // Confirm connectivity before persisting an operation which might otherwise never have run.
            logger.repositoryStep(trace, LogCategory.FIREBASE, "FIREBASE_CONNECTION_CHECK", "FirestoreCashControlRepository", "submit.connection", slowAfterMs = 2_000) {
                withTimeout(15_000) { db.collection("CaixasDispositivos").document(deviceId).get(Source.SERVER).await() }
            }
            check(preferences.edit().putString(key(operation), operation.toString()).commit()) { "Não foi possível guardar o comprovante da operação." }
            seenPending.add(id)
            logger.log(LogLevel.INFO, LogCategory.DATABASE, "CASH_REQUEST_SAVED_LOCAL",
                "Solicitação guardada para recuperação; abertura ou fechamento ainda depende do servidor.", trace,
                status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.PENDING)
            logger.log(LogLevel.INFO, LogCategory.SYNC, "CASH_REGISTER_SYNC_PENDING", "Aguardando confirmação da operação de caixa.", trace,
                status = LogStatus.PENDING, syncStatus = LogSyncStatus.PENDING)
            refreshPending()
            executeDurable(operation)
        }
        }
    }

    @SuppressLint("UseKtx")
    private suspend fun executeDurable(op: JSONObject) {
        val trace = logger.repositoryTrace(LogCategory.CASH_REGISTER, "CASH_OPERATION", "FirestoreCashControlRepository", "executeDurable", op.getString("id"))
        check(auth.currentUser?.uid == op.getString("operator")) { "Entre na conta que iniciou a operação." }
        val batch = op.getString("type") == "ESTORNO"
        val step = logger.startStep(trace, LogCategory.FIREBASE, if (batch) "DATABASE_BATCH" else "DATABASE_TRANSACTION", "FirestoreCashControlRepository", "executeDurable", slowAfterMs = 2_000, stuckAfterMs = 15_000)
        try {
            withLogTrace(trace) { withTimeout(30_000) {
                if (op.getString("type") == "ESTORNO") executeReversal(op) else executeCashTransaction(op)
            } }
            step.finish("Servidor confirmou a operação.", syncStatus = LogSyncStatus.SYNCED)
            logger.log(LogLevel.INFO, LogCategory.DATABASE, if (batch) "DATABASE_BATCH_COMMITTED" else "DATABASE_TRANSACTION_COMMITTED",
                "Comprovante confirmado pelo servidor.", trace, status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
            val event = when (op.getString("type")) {
                "ABERTURA" -> "CASH_REGISTER_OPENED_SERVER"
                "FECHAMENTO" -> "CASH_REGISTER_CLOSED_SERVER"
                "ESTORNO" -> "SALE_REVERSED_SERVER"
                else -> "CASH_REGISTER_MOVEMENT_CONFIRMED"
            }
            logger.log(LogLevel.INFO, LogCategory.CASH_REGISTER, event, "Operação confirmada remotamente.", trace,
                status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
            logger.log(LogLevel.INFO, LogCategory.SYNC, "CASH_REGISTER_SYNCED", "Confirmação remota recebida.", trace,
                status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
        } catch (error: Exception) {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
            val definitive = causes.any { it is IllegalArgumentException || it is IllegalStateException ||
                it is FirebaseFirestoreException && it.code in listOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                    FirebaseFirestoreException.Code.FAILED_PRECONDITION, FirebaseFirestoreException.Code.ABORTED,
                ) }
            if (definitive && error !is CancellationException) {
                step.fail(error, "Transação recusada pelo servidor.", error.diagnosticCode())
                logger.log(LogLevel.ERROR, LogCategory.CASH_REGISTER, "CASH_REGISTER_ERROR", "Operação recusada; solicitação será reconciliada.", trace,
                    status = LogStatus.FAILED, error = error, errorCode = error.diagnosticCode(), syncStatus = LogSyncStatus.FAILED)
                check(preferences.edit().remove(key(op)).commit()) { "Confira a pendência para reconciliar a operação recusada." }
                refreshPending()
                throw IllegalStateException(causes.last().localizedMessage ?: "Operação recusada. Atualize o caixa e tente novamente.", error)
            }
            // A timeout cannot establish whether the server committed. Keep the exact request.
            step.cancel("Confirmação interrompida; resultado remoto permanece incerto.")
            logger.log(LogLevel.WARNING, LogCategory.SYNC, "CASH_CONFIRMATION_UNCERTAIN", "A confirmação ficou incerta; o UUID durável será preservado.", trace,
                status = LogStatus.PENDING, error = if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) null else error,
                errorCode = error.diagnosticCode(), syncStatus = LogSyncStatus.PENDING)
            throw error
        }
        check(preferences.edit().remove(key(op)).commit()) { "Operação confirmada. Confira a pendência para concluir a confirmação local." }
        refreshPending()
    }

    private suspend fun executeCashTransaction(op: JSONObject) {
        val id = op.getString("id")
        val type = CashEventType.valueOf(op.getString("type"))
        val sessionId = if (type == CashEventType.ABERTURA) id else op.getString("session")
        val sessionRef = db.collection("CaixaSessoes").document(sessionId)
        val deviceRef = db.collection("CaixasDispositivos").document(deviceId)
        val eventRef = db.collection("MovimentosCaixa").document(id)
        if (type == CashEventType.FECHAMENTO) db.waitForPendingWrites().await()
        val trace = logger.repositoryTrace(LogCategory.DATABASE, "DATABASE_TRANSACTION", "FirestoreCashControlRepository", "executeCashTransaction", id)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        db.runTransaction { transaction ->
            val attempt = attempts.incrementAndGet()
            logger.log(if (attempt > 1) LogLevel.WARNING else LogLevel.INFO, LogCategory.DATABASE,
                if (attempt > 1) "DATABASE_TRANSACTION_RETRY" else "DATABASE_TRANSACTION_STARTED", "Execução da função de transação pelo Firestore.", trace,
                status = if (attempt > 1) LogStatus.RETRY else LogStatus.STARTED, metadata = mapOf("attempt" to attempt.toString()))
            val existing = transaction.get(eventRef)
            if (existing.exists()) {
                check(existing.getString("operador_id") == op.getString("operator") && existing.getString("tipo") == type.name &&
                    existing.getString("caixa_id") == sessionId && existing.getLong("amount_cents") == op.getLong("amount")) { "Comprovante incompatível." }
            } else {
                val device = transaction.get(deviceRef)
                val current = transaction.get(sessionRef)
                val amount = op.getLong("amount")
                val eventFields = eventFields(op, sessionId, amount).toMutableMap()
                if (type == CashEventType.ABERTURA) {
                    check(device.getString("active_session_id").isNullOrBlank() && !current.exists()) { "Este aparelho já possui caixa aberto. Feche o caixa atual primeiro." }
                    transaction.set(sessionRef, mapOf(
                        "id" to id, "aparelho_id" to deviceId, "operador_id" to op.getString("operator"), "operador_nome" to op.getString("name"),
                        "dataHora" to time(op), "status" to "OPEN", "opening_cents" to amount, "expected_cents" to amount,
                        "cash_sales_cents" to 0L, "cash_receipts_cents" to 0L, "supply_cents" to 0L, "withdrawal_cents" to 0L,
                        "refund_cents" to 0L, "revision" to 0L, "last_event_id" to id, "last_event_type" to type.name,
                    ))
                    transaction.set(deviceRef, mapOf("id" to deviceId, "active_session_id" to id, "operador_id" to op.getString("operator")))
                } else {
                    val cash = session(current)
                    check(cash.status == CashSessionStatus.OPEN && cash.deviceId == deviceId && cash.operatorId == op.getString("operator") && device.getString("active_session_id") == sessionId) {
                        "O caixa não está aberto para este operador e aparelho. Atualize a tela."
                    }
                    val changes = mutableMapOf<String, Any>("revision" to Math.addExact(cash.revision, 1), "last_event_id" to id, "last_event_type" to type.name)
                    when (type) {
                        CashEventType.SANGRIA -> {
                            require(amount <= cash.expectedCents) { "A sangria supera o dinheiro esperado no caixa." }
                            changes["withdrawal_cents"] = Math.addExact(cash.withdrawalCents, amount)
                            changes["expected_cents"] = cash.expectedCents - amount
                        }
                        CashEventType.SUPRIMENTO -> {
                            changes["supply_cents"] = Math.addExact(cash.supplyCents, amount)
                            changes["expected_cents"] = Math.addExact(cash.expectedCents, amount)
                        }
                        CashEventType.FECHAMENTO -> {
                            require(cash.revision == op.getLong("revision")) { "O caixa recebeu uma nova operação. Confira o saldo atualizado e faça uma nova contagem antes de fechar." }
                            changes["status"] = "CLOSED"
                            changes["closed_at"] = time(op)
                            changes["counted_cents"] = amount
                            changes["difference_cents"] = CashAccounting.difference(amount, cash.expectedCents)
                            eventFields["expected_cents"] = cash.expectedCents
                            eventFields["expected_revision"] = cash.revision
                            transaction.set(deviceRef, mapOf("id" to deviceId, "active_session_id" to "", "operador_id" to ""))
                        }
                        else -> error("Tipo de operação inválido.")
                    }
                    transaction.update(sessionRef, changes)
                }
                transaction.set(eventRef, eventFields)
            }
            Unit
        }.await()
    }

    private suspend fun executeReversal(op: JSONObject) {
        requireReversalPermission()
        val saleId = op.getString("sale")
        val reversalRef = db.collection("Estornos").document(saleId)
        val existing = reversalRef.get(Source.SERVER).await()
        if (existing.exists()) {
            check(existing.getString("id") == op.getString("id") && existing.getString("operador_id") == op.getString("operator")) { "Esta venda já foi estornada por outra operação." }
            return
        }
        val preview = previewReversal(saleId)
        val refunds = paymentMap(op.getJSONObject("refunds").let { json -> json.keys().asSequence().associateWith { json.getLong(it) } })
        check(preview.settledCents == op.getLong("settled") && preview.cancelledReceivableCents == op.getLong("cancelled") && preview.refundByMethod == refunds) {
            "Os recebimentos desta venda mudaram. Confira os novos valores e confirme novamente a devolução."
        }
        val current = currentSession()
        check(current.id == op.getString("session")) { "O caixa da devolução mudou. Confira a sessão atual." }
        check(preview.cashRefundCents <= current.expectedCents) { "O caixa não tem dinheiro suficiente para a devolução." }
        val sale = preview.sale
        val batch = db.batch()
        batch.set(reversalRef, mapOf(
            "id" to op.getString("id"), "venda_id" to saleId, "caixa_id" to current.id, "aparelho_id" to deviceId,
            "dataHora" to time(op), "operador_id" to op.getString("operator"), "operador_nome" to op.getString("name"), "motivo" to op.getString("reason"),
            "refund_by_method_cents" to refunds.mapKeys { it.key.name }, "expected_settled_cents" to preview.settledCents,
            "cancelled_receivable_cents" to preview.cancelledReceivableCents, "original_total_cents" to sale.totalCents, "original_profit_cents" to sale.profitCents,
        ))
        sale.items.forEachIndexed { index, item ->
            batch.update(db.collection("Produtos").document(item.productId), mapOf(
                "estoque_atual" to FieldValue.increment(item.stockQuantity.toLong()), "ultimo_estorno" to saleId, "indice_ultimo_estorno" to index,
            ))
        }
        if (sale.receivableCents > 0) {
            batch.update(db.collection("ContasReceber").document(saleId), mapOf(
                "cancelled_cents" to preview.cancelledReceivableCents, "estorno_id" to saleId, "closed" to true,
            ))
            batch.update(db.collection("Clientes").document(sale.customerId), mapOf(
                "balance_cents" to FieldValue.increment(-preview.cancelledReceivableCents), "last_credit_event_id" to saleId, "last_credit_event_type" to "ESTORNO",
            ))
        }
        batch.update(db.collection("CaixaSessoes").document(current.id), mapOf(
            "expected_cents" to FieldValue.increment(-preview.cashRefundCents), "refund_cents" to FieldValue.increment(preview.cashRefundCents),
            "revision" to FieldValue.increment(1L), "last_event_id" to saleId, "last_event_type" to "ESTORNO",
        ))
        batch.set(db.collection("MovimentosCaixa").document(op.getString("id")), eventFields(op, current.id, preview.cashRefundCents) + ("venda_id" to saleId))
        // Rules validate the immutable reversal + original stock deltas, cash session OPEN,
        // receivable's unchanged settled total and each refund method against the account.
        // Thus a competing receipt, close, or second reversal rejects the whole batch.
        try { batch.commit().await() } catch (error: FirebaseFirestoreException) {
            // Another retry of the exact batch may have committed while this device reconnected.
            val confirmed = runCatching { reversalRef.get(Source.SERVER).await() }.getOrNull()
            if (confirmed?.getString("id") != op.getString("id")) throw error
        }
    }

    private suspend fun currentSession(): CashSession {
        val user = auth.currentUser ?: error("Entre na conta para operar o caixa.")
        val device = db.collection("CaixasDispositivos").document(deviceId).get(Source.SERVER).await()
        val id = device.getString("active_session_id").orEmpty()
        check(id.isNotBlank()) { "Abra o caixa deste aparelho antes de continuar." }
        val cash = session(db.collection("CaixaSessoes").document(id).get(Source.SERVER).await())
        check(cash.status == CashSessionStatus.OPEN && cash.deviceId == deviceId && cash.operatorId == user.uid) { "O caixa está vinculado a outro operador. Entre na conta responsável para fechá-lo." }
        return cash
    }

    private suspend fun requireReversalPermission() {
        val uid = auth.currentUser?.uid ?: error("Entre na conta para estornar uma venda.")
        val profile = withTimeout(15_000) { db.collection("Perfis").document(uid).get(Source.SERVER).await() }
        check(profile.getBoolean("active") == true && (profile.getString("role") == "admin" || profile.getBoolean("permissions.reverseSales") == true)) {
            "Seu perfil não tem permissão para estornar vendas."
        }
    }

    private fun eventFields(op: JSONObject, sessionId: String, amount: Long): Map<String, Any> = mapOf(
        "id" to op.getString("id"), "caixa_id" to sessionId, "aparelho_id" to deviceId, "tipo" to op.getString("type"),
        "dataHora" to time(op), "amount_cents" to amount, "motivo" to op.optString("reason", ""),
        "operador_id" to op.getString("operator"), "operador_nome" to op.getString("name"),
    )

    private fun <T> observe(query: Query, mapper: (DocumentSnapshot) -> T): Flow<Feed<List<T>>> = callbackFlow {
        val diagnostic = QueryDiagnostics(logger, logger.repositoryTrace(LogCategory.DATABASE, "CASH_QUERY", "FirestoreCashControlRepository", "observe"))
        val registration = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) { diagnostic.failed(error); close(error) }
            else if (snapshot != null) runCatching {
                diagnostic.received(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                Feed(snapshot.documents.map(mapper), snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites()) }
                .onSuccess { trySend(it) }.onFailure { diagnostic.failed(it); close(it) }
        }
        awaitClose { registration.remove() }
    }

    private fun session(d: DocumentSnapshot): CashSession {
        check(d.exists()) { "Sessão de caixa não encontrada." }
        return CashSession(d.id, d.getString("aparelho_id").orEmpty(), d.getString("operador_id").orEmpty(), d.getString("operador_nome").orEmpty(),
            instant(d, "dataHora") ?: error("Caixa sem data."), CashSessionStatus.valueOf(d.getString("status") ?: "OPEN"),
            d.getLong("opening_cents") ?: 0L, d.getLong("expected_cents") ?: 0L, d.getLong("cash_sales_cents") ?: 0L,
            d.getLong("cash_receipts_cents") ?: 0L, d.getLong("supply_cents") ?: 0L, d.getLong("withdrawal_cents") ?: 0L,
            d.getLong("refund_cents") ?: 0L, d.getLong("revision") ?: 0L, instant(d, "closed_at"), d.getLong("counted_cents"), d.getLong("difference_cents"))
    }
    private fun event(d: DocumentSnapshot) = CashEvent(d.id, d.getString("caixa_id").orEmpty(), CashEventType.valueOf(d.getString("tipo") ?: "ABERTURA"),
        instant(d, "dataHora") ?: error("Operação sem data."), d.getLong("amount_cents") ?: 0L, d.getString("motivo").orEmpty(),
        d.getString("operador_id").orEmpty(), d.getString("operador_nome").orEmpty())
    private fun reversal(d: DocumentSnapshot) = SaleReversal(d.getString("id") ?: d.id, d.getString("venda_id") ?: d.id, d.getString("caixa_id").orEmpty(),
        instant(d, "dataHora") ?: error("Estorno sem data."), d.getString("motivo").orEmpty(), d.getString("operador_id").orEmpty(), d.getString("operador_nome").orEmpty(),
        paymentMap(d.get("refund_by_method_cents")), d.getLong("expected_settled_cents") ?: 0L, d.getLong("cancelled_receivable_cents") ?: 0L,
        d.getLong("original_total_cents") ?: 0L, d.getLong("original_profit_cents") ?: 0L)
    private fun paymentMap(raw: Any?): Map<PaymentMethod, Long> = PaymentMethod.entries.filter { it != PaymentMethod.FIADO }.associateWith { method ->
        ((raw as? Map<*, *>)?.get(method.name) as? Number)?.toLong() ?: 0L
    }
    private fun key(op: JSONObject) = "pending:${op.getString("operator")}:${op.getString("id")}"
    private fun receiptTrace(op: JSONObject, function: String): TraceContext {
        val id = op.getString("id")
        return (op.optString("diagnostic_trace_id").takeIf { it.isNotBlank() }?.let {
            logger.bindOperation(TraceContext(traceId = it, operationId = id), id)
        } ?: logger.forOperation(id, LogCategory.CASH_REGISTER)).at("FirestoreCashControlRepository", function)
    }
    private fun time(op: JSONObject) = timestamp(Instant.ofEpochMilli(op.getLong("time")))
    private fun timestamp(instant: Instant) = Timestamp(instant.epochSecond, instant.nano)
    private fun instant(d: DocumentSnapshot, field: String) = d.getTimestamp(field)?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
    private fun uuid(id: String) { require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Informe o identificador completo do comprovante." } }
    private fun description(type: String) = when (type) { "ABERTURA" -> "Abertura de caixa"; "FECHAMENTO" -> "Fechamento de caixa"; "SANGRIA" -> "Sangria"; "SUPRIMENTO" -> "Suprimento"; else -> "Estorno de venda" }
    private companion object { val deviceLock = Any() }
}

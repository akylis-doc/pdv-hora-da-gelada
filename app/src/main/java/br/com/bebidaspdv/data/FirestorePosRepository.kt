package br.com.bebidaspdv.data

import android.content.Context
import android.content.SharedPreferences
import android.annotation.SuppressLint
import br.com.bebidaspdv.core.Feed
import br.com.bebidaspdv.core.BusinessLimits
import br.com.bebidaspdv.core.Movement
import br.com.bebidaspdv.core.MovementType
import br.com.bebidaspdv.core.OperationStatus
import br.com.bebidaspdv.core.PosRepository
import br.com.bebidaspdv.core.Product
import br.com.bebidaspdv.core.SyncState
import br.com.bebidaspdv.core.Customer
import br.com.bebidaspdv.core.Expense
import br.com.bebidaspdv.core.DebtPayment
import br.com.bebidaspdv.core.DebtSettlementRejected
import br.com.bebidaspdv.core.PaymentMethod
import br.com.bebidaspdv.core.CalculatePayment
import br.com.bebidaspdv.core.ReceivableAccount
import br.com.bebidaspdv.core.ReceiptAccounting
import br.com.bebidaspdv.core.CashAccounting
import br.com.bebidaspdv.core.PurchaseCost
import br.com.bebidaspdv.core.logging.*
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.time.YearMonth
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Firestore owns the durable write queue; receipts only expose confirmation/rejection to the operator. */
class FirestorePosRepository(
    private val db: FirebaseFirestore,
    context: Context,
    private val logger: AppLogger = NoOpLogger,
) : PosRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = OperationLedger(context, logger)
    private val mutableOperations = MutableStateFlow(ledger.read())
    override val operations: StateFlow<List<OperationStatus>> = mutableOperations.asStateFlow()
    private val localWriteCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val mutableLocalWrites = MutableStateFlow(false)
    override val pendingLocalWrites = mutableLocalWrites.asStateFlow()
    private val mutex = Mutex()
    private val monitors = ConcurrentHashMap<String, ListenerRegistration>()
    private val writeDiagnostics = ConcurrentHashMap<String, LocalWriteDiagnostics>()
    private val settlementPreferences = context.getSharedPreferences("pending_debt_payments", Context.MODE_PRIVATE)
    private val settlementMutex = Mutex()
    private val mutablePendingDebts = MutableStateFlow<List<DebtPayment>>(emptyList())
    override val pendingDebtPayments = mutablePendingDebts.asStateFlow()
    private val settlementListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPendingDebts() }

    init {
        settlementPreferences.registerOnSharedPreferenceChangeListener(settlementListener)
        FirebaseAuth.getInstance().addAuthStateListener { refreshPendingDebts() }
        refreshPendingDebts()
        mutableOperations.value.filter { it.state == SyncState.PENDING }.forEach { status ->
            monitorMovement(status.id, recovered = true, localAcceptance = null)
        }
    }

    private fun refreshPendingDebts() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { mutablePendingDebts.value = emptyList(); return }
        val prefix = "${user.uid}:"
        mutablePendingDebts.value = settlementPreferences.all.filterKeys { it.startsWith(prefix) }.mapNotNull { (key, raw) ->
            runCatching {
                val fields = org.json.JSONObject(raw as String)
                DebtPayment(fields.getString("id"), key.removePrefix(prefix), fields.getString("customer"),
                    Instant.ofEpochMilli(fields.optLong("occurredAt", System.currentTimeMillis())),
                    fields.getLong("amount"), PaymentMethod.valueOf(fields.getString("method")), user.uid,
                    user.email ?: user.displayName ?: user.uid,
                    fields.optString("session"), fields.optString("device"))
            }.getOrNull()
        }
    }

    override fun observeProducts(): Flow<Feed<List<Product>>> = callbackFlow {
        val diagnostic = QueryDiagnostics(logger, logger.repositoryTrace(LogCategory.PRODUCT, "PRODUCT_QUERY", "FirestorePosRepository", "observeProducts"))
        val registration = db.collection("Produtos").orderBy("nome")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) { diagnostic.failed(error); close(error); return@addSnapshotListener }
                if (snapshot != null) runCatching {
                    diagnostic.received(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                    Feed(snapshot.documents.map(FirestoreMapper::product),
                        snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                }.onSuccess { trySend(it) }.onFailure { diagnostic.failed(it); close(it) }
            }
        awaitClose { registration.remove() }
    }

    override fun observeMovements(month: YearMonth, zone: ZoneId): Flow<Feed<List<Movement>>> = callbackFlow {
        val diagnostic = QueryDiagnostics(logger, logger.repositoryTrace(LogCategory.SALE, "SALE_QUERY", "FirestorePosRepository", "observeMovements"))
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val registration = db.collection("Vendas")
            .whereGreaterThanOrEqualTo("dataHora", Timestamp(start.epochSecond, start.nano))
            .whereLessThan("dataHora", Timestamp(end.epochSecond, end.nano))
            .orderBy("dataHora", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) { diagnostic.failed(error); close(error); return@addSnapshotListener }
                if (snapshot != null) runCatching {
                    diagnostic.received(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                    Feed(snapshot.documents.map(FirestoreMapper::movement),
                        snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                }.onSuccess { trySend(it) }.onFailure { diagnostic.failed(it); close(it) }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun submit(movement: Movement): Unit = withContext(Dispatchers.IO) {
        val category = if (movement.type == MovementType.SAIDA) LogCategory.SALE else LogCategory.STOCK
        val trace = logger.repositoryTrace(category, "MOVEMENT_SUBMIT", "FirestorePosRepository", "submit", movement.id, movement.id)
        logger.log(LogLevel.INFO, category, "DATABASE_BATCH_PREPARING", "Preparando movimentação e deltas de estoque atômicos.", trace,
            status = LogStatus.STARTED, metadata = mapOf("item_count" to movement.items.size.toString(), "movement_type" to movement.type.name))
        val user = FirebaseAuth.getInstance().currentUser ?: error("Entre na conta antes de registrar uma venda.")
        require(movement.operatorId.isEmpty() || movement.operatorId == user.uid) { "A conta mudou. Confira a venda antes de continuar." }
        val signedMovement = movement.copy(operatorId = user.uid, operatorName = movement.operatorName.ifBlank { user.email ?: user.uid })
        if (movement.type == MovementType.SAIDA) {
            require(UUID.fromString(movement.cashSessionId).toString() == movement.cashSessionId) { "Abra o caixa antes de vender." }
            require(UUID.fromString(movement.deviceId).toString() == movement.deviceId)
        }
        validateMovement(signedMovement)
        val accepted = CompletableDeferred<Unit>()
        mutex.withLock {
            val existing = mutableOperations.value.firstOrNull { it.id == movement.id }
            if (existing != null) {
                logger.log(LogLevel.INFO, category, "MOVEMENT_DUPLICATE_REQUEST", "Comprovante já registrado; preservando a operação existente.", trace,
                    syncStatus = if (existing.state == SyncState.CONFIRMED) LogSyncStatus.SYNCED else LogSyncStatus.PENDING)
                check(existing.state != SyncState.REJECTED) { "Este comprovante foi rejeitado. Confira-o antes de criar outra movimentação." }
                return@withContext
            }
            // Persist the receipt BEFORE enqueueing; a crash in this window reconciles as not confirmed.
            ledger.rememberTrace(trace)
            updateStatusLocked(OperationStatus(movement.id, SyncState.PENDING,
                "Aguardando sincronização\n${movementSummary(signedMovement)}"))
            val diagnostic = LocalWriteDiagnostics(logger, trace, category,
                if (movement.type == MovementType.SAIDA) "SALE" else "STOCK", "FirestorePosRepository")
            writeDiagnostics[movement.id] = diagnostic
            monitorMovement(movement.id, recovered = false, localAcceptance = accepted)
            val batch = db.batch()
            batch.set(db.collection("Vendas").document(movement.id), FirestoreMapper.movementFields(signedMovement))
            if (signedMovement.receivableCents > 0) {
                batch.set(db.collection("ContasReceber").document(movement.id), mapOf(
                    "venda_id" to movement.id, "cliente_id" to signedMovement.customerId,
                    "original_cents" to signedMovement.receivableCents, "settled_cents" to 0L,
                    "ultimo_recebimento" to "",
                    "settled_by_method_cents" to PaymentMethod.entries.filter { it != PaymentMethod.FIADO }.associate { it.name to 0L },
                    "closed" to false, "due_epoch_day" to signedMovement.dueEpochDay,
                ))
                batch.update(db.collection("Clientes").document(signedMovement.customerId), mapOf(
                    "balance_cents" to FieldValue.increment(signedMovement.receivableCents),
                    "last_credit_event_id" to signedMovement.id, "last_credit_event_type" to "VENDA"))
            }
            if (signedMovement.type == MovementType.SAIDA) batch.update(db.collection("CaixaSessoes").document(signedMovement.cashSessionId), mapOf(
                "expected_cents" to FieldValue.increment(CashAccounting.saleCash(signedMovement.payments, signedMovement.changeCents)),
                "cash_sales_cents" to FieldValue.increment(CashAccounting.saleCash(signedMovement.payments, signedMovement.changeCents)),
                "revision" to FieldValue.increment(1), "last_event_id" to signedMovement.id, "last_event_type" to "VENDA"))
            movement.items.forEachIndexed { index, item ->
                val delta = item.stockQuantity.toLong() * if (movement.type == MovementType.SAIDA) -1L else 1L
                batch.update(db.collection("Produtos").document(item.productId), mapOf(
                    "estoque_atual" to FieldValue.increment(delta),
                    "ultima_movimentacao" to movement.id,
                    "indice_ultima_movimentacao" to index,
                ))
            }
            try {
                batch.commit()
                    .addOnSuccessListener {
                        diagnostic.confirmed()
                        accepted.complete(Unit)
                        recordStatus(OperationStatus(movement.id, SyncState.CONFIRMED, "Sincronizado"))
                    }
                    .addOnFailureListener { error ->
                        diagnostic.rejected(error)
                        accepted.completeExceptionally(error)
                        recordStatus(OperationStatus(movement.id, SyncState.REJECTED,
                            "Movimentação rejeitada pelo servidor: ${error.localizedMessage}"))
                    }
                diagnostic.queued()
            } catch (error: Exception) {
                diagnostic.rejected(error)
                updateStatusLocked(OperationStatus(movement.id, SyncState.REJECTED, error.localizedMessage))
                monitors.remove(movement.id)?.remove()
                accepted.completeExceptionally(error)
            }
        }
        // commit() completes at the server and would hang offline. Local pending metadata releases UI.
        accepted.await()
    }

    override suspend fun saveProduct(product: Product): Unit = withContext(Dispatchers.IO) {
        require(UUID.fromString(product.id).toString() == product.id)
        require(product.name.isNotBlank() && product.name.length <= 120)
        require(product.barcode.length <= 80)
        require(product.costCents in 0..BusinessLimits.MAX_MONEY_CENTS && product.saleCents in 0..BusinessLimits.MAX_MONEY_CENTS)
        require(product.active) { "Produtos excluídos não podem ser alterados." }
        require(product.imageKey.matches(Regex("[a-z0-9_-]{0,100}"))) { "Nome de imagem inválido." }
        require(product.lowStockThreshold in 0..BusinessLimits.MAX_QUANTITY && product.packageSize in 1..BusinessLimits.MAX_QUANTITY)
        require(product.packageSaleCents in 0..BusinessLimits.MAX_MONEY_CENTS)
        require(product.category in setOf("BEBIDA", "ALIMENTO", "CIGARRO", "OUTROS"))
        require(product.unitLabel in setOf("UNIDADE", "MACO", "PACOTE"))
        require(product.packageCostCents in 0..BusinessLimits.MAX_MONEY_CENTS)
        require(!product.purchaseByPackage || (product.packageSize > 1 && product.costCents == PurchaseCost.unitCents(product.packageCostCents, product.packageSize)))
        val reference = db.collection("Produtos").document(product.id)
        val fields = FirestoreMapper.productFields(product) + ("estoque_atual" to FieldValue.increment(0L))
        awaitLocalProductWrite(reference) { reference.set(fields, SetOptions.merge()) }
    }

    override suspend fun deleteProduct(product: Product): Unit = withContext(Dispatchers.IO) {
        val reference = db.collection("Produtos").document(product.id)
        awaitLocalProductWrite(reference) { reference.update("ativo", false) }
    }

    private fun <T> observe(query: Query, mapper: (com.google.firebase.firestore.DocumentSnapshot) -> T): Flow<Feed<List<T>>> = callbackFlow {
        val diagnostic = QueryDiagnostics(logger, logger.repositoryTrace(LogCategory.DATABASE, "DATABASE_OBSERVE", "FirestorePosRepository", "observe"))
        val registration = query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) { diagnostic.failed(error); close(error); return@addSnapshotListener }
            if (snapshot != null) runCatching {
                diagnostic.received(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
                Feed(snapshot.documents.map(mapper), snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites())
            }.onSuccess { trySend(it) }.onFailure { diagnostic.failed(it); close(it) }
        }
        awaitClose { registration.remove() }
    }

    override fun observeCustomers() = observe(db.collection("Clientes").orderBy("nome"), FirestoreMapper::customer)
    override suspend fun saveCustomer(customer: Customer): Unit = withContext(Dispatchers.IO) {
        require(UUID.fromString(customer.id).toString() == customer.id)
        require(customer.name.isNotBlank() && customer.name.length <= 120 && customer.phone.length <= 30) { "Confira o nome e o telefone do cliente." }
        val reference = db.collection("Clientes").document(customer.id)
        require(customer.creditLimitCents in 0..BusinessLimits.MAX_MONEY_CENTS)
        awaitLocalProductWrite(reference) { reference.set(FirestoreMapper.customerFields(customer) +
            ("balance_cents" to FieldValue.increment(0)), SetOptions.merge()) }
    }

    override fun observeExpenses(month: YearMonth, zone: ZoneId): Flow<Feed<List<Expense>>> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        return observe(db.collection("Despesas")
            .whereGreaterThanOrEqualTo("dataHora", Timestamp(start.epochSecond, start.nano))
            .whereLessThan("dataHora", Timestamp(end.epochSecond, end.nano)).orderBy("dataHora", Query.Direction.DESCENDING), FirestoreMapper::expense)
    }
    override suspend fun saveExpense(expense: Expense): Unit = withContext(Dispatchers.IO) {
        require(UUID.fromString(expense.id).toString() == expense.id)
        require(expense.description.isNotBlank() && expense.description.length <= 200 && expense.category.isNotBlank() && expense.category.length <= 40)
        require(expense.amountCents in 1..BusinessLimits.MAX_MONEY_CENTS)
        val user = FirebaseAuth.getInstance().currentUser ?: error("Entre na conta antes de registrar uma despesa.")
        val signed = expense.copy(operatorId = user.uid, operatorName = user.email ?: user.displayName ?: user.uid)
        val reference = db.collection("Despesas").document(expense.id)
        awaitLocalProductWrite(reference) { reference.set(FirestoreMapper.expenseFields(signed)) }
    }
    override fun observeCreditSales() = observe(db.collection("Vendas").whereGreaterThan("receivable_cents", 0), FirestoreMapper::movement)
    override fun observeDebtPayments() = observe(db.collection("Recebimentos").orderBy("dataHora", Query.Direction.DESCENDING), FirestoreMapper::debtPayment)
    override fun observeDebtPayments(month: YearMonth, zone: ZoneId): Flow<Feed<List<DebtPayment>>> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        return observe(db.collection("Recebimentos").whereGreaterThanOrEqualTo("dataHora", Timestamp(start.epochSecond, 0))
            .whereLessThan("dataHora", Timestamp(end.epochSecond, 0)).orderBy("dataHora", Query.Direction.DESCENDING), FirestoreMapper::debtPayment)
    }
    override fun observeReceivables() = observe(db.collection("ContasReceber").whereEqualTo("closed", false), FirestoreMapper::receivable)
    override suspend fun loadMovements(ids: List<String>): List<Movement> = ids.distinct().chunked(30).flatMap { chunk ->
        db.collection("Vendas").whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk).get().await().documents.map(FirestoreMapper::movement)
    }
    override suspend fun loadReceivables(ids: List<String>): List<ReceivableAccount> = ids.distinct().chunked(30).flatMap { chunk ->
        db.collection("ContasReceber").whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk).get().await().documents.map(FirestoreMapper::receivable)
    }

    @SuppressLint("UseKtx") // commit's Boolean is required for durable receipt IDs before submission.
    override suspend fun settleDebt(payment: DebtPayment): Unit = withContext(Dispatchers.IO) {
        val initialTrace = logger.repositoryTrace(LogCategory.PAYMENT, "PAYMENT_REQUEST", "FirestorePosRepository", "settleDebt", payment.id, payment.saleId)
        settlementMutex.withLock {
            require(UUID.fromString(payment.id).toString() == payment.id && UUID.fromString(payment.saleId).toString() == payment.saleId)
            require(payment.amountCents in 1..BusinessLimits.MAX_MONEY_CENTS && payment.method != PaymentMethod.FIADO)
            val user = FirebaseAuth.getInstance().currentUser ?: error("Entre na conta antes de receber um pagamento.")
            require(payment.operatorId.isEmpty() || payment.operatorId == user.uid) { "A conta mudou. Confira o recebimento." }
            val key = "${user.uid}:${payment.saleId}"
            val pending = settlementPreferences.getString(key, null)?.let { org.json.JSONObject(it) }
            val trace = pending?.optString("diagnostic_trace_id")?.takeIf { it.isNotBlank() }?.let {
                logger.bindOperation(initialTrace.copy(traceId = it), payment.id)
            } ?: initialTrace
            logger.log(LogLevel.INFO, LogCategory.PAYMENT, if (pending == null) "PAYMENT_REQUEST" else "SYNC_RETRY",
                if (pending == null) "Recebimento solicitado para confirmação online." else "Conferência de recebimento pendente solicitada pelo operador.", trace,
                status = if (pending == null) LogStatus.STARTED else LogStatus.RETRY,
                metadata = mapOf("payment_method" to payment.method.name))
            // Never reinterpret a new payment as a retry, even if its amount and method match.
            // Only the explicitly recovered receipt may reconcile an uncertain prior request.
            if (pending != null && (pending.getString("id") != payment.id || pending.getLong("amount") != payment.amountCents || pending.getString("method") != payment.method.name || pending.getString("customer") != payment.customerId)) {
                throw DebtSettlementRejected("Há um recebimento anterior sem confirmação. Use Conferir pendência na aba Fiado antes de registrar outro pagamento para esta venda.")
            }
            val samePending = pending?.takeIf { it.getLong("amount") == payment.amountCents && it.getString("method") == payment.method.name && it.getString("customer") == payment.customerId }
            val signed = payment.copy(id = samePending?.getString("id") ?: payment.id,
                occurredAt = Instant.ofEpochMilli(samePending?.optLong("occurredAt", payment.occurredAt.toEpochMilli()) ?: payment.occurredAt.toEpochMilli()),
                operatorId = user.uid, operatorName = payment.operatorName.ifBlank { user.email ?: user.uid },
                cashSessionId = samePending?.optString("session", payment.cashSessionId) ?: payment.cashSessionId,
                deviceId = samePending?.optString("device", payment.deviceId) ?: payment.deviceId)
            val account = db.collection("ContasReceber").document(payment.saleId)
            // A server-only read rejects offline attempts before enqueueing a transaction.
            try { logger.repositoryStep(trace, LogCategory.FIREBASE, "PAYMENT_CONNECTION_CHECK", "FirestorePosRepository", "settleDebt.connection", slowAfterMs = 2_000) {
                withTimeout(15_000) { account.get(Source.SERVER).await() }
            } }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                throw DebtSettlementRejected("O recebimento precisa de conexão com o servidor. Conecte-se e tente novamente.").apply { initCause(error) }
            }
            val record = org.json.JSONObject().put("id", signed.id).put("amount", signed.amountCents).put("method", signed.method.name).put("customer", signed.customerId).put("occurredAt", signed.occurredAt.toEpochMilli())
                .put("session", signed.cashSessionId).put("device", signed.deviceId).put("diagnostic_trace_id", trace.traceId)
            check(settlementPreferences.edit().putString(key, record.toString()).commit()) { "Não foi possível guardar o comprovante do recebimento." }
            logger.log(LogLevel.INFO, LogCategory.DATABASE, "PAYMENT_REQUEST_SAVED_LOCAL", "Solicitação de recebimento persistida; saldo ainda depende da confirmação remota.",
                trace, status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.PENDING)
            refreshPendingDebts()
            val receipt = db.collection("Recebimentos").document(signed.id)
            val step = logger.startStep(trace, LogCategory.DATABASE, "PAYMENT_TRANSACTION", "FirestorePosRepository", "settleDebt.transaction", slowAfterMs = 2_000)
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            try { db.runTransaction { transaction ->
                val attempt = attempts.incrementAndGet()
                logger.log(if (attempt > 1) LogLevel.WARNING else LogLevel.INFO, LogCategory.DATABASE,
                    if (attempt > 1) "DATABASE_TRANSACTION_RETRY" else "DATABASE_TRANSACTION_STARTED", "Execução de recebimento pelo Firestore.", trace,
                    status = if (attempt > 1) LogStatus.RETRY else LogStatus.STARTED, metadata = mapOf("attempt" to attempt.toString()))
                val existing = transaction.get(receipt)
                if (existing.exists()) {
                    check(existing.getString("venda_id") == signed.saleId && existing.getLong("amount_cents") == signed.amountCents && existing.getString("metodo") == signed.method.name && existing.getString("cliente_id") == signed.customerId) { "Comprovante de recebimento incompatível." }
                } else {
                    val balance = transaction.get(account)
                    check(balance.exists() && balance.getString("cliente_id") == signed.customerId) { "Conta a receber não encontrada para este cliente." }
                    val original = balance.getLong("original_cents") ?: error("Saldo inválido.")
                    val settled = balance.getLong("settled_cents") ?: 0L
                    check(balance.getBoolean("closed") == false) { "Conta encerrada ou atualização dos dados pendente." }
                    require(signed.amountCents <= original - settled) { "O valor ultrapassa o saldo restante. Atualize a lista de fiados." }
                    val sale = FirestoreMapper.movement(transaction.get(db.collection("Vendas").document(signed.saleId)))
                    require(signed.cashSessionId.isNotBlank()) { "Abra o caixa antes de receber." }
                    val sessionRef = db.collection("CaixaSessoes").document(signed.cashSessionId)
                    val session = transaction.get(sessionRef)
                    check(session.getString("status") == "OPEN" && session.getString("operador_id") == signed.operatorId &&
                        session.getString("aparelho_id") == signed.deviceId) { "Abra o caixa neste aparelho para receber." }
                    val customerRef = db.collection("Clientes").document(signed.customerId)
                    val customer = transaction.get(customerRef)
                    val customerBalance = customer.getLong("balance_cents") ?: error("Atualize os saldos dos clientes antes de receber.")
                    check(customerBalance >= signed.amountCents) { "Saldo do cliente inconsistente. Confira a atualização dos dados." }
                    val methods = (balance.get("settled_by_method_cents") as? Map<*, *>) ?: error("Atualize os recebimentos antigos antes de continuar.")
                    val newMethods = PaymentMethod.entries.filter { it != PaymentMethod.FIADO }.associate { method ->
                        method.name to (((methods[method.name] as? Number)?.toLong() ?: 0L) + if (method == signed.method) signed.amountCents else 0L)
                    }
                    val recognized = signed.copy(costRecognizedCents = ReceiptAccounting.receiptCost(sale, settled, signed.amountCents),
                        paidBeforeCents = sale.totalCents - sale.receivableCents + settled)
                    transaction.set(receipt, FirestoreMapper.debtPaymentFields(recognized))
                    transaction.update(account, mapOf("settled_cents" to settled + signed.amountCents, "ultimo_recebimento" to signed.id,
                        "settled_by_method_cents" to newMethods, "closed" to (settled + signed.amountCents == original)))
                    transaction.update(customerRef, mapOf("balance_cents" to customerBalance - signed.amountCents,
                        "last_credit_event_id" to signed.id, "last_credit_event_type" to "RECEBIMENTO"))
                    val cash = if (signed.method == PaymentMethod.CASH) signed.amountCents else 0L
                    transaction.update(sessionRef, mapOf("expected_cents" to FieldValue.increment(cash), "cash_receipts_cents" to FieldValue.increment(cash),
                        "revision" to FieldValue.increment(1), "last_event_id" to signed.id, "last_event_type" to "RECEBIMENTO"))
                }
                Unit
            }.await()
                step.finish("Recebimento confirmado pelo servidor.", syncStatus = LogSyncStatus.SYNCED)
                logger.log(LogLevel.INFO, LogCategory.PAYMENT, "PAYMENT_REGISTERED", "Recebimento confirmado pelo servidor.", trace,
                    status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
                logger.log(LogLevel.INFO, LogCategory.SYNC, "PAYMENT_SYNCED", "Comprovante de recebimento sincronizado.", trace,
                    status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
            } catch (error: Exception) {
                val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
                val definitive = causes.any { it is IllegalArgumentException || it is IllegalStateException ||
                    it is FirebaseFirestoreException && it.code in setOf(
                        FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.INVALID_ARGUMENT,
                        FirebaseFirestoreException.Code.FAILED_PRECONDITION, FirebaseFirestoreException.Code.ABORTED,
                    ) }
                if (definitive) {
                    step.fail(error, "Recebimento recusado.", error.diagnosticCode())
                    logger.log(LogLevel.ERROR, LogCategory.PAYMENT, "PAYMENT_FAILED", "Recebimento recusado; nenhum novo comprovante será criado automaticamente.", trace,
                        status = LogStatus.FAILED, error = error, errorCode = error.diagnosticCode(), syncStatus = LogSyncStatus.FAILED)
                    check(settlementPreferences.edit().remove(key).commit()) { "Não foi possível reconciliar o recebimento recusado." }
                    refreshPendingDebts()
                    throw DebtSettlementRejected(causes.last().localizedMessage ?: "O recebimento foi recusado. Confira o saldo e tente novamente.").apply { initCause(error) }
                }
                // Network/cancellation uncertainty retains the UUID for a safe retry after restart.
                step.cancel("Confirmação interrompida; resultado remoto incerto.")
                logger.log(LogLevel.WARNING, LogCategory.SYNC, "PAYMENT_CONFIRMATION_UNCERTAIN", "Comprovante preservado para conferência; resposta remota incerta.", trace,
                    status = LogStatus.PENDING, error = if (error is CancellationException && error !is TimeoutCancellationException) null else error,
                    errorCode = error.diagnosticCode(), syncStatus = LogSyncStatus.PENDING)
                throw error
            }
            check(settlementPreferences.edit().remove(key).commit()) { "Recebimento confirmado; a confirmação local será reconciliada na próxima tentativa." }
            refreshPendingDebts()
        }
    }
    override suspend fun waitForPendingWrites() {
        val trace = logger.repositoryTrace(LogCategory.SYNC, "SYNC_STARTED", "FirestorePosRepository", "waitForPendingWrites")
        // Waiting for connectivity is normal. Measure it without a CPU/database stall watchdog.
        val started = System.nanoTime()
        logger.log(LogLevel.INFO, LogCategory.SYNC, "SYNC_WAIT_PENDING_WRITES", "Aguardando confirmação; a espera pode continuar enquanto o aparelho estiver offline.",
            trace, status = LogStatus.PENDING, syncStatus = LogSyncStatus.PENDING)
        try {
            db.waitForPendingWrites().await()
            logger.log(LogLevel.INFO, LogCategory.SYNC, "SYNC_FINISHED", "Todas as gravações que esta chamada aguardava foram confirmadas.", trace,
                status = LogStatus.SUCCESS, durationMs = (System.nanoTime() - started) / 1_000_000,
                metadata = mapOf("includes_offline_wait" to "true"), syncStatus = LogSyncStatus.SYNCED)
        } catch (failure: Exception) {
            logger.log(LogLevel.WARNING, LogCategory.SYNC, "SYNC_WAIT_INTERRUPTED", "A espera foi interrompida; isso não rejeita as gravações do Firestore.", trace,
                status = LogStatus.PENDING, durationMs = (System.nanoTime() - started) / 1_000_000,
                error = if (failure is CancellationException && failure !is TimeoutCancellationException) null else failure,
                errorCode = failure.diagnosticCode(), syncStatus = LogSyncStatus.PENDING)
            throw failure
        }
    }

    private suspend fun awaitLocalProductWrite(reference: DocumentReference, write: () -> Task<Void>) {
        val trace = logger.repositoryTrace(LogCategory.DATABASE, "DOCUMENT_WRITE", "FirestorePosRepository", "awaitLocalProductWrite",
            operationId = currentLogTrace()?.operationId ?: UUID.randomUUID().toString(), entityId = reference.id)
        val diagnostic = LocalWriteDiagnostics(logger, trace, LogCategory.DATABASE, "DOCUMENT", "FirestorePosRepository", atomicBatch = false)
        val accepted = CompletableDeferred<Unit>()
        synchronized(localWriteCount) {
            localWriteCount.incrementAndGet()
            mutableLocalWrites.value = true
        }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishTracking() {
            if (finished.compareAndSet(false, true)) synchronized(localWriteCount) {
                mutableLocalWrites.value = localWriteCount.decrementAndGet() > 0
            }
        }
        var registration: ListenerRegistration? = null
        try {
        registration = reference.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            // A read error cannot establish that the separately queued write failed.
            // Let the write task report rejection, avoiding a second UUID after an uncertain create.
            if (error != null) logger.log(LogLevel.WARNING, LogCategory.FIREBASE, "LOCAL_WRITE_OBSERVATION_FAILED",
                "Falha na observação; aguardando o resultado independente da gravação.", trace, error = error, errorCode = error.diagnosticCode())
            if (error == null && snapshot?.exists() == true && snapshot.metadata.hasPendingWrites()) {
                diagnostic.localAccepted()
                accepted.complete(Unit)
            }
        }
            write().addOnSuccessListener { diagnostic.confirmed(); finishTracking(); accepted.complete(Unit) }.addOnFailureListener { error ->
                diagnostic.rejected(error)
                finishTracking()
                accepted.completeExceptionally(error)
                // Product-edit failures are visible too, but are not replayed as sales on restart.
                recordStatus(OperationStatus(UUID.randomUUID().toString(), SyncState.REJECTED,
                    "Cadastro ${reference.id} rejeitado: ${error.localizedMessage}"))
            }
            diagnostic.queued()
            accepted.await()
        } catch (error: Exception) {
            // Cancellation of the caller does not cancel a Firestore write already queued.
            if (error !is CancellationException && !accepted.isCompleted) finishTracking()
            if (error !is CancellationException) diagnostic.rejected(error) else diagnostic.cancelWaiting()
            throw error
        } finally {
            registration?.remove()
        }
    }

    private fun monitorMovement(id: String, recovered: Boolean, localAcceptance: CompletableDeferred<Unit>?) {
        val diagnostic = writeDiagnostics.getOrPut(id) {
            val recoveredTrace = ledger.traceId(id)?.let { logger.bindOperation(TraceContext(traceId = it, operationId = id), id) }
                ?: logger.forOperation(id, LogCategory.SYNC, "FirestorePosRepository", "monitorMovement")
            LocalWriteDiagnostics(logger, recoveredTrace.copy(entityId = id).at("FirestorePosRepository", "monitorMovement"),
                LogCategory.SYNC, "MOVEMENT", "FirestorePosRepository", recovered = recovered)
        }
        if (recovered) diagnostic.queued()
        var sawPending = false
        val registration = db.collection("Vendas").document(id)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    // A read error does not prove a write was rejected. commit's callback is authoritative.
                    logger.log(LogLevel.WARNING, LogCategory.FIREBASE, "SYNC_OBSERVATION_FAILED",
                        "Falha ao acompanhar o comprovante; isso não confirma rejeição da gravação.", diagnostic.trace,
                        error = error, errorCode = error.diagnosticCode(), syncStatus = LogSyncStatus.PENDING)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.exists() && snapshot.metadata.hasPendingWrites()) {
                    diagnostic.localAccepted()
                    sawPending = true
                    localAcceptance?.complete(Unit)
                } else if (!snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    if (snapshot.exists() && (recovered || sawPending)) {
                        diagnostic.confirmed()
                        localAcceptance?.complete(Unit)
                        recordStatus(OperationStatus(id, SyncState.CONFIRMED, "Sincronizado"))
                    } else if (!snapshot.exists() && (recovered || sawPending)) {
                        diagnostic.rejected()
                        val message = "Comprovante não confirmado no servidor. Verifique a venda e o estoque antes de refazer."
                        localAcceptance?.completeExceptionally(IllegalStateException(message))
                        recordStatus(OperationStatus(id, SyncState.REJECTED, message))
                    }
                }
            }
        monitors.put(id, registration)?.remove()
    }

    private fun recordStatus(status: OperationStatus) {
        scope.launch {
            try { mutex.withLock { updateStatusLocked(status) } }
            finally {
                if (status.state != SyncState.PENDING) {
                    monitors.remove(status.id)?.remove()
                    writeDiagnostics.remove(status.id)
                }
            }
        }
    }

    private fun updateStatusLocked(status: OperationStatus) {
        val old = mutableOperations.value.firstOrNull { it.id == status.id }
        if (old?.state == SyncState.REJECTED && status.state == SyncState.CONFIRMED) return
        val summary = old?.message?.substringAfter("\nComprovante:", "")?.takeIf { it.isNotBlank() }
        val enriched = if (summary != null && status.message?.contains("\nComprovante:") != true) {
            status.copy(message = "${status.message.orEmpty()}\nComprovante:$summary")
        } else status
        var confirmed = 0
        val updated = (listOf(enriched) + mutableOperations.value.filterNot { it.id == status.id })
            .filter { it.state != SyncState.CONFIRMED || ++confirmed <= 100 }
        val trace = ledger.traceId(status.id)?.let { TraceContext(traceId = it, operationId = status.id) }
            ?: logger.forOperation(status.id, LogCategory.DATABASE)
        val step = logger.startStep(trace, LogCategory.DATABASE, "LOCAL_RECEIPT_WRITE", "OperationLedger", "write", slowAfterMs = 100)
        try { ledger.write(updated); step.finish("Comprovante local persistido.") }
        catch (error: Exception) { step.fail(error); throw error }
        mutableOperations.value = updated
    }

    private fun movementSummary(movement: Movement): String = buildString {
        fun amount(value: Long) = "R$ " + java.math.BigDecimal.valueOf(value, 2).toPlainString().replace('.', ',')
        append("Comprovante: ${movement.id} | ${movement.type} | ${movement.occurredAt}")
        append(" | Total ${amount(movement.totalCents)} | Lucro ${amount(movement.profitCents)}")
        append("\nOperador: ${movement.operatorName} [${movement.operatorId}]")
        if (movement.customerName.isNotBlank()) append("\nCliente: ${movement.customerName} [${movement.customerId}]")
        movement.payments.forEach { payment ->
            val label = when (payment.method) {
                PaymentMethod.CASH -> "Dinheiro"
                PaymentMethod.PIX -> "Pix"
                PaymentMethod.DEBIT -> "Débito"
                PaymentMethod.CREDIT -> "Crédito"
                PaymentMethod.FIADO -> "Fiado"
            }
            append("\n$label: ${amount(payment.amountCents)}")
        }
        append("\nTroco: ${amount(movement.changeCents)} | A receber: ${amount(movement.receivableCents)}")
        movement.items.forEach {
            append("\n${it.quantity} × ${it.nameSnapshot} [${it.productId}]")
            if (it.unitsPerPackage > 1) append(" | Caixa de ${it.unitsPerPackage} un. (${it.stockQuantity} un.)")
            append(" | Unitário ${amount(it.saleUnitCents)} | Custo histórico ${amount(it.historicalCostCents)}")
        }
    }

    private fun validateMovement(movement: Movement) {
        require(UUID.fromString(movement.id).toString() == movement.id)
        require(movement.items.size in 1..BusinessLimits.MAX_DISTINCT_PRODUCTS) { "Uma movimentação aceita até 8 produtos distintos." }
        require(movement.items.map { it.productId }.distinct().size == movement.items.size)
        movement.items.forEach {
            require(UUID.fromString(it.productId).toString() == it.productId)
            require(it.quantity in 1..BusinessLimits.MAX_QUANTITY)
            require(it.unitsPerPackage in 1..BusinessLimits.MAX_QUANTITY && it.stockQuantity > 0)
            require(it.nameSnapshot.isNotBlank() && it.nameSnapshot.length <= 120)
            require(it.saleUnitCents in 0..BusinessLimits.MAX_MONEY_CENTS && it.historicalCostCents in 0..BusinessLimits.MAX_MONEY_CENTS)
        }
        val total = movement.items.sumOf {
            it.quantity.toLong() * if (movement.type == MovementType.SAIDA) it.saleUnitCents else it.historicalCostCents
        }
        val profit = if (movement.type == MovementType.SAIDA) movement.items.sumOf {
            (it.saleUnitCents - it.historicalCostCents) * it.quantity
        } else 0L
        require(total in 0..BusinessLimits.MAX_MONEY_CENTS)
        require(movement.totalCents == total && movement.profitCents == profit) { "Totais inconsistentes com os itens." }
        if (movement.type == MovementType.SAIDA) {
            val customer = if (movement.customerId.isNotBlank()) Customer(movement.customerId, movement.customerName) else null
            val payment = CalculatePayment()(total, movement.payments)
            payment.requireComplete(customer)
            require(payment.changeCents == movement.changeCents && payment.fiadoCents == movement.receivableCents) { "Totais do pagamento inconsistentes." }
        } else require(movement.payments.isEmpty() && movement.changeCents == 0L && movement.receivableCents == 0L)
    }
}

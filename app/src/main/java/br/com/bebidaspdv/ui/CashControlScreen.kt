package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class CashControlUiState(
    val sessions: Feed<List<CashSession>>? = null,
    val events: List<CashEvent> = emptyList(),
    val reversals: List<SaleReversal> = emptyList(),
    val pending: List<PendingCashOperation> = emptyList(),
    val working: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val preview: ReversalPreview? = null,
    val paymentSession: CashSession? = null,
    val paymentSummary: CashPaymentSummary? = null,
    val paymentLoading: Boolean = false,
    val paymentError: String? = null,
)

internal class CashControlViewModel(private val repository: CashControlRepository, private val logger: AppLogger = NoOpLogger) : ViewModel() {
    val state = MutableStateFlow(CashControlUiState())
    private var subscriptions: Job? = null
    private var paymentQuery: Job? = null
    fun start(showReversals: Boolean) {
        if (subscriptions?.isActive == true) return
        subscriptions = viewModelScope.launch {
            coroutineScope {
                launch { repository.observeSessions().catch { e -> fail(e) }.collect { feed -> state.update { it.copy(sessions = feed) } } }
                launch { repository.observeEvents().catch { e -> fail(e) }.collect { events -> state.update { it.copy(events = events.value) } } }
                if (showReversals) launch { repository.observeReversals().catch { e -> fail(e) }.collect { feed -> state.update { it.copy(reversals = feed.value) } } }
                launch { repository.pendingOperations.collect { pending -> state.update { it.copy(pending = pending) } } }
            }
        }
    }
    fun stop() { subscriptions?.cancel(); subscriptions = null; dismissPayments() }
    fun dismissPayments() {
        paymentQuery?.cancel(); paymentQuery = null
        state.update { it.copy(paymentSession = null, paymentSummary = null, paymentLoading = false, paymentError = null) }
    }
    fun payments(session: CashSession) {
        paymentQuery?.cancel()
        state.update { it.copy(paymentSession = session, paymentSummary = null, paymentLoading = true, paymentError = null) }
        val trace = logger.startTrace(LogCategory.CASH_REGISTER, "CASH_PAYMENT_SUMMARY_REQUEST", entityId = session.id,
            screen = "CashControlScreen", component = "CashControlViewModel", function = "payments")
        val step = logger.startStep(trace, LogCategory.CASH_REGISTER, "CASH_PAYMENT_SUMMARY", "CashControlViewModel", "payments", slowAfterMs = 3_000)
        paymentQuery = viewModelScope.launch(TraceElement(trace)) {
            try {
                val summary = repository.readPaymentSummary(session.id)
                step.finish()
                state.update { it.copy(paymentSummary = summary, paymentLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) { step.cancel(); throw error }
                step.fail(error)
                val message = if (error is TimeoutCancellationException) "A consulta demorou demais. Confira a conexão e toque em Atualizar."
                    else error.localizedMessage ?: "Não foi possível conferir os recebimentos."
                state.update { it.copy(paymentLoading = false, paymentError = message) }
            }
        }
    }
    private fun fail(error: Throwable) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        logger.error(LogCategory.CASH_REGISTER, "CASH_REGISTER_ERROR", "Falha ao acompanhar o caixa", error)
        state.update { it.copy(error = error.localizedMessage ?: "Não foi possível consultar o caixa.") }
    }
    fun dismissPreview() { if (!state.value.working) state.update { it.copy(preview = null) } }
    fun run(success: String? = null, event: String = "CASH_REGISTER_OPERATION", entityId: String? = null, action: suspend () -> Unit) {
        if (state.value.working) return
        state.update { it.copy(working = true, error = null, notice = null) }
        val trace = logger.startTrace(LogCategory.CASH_REGISTER, event, entityId = entityId,
            screen = "CashControlScreen", component = "CashControlViewModel", function = event)
        logger.info(LogCategory.UI, "${event}_CLICK", "Operador solicitou operação de caixa", trace.at("CashControlScreen", event))
        val step = logger.startStep(trace, LogCategory.CASH_REGISTER, "${event}_EXECUTION", "CashControlViewModel", event,
            slowAfterMs = 3_000, stuckAfterMs = 30_000)
        viewModelScope.launch(TraceElement(trace)) {
            try { action(); step.finish(); state.update { it.copy(notice = success) } }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) { step.cancel(); throw error }
                step.fail(error)
                state.update { it.copy(error = error.localizedMessage ?: "Não foi possível concluir a operação de caixa.") }
            } finally { state.update { it.copy(working = false) } }
        }
    }
    fun preview(saleId: String) = run(event = "SALE_REVERSAL_PREVIEW", entityId = saleId) { val preview = repository.previewReversal(saleId.trim()); state.update { it.copy(preview = preview) } }
    fun reverse(reason: String) = run("Venda estornada. Estoque, devoluções e saldo fiado foram ajustados.", "SALE_REVERSAL_REQUEST", state.value.preview?.sale?.id) {
        repository.reverseSale(requireNotNull(state.value.preview), reason)
        state.update { it.copy(preview = null) }
    }
}

@Composable
fun CashControlScreen(
    repository: CashControlRepository,
    sales: List<Movement>,
    canReverse: Boolean,
    modifier: Modifier = Modifier,
    selectedSaleId: String? = null,
    logger: AppLogger = NoOpLogger,
) {
    val vm: CashControlViewModel = viewModel(key = "cash-control:${repository.deviceId}", factory = remember(repository, logger) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CashControlViewModel(repository, logger) as T
        }
    })
    DisposableEffect(vm, canReverse) { vm.start(canReverse); onDispose { vm.stop() } }
    val state by vm.state.collectAsStateWithLifecycle()
    val active = state.sessions?.value?.firstOrNull { it.status == CashSessionStatus.OPEN }
    var opening by rememberSaveable { mutableStateOf("") }
    var movementType by remember { mutableStateOf<CashEventType?>(null) }
    var closingSession by remember { mutableStateOf<CashSession?>(null) }
    var saleId by rememberSaveable { mutableStateOf(selectedSaleId.orEmpty()) }
    LaunchedEffect(selectedSaleId) { if (!selectedSaleId.isNullOrBlank()) saleId = selectedSaleId }
    val enabled = !state.working && state.pending.isEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Controle de caixa", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Abertura, dinheiro em espécie e conferência por aparelho e operador.", style = MaterialTheme.typography.bodyMedium)
                Text("Abertura, ajustes, fechamento e estorno precisam de internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        state.notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        if (state.sessions?.fromCache == true || state.sessions?.pendingWrites == true) item {
            Text("Saldo local aguardando conferência com o servidor. O fechamento só é confirmado após sincronizar as operações.", style = MaterialTheme.typography.bodySmall)
        }
        items(state.pending, key = { "pending:${it.id}" }) { pending ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.description}: confirmação pendente", fontWeight = FontWeight.Bold)
                    Text("Confira este comprovante antes de fazer outra operação. A conferência reaproveita o mesmo identificador.", style = MaterialTheme.typography.bodySmall)
                    Text(pending.id, style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { vm.run("Operação conferida e confirmada.", "CASH_REGISTER_RETRY", pending.id) { repository.retryPending(pending.id) } }, enabled = !state.working) { Text("Conferir pendência") }
                }
            }
        }
        item {
            if (state.sessions == null) Text("Consultando o caixa deste aparelho…")
            else if (active == null) OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Caixa fechado", style = MaterialTheme.typography.titleLarge)
                    Text("Informe o dinheiro disponível para troco antes de começar a vender.")
                    CashAmountField(opening, { opening = it }, "Dinheiro inicial (R$)", enabled)
                    val cents = cashCents(opening)
                    Button(onClick = { vm.run("Caixa aberto para este aparelho e operador.", "CASH_REGISTER_OPEN_REQUEST") { repository.openSession(requireNotNull(cents)); opening = "" } }, enabled = enabled && cents != null) { Text("Abrir caixa") }
                }
            } else OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Caixa aberto", style = MaterialTheme.typography.titleLarge)
                    Text(active.operatorName, fontWeight = FontWeight.Bold)
                    Text("Aberto em ${cashDate(active.openedAt)} • ${active.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                    CashMoneyRow("Dinheiro inicial", active.openingCents)
                    CashMoneyRow("Vendas em dinheiro, após troco", active.cashSalesCents)
                    CashMoneyRow("Fiado recebido em dinheiro", active.cashReceiptsCents)
                    CashMoneyRow("Suprimentos", active.supplyCents)
                    CashMoneyRow("Sangrias", -active.withdrawalCents)
                    CashMoneyRow("Devoluções em dinheiro", -active.refundCents)
                    HorizontalDivider()
                    CashMoneyRow("Dinheiro esperado", active.expectedCents, true)
                    TextButton(onClick = { vm.payments(active) }) { Text("Ver formas de pagamento") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { movementType = CashEventType.SANGRIA }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Sangria") }
                        OutlinedButton(onClick = { movementType = CashEventType.SUPRIMENTO }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Suprimento") }
                    }
                    Button(onClick = { closingSession = active }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Conferir e fechar caixa") }
                }
            }
        }
        if (canReverse) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Estorno completo", style = MaterialTheme.typography.titleLarge)
                    Text("A venda continua no histórico. O estorno repõe o estoque, cancela o fiado restante e registra os valores devolvidos ao cliente.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(saleId, { saleId = it.take(36) }, label = { Text("Identificador completo da venda") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled)
                    Button(onClick = { vm.preview(saleId) }, enabled = enabled && active != null && saleId.length == 36) { Text("Conferir estorno") }
                    if (active == null) Text("Abra o caixa para registrar uma devolução.", style = MaterialTheme.typography.bodySmall)
                    sales.filter { it.type == MovementType.SAIDA }.sortedByDescending { it.occurredAt }.take(5).forEach { sale ->
                        TextButton(onClick = { saleId = sale.id; vm.preview(sale.id) }, enabled = enabled && active != null && state.reversals.none { it.saleId == sale.id }) {
                            Text("${cashDate(sale.occurredAt)} • ${cashMoney(sale.totalCents)} • ${sale.id.take(8)}")
                        }
                    }
                }
            }
        }
        val activeEvents = state.events.filter { it.sessionId == active?.id }.take(40)
        if (activeEvents.isNotEmpty()) item { Text("Movimentos deste caixa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(activeEvents, key = { "event:${it.id}" }) { event ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CashMoneyRow(cashEventLabel(event.type), event.amountCents)
                    if (event.reason.isNotBlank()) Text(event.reason)
                    Text("${cashDate(event.occurredAt)} • ${event.operatorName}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.reversals.isNotEmpty()) item { Text("Estornos deste mês", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(state.reversals.take(20), key = { "reversal:${it.id}" }) { reversal ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Venda ${reversal.saleId.take(8)} • ${cashDate(reversal.occurredAt)}", fontWeight = FontWeight.Bold)
                    Text(reversal.reason)
                    CashMoneyRow("Total devolvido", reversal.refundByMethod.values.sum())
                    CashMoneyRow("Fiado cancelado", reversal.cancelledReceivableCents)
                    Text("Responsável: ${reversal.operatorName}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val closed = state.sessions?.value.orEmpty().filter { it.status == CashSessionStatus.CLOSED }.take(20)
        if (closed.isNotEmpty()) item { Text("Fechamentos deste aparelho", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(closed, key = { "closed:${it.id}" }) { session ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(session.operatorName, fontWeight = FontWeight.Bold)
                    Text("${cashDate(session.openedAt)} até ${session.closedAt?.let(::cashDate) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                    CashMoneyRow("Esperado", session.expectedCents)
                    CashMoneyRow("Contado", session.countedCents ?: 0L)
                    CashMoneyRow("Diferença", session.differenceCents ?: 0L, true)
                    TextButton(onClick = { vm.payments(session) }) { Text("Ver formas de pagamento") }
                }
            }
        }
    }
    movementType?.let { type -> active?.let { current ->
        CashMovementDialog(type, state.working, state.error, onDismiss = { movementType = null }) { amount, reason ->
            vm.run("${cashEventLabel(type)} registrada.", "CASH_REGISTER_${type.name}_REQUEST", current.id) { repository.addMovement(current.id, type, amount, reason); movementType = null }
        }
    } }
    closingSession?.let { session ->
        CashClosingDialog(session, state.working, state.error, onDismiss = { closingSession = null }) { amount ->
            vm.run("Caixa fechado e conferência registrada.", "CASH_REGISTER_CLOSE_REQUEST", session.id) { repository.closeSession(session.id, amount, session.revision); closingSession = null }
        }
    }
    state.preview?.let { preview -> CashReversalDialog(preview, state.working, state.error, vm::dismissPreview, vm::reverse) }
    state.paymentSession?.let { session ->
        CashPaymentDialog(session, state.paymentSummary, state.paymentLoading, state.paymentError,
            onDismiss = vm::dismissPayments, onRefresh = { vm.payments(session) })
    }
}

@Composable
private fun CashPaymentDialog(
    session: CashSession, summary: CashPaymentSummary?, loading: Boolean, error: String?,
    onDismiss: () -> Unit, onRefresh: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Formas de pagamento") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Caixa ${session.id.take(8)} • ${session.operatorName}")
            Text("Recebimentos registrados nesta sessão. A consulta é feita somente ao abrir ou atualizar esta conferência.", style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            summary?.let { totals ->
                totals.methods.forEach { (method, amounts) ->
                    Text(cashMethodLabel(method), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    CashMoneyRow("Vendas recebidas", amounts.salesCents)
                    CashMoneyRow("Recebimento de fiado", amounts.receiptsCents)
                    CashMoneyRow("Devoluções", -amounts.refundsCents)
                    CashMoneyRow("Saldo recebido", amounts.netCents, true)
                    HorizontalDivider()
                }
                CashMoneyRow("Total recebido após devoluções", totals.netCents, true)
                CashMoneyRow("Fiado concedido nesta sessão", totals.creditIssuedCents)
                Text("Fiado concedido não é recebimento nem saldo pendente atual. Dinheiro inicial, sangrias e suprimentos aparecem na conferência do caixa.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = !loading) { Text("Atualizar") } })
}

@Composable
private fun CashMovementDialog(type: CashEventType, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (Long, String) -> Unit) {
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    val cents = cashCents(amount)?.takeIf { it > 0 }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text(cashEventLabel(type)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (type == CashEventType.SANGRIA) "Registre a retirada de dinheiro deste caixa." else "Registre o reforço de dinheiro para este caixa.")
            CashAmountField(amount, { amount = it }, "Valor (R$)", !working)
            OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Motivo") }, enabled = !working, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { onSave(requireNotNull(cents), reason) }, enabled = !working && cents != null && reason.trim().length >= 3) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}

@Composable
private fun CashClosingDialog(session: CashSession, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by rememberSaveable(session.id) { mutableStateOf("") }
    val cents = cashCents(amount)
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text("Conferir e fechar caixa") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conte o dinheiro físico, incluindo o valor inicial. Pix e cartões não entram nesta contagem.")
            CashMoneyRow("Dinheiro esperado", session.expectedCents, true)
            CashAmountField(amount, { amount = it }, "Dinheiro contado (R$)", !working)
            cents?.let { CashMoneyRow("Diferença registrada", CashAccounting.difference(it, session.expectedCents), true) }
            Text("Se houver uma nova operação, o fechamento será recusado para permitir outra conferência. A diferença fica registrada no histórico.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { onSave(requireNotNull(cents)) }, enabled = !working && cents != null) { Text("Confirmar fechamento") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Voltar") } })
}

@Composable
private fun CashReversalDialog(preview: ReversalPreview, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var reason by rememberSaveable(preview.sale.id) { mutableStateOf("") }
    var refunded by rememberSaveable(preview.sale.id) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text("Confirmar estorno completo") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Venda ${preview.sale.id}", style = MaterialTheme.typography.bodySmall)
            CashMoneyRow("Total original", preview.sale.totalCents, true)
            preview.refundByMethod.filterValues { it > 0 }.forEach { (method, amount) -> CashMoneyRow("Devolver em ${cashMethodLabel(method)}", amount) }
            if (preview.settledCents > 0) Text("A devolução inclui ${cashMoney(preview.settledCents)} de fiado já recebido.")
            CashMoneyRow("Saldo fiado a cancelar", preview.cancelledReceivableCents)
            Text("${preview.sale.items.sumOf { it.stockQuantity }} unidade(s) voltarão ao estoque. O dinheiro devolvido será descontado do caixa atual.")
            OutlinedTextField(reason, { reason = it.take(200) }, label = { Text("Motivo do estorno") }, enabled = !working, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(refunded, { refunded = it }, enabled = !working); Text("Conferi a devolução dos valores acima ao cliente. Para Pix e cartões, a devolução foi realizada fora do aplicativo.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
            Text("O comprovante original e os recebimentos permanecem no histórico com a referência do estorno.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { onSave(reason) }, enabled = !working && refunded && reason.trim().length >= 3) { Text("Estornar venda") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}

@Composable
private fun CashAmountField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, { onChange(it.take(16)) }, label = { Text(label) }, placeholder = { Text("0,00") }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotBlank() && cashCents(value) == null)
}

@Composable
private fun CashMoneyRow(label: String, cents: Long, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
        Text(cashMoney(cents), fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal)
    }
}
private fun cashCents(value: String): Long? = runCatching { Money.fromDecimal(value) }.getOrNull()?.takeIf { it in 0..BusinessLimits.MAX_MONEY_CENTS }
private fun cashMoney(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(java.math.BigDecimal.valueOf(cents, 2))
private fun cashDate(instant: java.time.Instant): String = DateTimeFormatter.ofPattern("dd/MM HH:mm").format(instant.atZone(ZoneId.of("America/Sao_Paulo")))
private fun cashEventLabel(type: CashEventType): String = when (type) { CashEventType.ABERTURA -> "Abertura"; CashEventType.SANGRIA -> "Sangria"; CashEventType.SUPRIMENTO -> "Suprimento"; CashEventType.FECHAMENTO -> "Fechamento"; CashEventType.ESTORNO -> "Estorno" }
private fun cashMethodLabel(method: PaymentMethod): String = when (method) { PaymentMethod.CASH -> "dinheiro"; PaymentMethod.PIX -> "Pix"; PaymentMethod.DEBIT -> "débito"; PaymentMethod.CREDIT -> "crédito"; PaymentMethod.FIADO -> "fiado" }

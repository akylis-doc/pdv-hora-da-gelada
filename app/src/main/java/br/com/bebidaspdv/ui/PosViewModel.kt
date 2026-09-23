package br.com.bebidaspdv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import br.com.bebidaspdv.updates.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

data class ReportState(
    val loading: Boolean = true,
    val report: MonthlyReport? = null,
    val fromCache: Boolean = false,
    val pendingWrites: Boolean = false,
    val error: String? = null,
    val movements: List<Movement> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val receipts: List<DebtPayment> = emptyList(),
    val reversals: List<SaleReversal> = emptyList(),
)

data class CheckoutDraft(val customerName: String = "", val customerId: String = "", val payments: List<Payment> = emptyList(),
    val dueDate: String = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(30).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
    val creditOverrideReason: String = "")

/** Read-only receipt of a cart edit, never a second editable cart. */
data class CartRender(val revision: Long, val trace: TraceContext, val startedNanos: Long,
    val lines: List<CartLine>, val metadata: Map<String, String>)

@OptIn(ExperimentalCoroutinesApi::class)
class PosViewModel(private val repository: PosRepository, private val updates: AppUpdates = UnavailableUpdates,
    val operatorId: String = "", operatorName: String = "",
    private val cashControlRepository: CashControlRepository? = null,
    capabilities: OperatorCapabilities = OperatorCapabilities.LEGACY,
    private val logger: AppLogger = NoOpLogger) : ViewModel() {
    private var saleTrace: TraceContext? = null
    private var cartRevision = 0L
    private var receivedCartRevision = 0L
    private var drawnCartRevision = 0L
    private val _cartRender = MutableStateFlow<CartRender?>(null)
    val cartRender = _cartRender.asStateFlow()
    private fun saleTrace(): TraceContext = saleTrace ?: logger.startTrace(LogCategory.SALE, "SALE_STARTED",
        screen = "CashierScreen", component = "PosViewModel", function = "saleTrace").also { saleTrace = it }

    private data class CartAction(val trace: TraceContext, val kind: String, val startedNanos: Long,
        val before: List<CartLine>, val metadata: Map<String, String>)

    private fun cartAction(kind: String, productId: String?, requested: String, uiBefore: Int? = null,
        uiEvent: String? = null): CartAction {
        val before = _cart.value
        val action = CartAction(TraceContext(entityId = productId, screen = "CashierScreen",
            component = "PosViewModel", function = kind), kind, System.nanoTime(), before,
            mapOf("parent_trace_id" to saleTrace().traceId, "action" to kind,
                "product_id" to productId.orEmpty(), "requested_quantity" to requested,
                "ui_quantity_before" to (uiBefore?.toString() ?: "not_observed"),
                "viewmodel_quantity_before" to (before.firstOrNull { it.product.id == productId }?.quantity ?: 0).toString(),
                "revision_before" to cartRevision.toString()))
        if (uiEvent != null) cartLog(action, uiEvent, category = LogCategory.UI)
        cartLog(action, if (kind == "SET_QUANTITY") "SET_PRODUCT_QUANTITY_REQUEST" else "${kind}_STARTED")
        return action
    }

    private fun cartLog(action: CartAction, event: String, category: LogCategory = LogCategory.CART,
        extra: Map<String, String> = emptyMap()) {
        val inputEvent = event.endsWith("_BUTTON_CLICK") || event == "QUANTITY_INPUT_CHANGED"
        logger.log(LogLevel.INFO, category, event, "Etapa da alteração local do carrinho",
            if (inputEvent) action.trace.at("ProductRow", "onInteraction") else action.trace,
            status = if (inputEvent || event.endsWith("_STARTED") || event.endsWith("_REQUEST")) LogStatus.STARTED else LogStatus.SUCCESS,
            durationMs = (System.nanoTime() - action.startedNanos) / 1_000_000,
            metadata = action.metadata + mapOf("revision" to cartRevision.toString(),
                "viewmodel_quantity_after" to (_cart.value.firstOrNull { it.product.id == action.trace.entityId }?.quantity ?: 0).toString()) + extra)
    }

    fun addFromUi(product: Product, unitsPerPackage: Int, uiQuantity: Int) = addToCart(product, unitsPerPackage, uiQuantity)
    fun removeFromUi(product: Product, uiQuantity: Int) = removeFromCart(product.id, uiQuantity)
    fun setQuantityFromUi(product: Product, quantity: String, unitsPerPackage: Int = 1, uiQuantity: Int? = null) {
        if (_working.value) return
        applyQuantity(product, quantity, unitsPerPackage,
            cartAction("SET_QUANTITY", product.id, quantity, uiQuantity, "QUANTITY_INPUT_CHANGED"))
    }

    private fun cartChanged(action: CartAction, event: String) {
        val changed = action.before != _cart.value
        if (changed) {
            val revision = ++cartRevision
            _cartRender.value?.takeIf { it.revision > drawnCartRevision }?.let { previous ->
                logger.log(LogLevel.INFO, LogCategory.UI, "CART_RENDER_SUPERSEDED",
                    "Uma revisão posterior substituiu esta antes da confirmação de desenho", previous.trace,
                    metadata = previous.metadata + ("superseded_by_revision" to revision.toString()))
            }
            cartLog(action, "${action.kind}_STATE_CHANGED")
            cartLog(action, "STATEFLOW_EMITTED", LogCategory.UI,
                mapOf("stage" to "publisher", "distinct_products" to _cart.value.size.toString()))
            cartLog(action, event)
            cartLog(action, "SALE_TOTAL_UPDATED", LogCategory.SALE,
                mapOf("total_cents" to _cart.value.sumOf { it.quantity.toLong() * it.saleUnitCents }.toString()))
            _cartRender.value = CartRender(revision, action.trace, action.startedNanos, _cart.value,
                action.metadata + mapOf("revision" to revision.toString(),
                    "viewmodel_quantity_after" to (_cart.value.firstOrNull { it.product.id == action.trace.entityId }?.quantity ?: 0).toString()))
        }
        cartLog(action, if (action.kind == "SET_QUANTITY") "SET_PRODUCT_QUANTITY_COMPLETED" else "${action.kind}_COMPLETED",
            extra = mapOf("state_changed" to changed.toString()))
    }

    // Catalog snapshots may refresh prices/stock independently. Match the edit's actual quantities,
    // and report the current UI total rather than acknowledging an unrelated diagnostic emission.
    private fun matchesRender(render: CartRender, lines: List<CartLine>): Boolean =
        lines.size == render.lines.size && lines.zip(render.lines).all { (a, b) ->
            a.product.id == b.product.id && a.quantity == b.quantity && a.unitsPerPackage == b.unitsPerPackage
        }

    private fun renderLog(render: CartRender, lines: List<CartLine>, event: String, extra: Map<String, String> = emptyMap()) {
        logger.log(LogLevel.INFO, LogCategory.UI, event, "Estado observado pela interface do caixa",
            render.trace.at("CashierScreen", "cartRender"), status = if (event == "CART_RENDER_STARTED") LogStatus.STARTED else LogStatus.SUCCESS,
            durationMs = (System.nanoTime() - render.startedNanos) / 1_000_000,
            metadata = render.metadata + mapOf("ui_quantity_after" to (lines.firstOrNull { it.product.id == render.trace.entityId }?.quantity ?: 0).toString(),
                "ui_total_cents" to lines.sumOf { it.quantity.toLong() * it.saleUnitCents }.toString()) + extra)
    }

    fun cartStateReceived(render: CartRender, lines: List<CartLine>, lifecycle: String) {
        if (render.revision <= receivedCartRevision || !matchesRender(render, lines)) return
        receivedCartRevision = render.revision
        renderLog(render, lines, "CASHIER_SCREEN_STATE_RECEIVED", mapOf("lifecycle" to lifecycle))
    }
    fun cartRenderStarted(render: CartRender, lines: List<CartLine>) {
        if (render.revision > drawnCartRevision && matchesRender(render, lines)) renderLog(render, lines, "CART_RENDER_STARTED")
    }
    fun cartRendered(render: CartRender, lines: List<CartLine>) {
        if (render.revision <= drawnCartRevision || !matchesRender(render, lines)) return
        drawnCartRevision = render.revision
        renderLog(render, lines, "CART_RENDERED", mapOf("stage" to "draw_completed"))
    }
    fun cashierLifecycleChanged(state: String) {
        logger.log(LogLevel.INFO, LogCategory.UI, "CASHIER_LIFECYCLE_CHANGED", "Ciclo de vida da tela de caixa",
            TraceContext(screen = "CashierScreen", component = "CashierScreen", function = "lifecycle"),
            metadata = mapOf("lifecycle" to state, "viewmodel_revision" to cartRevision.toString(), "ui_revision" to receivedCartRevision.toString(),
                "cart_trace_id" to _cartRender.value?.trace?.traceId.orEmpty(), "parent_trace_id" to saleTrace?.traceId.orEmpty()))
    }
    private val capabilityState = MutableStateFlow(capabilities)
    val capabilities: OperatorCapabilities get() = capabilityState.value
    var operatorName: String = operatorName
        private set
    fun updateProfile(capabilities: OperatorCapabilities, name: String) {
        capabilityState.value = capabilities
        operatorName = name
    }
    val updatesEnabled = updates.enabled
    private val _update = MutableStateFlow(UpdateState())
    val update = _update.asStateFlow()
    private val _products = MutableStateFlow(Feed(emptyList<Product>()))
    val products = _products.asStateFlow()
    private val _productsLoading = MutableStateFlow(true)
    val productsLoading = _productsLoading.asStateFlow()
    private val _productsError = MutableStateFlow<String?>(null)
    val productsError = _productsError.asStateFlow()
    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart = _cart.asStateFlow()
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month = _month.asStateFlow()
    private val eventChannel = Channel<String>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    val operations = repository.operations
    private val _formError = MutableStateFlow<String?>(null)
    val formError = _formError.asStateFlow()
    private val _checkoutError = MutableStateFlow<String?>(null)
    val checkoutError = _checkoutError.asStateFlow()
    private val _checkoutDraft = MutableStateFlow(CheckoutDraft())
    val checkoutDraft = _checkoutDraft.asStateFlow()
    private val _customers = MutableStateFlow(Feed(emptyList<Customer>()))
    val customers = _customers.asStateFlow()
    private val _customerError = MutableStateFlow<String?>(null)
    val customerError = _customerError.asStateFlow()
    private val _creditSales = MutableStateFlow(Feed(emptyList<Movement>()))
    val creditSales = _creditSales.asStateFlow()
    private val _debtPayments = MutableStateFlow(Feed(emptyList<DebtPayment>()))
    val debtPayments = _debtPayments.asStateFlow()
    private val creditSalesLoading = MutableStateFlow(true)
    private val debtPaymentsLoading = MutableStateFlow(true)
    val creditLoading = combine(creditSalesLoading, debtPaymentsLoading) { a, b -> a || b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private val creditSalesError = MutableStateFlow<String?>(null)
    private val debtPaymentsError = MutableStateFlow<String?>(null)
    val creditError = combine(creditSalesError, debtPaymentsError) { a, b -> a ?: b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var unsettledRequest: DebtPayment? = null
    val pendingDebtPayments = repository.pendingDebtPayments
    private val activeSection = MutableStateFlow("caixa")
    private val creditMode = MutableStateFlow("Em aberto")
    private val _accounts = MutableStateFlow(Feed(emptyList<ReceivableAccount>()))
    val accounts = _accounts.asStateFlow()
    val cashSessions = (cashControlRepository?.observeSessions() ?: flowOf(Feed(emptyList())))
        .catch { emit(Feed(emptyList(), fromCache = true)); notify("Não foi possível consultar o caixa aberto. Confira a conexão.") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Feed(emptyList()))
    val reports = combine(activeSection, _month, capabilityState) { route, selected, access -> Triple(route, selected, access) }.flatMapLatest { (route, selected, access) ->
        if (route != "relatorios" || !access.canViewReports) return@flatMapLatest flowOf(ReportState(loading = false))
        combine(repository.observeMovements(selected, zone), repository.observeExpenses(selected, zone),
            repository.observeDebtPayments(selected, zone), cashControlRepository?.observeReversals(selected, zone) ?: flowOf(Feed(emptyList()))) { feed, expenses, receipts, reversals ->
                val result = runCatching { CalculateReceiptReport()(feed.value, receipts.value, reversals.value) }
                ReportState(false, result.getOrNull(),
                    feed.fromCache || expenses.fromCache || receipts.fromCache || reversals.fromCache,
                    feed.pendingWrites || expenses.pendingWrites || receipts.pendingWrites || reversals.pendingWrites,
                    error = result.exceptionOrNull()?.message, movements = feed.value, expenses = expenses.value,
                    receipts = receipts.value, reversals = reversals.value)
            }
            .onStart { emit(ReportState()) }
            .catch { error ->
                if (error is CancellationException) throw error
                emit(ReportState(loading = false, error = error.message ?: "Não foi possível carregar o relatório."))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), ReportState(loading = false))

    val pendingWrites = combine(listOf(
        _products.map { it.pendingWrites }, _customers.map { it.pendingWrites },
        operations.map { entries -> entries.any { it.state == SyncState.PENDING } },
        pendingDebtPayments.map { it.isNotEmpty() },
        repository.pendingLocalWrites,
        cashSessions.map { it.pendingWrites },
        cashControlRepository?.pendingOperations?.map { it.isNotEmpty() } ?: flowOf(false),
    )) { flags -> flags.any { it } }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            repository.observeProducts().catch { error ->
                if (error is CancellationException) throw error
                _productsLoading.value = false
                _productsError.value = error.message ?: "Não foi possível carregar os produtos."
            }.collect { feed ->
                _products.value = feed.copy(value = feed.value.filter { it.active })
                _productsLoading.value = false
                _productsError.value = null
                val current = feed.value.associateBy(Product::id)
                _cart.update { lines -> lines.map { it.copy(product = current[it.product.id] ?: it.product.copy(active = false)) } }
            }
        }
        viewModelScope.launch {
            val rejected = mutableSetOf<String>()
            operations.collect { statuses ->
                statuses.filter { it.state == SyncState.REJECTED && rejected.add(it.id) }.forEach {
                    eventChannel.trySend(it.message ?: "Sincronização recusada. Confira a operação antes de registrar novamente.")
                }
            }
        }
        viewModelScope.launch {
            repository.observeCustomers().catch { error ->
                if (error is CancellationException) throw error
                _customerError.value = "Não foi possível carregar os clientes. Confira a conexão e as regras do banco."
            }.collect { _customers.value = it; _customerError.value = null }
        }
        viewModelScope.launch {
            combine(activeSection, _month, creditMode) { route, selected, mode -> Triple(route, selected, mode) }.flatMapLatest { (route, selected, mode) ->
                if (route != "fiado") return@flatMapLatest emptyFlow()
                val accountsFlow = if (mode == "Quitados") repository.observeDebtPayments(selected, zone).mapLatest { feed ->
                    Feed(repository.loadReceivables(feed.value.map { it.saleId }).filter { it.remainingCents == 0L && it.cancelledCents == 0L },
                        feed.fromCache, feed.pendingWrites)
                } else repository.observeReceivables()
                accountsFlow.mapLatest { feed ->
                    val cached = _creditSales.value.value.associateBy { it.id }
                    val ids = feed.value.map { it.saleId }
                    val loaded = repository.loadMovements(ids.filterNot { it in cached })
                    feed to Feed((cached.values + loaded).filter { it.id in ids }, feed.fromCache, feed.pendingWrites)
                }.onStart { creditSalesLoading.value = true; creditSalesError.value = null }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        creditSalesLoading.value = false
                        creditSalesError.value = "Não foi possível carregar os fiados. Confira a conexão e abra esta tela novamente."
                    }
            }.collect { (feed, sales) ->
                _accounts.value = feed; _creditSales.value = sales
                creditSalesLoading.value = false; creditSalesError.value = null
            }
        }
        viewModelScope.launch {
            combine(activeSection, _month) { route, selected -> route to selected }.flatMapLatest { (route, selected) ->
                if (route == "fiado") repository.observeDebtPayments(selected, zone)
                    .onStart { debtPaymentsLoading.value = true; debtPaymentsError.value = null }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        debtPaymentsLoading.value = false
                        debtPaymentsError.value = "Não foi possível carregar os recebimentos. Confira a conexão e abra esta tela novamente."
                    } else emptyFlow()
            }.collect { feed ->
                _debtPayments.value = feed; debtPaymentsLoading.value = false; debtPaymentsError.value = null
                if (!feed.fromCache && !feed.pendingWrites && feed.value.any { it.id == unsettledRequest?.id }) unsettledRequest = null
            }
        }
        viewModelScope.launch {
            updates.downloadedUpdate.collect { available ->
                _update.update { it.copy(downloaded = available != null, available = available ?: it.available) }
            }
        }
    }

    fun setActiveSection(route: String) {
        if (activeSection.value != route) logger.info(LogCategory.UI, "SCREEN_CHANGED", "Seção aberta: $route",
            TraceContext(screen = route, component = "PosViewModel", function = "setActiveSection"))
        activeSection.value = route
    }
    fun setCreditMode(mode: String) { creditMode.value = mode }
    fun refreshDownloadedUpdate() { viewModelScope.launch {
        try { updates.refreshDownloadedUpdate() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            _update.update { it.copy(message = "Não foi possível conferir o arquivo baixado. Busque a atualização novamente.", error = true) }
        }
    } }
    fun changeMonth(offset: Long) { _month.update { it.plusMonths(offset) } }
    fun clearFormError() { _formError.value = null }

    fun updateBlockReason(): String? = when {
        _working.value -> "Aguarde a operação em andamento."
        _cart.value.isNotEmpty() -> "Finalize a venda ou esvazie o carrinho antes de atualizar."
        _productsLoading.value || _productsError.value != null -> "Aguarde o carregamento dos produtos. Se necessário, reabra o aplicativo conectado."
        hasKnownPendingWrites() ->
            "Aguarde a sincronização das gravações pendentes antes de atualizar."
        _products.value.fromCache -> "Conecte-se à internet e aguarde a sincronização antes de atualizar."
        else -> null
    }

    fun changeTesterAccount() {
        if (!updates.enabled || _working.value || _update.value.checking || _update.value.installing) return
        updates.signOutTester()
        _update.value = UpdateState(message = "Toque em Buscar atualização e entre com a outra conta Google convidada. A conta do caixa continua conectada.")
    }

    fun checkForUpdates() {
        if (!updates.enabled || _working.value || _update.value.checking || _update.value.installing) return
        _update.update { it.copy(checking = true, error = false, message = "Consultando versões disponíveis…") }
        val trace = logger.startTrace(LogCategory.UI, "UPDATE_CHECK_CLICK", screen = "UpdatesScreen", component = "PosViewModel", function = "checkForUpdates")
        viewModelScope.launch(TraceElement(trace)) {
            try {
                val available = updates.downloadedUpdate.value ?: updates.check()
                _update.value = UpdateState(available = available, downloaded = updates.downloadedUpdate.value != null, message = if (available == null)
                    "Nenhuma versão mais recente disponível para esta conta de testes." else "Uma atualização está disponível.")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    _update.value = UpdateState(message = "Consulta cancelada. Você pode tentar novamente.")
                    return@launch
                }
                _update.update { it.copy(checking = false, available = updates.downloadedUpdate.value,
                    downloaded = updates.downloadedUpdate.value != null, message = "Não foi possível consultar. Confira a conexão e a conta convidada no App Distribution.", error = true) }
            }
        }
    }

    fun installUpdate() {
        if (!updates.enabled || _update.value.checking || _update.value.installing || _update.value.available == null) return
        updateBlockReason()?.let { reason -> _update.update { it.copy(message = reason, error = true) }; return }
        // Reserve the same lock used by all POS mutations before yielding to a coroutine.
        _working.value = true
        _update.update { it.copy(installing = true, error = false, message = "Conferindo a sincronização…") }
        val trace = logger.startTrace(LogCategory.UPDATE, "UPDATE_INSTALL_REQUEST", screen = "UpdatesScreen", component = "PosViewModel", function = "installUpdate")
        viewModelScope.launch(TraceElement(trace)) {
            try {
                updates.awaitPendingWrites()
                check(!hasKnownPendingWrites()) {
                    "Aguarde a sincronização e tente novamente."
                }
                _update.update { it.copy(message = "Preparando download…") }
                updates.install { progress -> _update.update { it.copy(message = progress) } }
                _update.update { it.copy(available = updates.downloadedUpdate.value, downloaded = updates.downloadedUpdate.value != null,
                    message = "Arquivo pronto. Toque em Instalar agora e confirme a instalação no Android.") }
            } catch (error: Exception) {
                val message = if (error is UpdateActionRequired) error.message.orEmpty() else if (error is TimeoutCancellationException)
                    "A sincronização demorou. Confira a internet e tente novamente."
                else "Atualização não concluída. Confira a conexão e as permissões de instalação do Android. Depois, consulte novamente."
                _update.update { it.copy(available = updates.downloadedUpdate.value,
                    downloaded = updates.downloadedUpdate.value != null, message = message, error = true) }
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
            } finally {
                _update.update { it.copy(installing = false) }
                _working.value = false
            }
        }
    }

    fun add(product: Product) {
        addToCart(product, null, null)
    }

    private fun addToCart(product: Product, selectedFactor: Int?, uiQuantity: Int?) {
        if (_working.value) return
        val line = _cart.value.firstOrNull { it.product.id == product.id }
        val quantity = ((line?.quantity ?: 0) + 1).toString()
        applyQuantity(product, quantity, line?.unitsPerPackage ?: selectedFactor ?: 1,
            cartAction("ADD_TO_CART", product.id, quantity, uiQuantity,
                if (uiQuantity != null) "ADD_TO_CART_BUTTON_CLICK" else null))
    }

    fun setQuantity(product: Product, quantity: String, unitsPerPackage: Int = 1) {
        if (_working.value) return
        applyQuantity(product, quantity, unitsPerPackage, cartAction("SET_QUANTITY", product.id, quantity))
    }

    private fun applyQuantity(product: Product, quantity: String, unitsPerPackage: Int, action: CartAction) {
        val trace = action.trace
        _checkoutError.value = null
        val count = quantity.toIntOrNull()
        fun reject(code: String, message: String) {
            logger.log(LogLevel.WARNING, LogCategory.CART, "CART_CHANGE_REJECTED", "Quantidade não aplicada", trace,
                status = LogStatus.FAILED, errorCode = code, metadata = action.metadata + ("revision" to cartRevision.toString()))
            notify(message)
        }
        if (count == null || count !in 0..BusinessLimits.MAX_QUANTITY) { reject("INVALID_QUANTITY", "Informe uma quantidade inteira de 0 a 9.999."); return }
        if (count == 0) {
            _cart.update { lines -> lines.filterNot { it.product.id == product.id } }
            if (_cart.value.isEmpty()) _checkoutDraft.value = CheckoutDraft()
            cartChanged(action, "PRODUCT_REMOVED")
            return
        }
        val lookup = logger.startStep(trace, LogCategory.PRODUCT, "PRODUCT_LOOKUP", "PosViewModel", "inMemoryCatalog")
        val latest = _products.value.value.firstOrNull { it.id == product.id && it.active }
        lookup.finish("Consulta ao catálogo em memória concluída", mapOf("found" to (latest != null).toString(),
            "product_name" to latest?.name.orEmpty()))
        if (latest == null) { reject("PRODUCT_UNAVAILABLE", "Produto indisponível no catálogo."); return }
        if (unitsPerPackage != 1 && unitsPerPackage != latest.packageSize) { reject("PACKAGE_CHANGED", "A quantidade de unidades na caixa mudou. Selecione novamente."); return }
        if (unitsPerPackage < 1 || count.toLong() * unitsPerPackage > latest.stock) { reject("INSUFFICIENT_STOCK", "Estoque local insuficiente para ${latest.name}."); return }
        val existing = _cart.value.firstOrNull { it.product.id == product.id }
        if (existing == null && _cart.value.size >= BusinessLimits.MAX_DISTINCT_PRODUCTS) { reject("CART_LIMIT", "Uma venda comporta até 8 produtos diferentes."); return }
        _cart.update { lines ->
            val line = CartLine(latest, count, unitsPerPackage)
            if (existing == null) lines + line else lines.map { if (it.product.id == product.id) line else it }
        }
        cartChanged(action, if (existing == null) "PRODUCT_ADDED" else "CART_UPDATED")
    }

    fun remove(productId: String) = removeFromCart(productId, null)

    private fun removeFromCart(productId: String, uiQuantity: Int?) {
        if (_working.value) return
        val quantity = ((_cart.value.firstOrNull { it.product.id == productId }?.quantity ?: 0) - 1).coerceAtLeast(0)
        val action = cartAction("REMOVE_FROM_CART", productId, quantity.toString(), uiQuantity,
            if (uiQuantity != null) "REMOVE_FROM_CART_BUTTON_CLICK" else null)
        _checkoutError.value = null
        _cart.update { lines -> lines.mapNotNull {
            if (it.product.id != productId) it else if (it.quantity > 1) it.copy(quantity = it.quantity - 1) else null
        } }
        if (_cart.value.isEmpty()) _checkoutDraft.value = CheckoutDraft()
        cartChanged(action, "PRODUCT_REMOVED")
    }

    fun clearCart() { if (!_working.value) {
        val action = cartAction("CLEAR_CART", null, "0", uiEvent = "CLEAR_CART_BUTTON_CLICK")
        _cart.value = emptyList(); _checkoutDraft.value = CheckoutDraft(); _checkoutError.value = null
        cartChanged(action, "CART_CLEARED")
        saleTrace = null
    } }

    fun setCustomerName(name: String) {
        if (!_working.value) _checkoutDraft.update { it.copy(customerName = name.take(120), customerId = "") }
    }
    fun selectCustomer(customer: Customer) {
        if (!_working.value) _checkoutDraft.update { it.copy(customerName = customer.name, customerId = customer.id) }
    }
    fun setDueDate(value: String) { if (!_working.value) _checkoutDraft.update { it.copy(dueDate = value.take(10)) } }
    fun setCreditOverrideReason(value: String) { if (!_working.value && capabilities.isAdmin) _checkoutDraft.update { it.copy(creditOverrideReason = value.take(200)) } }
    private fun currentCashSession(): CashSession? {
        if (cashControlRepository == null) return null
        return requireNotNull(cashSessions.value.value.firstOrNull { it.status == CashSessionStatus.OPEN &&
            it.deviceId == cashControlRepository.deviceId && it.operatorId == operatorId }) {
            "Abra o caixa no menu Controle de caixa antes de vender ou receber."
        }
    }
    fun addPayment(method: PaymentMethod, amount: String) {
        if (_working.value) return
        val trace = saleTrace().at("PosViewModel", "addPayment")
        try {
            val cents = Money.fromDecimal(amount)
            require(cents > 0 && cents <= BusinessLimits.MAX_MONEY_CENTS) { "Informe um valor de pagamento maior que zero." }
            // One editable amount per method, matching the bounded Firestore schema.
            _checkoutDraft.update { it.copy(payments = it.payments.filterNot { p -> p.method == method } + Payment(method, cents)) }
            _checkoutError.value = null
            logger.log(LogLevel.INFO, LogCategory.PAYMENT, "PAYMENT_REGISTERED", "Forma de pagamento aplicada à venda", trace,
                status = LogStatus.SUCCESS, metadata = mapOf("method" to method.name))
        } catch (error: Exception) {
            logger.log(LogLevel.WARNING, LogCategory.PAYMENT, "PAYMENT_VALIDATION_FAILED", "Pagamento não aplicado", trace,
                status = LogStatus.FAILED, errorCode = "INVALID_AMOUNT")
            _checkoutError.value = error.message ?: "Valor inválido."
        }
    }
    fun removePayment(method: PaymentMethod) {
        if (!_working.value) {
            _checkoutDraft.update { it.copy(payments = it.payments.filterNot { p -> p.method == method }) }; _checkoutError.value = null
            logger.info(LogCategory.PAYMENT, "PAYMENT_REMOVED", "Forma de pagamento removida: ${method.name}", saleTrace().at("PosViewModel", "removePayment"))
        }
    }
    fun checkoutFromUi() {
        logger.info(LogCategory.UI, "SALE_FINALIZE_CLICK", "Operador solicitou finalizar a venda", saleTrace().at("CashierScreen", "checkoutClick"))
        checkoutDraft()
    }
    fun checkoutDraft() {
        val draft = _checkoutDraft.value
        checkout(draft.payments, _customers.value.value.firstOrNull { it.id == draft.customerId }, draft.customerName)
    }

    fun checkout() = checkout(listOf(Payment(PaymentMethod.CASH, _cart.value.sumOf { it.saleUnitCents * it.quantity })))

    fun checkout(payments: List<Payment>, customer: Customer? = null, customerName: String = "") {
        if (_working.value || _cart.value.isEmpty()) return
        var trace = saleTrace().at("PosViewModel", "checkout")
        val step = logger.startStep(trace, LogCategory.SALE, "SALE_FINALIZATION", "PosViewModel", "checkout", stuckAfterMs = 30_000)
        _checkoutError.value = null
        _working.value = true
        viewModelScope.launch(TraceElement(trace)) {
            try {
                // Build once: every historical cost is frozen before the durable write.
                val current = _products.value.value.associateBy(Product::id)
                val lines = _cart.value.map { line ->
                    val product = requireNotNull(current[line.product.id]) { "Produto indisponível. Revise o carrinho." }
                    line.copy(product = product)
                }
                val total = lines.sumOf { it.saleUnitCents * it.quantity }
                CalculatePayment()(total, payments).requireComplete(customer)
                val session = currentCashSession()
                val draft = _checkoutDraft.value
                val fiado = payments.filter { it.method == PaymentMethod.FIADO }.sumOf { it.amountCents }
                val due = if (fiado > 0) LocalDate.parse(draft.dueDate,
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)) else null
                require(due == null || !due.isBefore(LocalDate.now(zone))) { "O vencimento não pode ser anterior a hoje." }
                if (fiado > 0 && customer != null && customer.creditLimitCents > 0 && customer.balanceCents + fiado > customer.creditLimitCents) {
                    require(capabilities.isAdmin && draft.creditOverrideReason.trim().length >= 5) { "O fiado ultrapassa o limite do cliente. Um administrador deve informar o motivo da liberação." }
                }
                val movement = CreateMovement()(lines, MovementType.SAIDA, payments, customer, operatorId, operatorName)
                    .copy(customerName = customer?.name ?: customerName.trim().take(120),
                        cashSessionId = session?.id.orEmpty(), deviceId = session?.deviceId.orEmpty(),
                        dueEpochDay = due?.toEpochDay() ?: 0, creditOverrideReason = if (fiado > 0 && capabilities.isAdmin) draft.creditOverrideReason.trim() else "")
                trace = logger.bindOperation(trace, movement.id).copy(entityId = movement.id)
                withLogTrace(trace) { repository.submit(movement) }
                _cart.value = emptyList()
                _checkoutDraft.value = CheckoutDraft()
                step.finish("Venda aceita localmente; confirmação remota acompanhada separadamente", syncStatus = LogSyncStatus.PENDING)
                logger.log(LogLevel.INFO, LogCategory.SALE, "SALE_COMPLETED", "Operador liberado após gravação local", trace,
                    status = LogStatus.SUCCESS)
                saleTrace = null
                eventChannel.trySend("Venda registrada.")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) { step.cancel(); throw error }
                step.fail(error)
                logger.error(LogCategory.SALE, "SALE_FAILED", "Não foi possível concluir a venda", error, trace)
                _checkoutError.value = error.message ?: "Não foi possível registrar a venda."
                eventChannel.trySend(_checkoutError.value!!)
            } finally { _working.value = false }
        }
    }

    fun saveProduct(original: Product?, name: String, barcode: String, cost: String, price: String, onSaved: () -> Unit) {
        saveProductDetails(original, name, barcode, cost, price, original?.imageKey.orEmpty(),
            (original?.lowStockThreshold ?: 5).toString(), (original?.packageSize ?: 1).toString(),
            decimalInput(original?.packageSaleCents ?: 0), onSaved)
    }

    fun saveProductDetails(original: Product?, name: String, barcode: String, cost: String, price: String,
        imageKey: String, lowStockThreshold: String, packageSize: String, packagePrice: String, onSaved: () -> Unit) =
        saveProductDetails(original, ProductForm(name, barcode, cost, price, imageKey, lowStockThreshold, packageSize, packagePrice,
            original?.category ?: "OUTROS", original?.unitLabel ?: "UNIDADE", false), onSaved)

    fun saveProductDetails(original: Product?, form: ProductForm, onSaved: () -> Unit) {
        if (_working.value) return
        _formError.value = null
        _working.value = true
        val trace = logger.startTrace(LogCategory.PRODUCT, "PRODUCT_SAVE_REQUEST", entityId = original?.id,
            screen = "StockScreen", component = "PosViewModel", function = "saveProductDetails")
        val step = logger.startStep(trace, LogCategory.PRODUCT, "PRODUCT_SAVE", "PosViewModel", "saveProductDetails")
        viewModelScope.launch(TraceElement(trace)) {
            try {
                require(capabilities.canEditPrices) { "Seu perfil não permite alterar o cadastro e os preços." }
                val (name, barcode, cost, price, imageKey, lowStockThreshold, packageSize, packagePrice) = form
                require(name.trim().isNotEmpty()) { "Informe o nome do produto." }
                val enteredCost = Money.fromDecimal(cost)
                val size = packageSize.toIntOrNull() ?: error("Informe quantas unidades há na caixa.")
                require(size in 1..9999 && (!form.purchaseByPackage || size > 1)) { "Informe a quantidade por caixa; para custo de caixa, use pelo menos 2 unidades." }
                val costCents = if (form.purchaseByPackage) PurchaseCost.unitCents(enteredCost, size) else enteredCost
                val saleCents = Money.fromDecimal(price)
                require(costCents >= 0 && saleCents >= 0) { "Os preços não podem ser negativos." }
                val threshold = lowStockThreshold.toIntOrNull() ?: error("Informe um limite de estoque inteiro.")
                require(threshold in 0..9999 && size in 1..9999) { "Limite de estoque: 0 a 9.999; unidades por caixa: 1 a 9.999." }
                require(original == null || _products.value.value.any { it.id == original.id }) { "Este produto foi excluído do catálogo." }
                val boxPrice = if (packagePrice.isBlank() || size == 1) 0L else Money.fromDecimal(packagePrice)
                require(boxPrice in 0..BusinessLimits.MAX_MONEY_CENTS) { "Preço de caixa inválido." }
                require(costCents * size <= BusinessLimits.MAX_MONEY_CENTS && (if (boxPrice > 0) boxPrice else saleCents * size) <= BusinessLimits.MAX_MONEY_CENTS) { "Preço ou custo da caixa acima do limite permitido." }
                val product = Product(
                    id = original?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(), barcode = barcode.trim(),
                    stock = original?.let { old -> _products.value.value.firstOrNull { it.id == old.id }?.stock ?: old.stock } ?: 0,
                    costCents = costCents, saleCents = saleCents,
                    imageKey = imageKey, lowStockThreshold = threshold, packageSize = size, packageSaleCents = boxPrice,
                    category = form.category, unitLabel = form.unitLabel, purchaseByPackage = form.purchaseByPackage,
                    packageCostCents = if (form.purchaseByPackage) enteredCost else 0,
                )
                repository.saveProduct(product)
                step.finish("Cadastro aceito localmente", syncStatus = LogSyncStatus.PENDING)
                onSaved()
                eventChannel.trySend("Produto salvo neste aparelho.")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) { step.cancel(); throw error }
                step.fail(error)
                _formError.value = error.message ?: "Confira os dados do produto."
            } finally { _working.value = false }
        }
    }

    fun receiveStock(product: Product, quantity: String, unitsPerPackage: Int = 1, onSaved: () -> Unit) {
        if (_working.value) return
        _formError.value = null
        _working.value = true
        val trace = logger.startTrace(LogCategory.STOCK, "STOCK_UPDATE_REQUEST", entityId = product.id,
            screen = "StockScreen", component = "PosViewModel", function = "receiveStock")
        val step = logger.startStep(trace, LogCategory.STOCK, "STOCK_UPDATE", "PosViewModel", "receiveStock")
        viewModelScope.launch(TraceElement(trace)) {
            try {
                val count = quantity.toIntOrNull() ?: error("Informe uma quantidade inteira.")
                require(count in 1..9999) { "Informe de 1 a 9.999 unidades." }
                val latest = requireNotNull(_products.value.value.firstOrNull { it.id == product.id }) { "Produto indisponível." }
                val movement = CreateMovement()(listOf(CartLine(latest, count, unitsPerPackage)), MovementType.ENTRADA,
                    operatorId = operatorId, operatorName = operatorName)
                withLogTrace(logger.bindOperation(trace, movement.id)) { repository.submit(movement) }
                step.finish("Entrada aceita localmente", syncStatus = LogSyncStatus.PENDING)
                onSaved()
                eventChannel.trySend("Entrada registrada neste aparelho.")
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) { step.cancel(); throw error }
                step.fail(error)
                _formError.value = error.message ?: "Não foi possível registrar a entrada."
            } finally { _working.value = false }
        }
    }

    fun deleteProduct(product: Product, onDeleted: () -> Unit) = formOperation("Produto excluído do catálogo. O histórico foi preservado.", onDeleted, LogCategory.PRODUCT, "PRODUCT_DELETE") {
        require(capabilities.canDeleteProducts) { "Seu perfil não permite excluir produtos." }
        require(_cart.value.none { it.product.id == product.id }) { "Remova este produto do carrinho antes de excluir." }
        repository.deleteProduct(product)
    }

    fun saveCustomer(name: String, phone: String, onSaved: () -> Unit) = formOperation("Cliente cadastrado neste aparelho.", onSaved) {
        require(name.trim().isNotEmpty() && name.trim().length <= 120) { "Informe o nome do cliente." }
        require(phone.length <= 30) { "Telefone muito longo." }
        val customer = Customer(UUID.randomUUID().toString(), name.trim(), phone.trim())
        repository.saveCustomer(customer)
        _checkoutDraft.update { it.copy(customerId = customer.id, customerName = customer.name) }
    }

    fun editCustomer(customer: Customer, name: String, phone: String, creditLimit: String, onSaved: () -> Unit) = formOperation("Cliente atualizado.", onSaved) {
        val limit = Money.fromDecimal(creditLimit)
        require(capabilities.isAdmin || limit == customer.creditLimitCents) { "Somente o administrador pode alterar o limite de fiado." }
        repository.saveCustomer(customer.copy(name = name.trim(), phone = phone.trim(), creditLimitCents = limit))
    }

    fun saveExpense(description: String, category: String, amount: String, date: LocalDate, onSaved: () -> Unit) =
        formOperation("Despesa registrada neste aparelho. Acompanhe a sincronização.", onSaved) {
            require(capabilities.canViewReports) { "Seu perfil não permite registrar despesas." }
            val cents = Money.fromDecimal(amount)
            require(description.trim().isNotEmpty() && description.trim().length <= 120) { "Informe a descrição da despesa." }
            require(cents in 1..BusinessLimits.MAX_MONEY_CENTS) { "Informe um valor maior que zero." }
            require(category in setOf("AGUA", "ENERGIA", "ALUGUEL", "OUTROS")) { "Categoria inválida." }
            require(!date.isAfter(LocalDate.now(zone))) { "Registre somente despesas já ocorridas." }
            repository.saveExpense(Expense(UUID.randomUUID().toString(), date.atTime(12, 0).atZone(zone).toInstant(),
                description.trim(), category, cents, operatorId, operatorName))
        }

    fun settleDebt(sale: Movement, amount: String, method: PaymentMethod, onSaved: () -> Unit) =
        formOperation("Recebimento confirmado no servidor.", onSaved, LogCategory.PAYMENT, "DEBT_SETTLEMENT") {
            val cents = Money.fromDecimal(amount)
            require(cents in 1..BusinessLimits.MAX_MONEY_CENTS && method != PaymentMethod.FIADO) { "Informe um pagamento válido." }
            require(!_creditSales.value.fromCache && !_debtPayments.value.fromCache && creditError.value == null) { "Conecte-se e aguarde carregar os recebimentos antes de dar baixa." }
            require(pendingDebtPayments.value.none { it.saleId == sale.id }) {
                "Use Conferir pendência na aba Fiado antes de registrar outro recebimento desta venda."
            }
            val previous = unsettledRequest
            require(previous == null || (previous.saleId == sale.id && previous.amountCents == cents && previous.method == method)) {
                "Há um recebimento sem confirmação. Consulte a lista e tente novamente o mesmo pagamento antes de registrar outro."
            }
            val session = currentCashSession()
            val payment = previous ?: DebtPayment(UUID.randomUUID().toString(), sale.id, sale.customerId,
                Instant.now(), cents, method, operatorId, operatorName, session?.id.orEmpty(), session?.deviceId.orEmpty()).also { unsettledRequest = it }
            try { repository.settleDebt(payment) }
            catch (error: DebtSettlementRejected) { unsettledRequest = null; throw error }
            unsettledRequest = null
        }

    private fun hasKnownPendingWrites(): Boolean = repository.pendingLocalWrites.value || _products.value.pendingWrites || _customers.value.pendingWrites ||
        operations.value.any { it.state == SyncState.PENDING } || pendingDebtPayments.value.isNotEmpty()
        || cashSessions.value.pendingWrites || cashControlRepository?.pendingOperations?.value?.isNotEmpty() == true

    fun retryPendingDebt(payment: DebtPayment) = formOperation("Recebimento conferido no servidor.", {}, LogCategory.PAYMENT, "DEBT_SETTLEMENT_RETRY") {
        try { repository.settleDebt(payment); unsettledRequest = null }
        catch (error: DebtSettlementRejected) { unsettledRequest = null; throw error }
    }

    fun switchAccount(onSignOut: () -> Unit) {
        if (_working.value) return
        if (_cart.value.isNotEmpty()) { notify("Finalize a venda ou esvazie o carrinho antes de trocar de conta."); return }
        if (_update.value.checking || hasKnownPendingWrites() || unsettledRequest != null) {
            notify("Aguarde as operações e a sincronização antes de trocar de conta."); return
        }
        _working.value = true
        val trace = logger.startTrace(LogCategory.AUTH, "AUTH_ACCOUNT_SWITCH_REQUEST", screen = "SettingsScreen", component = "PosViewModel", function = "switchAccount")
        viewModelScope.launch(TraceElement(trace)) {
            try {
                withTimeout(15_000) { repository.waitForPendingWrites() }
                check(!hasKnownPendingWrites()) { "Aguarde as gravações pendentes e tente novamente." }
                onSignOut()
                logger.info(LogCategory.AUTH, "AUTH_ACCOUNT_SWITCH_COMPLETED", "Sessão encerrada após conferir pendências", trace)
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                logger.error(LogCategory.AUTH, "AUTH_ACCOUNT_SWITCH_FAILED", "Troca de conta não concluída", error, trace)
                notify("Não foi possível confirmar a sincronização. Mantenha a conta conectada e tente novamente com internet.")
            } finally { _working.value = false }
        }
    }

    private fun formOperation(message: String, onSaved: () -> Unit, category: LogCategory = LogCategory.DATABASE,
        event: String = "FORM_SAVE", block: suspend () -> Unit) {
        if (_working.value) return
        _formError.value = null
        _working.value = true
        val trace = logger.startTrace(category, "${event}_REQUEST", screen = activeSection.value,
            component = "PosViewModel", function = event)
        val step = logger.startStep(trace, category, event, "PosViewModel", event, stuckAfterMs = 30_000)
        viewModelScope.launch(TraceElement(trace)) {
            try { block(); step.finish(); onSaved(); eventChannel.trySend(message) }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) { step.cancel(); throw error }
                step.fail(error)
                _formError.value = error.message ?: "Não foi possível concluir. Tente novamente."
            } finally { _working.value = false }
        }
    }

    private fun notify(message: String) { eventChannel.trySend(message) }

    class Factory(private val repository: PosRepository, private val updates: AppUpdates = UnavailableUpdates,
        private val operatorId: String = "", private val operatorName: String = "",
        private val cashControlRepository: CashControlRepository? = null,
        private val capabilities: OperatorCapabilities = OperatorCapabilities.LEGACY,
        private val logger: AppLogger = NoOpLogger) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PosViewModel::class.java))
            return PosViewModel(repository, updates, operatorId, operatorName, cashControlRepository, capabilities, logger) as T
        }
    }
}

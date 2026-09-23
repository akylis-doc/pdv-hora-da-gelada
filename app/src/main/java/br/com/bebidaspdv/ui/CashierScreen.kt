package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import br.com.bebidaspdv.core.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal fun paymentLabel(method: PaymentMethod): String = when (method) {
    PaymentMethod.CASH -> "Dinheiro"
    PaymentMethod.PIX -> "Pix"
    PaymentMethod.DEBIT -> "Débito"
    PaymentMethod.CREDIT -> "Crédito"
    PaymentMethod.FIADO -> "Fiado"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CashierScreen(model: PosViewModel, onGoToStock: () -> Unit, operatorId: String = "", canOverrideCredit: Boolean = false) {
    val cartRender = model.cartRender.collectAsStateWithLifecycle().value
    val feed by model.products.collectAsStateWithLifecycle()
    val loading by model.productsLoading.collectAsStateWithLifecycle()
    val error by model.productsError.collectAsStateWithLifecycle()
    // The draw receipt and the content must describe the same composition snapshot.
    val cart = model.cart.collectAsStateWithLifecycle().value
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        model.cashierLifecycleChanged(lifecycle.currentState.name)
        val observer = LifecycleEventObserver { _, _ -> model.cashierLifecycleChanged(lifecycle.currentState.name) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SideEffect { cartRender?.let { model.cartStateReceived(it, cart, lifecycle.currentState.name) } }
    val working by model.working.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var showCart by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable(operatorId) { mutableStateOf(false) }
    val context = LocalContext.current
    val favoriteStore = remember(context, operatorId) { LocalFavorites(context, operatorId) }
    var favorites by remember(favoriteStore) { mutableStateOf(favoriteStore.read()) }
    val filtered = remember(feed.value, search, favoritesOnly, favorites) {
        feed.value.filter { it.active && (!favoritesOnly || it.id in favorites) && (it.name.contains(search.trim(), true) || it.barcode.contains(search.trim(), true)) }
            .sortedWith(compareByDescending<Product> { it.id in favorites }.thenBy { it.name.lowercase() })
    }
    val total = cart.sumOf { it.quantity.toLong() * it.saleUnitCents }
    LaunchedEffect(cart.isEmpty(), working) { if (cart.isEmpty() && !working) showCart = false }
    BoxWithConstraints(Modifier.fillMaxSize().drawWithContent {
        cartRender?.let { model.cartRenderStarted(it, cart) }
        drawContent()
        cartRender?.let { model.cartRendered(it, cart) }
    }) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Frente de caixa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Escolha as quantidades e revise a venda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Nome ou código de barras") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { IconButton(onClick = { scanning = true }, enabled = !working) { Icon(Icons.Default.QrCodeScanner, "Ler código de barras") } }, shape = RoundedCornerShape(16.dp))
                    FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly }, label = { Text("Favoritos neste celular") },
                        leadingIcon = { Icon(if (favoritesOnly) Icons.Default.Star else Icons.Default.StarBorder, null, Modifier.size(18.dp)) })
                    Spacer(Modifier.height(10.dp))
                    if (feed.fromCache) InfoNotice("Catálogo local • a lista pode estar incompleta", Icons.Default.CloudOff)
                    when {
                        loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        error != null -> EmptyState("Catálogo indisponível", error ?: "Reabra o aplicativo conectado.")
                        feed.value.isEmpty() -> EmptyState("Seu caixa começa aqui", "Cadastre um produto e registre uma entrada.") {
                            Button(onClick = onGoToStock) { Text("Cadastrar produtos") }
                        }
                        filtered.isEmpty() -> EmptyState("Nenhum produto encontrado", if (favoritesOnly) "Marque a estrela dos produtos que mais usa ou desative o filtro Favoritos." else "Tente outro nome ou código.")
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                            items(filtered, key = Product::id) { product ->
                                ProductRow(product, cart.firstOrNull { it.product.id == product.id }, !working,
                                    favorite = product.id in favorites, onFavorite = { favorites = favoriteStore.toggle(product.id) },
                                    onAdd = { factor, shown -> model.addFromUi(product, factor, shown) },
                                    onRemove = { shown -> model.removeFromUi(product, shown) },
                                ) { quantity, factor, shown -> model.setQuantityFromUi(product, quantity, factor, shown) }
                            }
                        }
                    }
                }
                if (wide) Surface(Modifier.width(370.dp).fillMaxHeight().padding(bottom = 12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) { CartPanel(model, canOverrideCredit, cart) }
            }
            if (!wide) Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${cart.sumOf { it.stockQuantity }} itens avulsos • ${cart.size}/8 produtos", style = MaterialTheme.typography.labelMedium)
                        Text(currency(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    }
                    Button(onClick = { showCart = true }, enabled = cart.isNotEmpty() && !working, modifier = Modifier.heightIn(min = 52.dp)) { Text("Revisar") }
                }
            }
        }
        if (!wide && showCart) ModalBottomSheet(onDismissRequest = { if (!working) showCart = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) { CartPanel(model, canOverrideCredit, cart) }
        }
    }
    if (scanning) BarcodeScannerDialog(onDetected = { search = it; favoritesOnly = false; scanning = false }, onDismiss = { scanning = false })
}

@Composable
private fun ProductRow(product: Product, line: CartLine?, enabled: Boolean, favorite: Boolean, onFavorite: () -> Unit,
    onAdd: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onQuantity: (String, Int, Int) -> Unit) {
    var mode by rememberSaveable(product.id, product.packageSize) { mutableIntStateOf(1) }
    val factor = line?.unitsPerPackage ?: mode
    val quantity = line?.quantity ?: 0
    val price = CartLine(product, 1, factor).saleUnitCents
    Surface(shape = RoundedCornerShape(18.dp), color = if (quantity > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductPhoto(product.imageKey, Modifier.size(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(product.name, fontWeight = FontWeight.SemiBold)
                    Text("Estoque: ${product.stock} ${productUnitShort(product.unitLabel)}", style = MaterialTheme.typography.bodySmall, color = if (product.stock <= product.lowStockThreshold) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${currency(price)} / ${if (factor == 1) productUnitLabel(product.unitLabel) else "caixa"}", fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favorite) "Remover ${product.name} dos favoritos" else "Favoritar ${product.name}",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (product.packageSize > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, product.packageSize).forEach { size -> FilterChip(selected = factor == size, enabled = enabled,
                    onClick = { mode = size; if (line != null) onQuantity(quantity.toString(), size, quantity) }, label = { Text(if (size == 1) productUnitLabel(product.unitLabel).replaceFirstChar(Char::uppercase) else "Caixa • $size ${productUnitShort(product.unitLabel)}") }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onRemove(quantity) }, enabled = enabled && quantity > 0, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Remove, "Remover ${product.name}") }
                OutlinedTextField(if (quantity == 0) "" else quantity.toString(), { raw -> if (raw.isEmpty() || raw.all(Char::isDigit)) onQuantity(raw.ifEmpty { "0" }, factor, quantity) },
                    label = { Text(if (factor == 1) productUnitLabel(product.unitLabel, true).replaceFirstChar(Char::uppercase) else "Caixas") }, placeholder = { Text("0") }, modifier = Modifier.weight(1f), singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                FilledTonalIconButton(onClick = { onAdd(factor, quantity) }, enabled = enabled && (quantity + 1L) * factor <= product.stock && quantity < 9999,
                    modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Add, "Adicionar ${product.name}") }
            }
        }
    }
}

@Composable
private fun CartPanel(model: PosViewModel, canOverrideCredit: Boolean, cart: List<CartLine>) {
    val working by model.working.collectAsStateWithLifecycle()
    val error by model.checkoutError.collectAsStateWithLifecycle()
    val draft by model.checkoutDraft.collectAsStateWithLifecycle()
    val customers by model.customers.collectAsStateWithLifecycle()
    val customerError by model.customerError.collectAsStateWithLifecycle()
    var method by rememberSaveable { mutableStateOf(PaymentMethod.CASH) }
    var amount by rememberSaveable { mutableStateOf("") }
    var showCustomer by remember { mutableStateOf(false) }
    val total = cart.sumOf { it.saleUnitCents * it.quantity }
    val result = remember(total, draft.payments) { runCatching { CalculatePayment()(total, draft.payments) } }
    val summary = result.getOrNull()
    val customer = customers.value.firstOrNull { it.id == draft.customerId }
    val exceedsCreditLimit = (summary?.fiadoCents ?: 0L) > 0 && customer != null && customer.creditLimitCents > 0 && customer.balanceCents + (summary?.fiadoCents ?: 0L) > customer.creditLimitCents
    val dueValid = summary?.fiadoCents == 0L || runCatching {
        val due = LocalDate.parse(draft.dueDate, DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT))
        !due.isBefore(LocalDate.now(ZoneId.of("America/Sao_Paulo")))
    }.getOrDefault(false)
    val ready = summary != null && runCatching { summary.requireComplete(customer) }.isSuccess && amount.isBlank() && dueValid &&
        (!exceedsCreditLimit || canOverrideCredit && draft.creditOverrideReason.trim().length >= 5)
    val otherPayments = draft.payments.filterNot { it.method == method }
    val remainingForMethod = (total - otherPayments.sumOf { it.amountCents }).coerceAtLeast(0)
    val preview = remember(total, draft.payments, method, amount) {
        if (amount.isBlank()) null else runCatching {
            val cents = Money.fromDecimal(amount)
            require(cents > 0) { "Informe um valor maior que zero." }
            CalculatePayment()(total, draft.payments.filterNot { it.method == method } + Payment(method, cents))
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Revisar venda", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = model::clearCart, enabled = cart.isNotEmpty() && !working) { Text("Limpar") }
        }
        if (cart.isEmpty()) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { EmptyState("Carrinho vazio", "Escolha produtos e quantidades no catálogo.") }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(cart, key = { it.product.id }) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProductPhoto(line.product.imageKey, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(line.product.name, fontWeight = FontWeight.SemiBold)
                        Text("${line.quantity} ${if (line.unitsPerPackage == 1) productUnitShort(line.product.unitLabel) else "cx. de ${line.unitsPerPackage} ${productUnitShort(line.product.unitLabel)}"} × ${currency(line.saleUnitCents)}", style = MaterialTheme.typography.bodySmall)
                        if (!line.product.active || line.stockQuantity > line.product.stock || (line.unitsPerPackage != 1 && line.unitsPerPackage != line.product.packageSize)) Text("Produto alterado. Revise no catálogo.", color = MaterialTheme.colorScheme.error)
                    }
                    Text(currency(line.saleUnitCents * line.quantity), fontWeight = FontWeight.Bold)
                }
            }
            item {
                HorizontalDivider()
                OutlinedTextField(draft.customerName, model::setCustomerName, Modifier.fillMaxWidth(), enabled = !working, label = { Text("Cliente por nome ou telefone (opcional)") }, singleLine = true)
                if (customer != null) {
                    Text("Cliente cadastrado selecionado", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text(customer.phone.ifBlank { "Telefone não informado" }, style = MaterialTheme.typography.bodyMedium)
                }
                if (draft.customerName.isNotBlank() && customer == null) customers.value.filter {
                    it.name.contains(draft.customerName.trim(), true) ||
                        draft.customerName.filter(Char::isDigit).takeIf { digits -> digits.isNotEmpty() }?.let { digits -> it.phone.filter(Char::isDigit).contains(digits) } == true
                }.take(5).forEach { candidate ->
                    TextButton(onClick = { model.selectCustomer(candidate) }, enabled = !working) { Text(candidate.name + candidate.phone.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()) }
                }
                customerError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { model.clearFormError(); showCustomer = true }, enabled = !working) { Text("Cadastrar cliente para fiado") }
            }
            item {
                Text("Formas de pagamento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Você pode dividir a compra. O troco é devolvido somente em dinheiro.", style = MaterialTheme.typography.bodySmall)
                PaymentMethodPicker(method, { method = it }, enabled = !working)
                OutlinedTextField(amount, { amount = it.take(16) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Valor em ${paymentLabel(method)} (R$)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !working)
                preview?.getOrNull()?.let { calculated ->
                    Text("Ao aplicar: restante ${currency(calculated.remainingCents)} • troco ${currency(calculated.changeCents)}", style = MaterialTheme.typography.bodySmall)
                }
                preview?.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { amount = decimalInput(remainingForMethod) }, enabled = !working && remainingForMethod > 0) { Text("Preencher restante") }
                    Button(onClick = { model.addPayment(method, amount); amount = "" }, enabled = !working && preview?.isSuccess == true) { Text("Aplicar") }
                }
                if (amount.isNotBlank()) Text("Aplique o valor ou apague o campo antes de finalizar.", style = MaterialTheme.typography.bodySmall)
                if (method == PaymentMethod.FIADO) InfoNotice("Fiado exige selecionar ou cadastrar o cliente. A cobrança ficará na aba Fiado.")
            }
            items(draft.payments, key = { it.method.name }) { payment ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${paymentLabel(payment.method)}: ${currency(payment.amountCents)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { model.removePayment(payment.method) }, enabled = !working) { Icon(Icons.Default.Close, "Remover ${paymentLabel(payment.method)}") }
                }
            }
            if (method == PaymentMethod.FIADO || (summary?.fiadoCents ?: 0) > 0) item {
                OutlinedTextField(draft.dueDate, { model.setDueDate(it.filter { character -> character.isDigit() || character == '/' }.take(10)) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Vencimento do fiado") }, placeholder = { Text("dd/mm/aaaa") },
                    singleLine = true, enabled = !working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    supportingText = { Text("Data prevista para o cliente pagar.") })
                if (!dueValid && (summary?.fiadoCents ?: 0) > 0) Text("Informe uma data válida, a partir de hoje, em dd/mm/aaaa.", color = MaterialTheme.colorScheme.error)
                customer?.let {
                    Text("Fiado em aberto: ${currency(it.balanceCents)}", style = MaterialTheme.typography.bodySmall)
                    Text(if (it.creditLimitCents > 0) "Limite do cliente: ${currency(it.creditLimitCents)}" else "Cliente sem limite de fiado definido", style = MaterialTheme.typography.bodySmall)
                }
                if (exceedsCreditLimit) {
                    Text("Esta venda ultrapassa o limite de fiado do cliente.", color = MaterialTheme.colorScheme.error)
                    if (canOverrideCredit) OutlinedTextField(draft.creditOverrideReason, { model.setCreditOverrideReason(it.take(200)) },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Motivo da autorização do administrador") },
                        supportingText = { Text("Informe pelo menos 5 caracteres. A autorização ficará registrada na venda.") }, enabled = !working)
                    else Text("Peça a um administrador para autorizar ou ajuste os pagamentos.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                summary?.let {
                    Text("Recebido agora: ${currency(it.paidCents)}")
                    if (it.fiadoCents > 0) Text("A prazo: ${currency(it.fiadoCents)}")
                    Text("Restante: ${currency(it.remainingCents)}", color = if (it.remainingCents > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (it.changeCents > 0) Text("Troco em dinheiro: ${currency(it.changeCents)}", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    if (it.fiadoCents > 0 && customer == null) Text("Selecione um cliente cadastrado para finalizar o fiado.", color = MaterialTheme.colorScheme.error)
                }
                result.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total da venda", style = MaterialTheme.typography.titleMedium)
            Text(currency(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
        Button(onClick = model::checkoutFromUi, enabled = !working && ready && cart.isNotEmpty() && cart.all { it.product.active && it.stockQuantity <= it.product.stock },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 58.dp), shape = RoundedCornerShape(16.dp)) {
            if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Icon(Icons.Default.CheckCircle, null)
            Spacer(Modifier.width(8.dp)); Text(if (working) "Registrando…" else "Finalizar venda", fontWeight = FontWeight.Bold)
        }
    }
    if (showCustomer) CustomerDialog(model, draft.customerName) { showCustomer = false }
}

@Composable
internal fun PaymentMethodPicker(selected: PaymentMethod, onSelect: (PaymentMethod) -> Unit, enabled: Boolean, includeCredit: Boolean = true) {
    Column {
        PaymentMethod.entries.filter { includeCredit || it != PaymentMethod.FIADO }.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { method -> FilterChip(selected == method, { onSelect(method) }, enabled = enabled, label = { Text(paymentLabel(method)) }) } }
        }
    }
}

@Composable
private fun CustomerDialog(model: PosViewModel, initialName: String, onDismiss: () -> Unit) {
    val working by model.working.collectAsStateWithLifecycle()
    val error by model.formError.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(initialName) }
    var phone by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text("Novo cliente") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nome") }, enabled = !working, singleLine = true)
            OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("Telefone (opcional)") }, enabled = !working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            Text("O cadastro identifica as vendas fiadas e seus recebimentos.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { model.saveCustomer(name, phone, onDismiss) }, enabled = !working && name.isNotBlank()) { Text(if (working) "Salvando…" else "Cadastrar") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}

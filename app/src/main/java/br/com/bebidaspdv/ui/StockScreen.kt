package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.bebidaspdv.core.Product
import br.com.bebidaspdv.core.Money
import br.com.bebidaspdv.core.PurchaseCost

private enum class StockFilter { ALL, LOW, MISSING }

@Composable
internal fun StockScreen(model: PosViewModel, canEditPrices: Boolean = true, canDeleteProducts: Boolean = true) {
    val feed by model.products.collectAsStateWithLifecycle()
    val loading by model.productsLoading.collectAsStateWithLifecycle()
    val error by model.productsError.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val formError by model.formError.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StockFilter.ALL) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Product?>(null) }
    var receiving by remember { mutableStateOf<Product?>(null) }
    var deleting by remember { mutableStateOf<Product?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val active = remember(feed.value) { feed.value.filter(Product::active) }
    val missing = active.count { it.stock <= 0 }
    val low = active.count { it.stock in 1..it.lowStockThreshold }
    val filtered = remember(active, search, filter) {
        active.filter { product ->
            (product.name.contains(search.trim(), true) || product.barcode.contains(search.trim(), true)) && when (filter) {
                StockFilter.ALL -> true
                StockFilter.LOW -> product.stock in 1..product.lowStockThreshold
                StockFilter.MISSING -> product.stock <= 0
            }
        }.sortedBy { it.name.lowercase() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(top = 8.dp)) {
            Text("Seu estoque", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Produtos, preços e reposição.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { model.clearFormError(); editing = null; showForm = true }, enabled = !working && canEditPrices, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Cadastrar produto")
        }
        OutlinedTextField(search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Buscar por nome ou código") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = { scanning = true }) { Icon(Icons.Default.QrCodeScanner, "Ler código de barras") } }, shape = RoundedCornerShape(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == StockFilter.ALL, onClick = { filter = StockFilter.ALL }, label = { Text("Todos (${active.size})") })
            FilterChip(selected = filter == StockFilter.LOW, onClick = { filter = StockFilter.LOW }, label = { Text("Acabando ($low)") }, leadingIcon = { Icon(Icons.Default.NotificationsActive, null, Modifier.size(18.dp)) })
            FilterChip(selected = filter == StockFilter.MISSING, onClick = { filter = StockFilter.MISSING }, label = { Text("Em falta ($missing)") })
        }
        if (feed.fromCache) InfoNotice("Estoque local • pode mudar após a sincronização", Icons.Default.CloudOff)
        if (active.any { it.stock < 0 }) InfoNotice("Há saldo negativo. Confira as vendas dos dois aparelhos e faça a conferência física do estoque.", Icons.Default.ErrorOutline)
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            error != null -> EmptyState("Estoque indisponível", error ?: "Tente abrir o aplicativo novamente.", Icons.Default.ErrorOutline)
            active.isEmpty() -> EmptyState("Prateleiras prontas para começar", "Cadastre o primeiro produto. O estoque inicial é zero; use Entrada para repor.", Icons.Default.Inventory2)
            filtered.isEmpty() -> EmptyState("Nenhum produto nesta seleção", if (search.isBlank() && filter != StockFilter.ALL) "Nenhum alerta de estoque nesta categoria." else "Revise a busca ou selecione Todos.", Icons.Default.SearchOff)
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(filtered, key = Product::id) { product ->
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                ProductPhoto(product.imageKey, Modifier.size(64.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(product.barcode.ifBlank { "Sem código de barras" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(productCategoryLabel(product.category), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (product.stock <= product.lowStockThreshold) Text(if (product.stock <= 0) "Em falta" else "Estoque acabando", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                                Surface(shape = RoundedCornerShape(12.dp), color = if (product.stock <= product.lowStockThreshold) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                                    Text("${product.stock} ${productUnitShort(product.unitLabel)}", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Column(Modifier.weight(1f)) { Text("Custo por ${productUnitLabel(product.unitLabel)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(currency(product.costCents), fontWeight = FontWeight.SemiBold) }
                                Column(Modifier.weight(1f)) { Text("Venda por ${productUnitLabel(product.unitLabel)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(currency(product.saleCents), fontWeight = FontWeight.SemiBold) }
                            }
                            if (product.packageSize > 1) {
                                Text("Caixa com ${product.packageSize} ${productUnitShort(product.unitLabel)} • venda ${currency(product.packageSaleCents.takeIf { it > 0 } ?: (product.saleCents * product.packageSize))}", style = MaterialTheme.typography.bodySmall)
                                if (product.purchaseByPackage) Text("Custo da caixa: ${currency(product.packageCostCents)}", style = MaterialTheme.typography.bodySmall)
                                if (product.stock >= 0) Text("Saldo equivale a ${product.stock / product.packageSize} caixa(s) + ${product.stock % product.packageSize} ${productUnitShort(product.unitLabel)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { model.clearFormError(); editing = product; showForm = true }, enabled = !working && canEditPrices, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Editar")
                                }
                                FilledTonalButton(onClick = { model.clearFormError(); receiving = product }, enabled = !working, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Icon(Icons.Default.AddBox, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Entrada")
                                }
                                IconButton(onClick = { model.clearFormError(); deleting = product }, enabled = !working && canDeleteProducts, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Excluir ${product.name}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showForm) {
        ProductDialog(editing, working, formError, onDismiss = { if (!working) showForm = false }, onSave = { form ->
            model.saveProductDetails(original = editing, form = form, onSaved = { showForm = false })
        })
    }
    if (scanning) BarcodeScannerDialog(onDetected = { search = it; scanning = false }, onDismiss = { scanning = false })
    receiving?.let { product ->
        val current = active.firstOrNull { it.id == product.id } ?: product
        ReceiveDialog(current, working, formError, onDismiss = { if (!working) receiving = null }, onSave = { count, multiplier ->
            model.receiveStock(product = current, quantity = count, unitsPerPackage = multiplier, onSaved = { receiving = null })
        })
    }
    deleting?.let { product ->
        AlertDialog(onDismissRequest = { if (!working) deleting = null }, title = { Text("Excluir produto?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${product.name} será retirado do estoque e do caixa. Para voltar a vendê-lo, será necessário fazer um novo cadastro.")
                    if (product.stock != 0) Text("Saldo local atual: ${product.stock} ${productUnitLabel(product.unitLabel, true)}. Confira esse saldo antes de excluir.")
                    Text("As vendas, as entradas e os valores históricos serão preservados.", style = MaterialTheme.typography.bodySmall)
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { Button(onClick = { model.deleteProduct(product, onDeleted = { deleting = null }) }, enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Excluir produto")
            } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !working) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ProductDialog(product: Product?, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (ProductForm) -> Unit) {
    var name by rememberSaveable(product?.id) { mutableStateOf(product?.name ?: "") }
    var barcode by rememberSaveable(product?.id) { mutableStateOf(product?.barcode ?: "") }
    var unitCost by rememberSaveable(product?.id) { mutableStateOf(product?.let { decimalInput(it.costCents) } ?: "") }
    var boxCost by rememberSaveable(product?.id) { mutableStateOf(product?.takeIf { it.purchaseByPackage }?.let { decimalInput(it.packageCostCents) } ?: "") }
    var byPackage by rememberSaveable(product?.id) { mutableStateOf(product?.purchaseByPackage ?: false) }
    var category by rememberSaveable(product?.id) { mutableStateOf(product?.category ?: "OUTROS") }
    var unitLabel by rememberSaveable(product?.id) { mutableStateOf(product?.unitLabel ?: "UNIDADE") }
    var scanning by remember { mutableStateOf(false) }
    var price by rememberSaveable(product?.id) { mutableStateOf(product?.let { decimalInput(it.saleCents) } ?: "") }
    var imageKey by rememberSaveable(product?.id) { mutableStateOf(product?.imageKey ?: "") }
    var threshold by rememberSaveable(product?.id) { mutableStateOf((product?.lowStockThreshold ?: 5).toString()) }
    var packageSize by rememberSaveable(product?.id) { mutableStateOf((product?.packageSize ?: 1).toString()) }
    var packagePrice by rememberSaveable(product?.id) { mutableStateOf(product?.packageSaleCents?.takeIf { it > 0 }?.let(::decimalInput) ?: "") }
    val size = packageSize.toIntOrNull() ?: 0
    val cost = if (byPackage) boxCost else unitCost
    val normalizedCost = if (byPackage) runCatching { PurchaseCost.unitCents(Money.fromDecimal(boxCost), size) }.getOrNull() else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product == null) "Novo produto" else "Editar produto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductPhotoField(imageKey, enabled = !working, onSelected = { imageKey = it })
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nome do produto") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !working)
                OutlinedTextField(barcode, { barcode = it.take(80) }, label = { Text("Código de barras (opcional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !working,
                    trailingIcon = { IconButton(onClick = { scanning = true }, enabled = !working) { Icon(Icons.Default.QrCodeScanner, "Ler código do produto") } })
                Text("Categoria", style = MaterialTheme.typography.labelLarge)
                listOf("BEBIDA", "ALIMENTO", "CIGARRO", "OUTROS").chunked(2).forEach { options ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { options.forEach { value ->
                        FilterChip(category == value, { category = value }, enabled = !working, label = { Text(productCategoryLabel(value)) })
                    } }
                }
                Text("Como cada item é contado?", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("UNIDADE", "MACO", "PACOTE").forEach { value -> FilterChip(unitLabel == value, { unitLabel = value }, enabled = !working, label = { Text(productUnitLabel(value).replaceFirstChar(Char::uppercase)) }) }
                }
                OutlinedTextField(packageSize, { packageSize = it.filter(Char::isDigit).take(4) }, label = { Text("${productUnitLabel(unitLabel, true).replaceFirstChar(Char::uppercase)} por caixa") }, supportingText = { Text("Use 1 sem caixa. Informe, por exemplo, 12 latas ou 10 maços por caixa.") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !working)
                Text("Como o custo de compra foi informado?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!byPackage, { if (byPackage && normalizedCost != null) unitCost = decimalInput(normalizedCost); byPackage = false }, enabled = !working, label = { Text("Por ${productUnitLabel(unitLabel)}") })
                    FilterChip(byPackage, { byPackage = true }, enabled = !working, label = { Text("Por caixa") })
                }
                OutlinedTextField(cost, { if (byPackage) boxCost = it.take(16) else unitCost = it.take(16) }, label = { Text(if (byPackage) "Custo da caixa (R$)" else "Custo por ${productUnitLabel(unitLabel)} (R$)") }, placeholder = { Text("0,00") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !working)
                if (byPackage) {
                    if (size <= 1) Text("Informe mais de um item por caixa.", color = MaterialTheme.colorScheme.error)
                    normalizedCost?.let { Text("Custo por ${productUnitLabel(unitLabel)}: ${currency(it)}", fontWeight = FontWeight.SemiBold) }
                    Text("O custo da caixa é preservado. O custo dos itens avulsos é dividido e arredondado para centavos.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(price, { price = it.take(16) }, label = { Text("Venda por ${productUnitLabel(unitLabel)} (R$)") }, placeholder = { Text("0,00") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !working)
                if (size > 1) OutlinedTextField(packagePrice, { packagePrice = it.take(16) }, label = { Text("Venda da caixa (R$, opcional)") }, supportingText = { Text("Deixe vazio para somar os preços dos itens avulsos.") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !working)
                OutlinedTextField(threshold, { threshold = it.filter(Char::isDigit).take(4) }, label = { Text("Avisar com quantos ${productUnitLabel(unitLabel, true)}?") }, supportingText = { Text("O alerta aparece com este saldo ou menos. Use 0 para avisar somente quando faltar.") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !working)
                Text(if (product == null) "Após salvar, registre uma entrada para adicionar itens ao estoque." else "A alteração de custo vale para as próximas operações. As vendas anteriores mantêm seu custo histórico.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(ProductForm(name, barcode, cost, price, imageKey, threshold, packageSize, if (size > 1) packagePrice else "", category, unitLabel, byPackage)) }, enabled = !working && name.isNotBlank() && cost.isNotBlank() && price.isNotBlank() && (!byPackage || size > 1)) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Salvar produto")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } },
    )
    if (scanning) BarcodeScannerDialog(onDetected = { barcode = it; scanning = false }, onDismiss = { scanning = false })
}

@Composable
private fun ReceiveDialog(product: Product, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var quantity by rememberSaveable(product.id) { mutableStateOf("") }
    var byPackage by rememberSaveable(product.id) { mutableStateOf(product.purchaseByPackage) }
    val multiplier = if (byPackage && product.packageSize > 1) product.packageSize else 1
    val number = quantity.toIntOrNull()
    val units = number?.toLong()?.times(multiplier)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entrada de estoque") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProductPhoto(product.imageKey, Modifier.size(56.dp))
                    Text(product.name, fontWeight = FontWeight.Bold)
                }
                Text("Saldo local: ${product.stock} ${productUnitLabel(product.unitLabel, true)}\nCusto por ${productUnitLabel(product.unitLabel)}: ${currency(product.costCents)}", style = MaterialTheme.typography.bodyMedium)
                if (product.packageSize > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byPackage, onClick = { byPackage = false }, enabled = !working, label = { Text(productUnitLabel(product.unitLabel, true).replaceFirstChar(Char::uppercase)) })
                    FilterChip(selected = byPackage, onClick = { byPackage = true }, enabled = !working, label = { Text("Caixas (${product.packageSize})") })
                }
                OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit).take(4) }, label = { Text(if (multiplier > 1) "Quantidade de caixas" else "Quantidade de ${productUnitLabel(product.unitLabel, true)}") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (units != null && units > 0) {
                    Text("Adicionar $units ${productUnitLabel(product.unitLabel, true)} ao estoque", style = MaterialTheme.typography.bodyMedium)
                    val entryCost = if (multiplier > 1 && product.purchaseByPackage) product.packageCostCents * (number ?: 0) else product.costCents * units
                    Text("Valor da entrada: ${currency(entryCost)}", fontWeight = FontWeight.SemiBold)
                    if (units > 9999) Text("Cada entrada aceita até 9.999 itens avulsos. Divida a reposição em duas entradas.", color = MaterialTheme.colorScheme.error)
                }
                Text("A entrada aumenta o estoque e não compõe o faturamento. Para mudar o custo de compra, edite o produto antes da entrada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { onSave(quantity, multiplier) }, enabled = !working && units != null && units in 1L..9999L) { if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Confirmar entrada") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } },
    )
}

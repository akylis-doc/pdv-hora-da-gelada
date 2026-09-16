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
import br.com.bebidaspdv.core.*
import java.time.format.DateTimeFormatter

private val creditDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")

@Composable
internal fun CreditScreen(model: PosViewModel) {
    val salesFeed by model.creditSales.collectAsStateWithLifecycle()
    val paymentsFeed by model.debtPayments.collectAsStateWithLifecycle()
    val loading by model.creditLoading.collectAsStateWithLifecycle()
    val error by model.creditError.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val formError by model.formError.collectAsStateWithLifecycle()
    val operations by model.operations.collectAsStateWithLifecycle()
    val pendingDebts by model.pendingDebtPayments.collectAsStateWithLifecycle()
    val customerFeed by model.customers.collectAsStateWithLifecycle()
    val accountFeed by model.accounts.collectAsStateWithLifecycle()
    val month by model.month.collectAsStateWithLifecycle()
    val pendingSaleIds = remember(pendingDebts) { pendingDebts.map { it.saleId }.toSet() }
    var search by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("Em aberto") }
    var expandedCustomers by rememberSaveable { mutableStateOf(listOf<String>()) }
    var receiving by remember { mutableStateOf<Movement?>(null) }
    var details by remember { mutableStateOf<Movement?>(null) }
    var editingCustomer by remember { mutableStateOf<Customer?>(null) }
    LaunchedEffect(mode) { model.setCreditMode(mode) }
    val sales = remember(salesFeed.value) { salesFeed.value.filter { it.type == MovementType.SAIDA && it.receivableCents > 0 } }
    val paymentsBySale = remember(paymentsFeed.value) { paymentsFeed.value.groupBy { it.saleId } }
    val balances = remember(sales, accountFeed) { sales.associate { sale -> sale.id to
        (accountFeed.value.firstOrNull { it.saleId == sale.id }?.remainingCents ?: sale.receivableCents) } }
    val openSales = remember(sales, balances) { sales.filter { balances.getValue(it.id) > 0 } }
    val openCustomers = remember(openSales) { openSales.map { it.customerId.ifBlank { "sale:${it.id}" } }.distinct().size }
    val groups = remember(sales, balances, mode, search, customerFeed) {
        sales.filter { sale ->
            (if (mode == "Quitados") balances.getValue(sale.id) == 0L else balances.getValue(sale.id) > 0L) &&
                (search.isBlank() || sale.customerLabel().contains(search.trim(), true) || sale.id.contains(search.trim(), true) ||
                    customerFeed.value.firstOrNull { it.id == sale.customerId }?.phone?.contains(search.trim(), true) == true)
        }.groupBy { it.customerId.ifBlank { "sale:${it.id}" } }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<Movement>>> { entry -> entry.value.sumOf { balances.getValue(it.id) } }.thenBy { it.value.first().customerLabel().lowercase() })
    }
    val filteredPayments = remember(paymentsFeed.value, sales, search, customerFeed) {
        val saleById = sales.associateBy { it.id }
        paymentsFeed.value.filter { payment ->
            val sale = saleById[payment.saleId]
            val customer = customerFeed.value.firstOrNull { it.id == payment.customerId }
            search.isBlank() || sale?.customerLabel()?.contains(search.trim(), true) == true || payment.saleId.contains(search.trim(), true) ||
                customer?.name?.contains(search.trim(), true) == true || customer?.phone?.contains(search.trim(), true) == true
        }.sortedByDescending { it.occurredAt }
    }
    val receivingBlock = when {
        loading -> "Aguarde o carregamento dos saldos."
        error != null -> "Resolva o erro de sincronização antes de receber."
        salesFeed.fromCache || paymentsFeed.fromCache -> "Conecte-se à internet e aguarde os saldos dos dois celulares para receber."
        salesFeed.pendingWrites || paymentsFeed.pendingWrites || operations.any { it.state == SyncState.PENDING } -> "Aguarde a confirmação das operações pendentes antes de receber."
        else -> null
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Fiado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Saldos por cliente, com histórico de cada recebimento.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (pendingDebts.isNotEmpty()) item {
            InfoNotice("Use Conferir pendência antes de registrar outro recebimento da mesma venda. Se o pagamento já foi confirmado, não será registrado novamente.", Icons.Default.CloudUpload)
            formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(pendingDebts, key = { "pending-${it.id}" }) { pending ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${currency(pending.amountCents)} • ${pending.method.paymentLabel()}", fontWeight = FontWeight.Bold)
                    Text("Venda ${pending.saleId.take(8)}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { model.clearFormError(); model.retryPendingDebt(pending) }, enabled = !working) { Text("Conferir pendência") }
                }
            }
        }
        error?.let { message -> item { InfoNotice(message, Icons.Default.ErrorOutline) } }
        if (!loading && error == null) {
            if (salesFeed.fromCache || paymentsFeed.fromCache) item { InfoNotice("Saldos salvos neste aparelho; podem mudar após a sincronização. Para registrar um recebimento, conecte-se à internet.", Icons.Default.CloudOff) }
            if (salesFeed.pendingWrites || paymentsFeed.pendingWrites) item { InfoNotice("Há operações aguardando confirmação. O recebimento será liberado quando a sincronização terminar.", Icons.Default.CloudUpload) }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Saldo geral a receber", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(currency(customerFeed.value.sumOf { it.balanceCents }), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Inclui fiados de todos os meses disponíveis.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(openCustomers.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Clientes com saldo", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(openSales.size.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Vendas em aberto", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(search, { search = it.take(160) }, label = { Text("Buscar nome, telefone ou venda") }, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Em aberto", "Quitados", "Recebimentos", "Clientes").forEach { title ->
                    FilterChip(selected = mode == title, onClick = { mode = title }, label = { Text(title) })
                }
            }
        }
        if (mode == "Quitados" || mode == "Recebimentos") item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.changeMonth(-1) }) { Text("Anterior") }
                Text(month.format(DateTimeFormatter.ofPattern("MM/yyyy")))
                TextButton(onClick = { model.changeMonth(1) }, enabled = month < java.time.YearMonth.now(businessZone)) { Text("Próximo") }
            }
            Text("Histórico de recebimentos do mês selecionado.", style = MaterialTheme.typography.bodySmall)
        }
        if (mode == "Clientes") items(customerFeed.value.filter { search.isBlank() || it.name.contains(search, true) || it.phone.contains(search, true) }, key = { "edit-${it.id}" }) { customer ->
            OutlinedCard(onClick = { model.clearFormError(); editingCustomer = customer }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(customer.name, fontWeight = FontWeight.Bold)
                    Text(customer.phone.ifBlank { "Telefone não informado" })
                    Text("Limite: ${if (customer.creditLimitCents == 0L) "sem limite definido" else currency(customer.creditLimitCents)}")
                    Text("Saldo a receber: ${currency(customer.balanceCents)}")
                    Text("Toque para editar", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!loading && error == null && mode == "Recebimentos") {
            if (filteredPayments.isEmpty()) item { EmptyState("Nenhum recebimento encontrado", "Os pagamentos de fiado aparecerão aqui após a confirmação.", Icons.Default.Payments) }
            items(filteredPayments, key = { "payment-${it.id}" }) { payment ->
                val sale = sales.firstOrNull { it.id == payment.saleId }
                DebtPaymentCard(payment, customerFeed.value.firstOrNull { it.id == payment.customerId }?.name ?: sale?.customerLabel() ?: "Cliente da venda ${payment.saleId.take(8)}")
            }
        } else if (!loading && error == null && mode != "Clientes") {
            if (groups.isEmpty()) item {
                EmptyState(if (mode == "Quitados") "Nenhum fiado quitado" else "Nenhum fiado em aberto", if (search.isBlank()) "Vendas feitas no fiado aparecerão aqui." else "Confira o nome ou código pesquisado.", Icons.Default.AccountBalanceWallet)
            }
            groups.forEach { (customerId, customerSales) ->
                val expanded = customerId in expandedCustomers
                item(key = "customer-$customerId") {
                    OutlinedCard(onClick = { expandedCustomers = if (expanded) expandedCustomers - customerId else expandedCustomers + customerId }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(customerSales.maxBy { it.occurredAt }.customerLabel(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(customerFeed.value.firstOrNull { it.id == customerId }?.phone?.ifBlank { "Telefone não informado" } ?: "Telefone não informado")
                                Text("${customerSales.size} venda(s) ${if (mode == "Quitados") "quitada(s)" else "em aberto"}", style = MaterialTheme.typography.bodySmall)
                                Text(if (mode == "Quitados") "Saldo quitado" else "A receber: ${currency(customerSales.sumOf { balances.getValue(it.id) })}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Recolher vendas do cliente" else "Ver vendas do cliente")
                        }
                    }
                }
                if (expanded) items(customerSales.sortedBy { it.occurredAt }, key = { "credit-sale-${it.id}" }) { sale ->
                    CreditSaleCard(sale, paymentsBySale[sale.id].orEmpty(), balances.getValue(sale.id), !working && receivingBlock == null && sale.id !in pendingSaleIds, onDetails = { details = sale }, onReceive = { model.clearFormError(); receiving = sale })
                }
            }
            if (openSales.isNotEmpty() && receivingBlock != null && error == null && !salesFeed.fromCache && !paymentsFeed.fromCache) item { InfoNotice(receivingBlock, Icons.Default.Sync) }
        }
    }

    details?.let { SaleDetailsDialog(it, onDismiss = { details = null }) }
    editingCustomer?.let { customer ->
        EditCustomerDialog(customer, model.capabilities.isAdmin, working, formError,
            onDismiss = { if (!working) editingCustomer = null },
            onSave = { name, phone, limit -> model.editCustomer(customer, name, phone, limit) { editingCustomer = null } })
    }
    receiving?.let { original ->
        val sale = sales.firstOrNull { it.id == original.id }
        DebtSettlementDialog(
            sale = sale ?: original,
            balance = sale?.let { balances.getValue(it.id) } ?: 0,
            working = working,
            blockReason = when {
                sale == null -> "Venda indisponível. Atualize os saldos antes de continuar."
                sale.id in pendingSaleIds -> "Use Conferir pendência na aba Fiado antes de registrar outro recebimento desta venda."
                else -> receivingBlock
            },
            error = formError,
            onDismiss = { if (!working) receiving = null },
            onSave = { amount, method -> sale?.let { model.settleDebt(it, amount, method) { receiving = null } } },
        )
    }
}

@Composable
private fun CreditSaleCard(sale: Movement, payments: List<DebtPayment>, balance: Long, canReceive: Boolean, onDetails: () -> Unit, onReceive: () -> Unit) {
    var showReceipts by rememberSaveable(sale.id) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(start = 10.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(sale.occurredAt.atZone(businessZone).format(creditDateFormat), fontWeight = FontWeight.Bold)
            Text("Venda ${sale.id.take(8)} • ${sale.operatorLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReportMoneyRow("Total da compra", sale.totalCents)
            ReportMoneyRow("Fiado na compra", sale.receivableCents)
            ReportMoneyRow("Fiado já recebido", sale.receivableCents - balance)
            ReportMoneyRow("Saldo a receber", balance, true)
            if (sale.dueEpochDay > 0) {
                val due = java.time.LocalDate.ofEpochDay(sale.dueEpochDay)
                Text("Vencimento: ${due.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}" +
                    if (balance > 0 && due.isBefore(java.time.LocalDate.now(businessZone))) " • ATRASADO" else "",
                    color = if (balance > 0 && due.isBefore(java.time.LocalDate.now(businessZone))) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Ver venda") }
                if (balance > 0) Button(onClick = onReceive, enabled = canReceive, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Receber") }
            }
            if (payments.isNotEmpty()) {
                TextButton(onClick = { showReceipts = !showReceipts }) {
                    Text(if (showReceipts) "Ocultar recebimentos" else "Ver ${payments.size} recebimento(s)")
                    Icon(if (showReceipts) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (showReceipts) payments.sortedByDescending { it.occurredAt }.forEach { payment ->
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ReportMoneyRow(payment.method.paymentLabel(), payment.amountCents)
                        Text(payment.occurredAt.atZone(businessZone).format(creditDateFormat), style = MaterialTheme.typography.bodySmall)
                        Text("Recebido por ${payment.operatorName.ifBlank { payment.operatorId }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtPaymentCard(payment: DebtPayment, customerName: String) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(customerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ReportMoneyRow(payment.method.paymentLabel(), payment.amountCents, true)
            Text(payment.occurredAt.atZone(businessZone).format(creditDateFormat), style = MaterialTheme.typography.bodySmall)
            Text("Recebido por ${payment.operatorName.ifBlank { payment.operatorId }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Venda ${payment.saleId.take(8)} • Recibo ${payment.id.take(8)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DebtSettlementDialog(sale: Movement, balance: Long, working: Boolean, blockReason: String?, error: String?, onDismiss: () -> Unit, onSave: (String, PaymentMethod) -> Unit) {
    var amount by rememberSaveable(sale.id) { mutableStateOf("") }
    var methodName by rememberSaveable(sale.id) { mutableStateOf(PaymentMethod.CASH.name) }
    val method = PaymentMethod.valueOf(methodName)
    val cents = remember(amount) { runCatching { Money.fromDecimal(amount) }.getOrNull() }
    val valid = cents != null && cents > 0 && cents <= balance && cents <= BusinessLimits.MAX_MONEY_CENTS
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Receber fiado") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(sale.customerLabel(), fontWeight = FontWeight.Bold)
            Text("Venda de ${sale.occurredAt.atZone(businessZone).format(creditDateFormat)}", style = MaterialTheme.typography.bodySmall)
            ReportMoneyRow("Saldo atualizado", balance, true)
            Text("Forma de pagamento", style = MaterialTheme.typography.labelLarge)
            listOf(PaymentMethod.CASH, PaymentMethod.PIX, PaymentMethod.DEBIT, PaymentMethod.CREDIT).forEach { option ->
                Surface(onClick = { if (!working) methodName = option.name }, shape = RoundedCornerShape(12.dp), color = if (method == option) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = method == option, onClick = { methodName = option.name }, enabled = !working)
                        Text(option.paymentLabel())
                    }
                }
            }
            OutlinedTextField(amount, { amount = it.take(16) }, label = { Text("Valor recebido (R$)") }, placeholder = { Text("0,00") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount.isNotBlank() && !valid)
            TextButton(onClick = { amount = decimalInput(balance) }, enabled = !working && balance > 0) { Text("Preencher saldo total") }
            if (cents != null && cents > balance) Text("O recebimento não pode superar o saldo. Informe somente o valor usado para quitar este fiado.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (valid) ReportMoneyRow("Saldo após receber", balance - cents!!)
            if (balance == 0L) Text("Esta venda já está quitada.", color = MaterialTheme.colorScheme.primary)
            Text("Você pode receber uma parte agora. A confirmação consulta o saldo na internet para evitar que os dois celulares recebam além do devido.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            blockReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        Button(onClick = { onSave(amount, method) }, enabled = !working && valid && blockReason == null) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Confirmar recebimento")
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}

package br.com.bebidaspdv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.bebidaspdv.core.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

private val reportDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR"))
private val expenseDateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

@Composable
internal fun ReportsScreen(model: PosViewModel, onReverse: (String) -> Unit = {}) {
    val month by model.month.collectAsStateWithLifecycle()
    val state by model.reports.collectAsStateWithLifecycle()
    val products by model.products.collectAsStateWithLifecycle()
    val payments = Feed(state.receipts, state.fromCache, state.pendingWrites)
    val creditLoading = state.loading
    val creditError = state.error
    val working by model.working.collectAsStateWithLifecycle()
    val formError by model.formError.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf("Resumo") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedSale by remember { mutableStateOf<Movement?>(null) }
    var showExpense by remember { mutableStateOf(false) }
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM 'de' yyyy", Locale.forLanguageTag("pt-BR")) }
    val sales = remember(state.movements, month) { salesInMonth(state.movements, month).sortedByDescending { it.occurredAt } }
    val expenses = remember(state.expenses, month) { state.expenses.filter { YearMonth.from(it.occurredAt.atZone(businessZone)) == month }.sortedByDescending { it.occurredAt } }
    val expenseTotal = remember(expenses) { expenses.sumOf { it.amountCents } }
    val rankings = remember(products.value, sales) { productPerformance(products.value, sales) }
    val accounts = remember(sales) { operatorPerformance(sales) }
    val receipts = remember(sales, payments.value, month) { monthReceipts(sales, payments.value, month) }
    val filteredSales = remember(sales, search) { sales.filter { search.isBlank() || it.customerLabel().contains(search.trim(), true) || it.operatorLabel().contains(search.trim(), true) || it.id.contains(search.trim(), true) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column {
                Text("Visão do negócio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Vendas, resultados e despesas do mês.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { model.changeMonth(-1) }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mês anterior") }
                    Text(month.format(monthFormatter).replaceFirstChar { it.titlecase(Locale.forLanguageTag("pt-BR")) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { model.changeMonth(1) }, enabled = month < YearMonth.now(businessZone), modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próximo mês") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Resumo", "Produtos", "Vendas", "Por conta", "Despesas").forEach { title ->
                    FilterChip(selected = section == title, onClick = { section = title }, label = { Text(title) })
                }
            }
        }
        if (state.loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Calculando o resultado do mês…", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { message -> item { InfoNotice(message, Icons.Default.ErrorOutline) } }
        if (state.fromCache) item { InfoNotice("Dados salvos neste aparelho. O relatório pode mudar quando as vendas do outro celular forem sincronizadas.", Icons.Default.CloudOff) }
        if (state.pendingWrites) item { InfoNotice("Inclui operações aguardando confirmação. Confira a sincronização no topo.", Icons.Default.CloudUpload) }
        state.report?.let { report ->
            when (section) {
                "Resumo" -> {
                    item { ReportHeading("Valores financeiros") }
                    item {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FinancialCard("Recebido no mês", report.revenueCents, "Pagamentos recebidos, descontados os estornos", Icons.Default.Payments, Modifier.weight(1f))
                                FinancialCard("Custos • CMV", report.costCents, "Custo histórico proporcional ao valor recebido", Icons.Default.Inventory2, Modifier.weight(1f))
                                FinancialCard("Resultado líquido recebido", report.profitCents - expenseTotal, "Recebido − CMV proporcional − despesas", Icons.AutoMirrored.Filled.TrendingUp, Modifier.weight(1f), true)
                            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                FinancialCard("Recebido no mês", report.revenueCents, "Pagamentos recebidos, descontados os estornos", Icons.Default.Payments)
                                FinancialCard("Custos • CMV", report.costCents, "Custo histórico proporcional ao valor recebido", Icons.Default.Inventory2)
                                FinancialCard("Resultado líquido recebido", report.profitCents - expenseTotal, "Recebido − CMV proporcional − despesas", Icons.AutoMirrored.Filled.TrendingUp, highlighted = true)
                            }
                        }
                    }
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReportMoneyRow("Lucro após CMV", report.profitCents)
                                ReportMoneyRow("Despesas operacionais", expenseTotal)
                                Text("O resultado considera as despesas cadastradas. Registre também taxas e impostos em Outros, quando houver.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReportHeading("Volume de vendas registradas")
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                QuantityCard("Unidades vendidas", report.unitsSold.toLong(), Modifier.weight(1f))
                                QuantityCard("Vendas realizadas", report.salesCount.toLong(), Modifier.weight(1f))
                            }
                            Text("Caixas são convertidas em unidades. O volume mostra as vendas originais; os estornos são descontados dos valores recebidos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { ReportHeading("Recebimentos do mês") }
                    if (creditLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else if (creditError != null) item { InfoNotice(creditError ?: "Não foi possível consultar os recebimentos.", Icons.Default.ErrorOutline) }
                    else {
                        if (payments.fromCache) item { InfoNotice("Recebimentos consultados no cache local. Conecte-se para conferir valores dos dois celulares.", Icons.Default.CloudOff) }
                        item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ReportMoneyRow("Pagamentos nas vendas", receipts.immediateCents)
                                    ReportMoneyRow("Fiado recebido neste mês", receipts.creditPaymentsCents)
                                    HorizontalDivider()
                                    ReportMoneyRow("Estornos devolvidos no mês", state.reversals.sumOf { it.refundByMethod.values.sum() })
                                    ReportMoneyRow("Total recebido após estornos", report.revenueCents, emphasized = true)
                                    Text("Troco já descontado. Fiado entra neste resumo no mês de cada recebimento, inclusive vendas antigas. Cartões seguem a data da venda; repasses e taxas não são conciliados automaticamente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (receipts.salesWithoutPaymentDetails > 0) item { InfoNotice("${receipts.salesWithoutPaymentDetails} venda(s) antiga(s) sem forma de pagamento registrada: são consideradas recebidas na data da venda no resumo; não há distribuição por forma de pagamento.", Icons.Default.Info) }
                    }
                    if (report.salesCount == 0) item { InfoNotice("Nenhuma venda disponível neste mês. Entradas de estoque não contam como faturamento.", Icons.AutoMirrored.Filled.ReceiptLong) }
                }
                "Produtos" -> { item { InfoNotice("Comparação das vendas originais do mês, incluindo fiado. Os estornos ficam no resumo dos recebimentos.") }; item { ProductRanking(rankings) } }
                "Vendas" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ReportHeading("${sales.size} venda(s) no mês")
                            OutlinedTextField(search, { search = it.take(160) }, label = { Text("Buscar cliente, conta ou código da venda") }, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
                            Text("Toque na venda para ver itens, custo histórico e pagamentos.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (filteredSales.isEmpty()) item { EmptyState("Nenhuma venda encontrada", "Confira o mês ou o texto da busca.", Icons.AutoMirrored.Filled.ReceiptLong) }
                    items(filteredSales, key = { "sale-${it.id}" }) { sale -> SaleHistoryCard(sale, onClick = { selectedSale = sale }) }
                }
                "Por conta" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReportHeading("Vendas por conta conectada")
                            Text("Cada venda preserva a conta usada no momento do registro. Despesas ficam no resultado geral; o lucro abaixo desconta somente CMV.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (accounts.isEmpty()) item { EmptyState("Sem vendas neste mês", "As contas aparecerão depois da primeira venda.", Icons.Default.Person) }
                    items(accounts, key = { "operator-${it.operatorId}" }) { account ->
                        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(account.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("${account.salesCount} venda(s)", style = MaterialTheme.typography.bodyMedium)
                                HorizontalDivider()
                                ReportMoneyRow("Valor vendido (inclui fiado)", account.revenueCents)
                                ReportMoneyRow("Lucro após CMV", account.profitCents)
                                ReportMoneyRow("Vendido no fiado", account.creditCents)
                            }
                        }
                    }
                }
                "Despesas" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            FinancialCard("Despesas operacionais", expenseTotal, "Despesas registradas no mês selecionado", Icons.Default.HomeWork)
                            Button(onClick = { model.clearFormError(); showExpense = true }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Registrar despesa")
                            }
                        }
                    }
                    if (expenses.isEmpty()) item { EmptyState("Nenhuma despesa cadastrada", "Registre água, energia, aluguel e outros gastos para acompanhar o resultado.", Icons.Default.HomeWork) }
                    items(expenses, key = { "expense-${it.id}" }) { expense ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(expense.description, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("${expenseCategoryLabel(expense.category)} • ${expense.occurredAt.atZone(businessZone).format(expenseDateFormat)}", style = MaterialTheme.typography.bodySmall)
                                Text(currency(expense.amountCents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                Text("Registrado por ${expense.operatorName.ifBlank { expense.operatorId.ifBlank { "conta não informada" } }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { Text("Horário de Brasília. Cada venda guarda os preços e custos do momento em que foi registrada.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    selectedSale?.let { sale -> SaleDetailsDialog(sale, onDismiss = { selectedSale = null }, onReverse = if (model.capabilities.canReverseSales) { { selectedSale = null; onReverse(sale.id) } } else null) }
    if (showExpense) ExpenseDialog(month, working, formError, onDismiss = { if (!working) showExpense = false }, onSave = { description, category, amount, date ->
        model.saveExpense(description, category, amount, date) { showExpense = false }
    })
}

@Composable
private fun ProductRanking(rankings: List<ProductPerformance>) {
    var order by rememberSaveable { mutableStateOf("Mais vendidos") }
    val sorted = remember(rankings, order) {
        when (order) {
            "Menos vendidos" -> rankings.sortedWith(compareBy<ProductPerformance> { it.units }.thenBy { it.name.lowercase() })
            "Mais rentáveis" -> rankings.sortedWith(compareByDescending<ProductPerformance> { it.profitCents }.thenBy { it.name.lowercase() })
            else -> rankings.sortedWith(compareByDescending<ProductPerformance> { it.units }.thenBy { it.name.lowercase() })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReportHeading("Desempenho dos produtos")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Mais vendidos", "Menos vendidos", "Mais rentáveis").forEach { label -> FilterChip(selected = order == label, onClick = { order = label }, label = { Text(label) }) }
        }
        Text("Quantidade em unidades de estoque. Rentabilidade por lucro total após o custo histórico, antes das despesas. Produtos ativos sem vendas aparecem com zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sorted.isEmpty()) EmptyState("Sem produtos para comparar", "Cadastre produtos ou selecione um mês com vendas.", Icons.Default.Inventory2)
        else Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) {
                Row(Modifier.width(620.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Produto", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("Unidades", Modifier.width(70.dp), fontWeight = FontWeight.Bold)
                    Text("Faturamento", Modifier.width(115.dp), fontWeight = FontWeight.Bold)
                    Text("Lucro após CMV", Modifier.width(125.dp), fontWeight = FontWeight.Bold)
                }
            }
            sorted.forEachIndexed { index, product ->
                Row(Modifier.width(620.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${product.name}", Modifier.weight(1f), fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                    Text(product.units.toString(), Modifier.width(70.dp))
                    Text(currency(product.revenueCents), Modifier.width(115.dp))
                    Text(currency(product.profitCents), Modifier.width(125.dp), color = if (product.profitCents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(Modifier.width(620.dp))
            }
        }
    }
}

@Composable
internal fun SaleHistoryCard(sale: Movement, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sale.customerLabel(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(sale.occurredAt.atZone(businessZone).format(reportDateFormat), style = MaterialTheme.typography.bodySmall)
            Text(currency(sale.totalCents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Vendido por ${sale.operatorLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sale.receivableCents > 0) Text("Fiado na venda: ${currency(sale.receivableCents)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Venda ${sale.id.take(8)} • Ver detalhes", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun SaleDetailsDialog(sale: Movement, onDismiss: () -> Unit, onReverse: (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Detalhes da venda") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(sale.occurredAt.atZone(businessZone).format(reportDateFormat), fontWeight = FontWeight.SemiBold)
            Text("Cliente: ${sale.customerLabel()}")
            Text("Conta: ${sale.operatorLabel()}")
            HorizontalDivider()
            sale.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.nameSnapshot, fontWeight = FontWeight.Bold)
                    val unitLabel = if (item.unitsPerPackage > 1) "caixa(s) de ${item.unitsPerPackage} un." else "unidade(s)"
                    Text("${item.quantity} $unitLabel × ${currency(item.saleUnitCents)}")
                    Text("Subtotal: ${currency(item.saleUnitCents * item.quantity)}", fontWeight = FontWeight.SemiBold)
                    Text("Custo histórico: ${currency(item.historicalCostCents)} por ${if (item.unitsPerPackage > 1) "caixa" else "unidade"}", style = MaterialTheme.typography.bodySmall)
                    Text("Lucro após CMV: ${currency((item.saleUnitCents - item.historicalCostCents) * item.quantity)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            ReportMoneyRow("Total da venda", sale.totalCents, true)
            ReportMoneyRow("CMV", sale.items.sumOf { it.historicalCostCents * it.quantity })
            ReportMoneyRow("Lucro após CMV", sale.profitCents)
            HorizontalDivider()
            Text("Pagamentos na venda", fontWeight = FontWeight.Bold)
            if (sale.payments.isEmpty()) Text("Forma de pagamento não registrada nesta venda antiga.", style = MaterialTheme.typography.bodySmall)
            sale.payments.forEach { payment -> ReportMoneyRow(payment.method.paymentLabel(), payment.amountCents) }
            if (sale.changeCents > 0) ReportMoneyRow("Troco entregue", sale.changeCents)
            if (sale.receivableCents > 0) Text("O saldo atualizado e as parcelas recebidas estão na aba Fiado.", style = MaterialTheme.typography.bodySmall)
            Text("Código: ${sale.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } }, dismissButton = { onReverse?.let { TextButton(onClick = it) { Text("Estornar venda") } } })
}

@Composable
private fun ExpenseDialog(month: YearMonth, working: Boolean, error: String?, onDismiss: () -> Unit, onSave: (String, String, String, LocalDate) -> Unit) {
    var description by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("AGUA") }
    val today = LocalDate.now(businessZone)
    var date by rememberSaveable { mutableStateOf((if (month == YearMonth.from(today)) today else month.atEndOfMonth()).format(expenseDateFormat)) }
    val parsedDate = remember(date) { runCatching { LocalDate.parse(date, expenseDateFormat) }.getOrNull() }
    val parsedAmount = remember(amount) { runCatching { Money.fromDecimal(amount) }.getOrNull() }
    val dateValid = parsedDate != null && !parsedDate.isAfter(today)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Registrar despesa") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Categoria", style = MaterialTheme.typography.labelLarge)
            listOf(listOf("AGUA", "ENERGIA"), listOf("ALUGUEL", "OUTROS")).forEach { categories ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { value -> FilterChip(selected = category == value, onClick = { category = value }, label = { Text(expenseCategoryLabel(value)) }, enabled = !working) }
                }
            }
            OutlinedTextField(description, { description = it.take(120) }, label = { Text("Descrição") }, placeholder = { Text("Ex.: Conta de água de setembro") }, singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.take(16) }, label = { Text("Valor (R$)") }, placeholder = { Text("0,00") }, singleLine = true, enabled = !working, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it.filter { char -> char.isDigit() || char == '/' }.take(10) }, label = { Text("Data (dia/mês/ano)") }, singleLine = true, enabled = !working, isError = date.length == 10 && !dateValid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth())
            if (date.length == 10 && !dateValid) Text("Informe uma data válida, até hoje.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (parsedDate != null && YearMonth.from(parsedDate) != month) Text("A despesa aparecerá no mês da data informada.", style = MaterialTheme.typography.bodySmall)
            Text("A despesa será descontada do lucro do mês informado. Confira os dados antes de confirmar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        Button(onClick = { parsedDate?.let { onSave(description.trim(), category, amount, it) } }, enabled = !working && description.isNotBlank() && dateValid && parsedAmount != null && parsedAmount in 1..BusinessLimits.MAX_MONEY_CENTS) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Registrar")
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}

@Composable
private fun ReportHeading(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
internal fun ReportMoneyRow(label: String, cents: Long, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(currency(cents), fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold, color = if (cents < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun FinancialCard(title: String, cents: Long, subtitle: String, icon: ImageVector, modifier: Modifier = Modifier, highlighted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = if (highlighted) colors.primaryContainer else colors.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = if (highlighted) colors.onPrimaryContainer else colors.primary)
            }
            Text(currency(cents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = if (cents < 0) colors.error else if (highlighted) colors.onPrimaryContainer else colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QuantityCard(label: String, quantity: Long, modifier: Modifier = Modifier) {
    OutlinedCard(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(quantity.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

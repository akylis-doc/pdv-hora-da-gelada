package br.com.bebidaspdv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.bebidaspdv.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private enum class ExportKind(val label: String, val fileLabel: String) {
    SALES("Vendas", "vendas"), STOCK("Estoque", "estoque"), CUSTOMERS("Clientes", "clientes"), CREDIT("Fiado em aberto", "fiado_aberto"),
}

private data class PreparedExport(val content: String, val fileName: String)

@Composable
internal fun ExportScreen(repository: PosRepository, capabilities: OperatorCapabilities) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestCapabilities by rememberUpdatedState(capabilities)
    var kind by rememberSaveable { mutableStateOf(ExportKind.SALES) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now(businessZone).toString()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var prepared by remember { mutableStateOf<PreparedExport?>(null) }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val export = prepared
        prepared = null
        if (uri == null) {
            busy = false
        } else if (export == null) {
            busy = false
            error = "A tela foi recriada durante a seleção do arquivo. Solicite a exportação novamente."
        } else {
            scope.launch {
                try {
                    require(latestCapabilities.canExportData) { "Seu perfil não permite exportar dados." }
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Não foi possível abrir o local escolhido.")
                        stream.bufferedWriter(Charsets.UTF_8).use { it.write(export.content) }
                    }
                    message = "Arquivo CSV salvo no local escolhido."
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message ?: "Não foi possível salvar o arquivo. Tente outro local."
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Exportar dados", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Salve uma planilha CSV para consultar ou guardar os dados da loja.")
        if (!capabilities.canExportData) {
            Text("Seu perfil não permite exportar dados.", color = MaterialTheme.colorScheme.error)
        } else {
            ExportKind.entries.chunked(2).forEach { options ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        FilterChip(kind == option, { kind = option; error = null; message = null }, enabled = !busy, label = { Text(option.label) })
                    }
                }
            }
            if (kind == ExportKind.SALES) {
                val selected = runCatching { YearMonth.parse(monthText) }.getOrNull()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { monthText = (selected ?: YearMonth.now(businessZone)).minusMonths(1).toString() }, enabled = !busy) { Text("Anterior") }
                    OutlinedButton(onClick = { monthText = (selected ?: YearMonth.now(businessZone)).plusMonths(1).toString() }, enabled = !busy && (selected ?: YearMonth.now(businessZone)) < YearMonth.now(businessZone)) { Text("Próximo") }
                }
                OutlinedTextField(monthText, { monthText = it.take(7) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    label = { Text("Mês das vendas") }, placeholder = { Text("aaaa-mm") }, supportingText = { Text("Exemplo: 2026-09") })
                Text("Uma linha por venda original. Recebimentos posteriores de fiado e estornos não alteram este histórico. Para o dinheiro que entrou, consulte o Resumo dos relatórios.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(when (kind) {
                    ExportKind.STOCK -> "Exporta os produtos ativos e os saldos atuais, com quantidades e preços em colunas separadas."
                    ExportKind.CUSTOMERS -> "Exporta nome, telefone, limite e saldo de fiado dos clientes cadastrados."
                    else -> "Exporta somente contas ainda em aberto, com valor original, recebido, restante e vencimento."
                }, style = MaterialTheme.typography.bodySmall)
            }
            Text("A consulta acontece ao tocar no botão. É necessário estar conectado e aguardar a confirmação dos dados para exportar.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = {
                if (!busy && latestCapabilities.canExportData) {
                    busy = true
                    error = null
                    message = null
                    val selectedKind = kind
                    val selectedMonthText = monthText
                    scope.launch {
                        try {
                            val selectedMonth = if (selectedKind == ExportKind.SALES) YearMonth.parse(selectedMonthText) else null
                            require(selectedMonth == null || selectedMonth <= YearMonth.now(businessZone)) { "Escolha um mês até o mês atual." }
                            val content = withTimeout(15_000) {
                                when (selectedKind) {
                                    ExportKind.SALES -> CsvExport.sales(repository.observeMovements(requireNotNull(selectedMonth), businessZone).confirmed())
                                    ExportKind.STOCK -> CsvExport.products(repository.observeProducts().confirmed())
                                    ExportKind.CUSTOMERS -> CsvExport.customers(repository.observeCustomers().confirmed())
                                    ExportKind.CREDIT -> coroutineScope {
                                        val accounts = async { repository.observeReceivables().confirmed() }
                                        val customers = async { repository.observeCustomers().confirmed() }
                                        CsvExport.receivables(accounts.await(), customers.await())
                                    }
                                }
                            }
                            require(latestCapabilities.canExportData) { "Seu perfil não permite exportar dados." }
                            val date = selectedMonth?.toString() ?: LocalDate.now(businessZone).format(DateTimeFormatter.ISO_DATE)
                            val export = PreparedExport(content, "pdv_hora_da_gelada_${selectedKind.fileLabel}_$date.csv")
                            prepared = export
                            destination.launch(export.fileName)
                        } catch (_: TimeoutCancellationException) {
                            error = "Os dados ainda não foram confirmados. Confira a internet e as pendências de sincronização e tente novamente."
                            busy = false
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = if (failure is java.time.format.DateTimeParseException) "Informe o mês em aaaa-mm, como 2026-09." else failure.message ?: "Não foi possível preparar a exportação."
                            prepared = null
                            busy = false
                        }
                    }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Icon(Icons.Default.FileDownload, null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Preparando exportação…" else "Escolher onde salvar CSV")
            }
        }
    }
}

private suspend fun <T> Flow<Feed<T>>.confirmed(): T = first { !it.fromCache && !it.pendingWrites }.value

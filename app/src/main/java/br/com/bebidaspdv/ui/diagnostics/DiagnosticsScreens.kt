package br.com.bebidaspdv.ui.diagnostics

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.bebidaspdv.core.logging.*
import java.io.FilterWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val fullFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS XXX")
private fun logTime(timestamp: Long, full: Boolean = false): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(if (full) fullFormat else timeFormat)

@Composable
private fun diagnosticModel(store: DiagnosticLogStore): DiagnosticsViewModel {
    val factory = remember(store) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = DiagnosticsViewModel(store) as T
    } }
    return viewModel(factory = factory)
}

/** Revocation also invalidates callbacks that return after a document picker or IO operation. */
@Composable
private fun diagnosticAccess(isAdmin: Boolean): AtomicBoolean {
    val access = remember { AtomicBoolean(isAdmin) }
    SideEffect { access.set(isAdmin) }
    DisposableEffect(access) { onDispose { access.set(false) } }
    return access
}

@Composable
internal fun DiagnosticsOverviewScreen(logger: LocalAppLogger, isAdmin: Boolean, onLogs: () -> Unit, onBack: () -> Unit) {
    val access = diagnosticAccess(isAdmin)
    if (!isAdmin) return
    val model = diagnosticModel(logger.store)
    val state by model.state.collectAsStateWithLifecycle()
    val mode by logger.diagnosticMode.collectAsStateWithLifecycle()
    val health by logger.store.health.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageTitle("Diagnóstico", onBack)
        Text("Registros técnicos deste aparelho, disponíveis mesmo sem internet. Acesso reservado ao administrador.")
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.summary?.let { SummaryCards(it) }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Modo de registro", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (mode) "Modo diagnóstico" else "Modo normal")
                        Text(if (mode) "Inclui TRACE e DEBUG para investigar um problema." else "Registra INFO, WARNING, ERROR e CRITICAL.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(mode, onCheckedChange = { if (access.get()) logger.setDiagnosticMode(it) })
                }
            }
        }
        LoggerHealthNotice(health)
        Button(onClick = { if (access.get()) onLogs() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Abrir Logs") }
        Text("Retenção local: TRACE/DEBUG 7 dias; INFO 15; WARNING 30; ERROR/CRITICAL 90. Limite de 20.000 registros ou 32 MB; registros menos graves são removidos primeiro quando necessário.", style = MaterialTheme.typography.bodySmall)
        Text("A exportação é manual. Os logs não são enviados ao Firebase e não representam um backup das vendas.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SummaryCards(summary: LogSummary) {
    val cards = listOf("Erros hoje" to summary.errorsToday, "Warnings hoje" to summary.warningsToday,
        "Operações lentas hoje" to summary.slowOperations, "Sync pendentes nos logs" to summary.pendingSyncOperations)
    cards.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (label, value) ->
                ElevatedCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    summary.lastError?.let { Text("Último erro: ${logTime(it.timestamp, true)} · ${it.event}", style = MaterialTheme.typography.bodySmall) }
    summary.lastSync?.let { Text("Última sincronização registrada: ${logTime(it.timestamp, true)}", style = MaterialTheme.typography.bodySmall) }
    Text("Resumo baseado no histórico retido neste aparelho. Pendências antigas removidas pela retenção podem não aparecer.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LoggerHealthNotice(health: LoggerHealth) {
    if (health.droppedRecords == 0L && health.storageFailures == 0L && health.recoveredCorruptRecords == 0L) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Atenção ao armazenamento dos logs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text("Descartados por volume: ${health.droppedRecords} · Falhas de gravação: ${health.storageFailures} · Registros inválidos recuperados/ignorados: ${health.recoveredCorruptRecords}", style = MaterialTheme.typography.bodySmall)
            health.lastFailure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun DiagnosticsLogsScreen(logger: LocalAppLogger, isAdmin: Boolean, onBack: () -> Unit) {
    val access = diagnosticAccess(isAdmin)
    if (!isAdmin) return
    val store = logger.store
    val model = diagnosticModel(store)
    val state by model.state.collectAsStateWithLifecycle()
    val filters by model.filters.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val health by store.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    var traceId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var clearMode by remember { mutableStateOf<LogClearMode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportFilter by remember { mutableStateOf<LogFilter?>(null) }
    fun message(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val query = exportFilter
        exportFilter = null
        if (uri == null || !access.get()) busy = false
        else if (query == null) { busy = false; message("A tela foi recriada. Solicite a exportação novamente.") }
        else scope.launch {
            try {
                check(access.get())
                val count = withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    coroutine.ensureActive()
                    check(access.get())
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Destino indisponível")
                    stream.bufferedWriter(Charsets.UTF_8).use { output ->
                        val guarded = AccessCheckedWriter(output) { coroutine.ensureActive(); check(access.get()) }
                        store.export(query, guarded)
                    }
                }
                if (access.get()) message("$count registros exportados em JSON.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (access.get()) message("Não foi possível concluir a exportação. O arquivo escolhido pode estar incompleto.")
            } finally { busy = false }
        }
    }
    fun export(query: LogFilter) {
        if (!access.get() || busy) return
        exportFilter = query; busy = true; showExport = false
        destination.launch("pdv-logs-${Instant.now().toEpochMilli()}.json")
    }
    BackHandler(enabled = traceId != null) { traceId = null; model.openTrace(null) }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PageTitle(if (traceId == null) "Logs" else "Rastreamento") {
                if (traceId != null) { traceId = null; model.openTrace(null) } else onBack()
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (live) "● LIVE" else "PAUSADO", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(live, { if (access.get()) model.live.value = it })
                TextButton(onClick = { if (access.get()) model.refresh() }) { Text("Atualizar") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (traceId == null) OutlinedButton(onClick = { showFilters = true }) { Text("Filtros") }
                OutlinedButton(onClick = { if (access.get()) showExport = true }, enabled = !busy) { Text("Exportar JSON") }
                OutlinedButton(onClick = { if (access.get()) showClear = true }, enabled = !busy) { Text("Limpar") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LoggerHealthNotice(health)
            if (traceId == null) {
                OutlinedTextField(filters.text, { input -> model.updateFilters { it.copy(text = input.take(300)) } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar mensagem, produto, venda, ID ou erro") })
                QuickFilters(filters, model::updateFilters)
                Text("${state.records.size} de ${state.total} registros · mais recentes primeiro", style = MaterialTheme.typography.labelSmall)
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!state.loading && state.records.isEmpty()) item { Text("Nenhum registro corresponde aos filtros.", Modifier.padding(12.dp)) }
                    items(state.records, key = LogEntry::id) { log -> LogRow(log, onClick = { if (access.get()) selected = log }) }
                    if (state.records.size < state.total) item {
                        OutlinedButton(onClick = model::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Carregar mais 100") }
                    }
                }
            } else {
                LaunchedEffect(traceId) { model.openTrace(traceId) }
                val traceState by model.traceState.collectAsStateWithLifecycle()
                TraceContent(traceId.orEmpty(), traceState, onLog = { if (access.get()) selected = it }, modifier = Modifier.weight(1f))
            }
        }
    }
    if (showFilters) FilterDialog(filters, onChange = { transform -> if (access.get()) model.updateFilters(transform) }, onDismiss = { showFilters = false })
    selected?.let { log -> LogDetailsDialog(log, access, onDismiss = { selected = null }, onTrace = {
        if (access.get()) { traceId = it; model.openTrace(it); selected = null }
    }) }
    if (showExport) AlertDialog(onDismissRequest = { showExport = false }, title = { Text("Exportar logs locais") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Escolha o conteúdo. O arquivo inclui detalhes técnicos já protegidos pelo logger.")
            OutlinedButton(onClick = { export(LogFilter()) }, modifier = Modifier.fillMaxWidth()) { Text("Todos os registros retidos") }
            OutlinedButton(onClick = { export(LogFilter(levels = setOf(LogLevel.ERROR, LogLevel.CRITICAL))) }, modifier = Modifier.fillMaxWidth()) { Text("Somente erros e críticos") }
            OutlinedButton(onClick = {
                runCatching { filters.toQuery() }.onSuccess(::export).onFailure { message("Confira as datas e os filtros antes de exportar.") }
            }, modifier = Modifier.fillMaxWidth()) { Text("Filtros e período atuais") }
            traceId?.let { id -> OutlinedButton(onClick = { export(LogFilter(traceId = id)) }, modifier = Modifier.fillMaxWidth()) { Text("Somente este rastreamento") } }
        }
    }, confirmButton = { TextButton(onClick = { showExport = false }) { Text("Cancelar") } })
    if (showClear) AlertDialog(onDismissRequest = { showClear = false }, title = { Text("Limpar logs") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LogClearMode.entries.forEach { mode -> OutlinedButton(onClick = { if (access.get()) { clearMode = mode; showClear = false } }, modifier = Modifier.fillMaxWidth()) { Text(clearLabel(mode)) } }
        }
    }, confirmButton = { TextButton(onClick = { showClear = false }) { Text("Cancelar") } })
    clearMode?.let { mode -> AlertDialog(onDismissRequest = { clearMode = null }, title = { Text("Confirmar limpeza?") },
        text = { Text("${clearLabel(mode)}. Esta ação apaga o histórico técnico selecionado deste aparelho e não pode ser desfeita. As vendas e o estoque permanecem intactos.") },
        confirmButton = { TextButton(onClick = {
            clearMode = null
            if (access.get() && !busy) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive(); check(access.get())
                            // Best effort drain; cleanup must remain possible after storage failures.
                            logger.flush()
                            check(access.get()); store.clear(mode)
                        }
                        if (access.get()) { model.refresh(); selected = null; message("Logs selecionados removidos.") }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (access.get()) message("Não foi possível limpar os logs locais.")
                    } finally { busy = false }
                }
            }
        }) { Text("Confirmar limpeza") } }, dismissButton = { TextButton(onClick = { clearMode = null }) { Text("Cancelar") } }) }
}

private fun clearLabel(mode: LogClearMode): String = when (mode) {
    LogClearMode.DEBUG_TRACE -> "Limpar somente DEBUG e TRACE"
    LogClearMode.EXPIRED -> "Limpar registros fora da retenção"
    LogClearMode.ALL -> "Limpar todos os logs"
}

internal class AccessCheckedWriter(writer: Writer, private val checkAccess: () -> Unit) : FilterWriter(writer) {
    override fun write(cbuf: CharArray, off: Int, len: Int) { checkAccess(); super.write(cbuf, off, len) }
    override fun write(str: String, off: Int, len: Int) { checkAccess(); super.write(str, off, len) }
    override fun write(c: Int) { checkAccess(); super.write(c) }
    override fun flush() { checkAccess(); super.flush() }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuickFilters(filters: DiagnosticsFilters, update: ((DiagnosticsFilters) -> DiagnosticsFilters) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val errors = setOf("ERROR", "CRITICAL")
        FilterChip(filters.levels == errors, { update { it.copy(levels = if (it.levels == errors) emptySet() else errors) } }, label = { Text("Somente erros") })
        FilterChip(filters.levels == setOf("WARNING"), { update { it.copy(levels = if (it.levels == setOf("WARNING")) emptySet() else setOf("WARNING")) } }, label = { Text("Somente warnings") })
        FilterChip(filters.levels == errors + "WARNING", { update { it.copy(levels = if (it.levels == errors + "WARNING") emptySet() else errors + "WARNING") } }, label = { Text("Erros + warnings") })
        listOf(DiagnosticsPeriod.TEN_MINUTES, DiagnosticsPeriod.HOUR, DiagnosticsPeriod.TODAY).forEach { period ->
            FilterChip(filters.period == period, { update { it.copy(period = if (it.period == period) DiagnosticsPeriod.ALL else period) } }, label = { Text(period.label) })
        }
        TextButton(onClick = { update { DiagnosticsFilters() } }) { Text("Limpar filtros") }
    }
}

@Composable
private fun FilterDialog(filters: DiagnosticsFilters, onChange: ((DiagnosticsFilters) -> DiagnosticsFilters) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Filtros dos logs") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Níveis (nenhum selecionado = todos)", style = MaterialTheme.typography.labelLarge)
            LogLevel.entries.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { level -> FilterChip(level.name in filters.levels, { onChange { it.copy(levels = if (level.name in it.levels) it.levels - level.name else it.levels + level.name) } }, label = { Text(level.name) }) }
            } }
            FilterDropdown("Categoria", filters.category, LogCategory.entries.map { it.name }) { value -> onChange { it.copy(category = value) } }
            FilterDropdown("Período", filters.period.name, DiagnosticsPeriod.entries.map { it.name }, labelFor = { value -> DiagnosticsPeriod.valueOf(value).label }, allowAll = false) { value -> onChange { it.copy(period = DiagnosticsPeriod.valueOf(value)) } }
            if (filters.period == DiagnosticsPeriod.CUSTOM) {
                FilterInput("Data inicial (aaaa-mm-dd)", filters.startDate) { value -> onChange { it.copy(startDate = value.take(10)) } }
                FilterInput("Data final (aaaa-mm-dd)", filters.endDate) { value -> onChange { it.copy(endDate = value.take(10)) } }
            }
            FilterInput("Trace ID exato", filters.traceId) { value -> onChange { it.copy(traceId = value) } }
            FilterInput("Operation ID exato", filters.operationId) { value -> onChange { it.copy(operationId = value) } }
            FilterInput("Evento exato", filters.event) { value -> onChange { it.copy(event = value) } }
            FilterDropdown("Status", filters.status, LogStatus.entries.map { it.name }) { value -> onChange { it.copy(status = value) } }
            FilterDropdown("Conectividade", filters.connectivity, ConnectivityStatus.entries.map { it.name }) { value -> onChange { it.copy(connectivity = value) } }
            FilterDropdown("Sincronização", filters.sync, LogSyncStatus.entries.map { it.name }) { value -> onChange { it.copy(sync = value) } }
            Text("PENDING: aguardando envio; SYNCED: confirmação remota; FAILED: falhou; NOT_APPLICABLE: não se aplica. Offline é um estado normal.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Ver resultados") } }, dismissButton = { TextButton(onClick = { onChange { DiagnosticsFilters() } }) { Text("Limpar filtros") } })
}

@Composable
private fun FilterInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(300)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) })
}

@Composable
private fun FilterDropdown(label: String, value: String, values: List<String>, labelFor: (String) -> String = { it }, allowAll: Boolean = true, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${if (value.isBlank()) "Todos" else labelFor(value)}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (allowAll) DropdownMenuItem(text = { Text("Todos") }, onClick = { onChange(""); expanded = false })
            values.forEach { choice -> DropdownMenuItem(text = { Text(labelFor(choice)) }, onClick = { onChange(choice); expanded = false }) }
        }
    }
}

@Composable
private fun LogRow(log: LogEntry, onClick: () -> Unit) {
    val color = when (log.level) {
        LogLevel.TRACE, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR, LogLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${logTime(log.timestamp)}  ${log.level}  ${log.category}", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
            Text(log.event, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(log.message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (log.durationMs != null || log.status != null) Text(listOfNotNull(log.status?.name, log.durationMs?.let { "$it ms" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            if (log.status == LogStatus.SLOW || log.status == LogStatus.STALLED) Text(if (log.status == LogStatus.STALLED) "⚠ OPERAÇÃO PRESA / AGUARDANDO" else "⚠ OPERAÇÃO LENTA", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TraceContent(traceId: String, state: DiagnosticsTraceState, onLog: (LogEntry) -> Unit, modifier: Modifier = Modifier) {
    val entries = state.entries
    val bottleneck = remember(entries) { traceBottleneck(entries) }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            SelectionContainer { Text("TRACE: $traceId", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.loading) Text("Intervalo dos registros: ${traceIntervalMs(entries)} ms · ${entries.size} eventos", fontWeight = FontWeight.Bold)
        }
        bottleneck?.let { measured -> item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (measured.status == LogStatus.SLOW || measured.status == LogStatus.STALLED) "⚠ GARGALO / ETAPA MAIS LONGA" else "Etapa medida mais longa", fontWeight = FontWeight.Bold)
                    Text("${measured.component ?: measured.category.name}.${measured.function ?: measured.event}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("${measured.durationMs} ms · ${measured.event}")
                    Text("As durações de etapas sobrepostas não são somadas. O intervalo inclui esperas de sincronização; a retenção pode remover etapas antigas.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        if (!state.loading && entries.isEmpty()) item { Text("Não há mais registros deste rastreamento no aparelho.") }
        items(entries.size, key = { index -> entries[index].id }) { index ->
            val log = entries[index]
            if (index > 0) Text("↓ +${(log.timestamp - entries[index - 1].timestamp).coerceAtLeast(0)} ms", style = MaterialTheme.typography.labelSmall)
            LogRow(log, onClick = { onLog(log) })
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun LogDetailsDialog(log: LogEntry, access: AtomicBoolean, onDismiss: () -> Unit, onTrace: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val details = remember(log) { logDetailsText(log) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Detalhes do registro") }, text = {
        SelectionContainer {
            Text(details, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }, confirmButton = {
        Column(horizontalAlignment = Alignment.End) {
            log.traceId?.let { id -> TextButton(onClick = { if (access.get()) onTrace(id) }) { Text("Ver rastreamento") } }
            TextButton(onClick = { if (access.get()) clipboard.setText(AnnotatedString(details)) }) { Text("Copiar detalhes") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
}

internal fun logDetailsText(log: LogEntry): String = buildString {
    fun field(name: String, value: Any?) { append(name).append(": ").append(value ?: "—").append('\n') }
    field("id", log.id); field("timestamp", logTime(log.timestamp, true)); field("timestamp_epoch_ms", log.timestamp)
    field("level", log.level); field("category", log.category); field("event", log.event); field("message", log.message)
    field("trace_id", log.traceId); field("operation_id", log.operationId); field("entity_id", log.entityId)
    field("user_id", log.userId); field("device_id", log.deviceId); field("screen", log.screen)
    field("component", log.component); field("function", log.function); field("duration_ms", log.durationMs)
    field("status", log.status); field("error_code", log.errorCode); field("connectivity_status", log.connectivityStatus)
    field("sync_status", log.syncStatus); field("app_version", log.appVersion); field("build_version", log.buildVersion)
    field("device_information", log.deviceInformation); field("exception_type", log.exceptionType)
    field("stack_trace", log.stackTrace)
    append("metadata:\n")
    if (log.metadata.isEmpty()) append("  {}\n") else log.metadata.toSortedMap().forEach { (key, value) -> append("  ").append(key).append(": ").append(value).append('\n') }
}

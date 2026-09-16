package br.com.bebidaspdv.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal data class DiagnosticsUiState(
    val records: List<LogEntry> = emptyList(),
    val total: Int = 0,
    val summary: LogSummary? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

internal data class DiagnosticsTraceState(
    val entries: List<LogEntry> = emptyList(), val loading: Boolean = true, val error: String? = null,
)

/** Only collected screens open local queries; invalidations are coalesced, never polled. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class DiagnosticsViewModel(
    private val store: DiagnosticLogStore,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val filters = MutableStateFlow(DiagnosticsFilters())
    private val pageSize = MutableStateFlow(100)
    private val refresh = MutableStateFlow(0)
    val live = MutableStateFlow(true)
    private val trace = MutableStateFlow<String?>(null)

    private val invalidations = live.flatMapLatest { enabled -> if (enabled) store.changes else flowOf(store.changes.value) }
    // Sampling invalidations still delivers updates during a continuous stream of logs.
    // Debouncing that stream could indefinitely postpone the LIVE view.
    val state: StateFlow<DiagnosticsUiState> = combine(filters.debounce(250), pageSize, invalidations.sample(500), refresh) { filter, limit, _, _ -> filter to limit }
        .mapLatest { (filter, limit) ->
            try {
                withContext(io) {
                    val instant = now()
                    val query = filter.toQuery(instant, zone)
                    DiagnosticsUiState(store.query(query, limit = limit), store.count(query),
                        store.summary(instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()), loading = false)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagnosticsUiState(error = if (error is java.time.format.DateTimeParseException) "Informe as datas em aaaa-mm-dd."
                    else if (error is IllegalArgumentException) error.message else "Não foi possível consultar os logs locais.", loading = false)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), DiagnosticsUiState())

    val traceState: StateFlow<DiagnosticsTraceState> = combine(trace, invalidations.sample(500), refresh) { id, _, _ -> id }
        .mapLatest { id ->
            if (id == null) DiagnosticsTraceState(loading = false)
            else try {
                withContext(io) {
                    val query = LogFilter(traceId = id)
                    val count = store.count(query)
                    DiagnosticsTraceState(store.query(query, limit = count.coerceAtLeast(1), oldestFirst = true), loading = false)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DiagnosticsTraceState(loading = false, error = "Não foi possível ler o rastreamento local.")
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), DiagnosticsTraceState())

    fun updateFilters(transform: (DiagnosticsFilters) -> DiagnosticsFilters) { pageSize.value = 100; filters.update(transform) }
    fun loadMore() { pageSize.update { (it + 100).coerceAtMost(20_000) } }
    fun refresh() { refresh.update { it + 1 } }
    fun openTrace(id: String?) { trace.value = id }
}

internal fun traceIntervalMs(entries: List<LogEntry>): Long = if (entries.size < 2) 0 else
    (entries.maxOf(LogEntry::timestamp) - entries.minOf(LogEntry::timestamp)).coerceAtLeast(0)

/** Compare measured stages; nested durations must never be added to one another. */
internal fun traceBottleneck(entries: List<LogEntry>): LogEntry? {
    val measured = entries.filter { it.status != LogStatus.STARTED && it.durationMs != null &&
        it.metadata["includes_offline_wait"] != "true" }
    // A surrounding operation includes its children. Prefer a measured inner stage where its
    // interval is known, instead of incorrectly blaming the ViewModel that awaited the database.
    val leaves = measured.filter { parent ->
        val parentId = parent.metadata["step_id"]
        parentId == null || measured.none { child ->
            val childId = child.metadata["step_id"]
            childId != null && childId != parentId && (child.durationMs ?: 0) < (parent.durationMs ?: 0) &&
                child.timestamp <= parent.timestamp &&
                child.timestamp - (child.durationMs ?: 0) >= parent.timestamp - (parent.durationMs ?: 0)
        }
    }
    return (leaves.ifEmpty { measured }).maxByOrNull { it.durationMs ?: 0 }
}

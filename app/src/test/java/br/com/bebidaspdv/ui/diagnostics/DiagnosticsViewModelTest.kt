package br.com.bebidaspdv.ui.diagnostics

import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.logging.*
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore()
    private lateinit var model: DiagnosticsViewModel

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        model = DiagnosticsViewModel(store, { Instant.parse("2026-09-15T12:00:00Z") }, ZoneId.of("America/Sao_Paulo"), dispatcher)
    }
    @After fun after() { model.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun `queries run only while the screen has an active collector`() = runTest(dispatcher) {
        store.changes.value++
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, store.reads)
        val collecting = backgroundScope.launch { model.state.collect() }
        advanceTimeBy(501); runCurrent()
        assertEquals(1, store.reads)
        collecting.cancel(); runCurrent()
        store.changes.value++
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, store.reads)
    }

    @Test fun `continuous log volume still refreshes LIVE view with bounded queries`() = runTest(dispatcher) {
        backgroundScope.launch { model.state.collect() }
        repeat(30) { store.changes.value++; advanceTimeBy(100); runCurrent() }
        assertTrue(store.reads in 4..7)
        assertFalse(model.state.value.loading)
    }

    @Test fun `paused LIVE stops automatic reads but manual refresh works`() = runTest(dispatcher) {
        backgroundScope.launch { model.state.collect() }
        advanceTimeBy(501); runCurrent()
        model.live.value = false
        advanceTimeBy(501); runCurrent()
        val before = store.reads
        repeat(10) { store.changes.value++; advanceTimeBy(100) }
        runCurrent()
        assertEquals(before, store.reads)
        model.refresh(); runCurrent()
        assertEquals(before + 1, store.reads)
    }

    @Test fun `pagination expands by one page and changing filters resets it`() = runTest(dispatcher) {
        store.records = (1..250).map { entry(it.toString()) }
        backgroundScope.launch { model.state.collect() }
        advanceTimeBy(501); runCurrent()
        assertEquals(100, model.state.value.records.size)
        model.loadMore(); runCurrent()
        assertEquals(200, model.state.value.records.size)
        model.updateFilters { it.copy(text = "sale-123") }
        advanceTimeBy(251); runCurrent()
        assertEquals(100, store.lastLimit)
        assertEquals("sale-123", store.lastFilter.text)
    }

    @Test fun `invalid date filter produces actionable error then valid filter recovers`() = runTest(dispatcher) {
        backgroundScope.launch { model.state.collect() }
        model.updateFilters { it.copy(period = DiagnosticsPeriod.CUSTOM, startDate = "invalid") }
        advanceTimeBy(501); runCurrent()
        assertEquals(0, store.reads)
        assertEquals("Informe as datas em aaaa-mm-dd.", model.state.value.error)
        model.updateFilters { DiagnosticsFilters() }
        advanceTimeBy(251); runCurrent()
        assertNull(model.state.value.error)
        assertFalse(model.state.value.loading)
    }

    @Test fun `trace query fetches complete operation chronologically instead of recent page`() = runTest(dispatcher) {
        store.records = (1..250).map { entry(it.toString()).copy(traceId = "trace-sale") } + entry("other").copy(traceId = "other-trace")
        model.openTrace("trace-sale")
        backgroundScope.launch { model.traceState.collect() }
        advanceTimeBy(501); runCurrent()
        assertTrue(store.oldestFirst)
        assertEquals("trace-sale", store.lastFilter.traceId)
        assertEquals(250, model.traceState.value.entries.size)
        model.openTrace(null); runCurrent()
        assertTrue(model.traceState.value.entries.isEmpty())
    }

    @Test fun `local store failures are shown and recover on manual refresh`() = runTest(dispatcher) {
        store.fail = true
        backgroundScope.launch { model.state.collect() }
        advanceTimeBy(501); runCurrent()
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.loading)
        store.fail = false
        model.refresh(); runCurrent()
        assertNull(model.state.value.error)
    }

    private fun entry(id: String) = LogEntry(id, 1_000, LogLevel.INFO, LogCategory.SALE, "SALE_SAVED_LOCAL", "Venda salva", connectivityStatus = ConnectivityStatus.OFFLINE)

    private class FakeStore : DiagnosticLogStore {
        override val changes = MutableStateFlow(0L)
        override val health = MutableStateFlow(LoggerHealth())
        var reads = 0
        var records = emptyList<LogEntry>()
        var lastFilter = LogFilter()
        var lastLimit = 0
        var oldestFirst = false
        var fail = false
        private fun selected(filter: LogFilter) = records.filter { filter.traceId == null || filter.traceId == it.traceId }
        override suspend fun query(filter: LogFilter, limit: Int, offset: Int, oldestFirst: Boolean): List<LogEntry> {
            reads++; check(!fail)
            lastFilter = filter; lastLimit = limit; this.oldestFirst = oldestFirst
            return selected(filter).drop(offset).take(limit)
        }
        override suspend fun count(filter: LogFilter): Int = selected(filter).size
        override suspend fun summary(dayStartMs: Long) = LogSummary(0, 0, 0, 0, null, null, records.size)
        override suspend fun clear(mode: LogClearMode) = Unit
        override suspend fun export(filter: LogFilter, writer: Writer) = 0
    }
}

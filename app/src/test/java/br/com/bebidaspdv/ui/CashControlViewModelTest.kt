package br.com.bebidaspdv.ui

import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import br.com.bebidaspdv.diagnostics.RecordingLogger
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CashControlViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeCashRepository()
    private lateinit var model: CashControlViewModel
    private val session = CashSession("session", "device", "operator", "Operador", Instant.EPOCH, CashSessionStatus.OPEN, 0, 0)
    private val summary = CashPaymentSummary("session", 1, emptyMap(), 0)

    @Before fun setUp() { Dispatchers.setMain(dispatcher); model = CashControlViewModel(repository) }
    @After fun tearDown() { model.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun `opening and closing propagate their traces and report real completion or failure`() = runTest(dispatcher) {
        model.viewModelScope.cancel()
        val logger = RecordingLogger()
        model = CashControlViewModel(repository, logger)
        var observed: TraceContext? = null
        model.run(event = "CASH_REGISTER_OPEN_REQUEST") { observed = currentLogTrace() }
        runCurrent()
        assertNotNull(observed)
        assertEquals(observed?.traceId, logger.records.last().traceId)
        assertEquals(LogStatus.SUCCESS, logger.records.last().status)
        model.run(event = "CASH_REGISTER_CLOSE_REQUEST", entityId = "session") { throw IllegalStateException("server denied") }
        runCurrent()
        val failure = logger.records.last()
        assertEquals(LogStatus.FAILED, failure.status)
        assertEquals("session", failure.entityId)
        assertNotEquals(observed?.traceId, failure.traceId)
        assertFalse(model.state.value.working)
    }

    @Test fun `payment query starts only on request and can refresh after completion`() = runTest(dispatcher) {
        repository.read = { summary }
        model.start(false)
        runCurrent()
        assertEquals(0, repository.reads)
        model.payments(session)
        runCurrent()
        assertEquals(1, repository.reads)
        assertEquals(summary, model.state.value.paymentSummary)
        assertFalse(model.state.value.paymentLoading)
        model.payments(session)
        runCurrent()
        assertEquals(2, repository.reads)
    }

    @Test fun `timed out payment query stops loading and allows retry`() = runTest(dispatcher) {
        repository.read = { withTimeout(30_000) { awaitCancellation() } }
        model.payments(session)
        runCurrent()
        assertTrue(model.state.value.paymentLoading)
        advanceTimeBy(30_001)
        runCurrent()
        assertFalse(model.state.value.paymentLoading)
        assertNotNull(model.state.value.paymentError)
        repository.read = { summary }
        model.payments(session)
        runCurrent()
        assertEquals(summary, model.state.value.paymentSummary)
        assertNull(model.state.value.paymentError)
    }

    @Test fun `leaving screen discards unfinished query and late completion`() = runTest(dispatcher) {
        val result = CompletableDeferred<CashPaymentSummary>()
        repository.read = { result.await() }
        model.payments(session)
        runCurrent()
        model.stop()
        result.complete(summary)
        runCurrent()
        assertNull(model.state.value.paymentSession)
        assertNull(model.state.value.paymentSummary)
        assertNull(model.state.value.paymentError)
        assertFalse(model.state.value.paymentLoading)
    }

    private class FakeCashRepository : CashControlRepository {
        var reads = 0
        var read: suspend () -> CashPaymentSummary = { error("Query not configured") }
        override val deviceId = "device"
        override val pendingOperations = MutableStateFlow<List<PendingCashOperation>>(emptyList())
        override fun observeSessions() = flowOf(Feed(emptyList<CashSession>()))
        override fun observeEvents() = flowOf(Feed(emptyList<CashEvent>()))
        override fun observeReversals(month: YearMonth, zone: ZoneId) = flowOf(Feed(emptyList<SaleReversal>()))
        override suspend fun readPaymentSummary(sessionId: String): CashPaymentSummary { reads++; return read() }
        override suspend fun openSession(openingCents: Long) = Unit
        override suspend fun addMovement(sessionId: String, type: CashEventType, amountCents: Long, reason: String) = Unit
        override suspend fun closeSession(sessionId: String, countedCents: Long, expectedRevision: Long) = Unit
        override suspend fun previewReversal(saleId: String): ReversalPreview = error("Not used")
        override suspend fun reverseSale(preview: ReversalPreview, reason: String) = Unit
        override suspend fun retryPending(id: String) = Unit
    }
}

package br.com.bebidaspdv.updates

import br.com.bebidaspdv.core.logging.*
import br.com.bebidaspdv.diagnostics.RecordingLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LoggingAppUpdatesTest {
    private val logger = RecordingLogger()
    private class Gateway : AppUpdates {
        override val enabled = true
        var checkAction: suspend () -> AvailableUpdate? = { null }
        var installAction: suspend () -> Unit = {}
        override fun signOutTester() = Unit
        override suspend fun check() = checkAction()
        override suspend fun awaitPendingWrites() = Unit
        override suspend fun install(onProgress: (String) -> Unit) { installAction() }
    }
    @Test fun `inherited trace joins sync and update without declaring successful installation`() = runTest {
        val updates = LoggingAppUpdates(Gateway(), logger)
        val trace = TraceContext()
        withLogTrace(trace) { updates.awaitPendingWrites(); updates.install {} }
        assertEquals(setOf(trace.traceId), logger.records.map { it.traceId }.toSet())
        assertTrue(logger.records.any { it.event == "UPDATE_ACTION_RETURNED" })
        assertFalse(logger.records.any { it.event == "UPDATE_INSTALLED" })
    }
    @Test fun `timeout is a failure but user action and cancellation are not errors`() = runTest {
        val gateway = Gateway()
        val updates = LoggingAppUpdates(gateway, logger)
        gateway.checkAction = { withTimeout(10) { awaitCancellation() } }
        runCatching { updates.check() }
        assertEquals(LogStatus.FAILED, logger.records.last().status)
        gateway.checkAction = { throw CancellationException() }
        runCatching { updates.check() }
        assertEquals(LogStatus.CANCELLED, logger.records.last().status)
        gateway.installAction = { throw UpdateActionRequired("Autorize no Android") }
        runCatching { updates.install {} }
        assertEquals(LogStatus.CANCELLED, logger.records.last().status)
        assertEquals(1, logger.records.count { it.level == LogLevel.ERROR })
    }
}

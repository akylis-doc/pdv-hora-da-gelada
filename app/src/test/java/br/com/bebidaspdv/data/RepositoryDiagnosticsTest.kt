package br.com.bebidaspdv.data

import br.com.bebidaspdv.core.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual callback state adapter: observed cache state is distinct from server commit. */
class RepositoryDiagnosticsTest {
    @Test fun `sale emits local acceptance and server confirmation once`() {
        val logger = RecordingLogger()
        val write = LocalWriteDiagnostics(logger, TraceContext(operationId = "sale-1"), LogCategory.SALE, "SALE", "Repository")
        write.queued()
        write.localAccepted()
        write.localAccepted()
        assertEquals(1, logger.records.count { it.event == "SALE_SAVED_LOCAL" })
        assertEquals(LogSyncStatus.PENDING, logger.records.last().sync)
        assertFalse(logger.records.any { it.event == "SALE_SYNCED" })
        write.confirmed()
        write.confirmed()
        assertEquals(1, logger.records.count { it.event == "SALE_SYNCED" })
        assertEquals(1, logger.records.count { it.event == "DATABASE_BATCH_COMMITTED" })
        assertEquals(LogSyncStatus.SYNCED, logger.records.last().sync)
    }

    @Test fun `server confirmation without local snapshot does not invent a local saved event`() {
        val logger = RecordingLogger()
        val write = LocalWriteDiagnostics(logger, TraceContext(), LogCategory.SALE, "SALE", "Repository")
        write.confirmed()
        write.queued()
        write.localAccepted()
        assertFalse(logger.records.any { it.event == "SALE_SAVED_LOCAL" || it.sync == LogSyncStatus.PENDING })
    }

    @Test fun `recovered operation remains pending until actual server result`() {
        val logger = RecordingLogger()
        val trace = TraceContext(traceId = "original-trace", operationId = "sale-1")
        val write = LocalWriteDiagnostics(logger, trace, LogCategory.SYNC, "MOVEMENT", "Repository", recovered = true)
        write.queued()
        assertEquals("SYNC_OPERATION_RECOVERED", logger.records.single().event)
        assertEquals(LogSyncStatus.PENDING, logger.records.single().sync)
        assertEquals(0, logger.startedSteps)
        write.confirmed()
        assertTrue(logger.records.all { it.trace?.traceId == "original-trace" && it.trace.operationId == "sale-1" })
    }

    @Test fun `authoritative rejection records exception once and prevents false confirmation`() {
        val logger = RecordingLogger()
        val write = LocalWriteDiagnostics(logger, TraceContext(), LogCategory.SALE, "SALE", "Repository")
        val error = IllegalStateException("rejected")
        write.rejected(error)
        write.rejected(error)
        write.confirmed()
        assertEquals(1, logger.records.size)
        assertEquals("SALE_FAILED", logger.records.single().event)
        assertSame(error, logger.records.single().error)
        assertEquals(LogSyncStatus.FAILED, logger.records.single().sync)
    }

    @Test fun `leaving UI does not turn a pending Firestore operation into failure`() {
        val logger = RecordingLogger()
        val write = LocalWriteDiagnostics(logger, TraceContext(), LogCategory.SALE, "SALE", "Repository")
        write.queued()
        write.cancelWaiting()
        write.confirmed()
        assertFalse(logger.records.any { it.level == LogLevel.ERROR })
        assertEquals("SALE_SYNCED", logger.records.last().event)
    }

    @Test fun `document write does not claim a batch commit`() {
        val logger = RecordingLogger()
        LocalWriteDiagnostics(logger, TraceContext(), LogCategory.PRODUCT, "DOCUMENT", "Repository", atomicBatch = false).confirmed()
        assertTrue(logger.records.any { it.event == "DATABASE_DOCUMENT_COMMITTED" })
        assertFalse(logger.records.any { it.event == "DATABASE_BATCH_COMMITTED" })
    }

    @Test fun `rejection snapshot followed by task failure retains original cause once`() {
        val logger = RecordingLogger()
        val write = LocalWriteDiagnostics(logger, TraceContext(), LogCategory.SALE, "SALE", "Repository")
        val failure = IllegalStateException("original server cause")
        write.rejected()
        write.rejected(failure)
        write.rejected(failure)
        assertEquals(1, logger.records.count { it.event == "SALE_FAILED" })
        assertEquals(1, logger.records.count { it.event == "SALE_REJECTION_CAUSE" })
        assertSame(failure, logger.records.last().error)
    }

    @Test fun `queued callbacks are deduplicated and long offline wait is measured without slow classification`() {
        val logger = RecordingLogger()
        var clock = 0L
        val write = LocalWriteDiagnostics(logger, TraceContext(), LogCategory.SALE, "SALE", "Repository", nanoTime = { clock })
        repeat(20) { write.queued() }
        write.localAccepted()
        clock = 3_600_000_000_000L
        write.confirmed()
        assertEquals(1, logger.records.count { it.event == "SALE_SYNC_QUEUED" })
        assertEquals(3_600_000L, logger.records.last().duration)
        assertEquals(LogLevel.INFO, logger.records.last().level)
    }

    @Test fun `query listener failures never reject a submitted write`() {
        val logger = RecordingLogger()
        val trace = TraceContext(operationId = "sale-1")
        val write = LocalWriteDiagnostics(logger, trace, LogCategory.SALE, "SALE", "Repository")
        val query = QueryDiagnostics(logger, trace)
        query.failed(IllegalStateException("listener failed"))
        query.failed(IllegalStateException("duplicate callback"))
        write.localAccepted()
        write.confirmed()
        assertEquals(1, logger.records.count { it.event == "DATABASE_QUERY_FAILED" })
        assertFalse(logger.records.any { it.event == "SALE_FAILED" })
        assertEquals("SALE_SYNCED", logger.records.last().event)
    }

    @Test fun `query emits only the first snapshot observation`() {
        val logger = RecordingLogger()
        val query = QueryDiagnostics(logger, TraceContext())
        repeat(1000) { query.received(fromCache = true, pending = false) }
        assertEquals(1, logger.records.size)
        assertEquals(LogSyncStatus.UNKNOWN, logger.records.single().sync)
    }

    @Test fun `repository trace inherits UI trace and binds the durable UUID`() = runTest {
        val logger = RecordingLogger()
        val ui = TraceContext(traceId = "ui-trace", screen = "CashierScreen")
        withLogTrace(ui) {
            val repository = logger.repositoryTrace(LogCategory.SALE, "SUBMIT", "Repository", "submit", "sale-uuid", "sale-uuid")
            assertEquals("ui-trace", repository.traceId)
            assertEquals("CashierScreen", repository.screen)
            assertEquals("sale-uuid", repository.operationId)
            assertEquals("Repository", repository.component)
            assertEquals("submit", repository.function)
        }
        assertNull(currentLogTrace())
    }

    @Test fun `repository measurement propagates original exception and resets context`() = runTest {
        val logger = RecordingLogger()
        val failure = IllegalArgumentException("original")
        try {
            logger.repositoryStep(TraceContext(), LogCategory.DATABASE, "WRITE", "Repository", "write") { throw failure }
            fail("Expected original exception")
        } catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
        assertEquals(1, logger.failedSteps)
        assertNull(currentLogTrace())
    }

    @Test fun `coroutine cancellation is cancellation rather than write failure`() = runTest {
        val logger = RecordingLogger()
        try {
            logger.repositoryStep(TraceContext(), LogCategory.DATABASE, "WRITE", "Repository", "write") { throw CancellationException("screen closed") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, logger.cancelledSteps)
        assertEquals(0, logger.failedSteps)
    }

    @Test fun `timeout is a failed measured step rather than ordinary screen cancellation`() = runTest {
        val logger = RecordingLogger()
        try {
            logger.repositoryStep(TraceContext(), LogCategory.DATABASE, "QUERY", "Repository", "query") {
                withTimeout(5) { delay(10) }
            }
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) { }
        assertEquals(1, logger.failedSteps)
        assertEquals(0, logger.cancelledSteps)
    }

    private data class Record(val level: LogLevel, val event: String, val trace: TraceContext?, val sync: LogSyncStatus, val error: Throwable?, val duration: Long?)
    private class RecordingLogger : AppLogger by NoOpLogger {
        val records = mutableListOf<Record>()
        var startedSteps = 0
        var failedSteps = 0
        var cancelledSteps = 0
        override fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?, status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String, String>, syncStatus: LogSyncStatus) {
            records += Record(level, event, trace, syncStatus, error, durationMs)
        }
        override fun startStep(trace: TraceContext, category: LogCategory, event: String, component: String, function: String, slowAfterMs: Long, stuckAfterMs: Long): LogStep {
            startedSteps++
            return object : LogStep {
                override fun finish(message: String, metadata: Map<String, String>, syncStatus: LogSyncStatus) = Unit
                override fun fail(error: Throwable, message: String, errorCode: String?) { failedSteps++ }
                override fun cancel(message: String) { cancelledSteps++ }
            }
        }
    }
}

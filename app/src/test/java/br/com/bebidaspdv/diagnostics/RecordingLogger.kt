package br.com.bebidaspdv.diagnostics

import br.com.bebidaspdv.core.logging.*
import java.util.UUID

/** In-memory observer for verifying propagation at application boundaries. */
class RecordingLogger : AppLogger {
    val records = mutableListOf<LogEntry>()
    private val traces = mutableMapOf<String, TraceContext>()
    override fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?, status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String, String>, syncStatus: LogSyncStatus) {
        records += LogEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), level, category, event, message,
            traceId = trace?.traceId, operationId = trace?.operationId, entityId = trace?.entityId,
            screen = trace?.screen, component = trace?.component, function = trace?.function,
            durationMs = durationMs, status = status, exceptionType = error?.javaClass?.name,
            errorCode = errorCode, metadata = metadata, syncStatus = syncStatus)
    }
    override fun startTrace(category: LogCategory, event: String, operationId: String?, entityId: String?, screen: String?, component: String?, function: String?): TraceContext {
        val trace = TraceContext(operationId = operationId, entityId = entityId, screen = screen, component = component, function = function)
        log(LogLevel.INFO, category, event, "start", trace, LogStatus.STARTED)
        return trace
    }
    override fun bindOperation(trace: TraceContext, operationId: String): TraceContext = traces.getOrPut(operationId) { trace.copy(operationId = operationId) }
    override fun forOperation(operationId: String, category: LogCategory, component: String?, function: String?) = traces.getOrPut(operationId) { TraceContext(operationId = operationId, component = component, function = function) }
    override fun startStep(trace: TraceContext, category: LogCategory, event: String, component: String, function: String, slowAfterMs: Long, stuckAfterMs: Long): LogStep {
        val stage = trace.at(component, function)
        log(LogLevel.INFO, category, "${event}_STARTED", "start", stage, LogStatus.STARTED)
        return object : LogStep {
            override fun finish(message: String, metadata: Map<String, String>, syncStatus: LogSyncStatus) { log(LogLevel.INFO, category, "${event}_COMPLETED", message, stage, LogStatus.SUCCESS, metadata = metadata, syncStatus = syncStatus) }
            override fun fail(error: Throwable, message: String, errorCode: String?) { log(LogLevel.ERROR, category, "${event}_FAILED", message, stage, LogStatus.FAILED, error = error, errorCode = errorCode) }
            override fun cancel(message: String) { log(LogLevel.INFO, category, "${event}_CANCELLED", message, stage, LogStatus.CANCELLED) }
        }
    }
}

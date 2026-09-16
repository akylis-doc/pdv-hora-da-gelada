package br.com.bebidaspdv.core.logging

import java.io.Writer
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

enum class LogLevel { TRACE, DEBUG, INFO, WARNING, ERROR, CRITICAL }
enum class LogCategory { APP, UI, AUTH, CASH_REGISTER, CART, PRODUCT, SALE, PAYMENT, STOCK, DATABASE, SYNC, FIREBASE, NETWORK, UPDATE, MIGRATION, SECURITY, PERFORMANCE, SYSTEM }
enum class LogStatus { STARTED, SUCCESS, FAILED, PENDING, RETRY, CANCELLED, SLOW, STALLED }
enum class ConnectivityStatus { ONLINE, OFFLINE, UNKNOWN }
enum class LogSyncStatus { NOT_APPLICABLE, PENDING, SYNCED, FAILED, UNKNOWN }
enum class LogClearMode { DEBUG_TRACE, EXPIRED, ALL }

data class TraceContext(
    val traceId: String = UUID.randomUUID().toString(),
    val operationId: String? = null,
    val entityId: String? = null,
    val screen: String? = null,
    val component: String? = null,
    val function: String? = null,
) {
    fun at(component: String, function: String, screen: String? = this.screen): TraceContext =
        copy(component = component, function = function, screen = screen)
}

class TraceElement(val trace: TraceContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TraceElement>
}
suspend fun currentLogTrace(): TraceContext? = currentCoroutineContext()[TraceElement]?.trace
suspend fun <T> withLogTrace(trace: TraceContext, block: suspend () -> T): T = withContext(TraceElement(trace)) { block() }

data class LogEnvironment(
    val userId: String? = null,
    val deviceId: String = "unknown",
    val connectivityStatus: ConnectivityStatus = ConnectivityStatus.UNKNOWN,
    val appVersion: String = "unknown",
    val buildVersion: String = "unknown",
    val deviceInformation: String? = null,
)

data class LogEntry(
    val id: String,
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val event: String,
    val message: String,
    val traceId: String? = null,
    val operationId: String? = null,
    val entityId: String? = null,
    val userId: String? = null,
    val deviceId: String = "unknown",
    val screen: String? = null,
    val component: String? = null,
    val function: String? = null,
    val durationMs: Long? = null,
    val status: LogStatus? = null,
    val errorCode: String? = null,
    val exceptionType: String? = null,
    val stackTrace: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val connectivityStatus: ConnectivityStatus = ConnectivityStatus.UNKNOWN,
    val syncStatus: LogSyncStatus = LogSyncStatus.NOT_APPLICABLE,
    val appVersion: String = "unknown",
    val buildVersion: String = "unknown",
    val deviceInformation: String? = null,
    /** Local journal order when several callbacks occur within the same millisecond. */
    val sequence: Long = 0,
)

data class LogFilter(
    val levels: Set<LogLevel> = emptySet(),
    val categories: Set<LogCategory> = emptySet(),
    val fromTimestamp: Long? = null,
    val toTimestamp: Long? = null,
    val traceId: String? = null,
    val operationId: String? = null,
    val event: String? = null,
    val statuses: Set<LogStatus> = emptySet(),
    val connectivityStatus: ConnectivityStatus? = null,
    val syncStatus: LogSyncStatus? = null,
    val text: String = "",
)

data class LoggerHealth(
    val droppedRecords: Long = 0,
    val storageFailures: Long = 0,
    val recoveredCorruptRecords: Long = 0,
    val lastFailure: String? = null,
    val queuedRecords: Int = 0,
)

data class LogSummary(
    val errorsToday: Int,
    val warningsToday: Int,
    val slowOperations: Int,
    val pendingSyncOperations: Int,
    val lastError: LogEntry?,
    val lastSync: LogEntry?,
    val totalRecords: Int,
)

interface DiagnosticLogStore {
    val changes: StateFlow<Long>
    val health: StateFlow<LoggerHealth>
    suspend fun query(filter: LogFilter = LogFilter(), limit: Int = 200, offset: Int = 0, oldestFirst: Boolean = false): List<LogEntry>
    suspend fun count(filter: LogFilter = LogFilter()): Int
    suspend fun summary(dayStartMs: Long): LogSummary
    suspend fun clear(mode: LogClearMode)
    /** JSON array, stable schema with snake_case names; returns exported record count. */
    suspend fun export(filter: LogFilter = LogFilter(), writer: Writer): Int
}

interface LogStep {
    fun finish(message: String = "Etapa concluída", metadata: Map<String, String> = emptyMap(), syncStatus: LogSyncStatus = LogSyncStatus.NOT_APPLICABLE)
    fun fail(error: Throwable, message: String = "Falha na etapa", errorCode: String? = null)
    fun cancel(message: String = "Etapa cancelada")
}

interface AppLogger {
    fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext? = null, status: LogStatus? = null, durationMs: Long? = null, error: Throwable? = null, errorCode: String? = null, metadata: Map<String, String> = emptyMap(), syncStatus: LogSyncStatus = LogSyncStatus.NOT_APPLICABLE)
    fun startTrace(category: LogCategory, event: String, operationId: String? = null, entityId: String? = null, screen: String? = null, component: String? = null, function: String? = null): TraceContext
    fun forOperation(operationId: String, category: LogCategory, component: String? = null, function: String? = null): TraceContext
    fun bindOperation(trace: TraceContext, operationId: String): TraceContext
    fun startStep(trace: TraceContext, category: LogCategory, event: String, component: String, function: String, slowAfterMs: Long = 500, stuckAfterMs: Long = 15_000): LogStep
    fun debug(category: LogCategory, event: String, message: String, trace: TraceContext? = null) = log(LogLevel.DEBUG, category, event, message, trace)
    fun info(category: LogCategory, event: String, message: String, trace: TraceContext? = null) = log(LogLevel.INFO, category, event, message, trace)
    fun warning(category: LogCategory, event: String, message: String, trace: TraceContext? = null) = log(LogLevel.WARNING, category, event, message, trace)
    fun error(category: LogCategory, event: String, message: String, error: Throwable? = null, trace: TraceContext? = null) = log(LogLevel.ERROR, category, event, message, trace, error = error)
    fun critical(category: LogCategory, event: String, message: String, error: Throwable? = null, trace: TraceContext? = null) = log(LogLevel.CRITICAL, category, event, message, trace, error = error)
    fun trace(category: LogCategory, event: String, message: String, trace: TraceContext? = null) = log(LogLevel.TRACE, category, event, message, trace)
}

object NoOpLogger : AppLogger {
    override fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?, status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String, String>, syncStatus: LogSyncStatus) = Unit
    override fun startTrace(category: LogCategory, event: String, operationId: String?, entityId: String?, screen: String?, component: String?, function: String?) = TraceContext(operationId = operationId, entityId = entityId, screen = screen, component = component, function = function)
    override fun forOperation(operationId: String, category: LogCategory, component: String?, function: String?) = TraceContext(operationId = operationId, component = component, function = function)
    override fun bindOperation(trace: TraceContext, operationId: String) = trace.copy(operationId = operationId)
    override fun startStep(trace: TraceContext, category: LogCategory, event: String, component: String, function: String, slowAfterMs: Long, stuckAfterMs: Long) = object : LogStep {
        override fun finish(message: String, metadata: Map<String, String>, syncStatus: LogSyncStatus) = Unit
        override fun fail(error: Throwable, message: String, errorCode: String?) = Unit
        override fun cancel(message: String) = Unit
    }
}

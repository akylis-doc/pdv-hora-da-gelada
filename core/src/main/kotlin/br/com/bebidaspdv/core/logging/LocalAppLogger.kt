package br.com.bebidaspdv.core.logging

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local only. Callers enqueue bounded snapshots; serialization, redaction and disk IO run on one worker.
 * ERROR/CRITICAL have a separate priority queue and are fsynced. No Firebase object or replay queue lives here.
 */
class LocalAppLogger(
    private val directory: File,
    private val contextProvider: () -> LogEnvironment = { LogEnvironment() },
    retention: LogRetentionPolicy = LogRetentionPolicy(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val queueCapacity: Int = 2_048,
    private val priorityCapacity: Int = 256,
) : AppLogger, Closeable {
    init { require(queueCapacity > 0 && priorityCapacity > 0) }

    private val localStore = LocalLogStore(directory, retention, wallClock)
    val store: DiagnosticLogStore = localStore
    private val modeFlow = MutableStateFlow(false)
    val diagnosticMode = modeFlow.asStateFlow()
    private val modeChanged = AtomicBoolean(false)
    private val modeDirty = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val crashInProgress = AtomicBoolean(false)
    private val wake = Semaphore(0)
    private val queueLock = Any()
    private val normal = ArrayDeque<PendingLog>()
    private val priority = ArrayDeque<PendingLog>()
    private val flushWaiters = ArrayDeque<FlushWaiter>()
    private val operationLock = Any()
    private val operationTraces = LinkedHashMap<String, TraceContext>()
    private val operationIndex = OperationTraceIndex(directory, wallClock)
    private val operationIndexRevision = AtomicLong(0)
    private var persistedOperationIndexRevision = 0L
    private val activeSteps = ConcurrentHashMap<String, Step>()
    private val sequence = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val ready = CompletableDeferred<Unit>()
    private var reportedDropped = 0L
    private var maintenanceAt = 0L
    private val worker = Thread(::work, "pdv-local-diagnostics").apply { isDaemon = true; start() }

    private data class PendingLog(val sequence: Long, val entry: LogEntry, val error: Throwable?)
    private data class FlushWaiter(val throughSequence: Long, val completion: CompletableDeferred<Boolean>)

    override fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?, status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String, String>, syncStatus: LogSyncStatus) {
        if (closed.get() || (level < LogLevel.INFO && !modeFlow.value)) return
        try {
            enqueue(snapshot(level, category, event, message, trace, status, durationMs, error, errorCode, metadata, syncStatus))
        } catch (failure: Throwable) {
            // Diagnostics must never propagate to checkout/auth/business callers, including a failing context provider.
            dropped.incrementAndGet()
        }
    }

    private fun snapshot(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?, status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String, String>, syncStatus: LogSyncStatus): PendingLog {
        val environment = try { contextProvider() } catch (_: Throwable) { LogEnvironment() }
        val serial = sequence.incrementAndGet()
        return PendingLog(serial, LogEntry(
            id = UUID.randomUUID().toString(), timestamp = wallClock(), level = level, category = category,
            event = event.take(120), message = message.take(20_000), traceId = trace?.traceId,
            operationId = trace?.operationId, entityId = trace?.entityId, userId = environment.userId,
            deviceId = environment.deviceId, screen = trace?.screen, component = trace?.component,
            function = trace?.function, durationMs = durationMs?.coerceAtLeast(0), status = status,
            errorCode = errorCode?.take(160), exceptionType = error?.javaClass?.name,
            // Copy a small, immutable snapshot; never retain arbitrary mutable payload maps.
            metadata = metadata.entries.take(24).associate { it.key.take(128) to it.value.take(2_000) },
            connectivityStatus = environment.connectivityStatus, syncStatus = syncStatus,
            appVersion = environment.appVersion, buildVersion = environment.buildVersion,
            deviceInformation = environment.deviceInformation, sequence = serial,
        ), error)
    }

    private fun enqueue(log: PendingLog) {
        synchronized(queueLock) {
            if (closed.get()) return
            val queue = if (log.entry.level >= LogLevel.ERROR) priority else normal
            val capacity = if (log.entry.level >= LogLevel.ERROR) priorityCapacity else queueCapacity
            if (queue.size >= capacity) {
                // Prefer preserving warning records even when TRACE floods the normal queue.
                val disposable = if (queue === normal) queue.firstOrNull { it.entry.level < LogLevel.WARNING } else null
                if (disposable != null) queue.remove(disposable)
                else if (queue === normal && log.entry.level < LogLevel.WARNING) { dropped.incrementAndGet(); return }
                else queue.removeFirst()
                dropped.incrementAndGet()
            }
            queue.addLast(log)
        }
        signal()
    }

    private fun signal() { if (wake.availablePermits() == 0) wake.release() }

    override fun startTrace(category: LogCategory, event: String, operationId: String?, entityId: String?, screen: String?, component: String?, function: String?): TraceContext {
        val trace = TraceContext(operationId = operationId, entityId = entityId, screen = screen, component = component, function = function)
        val linked = if (operationId == null) trace else remember(trace, operationId)
        log(LogLevel.INFO, category, event, "Operação iniciada", linked, status = LogStatus.STARTED)
        return linked
    }

    override fun forOperation(operationId: String, category: LogCategory, component: String?, function: String?): TraceContext {
        // Deterministic fallback does not require disk IO on a callback, even during startup.
        val deterministic = TraceContext(
            traceId = UUID.nameUUIDFromBytes(("pdv-diagnostic-operation:" + operationId).toByteArray(Charsets.UTF_8)).toString(),
            operationId = operationId, component = component, function = function,
        )
        return remember(deterministic, operationId).copy(component = component, function = function)
    }

    override fun bindOperation(trace: TraceContext, operationId: String): TraceContext {
        val linked = remember(trace, operationId)
        log(LogLevel.INFO, LogCategory.SYSTEM, "OPERATION_TRACE_LINKED", "Operação vinculada ao rastreamento local", linked)
        return linked
    }

    private fun remember(trace: TraceContext, operationId: String): TraceContext = synchronized(operationLock) {
        val linked = operationTraces[operationId]?.let { trace.copy(traceId = it.traceId, operationId = operationId) }
            ?: trace.copy(operationId = operationId).also {
                operationTraces[operationId] = it
                operationIndexRevision.incrementAndGet()
            }
        while (operationTraces.size > 20_000) operationTraces.remove(operationTraces.keys.first())
        linked
    }

    override fun startStep(trace: TraceContext, category: LogCategory, event: String, component: String, function: String, slowAfterMs: Long, stuckAfterMs: Long): LogStep {
        val step = Step(trace.at(component, function), category, event.removeSuffix("_STARTED"),
            slowAfterMs.coerceAtLeast(1), stuckAfterMs.coerceAtLeast(slowAfterMs.coerceAtLeast(1)))
        if (!closed.get() && activeSteps.size < 1_024) activeSteps[step.id] = step
        log(LogLevel.INFO, category, "${step.event}_STARTED", "Etapa iniciada", step.trace, status = LogStatus.STARTED,
            metadata = mapOf("step_id" to step.id, "slow_after_ms" to step.slowAfterMs.toString(), "stuck_after_ms" to step.stuckAfterMs.toString()))
        return step
    }

    private inner class Step(val trace: TraceContext, val category: LogCategory, val event: String, val slowAfterMs: Long, val stuckAfterMs: Long) : LogStep {
        val id = UUID.randomUUID().toString()
        private val startedAt = monotonicClock()
        private val finished = AtomicBoolean(false)
        private val warned = AtomicBoolean(false)
        private val stalled = AtomicBoolean(false)
        private fun elapsed() = (monotonicClock() - startedAt).coerceAtLeast(0)
        fun inspect() {
            if (finished.get()) return
            val duration = elapsed()
            if (duration >= stuckAfterMs && stalled.compareAndSet(false, true)) {
                warned.set(true)
                log(LogLevel.WARNING, LogCategory.PERFORMANCE, "${event}_STALLED", "Etapa ainda sem conclusão; conferir componente e conectividade", trace,
                    status = LogStatus.STALLED, durationMs = duration, metadata = mapOf("step_id" to id, "source_category" to category.name))
            } else if (duration > slowAfterMs && warned.compareAndSet(false, true)) {
                log(LogLevel.WARNING, LogCategory.PERFORMANCE, "${event}_SLOW", "Etapa excedeu o tempo esperado e continua em andamento", trace,
                    status = LogStatus.SLOW, durationMs = duration, metadata = mapOf("step_id" to id, "source_category" to category.name))
            }
        }
        override fun finish(message: String, metadata: Map<String, String>, syncStatus: LogSyncStatus) {
            if (!finished.compareAndSet(false, true)) return
            activeSteps.remove(id)
            val duration = elapsed()
            val classification = when {
                duration > slowAfterMs -> "SLOW"
                duration >= minOf(100L, slowAfterMs) -> "WARNING"
                else -> "NORMAL"
            }
            log(if (classification == "NORMAL") LogLevel.INFO else LogLevel.WARNING, category, "${event}_COMPLETED", message, trace,
                status = if (classification == "SLOW") LogStatus.SLOW else LogStatus.SUCCESS, durationMs = duration,
                metadata = metadata + mapOf("step_id" to id, "performance_class" to classification, "slow_after_ms" to slowAfterMs.toString()), syncStatus = syncStatus)
        }
        override fun fail(error: Throwable, message: String, errorCode: String?) {
            if (!finished.compareAndSet(false, true)) return
            activeSteps.remove(id)
            log(LogLevel.ERROR, category, "${event}_FAILED", message, trace, status = LogStatus.FAILED,
                durationMs = elapsed(), error = error, errorCode = errorCode, metadata = mapOf("step_id" to id))
        }
        override fun cancel(message: String) {
            if (!finished.compareAndSet(false, true)) return
            activeSteps.remove(id)
            log(LogLevel.INFO, category, "${event}_CANCELLED", message, trace, status = LogStatus.CANCELLED,
                durationMs = elapsed(), metadata = mapOf("step_id" to id))
        }
    }

    fun setDiagnosticMode(enabled: Boolean) {
        if (closed.get()) return
        modeChanged.set(true)
        modeFlow.value = enabled
        modeDirty.set(true)
        info(LogCategory.SYSTEM, "DIAGNOSTIC_MODE_CHANGED", if (enabled) "Modo diagnóstico ativado" else "Modo normal ativado")
        signal()
    }

    /** Waits off the caller dispatcher for a durable barrier. False means timeout/storage failure. */
    suspend fun flush(timeoutMs: Long = 2_000): Boolean = withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) {
        ready.await()
        if (!worker.isAlive) return@withTimeoutOrNull false
        val completion = CompletableDeferred<Boolean>()
        synchronized(queueLock) { flushWaiters.addLast(FlushWaiter(sequence.get(), completion)) }
        signal()
        completion.await()
    } ?: false

    /** Crash-only best effort. A bounded wait always gives control back to Android's crash handler. */
    fun recordCrash(error: Throwable) {
        if (!crashInProgress.compareAndSet(false, true)) return
        try {
            val pending = snapshot(LogLevel.CRITICAL, LogCategory.SYSTEM, "UNCAUGHT_EXCEPTION", "Exceção não tratada; o processo será encerrado",
                null, LogStatus.FAILED, null, error, null, emptyMap(), LogSyncStatus.NOT_APPLICABLE)
            // Even fsync can hang on faulty storage. Never make Android wait indefinitely for it.
            Thread({
                try { localStore.crashFallback(materialize(pending)); enqueue(pending) }
                catch (failure: Throwable) { runCatching { localStore.storageFailure(failure) } }
                finally { crashInProgress.set(false) }
            }, "pdv-crash-diagnostics").apply { isDaemon = true; start(); join(500) }
        } catch (failure: Throwable) { runCatching { localStore.storageFailure(failure) } }
    }

    private fun materialize(pending: PendingLog): LogEntry {
        val storedTraceId = pending.entry.operationId?.let { operationId -> synchronized(operationLock) { operationTraces[operationId]?.traceId } }
        return LogPrivacy.entry(pending.entry.copy(
            traceId = storedTraceId ?: pending.entry.traceId,
            stackTrace = pending.error?.let(LogPrivacy::stackTrace),
        ))
    }

    private fun persistMode() {
        if (!modeDirty.compareAndSet(true, false)) return
        val temporary = File(directory, "mode.tmp")
        directory.mkdirs()
        FileOutputStream(temporary).use { output -> output.write(if (modeFlow.value) 1 else 0); output.fd.sync() }
        atomicReplace(temporary, File(directory, "mode.dat"))
    }

    private fun persistOperationIndex() {
        val revision = operationIndexRevision.get()
        if (revision == persistedOperationIndexRevision) return
        val snapshot = synchronized(operationLock) { operationTraces.toMap() }
        operationIndex.save(snapshot)
        persistedOperationIndexRevision = revision
    }

    private fun work() {
        try {
            localStore.initialize()
            val restoredOperations = operationIndex.load() + localStore.operationTraces()
            synchronized(operationLock) {
                // Already queued operation callbacks are canonicalized when materialized after this restoration.
                restoredOperations.forEach { (id, trace) -> operationTraces[id] = trace }
                while (operationTraces.size > 20_000) operationTraces.remove(operationTraces.keys.first())
                operationIndexRevision.incrementAndGet()
            }
            sequence.accumulateAndGet(localStore.lastSequence(), ::maxOf)
            val modeFile = File(directory, "mode.dat")
            if (!modeChanged.get() && modeFile.exists()) modeFlow.value = modeFile.inputStream().use { it.read() == 1 }
        } catch (failure: Throwable) { localStore.storageFailure(failure) }
        finally { ready.complete(Unit) }
        try {
            while (!closed.get() || synchronized(queueLock) { normal.isNotEmpty() || priority.isNotEmpty() }) {
                wake.tryAcquire(250, TimeUnit.MILLISECONDS)
                activeSteps.values.forEach { it.inspect() }
                var hasMore: Boolean
                do {
                    val batch = synchronized(queueLock) {
                        buildList {
                            repeat(32) { if (priority.isNotEmpty()) add(priority.removeFirst()) }
                            repeat(64 - size) { if (normal.isNotEmpty()) add(normal.removeFirst()) }
                        }
                    }
                    var persisted = true
                    try {
                        if (batch.isNotEmpty()) localStore.append(batch.map(::materialize), durable = batch.any { it.entry.level >= LogLevel.ERROR })
                        persistMode()
                        persistOperationIndex()
                        val loss = dropped.get()
                        if (loss != reportedDropped) {
                            val delta = loss - reportedDropped
                            localStore.append(listOf(materialize(snapshot(LogLevel.WARNING, LogCategory.SYSTEM, "LOGGER_QUEUE_OVERFLOW",
                                "$delta registros descartados pela proteção de capacidade; veja saúde do diagnóstico", null,
                                null, null, null, "LOCAL_LOG_QUEUE_FULL", mapOf("dropped_records" to delta.toString()), LogSyncStatus.NOT_APPLICABLE))), durable = false)
                            reportedDropped = loss
                        }
                    } catch (failure: Throwable) {
                        persisted = false
                        localStore.storageFailure(failure)
                    }
                    val completedWaiters = synchronized(queueLock) {
                        val queuedMin = minOf(normal.firstOrNull()?.sequence ?: Long.MAX_VALUE, priority.firstOrNull()?.sequence ?: Long.MAX_VALUE)
                        val completed = mutableListOf<FlushWaiter>()
                        while (flushWaiters.isNotEmpty() && flushWaiters.first.throughSequence < queuedMin) {
                            completed.add(flushWaiters.removeFirst())
                        }
                        hasMore = normal.isNotEmpty() || priority.isNotEmpty()
                        localStore.updateHealth { it.copy(droppedRecords = dropped.get(), queuedRecords = normal.size + priority.size) }
                        completed
                    }
                    if (completedWaiters.isNotEmpty()) {
                        val durable = if (persisted) runCatching { localStore.sync(); localStore.health.value.storageFailures == 0L }
                            .getOrElse { localStore.storageFailure(it); false } else false
                        completedWaiters.forEach { it.completion.complete(durable) }
                    }
                    if (batch.isNotEmpty()) localStore.publish()
                    // Revisit watchdog between batches, including during a continuous flood.
                    activeSteps.values.forEach { it.inspect() }
                } while (hasMore)
                val now = monotonicClock()
                if (now - maintenanceAt >= 60_000 || maintenanceAt == 0L) {
                    runCatching { localStore.maintenance() }.onFailure(localStore::storageFailure)
                    maintenanceAt = now
                }
            }
        } catch (failure: Throwable) { localStore.storageFailure(failure) }
        finally {
            runCatching { localStore.sync(); persistMode(); persistOperationIndex(); localStore.publish() }.onFailure(localStore::storageFailure)
            synchronized(queueLock) { flushWaiters.forEach { it.completion.complete(false) }; flushWaiters.clear() }
        }
    }

    /** Used for tests/controlled shutdown only; Android process crashes use recordCrash instead. */
    override fun close() {
        closed.set(true)
        signal()
        if (Thread.currentThread() !== worker) runCatching { worker.join(2_000) }
    }
}

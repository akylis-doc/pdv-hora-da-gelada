package br.com.bebidaspdv.core.logging

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class LogRetentionPolicy(val maxRecords: Int = 20_000, val maxBytes: Long = 32L * 1024 * 1024) {
    init { require(maxRecords > 0 && maxBytes > 0) }
    fun retentionMs(level: LogLevel): Long = when (level) {
        LogLevel.TRACE, LogLevel.DEBUG -> 7L
        LogLevel.INFO -> 15L
        LogLevel.WARNING -> 30L
        LogLevel.ERROR, LogLevel.CRITICAL -> 90L
    } * 86_400_000
}

internal class LocalLogStore(
    private val directory: File,
    private val retention: LogRetentionPolicy,
    private val now: () -> Long,
) : DiagnosticLogStore {
    internal val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).disableHtmlEscaping().create()
    private val journal = File(directory, "events.jsonl")
    private val recovery = File(directory, "crash-recovery.json")
    private val recoveryLock = Any()
    private val lock = Any()
    private val entries = ArrayList<LogEntry>()
    private val sizes = ArrayList<Int>()
    private var bytes = 0L
    private var removedSinceRewrite = 0
    private val ready = CompletableDeferred<Unit>()
    private val changeFlow = MutableStateFlow(0L)
    private val healthFlow = MutableStateFlow(LoggerHealth())
    override val changes = changeFlow.asStateFlow()
    override val health = healthFlow.asStateFlow()

    internal fun updateHealth(update: (LoggerHealth) -> LoggerHealth) = synchronized(healthFlow) { healthFlow.value = update(healthFlow.value) }
    internal fun storageFailure(error: Throwable) = updateHealth { it.copy(storageFailures = it.storageFailures + 1, lastFailure = LogPrivacy.text(error.javaClass.simpleName + ": " + error.message, 300)) }
    internal fun initialize() {
        try {
            synchronized(lock) {
                check(directory.isDirectory || directory.mkdirs()) { "Não foi possível abrir o armazenamento local de diagnóstico" }
                val temporary = File(directory, "events.tmp")
                if (!journal.exists() && temporary.exists()) atomicReplace(temporary, journal)
                if (journal.exists()) journal.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        try {
                            require(line.length <= 40_000)
                            val item = gson.fromJson(line, LogEntry::class.java)
                            require(item != null && item.id.isNotBlank() && item.event.isNotBlank() && item.timestamp > 0)
                            requireNotNull(item.level); requireNotNull(item.category); requireNotNull(item.message)
                            val safe = LogPrivacy.entry(item)
                            val size = gson.toJson(safe).toByteArray(Charsets.UTF_8).size + 1
                            entries.add(safe); sizes.add(size); bytes += size
                            if (entries.size > retention.maxRecords + 256 || bytes > retention.maxBytes + 1_048_576) retainLocked()
                        } catch (_: Exception) {
                            updateHealth { it.copy(recoveredCorruptRecords = it.recoveredCorruptRecords + 1) }
                        }
                    }
                }
                synchronized(recoveryLock) { if (recovery.exists()) {
                    try {
                        require(recovery.length() <= 40_000)
                        val log = LogPrivacy.entry(gson.fromJson(recovery.readText(), LogEntry::class.java))
                        if (entries.none { it.id == log.id }) { entries.add(log); sizes.add(gson.toJson(log).toByteArray(Charsets.UTF_8).size + 1) }
                    } catch (error: Exception) { storageFailure(error) }
                } }
                bytes = sizes.sumOf { it.toLong() }
                retainLocked()
                rewriteLocked()
                synchronized(recoveryLock) { recovery.delete() }
            }
        } catch (error: Exception) { storageFailure(error) }
        finally { ready.complete(Unit); publish() }
    }

    internal fun append(logs: List<LogEntry>, durable: Boolean) {
        if (logs.isEmpty()) return
        synchronized(lock) {
            directory.mkdirs()
            FileOutputStream(journal, true).use { output ->
                logs.forEach { raw ->
                    val safe = LogPrivacy.entry(raw)
                    val json = (gson.toJson(safe) + "\n").toByteArray(Charsets.UTF_8)
                    output.write(json)
                    entries.add(safe); sizes.add(json.size); bytes += json.size
                }
                if (durable) output.fd.sync()
            }
            if (entries.size > retention.maxRecords || bytes > retention.maxBytes || entries.size % 250 < logs.size) {
                retainLocked()
                // Pruning is immediate in memory. Compact in chunks instead of rewriting 32MB on every event.
                if (removedSinceRewrite >= 500 || journal.length() >= retention.maxBytes) rewriteLocked()
            }
        }
    }

    internal fun publish() = synchronized(changeFlow) { changeFlow.value = changeFlow.value + 1 }
    internal fun maintenance() = synchronized(lock) { if (retainLocked()) { rewriteLocked(); publish() } }
    internal fun sync() = synchronized(lock) { if (journal.exists()) FileOutputStream(journal, true).use { it.fd.sync() } }
    internal fun operationTraces(): Map<String, TraceContext> = synchronized(lock) {
        entries.sortedWith(compareBy<LogEntry> { it.timestamp }.thenBy { it.sequence }).mapNotNull { log ->
            val operation = log.operationId ?: return@mapNotNull null
            val trace = log.traceId ?: return@mapNotNull null
            operation to TraceContext(trace, operation, log.entityId, log.screen, log.component, log.function)
        }.toMap()
    }
    internal fun lastSequence(): Long = synchronized(lock) { entries.maxOfOrNull { it.sequence } ?: 0L }
    private fun retainLocked(): Boolean {
        var changed = false
        val timestamp = now()
        for (index in entries.lastIndex downTo 0) {
            if (timestamp - entries[index].timestamp > retention.retentionMs(entries[index].level)) {
                bytes -= sizes.removeAt(index); entries.removeAt(index); changed = true; removedSinceRewrite++
            }
        }
        // Prefer retaining warnings/errors under pressure. All classes remain bounded by the hard cap.
        while (entries.size > retention.maxRecords || bytes > retention.maxBytes) {
            val index = entries.indexOfFirst { it.level < LogLevel.WARNING }.takeIf { it >= 0 } ?: 0
            bytes -= sizes.removeAt(index); entries.removeAt(index); changed = true; removedSinceRewrite++
        }
        return changed
    }

    private fun rewriteLocked() {
        val temporary = File(directory, "events.tmp")
        FileOutputStream(temporary).use { output ->
            entries.forEach { output.write((gson.toJson(it) + "\n").toByteArray(Charsets.UTF_8)) }
            output.fd.sync()
        }
        atomicReplace(temporary, journal)
        removedSinceRewrite = 0
    }

    override suspend fun query(filter: LogFilter, limit: Int, offset: Int, oldestFirst: Boolean): List<LogEntry> = withContext(Dispatchers.IO) {
        ready.await()
        synchronized(lock) {
            val selected = entries.asSequence().filter { matches(it, filter) }.sortedWith(compareBy<LogEntry> { it.timestamp }.thenBy { it.sequence })
            (if (oldestFirst) selected else selected.toList().asReversed().asSequence()).drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 20_000)).toList()
        }
    }
    override suspend fun count(filter: LogFilter): Int = withContext(Dispatchers.IO) { ready.await(); synchronized(lock) { entries.count { matches(it, filter) } } }
    override suspend fun summary(dayStartMs: Long): LogSummary = withContext(Dispatchers.IO) {
        ready.await()
        synchronized(lock) {
            val ordered = entries.sortedWith(compareBy<LogEntry> { it.timestamp }.thenBy { it.sequence })
            val syncLatest = ordered.filter { it.operationId != null && when (it.syncStatus) {
                LogSyncStatus.PENDING, LogSyncStatus.SYNCED, LogSyncStatus.FAILED -> true
                else -> false
            } }.associateBy { it.operationId }
            LogSummary(
                errorsToday = entries.count { it.timestamp >= dayStartMs && it.level >= LogLevel.ERROR },
                warningsToday = entries.count { it.timestamp >= dayStartMs && it.level == LogLevel.WARNING },
                slowOperations = entries.filter { it.timestamp >= dayStartMs && (it.status == LogStatus.SLOW || it.status == LogStatus.STALLED) }.distinctBy { it.traceId ?: it.id }.size,
                pendingSyncOperations = syncLatest.values.count { it.syncStatus == LogSyncStatus.PENDING },
                lastError = ordered.lastOrNull { it.level >= LogLevel.ERROR },
                lastSync = ordered.lastOrNull { it.syncStatus == LogSyncStatus.SYNCED }, totalRecords = entries.size,
            )
        }
    }
    override suspend fun clear(mode: LogClearMode) = withContext(Dispatchers.IO) {
        ready.await()
        synchronized(lock) {
            when (mode) {
                LogClearMode.ALL -> { entries.clear(); sizes.clear(); bytes = 0 }
                LogClearMode.DEBUG_TRACE -> for (index in entries.lastIndex downTo 0) if (entries[index].level <= LogLevel.DEBUG) { entries.removeAt(index); bytes -= sizes.removeAt(index) }
                LogClearMode.EXPIRED -> retainLocked()
            }
            rewriteLocked()
            synchronized(recoveryLock) { recovery.delete() }
        }
        publish()
    }
    override suspend fun export(filter: LogFilter, writer: Writer): Int = withContext(Dispatchers.IO) {
        ready.await()
        val snapshot = synchronized(lock) { entries.filter { matches(it, filter) }.sortedWith(compareBy<LogEntry> { it.timestamp }.thenBy { it.sequence }) }
        writer.write("[\n")
        snapshot.forEachIndexed { index, entry ->
            if (index > 0) writer.write(",\n")
            writer.write(gson.toJson(LogPrivacy.entry(entry)))
        }
        writer.write("\n]\n"); writer.flush(); snapshot.size
    }

    private fun matches(log: LogEntry, filter: LogFilter): Boolean {
        if (filter.levels.isNotEmpty() && log.level !in filter.levels) return false
        if (filter.categories.isNotEmpty() && log.category !in filter.categories) return false
        if (filter.fromTimestamp != null && log.timestamp < filter.fromTimestamp) return false
        if (filter.toTimestamp != null && log.timestamp > filter.toTimestamp) return false
        if (!filter.traceId.isNullOrBlank() && log.traceId != filter.traceId) return false
        if (!filter.operationId.isNullOrBlank() && log.operationId != filter.operationId) return false
        if (!filter.event.isNullOrBlank() && !log.event.contains(filter.event, true)) return false
        if (filter.statuses.isNotEmpty() && log.status !in filter.statuses) return false
        if (filter.connectivityStatus != null && log.connectivityStatus != filter.connectivityStatus) return false
        if (filter.syncStatus != null && log.syncStatus != filter.syncStatus) return false
        if (filter.text.isNotBlank()) {
            val haystack = listOf(log.event, log.message, log.traceId, log.operationId, log.entityId, log.errorCode, log.category.name, log.component, log.function, log.metadata.toString()).joinToString(" ")
            if (!haystack.contains(filter.text.trim(), true)) return false
        }
        return true
    }

    internal fun crashFallback(log: LogEntry) = synchronized(recoveryLock) {
        directory.mkdirs()
        val temporary = File(directory, "crash-recovery.tmp")
        FileOutputStream(temporary).use { output -> output.write(gson.toJson(LogPrivacy.entry(log)).toByteArray(Charsets.UTF_8)); output.fd.sync() }
        atomicReplace(temporary, recovery)
    }
}

internal fun atomicReplace(source: File, destination: File) {
    try { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
}

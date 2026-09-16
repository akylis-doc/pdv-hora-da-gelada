package br.com.bebidaspdv.core.logging

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/** Contains only opaque operation/trace IDs, so log retention does not disconnect a later sync. */
internal class OperationTraceIndex(private val directory: File, private val now: () -> Long) {
    private val target = File(directory, "operation-traces.json")
    private val gson = Gson()
    private data class Link(val operationId: String, val traceId: String)
    private data class Index(val version: Int, val savedAt: Long, val links: List<Link>)

    fun load(): Map<String, TraceContext> {
        if (!target.exists()) return emptyMap()
        // A damaged/foreign file must not allocate unbounded memory or prevent the journal opening.
        if (target.length() > 6L * 1024 * 1024) return emptyMap()
        return runCatching {
            val index = target.reader().use { gson.fromJson(it, Index::class.java) }
            if (index.version != 1) return@runCatching emptyMap()
            index.links.takeLast(20_000).mapNotNull { link ->
                val operation = LogPrivacy.identifier(link.operationId)?.takeIf { it.isNotBlank() }
                val trace = LogPrivacy.identifier(link.traceId)?.takeIf { it.isNotBlank() }
                if (operation == null || trace == null) null else operation to TraceContext(traceId = trace, operationId = operation)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun save(traces: Map<String, TraceContext>) {
        check(directory.isDirectory || directory.mkdirs())
        val links = traces.entries.toList().takeLast(20_000).mapNotNull { (operation, trace) ->
            val safeOperation = LogPrivacy.identifier(operation) ?: return@mapNotNull null
            val safeTrace = LogPrivacy.identifier(trace.traceId) ?: return@mapNotNull null
            Link(safeOperation, safeTrace)
        }
        val temporary = File(directory, "operation-traces.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(gson.toJson(Index(1, now(), links)).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        atomicReplace(temporary, target)
    }
}

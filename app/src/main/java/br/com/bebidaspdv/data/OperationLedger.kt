package br.com.bebidaspdv.data

import android.content.Context
import android.annotation.SuppressLint
import br.com.bebidaspdv.core.OperationStatus
import br.com.bebidaspdv.core.SyncState
import br.com.bebidaspdv.core.logging.*
import org.json.JSONArray
import org.json.JSONObject

/** Durable receipts, not a second write queue. Only Firestore replays its own persisted batches. */
internal class OperationLedger(context: Context, private val logger: AppLogger = NoOpLogger) {
    private val preferences = context.applicationContext.getSharedPreferences("pdv_operation_receipts", Context.MODE_PRIVATE)
    private val traceIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberTrace(trace: TraceContext) { trace.operationId?.let { traceIds[it] = trace.traceId } }
    fun traceId(operationId: String): String? = traceIds[operationId]

    fun read(): List<OperationStatus> = runCatching {
        val array = JSONArray(preferences.getString("operations", "[]"))
        (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            value.optString("diagnostic_trace_id").takeIf { it.isNotBlank() }?.let { traceIds[value.getString("id")] = it }
            OperationStatus(value.getString("id"), SyncState.valueOf(value.getString("state")),
                value.optString("message").takeIf { it.isNotBlank() })
        }
    }.getOrElse {
        logger.critical(LogCategory.DATABASE, "LOCAL_RECEIPTS_READ_FAILED", "Não foi possível ler os comprovantes locais.", it,
            TraceContext(component = "OperationLedger", function = "read"))
        throw IllegalStateException("Não foi possível ler os comprovantes locais. Preserve os dados do aplicativo para reconciliação.", it)
    }

    // The KTX edit helper returns Unit; this code needs commit's Boolean to detect disk failures.
    @SuppressLint("UseKtx")
    fun write(operations: List<OperationStatus>) {
        val array = JSONArray()
        operations.forEach { status ->
            array.put(JSONObject().put("id", status.id).put("state", status.state.name)
                .put("message", status.message.orEmpty()).put("diagnostic_trace_id", traceIds[status.id].orEmpty()))
        }
        val saved = preferences.edit().putString("operations", array.toString()).commit()
        if (!saved) logger.critical(LogCategory.DATABASE, "LOCAL_RECEIPTS_WRITE_FAILED", "Não foi possível persistir o comprovante local.",
            trace = TraceContext(component = "OperationLedger", function = "write"))
        check(saved) {
            "Não foi possível salvar o comprovante local; confira o espaço livre do aparelho."
        }
        traceIds.keys.retainAll(operations.map { it.id }.toSet())
    }
}

package br.com.bebidaspdv.data

import br.com.bebidaspdv.core.logging.*
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal fun Throwable.diagnosticCode(): String? = generateSequence(this) { it.cause }
    .take(12).filterIsInstance<FirebaseFirestoreException>().firstOrNull()?.code?.name

/** Context is explicit in SDK callbacks: coroutine context alone cannot cross that boundary. */
internal suspend fun AppLogger.repositoryTrace(category: LogCategory, event: String, component: String,
    function: String, operationId: String? = null, entityId: String? = null): TraceContext {
    val inherited = currentLogTrace()
    val trace = when {
        inherited != null && operationId != null -> bindOperation(inherited, operationId)
        inherited != null -> inherited
        operationId != null -> forOperation(operationId, category, component, function)
        else -> startTrace(category, event, component = component, function = function)
    }
    return trace.copy(entityId = entityId ?: trace.entityId).at(component, function)
}

internal suspend fun <T> AppLogger.repositoryStep(trace: TraceContext, category: LogCategory, event: String,
    component: String, function: String, slowAfterMs: Long = 500, stuckAfterMs: Long = 15_000,
    syncStatus: LogSyncStatus = LogSyncStatus.NOT_APPLICABLE, block: suspend () -> T): T {
    val step = startStep(trace, category, event, component, function, slowAfterMs, stuckAfterMs)
    return try {
        withLogTrace(trace) { block() }.also { step.finish(syncStatus = syncStatus) }
    } catch (failure: Exception) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) step.cancel()
        else step.fail(failure, errorCode = failure.diagnosticCode())
        throw failure
    }
}

/** Diagnostic state only. Firestore remains the sole owner of replay and business write status. */
internal class LocalWriteDiagnostics(
    private val logger: AppLogger,
    val trace: TraceContext,
    private val category: LogCategory,
    private val prefix: String,
    private val component: String,
    private val recovered: Boolean = false,
    private val atomicBatch: Boolean = true,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val accepted = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val queueReported = AtomicBoolean(false)
    private var rejectedWithoutCause = false
    private val submittedAt = nanoTime()
    private val localStep = if (recovered) null else logger.startStep(trace, LogCategory.DATABASE,
        "DATABASE_LOCAL_WRITE", component, "localAcceptance", slowAfterMs = 500, stuckAfterMs = 10_000)

    @Synchronized fun queued() {
        if (completed.get() || !queueReported.compareAndSet(false, true)) return
        logger.log(LogLevel.INFO, LogCategory.SYNC, if (recovered) "SYNC_OPERATION_RECOVERED" else "${prefix}_SYNC_QUEUED",
            if (recovered) "Acompanhamento do comprovante retomado; o Firestore gerencia a fila persistida."
            else "Gravação enviada à fila persistente do Firestore.", trace,
            status = LogStatus.PENDING, syncStatus = LogSyncStatus.PENDING)
    }

    @Synchronized fun localAccepted() {
        if (completed.get()) return
        if (accepted.compareAndSet(false, true)) {
            localStep?.finish("Gravação observada no cache persistente.", syncStatus = LogSyncStatus.PENDING)
            logger.log(LogLevel.INFO, category, "${prefix}_SAVED_LOCAL", "Gravação observada localmente com confirmação remota pendente.",
                trace.at(component, "snapshotListener"), status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.PENDING)
        }
    }

    @Synchronized fun confirmed() {
        if (completed.compareAndSet(false, true)) {
            localStep?.finish("Gravação confirmada pelo servidor.", syncStatus = LogSyncStatus.SYNCED)
            logger.log(LogLevel.INFO, LogCategory.DATABASE, if (atomicBatch) "DATABASE_BATCH_COMMITTED" else "DATABASE_DOCUMENT_COMMITTED", "Gravação confirmada pelo servidor.",
                trace.at(component, "serverConfirmation"), status = LogStatus.SUCCESS, syncStatus = LogSyncStatus.SYNCED)
            logger.log(LogLevel.INFO, LogCategory.SYNC, "${prefix}_SYNCED", "Comprovante sincronizado e confirmado.",
                trace, status = LogStatus.SUCCESS, durationMs = elapsed(), syncStatus = LogSyncStatus.SYNCED,
                metadata = mapOf("duration_scope" to if (recovered) "SINCE_OBSERVATION_RESUMED" else "SINCE_SUBMISSION",
                    "includes_offline_wait" to "true"))
        }
    }

    @Synchronized fun rejected(failure: Throwable? = null) {
        if (completed.compareAndSet(false, true)) {
            rejectedWithoutCause = failure == null
            if (failure != null) localStep?.fail(failure, errorCode = failure.diagnosticCode())
            else localStep?.cancel("Comprovante não confirmado; conferir reconciliação.")
            logger.log(LogLevel.ERROR, category, "${prefix}_FAILED", "A gravação não foi confirmada; confira o comprovante antes de refazer.",
                trace, status = LogStatus.FAILED, durationMs = elapsed(), error = failure, errorCode = failure?.diagnosticCode(), syncStatus = LogSyncStatus.FAILED)
        } else if (failure != null && rejectedWithoutCause) {
            // A server snapshot can arrive before the failed Task callback. Keep its real cause.
            rejectedWithoutCause = false
            logger.log(LogLevel.ERROR, category, "${prefix}_REJECTION_CAUSE", "Causa da rejeição informada pela tarefa de gravação.",
                trace.at(component, "writeFailure"), status = LogStatus.FAILED, error = failure,
                errorCode = failure.diagnosticCode(), syncStatus = LogSyncStatus.FAILED)
        }
    }

    fun cancelWaiting() { localStep?.cancel("A tela deixou de aguardar; a fila do Firestore continua independente.") }
    private fun elapsed() = ((nanoTime() - submittedAt) / 1_000_000).coerceAtLeast(0)
}

/** A single first-result measurement, not a log for every document or snapshot. */
internal class QueryDiagnostics(private val logger: AppLogger, private val trace: TraceContext) {
    private val startedAt = System.nanoTime()
    private val first = AtomicBoolean(true)
    private val failed = AtomicBoolean(false)
    fun received(fromCache: Boolean, pending: Boolean) {
        if (first.compareAndSet(true, false)) {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            logger.log(if (elapsed > 500) LogLevel.WARNING else LogLevel.DEBUG, LogCategory.DATABASE,
                if (elapsed > 500) "DATABASE_QUERY_SLOW" else "DATABASE_QUERY_READY", "Primeiro resultado da consulta recebido.",
                trace, status = if (elapsed > 500) LogStatus.SLOW else LogStatus.SUCCESS, durationMs = elapsed,
                metadata = mapOf("source" to if (fromCache) "CACHE" else "SERVER"),
                syncStatus = if (pending) LogSyncStatus.PENDING else if (fromCache) LogSyncStatus.UNKNOWN else LogSyncStatus.SYNCED)
        }
    }
    fun failed(error: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        logger.log(LogLevel.ERROR, LogCategory.FIREBASE, "DATABASE_QUERY_FAILED", "Falha ao observar ou interpretar a consulta.",
            trace, status = LogStatus.FAILED, durationMs = (System.nanoTime() - startedAt) / 1_000_000,
            error = error, errorCode = error.diagnosticCode())
    }
}

package br.com.bebidaspdv.updates

import br.com.bebidaspdv.core.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Never records SDK URLs, release notes, tester emails or download progress payloads. */
class LoggingAppUpdates(private val delegate: AppUpdates, private val logger: AppLogger) : AppUpdates by delegate {
    override suspend fun refreshDownloadedUpdate() = stage("UPDATE_LOCAL_FILE_CHECK") { delegate.refreshDownloadedUpdate() }
    override suspend fun check() = stage("UPDATE_CHECK", 3_000) { delegate.check() }
    override suspend fun awaitPendingWrites() = stage("UPDATE_WAIT_FOR_SYNC", 5_000) { delegate.awaitPendingWrites() }
    override suspend fun install(onProgress: (String) -> Unit) = stage("UPDATE_DOWNLOAD_OR_INSTALL", 30_000) {
        delegate.install(onProgress)
        logger.info(LogCategory.UPDATE, "UPDATE_ACTION_RETURNED",
            "A ação retornou; a instalação final é confirmada pelo Android", currentLogTrace())
    }

    override fun signOutTester() {
        val trace = logger.startTrace(LogCategory.UPDATE, "UPDATE_TESTER_SIGN_OUT", component = "LoggingAppUpdates", function = "signOutTester")
        try { delegate.signOutTester(); logger.info(LogCategory.UPDATE, "UPDATE_TESTER_SIGNED_OUT", "Conta de distribuição desconectada", trace) }
        catch (error: Exception) { logger.error(LogCategory.UPDATE, "UPDATE_TESTER_SIGN_OUT_FAILED", "Falha ao desconectar distribuição", error, trace); throw error }
    }

    private suspend fun <T> stage(event: String, slowMs: Long = 500, block: suspend () -> T): T {
        val trace = currentLogTrace() ?: logger.startTrace(LogCategory.UPDATE, event, screen = "UpdatesScreen", component = "LoggingAppUpdates", function = event)
        val step = logger.startStep(trace, LogCategory.UPDATE, event, "LoggingAppUpdates", event,
            slowAfterMs = slowMs, stuckAfterMs = maxOf(15_000, slowMs * 3))
        return withLogTrace(trace) {
            try { block().also { step.finish() } }
            catch (error: Exception) {
                if (error is UpdateActionRequired || (error is CancellationException && error !is TimeoutCancellationException)) step.cancel("Ação interrompida ou dependente do operador")
                else step.fail(error)
                throw error
            }
        }
    }
}

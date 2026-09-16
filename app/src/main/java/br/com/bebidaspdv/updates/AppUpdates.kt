package br.com.bebidaspdv.updates

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AvailableUpdate(val version: String, val code: Long, val notes: String)

private val noDownloadedUpdate = MutableStateFlow<AvailableUpdate?>(null).asStateFlow()

/** An actionable message, safe to show without exposing SDK URLs or credentials. */
class UpdateActionRequired(message: String) : IllegalStateException(message)

interface AppUpdates {
    val enabled: Boolean
    /** Only complete, locally verified APKs appear here; restored without a network check. */
    val downloadedUpdate: StateFlow<AvailableUpdate?> get() = noDownloadedUpdate
    suspend fun refreshDownloadedUpdate() = Unit
    fun signOutTester()
    suspend fun check(): AvailableUpdate?
    suspend fun awaitPendingWrites()
    /** Downloads first; a subsequent explicit call opens Android for a verified saved APK.
     * Completion is never proof of successful installation. */
    suspend fun install(onProgress: (String) -> Unit)
}

object UnavailableUpdates : AppUpdates {
    override val enabled = false
    override fun signOutTester() = Unit
    override suspend fun check(): AvailableUpdate? = error("Atualizações de teste indisponíveis nesta versão.")
    override suspend fun awaitPendingWrites(): Unit = error("Atualizações indisponíveis.")
    override suspend fun install(onProgress: (String) -> Unit): Unit = error("Atualizações indisponíveis.")
}

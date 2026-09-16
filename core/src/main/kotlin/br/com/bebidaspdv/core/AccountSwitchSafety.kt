package br.com.bebidaspdv.core

/** Authentication must survive an uncertain operation, including when the profile is blocked. */
suspend fun confirmAccountSwitch(
    hasUnresolvedOperations: () -> Boolean,
    awaitPendingWrites: suspend () -> Unit,
    signOut: () -> Unit,
) {
    fun checkPending() = check(!hasUnresolvedOperations()) {
        "Há operações sem confirmação nesta conta. Peça ao administrador para restabelecer o acesso e conferir as pendências antes de trocar de conta."
    }
    checkPending()
    awaitPendingWrites()
    checkPending()
    signOut()
}

package br.com.bebidaspdv.core

enum class OperatorRole(val storedValue: String) { ADMIN("admin"), OPERATOR("operator") }

data class OperatorPermissions(
    val editPrices: Boolean = false,
    val deleteProducts: Boolean = false,
    val viewReports: Boolean = false,
    val reverseSales: Boolean = false,
    val exportData: Boolean = false,
)

data class OperatorProfile(
    val uid: String,
    val name: String,
    val email: String,
    val role: OperatorRole = OperatorRole.OPERATOR,
    val active: Boolean = true,
    val permissions: OperatorPermissions = OperatorPermissions(),
) {
    val capabilities: OperatorCapabilities get() = when {
        !active -> OperatorCapabilities.NONE
        role == OperatorRole.ADMIN -> OperatorCapabilities.ADMIN
        else -> OperatorCapabilities(
            canEditPrices = permissions.editPrices,
            canDeleteProducts = permissions.deleteProducts,
            canViewReports = permissions.viewReports,
            canReverseSales = permissions.reverseSales,
            canExportData = permissions.exportData,
        )
    }
}

data class OperatorCapabilities(
    val canManageProfiles: Boolean = false,
    val canEditPrices: Boolean = false,
    val canDeleteProducts: Boolean = false,
    val canViewReports: Boolean = false,
    val canReverseSales: Boolean = false,
    val canExportData: Boolean = false,
) {
    val isAdmin: Boolean get() = canManageProfiles

    companion object {
        val NONE = OperatorCapabilities()
        val ADMIN = OperatorCapabilities(true, true, true, true, true, true)
        // The old claim preserves old register operations only, never administrator access.
        val LEGACY = OperatorCapabilities(false, true, true, true, false, true)
    }
}

sealed interface ProfileSession {
    data object Loading : ProfileSession
    data object SignedOut : ProfileSession
    data class Authorized(
        val uid: String,
        val name: String,
        val profile: OperatorProfile?,
        val fromCache: Boolean = false,
    ) : ProfileSession {
        val capabilities: OperatorCapabilities get() = profile?.capabilities ?: OperatorCapabilities.LEGACY
        val legacy: Boolean get() = profile == null
    }
    data class Blocked(val message: String, val disabled: Boolean = false) : ProfileSession
}

/** Only a server-confirmed absence may fall back to the historical custom claim. */
fun resolveProfileAccess(
    uid: String,
    fallbackName: String,
    profile: OperatorProfile?,
    legacyClaim: Boolean,
    profileAbsenceConfirmed: Boolean,
    fromCache: Boolean = false,
): ProfileSession = when {
    profile != null && profile.uid != uid -> ProfileSession.Blocked("Não foi possível validar o perfil desta conta.")
    profile != null && !profile.active -> ProfileSession.Blocked("Esta conta foi desativada. Peça ao administrador da loja para reativá-la.", disabled = true)
    profile != null -> ProfileSession.Authorized(uid, profile.name, profile, fromCache)
    legacyClaim && profileAbsenceConfirmed -> ProfileSession.Authorized(uid, fallbackName, null, fromCache)
    else -> ProfileSession.Blocked("Esta conta ainda não tem um perfil autorizado. Peça ao administrador da loja para concluir o cadastro.")
}

/** A cached profile alone may have been read by another administrator; it is not a validated login. */
fun restoreVerifiedProfileAccess(remembered: ProfileSession?, cached: ProfileSession?): ProfileSession? = when {
    remembered is ProfileSession.Blocked -> remembered
    cached is ProfileSession.Blocked -> cached
    remembered is ProfileSession.Authorized && cached is ProfileSession.Authorized && remembered.uid == cached.uid -> cached
    remembered is ProfileSession.Authorized -> remembered
    else -> null
}

/** Recoverable Auth account awaiting its protected Firestore profile; never contains a password. */
data class PendingOperatorProfile(val profile: OperatorProfile, val requestedBy: String)

sealed interface ProfileCreationResult {
    data class Created(val profile: OperatorProfile) : ProfileCreationResult
    data class Pending(val profile: OperatorProfile) : ProfileCreationResult
}

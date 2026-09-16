package br.com.bebidaspdv.core

import org.junit.Assert.*
import org.junit.Test

class OperatorProfileTest {
    private val profile = OperatorProfile("uid", "Operador", "operador@loja.example")

    @Test fun `a disabled profile always overrides legacy claims and administrator role`() {
        val result = resolveProfileAccess("uid", "", profile.copy(active = false, role = OperatorRole.ADMIN),
            legacyClaim = true, profileAbsenceConfirmed = true, fromCache = true)
        assertTrue(result is ProfileSession.Blocked)
        assertTrue((result as ProfileSession.Blocked).disabled)
        assertEquals(OperatorCapabilities.NONE, profile.copy(active = false, role = OperatorRole.ADMIN).capabilities)
    }

    @Test fun `legacy claim only grants prior capabilities after absence has been verified`() {
        assertTrue(resolveProfileAccess("uid", "Nome", null, true, false) is ProfileSession.Blocked)
        assertTrue(resolveProfileAccess("uid", "Nome", null, false, true) is ProfileSession.Blocked)
        val allowed = resolveProfileAccess("uid", "Nome", null, true, true) as ProfileSession.Authorized
        assertFalse(allowed.capabilities.canManageProfiles)
        assertFalse(allowed.capabilities.canReverseSales)
        assertTrue(allowed.capabilities.canViewReports)
    }

    @Test fun `operator permissions are explicit and cannot be elevated by historical claim`() {
        val allowed = resolveProfileAccess("uid", "Nome antigo", profile,
            legacyClaim = true, profileAbsenceConfirmed = true) as ProfileSession.Authorized
        assertEquals("Operador", allowed.name)
        assertEquals(OperatorCapabilities.NONE, allowed.capabilities)
        assertFalse(allowed.legacy)
        val custom = profile.copy(permissions = OperatorPermissions(viewReports = true, reverseSales = true)).capabilities
        assertTrue(custom.canViewReports)
        assertTrue(custom.canReverseSales)
        assertFalse(custom.canEditPrices)
        assertFalse(custom.canManageProfiles)
        assertFalse(custom.canExportData)
    }

    @Test fun `active admin has every permission and mismatched uid is blocked`() {
        assertEquals(OperatorCapabilities.ADMIN, profile.copy(role = OperatorRole.ADMIN).capabilities)
        assertTrue(resolveProfileAccess("another-uid", "", profile, true, true) is ProfileSession.Blocked)
    }

    @Test fun `a profile previously read by another administrator does not validate a first offline login`() {
        val cached = ProfileSession.Authorized(profile.uid, profile.name, profile, fromCache = true)
        assertNull(restoreVerifiedProfileAccess(null, cached))
        assertNull(restoreVerifiedProfileAccess(ProfileSession.Loading, cached))
    }

    @Test fun `known revocations always defeat an older allowed profile during offline restoration`() {
        val allowed = ProfileSession.Authorized(profile.uid, profile.name, profile, fromCache = true)
        val blocked = ProfileSession.Blocked("Desativado", disabled = true)
        assertEquals(blocked, restoreVerifiedProfileAccess(allowed, blocked))
        assertEquals(blocked, restoreVerifiedProfileAccess(blocked, allowed))
        assertEquals(blocked, restoreVerifiedProfileAccess(null, blocked))
    }

    @Test fun `verified offline restoration uses only the current operators cached permissions`() {
        val remembered = ProfileSession.Authorized(profile.uid, profile.name, profile.copy(role = OperatorRole.ADMIN))
        val downgraded = ProfileSession.Authorized(profile.uid, profile.name, profile, fromCache = true)
        assertEquals(downgraded, restoreVerifiedProfileAccess(remembered, downgraded))
        val anotherUid = ProfileSession.Authorized("other", "Outra conta", null, fromCache = true)
        assertEquals(remembered, restoreVerifiedProfileAccess(remembered, anotherUid))
        assertEquals(remembered, restoreVerifiedProfileAccess(remembered, null))
    }
}

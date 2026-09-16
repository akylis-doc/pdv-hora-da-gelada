package br.com.bebidaspdv.updates

import org.junit.Assert.*
import org.junit.Test

class DownloadedApkIdentityTest {
    private val installed = DownloadedApkIdentity("br.com.bebidaspdv", 4, setOf("original-certificate"))
    private val candidate = installed.copy(versionCode = 5)

    @Test fun `only the expected newer version with the installed package and complete signer set is accepted`() {
        assertTrue(isExpectedUpdate(installed, candidate, 5))
        assertFalse(isExpectedUpdate(installed, candidate.copy(packageName = "outro.app"), 5))
        assertFalse(isExpectedUpdate(installed, candidate.copy(signerSha256 = setOf("different-certificate")), 5))
        assertFalse(isExpectedUpdate(installed, candidate.copy(signerSha256 = emptySet()), 5))
        assertFalse(isExpectedUpdate(installed, candidate.copy(signerSha256 = candidate.signerSha256 + "extra-signer"), 5))
        assertFalse(isExpectedUpdate(installed, candidate, 6))
    }

    @Test fun `downgrade same version and absent installed certificate fail closed`() {
        assertFalse(isExpectedUpdate(installed, candidate.copy(versionCode = 3), 3))
        assertFalse(isExpectedUpdate(installed, installed, 4))
        assertFalse(isExpectedUpdate(installed.copy(signerSha256 = emptySet()), candidate.copy(signerSha256 = emptySet()), 5))
    }
}

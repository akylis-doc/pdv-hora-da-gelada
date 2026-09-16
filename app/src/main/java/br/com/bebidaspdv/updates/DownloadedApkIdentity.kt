package br.com.bebidaspdv.updates

/** Compare the complete signer set; key rotation is deliberately not accepted implicitly. */
internal data class DownloadedApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

internal fun isExpectedUpdate(
    installed: DownloadedApkIdentity,
    candidate: DownloadedApkIdentity,
    expectedVersion: Long,
): Boolean = candidate.packageName == installed.packageName &&
    candidate.versionCode == expectedVersion && candidate.versionCode > installed.versionCode &&
    installed.signerSha256.isNotEmpty() && candidate.signerSha256 == installed.signerSha256

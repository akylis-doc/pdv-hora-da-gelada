package br.com.bebidaspdv.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Adapter for the pinned App Distribution 16.0.0-beta20 private download location.
 * No URLs or tester credentials are read. The SDK owns the authenticated transport.
 * Keep the two filenames and debug manifest adapter under review when upgrading the SDK. */
internal class DownloadedUpdateStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("downloaded_test_update", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "verified_updates")
    val apkFile get() = File(directory, "pending-release.apk")
    private val stagingFile get() = File(directory, "pending-release.tmp")

    @Synchronized fun prepare(release: AvailableUpdate) {
        clear()
        check(preferences.edit().putLong("code", release.code).putString("version", release.version)
            .putString("notes", release.notes).putBoolean("requested", true).commit()) {
            "Não foi possível guardar a etapa de atualização. Libere espaço e tente novamente."
        }
    }

    @Synchronized fun clear() {
        preferences.edit().clear().commit()
        apkFile.delete()
        stagingFile.delete()
    }

    @Synchronized fun restore(): AvailableUpdate? {
        val release = savedRelease() ?: return null
        if (release.code <= installedIdentity().versionCode) { clear(); return null }
        if (apkFile.isFile) {
            val savedHash = preferences.getString("sha256", null)
            if (isValid(apkFile, release.code) && (savedHash == null || sha256(apkFile) == savedHash)) {
                if (savedHash == null) rememberHash()
                return release
            }
            // An altered saved copy must never silently fall back to an older SDK file.
            clear()
            return null
        }
        return capture(release)
    }

    @Synchronized fun capture(release: AvailableUpdate): AvailableUpdate? {
        if (savedRelease()?.code != release.code) return null
        if (apkFile.isFile) return restore()
        // javap of beta20 ApkUpdater.getApkFileName(): filesDir/<app label>.apk,
        // or filesDir/downloaded_release.apk. Never search external or cache storage.
        val label = runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrNull()
        val names = listOfNotNull(label?.let { "$it.apk" }, "downloaded_release.apk").distinct()
        val privateRoot = context.filesDir.canonicalFile
        val source = names.map { File(privateRoot, it) }.firstOrNull {
            it.canonicalFile.parentFile == privateRoot && it.isFile && isValid(it, release.code)
        } ?: return null
        check(directory.isDirectory || directory.mkdirs()) { "Não foi possível preparar a atualização." }
        source.inputStream().use { input -> stagingFile.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
        check(isValid(stagingFile, release.code)) { "O arquivo de atualização está incompleto ou não corresponde a este aplicativo." }
        check(stagingFile.renameTo(apkFile)) { "Não foi possível guardar a atualização baixada." }
        rememberHash()
        return release
    }

    private fun savedRelease(): AvailableUpdate? {
        if (!preferences.getBoolean("requested", false)) return null
        val code = preferences.getLong("code", 0L)
        if (code <= 0L) return null
        return AvailableUpdate(preferences.getString("version", "").orEmpty(), code,
            preferences.getString("notes", "").orEmpty())
    }

    private fun rememberHash() {
        check(preferences.edit().putString("sha256", sha256(apkFile)).commit()) {
            "Não foi possível guardar a verificação da atualização. Tente novamente."
        }
    }

    @Suppress("DEPRECATION")
    private fun installedIdentity(): DownloadedApkIdentity = identity(context.packageManager.getPackageInfo(context.packageName, signatureFlags))

    @Suppress("DEPRECATION")
    private fun isValid(file: File, expectedVersion: Long): Boolean = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags) ?: return false
        if (!isExpectedUpdate(installedIdentity(), identity(info), expectedVersion)) return false
        // Read every ZIP entry and check its CRC: a partial SDK download cannot be resumed as an APK.
        ZipFile(file).use { zip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        size += count
                        crc.update(buffer, 0, count)
                    }
                }
                check(entry.size == size && entry.crc == crc.value)
            }
        }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private val signatureFlags get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): DownloadedApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return DownloadedApkIdentity(info.packageName,
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signatures.orEmpty().map { hash(it.toByteArray()) }.toSet())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

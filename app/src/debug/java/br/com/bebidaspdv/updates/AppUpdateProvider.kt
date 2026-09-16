package br.com.bebidaspdv.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import br.com.bebidaspdv.BuildConfig
import com.google.firebase.appdistribution.BinaryType
import com.google.firebase.appdistribution.FirebaseAppDistribution
import com.google.firebase.appdistribution.UpdateStatus
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await

fun createAppUpdates(context: Context, db: FirebaseFirestore): AppUpdates = FirebaseTestUpdates(context.applicationContext, db)

private class FirebaseTestUpdates(private val context: Context, private val db: FirebaseFirestore) : AppUpdates {
    override val enabled = true
    private val distribution get() = FirebaseAppDistribution.getInstance()
    private val store = DownloadedUpdateStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operation = Mutex()
    private val downloaded = MutableStateFlow<AvailableUpdate?>(null)
    override val downloadedUpdate = downloaded.asStateFlow()
    private var selected: AvailableUpdate? = null
    private var accountRevision = 0L

    init { scope.launch { runCatching { refreshDownloadedUpdate() } } }

    override suspend fun refreshDownloadedUpdate() {
        val revision = accountRevision
        val restored = withContext(Dispatchers.IO) { store.restore() }
        if (revision == accountRevision) downloaded.value = restored
    }

    override fun signOutTester() {
        accountRevision++
        distribution.signOutTester()
        selected = null
        downloaded.value = null
        store.clear()
    }

    override suspend fun check(): AvailableUpdate? {
        // This sign-in does not touch the store operator's Firebase Auth session.
        if (!distribution.isTesterSignedIn) distribution.signInTester().await()
        val release = withTimeout(30_000) { distribution.checkForNewRelease().await() }
        selected = release?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let {
            if (it.binaryType != BinaryType.APK) throw UpdateActionRequired("Este canal precisa de uma versão APK. Peça ao responsável que disponibilize o APK no App Distribution.")
            AvailableUpdate(it.displayVersion, it.versionCode, it.releaseNotes.orEmpty())
        }
        return downloaded.value ?: selected
    }

    override suspend fun awaitPendingWrites() {
        withTimeout(15_000) { db.waitForPendingWrites().await() }
    }

    override suspend fun install(onProgress: (String) -> Unit) {
        check(operation.tryLock()) { "Uma atualização já está em andamento." }
        try {
            refreshDownloadedUpdate()
            if (downloaded.value != null) {
                openInstaller()
                return
            }
            val release = selected ?: throw UpdateActionRequired("Busque a atualização antes de baixar novamente.")
            val revision = accountRevision
            withContext(Dispatchers.IO) { store.prepare(release) }
            val completion = CompletableDeferred<Unit>()
            var captureJob: Job? = null
            fun captureDownloaded() {
                if (captureJob != null) return
                captureJob = scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { store.capture(release) }
                            ?: throw UpdateActionRequired("Não foi possível validar o download. Busque a atualização e tente baixar novamente.")
                        if (revision == accountRevision) {
                            downloaded.value = saved
                            onProgress("Download concluído. Toque em Instalar agora para continuar.")
                        }
                        completion.complete(Unit)
                    } catch (error: Exception) { completion.completeExceptionally(error) }
                }
            }
            // beta20 has no public download-only API. The debug manifest disables its
            // automatic InstallActivity. We complete at the verified DOWNLOADED event;
            // a late SDK foreground callback cannot open an installer during a sale.
            distribution.updateApp().addOnProgressListener { progress ->
                when (progress.updateStatus) {
                    UpdateStatus.DOWNLOADED -> captureDownloaded()
                    UpdateStatus.DOWNLOADING, UpdateStatus.PENDING -> {
                        val total = progress.apkFileTotalBytes
                        val bytes = progress.apkBytesDownloaded
                        onProgress(if (total > 0 && bytes >= 0) "Baixando atualização: ${(bytes.toDouble() / total * 100).toInt().coerceIn(0, 100)}%"
                            else "Preparando download…")
                    }
                    else -> Unit
                }
            }.addOnCompleteListener {
                // The disabled SDK installer is expected to fail after downloading.
                // Recover only a complete matching APK; never treat Task success as installation.
                captureDownloaded()
            }
            try {
                withTimeout(10 * 60_000L) { completion.await() }
            } catch (_: TimeoutCancellationException) {
                // The SDK has no public cancel API. Its automatic installer stays disabled;
                // a later completed download is verified and restored for an explicit handoff.
                throw UpdateActionRequired("O download está demorando. O caixa foi liberado. Volte a Atualizações para conferir o download antes de tentar novamente.")
            }
        } finally { operation.unlock() }
    }

    private fun openInstaller() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                throw UpdateActionRequired("Abra as configurações do Android, permita instalar apps por este aplicativo e volte a tocar em Instalar agora.")
            }
            throw UpdateActionRequired("Ative Permitir desta fonte no Android, volte ao aplicativo e toque em Instalar agora. O download está guardado.")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.verified-updates", store.apkFile)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri("Atualização verificada", uri) })
        } catch (_: Exception) {
            throw UpdateActionRequired("O Android não abriu o instalador. A atualização continua baixada; confira a permissão de instalação e toque em Instalar agora para tentar novamente.")
        }
    }
}

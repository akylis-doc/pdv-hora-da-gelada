package br.com.bebidaspdv.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import br.com.bebidaspdv.BuildConfig
import br.com.bebidaspdv.core.logging.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** The Android adapter supplies context only. Persistence has no dependency on Firebase. */
class AndroidDiagnostics(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val environment = AtomicReference(LogEnvironment(
        appVersion = BuildConfig.VERSION_NAME, buildVersion = BuildConfig.VERSION_CODE.toString(),
        deviceInformation = "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}",
    ))
    val logger = LocalAppLogger(File(app.noBackupFilesDir, "diagnostics"), environment::get)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateConnectivity()
        override fun onLost(network: Network) = updateConnectivity()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateConnectivity()
    }

    fun start() {
        Thread.getDefaultUncaughtExceptionHandler()?.let { previous ->
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(logger::recordCrash, previous))
        }
        scope.launch {
            runCatching {
                // SharedPreferences are first opened on IO, never as part of a log call.
                val preferences = app.getSharedPreferences("diagnostic_device", Context.MODE_PRIVATE)
                val existingCashId = app.getSharedPreferences("cash_control", Context.MODE_PRIVATE).getString("device_id", null)
                val id = preferences.getString("id", null) ?: existingCashId ?: UUID.randomUUID().toString()
                preferences.edit().putString("id", id).apply()
                environment.updateAndGet { it.copy(deviceId = id) }
                updateConnectivity()
                connectivity?.registerDefaultNetworkCallback(callback)
                logger.startTrace(LogCategory.APP, "APP_STARTED", component = "BebidasApplication", function = "onCreate")
            }.onFailure { logger.error(LogCategory.SYSTEM, "DIAGNOSTIC_CONTEXT_FAILED", "Falha ao obter o contexto do aparelho", it) }
        }
    }

    fun setUser(uid: String?) { environment.updateAndGet { it.copy(userId = uid) } }

    private fun updateConnectivity() {
        val state = runCatching {
            val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) ConnectivityStatus.ONLINE
            else ConnectivityStatus.OFFLINE
        }.getOrDefault(ConnectivityStatus.UNKNOWN)
        val previous = environment.getAndUpdate { it.copy(connectivityStatus = state) }
        if (previous.connectivityStatus != state) logger.info(LogCategory.NETWORK, "CONNECTIVITY_CHANGED",
            "Conectividade do aparelho: ${state.name}")
    }
}

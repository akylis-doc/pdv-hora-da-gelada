package br.com.bebidaspdv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun BarcodeScannerDialog(onDetected: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by remember { mutableStateOf(false) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = it
        awaitingPermission = false
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        awaitingPermission = false
    }
    val requestPermission: () -> Unit = {
        if (!awaitingPermission) {
            awaitingPermission = true
            permissionError = null
            try {
                permission.launch(Manifest.permission.CAMERA)
            } catch (_: Exception) {
                awaitingPermission = false
                permissionError = "Não foi possível abrir as permissões. Você pode digitar o código manualmente."
            }
        }
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!permitted && !requested) {
            requested = true
            requestPermission()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Ler código de barras", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Fechar leitor") }
                }
                if (permitted) {
                    Text("Enquadre um único código, inteiro e nítido. Mantenha o celular parado por um instante até a leitura ser confirmada.")
                    CameraBarcodePreview(onDetected, Modifier.fillMaxWidth().weight(1f))
                } else {
                    Text("Permita o acesso à câmera para ler o código. Você também pode digitá-lo no campo.")
                    permissionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = requestPermission, enabled = !awaitingPermission) { Text("Permitir câmera") }
                    TextButton(enabled = !awaitingPermission, onClick = {
                        awaitingPermission = true
                        permissionError = null
                        try {
                            settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        } catch (_: Exception) {
                            awaitingPermission = false
                            permissionError = "Não foi possível abrir as configurações. Você pode digitar o código manualmente."
                        }
                    }) { Text("Abrir permissões do aplicativo") }
                    Spacer(Modifier.weight(1f))
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Voltar e digitar manualmente") }
            }
        }
    }
}

@Composable
private fun CameraBarcodePreview(onDetected: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latestDetected by rememberUpdatedState(onDetected)
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Aguardando um código nítido…") }
    val preview = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    DisposableEffect(context, owner, preview) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val active = AtomicBoolean(true)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(
            productBarcodeFormats.first(), *productBarcodeFormats.drop(1).toIntArray(),
        ).build())
        val controller = LifecycleCameraController(context)
        val analyzer = ProductBarcodeAnalyzer(scanner, mainExecutor, active, BarcodeReadPolicy(),
            onDetected = { latestDetected(it) },
            canDetect = { owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            onStatus = { status = it; error = null },
            onFailure = { error = "Não foi possível ler pela câmera. Volte e digite o código manualmente." },
        )
        try {
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
            preview.controller = controller
            controller.bindToLifecycle(owner)
            val initialization = controller.initializationFuture
            initialization.addListener({
                try {
                    initialization.get()
                } catch (_: Exception) {
                    if (active.get()) error = "A câmera não está disponível. Volte e digite o código manualmente."
                }
            }, mainExecutor)
        } catch (_: Exception) {
            error = "A câmera não está disponível. Volte e digite o código manualmente."
        }
        onDispose {
            active.set(false)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            preview.controller = null
            analysisExecutor.shutdown()
            analyzer.close()
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (error == null) Text(status, style = MaterialTheme.typography.bodyMedium)
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

private class ProductBarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val mainExecutor: Executor,
    private val active: AtomicBoolean,
    private val readPolicy: BarcodeReadPolicy,
    private val onDetected: (String) -> Unit,
    private val canDetect: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onFailure: () -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val delivered = AtomicBoolean(false)
    private val scannerLock = Any()
    private var processing = false
    private var disposed = false

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) = synchronized(scannerLock) {
        val mediaImage = image.image
        if (disposed || processing || !active.get() || delivered.get() || mediaImage == null) {
            image.close()
            return
        }
        processing = true
        val frameTimeMs = image.imageInfo.timestamp / 1_000_000L
        try {
            scanner.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
                .addOnSuccessListener(mainExecutor) { barcodes ->
                    if (active.get() && !delivered.get() && canDetect()) {
                        val value = readPolicy.accept(barcodes.map { BarcodeReading(it.rawValue, it.format) }, frameTimeMs)
                        onStatus(when {
                            barcodes.size > 1 -> "Há mais de um código. Deixe apenas o desejado visível."
                            readPolicy.consecutiveFrames > 0 -> "Confirmando leitura… mantenha o celular parado."
                            else -> "Aguardando um código nítido…"
                        })
                        if (value != null && delivered.compareAndSet(false, true)) onDetected(value)
                    } else {
                        readPolicy.reset()
                    }
                }
                .addOnFailureListener(mainExecutor) {
                    readPolicy.reset()
                    if (active.get()) onFailure()
                }
                .addOnCompleteListener {
                    image.close()
                    synchronized(scannerLock) {
                        processing = false
                        if (disposed) scanner.close()
                    }
                }
        } catch (_: Exception) {
            processing = false
            image.close()
            mainExecutor.execute { readPolicy.reset(); if (active.get()) onFailure() }
        }
        Unit
    }

    override fun close() = synchronized(scannerLock) {
        if (!disposed) {
            disposed = true
            // ML Kit must finish with the current ImageProxy before its scanner
            // is released, including when the operator closes the dialog early.
            if (!processing) scanner.close()
        }
    }
}

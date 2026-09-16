package br.com.bebidaspdv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import br.com.bebidaspdv.BuildConfig
import br.com.bebidaspdv.updates.AvailableUpdate

data class UpdateState(
    val checking: Boolean = false,
    val installing: Boolean = false,
    val downloaded: Boolean = false,
    val available: AvailableUpdate? = null,
    val message: String? = null,
    val error: Boolean = false,
)

@Composable
fun UpdatesScreen(model: PosViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshDownloadedUpdate()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val state by model.update.collectAsStateWithLifecycle()
    // Observe inputs used by the installation guard so button state follows synchronization.
    val cart by model.cart.collectAsStateWithLifecycle()
    val products by model.products.collectAsStateWithLifecycle()
    val operations by model.operations.collectAsStateWithLifecycle()
    val loading by model.productsLoading.collectAsStateWithLifecycle()
    val productError by model.productsError.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val pendingWrites by model.pendingWrites.collectAsStateWithLifecycle()
    val reason = remember(cart, products, operations, loading, productError, working, pendingWrites) { model.updateBlockReason() }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = state.installing) { /* Keep the POS paused until the current download or handoff finishes. */ }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Atualizações", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Versão instalada: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text(if (model.updatesEnabled) "Canal de testes • Firebase App Distribution" else "Atualizações de teste indisponíveis nesta versão.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model.updatesEnabled) {
            InfoNotice("Use a conta Google que recebeu o convite de testes. Ela é separada da conta usada no caixa; o acesso da loja continua conectado.")
            Text("Baixe quando for conveniente. Depois, toque em Instalar agora. Se fechar o aplicativo ou a tela do Android, o download continuará guardado para retomar.")
            Button(onClick = model::checkForUpdates, enabled = !state.checking && !state.installing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (state.checking) "Consultando…" else "Buscar atualização")
            }
            if (state.checking || state.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = model::changeTesterAccount, enabled = !state.checking && !state.installing) {
                Text("Trocar conta de testes")
            }
            state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            state.available?.let { release ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Versão ${release.version} (${release.code})", style = MaterialTheme.typography.titleLarge)
                        if (state.downloaded) Text("Baixada • pronta para instalar", color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold)
                        Text(release.notes.ifBlank { "Esta versão não possui notas de atualização." })
                        if (reason != null && !state.installing) InfoNotice(reason)
                        Button(onClick = { confirm = true }, enabled = reason == null && !state.checking && !state.installing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.downloaded) "Instalar agora" else "Baixar atualização") }
                    }
                }
            }
            Text("Atualize sobre a instalação existente. Não desinstale nem limpe os dados do aplicativo.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(if (state.downloaded) "Instalar atualização?" else "Baixar atualização?") },
        text = { Text(if (state.downloaded) "Confirme a instalação no Android. Se uma permissão for solicitada, ative Permitir desta fonte, volte e toque novamente em Instalar agora."
            else "O caixa ficará pausado durante o download. Quando terminar, a atualização ficará guardada e você poderá escolher Instalar agora.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.installUpdate() }) { Text(if (state.downloaded) "Instalar agora" else "Baixar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Depois") } },
    )
}

package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.bebidaspdv.BuildConfig
import br.com.bebidaspdv.R

@Composable
internal fun SettingsScreen(model: PosViewModel, onUpdates: () -> Unit, onSignOut: () -> Unit,
    onDiagnostics: (() -> Unit)? = null) {
    val working by model.working.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Configurações", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(36.dp))
                Text("Conta conectada", style = MaterialTheme.typography.labelLarge)
                Text(model.operatorName.ifBlank { "Operador da loja" }, style = MaterialTheme.typography.titleMedium)
                Text("As vendas e os recebimentos registram quem estava conectado neste aparelho.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmSignOut = true }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Entrar em outra conta") }
            }
        }
        Text(stringResource(R.string.app_version_label, stringResource(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), fontWeight = FontWeight.SemiBold)
        Button(onClick = onUpdates, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("Atualizações do aplicativo")
        }
        onDiagnostics?.let { open ->
            OutlinedButton(onClick = open, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(8.dp)); Text("Diagnóstico")
            }
        }
        InfoNotice("Seu acesso continua salvo ao fechar o aplicativo. A troca de conta exige carrinho vazio e confirmação de que as gravações foram sincronizadas.")
        Text("O tema claro ou escuro acompanha a configuração do celular.", style = MaterialTheme.typography.bodyMedium)
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Trocar conta do caixa?") },
        text = { Text("Vamos conferir a sincronização antes de sair. Depois, entre com outra conta autorizada da loja. A conta Google de testes é gerenciada separadamente em Atualizações.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; model.switchAccount(onSignOut) }, enabled = !working) { Text("Trocar conta") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancelar") } })
}

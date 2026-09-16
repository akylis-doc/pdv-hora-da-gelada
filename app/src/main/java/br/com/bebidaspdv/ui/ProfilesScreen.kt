package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.com.bebidaspdv.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal class ProfilesViewModel(private val repository: ProfileRepository) : ViewModel() {
    val session = repository.session
    val pendingProfiles = repository.pendingProfiles
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val profiles = repository.observeProfiles().catch {
        _error.value = "Não foi possível carregar os operadores. Confira a conexão e o acesso de administrador."
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Feed(emptyList()))
    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun create(name: String, email: String, password: String, permissions: OperatorPermissions, onAccepted: () -> Unit) = execute {
        val result = repository.createOperator(name, email, password, permissions)
        _message.value = when (result) {
            is ProfileCreationResult.Created -> "Operador cadastrado. Ele já pode entrar com o e-mail e a senha informados."
            is ProfileCreationResult.Pending -> "A conta foi criada, mas o perfil ainda precisa ser confirmado. Use Concluir cadastro abaixo."
        }
        onAccepted()
    }

    fun save(profile: OperatorProfile, onAccepted: () -> Unit) = execute {
        repository.saveProfile(profile)
        _message.value = "Perfil atualizado. Os caixas conectados receberão a alteração."
        onAccepted()
    }

    fun retry(uid: String) = execute {
        repository.retryPendingProfile(uid)
        _message.value = "Cadastro concluído. O operador já pode entrar no caixa."
    }

    fun clearError() { _error.value = null }
    private fun execute(action: suspend () -> Unit) {
        if (_working.value) return
        _working.value = true
        _error.value = null
        _message.value = null
        viewModelScope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                _error.value = if (failure is IllegalArgumentException || failure is IllegalStateException) failure.message
                    ?: "Não foi possível salvar. Confira a conexão e tente novamente."
                else "Não foi possível salvar. Confira a conexão e o acesso de administrador; tente novamente."
            } finally { _working.value = false }
        }
    }

    class Factory(private val repository: ProfileRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfilesViewModel(repository) as T
    }
}

@Composable
fun ProfilesScreen(repository: ProfileRepository) {
    val model: ProfilesViewModel = viewModel(factory = remember(repository) { ProfilesViewModel.Factory(repository) })
    val session by model.session.collectAsStateWithLifecycle()
    val current = session as? ProfileSession.Authorized
    if (current?.capabilities?.canManageProfiles != true) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Somente administradores podem gerenciar operadores.") }
        return
    }
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val pending by model.pendingProfiles.collectAsStateWithLifecycle()
    val working by model.working.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OperatorProfile?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Operadores", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Crie contas e escolha o que cada pessoa pode fazer. Cadastros e alterações exigem internet.")
        }
        item {
            Button(onClick = { model.clearError(); creating = true }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Cadastrar operador")
            }
        }
        if (working) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (profiles.fromCache) item { InfoNotice("Lista salva neste aparelho. Conecte à internet para confirmar alterações.", Icons.Default.CloudOff) }
        if (error != null && !creating && editing == null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
        if (message != null) item { InfoNotice(message.orEmpty()) }
        items(pending, key = { "pending_${it.profile.uid}" }) { item ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cadastro incompleto", fontWeight = FontWeight.Bold)
                    Text("${item.profile.name} • ${item.profile.email}")
                    Text("A conta já existe. Confirme o perfil para liberar seu acesso.", style = MaterialTheme.typography.bodySmall)
                    Text("UID: ${item.profile.uid}", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { model.retry(item.profile.uid) }, enabled = !working) { Text("Concluir cadastro") }
                }
            }
        }
        items(profiles.value, key = { it.uid }) { profile ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Person, null)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(profile.name, fontWeight = FontWeight.Bold)
                        Text(profile.email, style = MaterialTheme.typography.bodySmall)
                        Text("${if (profile.role == OperatorRole.ADMIN) "Administrador" else "Operador"} • ${if (profile.active) "Ativo" else "Desativado"}${if (profile.uid == current.uid) " • Sua conta" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (profile.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { model.clearError(); editing = profile }, enabled = !working) { Text("Editar") }
                }
            }
        }
        item { InfoNotice("Desativar bloqueia o acesso e preserva vendas, recebimentos e o UID do operador. Um aparelho offline recebe a mudança ao reconectar.") }
    }

    if (creating) ProfileEditor(profile = null, ownProfile = false, working = working, error = error,
        onDismiss = { if (!working) creating = false },
        onCreate = { name, email, password, permissions -> model.create(name, email, password, permissions) { creating = false } },
        onSave = {})
    editing?.let { profile ->
        ProfileEditor(profile, ownProfile = profile.uid == current.uid, working = working, error = error,
            onDismiss = { if (!working) editing = null }, onCreate = { _, _, _, _ -> },
            onSave = { updated -> model.save(updated) { editing = null } })
    }
}

@Composable
private fun ProfileEditor(
    profile: OperatorProfile?, ownProfile: Boolean, working: Boolean, error: String?, onDismiss: () -> Unit,
    onCreate: (String, String, String, OperatorPermissions) -> Unit, onSave: (OperatorProfile) -> Unit,
) {
    var name by remember(profile?.uid) { mutableStateOf(profile?.name.orEmpty()) }
    var email by remember(profile?.uid) { mutableStateOf(profile?.email.orEmpty()) }
    // Credentials only live in this form until submission/dismissal; never SavedState or Firestore.
    var password by remember { mutableStateOf("") }
    var role by remember(profile?.uid) { mutableStateOf(profile?.role ?: OperatorRole.OPERATOR) }
    var active by remember(profile?.uid) { mutableStateOf(profile?.active ?: true) }
    var permissions by remember(profile?.uid) { mutableStateOf(profile?.permissions ?: OperatorPermissions()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (profile == null) "Cadastrar operador" else "Editar operador") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(120) }, label = { Text("Nome") }, enabled = !working, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it.take(254) }, label = { Text("E-mail") }, enabled = profile == null && !working,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                if (profile == null) {
                    OutlinedTextField(password, { password = it }, label = { Text("Senha inicial") }, enabled = !working, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Text("Mínimo de 6 caracteres. Entregue a senha ao operador por um meio privado. Se a conta já existe, sua senha atual permite concluir o perfil sem criar outra conta.", style = MaterialTheme.typography.bodySmall)
                } else {
                    PermissionSwitch("Conta ativa", active, !working && !ownProfile) { active = it }
                    PermissionSwitch("Administrador", role == OperatorRole.ADMIN, !working && !ownProfile) { role = if (it) OperatorRole.ADMIN else OperatorRole.OPERATOR }
                    if (ownProfile) Text("Para proteger o acesso da loja, você não pode desativar nem rebaixar a própria conta.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Permissões", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (role == OperatorRole.ADMIN) Text("Administradores têm todas as permissões, incluindo o gerenciamento de contas.", style = MaterialTheme.typography.bodySmall)
                val enabled = !working && role != OperatorRole.ADMIN
                PermissionSwitch("Editar preços", role == OperatorRole.ADMIN || permissions.editPrices, enabled) { permissions = permissions.copy(editPrices = it) }
                PermissionSwitch("Excluir produtos", role == OperatorRole.ADMIN || permissions.deleteProducts, enabled) { permissions = permissions.copy(deleteProducts = it) }
                PermissionSwitch("Ver relatórios", role == OperatorRole.ADMIN || permissions.viewReports, enabled) { permissions = permissions.copy(viewReports = it) }
                PermissionSwitch("Estornar vendas", role == OperatorRole.ADMIN || permissions.reverseSales, enabled) { permissions = permissions.copy(reverseSales = it) }
                PermissionSwitch("Exportar dados", role == OperatorRole.ADMIN || permissions.exportData, enabled) { permissions = permissions.copy(exportData = it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && name.isNotBlank() && (profile != null || (email.contains('@') && password.length >= 6)), onClick = {
                if (profile == null) onCreate(name, email, password, permissions)
                else onSave(profile.copy(name = name.trim(), active = active, role = role, permissions = permissions))
            }) { Text(if (working) "Salvando…" else "Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancelar") } })
}

@Composable
private fun PermissionSwitch(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

package br.com.bebidaspdv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.bebidaspdv.core.CashControlRepository
import br.com.bebidaspdv.core.PosRepository
import br.com.bebidaspdv.core.ProfileRepository
import br.com.bebidaspdv.core.ProfileSession
import br.com.bebidaspdv.core.SyncState
import br.com.bebidaspdv.core.confirmAccountSwitch
import br.com.bebidaspdv.core.logging.*
import br.com.bebidaspdv.ui.BebidasTheme
import br.com.bebidaspdv.ui.PosApp
import br.com.bebidaspdv.updates.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BebidasTheme {
                if (BuildConfig.FIREBASE_CONFIGURED) {
                    val profiles = remember { GlobalContext.get().get<ProfileRepository>() }
                    val repository = remember { GlobalContext.get().get<PosRepository>() }
                    val cashControl = remember { GlobalContext.get().get<CashControlRepository>() }
                    val signOutAndClear: () -> Unit = { profiles.signOut(); viewModelStore.clear() }
                    AuthGate(profiles, logger = (application as BebidasApplication).diagnostics.logger, onBlockedSignOut = {
                        withTimeout(15_000) {
                            confirmAccountSwitch(
                                hasUnresolvedOperations = {
                                    repository.pendingDebtPayments.value.isNotEmpty() ||
                                        cashControl.pendingOperations.value.isNotEmpty() ||
                                        repository.operations.value.any { it.state == SyncState.PENDING }
                                },
                                awaitPendingWrites = repository::waitForPendingWrites,
                                signOut = signOutAndClear,
                            )
                        }
                    }) { session ->
                        key(session.uid) {
                            PosApp(repository,
                                remember { GlobalContext.get().get<AppUpdates>() },
                                operatorId = session.uid,
                                operatorName = session.name,
                                capabilities = session.capabilities,
                                profileRepository = profiles,
                                cashControlRepository = cashControl,
                                diagnostics = (application as BebidasApplication).diagnostics.logger,
                                onSignOut = signOutAndClear)
                        }
                    }
                } else SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(52.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Seu caixa, pronto para conectar.", style = MaterialTheme.typography.headlineSmall)
            Text("Esta instalação ainda precisa da configuração da loja. Entregue o projeto ao responsável pela implantação e siga o README.")
            Text("1. Cadastre o app br.com.bebidaspdv no Firebase.\n\n2. Adicione google-services.json à pasta app.\n\n3. Ative o acesso por e-mail e senha e autorize os operadores.\n\n4. Publique as regras do banco e gere uma nova instalação.")
            Text("Depois do primeiro acesso conectado, o caixa usa os dados armazenados neste celular para trabalhar offline.", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AuthGate(
    profiles: ProfileRepository,
    logger: AppLogger,
    onBlockedSignOut: suspend () -> Unit,
    content: @Composable (ProfileSession.Authorized) -> Unit,
) {
    // The repository restores only a previously verified account, including cached revocations.
    val session by profiles.session.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    when (val current = session) {
        is ProfileSession.Authorized -> { content(current); return }
        ProfileSession.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Conferindo seu acesso…")
                }
            }
            return
        }
        is ProfileSession.Blocked -> {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(28.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(current.message)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = !loading, onClick = {
                        loading = true; error = null
                        scope.launch {
                            try { profiles.refreshSession() }
                            catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = failure.message ?: "Confira sua conexão e tente novamente."
                            } finally { loading = false }
                        }
                    }) { Text(if (loading) "Conferindo…" else "Conferir acesso novamente") }
                    TextButton(onClick = {
                        loading = true; error = null
                        scope.launch {
                            try { onBlockedSignOut() }
                            catch (failure: Exception) {
                                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                                error = if (failure is TimeoutCancellationException)
                                    "Não foi possível confirmar a sincronização. Mantenha esta conta conectada e tente novamente com internet."
                                else failure.message ?: "Não foi possível confirmar a sincronização. Tente novamente com internet."
                            } finally { loading = false }
                        }
                    }, enabled = !loading) { Text("Entrar com outra conta") }
                }
            }
            return
        }
        ProfileSession.SignedOut -> Unit
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(28.dp)
            .verticalScroll(rememberScrollState()).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(48.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Vamos abrir o caixa.", style = MaterialTheme.typography.headlineSmall)
            Text("Acesse com a conta autorizada da loja. O primeiro acesso precisa de internet.")
            OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), enabled = !loading)
            OutlinedTextField(password, { password = it }, label = { Text("Senha") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), enabled = !loading)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                loading = true; error = null
                val trace = logger.startTrace(LogCategory.AUTH, "AUTH_LOGIN_CLICK", screen = "AuthGate",
                    component = "AuthGate", function = "signIn")
                scope.launch(TraceElement(trace)) {
                    try {
                        profiles.signIn(email.trim(), password)
                        password = ""
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message ?: "Não foi possível entrar. Confira sua conexão, e-mail e senha."
                    } finally { loading = false }
                }
            }, enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Text("Entrar no caixa")
            }
        }
    }
}

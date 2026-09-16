package br.com.bebidaspdv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import br.com.bebidaspdv.R
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import br.com.bebidaspdv.ui.diagnostics.DiagnosticsOverviewScreen
import br.com.bebidaspdv.ui.diagnostics.DiagnosticsLogsScreen
import br.com.bebidaspdv.updates.AppUpdates
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal fun currency(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
    .format(BigDecimal.valueOf(cents, 2))
internal fun decimalInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("caixa", "Caixa", Icons.Default.PointOfSale),
    Destination("estoque", "Estoque", Icons.Default.Inventory2),
    Destination("relatorios", "Relatórios", Icons.Default.BarChart),
    Destination("fiado", "Fiado", Icons.Default.AccountBalanceWallet),
    Destination("configuracoes", "Ajustes", Icons.Default.Settings),
    Destination("controle", "Controle de caixa", Icons.Default.AccountBalance),
    Destination("perfis", "Perfis da equipe", Icons.Default.ManageAccounts),
    Destination("exportar", "Exportar dados", Icons.Default.FileDownload),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosApp(repository: PosRepository, updates: AppUpdates, operatorId: String, operatorName: String, onSignOut: () -> Unit,
    capabilities: OperatorCapabilities, profileRepository: ProfileRepository, cashControlRepository: CashControlRepository,
    diagnostics: LocalAppLogger? = null) {
    val factory = remember(repository, updates, operatorId, capabilities, diagnostics) { PosViewModel.Factory(repository, updates, operatorId, operatorName, cashControlRepository, capabilities, logger = diagnostics ?: NoOpLogger) }
    val model: PosViewModel = viewModel(factory = factory)
    SideEffect { model.updateProfile(capabilities, operatorName) }
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val activeRoute = entry?.destination?.route ?: "caixa"
    val snackbar = remember { SnackbarHostState() }
    val products by model.products.collectAsStateWithLifecycle()
    val operations by model.operations.collectAsStateWithLifecycle()
    val pendingWrites by model.pendingWrites.collectAsStateWithLifecycle()
    val update by model.update.collectAsStateWithLifecycle()
    var showSync by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var reversalSaleId by remember { mutableStateOf<String?>(null) }
    fun go(route: String) {
        navigation.navigate(route) { popUpTo(navigation.graph.findStartDestination().id); launchSingleTop = true }
        scope.launch { drawer.close() }
    }
    LaunchedEffect(activeRoute) { model.setActiveSection(activeRoute) }
    DisposableEffect(model) { onDispose { model.setActiveSection("") } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activeRoute, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> model.setActiveSection(activeRoute)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> model.setActiveSection("")
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model) { model.events.collectLatest {
        snackbar.currentSnackbarData?.dismiss()
        withTimeoutOrNull(2_000) { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Short) }
    } }

    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !update.installing, drawerContent = {
        ModalDrawerSheet {
            Text(stringResource(R.string.app_name), Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
            destinations.drop(2).filter { destination -> when (destination.route) {
                "relatorios" -> capabilities.canViewReports
                "perfis" -> capabilities.canManageProfiles
                "exportar" -> capabilities.canExportData
                else -> true
            } }.forEach { destination ->
                NavigationDrawerItem(label = { Text(destination.label) }, selected = activeRoute == destination.route,
                    onClick = { if (!update.installing) go(destination.route) },
                    icon = { Icon(destination.icon, null) }, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }, enabled = !update.installing) { Icon(Icons.Default.Menu, "Abrir menu") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.LocalBar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.app_name), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    val rejected = operations.count { it.state == SyncState.REJECTED }
                    val pending = operations.count { it.state == SyncState.PENDING }
                    TextButton(onClick = { showSync = true }, enabled = !update.installing) {
                        Icon(
                            when { rejected > 0 -> Icons.Default.ErrorOutline; pendingWrites -> Icons.Default.CloudUpload; products.fromCache -> Icons.Default.CloudOff; else -> Icons.Default.CloudDone },
                            contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = if (rejected > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(when { rejected > 0 -> "$rejected recusada(s)"; pending > 0 -> "$pending pendente(s)"; pendingWrites -> "Pendente"; products.fromCache -> "Dados locais"; else -> "Sincronização" })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                destinations.take(2).forEach { destination ->
                    NavigationBarItem(
                        enabled = !update.installing,
                        selected = activeRoute == destination.route || (destination.route == "configuracoes" && activeRoute == "atualizacoes"),
                        onClick = { go(destination.route) },
                        icon = {
                            val alerts = products.value.count { it.stock <= it.lowStockThreshold }
                            if (destination.route == "estoque" && alerts > 0) BadgedBox(badge = { Badge { Text(if (alerts > 99) "99+" else alerts.toString()) } }) {
                                Icon(destination.icon, contentDescription = "$alerts produtos com alerta de estoque")
                            } else Icon(destination.icon, contentDescription = null)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(navigation, startDestination = "caixa", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("caixa") { CashierScreen(model, onGoToStock = { go("estoque") }, operatorId = operatorId, canOverrideCredit = capabilities.isAdmin) }
            composable("estoque") { StockScreen(model, capabilities.canEditPrices, capabilities.canDeleteProducts) }
            composable("relatorios") { if (capabilities.canViewReports) ReportsScreen(model, onReverse = { reversalSaleId = it; go("controle") }) }
            composable("fiado") { CreditScreen(model) }
            composable("configuracoes") { SettingsScreen(model, onUpdates = { navigation.navigate("atualizacoes") { launchSingleTop = true } }, onSignOut = onSignOut,
                onDiagnostics = if (capabilities.isAdmin && diagnostics != null) ({ navigation.navigate("diagnostico") { launchSingleTop = true } }) else null) }
            composable("diagnostico") {
                if (capabilities.isAdmin && diagnostics != null) DiagnosticsOverviewScreen(diagnostics, capabilities.isAdmin,
                    onLogs = { navigation.navigate("logs") { launchSingleTop = true } }, onBack = { navigation.popBackStack() })
            }
            composable("logs") {
                if (capabilities.isAdmin && diagnostics != null) DiagnosticsLogsScreen(diagnostics, capabilities.isAdmin,
                    onBack = { navigation.popBackStack() })
            }
            composable("atualizacoes") { UpdatesScreen(model) }
            composable("controle") { CashControlScreen(cashControlRepository, emptyList(), capabilities.canReverseSales, selectedSaleId = reversalSaleId, logger = diagnostics ?: NoOpLogger) }
            composable("perfis") { if (capabilities.canManageProfiles) ProfilesScreen(profileRepository) }
            composable("exportar") { if (capabilities.canExportData) ExportScreen(repository, capabilities) }
        }
    }
    }

    if (showSync) {
        ModalBottomSheet(onDismissRequest = { showSync = false }) {
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.88f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("Sincronização", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                item {
                    Text(
                        "As operações são gravadas neste aparelho e enviadas automaticamente quando há conexão. Estoque e preços podem mudar quando o outro caixa sincronizar.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (products.fromCache) item { InfoNotice("Produtos exibidos a partir do cache local. A lista pode estar incompleta.", Icons.Default.CloudOff) }
                if (operations.isEmpty()) item { Text("Nenhuma operação acompanhada neste aparelho.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(operations.sortedBy { when (it.state) { SyncState.REJECTED -> 0; SyncState.PENDING -> 1; SyncState.CONFIRMED -> 2 } }, key = OperationStatus::id) { operation ->
                    val (label, icon) = when (operation.state) {
                        SyncState.PENDING -> "Pendente" to Icons.Default.CloudUpload
                        SyncState.CONFIRMED -> "Confirmada" to Icons.Default.CloudDone
                        SyncState.REJECTED -> "Recusada" to Icons.Default.ErrorOutline
                    }
                    Surface(shape = RoundedCornerShape(16.dp), color = if (operation.state == SyncState.REJECTED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(icon, contentDescription = null)
                            Column {
                                Text(label, fontWeight = FontWeight.Bold)
                                Text("Operação ${operation.id.take(8)}", style = MaterialTheme.typography.labelMedium)
                                operation.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                item { Text("Sem conexão, os dois caixas podem vender o mesmo saldo local. Confira divergências após a sincronização.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
internal fun InfoNotice(text: String, icon: ImageVector = Icons.Default.Info) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun EmptyState(title: String, description: String, icon: ImageVector = Icons.AutoMirrored.Filled.ReceiptLong, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(22.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

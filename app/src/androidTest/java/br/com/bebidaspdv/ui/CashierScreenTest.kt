package br.com.bebidaspdv.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.*
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.time.YearMonth
import java.time.ZoneId
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.rules.TestName
import androidx.test.filters.LargeTest
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uses the real CashierScreen and the application's exact singleTop navigation options. */
class CashierScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val product = Product("probe-product", "Produto teste", "123", 500, 200, 500)
    private val logger = ProbeLogger()
    private lateinit var model: PosViewModel
    private lateinit var repository: ProbeRepository
    private var lifecycle = "unknown"
    private lateinit var navigation: NavHostController
    private fun open(navigation: Boolean, catalog: List<Product> = listOf(product)) {
        val offline = Settings.Global.getInt(compose.activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        repository = ProbeRepository(catalog, offline)
        model = PosViewModel(repository, logger = logger)
        compose.setContent {
            MaterialTheme {
                if (navigation) {
                    val nav = rememberNavController().also { this.navigation = it }
                    NavHost(nav, startDestination = "caixa") {
                        composable("caixa") {
                            val owner = LocalLifecycleOwner.current
                            DisposableEffect(owner) {
                                lifecycle = owner.lifecycle.currentState.name
                                val observer = LifecycleEventObserver { _, _ -> lifecycle = owner.lifecycle.currentState.name }
                                owner.lifecycle.addObserver(observer)
                                onDispose { owner.lifecycle.removeObserver(observer) }
                            }
                            CashierScreen(model, onGoToStock = {})
                        }
                        composable("estoque") { Text("Estoque do teste") }
                    }
                } else CashierScreen(model, onGoToStock = {})
            }
        }
        compose.waitForIdle()
    }
    private fun quantity() = compose.onAllNodes(hasSetTextAction())[1]
    private fun checkQuantity(expected: String) {
        try {
            quantity().assertTextContains(expected)
            val draw = logger.events.lastOrNull { it.event == "CART_RENDERED" && it.metadata["ui_quantity_after"] == expected }
            assertNotNull("A quantidade precisa ter confirmação de desenho, além de atualizar o modelo", draw)
            // ComposeTestRule drives a synthetic clock until animations become idle.
            // Assert visible content here; CashierNaturalFrameTest enforces the 500 ms budget
            // using the Android frame clock and real input, without Espresso clock advancement.
            assertTrue("O desenho deve ter duração registrada", requireNotNull(draw?.durationMs) >= 0)
        }
        catch (error: AssertionError) {
            throw AssertionError("cart=${model.cart.value}; lifecycle=$lifecycle; events=${logger.events}\n${error.message}", error)
        }
    }
    @Test fun standaloneButton() {
        open(false)
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("1")
    }
    @Test fun navigationButton() {
        open(true)
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("1")
    }
    @Test fun navigationInput() {
        open(true)
        quantity().performTextReplacement("10")
        checkQuantity("10")
    }
    private fun reselectCashier() {
        compose.runOnIdle {
            navigation.navigate("caixa") {
                popUpTo(navigation.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
        compose.waitForIdle()
    }
    @Test fun navigationSingleTopButton() {
        open(true)
        reselectCashier()
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("1")
    }
    @Test fun navigationSingleTopInput() {
        open(true)
        reselectCashier()
        quantity().performTextReplacement("10")
        checkQuantity("10")
    }
    @Test fun repeatedClicksUseLatestQuantityEvenBeforeARecomposition() {
        open(true)
        reselectCashier()
        compose.onNodeWithContentDescription("Adicionar Produto teste").performSemanticsAction(SemanticsActions.OnClick) { click ->
            repeat(20) { click() }
        }
        checkQuantity("20")
        compose.onNodeWithContentDescription("Remover Produto teste").performSemanticsAction(SemanticsActions.OnClick) { click ->
            repeat(5) { click() }
        }
        checkQuantity("15")
        assertEquals(1, model.cart.value.size)
        assertEquals(15, model.cart.value.single().quantity)
        assertEquals(20, logger.events.filter { it.event == "ADD_TO_CART_BUTTON_CLICK" }.map { it.traceId }.distinct().size)
    }
    @Test fun typedQuantityReductionRemovalTotalAndStock() {
        open(true)
        reselectCashier()
        quantity().performTextReplacement("10")
        checkQuantity("10")
        compose.onNodeWithText(currency(5000)).assertIsDisplayed()
        quantity().performTextReplacement("3")
        checkQuantity("3")
        compose.onNodeWithText(currency(1500)).assertIsDisplayed()
        quantity().performTextReplacement("501")
        checkQuantity("3")
        assertEquals(500, repository.feed.value.value.single().stock)
        quantity().performTextReplacement("0")
        assertEquals("", quantity().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Revisar").assertIsNotEnabled()
        assertTrue(model.cart.value.isEmpty())
        assertEquals(0, repository.writes)
    }
    @Test fun navigationAwayReturnAndReopenPreserveVisibleCart() {
        open(true)
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        compose.runOnIdle { navigation.navigate("estoque") }
        compose.onNodeWithText("Estoque do teste").assertIsDisplayed()
        reselectCashier()
        checkQuantity("1")
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("2")
        compose.runOnIdle { navigation.popBackStack("caixa", inclusive = true); navigation.navigate("caixa") }
        checkQuantity("2")
        quantity().performTextReplacement("7")
        checkQuantity("7")
    }
    @Test fun largeCatalogEightDistinctItemsAndVisibleReview() {
        val catalog = (0 until 5000).map { product.copy(id = "product-$it", name = "Produto %04d".format(it), barcode = "code-$it") }
        open(true, catalog)
        reselectCashier()
        repeat(8) { index ->
            val p = catalog[index]
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(p.name)
            compose.onNodeWithContentDescription("Adicionar ${p.name}").performClick()
            checkQuantity("1")
        }
        assertEquals(8, model.cart.value.size)
        assertEquals(8, model.cart.value.map { it.product.id }.distinct().size)
        compose.onNodeWithText(currency(4000)).assertIsDisplayed()
        compose.onNodeWithText("Revisar").performClick()
        compose.onNodeWithText("Revisar venda").assertIsDisplayed()
        compose.onNodeWithText(catalog[0].name).assertIsDisplayed()
        assertEquals(0, repository.writes)
    }
    @Test fun syncSnapshotsInParallelDoNotDelayCartOrDuplicateItems() {
        open(true)
        reselectCashier()
        val job = model.viewModelScope.launch {
            repeat(30) { index ->
                repository.feed.value = repository.feed.value.copy(pendingWrites = index % 2 == 0)
                repository.operations.value = listOf(OperationStatus("existing-sale", if (index % 2 == 0) SyncState.PENDING else SyncState.CONFIRMED))
                delay(20) // Test-only simulated SDK callbacks; no production delay.
            }
        }
        repeat(5) { compose.onNodeWithContentDescription("Adicionar Produto teste").performClick(); checkQuantity((it + 1).toString()) }
        compose.runOnIdle { job.cancel() }
        assertEquals(5, model.cart.value.single().quantity)
        assertEquals(500, model.cart.value.single().product.stock)
        assertEquals(0, repository.writes)
    }
    @Test @LargeTest fun screenStillRespondsAfterTwoMinutesOpen() {
        open(true)
        reselectCashier()
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("1")
        SystemClock.sleep(120_000) // Elapsed-time regression scenario on the instrumentation thread, not Main.
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("2")
        quantity().performTextReplacement("12")
        checkQuantity("12")
    }
    @Test @LargeTest fun wideCartPanelUsesTheSameCollectedQuantity() {
        org.junit.Assume.assumeTrue(compose.activity.resources.configuration.screenWidthDp >= 840)
        open(true)
        reselectCashier()
        compose.onNodeWithContentDescription("Adicionar Produto teste").performClick()
        checkQuantity("1")
        compose.onNodeWithText("Revisar venda").assertIsDisplayed()
        compose.onNodeWithText("1 ${productUnitShort(product.unitLabel)} × ${currency(product.saleCents)}").assertIsDisplayed()
        quantity().performTextReplacement("10")
        checkQuantity("10")
        compose.onNodeWithText("10 ${productUnitShort(product.unitLabel)} × ${currency(product.saleCents)}").assertIsDisplayed()
    }
    @After fun close() {
        if (::model.isInitialized) {
            compose.waitForIdle()
            val offline = Settings.Global.getInt(compose.activity.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            val output = File(compose.activity.getExternalFilesDir(null), "cart-ui-tests").apply { mkdirs() }
            val events = JSONArray()
            logger.events.forEach { event -> events.put(JSONObject().put("event", event.event).put("trace_id", event.traceId)
                .put("duration_ms", event.durationMs).put("metadata", JSONObject(event.metadata))) }
            File(output, "${testName.methodName}-${if (offline) "offline" else "online"}.json").writeText(events.toString(2))
            compose.runOnIdle { model.viewModelScope.cancel() }
        }
    }
    private class ProbeRepository(catalog: List<Product>, offline: Boolean) : PosRepository {
        val feed = MutableStateFlow(Feed(catalog, fromCache = offline))
        var writes = 0
        override val operations = MutableStateFlow(emptyList<OperationStatus>())
        override fun observeProducts() = feed
        override fun observeMovements(month: YearMonth, zone: ZoneId) = flowOf(Feed(emptyList<Movement>()))
        override suspend fun submit(movement: Movement) { writes++; error("No backend in UI test") }
        override suspend fun saveProduct(product: Product) { writes++; error("No backend in UI test") }
    }
    private class ProbeLogger : AppLogger by NoOpLogger {
        val events = java.util.concurrent.CopyOnWriteArrayList<LogEntry>()
        override fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?, status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String,String>, syncStatus: LogSyncStatus) {
            events += LogEntry(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), level, category, event, message,
                traceId = trace?.traceId, durationMs = durationMs, metadata = metadata)
        }
        override fun startStep(trace: TraceContext, category: LogCategory, event: String, component: String, function: String, slowAfterMs: Long, stuckAfterMs: Long): LogStep {
            val started = System.nanoTime()
            log(LogLevel.INFO, category, "${event}_STARTED", "Measured test step", trace)
            return object : LogStep {
                override fun finish(message: String, metadata: Map<String, String>, syncStatus: LogSyncStatus) {
                    log(LogLevel.INFO, category, "${event}_COMPLETED", message, trace,
                        durationMs = (System.nanoTime() - started) / 1_000_000, metadata = metadata)
                }
                override fun fail(error: Throwable, message: String, errorCode: String?) = Unit
                override fun cancel(message: String) = Unit
            }
        }
    }
}

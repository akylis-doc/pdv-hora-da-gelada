package br.com.bebidaspdv.ui

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.core.logging.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android frames: no Compose test clock or Espresso auto-advancing animations. */
class CashierNaturalFrameTest {
    @get:Rule val performance = ErrorCollector()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val product = Product("natural-frame-product", "Produto teste", "123", 500, 200, 500)
    private val logger = DrawLogger()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var model: PosViewModel
    private lateinit var navigation: NavHostController
    private var widthDp = 0

    @Test fun addAndKeyboardUpdateVisibleCartAfterSingleTopWithNaturalFrames() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            widthDp = activity.resources.configuration.screenWidthDp
            model = PosViewModel(CatalogRepository(product), logger = logger)
            activity.setContent {
                MaterialTheme {
                    val nav = rememberNavController().also { navigation = it }
                    NavHost(nav, startDestination = "caixa") {
                        composable("caixa") { CashierScreen(model, onGoToStock = {}) }
                    }
                }
            }
        }
        awaitNode { it.contentDescription?.toString() == "Adicionar Produto teste" }
        scenario.onActivity {
            navigation.navigate("caixa") {
                popUpTo(navigation.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
        automation.waitForIdle(100, 5_000)
        logger.nextDraw = CountDownLatch(1)
        tap(awaitNode { it.contentDescription?.toString() == "Adicionar Produto teste" })
        assertDraw("1")
        val quantity = awaitNode { it.isEditable && it.text?.toString() == "1" }
        if (widthDp >= 840) awaitNode { it.text?.toString() == "1 ${productUnitShort(product.unitLabel)} × ${currency(product.saleCents)}" }

        logger.nextDraw = CountDownLatch(1)
        assertTrue(quantity.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "10")
        }))
        assertDraw("10")
        awaitNode { it.isEditable && it.text?.toString() == "10" }
        if (widthDp >= 840) awaitNode { it.text?.toString() == "10 ${productUnitShort(product.unitLabel)} × ${currency(product.saleCents)}" }
        scenario.onActivity {
            assertEquals(1, model.cart.value.size)
            assertEquals(10, model.cart.value.single().quantity)
            assertEquals(5000L, model.cart.value.sumOf { it.saleUnitCents * it.quantity })
        }
    }

    private fun assertDraw(expected: String) {
        assertTrue("A tela não confirmou desenho em 5 segundos", logger.nextDraw.await(5, TimeUnit.SECONDS))
        val draw = logger.events.last { it.event == "CART_RENDERED" }
        assertEquals(expected, draw.metadata["ui_quantity_after"])
        performance.checkSucceeds {
            assertTrue("Desenho com quadros naturais excedeu 500 ms: ${draw.durationMs}; trace=${draw.traceId}",
                requireNotNull(draw.durationMs) < 500)
            Unit
        }
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val downTime = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node) && node.isVisibleToUser) return node
                repeat(node.childCount) { index -> find(node.getChild(index))?.let { return it } }
                return null
            }
            find(automation.rootInActiveWindow)?.let { return it }
            Thread.sleep(50) // Accessibility readiness in the test only; draw timing is independent.
        }
        throw AssertionError("Conteúdo esperado não apareceu na árvore de acessibilidade")
    }

    @After fun close() {
        if (::scenario.isInitialized) {
            scenario.onActivity { activity ->
                val events = JSONArray()
                logger.events.forEach { event -> events.put(JSONObject().put("event", event.event)
                    .put("trace_id", event.traceId).put("duration_ms", event.durationMs)
                    .put("metadata", JSONObject(event.metadata))) }
                File(activity.getExternalFilesDir(null), "natural-cart-${widthDp}dp.json").writeText(events.toString(2))
                if (::model.isInitialized) model.viewModelScope.cancel()
            }
            scenario.close()
        }
    }

    private class CatalogRepository(private val product: Product) : PosRepository {
        override val operations = MutableStateFlow(emptyList<OperationStatus>())
        override fun observeProducts() = flowOf(Feed(listOf(product), fromCache = true))
        override fun observeMovements(month: YearMonth, zone: ZoneId) = flowOf(Feed(emptyList<Movement>()))
        override suspend fun submit(movement: Movement) = error("Cart editing cannot write to the backend")
        override suspend fun saveProduct(product: Product) = error("Cart editing cannot write to the backend")
    }

    private class DrawLogger : AppLogger by NoOpLogger {
        val events = CopyOnWriteArrayList<LogEntry>()
        @Volatile var nextDraw = CountDownLatch(1)
        override fun log(level: LogLevel, category: LogCategory, event: String, message: String, trace: TraceContext?,
            status: LogStatus?, durationMs: Long?, error: Throwable?, errorCode: String?, metadata: Map<String, String>, syncStatus: LogSyncStatus) {
            events += LogEntry(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), level, category, event, message,
                traceId = trace?.traceId, durationMs = durationMs, metadata = metadata)
            if (event == "CART_RENDERED") nextDraw.countDown()
        }
    }
}

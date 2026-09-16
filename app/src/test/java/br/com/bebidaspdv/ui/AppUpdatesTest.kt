package br.com.bebidaspdv.ui

import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.*
import br.com.bebidaspdv.updates.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private val dispatcher = StandardTestDispatcher()
    private val product = Product("p1", "Bebida", "", 20, 100, 200)
    private lateinit var repository: FakeRepository
    private lateinit var gateway: FakeUpdates
    private lateinit var model: PosViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeRepository()
        gateway = FakeUpdates()
        model = PosViewModel(repository, gateway)
    }
    @After fun tearDown() { model.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun `no automatic check or installation on startup`() = runTest(dispatcher) {
        runCurrent()
        assertEquals(0, gateway.checks)
        assertEquals(0, gateway.installs)
    }

    @Test fun `failed check never claims current version or retains stale update`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        assertNotNull(model.update.value.available)
        gateway.checkError = true
        model.checkForUpdates(); runCurrent()
        assertNull(model.update.value.available)
        assertTrue(model.update.value.error)
        assertFalse(model.update.value.checking)
    }

    @Test fun `duplicate checks run once and never install automatically`() = runTest(dispatcher) {
        model.checkForUpdates(); model.checkForUpdates(); runCurrent()
        assertEquals(1, gateway.checks)
        assertEquals(0, gateway.installs)
        gateway.release = null
        model.checkForUpdates(); runCurrent()
        assertNull(model.update.value.available)
        assertFalse(model.update.value.error)
    }

    @Test fun `cart blocks install without losing its items`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        model.add(product)
        model.installUpdate(); runCurrent()
        assertEquals(0, gateway.installs)
        assertEquals(1, model.cart.value.single().quantity)
        assertFalse(model.working.value)
    }

    @Test fun `pending receipt and product metadata and offline cache each block installation`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        repository.operations.value = listOf(OperationStatus("sale", SyncState.PENDING))
        model.installUpdate(); runCurrent()
        assertEquals(0, gateway.drains)
        repository.operations.value = emptyList()
        repository.products.value = Feed(listOf(product), pendingWrites = true)
        runCurrent(); model.installUpdate(); runCurrent()
        assertEquals(0, gateway.drains)
        repository.products.value = Feed(listOf(product), fromCache = true)
        runCurrent(); model.installUpdate(); runCurrent()
        assertEquals(0, gateway.drains)
        assertFalse(model.working.value)
    }

    @Test fun `install reserves POS before awaiting server and rejects duplicate taps and mutations`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        model.installUpdate(); model.installUpdate()
        model.add(product)
        model.saveProduct(null, "Outro", "", "1", "2") {}
        model.receiveStock(product, "5") {}
        runCurrent()
        assertTrue(model.working.value)
        assertTrue(model.update.value.installing)
        assertEquals(1, gateway.drains)
        assertEquals(0, gateway.installs)
        assertEquals(0, repository.writes)
        assertTrue(model.cart.value.isEmpty())
        gateway.drained.complete(Unit); runCurrent()
        assertEquals(1, gateway.installs)
        assertTrue(model.working.value)
        gateway.installed.complete(Unit); runCurrent()
        assertFalse(model.working.value)
        assertFalse(model.update.value.installing)
        assertNull(model.update.value.available)
        model.add(product)
        assertEquals(1, model.cart.value.size)
    }

    @Test fun `pending write appearing during drain prevents SDK handoff`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        model.installUpdate(); runCurrent()
        repository.operations.value = listOf(OperationStatus("late", SyncState.PENDING))
        gateway.drained.complete(Unit); runCurrent()
        assertEquals(0, gateway.installs)
        assertTrue(model.update.value.error)
        assertFalse(model.working.value)
    }

    @Test fun `server timeout releases POS without starting download`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        model.installUpdate(); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(0, gateway.installs)
        assertFalse(model.working.value)
        assertTrue(model.update.value.error)
        assertTrue(model.update.value.message!!.contains("sincronização demorou"))
    }

    @Test fun `failed download releases POS and requires fresh check`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        gateway.drained.complete(Unit)
        model.installUpdate(); runCurrent()
        gateway.installed.completeExceptionally(IllegalStateException("Network error")); runCurrent()
        assertFalse(model.working.value)
        assertFalse(model.update.value.installing)
        assertTrue(model.update.value.error)
        assertNull(model.update.value.available)
    }

    @Test fun `changing tester account clears release and is blocked during download`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        model.changeTesterAccount()
        assertEquals(1, gateway.signOuts)
        assertNull(model.update.value.available)
        assertEquals(0, repository.writes)
        model.checkForUpdates(); runCurrent()
        model.installUpdate(); runCurrent()
        model.changeTesterAccount()
        assertEquals(1, gateway.signOuts)
    }

    @Test fun `a previously downloaded release appears without another network check`() = runTest(dispatcher) {
        gateway.downloadedUpdate.value = gateway.release
        runCurrent()
        assertTrue(model.update.value.downloaded)
        assertEquals(gateway.release, model.update.value.available)
        model.checkForUpdates(); runCurrent()
        assertEquals(0, gateway.checks)
        assertTrue(model.update.value.downloaded)
    }

    @Test fun `finishing download releases cashier while keeping install action available`() = runTest(dispatcher) {
        model.checkForUpdates(); runCurrent()
        gateway.drained.complete(Unit)
        model.installUpdate(); runCurrent()
        gateway.downloadedUpdate.value = gateway.release
        gateway.installed.complete(Unit); runCurrent()
        assertFalse(model.working.value)
        assertFalse(model.update.value.installing)
        assertTrue(model.update.value.downloaded)
        assertEquals(gateway.release, model.update.value.available)
        assertEquals(1, gateway.installs)
    }

    @Test fun `Android permission request preserves verified download and unlocks cashier`() = runTest(dispatcher) {
        gateway.downloadedUpdate.value = gateway.release
        runCurrent()
        gateway.drained.complete(Unit)
        model.installUpdate(); runCurrent()
        gateway.installed.completeExceptionally(UpdateActionRequired("Permita instalar desta fonte e tente novamente."))
        runCurrent()
        assertFalse(model.working.value)
        assertTrue(model.update.value.downloaded)
        assertEquals(gateway.release, model.update.value.available)
        assertEquals("Permita instalar desta fonte e tente novamente.", model.update.value.message)
    }

    @Test fun `changing tester account discards downloaded release without signing out store`() = runTest(dispatcher) {
        gateway.downloadedUpdate.value = gateway.release
        runCurrent()
        model.changeTesterAccount(); runCurrent()
        assertFalse(model.update.value.downloaded)
        assertNull(model.update.value.available)
        assertEquals(1, gateway.signOuts)
        assertEquals(0, repository.writes)
    }

    private class FakeUpdates : AppUpdates {
        override val enabled = true
        override val downloadedUpdate = MutableStateFlow<AvailableUpdate?>(null)
        var checks = 0; var installs = 0; var drains = 0
        var signOuts = 0
        override fun signOutTester() { signOuts++; downloadedUpdate.value = null }
        var checkError = false
        var release: AvailableUpdate? = AvailableUpdate("1.0.2", 3, "Melhorias")
        val drained = CompletableDeferred<Unit>()
        val installed = CompletableDeferred<Unit>()
        override suspend fun check(): AvailableUpdate? {
            checks++
            if (checkError) error("Network error")
            return release
        }
        override suspend fun awaitPendingWrites() { drains++; withTimeout(15_000) { drained.await() } }
        override suspend fun install(onProgress: (String) -> Unit) { installs++; onProgress("Baixando: 50%"); installed.await() }
    }

    private inner class FakeRepository : PosRepository {
        val products = MutableStateFlow(Feed(listOf(product)))
        override val operations = MutableStateFlow<List<OperationStatus>>(emptyList())
        var writes = 0
        override fun observeProducts() = products
        override fun observeMovements(month: YearMonth, zone: ZoneId) = flowOf(Feed(emptyList<Movement>()))
        override suspend fun submit(movement: Movement) { writes++ }
        override suspend fun saveProduct(product: Product) { writes++ }
    }
}

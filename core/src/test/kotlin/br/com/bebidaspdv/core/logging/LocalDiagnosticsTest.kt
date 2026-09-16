package br.com.bebidaspdv.core.logging

import com.google.gson.JsonParser
import java.io.File
import java.io.StringWriter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val epoch = 1_790_000_000_000L
    private val day = 86_400_000L
    private fun event(
        level: LogLevel = LogLevel.INFO, timestamp: Long = epoch, event: String = "SALE_COMPLETED",
        trace: String? = null, operation: String? = null, status: LogStatus? = null,
        connectivity: ConnectivityStatus = ConnectivityStatus.OFFLINE,
        sync: LogSyncStatus = LogSyncStatus.NOT_APPLICABLE, sequence: Long = 0,
    ) = LogEntry(UUID.randomUUID().toString(), timestamp, level, LogCategory.SALE, event,
        "Venda registrada no aparelho", traceId = trace, operationId = operation, status = status,
        connectivityStatus = connectivity, syncStatus = sync, sequence = sequence)

    private fun store(directory: File = temporary.newFolder(), policy: LogRetentionPolicy = LogRetentionPolicy(), now: () -> Long = { epoch }): LocalLogStore =
        LocalLogStore(directory, policy, now).also { it.initialize() }

    @Test fun infoWarningExceptionAndCauseArePersistedLocally() = runBlocking {
        LocalAppLogger(temporary.newFolder()).use { logger ->
            logger.info(LogCategory.APP, "APP_READY", "Pronto")
            logger.warning(LogCategory.STOCK, "STOCK_LOW", "Estoque baixo")
            val failure = IllegalStateException("Falha de venda", IllegalArgumentException("Causa real"))
            logger.error(LogCategory.SALE, "SALE_FAILED", "Não foi possível concluir", failure)
            assertTrue(logger.flush())
            val logs = logger.store.query()
            assertEquals(3, logs.size)
            assertTrue(logs.any { it.level == LogLevel.INFO })
            assertTrue(logs.any { it.level == LogLevel.WARNING })
            val error = logs.single { it.level == LogLevel.ERROR }
            assertEquals(IllegalStateException::class.java.name, error.exceptionType)
            assertTrue(error.stackTrace!!.contains("Caused by: java.lang.IllegalArgumentException: Causa real"))
            assertTrue(error.stackTrace!!.contains("LocalDiagnosticsTest"))
        }
    }

    @Test fun normalModeSuppressesDebugAndTraceAndDiagnosticModePersistsAcrossRestart() = runBlocking {
        val directory = temporary.newFolder()
        LocalAppLogger(directory).use { logger ->
            logger.debug(LogCategory.APP, "HIDDEN_DEBUG", "Não registrar")
            logger.trace(LogCategory.APP, "HIDDEN_TRACE", "Não registrar")
            logger.setDiagnosticMode(true)
            logger.debug(LogCategory.APP, "VISIBLE_DEBUG", "Registrar")
            logger.trace(LogCategory.APP, "VISIBLE_TRACE", "Registrar")
            assertTrue(logger.flush())
            assertEquals(0, logger.store.count(LogFilter(event = "HIDDEN")))
            assertEquals(2, logger.store.count(LogFilter(event = "VISIBLE")))
        }
        LocalAppLogger(directory).use { restored ->
            assertTrue(restored.flush())
            assertTrue(restored.diagnosticMode.value)
            restored.trace(LogCategory.APP, "TRACE_AFTER_RESTART", "Reiniciado")
            assertTrue(restored.flush())
            assertEquals(1, restored.store.count(LogFilter(event = "TRACE_AFTER_RESTART")))
        }
    }

    @Test fun offlineAndOnlineAreNormalEventSnapshots() = runBlocking {
        var environment = LogEnvironment(connectivityStatus = ConnectivityStatus.OFFLINE, appVersion = "1.3.0", buildVersion = "6", deviceId = "device-test")
        LocalAppLogger(temporary.newFolder(), { environment }).use { logger ->
            logger.info(LogCategory.SALE, "SALE_SAVED_LOCAL", "Salva offline")
            environment = environment.copy(connectivityStatus = ConnectivityStatus.ONLINE)
            logger.info(LogCategory.SYNC, "SALE_SYNCED", "Confirmada no servidor")
            assertTrue(logger.flush())
            val offline = logger.store.query(LogFilter(connectivityStatus = ConnectivityStatus.OFFLINE)).single()
            assertEquals(LogLevel.INFO, offline.level)
            assertEquals("1.3.0", offline.appVersion)
            assertEquals("6", offline.buildVersion)
            assertEquals(1, logger.store.count(LogFilter(connectivityStatus = ConnectivityStatus.ONLINE)))
        }
    }

    @Test fun traceIsUniqueAndCoroutineContextCrossesSuspension() = runBlocking {
        LocalAppLogger(temporary.newFolder()).use { logger ->
            val trace = logger.startTrace(LogCategory.SALE, "SALE_STARTED")
            val other = logger.startTrace(LogCategory.SALE, "SALE_STARTED")
            assertNotEquals(trace.traceId, other.traceId)
            UUID.fromString(trace.traceId)
            withLogTrace(trace) {
                delay(1)
                assertEquals(trace, currentLogTrace())
                logger.info(LogCategory.CART, "PRODUCT_ADDED", "Produto adicionado", currentLogTrace())
                logger.info(LogCategory.PAYMENT, "PAYMENT_REGISTERED", "Pagamento registrado", currentLogTrace())
            }
            assertNull(currentLogTrace())
            assertTrue(logger.flush())
            assertEquals(listOf("SALE_STARTED", "PRODUCT_ADDED", "PAYMENT_REGISTERED"), logger.store.query(LogFilter(traceId = trace.traceId), oldestFirst = true).map { it.event })
        }
    }

    @Test fun operationTraceSurvivesRestartAndClearingLogs() = runBlocking {
        val directory = temporary.newFolder()
        val operation = UUID.randomUUID().toString()
        var traceId = ""
        LocalAppLogger(directory).use { logger ->
            val trace = logger.startTrace(LogCategory.SALE, "SALE_STARTED")
            val bound = logger.bindOperation(trace, operation)
            traceId = bound.traceId
            logger.log(LogLevel.INFO, LogCategory.SYNC, "SALE_SYNC_QUEUED", "Aguardando servidor", bound, syncStatus = LogSyncStatus.PENDING)
            assertTrue(logger.flush())
            logger.store.clear(LogClearMode.ALL)
            assertEquals(0, logger.store.count())
        }
        LocalAppLogger(directory).use { restored ->
            assertTrue(restored.flush())
            val trace = restored.forOperation(operation, LogCategory.SYNC)
            assertEquals(traceId, trace.traceId)
            restored.log(LogLevel.INFO, LogCategory.SYNC, "SALE_SYNCED", "Confirmada após reinício", trace, syncStatus = LogSyncStatus.SYNCED)
            assertTrue(restored.flush())
            assertEquals(traceId, restored.store.query().single().traceId)
        }
    }

    @Test fun queryFiltersCombineAndSearchIdsMessagesMetadataAndErrorCodes() = runBlocking {
        val store = store()
        val desired = event(trace = "trace-sale", operation = "operation-sale", status = LogStatus.FAILED, sync = LogSyncStatus.FAILED)
            .copy(level = LogLevel.ERROR, entityId = "product-987", errorCode = "FIRESTORE_TIMEOUT", metadata = mapOf("sku" to "cerveja-lata"))
        store.append(listOf(desired, event(timestamp = epoch - day), event(level = LogLevel.WARNING, connectivity = ConnectivityStatus.ONLINE)), true)
        val filter = LogFilter(levels = setOf(LogLevel.ERROR), categories = setOf(LogCategory.SALE), fromTimestamp = epoch - 1,
            toTimestamp = epoch + 1, traceId = "trace-sale", operationId = "operation-sale", event = "sale_completed",
            statuses = setOf(LogStatus.FAILED), connectivityStatus = ConnectivityStatus.OFFLINE, syncStatus = LogSyncStatus.FAILED)
        assertEquals(desired.id, store.query(filter).single().id)
        listOf("product-987", "operation-sale", "trace-sale", "firestore_timeout", "cerveja-lata", "VENDA REGISTRADA").forEach {
            assertTrue("Search $it", store.query(LogFilter(text = it)).any { row -> row.id == desired.id })
        }
        assertEquals(0, store.count(filter.copy(categories = setOf(LogCategory.AUTH))))
        assertEquals(0, store.count(filter.copy(fromTimestamp = epoch + 2)))
        assertEquals(0, store.count(filter.copy(toTimestamp = epoch - 2)))
        assertEquals(0, store.count(filter.copy(traceId = "another")))
        assertEquals(0, store.count(filter.copy(operationId = "another")))
        assertEquals(0, store.count(filter.copy(event = "another")))
        assertEquals(0, store.count(filter.copy(statuses = setOf(LogStatus.SUCCESS))))
        assertEquals(0, store.count(filter.copy(connectivityStatus = ConnectivityStatus.ONLINE)))
        assertEquals(0, store.count(filter.copy(syncStatus = LogSyncStatus.SYNCED)))
    }

    @Test fun paginationAndChronologicalTraceOrderUseSequenceForEqualTimestamps() = runBlocking {
        val store = store()
        store.append(listOf(event(event = "third", sequence = 3), event(event = "first", sequence = 1), event(event = "second", sequence = 2)), false)
        assertEquals(listOf("third", "second"), store.query(limit = 2).map { it.event })
        assertEquals(listOf("second", "third"), store.query(offset = 1, oldestFirst = true).map { it.event })
    }

    @Test fun liveFlowEmitsAfterPersistedChangesWithoutPolling() = runBlocking {
        val store = store()
        val version = store.changes.value
        val changed = async { withTimeout(1_000) { store.changes.first { it > version } } }
        store.append(listOf(event()), true)
        store.publish()
        assertTrue(changed.await() > version)
        assertEquals(1, store.count())
    }

    @Test fun retentionAppliesDifferentAgesToEachLevel() = runBlocking {
        val store = store()
        val ages = mapOf(LogLevel.TRACE to 7, LogLevel.DEBUG to 7, LogLevel.INFO to 15, LogLevel.WARNING to 30, LogLevel.ERROR to 90, LogLevel.CRITICAL to 90)
        ages.forEach { (level, days) -> store.append(listOf(event(level, epoch - days * day + 1, "KEEP_${level.name}"), event(level, epoch - days * day - 1, "EXPIRE_${level.name}")), false) }
        store.maintenance()
        assertEquals(6, store.count())
        assertTrue(store.query().all { it.event.startsWith("KEEP_") })
    }

    @Test fun recordCapPreservesWarningsAndErrorsUnderDebugFlood() = runBlocking {
        val store = store(policy = LogRetentionPolicy(maxRecords = 25))
        store.append(listOf(event(LogLevel.CRITICAL), event(LogLevel.WARNING)), true)
        repeat(50) { batch -> store.append((1..100).map { event(LogLevel.DEBUG, sequence = (batch * 100 + it).toLong()) }, false) }
        assertEquals(25, store.count())
        assertEquals(1, store.count(LogFilter(levels = setOf(LogLevel.CRITICAL))))
        assertEquals(1, store.count(LogFilter(levels = setOf(LogLevel.WARNING))))
    }

    @Test fun byteCapBoundsActualJournalAndRestartsWithValidRecords() = runBlocking {
        val directory = temporary.newFolder()
        val policy = LogRetentionPolicy(maxBytes = 4_000)
        val store = store(directory, policy)
        // Spaced text stays large after redaction; a long opaque token is intentionally shortened.
        store.append((1..50).map { event().copy(message = "conteudo ".repeat(125)) }, true)
        assertTrue(File(directory, "events.jsonl").length() <= policy.maxBytes)
        assertTrue(store.count() in 1..3)
        assertEquals(store.count(), store(directory, policy).count())
    }

    @Test fun clearDebugExpiredAndAllHaveDistinctEffectsAndPersist() = runBlocking {
        val clock = AtomicLong(epoch)
        val directory = temporary.newFolder()
        val store = store(directory, now = clock::get)
        store.append(listOf(event(LogLevel.TRACE), event(LogLevel.DEBUG), event(LogLevel.INFO), event(LogLevel.ERROR)), true)
        store.clear(LogClearMode.DEBUG_TRACE)
        assertEquals(setOf(LogLevel.INFO, LogLevel.ERROR), store.query().map { it.level }.toSet())
        clock.addAndGet(16 * day)
        store.clear(LogClearMode.EXPIRED)
        assertEquals(LogLevel.ERROR, store.query().single().level)
        store.clear(LogClearMode.ALL)
        assertEquals(0, store(directory, now = clock::get).count())
    }

    @Test fun exportContainsStableJsonFieldsAndHonorsErrorPeriodAndTraceFilters() = runBlocking {
        val store = store()
        store.append(listOf(event(LogLevel.ERROR, trace = "wanted", operation = "sale-1"), event(LogLevel.INFO, trace = "other"), event(LogLevel.ERROR, epoch - day, trace = "old")), true)
        val writer = StringWriter()
        val count = store.export(LogFilter(levels = setOf(LogLevel.ERROR), fromTimestamp = epoch - 1, traceId = "wanted"), writer)
        assertEquals(1, count)
        val json = JsonParser.parseString(writer.toString()).asJsonArray.single().asJsonObject
        assertEquals("wanted", json["trace_id"].asString)
        assertEquals("sale-1", json["operation_id"].asString)
        assertEquals("OFFLINE", json["connectivity_status"].asString)
        assertTrue(json.has("app_version"))
        assertFalse(json.has("traceId"))
    }

    @Test fun summaryTracksObservedPendingSyncAndEventualAcknowledgment() = runBlocking {
        val store = store()
        store.append(listOf(event(operation = "sale-a", sync = LogSyncStatus.PENDING, sequence = 1),
            event(operation = "sale-b", sync = LogSyncStatus.PENDING, sequence = 2),
            event(LogLevel.WARNING, status = LogStatus.SLOW, trace = "slow", sequence = 3),
            event(LogLevel.WARNING, status = LogStatus.STALLED, trace = "slow", sequence = 4),
            event(LogLevel.ERROR, sequence = 5)), true)
        assertEquals(2, store.summary(epoch).pendingSyncOperations)
        store.append(listOf(event(operation = "sale-a", sync = LogSyncStatus.SYNCED, sequence = 6),
            event(operation = "sale-b", sync = LogSyncStatus.UNKNOWN, sequence = 7),
            event(operation = "sale-a", sync = LogSyncStatus.NOT_APPLICABLE, sequence = 8)), true)
        val summary = store.summary(epoch)
        assertEquals(1, summary.pendingSyncOperations)
        assertEquals(1, summary.slowOperations)
        assertEquals(1, summary.errorsToday)
        assertEquals(2, summary.warningsToday)
        assertEquals(LogSyncStatus.SYNCED, summary.lastSync!!.syncStatus)
        assertNotNull(summary.lastError)
    }

    @Test fun corruptAndPartialLinesAreSkippedAndValidJournalIsRepaired() = runBlocking {
        val directory = temporary.newFolder()
        val original = store(directory)
        original.append(listOf(event()), true)
        File(directory, "events.jsonl").appendText("{broken json}\n{\"id\":\"partial\"")
        val restored = store(directory)
        assertEquals(1, restored.count())
        assertEquals(2L, restored.health.value.recoveredCorruptRecords)
        assertEquals(1, File(directory, "events.jsonl").readLines().size)
    }

    @Test fun interruptedCompactionRecoversTemporaryJournalIfMainFileMissing() = runBlocking {
        val directory = temporary.newFolder()
        val original = store(directory)
        original.append(listOf(event()), true)
        assertTrue(File(directory, "events.jsonl").renameTo(File(directory, "events.tmp")))
        assertEquals(1, store(directory).count())
    }

    @Test fun crashRecordIsRecoveredAfterRestartWithoutDuplicateId() = runBlocking {
        val directory = temporary.newFolder()
        val logger = LocalAppLogger(directory)
        assertTrue(logger.flush())
        logger.close()
        logger.recordCrash(IllegalStateException("Crash de teste"))
        LocalAppLogger(directory).use { restored ->
            assertTrue(restored.flush())
            val crash = restored.store.query(LogFilter(event = "UNCAUGHT_EXCEPTION")).single()
            assertEquals(LogLevel.CRITICAL, crash.level)
            assertTrue(crash.stackTrace!!.contains("Crash de teste"))
            assertFalse(File(directory, "crash-recovery.json").exists())
        }
    }

    @Test fun emergencyAndJournalCopiesOfSameCrashAreDeduplicatedOnRestart() = runBlocking {
        val directory = temporary.newFolder()
        val store = store(directory)
        val crash = event(LogLevel.CRITICAL)
        store.append(listOf(crash), true)
        store.crashFallback(crash)
        assertEquals(1, store(directory).count())
    }

    @Test fun boundedQueueReturnsImmediatelyWhileWorkerIsBlockedAndPreservesPriority() = runBlocking {
        val workerEntered = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val logger = LocalAppLogger(temporary.newFolder(), wallClock = {
            if (Thread.currentThread().name == "pdv-local-diagnostics") {
                workerEntered.countDown()
                check(releaseWorker.await(5, TimeUnit.SECONDS))
            }
            epoch
        }, queueCapacity = 32, priorityCapacity = 8)
        try {
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS))
            logger.setDiagnosticMode(true)
            repeat(1_000) { logger.debug(LogCategory.CART, "FLOOD", "Evento $it") }
            logger.warning(LogCategory.STOCK, "IMPORTANT_WARNING", "Atenção")
            logger.error(LogCategory.SALE, "IMPORTANT_ERROR", "Erro")
            // Reaching this point while the worker is blocked proves business callers did not wait on IO.
            assertEquals(1L, releaseWorker.count)
            releaseWorker.countDown()
            assertTrue(logger.flush(5_000))
            assertTrue(logger.store.health.value.droppedRecords > 0)
            assertEquals(1, logger.store.count(LogFilter(event = "IMPORTANT_ERROR")))
            assertEquals(1, logger.store.count(LogFilter(event = "IMPORTANT_WARNING")))
            assertTrue(logger.store.count(LogFilter(event = "LOGGER_QUEUE_OVERFLOW")) > 0)
        } finally { releaseWorker.countDown(); logger.close() }
    }

    @Test fun storageFailureDoesNotEscapeToBusinessCallerAndIsVisibleInHealth() = runBlocking {
        val unusableDirectory = temporary.newFile()
        LocalAppLogger(unusableDirectory).use { logger ->
            logger.info(LogCategory.SALE, "SALE_STARTED", "Venda")
            assertFalse(logger.flush())
            assertTrue(logger.store.health.value.storageFailures > 0)
            assertNotNull(logger.store.health.value.lastFailure)
        }
    }

    @Test fun contextProviderFailureUsesUnknownEnvironmentWithoutBreakingSale() = runBlocking {
        LocalAppLogger(temporary.newFolder(), { error("ambiente indisponível") }).use { logger ->
            logger.info(LogCategory.SALE, "SALE_STARTED", "Venda")
            assertTrue(logger.flush())
            assertEquals(ConnectivityStatus.UNKNOWN, logger.store.query().single().connectivityStatus)
        }
    }

    @Test fun stepMeasuresMonotonicDurationAndCompletesOnlyOnce() = runBlocking {
        val wall = AtomicLong(epoch)
        val monotonic = AtomicLong(100)
        LocalAppLogger(temporary.newFolder(), wallClock = wall::get, monotonicClock = monotonic::get).use { logger ->
            val trace = logger.startTrace(LogCategory.CART, "ADD_PRODUCT")
            val step = logger.startStep(trace, LogCategory.CART, "CART_UPDATE", "Cart", "add")
            wall.addAndGet(-day)
            monotonic.addAndGet(25)
            step.finish()
            step.finish()
            step.cancel()
            assertTrue(logger.flush())
            val completed = logger.store.query(LogFilter(event = "CART_UPDATE_COMPLETED")).single()
            assertEquals(25L, completed.durationMs)
            assertEquals("NORMAL", completed.metadata["performance_class"])
            assertEquals(trace.traceId, completed.traceId)
        }
    }

    @Test fun slowCompletedStepIdentifiesComponentFunctionAndThreshold() = runBlocking {
        val monotonic = AtomicLong(1)
        LocalAppLogger(temporary.newFolder(), monotonicClock = monotonic::get).use { logger ->
            val step = logger.startStep(TraceContext(), LogCategory.DATABASE, "DATABASE_WRITE", "ProductRepository", "save", slowAfterMs = 500)
            monotonic.addAndGet(900)
            step.finish()
            assertTrue(logger.flush())
            val result = logger.store.query(LogFilter(event = "DATABASE_WRITE_COMPLETED")).single()
            assertEquals(LogStatus.SLOW, result.status)
            assertEquals(LogLevel.WARNING, result.level)
            assertEquals(900L, result.durationMs)
            assertEquals("ProductRepository", result.component)
            assertEquals("save", result.function)
            assertEquals("500", result.metadata["slow_after_ms"])
        }
    }

    @Test fun watchdogReportsStalledStepOnceAndCanLaterFinish() = runBlocking {
        val monotonic = AtomicLong(1)
        LocalAppLogger(temporary.newFolder(), monotonicClock = monotonic::get).use { logger ->
            val step = logger.startStep(TraceContext(), LogCategory.FIREBASE, "FIREBASE_CHECK", "Repository", "check", 500, 1_000)
            monotonic.addAndGet(1_500)
            logger.info(LogCategory.APP, "WAKE", "Verificar etapas")
            assertTrue(logger.flush())
            assertEquals(1, logger.store.count(LogFilter(statuses = setOf(LogStatus.STALLED))))
            logger.info(LogCategory.APP, "WAKE_AGAIN", "Verificar novamente")
            assertTrue(logger.flush())
            assertEquals(1, logger.store.count(LogFilter(statuses = setOf(LogStatus.STALLED))))
            step.finish()
            assertTrue(logger.flush())
            assertEquals(1, logger.store.count(LogFilter(event = "FIREBASE_CHECK_COMPLETED")))
        }
    }

    @Test fun failedAndCancelledStepsHaveDifferentLevelsAndFinalStatuses() = runBlocking {
        LocalAppLogger(temporary.newFolder()).use { logger ->
            logger.startStep(TraceContext(), LogCategory.AUTH, "LOGIN", "Auth", "login").fail(IllegalArgumentException("Falha"), errorCode = "AUTH_FAILED")
            logger.startStep(TraceContext(), LogCategory.UPDATE, "UPDATE_CHECK", "Updates", "check").cancel("Operador cancelou")
            assertTrue(logger.flush())
            assertEquals(LogLevel.ERROR, logger.store.query(LogFilter(statuses = setOf(LogStatus.FAILED))).single().level)
            assertEquals(LogLevel.INFO, logger.store.query(LogFilter(statuses = setOf(LogStatus.CANCELLED))).single().level)
        }
    }
}

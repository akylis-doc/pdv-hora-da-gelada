package br.com.bebidaspdv.ui.diagnostics

import br.com.bebidaspdv.core.logging.*
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsFiltersTest {
    private val now = Instant.parse("2026-09-15T17:30:00Z")
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test fun `today follows device local midnight rather than UTC midnight`() {
        val query = DiagnosticsFilters(period = DiagnosticsPeriod.TODAY).toQuery(now, zone)
        assertEquals(Instant.parse("2026-09-15T03:00:00Z").toEpochMilli(), query.fromTimestamp)
        assertNull(query.toTimestamp)
    }

    @Test fun `quick windows are relative to current query time`() {
        assertEquals(now.minusSeconds(600).toEpochMilli(), DiagnosticsPeriod.TEN_MINUTES.start(now, zone))
        assertEquals(now.minusSeconds(3_600).toEpochMilli(), DiagnosticsPeriod.HOUR.start(now, zone))
        assertNull(DiagnosticsPeriod.ALL.start(now, zone))
    }

    @Test fun `custom range includes final day until final millisecond`() {
        val filter = DiagnosticsFilters(period = DiagnosticsPeriod.CUSTOM, startDate = "2026-09-14", endDate = "2026-09-15")
        assertEquals(Instant.parse("2026-09-14T03:00:00Z").toEpochMilli() to Instant.parse("2026-09-16T02:59:59.999Z").toEpochMilli(), filter.periodBounds(now, zone))
    }

    @Test fun `invalid or reversed dates cannot silently export unfiltered data`() {
        listOf(
            DiagnosticsFilters(period = DiagnosticsPeriod.CUSTOM),
            DiagnosticsFilters(period = DiagnosticsPeriod.CUSTOM, startDate = "2026-99-01"),
            DiagnosticsFilters(period = DiagnosticsPeriod.CUSTOM, startDate = "2026-09-16", endDate = "2026-09-15"),
        ).forEach { filter -> assertTrue(runCatching { filter.toQuery(now, zone) }.isFailure) }
    }

    @Test fun `all operation and connectivity selectors survive conversion without being mixed`() {
        val query = DiagnosticsFilters(levels = setOf("ERROR", "WARNING"), category = "FIREBASE", traceId = " trace-123 ",
            operationId = " sale-456 ", event = " SYNC_OPERATION_FAILED ", status = "RETRY", connectivity = "OFFLINE",
            sync = "PENDING", text = " permission-denied ").toQuery(now, zone)
        assertEquals(setOf(LogLevel.ERROR, LogLevel.WARNING), query.levels)
        assertEquals(setOf(LogCategory.FIREBASE), query.categories)
        assertEquals("trace-123", query.traceId)
        assertEquals("sale-456", query.operationId)
        assertEquals("SYNC_OPERATION_FAILED", query.event)
        assertEquals(setOf(LogStatus.RETRY), query.statuses)
        assertEquals(ConnectivityStatus.OFFLINE, query.connectivityStatus)
        assertEquals(LogSyncStatus.PENDING, query.syncStatus)
        assertEquals("permission-denied", query.text)
    }

    @Test fun `empty selectors remove restrictions`() {
        assertEquals(LogFilter(), DiagnosticsFilters().toQuery(now, zone))
    }

    @Test fun `nested measured stages are compared without adding durations`() {
        val entries = listOf(entry("1", 1_000, 20L), entry("2", 1_100, 50L), entry("3", 1_050, 100L).copy(status = LogStatus.STARTED))
        assertEquals(100L, traceIntervalMs(entries))
        assertEquals("2", traceBottleneck(entries)?.id)
        assertEquals(50L, traceBottleneck(entries)?.durationMs)
        assertEquals(0L, traceIntervalMs(emptyList()))
    }

    @Test fun `copyable details retain all technical context and exception cause`() {
        val log = entry("log", 1_000, 50).copy(traceId = "trace", operationId = "sale", entityId = "product",
            userId = "operator", deviceId = "phone", screen = "Caixa", component = "Repository", function = "save",
            errorCode = "PERMISSION_DENIED", exceptionType = "IOException", stackTrace = "IOException\nCaused by: FileSystemException",
            connectivityStatus = ConnectivityStatus.OFFLINE, syncStatus = LogSyncStatus.PENDING,
            appVersion = "1.2.1", buildVersion = "6", deviceInformation = "Android 15", metadata = mapOf("attempt" to "2"))
        val text = logDetailsText(log)
        listOf("trace_id: trace", "operation_id: sale", "entity_id: product", "error_code: PERMISSION_DENIED",
            "Caused by: FileSystemException", "attempt: 2", "connectivity_status: OFFLINE", "build_version: 6",
            "device_information: Android 15").forEach { assertTrue(text.contains(it)) }
    }

    @Test fun `export writer refuses writes after administrator access is revoked`() {
        val destination = StringWriter()
        var allowed = true
        val writer = AccessCheckedWriter(destination) { check(allowed) }
        writer.write("first")
        allowed = false
        assertTrue(runCatching { writer.write("second") }.isFailure)
        assertTrue(runCatching { writer.write(charArrayOf('x'), 0, 1) }.isFailure)
        assertEquals("first", destination.toString())
    }

    @Test fun `bottleneck identifies internal stage and excludes offline waiting`() {
        val wrapper = entry("wrapper", 1_000, 1_000).copy(metadata = mapOf("step_id" to "parent"))
        val database = entry("database", 990, 900).copy(metadata = mapOf("step_id" to "child"))
        val offline = entry("offline", 60_000, 59_000).copy(metadata = mapOf("includes_offline_wait" to "true"))
        assertEquals("database", traceBottleneck(listOf(wrapper, database, offline))?.id)
        assertEquals(null, traceBottleneck(listOf(offline)))
    }

    private fun entry(id: String, timestamp: Long, duration: Long?) = LogEntry(id, timestamp, LogLevel.INFO,
        LogCategory.DATABASE, "DATABASE_WRITE", "Gravação local", durationMs = duration, status = LogStatus.SUCCESS)
}

package br.com.bebidaspdv.ui.diagnostics

import br.com.bebidaspdv.core.logging.*
import java.time.Instant
import java.time.ZoneId

internal enum class DiagnosticsPeriod(val label: String) {
    ALL("Todo o período"), TEN_MINUTES("Últimos 10 minutos"), HOUR("Última hora"), TODAY("Hoje"), CUSTOM("Período personalizado");

    fun start(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Long? = when (this) {
        ALL, CUSTOM -> null
        TEN_MINUTES -> now.minusSeconds(600).toEpochMilli()
        HOUR -> now.minusSeconds(3_600).toEpochMilli()
        TODAY -> now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** UI input remains separate from the query so invalid date input is never silently ignored. */
internal data class DiagnosticsFilters(
    val levels: Set<String> = emptySet(),
    val category: String = "",
    val period: DiagnosticsPeriod = DiagnosticsPeriod.ALL,
    val startDate: String = "",
    val endDate: String = "",
    val traceId: String = "",
    val operationId: String = "",
    val event: String = "",
    val status: String = "",
    val connectivity: String = "",
    val sync: String = "",
    val text: String = "",
) {
    fun periodBounds(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Pair<Long?, Long?> {
        if (period != DiagnosticsPeriod.CUSTOM) return period.start(now, zone) to null
        val start = startDate.takeIf(String::isNotBlank)?.let { java.time.LocalDate.parse(it) }
        val end = endDate.takeIf(String::isNotBlank)?.let { java.time.LocalDate.parse(it) }
        require(start != null || end != null) { "Informe a data inicial ou final em aaaa-mm-dd." }
        require(start == null || end == null || !end.isBefore(start)) { "A data final deve ser igual ou posterior à inicial." }
        return start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() to
            end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1)
    }

    fun toQuery(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): LogFilter {
        val (from, to) = periodBounds(now, zone)
        return LogFilter(
            levels = levels.map(LogLevel::valueOf).toSet(),
            categories = category.takeIf(String::isNotBlank)?.let { setOf(LogCategory.valueOf(it)) }.orEmpty(),
            fromTimestamp = from, toTimestamp = to,
            traceId = traceId.trim().takeIf(String::isNotBlank),
            operationId = operationId.trim().takeIf(String::isNotBlank),
            event = event.trim().takeIf(String::isNotBlank),
            statuses = status.takeIf(String::isNotBlank)?.let { setOf(LogStatus.valueOf(it)) }.orEmpty(),
            connectivityStatus = connectivity.takeIf(String::isNotBlank)?.let(ConnectivityStatus::valueOf),
            syncStatus = sync.takeIf(String::isNotBlank)?.let(LogSyncStatus::valueOf),
            text = text.trim(),
        )
    }
}

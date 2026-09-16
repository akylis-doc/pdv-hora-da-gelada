package br.com.bebidaspdv.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.YearMonth
import java.time.ZoneId

interface CashControlRepository {
    val deviceId: String
    val pendingOperations: StateFlow<List<PendingCashOperation>>
    fun observeSessions(): Flow<Feed<List<CashSession>>>
    fun observeEvents(): Flow<Feed<List<CashEvent>>>
    fun observeReversals(month: YearMonth = YearMonth.now(), zone: ZoneId = ZoneId.of("America/Sao_Paulo")): Flow<Feed<List<SaleReversal>>>
    /** A one-time, complete server read. Fails if the session changes during the reading. */
    suspend fun readPaymentSummary(sessionId: String): CashPaymentSummary = error("Conferência indisponível.")
    suspend fun openSession(openingCents: Long)
    suspend fun addMovement(sessionId: String, type: CashEventType, amountCents: Long, reason: String)
    /** Fails if the operator's displayed balance has changed since the closing dialog opened. */
    suspend fun closeSession(sessionId: String, countedCents: Long, expectedRevision: Long)
    suspend fun previewReversal(saleId: String): ReversalPreview
    /** The caller explicitly confirms refunds shown in the preview, including prior fiado receipts. */
    suspend fun reverseSale(preview: ReversalPreview, reason: String)
    /** Reuses the persisted UUID and exact payload, including after process death. */
    suspend fun retryPending(id: String)
}

package br.com.bebidaspdv.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticCrashHandlerTest {
    @Test fun `records original exception then delegates Android handler`() {
        val calls = mutableListOf<String>()
        val crash = IllegalStateException("failure")
        val current = Thread.currentThread()
        val handler = DiagnosticCrashHandler({ assertSame(crash, it); calls += "record" },
            Thread.UncaughtExceptionHandler { thread, error -> assertSame(current, thread); assertSame(crash, error); calls += "android" })
        handler.uncaughtException(current, crash)
        assertEquals(listOf("record", "android"), calls)
    }
    @Test fun `recorder failure and repeated crashes never suppress Android handler`() {
        var records = 0
        var delegated = 0
        val handler = DiagnosticCrashHandler({ records++; throw IllegalStateException("disk full") },
            Thread.UncaughtExceptionHandler { _, _ -> delegated++ })
        repeat(2) { handler.uncaughtException(Thread.currentThread(), IllegalArgumentException()) }
        assertEquals(1, records)
        assertEquals(2, delegated)
    }
}

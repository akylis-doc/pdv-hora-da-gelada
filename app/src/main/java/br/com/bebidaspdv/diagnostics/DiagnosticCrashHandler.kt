package br.com.bebidaspdv.diagnostics

import java.util.concurrent.atomic.AtomicBoolean

/** Recording is best effort. Android's existing crash handler always retains control. */
internal class DiagnosticCrashHandler(
    private val record: (Throwable) -> Unit,
    private val downstream: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    private val recording = AtomicBoolean(false)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (recording.compareAndSet(false, true)) runCatching { record(error) }
        } finally {
            downstream.uncaughtException(thread, error)
        }
    }
}

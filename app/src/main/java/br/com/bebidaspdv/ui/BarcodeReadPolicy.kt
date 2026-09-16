package br.com.bebidaspdv.ui

import com.google.mlkit.vision.barcode.common.Barcode

internal val productBarcodeFormats = listOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128,
    Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR)

internal data class BarcodeReading(val rawValue: String?, val format: Int)

/** Runs on the analyzer's result executor. One image can contribute at most one vote.
 * ML Kit explicitly recommends consecutive equal results, and does not validate ITF checksums:
 * https://developers.google.com/ml-kit/vision/barcode-scanning/android#tips_to_improve_real-time_performance
 */
internal class BarcodeReadPolicy {
    private var previous: BarcodeReading? = null
    private var firstFrameMs = 0L
    private var lastFrameMs = 0L
    var consecutiveFrames = 0
        private set

    fun accept(readings: List<BarcodeReading>, frameTimeMs: Long): String? {
        // Never pick the first result: list order does not express recognition confidence.
        val reading = readings.singleOrNull()
        if (reading == null || !validBarcodeReading(reading)) {
            reset()
            return null
        }
        if (previous != null && frameTimeMs <= lastFrameMs) {
            reset()
            return null
        }
        if (reading != previous || frameTimeMs - lastFrameMs > 1_000) {
            previous = reading
            consecutiveFrames = 1
            firstFrameMs = frameTimeMs
        } else {
            consecutiveFrames++
        }
        lastFrameMs = frameTimeMs
        return reading.rawValue.takeIf {
            consecutiveFrames >= 5 && frameTimeMs - firstFrameMs >= 750
        }
    }

    fun reset() {
        previous = null
        consecutiveFrames = 0
        firstFrameMs = 0
        lastFrameMs = 0
    }
}

internal fun validBarcodeReading(reading: BarcodeReading): Boolean {
    val code = reading.rawValue ?: return false
    if (reading.format !in productBarcodeFormats || code.isBlank() || code.length > 80 || code.any(Char::isISOControl)) return false
    return when (reading.format) {
        Barcode.FORMAT_EAN_13 -> validGtin(code, 13)
        Barcode.FORMAT_EAN_8 -> validGtin(code, 8)
        Barcode.FORMAT_UPC_A -> validGtin(code, 12)
        Barcode.FORMAT_UPC_E -> expandedUpcE(code)?.let { validGtin(it, 12) } == true
        // Generic ITF and Code 39 do not have a mandatory check digit. Do not
        // assume every ITF label is a GTIN-14 or impose a minimum product-code length.
        Barcode.FORMAT_ITF -> code.length % 2 == 0 && code.all { it in '0'..'9' }
        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODABAR -> true
        else -> false
    }
}

private fun validGtin(code: String, length: Int): Boolean {
    if (code.length != length || code.any { it !in '0'..'9' }) return false
    val weightedSum = code.dropLast(1).reversed().mapIndexed { index, digit ->
        (digit - '0') * if (index % 2 == 0) 3 else 1
    }.sum()
    return (10 - weightedSum % 10) % 10 == code.last() - '0'
}

// UPC-E's check digit belongs to its expanded UPC-A representation, not its eight visible digits.
// Expansion is only for validation: return the original code, including every leading zero.
private fun expandedUpcE(code: String): String? {
    if (code.length != 8 || code.any { it !in '0'..'9' } || code[0] !in "01") return null
    val payload = code.substring(1, 7)
    val body = when (payload.last()) {
        '0', '1', '2' -> payload.take(2) + payload.last() + "0000" + payload.substring(2, 5)
        '3' -> payload.take(3) + "00000" + payload.substring(3, 5)
        '4' -> payload.take(4) + "00000" + payload[4]
        else -> payload.take(5) + "0000" + payload.last()
    }
    return code.first() + body + code.last()
}

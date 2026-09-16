package br.com.bebidaspdv.ui

import com.google.mlkit.vision.barcode.common.Barcode
import org.junit.Assert.*
import org.junit.Test

class BarcodeReadPolicyTest {
    private val ean = BarcodeReading("4006381333931", Barcode.FORMAT_EAN_13)
    private val otherEan = BarcodeReading("7891234567895", Barcode.FORMAT_EAN_13)
    private val shortCode = BarcodeReading("308863", Barcode.FORMAT_ITF)
    private fun read(policy: BarcodeReadPolicy, reading: BarcodeReading = ean, time: Long) = policy.accept(listOf(reading), time)

    private fun assertFreshSequence(policy: BarcodeReadPolicy, reading: BarcodeReading = ean, start: Long = 0) {
        for (offset in listOf(0L, 200L, 400L, 600L)) assertNull(read(policy, reading, start + offset))
        assertEquals(reading.rawValue, read(policy, reading, start + 800))
    }

    @Test fun `a single frame never fills the field even when the number is valid`() {
        assertNull(read(BarcodeReadPolicy(), time = 50_000))
    }

    @Test fun `five consecutive frames over at least 750 milliseconds confirm a product`() {
        val policy = BarcodeReadPolicy()
        for (time in listOf(0L, 200L, 400L, 600L)) assertNull(read(policy, time = time))
        assertEquals(ean.rawValue, read(policy, time = 750))
    }

    @Test fun `a fast burst must still span the minimum observation time`() {
        val policy = BarcodeReadPolicy()
        for (frame in 0..24) assertNull(read(policy, time = frame * 30L))
        assertEquals(ean.rawValue, read(policy, time = 750))
    }

    @Test fun `elapsed time alone is insufficient without five frames`() {
        val policy = BarcodeReadPolicy()
        for (time in listOf(0L, 800L, 1600L, 2400L)) assertNull(read(policy, time = time))
        assertEquals(ean.rawValue, read(policy, time = 3200))
    }

    @Test fun `one incorrect but valid number cannot beat subsequent stable frames`() {
        val policy = BarcodeReadPolicy()
        assertNull(read(policy, otherEan, 0))
        assertFreshSequence(policy, start = 200)
    }

    @Test fun `alternating numbers are never confirmed even after a long time`() {
        val policy = BarcodeReadPolicy()
        repeat(50) { assertNull(read(policy, if (it % 2 == 0) ean else otherEan, it * 200L)) }
    }

    @Test fun `a missing frame breaks the consecutive sequence`() {
        val policy = BarcodeReadPolicy()
        for (time in listOf(0L, 200L, 400L, 600L)) read(policy, time = time)
        assertNull(policy.accept(emptyList(), 800))
        assertFreshSequence(policy, start = 1000)
    }

    @Test fun `multiple barcodes are ambiguous regardless of their order`() {
        for (readings in listOf(listOf(ean, otherEan), listOf(otherEan, ean))) {
            val policy = BarcodeReadPolicy()
            for (time in listOf(0L, 200L, 400L, 600L)) read(policy, time = time)
            assertNull(policy.accept(readings, 800))
            assertFreshSequence(policy, start = 1000)
        }
    }

    @Test fun `two detections in one frame are never counted as repeated frames`() {
        val policy = BarcodeReadPolicy()
        repeat(10) { assertNull(policy.accept(listOf(ean, ean), it * 200L)) }
    }

    @Test fun `even an invalid candidate beside a valid one prevents picking the first`() {
        val policy = BarcodeReadPolicy()
        repeat(10) { assertNull(policy.accept(listOf(ean, ean.copy(rawValue = "incorrect")), it * 200L)) }
    }

    @Test fun `repeated timestamps and frames arriving backwards do not supply votes`() {
        val policy = BarcodeReadPolicy()
        repeat(10) { assertNull(read(policy, time = 1000)) }
        assertNull(read(policy, time = 900))
        assertNull(read(policy, time = 800))
        assertFreshSequence(policy, start = 1200)
    }

    @Test fun `a long interruption requires a fresh confirmation sequence`() {
        val policy = BarcodeReadPolicy()
        read(policy, time = 0)
        read(policy, time = 200)
        assertFreshSequence(policy, start = 1500)
    }

    @Test fun `scanner failures and lifecycle resets discard earlier votes`() {
        val policy = BarcodeReadPolicy()
        for (time in listOf(0L, 200L, 400L, 600L)) read(policy, time = time)
        policy.reset()
        assertEquals(0, policy.consecutiveFrames)
        assertFreshSequence(policy, start = 800)
    }

    @Test fun `changing short fragments do not replace a stable full barcode`() {
        val policy = BarcodeReadPolicy()
        // The user's illustrative 9788533304005 has an invalid EAN check digit.
        // Use valid 9788533304000 without silently changing an actual scan.
        val full = BarcodeReading("9788533304000", Barcode.FORMAT_EAN_13)
        for ((index, fragment) in listOf("308863", "883330", "040005", "853330").withIndex()) {
            assertNull(read(policy, shortCode.copy(rawValue = fragment), index * 200L))
        }
        assertNull(read(policy, full, 800))
        assertNull(read(policy, shortCode, 1000))
        assertFreshSequence(policy, full, 1200)
    }

    @Test fun `a genuine stable short code is accepted without a product length restriction`() {
        for (example in listOf(shortCode, BarcodeReading("12", Barcode.FORMAT_ITF),
            BarcodeReading("7", Barcode.FORMAT_CODE_128), BarcodeReading("000123", Barcode.FORMAT_CODE_39),
            BarcodeReading("A1234B", Barcode.FORMAT_CODABAR))) {
            assertFreshSequence(BarcodeReadPolicy(), example)
        }
    }

    @Test fun `EAN UPC and ITF14 keep their original length and leading zeros`() {
        for (example in listOf(ean, otherEan, BarcodeReading("96385074", Barcode.FORMAT_EAN_8),
            BarcodeReading("042100005264", Barcode.FORMAT_UPC_A),
            BarcodeReading("0042100005264", Barcode.FORMAT_EAN_13),
            BarcodeReading("04252614", Barcode.FORMAT_UPC_E),
            BarcodeReading("10012345000017", Barcode.FORMAT_ITF))) {
            assertFreshSequence(BarcodeReadPolicy(), example)
        }
    }

    @Test fun `mandatory EAN and UPC checksums are validated without correcting the value`() {
        for (example in listOf(BarcodeReading("9788533304005", Barcode.FORMAT_EAN_13),
            BarcodeReading("96385075", Barcode.FORMAT_EAN_8),
            BarcodeReading("042100005265", Barcode.FORMAT_UPC_A),
            BarcodeReading("04252615", Barcode.FORMAT_UPC_E))) {
            val policy = BarcodeReadPolicy()
            repeat(10) { assertNull(read(policy, example, it * 200L)) }
        }
    }

    @Test fun `generic ITF is not forced to carry a GTIN14 checksum`() {
        assertFreshSequence(BarcodeReadPolicy(), BarcodeReading("10012345000018", Barcode.FORMAT_ITF))
    }

    @Test fun `UPC E uses expanded UPC A checksum rather than the EAN8 algorithm`() {
        assertTrue(validBarcodeReading(BarcodeReading("04252614", Barcode.FORMAT_UPC_E)))
        assertFalse(validBarcodeReading(BarcodeReading("04252614", Barcode.FORMAT_EAN_8)))
        assertFalse(validBarcodeReading(BarcodeReading("24252614", Barcode.FORMAT_UPC_E)))
        assertFalse(validBarcodeReading(BarcodeReading("425261", Barcode.FORMAT_UPC_E)))
    }

    @Test fun `truncated nonnumeric whitespace and absent commercial values are invalid`() {
        for (raw in listOf(null, "", " ", "400638133393", "04006381333931", "400638133393A",
            "４006381333931", " 4006381333931", "4006381333931 ", "4006381333931\n")) {
            assertFalse(validBarcodeReading(BarcodeReading(raw, Barcode.FORMAT_EAN_13)))
        }
    }

    @Test fun `invalid data in the middle restarts confirmation`() {
        val policy = BarcodeReadPolicy()
        for (time in listOf(0L, 200L, 400L, 600L)) read(policy, time = time)
        assertNull(read(policy, ean.copy(rawValue = "4006381333932"), 800))
        assertFreshSequence(policy, start = 1000)
    }

    @Test fun `same text read as another format must establish its own sequence`() {
        val policy = BarcodeReadPolicy()
        for (time in listOf(0L, 200L, 400L, 600L)) read(policy, shortCode, time)
        assertFreshSequence(policy, shortCode.copy(format = Barcode.FORMAT_CODE_128), 800)
    }

    @Test fun `unsupported types control characters and oversized labels remain invalid`() {
        assertFalse(validBarcodeReading(BarcodeReading("4006381333931", Barcode.FORMAT_QR_CODE)))
        for (code in listOf("", " ", "A".repeat(81), "ABC\n123", "ABC\u0000123")) {
            assertFalse(validBarcodeReading(BarcodeReading(code, Barcode.FORMAT_CODE_128)))
        }
    }
}

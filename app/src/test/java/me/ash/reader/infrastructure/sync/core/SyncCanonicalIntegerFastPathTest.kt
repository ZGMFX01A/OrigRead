package me.ash.reader.infrastructure.sync.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncCanonicalIntegerFastPathTest {
    @Test fun protocolIntegersKeepTheExactExistingBytes() {
        val values = listOf(0L, 1L, -1L, 42L, 1_790_956_537_793L, -1_790_956_537_793L,
            9_007_199_254_740_991L, -9_007_199_254_740_991L)
        for (value in values) for (raw in listOf(value.toString(), "$value.0", "${value}e0")) {
            assertEquals(legacyNumber(raw), SyncOperationCanonicalizer.canonicalJson(raw))
        }
        assertEquals("0", SyncOperationCanonicalizer.canonicalJson("-0.0"))
    }

    @Test fun deterministicSafeIntegerAndBinary64DifferentialCorpus() {
        val random = Random(0x5211)
        repeat(2048) {
            val value = random.nextLong() % 9_007_199_254_740_991L
            val raw = value.toString()
            assertEquals(raw, legacyNumber(raw))
            assertEquals(legacyNumber(raw), SyncOperationCanonicalizer.canonicalJson(raw))
        }
        val boundary = listOf("1.5", "-1.5", "0.000001", "1e-7", "1e20", "1e21",
            "9007199254740992", "9007199254740993", "333333333.33333329", "5e-324", "1.7976931348623157e308")
        for (raw in boundary) assertEquals(legacyNumber(raw), SyncOperationCanonicalizer.canonicalJson(raw))
        repeat(256) {
            val value = Double.fromBits(random.nextLong())
            if (value.isFinite()) {
                val raw = value.toString()
                assertEquals(legacyNumber(raw), SyncOperationCanonicalizer.canonicalJson(raw))
            }
        }
    }

    // Frozen pre-optimization implementation: changes to the production branch must not alter wire bytes.
    private fun legacyNumber(raw: String): String {
        val value = raw.toDouble()
        require(value.isFinite())
        if (value == 0.0) return "0"
        val exact = BigDecimal(value)
        val shortest = (1..17).firstNotNullOf { precision ->
            exact.round(MathContext(precision, RoundingMode.HALF_EVEN)).takeIf { it.toDouble() == value }
        }.stripTrailingZeros()
        val magnitude = kotlin.math.abs(value)
        if (magnitude >= 1e-6 && magnitude < 1e21) return shortest.toPlainString()
        val digits = shortest.unscaledValue().abs().toString()
        val exponent = digits.length - shortest.scale() - 1
        val fraction = if (digits.length == 1) digits else digits.first() + "." + digits.drop(1)
        return (if (value < 0) "-" else "") + fraction + "e" + (if (exponent >= 0) "+" else "") + exponent
    }
}

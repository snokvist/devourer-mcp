package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.RxQuality

/**
 * The fused RX sensor and the thermal meter.
 *
 * Both are the honest-absence kind: a backend that does not wire them must say
 * so rather than return an all-invalid snapshot that reads as a real
 * NO_SIGNAL or a cold chip. The fake carries the same capability split the
 * bridge does, so that distinction is exercised offline.
 */
class RxQualityAndThermalTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))
    private fun mediatek() = FakeRadios(listOf(FakeRadios.mediatek(2)))

    @Test
    fun `a non-realtek reports no fused feed rather than a fabricated NO_SIGNAL`() = runTest {
        val q = mediatek().rxQuality(2)
        assertFalse(q.supported)
        assertNotNull(q.why)
    }

    @Test
    fun `a realtek reports the fused verdict and its sensor fields`() = runTest {
        val q = realtek().rxQuality(1)
        assertTrue(q.supported)
        assertTrue(q.valid)
        assertEquals("HEALTHY", q.verdict)
        assertTrue(q.snrValid)
        assertTrue(q.evmValid)
    }

    @Test
    fun `an injected window is what comes back, not the default`() = runTest {
        val radios = realtek()
        radios.quality[1] = RxQuality(
            session = 1, supported = true, valid = true, frames = 3,
            rssiMeanDbm = -80, snrMeanDb = 4.0, snrValid = true,
            verdict = "WEAK", label = "WEAK", cause = "low SNR", fix = "closer",
        )

        val q = radios.rxQuality(1)

        assertEquals(3, q.frames)
        assertEquals(-80, q.rssiMeanDbm)
        assertEquals("WEAK", q.verdict)
    }

    @Test
    fun `a non-realtek reports no thermal meter rather than a cold chip`() = runTest {
        val t = mediatek().thermal(2)
        assertFalse(t.supported)
        assertNotNull(t.why)
    }

    @Test
    fun `a realtek reports the meter with a bucket, not a temperature`() = runTest {
        val t = realtek().thermal(1)
        assertTrue(t.supported)
        assertEquals("cool", t.bucket)
        assertTrue(t.valid)
    }
}

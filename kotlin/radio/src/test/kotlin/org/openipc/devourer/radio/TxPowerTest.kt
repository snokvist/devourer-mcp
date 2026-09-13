package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec

/**
 * The runtime TX-power vertical slice: offset, flat-index override and reapply.
 *
 * The point of these is the honesty the contract demands. An index/offset model
 * is not dBm, and `step_measured=false` means the slope is documentation rather
 * than measurement; a power sweep built on it must say so. The fake carries the
 * same capability report the bridge does, so "backend has no knob" and "chip
 * is not up yet" stay distinct.
 */
class TxPowerTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))
    private fun mediatek() = FakeRadios(listOf(FakeRadios.mediatek(2)))

    @Test
    fun `a backend with no TX-power knobs says so rather than reporting zeroes`() = runTest {
        val power = mediatek().txPower(2)
        assertFalse(power.supported)
        assertNotNull(power.why)
        assertNull(power.flatIndex)
    }

    @Test
    fun `setting TX power on a backend without the knobs is refused`() = runTest {
        assertFailsWith<CapabilityException> {
            mediatek().setTxPower(2, offsetQdb = 4)
        }
    }

    @Test
    fun `the caps travel with the state and validity follows bring-up`() = runTest {
        val radios = realtek()
        val before = radios.txPower(1)
        assertTrue(before.supported)
        assertEquals(63, before.indexMax)
        assertEquals(2, before.stepQdb)
        assertFalse(before.stepMeasured)
        // Nothing is applied yet because the baseband is not up.
        assertFalse(before.valid)

        radios.startMonitor(1, ChannelSpec(6))
        assertTrue(radios.txPower(1).valid)
    }

    @Test
    fun `an offset is quantized to whole steps and reported as applied`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        val applied = radios.setTxPower(1, offsetQdb = 5)

        // step is 2 qdB, so 5 rounds to 6 qdB == 3 steps.
        assertEquals(6, applied.offsetQdb)
        assertEquals(3, applied.offsetSteps)
    }

    @Test
    fun `a flat index override composes and -1 clears it`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        assertEquals(40, radios.setTxPower(1, indexOverride = 40).flatIndex)
        assertEquals(-1, radios.setTxPower(1, indexOverride = -1).flatIndex)
    }

    @Test
    fun `an out-of-range offset or index is refused, not coerced`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        assertFailsWith<IllegalArgumentException> { radios.setTxPower(1, offsetQdb = 999) }
        assertFailsWith<IllegalArgumentException> { radios.setTxPower(1, indexOverride = 999) }
    }

    @Test
    fun `reapply needs the chip up, unlike a recorded knob change`() = runTest {
        val radios = realtek()

        // Setting a knob before bring-up is recorded, not an error.
        radios.setTxPower(1, offsetQdb = 4)

        assertFailsWith<IllegalStateException> { radios.setTxPower(1, reapply = true) }

        radios.startMonitor(1, ChannelSpec(6))
        val applied = radios.setTxPower(1, reapply = true)
        assertEquals(4, applied.offsetQdb)
    }

    @Test
    fun `a set with no knob named is refused rather than reported as success`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        assertFailsWith<IllegalArgumentException> { radios.setTxPower(1) }
    }
}

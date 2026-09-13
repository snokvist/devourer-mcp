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
 * The two hardware-verified knobs the bridge already had but the Kotlin side
 * could not reach: the receive-gain clamp and the split carrier-sense gates.
 *
 * The distinctions under test are the reason these are not just pass-throughs.
 * "This backend has no gain index" and "the baseband is not up yet" are
 * different facts; a clamp is bounded by the supported envelope, not guessed;
 * and disabling a gate is the same antisocial act as disabling carrier sense,
 * so it goes through the production [RadioSafety], not a copy.
 */
class RxGainAndCcaGatesTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))
    private fun mediatek() = FakeRadios(listOf(FakeRadios.mediatek(2)))

    @Test
    fun `a backend with no gain index says so rather than reporting a zero`() = runTest {
        val gain = mediatek().rxGain(2)
        assertFalse(gain.supported)
        assertNotNull(gain.why)
        assertNull(gain.index)
    }

    @Test
    fun `a realtek reports an envelope wider than the window in force`() = runTest {
        val gain = realtek().rxGain(1)
        assertTrue(gain.supported)
        assertTrue(gain.settable)
        assertEquals("igi", gain.indexName)
        // The caps envelope spans the clamp; the live range is the narrower
        // DIG window. Treating them as the same value is the mistake the
        // contract warns against.
        assertTrue(gain.indexMax > (gain.rangeMax ?: 0))
    }

    @Test
    fun `clamping moves the index and the live range, not the envelope`() = runTest {
        val radios = realtek()
        val before = radios.rxGain(1)

        val clamped = radios.clampRxGain(1, 0x24, 0x24)
        assertEquals(0x24, clamped.index)
        assertEquals(0x24, clamped.rangeMin)
        assertEquals(0x24, clamped.rangeMax)
        assertEquals(before.indexMin, clamped.indexMin)
        assertEquals(before.indexMax, clamped.indexMax)
        assertTrue(clamped.atMaximumGain.not(), "a pinned mid-range index is not maximum gain")
    }

    @Test
    fun `a range outside the envelope or inverted is refused, not coerced`() = runTest {
        val radios = realtek()
        assertFailsWith<IllegalArgumentException> { radios.clampRxGain(1, 0x10, 0x20) }
        assertFailsWith<IllegalArgumentException> { radios.clampRxGain(1, 0x2a, 0x24) }
    }

    @Test
    fun `a backend without the split reports the combined state and a reason`() = runTest {
        val gates = mediatek().ccaGates(2)
        assertFalse(gates.supported)
        assertNotNull(gates.why)
        assertNull(gates.primaryCcaDisabled)
        assertFalse(gates.ccaDisabled)
    }

    @Test
    fun `the split gates are unavailable before bring-up`() = runTest {
        val gates = realtek().ccaGates(1)
        assertFalse(gates.supported)
        assertTrue(gates.why!!.contains("not brought up"), gates.why)
    }

    @Test
    fun `setting one gate leaves the other alone`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        radios.setCcaGates(1, edccaDisabled = true, safety = SafetyLevel.EXPERIMENTAL)

        val gates = radios.ccaGates(1)
        assertTrue(gates.supported)
        assertEquals(true, gates.edccaDisabled)
        assertEquals(false, gates.primaryCcaDisabled)
        // The combined state has to follow, or a fully-deaf radio could read
        // as compliant.
        assertTrue(gates.ccaDisabled)
    }

    @Test
    fun `disabling a gate without the experimental level is refused and changes nothing`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        assertFailsWith<SafetyLevelException> {
            radios.setCcaGates(1, primaryCcaDisabled = true, safety = SafetyLevel.NORMAL)
        }

        assertEquals(false, radios.ccaGates(1).primaryCcaDisabled)
    }

    @Test
    fun `re-enabling a gate needs no safety level`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))
        radios.setCcaGates(1, edccaDisabled = true, safety = SafetyLevel.EXPERIMENTAL)

        val gates = radios.setCcaGates(1, edccaDisabled = false, safety = SafetyLevel.NORMAL)

        assertEquals(false, gates.edccaDisabled)
    }
}

package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.AmpduMode
import org.openipc.devourer.protocol.ChannelSpec

/**
 * The A-MPDU TX mode. The capability is genuinely unknown until a set attempt
 * settles it — the cleared state is byte-identical to the unwired default — so
 * the fake carries the same tri-state the bridge does.
 */
class AmpduTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))
    private fun mediatek() = FakeRadios(listOf(FakeRadios.mediatek(2)))

    @Test
    fun `a read before any set attempt reports capability unknown`() = runTest {
        val state = realtek().ampdu(1)
        assertEquals("unknown", state.capability)
        assertFalse(state.enabled)
    }

    @Test
    fun `a backend that does not wire it reports unsupported`() = runTest {
        assertEquals("unsupported", mediatek().ampdu(2).capability)
        assertFailsWith<CapabilityException> { mediatek().setAmpdu(2, AmpduMode()) }
    }

    @Test
    fun `setting enables and records the mode, clear disables it`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        val on = radios.setAmpdu(1, AmpduMode(tid = 3, maxNum = 8))
        assertTrue(on.enabled)
        assertEquals("supported", on.capability)
        assertEquals(3, on.tid)
        assertEquals(8, on.maxNum)

        assertFalse(radios.clearAmpdu(1).enabled)
        assertEquals("supported", radios.ampdu(1).capability)
    }

    @Test
    fun `setting needs a brought-up radio`() = runTest {
        assertFailsWith<IllegalStateException> { realtek().setAmpdu(1, AmpduMode()) }
    }

    @Test
    fun `an out-of-range mode is rejected before it reaches a radio`() {
        assertFailsWith<IllegalArgumentException> { AmpduMode(tid = 9) }
        assertFailsWith<IllegalArgumentException> { AmpduMode(maxNum = 0) }
        assertFailsWith<IllegalArgumentException> { AmpduMode(density = 8) }
    }
}

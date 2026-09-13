package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec

/**
 * The hardware ACK responder. Arming makes the radio answer a chosen address
 * on the air, so it goes through the same EXPERIMENTAL gate as carrier-sense
 * disable; clearing is always allowed. Capability-gated on the adapter's
 * feature report, not a chipset assumption.
 */
class AckResponderTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))
    private fun mediatek() = FakeRadios(listOf(FakeRadios.mediatek(2)))

    @Test
    fun `a backend without the responder says so rather than reporting unarmed`() = runTest {
        val ack = mediatek().ackResponder(2)
        assertFalse(ack.supported)
        assertNotNull(ack.why)
        assertFalse(ack.armed)
    }

    @Test
    fun `arming without the experimental level is refused`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        assertFailsWith<SafetyLevelException> {
            radios.setAckResponder(1, "02:00:00:00:00:01")
        }
        assertFalse(radios.ackResponder(1).armed)
    }

    @Test
    fun `arming with the level records the address, and clear disarms`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        val armed = radios.setAckResponder(1, "02:00:00:00:00:01", SafetyLevel.EXPERIMENTAL)
        assertTrue(armed.armed)
        assertEquals("02:00:00:00:00:01", armed.mac)
        assertEquals("02:00:00:00:00:01", radios.ackResponder(1).mac)

        val cleared = radios.clearAckResponder(1)
        assertFalse(cleared.armed)
    }

    @Test
    fun `arming needs a brought-up radio`() = runTest {
        assertFailsWith<IllegalStateException> {
            realtek().setAckResponder(1, "02:00:00:00:00:01", SafetyLevel.EXPERIMENTAL)
        }
    }

    @Test
    fun `a backend without the responder refuses to arm it`() = runTest {
        assertFailsWith<CapabilityException> {
            mediatek().setAckResponder(2, "02:00:00:00:00:01", SafetyLevel.EXPERIMENTAL)
        }
    }
}

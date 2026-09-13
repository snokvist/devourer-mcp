package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec

/**
 * The hardware beacon. `IRadio` has no beacon getter, so the state is the
 * bridge's record — and arming an autonomous transmitter is EXPERIMENTAL while
 * stopping it is not, so a cleanup path cannot be blocked.
 */
class BeaconTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))

    /** Four bytes is enough shape for the fake. */
    private val frame = "80000000"

    @Test
    fun `an unarmed beacon is reported inactive, not absent`() = runTest {
        val b = realtek().beacon(1)
        assertTrue(b.supported)
        assertFalse(b.active)
    }

    @Test
    fun `starting requires the experimental level`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))
        assertFailsWith<SafetyLevelException> {
            radios.startBeacon(1, frame, 100, SafetyLevel.NORMAL)
        }
    }

    @Test
    fun `starting arms the beacon and a read reflects it`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        val started = radios.startBeacon(1, frame, 100, SafetyLevel.EXPERIMENTAL)

        assertTrue(started.active)
        assertTrue(started.started == true)
        assertEquals(100, started.intervalTu)
        val read = radios.beacon(1)
        assertTrue(read.active)
        assertEquals(100, read.intervalTu)
    }

    @Test
    fun `updating needs an active beacon`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))
        assertFailsWith<IllegalStateException> { radios.updateBeaconPayload(1, frame) }
    }

    @Test
    fun `update swaps the payload and keeps the interval`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))
        radios.startBeacon(1, frame, 100, SafetyLevel.EXPERIMENTAL)

        val updated = radios.updateBeaconPayload(1, "80" + "00".repeat(23))

        assertEquals(100, updated.intervalTu)
        assertTrue(updated.updated == true)
        assertEquals(24, updated.mpduBytes)
    }

    @Test
    fun `stopping is allowed without the experimental level`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))
        radios.startBeacon(1, frame, 100, SafetyLevel.EXPERIMENTAL)

        val stopped = radios.stopBeacon(1)

        assertFalse(stopped.active)
        assertTrue(stopped.stopped == true)
        assertFalse(radios.beacon(1).active)
    }

    @Test
    fun `starting needs a brought-up radio`() = runTest {
        assertFailsWith<IllegalStateException> {
            realtek().startBeacon(1, frame, 100, SafetyLevel.EXPERIMENTAL)
        }
    }
}

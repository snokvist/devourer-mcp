package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.RxEnergy

/**
 * The coarse energy survey (M3): dwell each channel, read the chip's
 * frame-free counters, and name the one that looked clearest — then put the
 * radio back where it started.
 */
class SpectrumScanTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))

    private fun energy(session: Int, cca: Long, fa: Long) = RxEnergy(
        session = session,
        supported = true,
        validCounters = true,
        ccaOfdm = cca,
        ccaCck = 0,
        faOfdm = fa,
        faCck = 0,
    )

    @Test
    fun `a backend without frame-free energy reports no survey, not zeros`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.mediatek(2)))
        val sweep = SpectrumScanner(radios).scan(2, listOf(1, 6), dwellMs = 10)

        assertFalse(sweep.supported)
        assertNotNull(sweep.why)
        assertTrue(sweep.points.isEmpty())
    }

    @Test
    fun `the quietest channel is the one with the least channel-busy energy`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(1))
        radios.energyByChannel[1] = energy(1, cca = 900, fa = 100)
        radios.energyByChannel[6] = energy(1, cca = 5, fa = 1)
        radios.energyByChannel[11] = energy(1, cca = 400, fa = 50)

        val sweep = SpectrumScanner(radios).scan(1, listOf(1, 6, 11), dwellMs = 10)

        assertTrue(sweep.supported)
        assertEquals(listOf(1, 6, 11), sweep.points.map { it.channel })
        assertEquals(6, sweep.quietestChannel)
        assertEquals(5L, sweep.points.first { it.channel == 6 }.ccaTotal)
    }

    @Test
    fun `the scan leaves the radio on the channel it started from`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(1))
        radios.energyByChannel[1] = energy(1, cca = 900, fa = 0)
        radios.energyByChannel[6] = energy(1, cca = 5, fa = 0)

        SpectrumScanner(radios).scan(1, listOf(1, 6), dwellMs = 10)

        assertEquals(1, radios.describe(1).channel?.channel)
    }

    @Test
    fun `an empty or absurd scan is refused before any radio is touched`() = runTest {
        val scanners = SpectrumScanner(realtek())
        assertFailsWith<IllegalArgumentException> { scanners.scan(1, emptyList(), dwellMs = 10) }
        assertFailsWith<IllegalArgumentException> { scanners.scan(1, listOf(1), dwellMs = 0) }
        assertFailsWith<IllegalArgumentException> { scanners.scan(1, listOf(300), dwellMs = 10) }
    }
}

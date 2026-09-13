package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec

/**
 * The MAC TSF. A bare 0 reads as a timestamp, so "not running yet" is a
 * distinct `readable:false` rather than a zero.
 */
class TsfTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))

    @Test
    fun `a radio that is not up reports not readable, not tsf zero`() = runTest {
        val t = realtek().tsf(1)
        assertTrue(t.supported)
        assertFalse(t.readable)
        assertEquals(0, t.tsfUs)
        assertTrue(t.why != null)
    }

    @Test
    fun `a brought-up radio reports its TSF`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))
        radios.tsfBySession[1] = 12_345_678L

        val t = radios.tsf(1)

        assertTrue(t.readable)
        assertEquals(12_345_678L, t.tsfUs)
    }
}

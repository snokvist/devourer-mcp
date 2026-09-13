package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec

/**
 * The lean retune paths (M3): a same-band hop keeps the width, and a
 * bandwidth toggle keeps the channel. Both require a live radio — they are a
 * move *from* a tuned channel, not a first tune — and both stay available as
 * unconditional calls because devourer falls back to a full retune.
 */
class FastRetuneTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))

    @Test
    fun `a fast retune needs a brought-up radio, not a cold one`() = runTest {
        assertFailsWith<IllegalStateException> { realtek().fastRetune(1, 11) }
    }

    @Test
    fun `a same-band hop keeps the width and offset`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6, width = org.openipc.devourer.protocol.ChannelWidth.W40))

        val info = radios.fastRetune(1, 11)

        assertEquals(11, info.channel)
        assertEquals(40, info.width)
        assertTrue(info.fastRetune)
        assertEquals(11, radios.describe(1).channel?.channel)
        assertTrue(radios.calls.any { it.startsWith("fastRetune(1,ch=11)") })
    }

    @Test
    fun `a bandwidth toggle keeps the channel`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        val info = radios.fastBandwidth(1, 40)

        assertEquals(6, info.channel)
        assertEquals(40, info.width)
        assertEquals(40, radios.describe(1).channel?.width)
    }

    @Test
    fun `a width the adapter lacks is refused, not silently downgraded`() = runTest {
        val radios = realtek()
        radios.startMonitor(1, ChannelSpec(6))

        assertFailsWith<IllegalArgumentException> { radios.fastBandwidth(1, 160) }
    }
}

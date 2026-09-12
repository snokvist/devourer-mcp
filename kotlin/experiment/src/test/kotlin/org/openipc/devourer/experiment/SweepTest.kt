package org.openipc.devourer.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SweepTest {

    private val base = SweepPoint("6M", ChannelLabel("ch6"), 200, 1_000)

    @Test
    fun `an empty sweep is one point, the base`() {
        val points = Sweep().expand(base)
        assertEquals(1, points.size)
        assertEquals(base, points.single())
    }

    @Test
    fun `axes multiply`() {
        val points = Sweep(
            modes = listOf("6M", "MCS0/20", "MCS5/20"),
            channels = listOf("ch1", "ch6"),
        ).expand(base)
        assertEquals(6, points.size)
        assertEquals(6, points.distinct().size)
    }

    @Test
    fun `channel is the outermost axis so retuning happens once per group`() {
        // Retuning costs ~130ms on a Realtek. Interleaving channels would pay
        // that on every point instead of once per group.
        val points = Sweep(
            modes = listOf("6M", "MCS5/20"),
            channels = listOf("ch1", "ch6"),
        ).expand(base)
        assertEquals(
            listOf("ch1", "ch1", "ch6", "ch6"),
            points.map { it.channel.text },
        )
    }

    @Test
    fun `a sweep too large to interpret is refused before any radio is touched`() {
        val e = assertFailsWith<ExperimentException> {
            Sweep(
                modes = List(8) { "MCS$it/20" },
                channels = listOf("ch1", "ch6", "ch11"),
                frameBytes = listOf(64, 200, 800),
                intervalUs = listOf(500, 1_000),
            ).expand(base)
        }
        assertTrue("144 points" in e.message!!, e.message)
        assertTrue("max ${Sweep.MAX_POINTS}" in e.message!!)
    }

    @Test
    fun `a frame too small to carry a probe tag is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Sweep(frameBytes = listOf(8)).expand(base)
        }
    }

    @Test
    fun `a malformed channel fails at expansion, not mid-run`() {
        // Discovering a typo on point 9 of 12 wastes the whole run and leaves
        // it half comparable.
        assertFailsWith<IllegalArgumentException> {
            Sweep(channels = listOf("ch1", "not-a-channel")).expand(base)
        }
    }

    @Test
    fun `labels name the varying conditions`() {
        val p = SweepPoint("MCS5/20", ChannelLabel("ch36/80"), 800, 500)
        assertEquals("MCS5/20 @ch36/80 800B /500us", p.label)
        // The default spacing is left out: it is noise on every label.
        assertEquals("6M @ch6 200B", base.label)
    }

    @Test
    fun `axes reports only the dimensions that actually vary`() {
        assertEquals(emptyList(), Sweep(modes = listOf("6M")).axes)
        assertEquals(
            listOf("mode", "channel"),
            Sweep(modes = listOf("6M", "24M"), channels = listOf("ch1", "ch6")).axes,
        )
    }
}

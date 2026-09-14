package org.openipc.devourer.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExperimentSpecTest {

    private val base = SweepPoint("6M", ChannelLabel("ch6"), 200, 1_000)

    private fun spec(roles: Map<RadioRole, Int>) =
        ExperimentSpec(roles = roles, basePoint = base)

    @Test
    fun `a radio may not witness itself`() {
        val e = assertFailsWith<ExperimentException> {
            spec(mapOf(RadioRole.TX_PEER to 1, RadioRole.RX_PEER to 1))
        }
        assertTrue("cannot witness itself" in e.message!!, e.message)
    }

    @Test
    fun `a second witness may not be the transmitter either`() {
        val e = assertFailsWith<ExperimentException> {
            spec(
                mapOf(
                    RadioRole.TX_PEER to 1,
                    RadioRole.RX_PEER to 2,
                    RadioRole.MONITOR to 1,
                ),
            )
        }
        assertTrue("MONITOR" in e.message!!, e.message)
    }

    @Test
    fun `one adapter cannot fill two witness roles`() {
        // It would be counted twice, and two "independent" witnesses that are
        // the same radio is precisely the false corroboration this exists to
        // prevent.
        val e = assertFailsWith<ExperimentException> {
            spec(
                mapOf(
                    RadioRole.TX_PEER to 1,
                    RadioRole.RX_PEER to 2,
                    RadioRole.MONITOR to 2,
                ),
            )
        }
        assertTrue("two independent witnesses" in e.message!!, e.message)
    }

    @Test
    fun `a run with nothing transmitting is refused`() {
        assertFailsWith<ExperimentException> { spec(mapOf(RadioRole.RX_PEER to 2)) }
    }

    @Test
    fun `a run with nothing listening is refused`() {
        assertFailsWith<ExperimentException> { spec(mapOf(RadioRole.TX_PEER to 1)) }
    }

    @Test
    fun `a qos_tid outside the A-MPDU space is refused up front`() {
        val e = assertFailsWith<ExperimentException> {
            ExperimentSpec(
                roles = mapOf(RadioRole.TX_PEER to 1, RadioRole.RX_PEER to 2),
                basePoint = base,
                qosTid = 8,
            )
        }
        assertTrue("qos_tid" in e.message!!, e.message)
    }

    @Test
    fun `witnesses come back with RX_PEER first`() {
        val s = spec(
            mapOf(
                RadioRole.MONITOR_2 to 4,
                RadioRole.TX_PEER to 1,
                RadioRole.MONITOR to 3,
                RadioRole.RX_PEER to 2,
            ),
        )
        assertEquals(listOf(2, 3, 4), s.witnesses.map { it.second })
        assertEquals(1, s.transmitter)
    }
}

class ExperimentBoundsTest {

    @Test
    fun `a point that would outlive the whole run is refused`() {
        // Every field below is individually legal; the product is 27 hours,
        // and the run ceiling is only tested between points.
        val e = assertFailsWith<IllegalArgumentException> {
            ExperimentBounds(
                maxDurationMs = 60_000,
                framesPerPoint = 100_000,
                intervalUs = 1_000_000,
            )
        }
        assertTrue("one point would take" in e.message!!, e.message)
    }

    @Test
    fun `the per-point deadline is generous but finite`() {
        val b = ExperimentBounds(framesPerPoint = 200, intervalUs = 1_000, settleMs = 300)
        assertEquals(500, b.estimatedPointMs)
        assertTrue(b.pointTimeoutMs > b.estimatedPointMs)
        assertTrue(b.pointTimeoutMs <= b.maxDurationMs + 10_000)
    }
}

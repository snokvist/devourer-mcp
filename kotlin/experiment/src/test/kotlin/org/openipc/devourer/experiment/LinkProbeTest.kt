package org.openipc.devourer.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.radio.FakeRadios
import org.openipc.devourer.radio.SafetyLevel
import org.openipc.devourer.radio.SafetyLevelException
import org.openipc.devourer.radio.VerificationState

/**
 * The first offline tests of the experiment engine.
 *
 * These exist because of a structural fact, not a coverage target: until
 * `Radios` was an interface, a `LinkProbe` could not be constructed without an
 * adapter claimed on a shared bench, so the code that decides whether
 * something is TX_VERIFIED had never been executed by a test.
 */
class LinkProbeTest {

    private val tx = FakeRadios.realtek(session = 1)
    private val rx = FakeRadios.mediatek(session = 2)
    private val rx2 = FakeRadios.mediatek(session = 3, bus = 5, address = 7)

    private fun fake() = FakeRadios(listOf(tx, rx, rx2))

    private fun spec(
        roles: Map<RadioRole, Int> = mapOf(RadioRole.TX_PEER to 1, RadioRole.RX_PEER to 2),
        sweep: Sweep = Sweep(),
        carrierSense: Boolean = true,
        safety: SafetyLevel = SafetyLevel.NORMAL,
        bounds: ExperimentBounds = ExperimentBounds(framesPerPoint = 100, intervalUs = 100),
    ) = ExperimentSpec(
        roles = roles,
        sweep = sweep,
        bounds = bounds,
        basePoint = SweepPoint("6M", ChannelLabel("ch6"), 200, 100),
        carrierSense = carrierSense,
        safety = safety,
    )

    @Test
    fun `a burst nobody hears is FAILED, not a quiet success`() = runTest {
        val radios = fake()
        val result = LinkProbe(radios, backgroundScope).run(spec())

        assertEquals(VerificationState.FAILED, result.verification)
        assertTrue("NOT TX_VERIFIED" in result.conclusion, result.conclusion)
        assertEquals(0.0, result.points.single().deliveryRatio)
        // The TX path still accepted every frame. That is the whole point:
        // acceptance and transmission are different facts.
        assertEquals(100, result.points.single().txAccepted)
    }

    @Test
    fun `an independent witness hearing the burst is what grants TX_VERIFIED`() = runTest {
        val radios = fake()
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 93)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope).run(spec())

        assertEquals(VerificationState.TX_VERIFIED, result.verification)
        val point = result.points.single()
        assertEquals(93, point.framesReceived)
        assertEquals(0.93, point.deliveryRatio!!, 1e-9)
        // The witness records the strongest reporting chain, not a chain average.
        assertEquals(70.0, point.rssiMean)
        assertTrue("TX_VERIFIED" in result.conclusion)
    }

    @Test
    fun `each sweep point is measured separately`() = runTest {
        val radios = fake()
        val perMode = mapOf("6M" to 100, "MCS0/20" to 50, "MCS7/20" to 3)
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = perMode.getValue(p.mode))
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope)
            .run(spec(sweep = Sweep(modes = perMode.keys.toList())))

        assertEquals(listOf(100, 50, 3), result.points.map { it.framesReceived })
        // A point's counters must not leak into the next one.
        assertEquals(listOf(1.0, 0.5, 0.03), result.points.map { it.deliveryRatio })
        assertTrue("MCS0/20" !in result.conclusion, "the best point is the one reported")
    }

    @Test
    fun `sweeping channels retunes once per channel, not once per point`() = runTest {
        val radios = fake()
        LinkProbe(radios, backgroundScope).run(
            spec(sweep = Sweep(modes = listOf("6M", "24M"), channels = listOf("ch1", "ch6"))),
        )
        assertEquals(2, radios.calls.count { it.startsWith("retune(1,") })
        assertEquals(2, radios.calls.count { it.startsWith("startMonitor(2,") })
    }

    @Test
    fun `two witnesses agreeing exactly localises the loss to the transmitter`() = runTest {
        // This is the shape of the real finding on this bench: two MT7612U
        // receivers heard 3/3, 7/7 and 13/13 of a Realtek's bursts, which is
        // what ruled the receiver out.
        val radios = fake()
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 7)
            radios.deliverTo(p, to = 3, frames = 7)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope).run(
            spec(
                roles = mapOf(
                    RadioRole.TX_PEER to 1,
                    RadioRole.RX_PEER to 2,
                    RadioRole.MONITOR to 3,
                ),
            ),
        )

        val point = result.points.single()
        assertEquals(setOf("RX_PEER", "MONITOR"), point.witnesses.keys)
        assertEquals(7, point.witnesses.getValue("MONITOR").framesReceived)
        assertTrue(
            result.caveats.any { "loss is at the transmitter" in it },
            result.caveats.toString(),
        )
    }

    @Test
    fun `witnesses disagreeing says the opposite, and says why`() = runTest {
        val radios = fake()
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 80)
            radios.deliverTo(p, to = 3, frames = 20)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope).run(
            spec(
                roles = mapOf(
                    RadioRole.TX_PEER to 1,
                    RadioRole.RX_PEER to 2,
                    RadioRole.MONITOR to 3,
                ),
            ),
        )

        assertTrue(
            result.caveats.any { "disagreed by up to 60 frames" in it },
            result.caveats.toString(),
        )
    }

    @Test
    fun `disabling carrier sense needs the experimental level`() = runTest {
        val radios = fake()
        assertFailsWith<SafetyLevelException> {
            LinkProbe(radios, backgroundScope).run(spec(carrierSense = false))
        }
        assertFalse(radios.carrierSenseDisabled(1), "nothing should have been changed")
    }

    @Test
    fun `carrier sense is restored even when the run throws`() = runTest {
        val radios = fake()
        radios.onProbe = { error("the bridge fell over") }

        assertFailsWith<IllegalStateException> {
            LinkProbe(radios, backgroundScope).run(
                spec(carrierSense = false, safety = SafetyLevel.EXPERIMENTAL),
            )
        }

        assertFalse(
            radios.carrierSenseDisabled(1),
            "a radio left transmitting deaf is not a state to walk away from",
        )
        assertFalse(radios.isMonitoring(2), "the witness's monitor should be stopped")
        assertEquals(
            listOf("setCarrierSense(1,enabled=false,EXPERIMENTAL)", "setCarrierSense(1,enabled=true,NORMAL)"),
            radios.calls.filter { it.startsWith("setCarrierSense") },
        )
    }

    @Test
    fun `a run with carrier sense off says so in the result and in a caveat`() = runTest {
        val radios = fake()
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 95)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope).run(
            spec(carrierSense = false, safety = SafetyLevel.EXPERIMENTAL),
        )

        assertFalse(result.carrierSenseEnabled)
        assertTrue(result.caveats.any { "not comparable to a run with carrier sense on" in it })
    }

    @Test
    fun `a transmit call that never returns is bounded, not waited on forever`() = runTest {
        // A wedged adapter is a real failure mode here: an undrained MT7612U
        // receiver stops answering below the USB level.
        val radios = fake()
        radios.onProbe = { delay(Long.MAX_VALUE / 2); FakeRadios.TxOutcome(0) }

        val result = LinkProbe(radios, backgroundScope).run(spec())

        assertTrue(result.truncated)
        assertTrue(result.caveats.any { "did not complete within" in it }, result.caveats.toString())
        val timedOut = result.points.single()
        assertNotNull(timedOut.note)
        // Absent, not zero. A timed-out point delivered no evidence at all,
        // and reporting 0% would put a measured-looking dot on a chart.
        assertNull(timedOut.deliveryRatio)
        assertNull(timedOut.framesReceived)
        assertEquals(VerificationState.FAILED, result.verification)
        assertFalse(radios.isMonitoring(2), "cleanup runs after a timeout too")
    }

    @Test
    fun `a witness that cannot tune the swept channel is refused before transmitting`() = runTest {
        // The MediaTek fake has no 5MHz width. Discovering that on point 9 of
        // 12 would waste the run and leave it half comparable.
        val radios = fake()
        assertFailsWith<org.openipc.devourer.radio.CapabilityException> {
            LinkProbe(radios, backgroundScope).run(
                spec(sweep = Sweep(channels = listOf("ch6", "ch6/5"))),
            )
        }
        assertEquals(0, radios.calls.count { it.startsWith("sendProbe") })
    }

    @Test
    fun `duplicates are counted rather than inflating delivery`() = runTest {
        val radios = fake()
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 50, duplicateEvery = 5)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val point = LinkProbe(radios, backgroundScope).run(spec()).points.single()

        assertEquals(50, point.framesReceived)
        assertEquals(10, point.duplicates)
        assertTrue(
            LinkProbe(radios, backgroundScope).run(spec()).caveats.isNotEmpty(),
        )
    }

    @Test
    fun `a monitor left running on a witness does not fail the run`() = runTest {
        // The bridge refuses monitor.start on a session already monitoring.
        // An experiment that depends on what happened before it started is
        // the kind that only fails when it matters.
        val radios = fake()
        radios.startMonitor(2, ChannelSpec(11))
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 40)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope).run(spec())

        assertEquals(VerificationState.TX_VERIFIED, result.verification)
        assertEquals(40, result.points.single().framesReceived)
    }

    @Test
    fun `each channel carries the transmitter's own view of it`() = runTest {
        // The radio that decides not to transmit is the transmitter, so its
        // PHY's view is the one that explains a deferral. A witness's frame
        // count cannot see energy that never becomes a frame.
        val radios = fake()
        radios.energy[1] = RxEnergy(
            supported = true, validCounters = true, ccaOfdm = 4200, faOfdm = 310,
            validIgi = true, igi = 34,
        )

        val result = LinkProbe(radios, backgroundScope)
            .run(spec(sweep = Sweep(channels = listOf("ch1", "ch6"))))

        assertEquals(2, result.points.size)
        result.points.forEach { p ->
            val e = assertNotNull(p.channelEnergy, "no energy for ${p.point}")
            assertEquals(4200L, e.ccaOfdm)
            assertEquals(34, e.igi)
        }
        // Two reads per channel: one to reset the counters, one to measure.
        assertEquals(4, radios.calls.count { it.startsWith("rxEnergy") })
    }

    @Test
    fun `a transmitter that cannot measure energy reports absent, not quiet`() = runTest {
        // The MediaTek has no such counter. A zero here would be a fabricated
        // reading, and it would read as "the channel was silent".
        val radios = fake()
        val result = LinkProbe(radios, backgroundScope).run(
            spec(roles = mapOf(RadioRole.TX_PEER to 2, RadioRole.RX_PEER to 1)),
        )

        val energy = assertNotNull(result.points.single().channelEnergy)
        assertFalse(energy.supported)
        assertNull(energy.ccaOfdm)
        // And it does not pay the dwell: one read, then it gives up.
        assertEquals(1, radios.calls.count { it.startsWith("rxEnergy") })
    }

    @Test
    fun `a power sweep sets each point's offset and restores the pre-run value`() = runTest {
        val radios = fake()
        val result = LinkProbe(radios, backgroundScope).run(
            spec(sweep = Sweep(modes = listOf("6M"), powerOffsetQdb = listOf(-16, 16))),
        )

        assertEquals(2, result.points.size)
        assertEquals(listOf(-16, 16), result.points.map { it.powerOffsetQdb })
        assertEquals(listOf(-16, 16), result.points.map { it.powerAppliedQdb })

        val sets = radios.calls.filter { it.startsWith("setTxPower(1,") }
        assertTrue(sets.any { "offset=-16" in it }, sets.toString())
        assertTrue(sets.any { "offset=16" in it }, sets.toString())
        // The run leaves the transmitter where it found it, not on the last point.
        assertTrue(sets.last().contains("offset=0"), sets.toString())
        assertTrue(result.caveats.any { "RELATIVE" in it }, result.caveats.toString())
    }

    @Test
    fun `a request to sweep power on a radio without the knobs is refused up front`() = runTest {
        val radios = fake()
        // The MediaTek fixture cannot move its power, so a power sweep on it
        // must fail before any burst rather than silently measuring one level.
        assertFailsWith<ExperimentException> {
            LinkProbe(radios, backgroundScope).run(
                spec(
                    roles = mapOf(RadioRole.TX_PEER to 2, RadioRole.RX_PEER to 1),
                    sweep = Sweep(modes = listOf("6M"), powerOffsetQdb = listOf(-16, 16)),
                ),
            )
        }
    }

    @Test
    fun `the simple two-radio form still works`() = runTest {
        val radios = fake()
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = p.count)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val result = LinkProbe(radios, backgroundScope).simple(
            txSession = 1,
            rxSession = 2,
            channel = ChannelSpec(6),
            modes = listOf("6M", "24M"),
            bounds = ExperimentBounds(framesPerPoint = 10, intervalUs = 100),
        )

        assertEquals(2, result.points.size)
        assertEquals(VerificationState.TX_VERIFIED, result.verification)
    }
}

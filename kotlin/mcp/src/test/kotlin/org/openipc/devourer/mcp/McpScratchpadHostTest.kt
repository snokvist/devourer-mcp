package org.openipc.devourer.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.capture.CaptureService
import org.openipc.devourer.experiment.ExperimentBounds
import org.openipc.devourer.experiment.ExperimentResult
import org.openipc.devourer.experiment.ExperimentRunner
import org.openipc.devourer.experiment.PointResult
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.radio.FakeRadios
import org.openipc.devourer.radio.VerificationState

/**
 * What a scratchpad can read, and what it is told when there is nothing there.
 *
 * The distinctions here are the point: a mistyped capture id, a capture that
 * has never seen a frame, and a quiet window are three different faults with
 * three different fixes, and collapsing them into one null is how a live view
 * comes to show a confident zero.
 */
class McpScratchpadHostTest {

    private fun host(
        radios: FakeRadios,
        captures: CaptureService,
        experiments: ExperimentRunner = ExperimentRunner(CoroutineScope(SupervisorJob())),
    ) = McpScratchpadHost(radios, captures, experiments)

    @Test
    fun `a mistyped capture id is an error, not an empty reading`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1)))
        val captures = CaptureService(radios, backgroundScope)
        captures.start(1, ChannelSpec(6))

        val e = assertFailsWith<IllegalStateException> {
            host(radios, captures).captureMetric("cap-typo", "frames", 1_000, null, null)
        }
        assertTrue("running: cap-1" in e.message!!, e.message)
    }

    @Test
    fun `a capture that has never heard anything is an error, not a quiet window`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1)))
        val captures = CaptureService(radios, backgroundScope)
        val c = captures.start(1, ChannelSpec(6))

        val e = assertFailsWith<IllegalStateException> {
            host(radios, captures).captureMetric(c.id, "frames", 1_000, null, null)
        }
        assertTrue("check the radio is monitoring" in e.message!!, e.message)
    }

    @Test
    fun `a window with nothing in it reads as null, which is not zero`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1)))
        val captures = CaptureService(radios, backgroundScope)
        val c = captures.start(1, ChannelSpec(6))
        radios.awaitCollector(1)
        // Dated ten minutes ago: inside a wide window, outside a recent one.
        radios.injectAmbient(1, 5, atEpochMs = System.currentTimeMillis() - 600_000)
        testScheduler.runCurrent()

        val h = host(radios, captures)
        assertEquals(5.0, h.captureMetric(c.id, "frames", 3_600_000, null, null))
        assertNull(h.captureMetric(c.id, "frames", 10_000, null, null))
    }

    @Test
    fun `an unknown metric name reads as null rather than inventing a number`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1)))
        val captures = CaptureService(radios, backgroundScope)
        val c = captures.start(1, ChannelSpec(6))
        radios.awaitCollector(1)
        radios.injectAmbient(1, 5)
        testScheduler.runCurrent()

        assertNull(
            host(radios, captures)
                .captureMetric(c.id, "no_such_metric", Long.MAX_VALUE / 2, null, null),
        )
    }

    @Test
    fun `a radio metric the backend does not implement reads as null, not as zero`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.mediatek(2)))
        val captures = CaptureService(radios, backgroundScope)
        val h = host(radios, captures)

        assertEquals(0.0, h.radioMetric(2, "tx_submitted"))
        assertNull(h.radioMetric(2, "no_such_metric"))
        // A session that does not exist must not read as a measured zero.
        assertNull(h.radioMetric(99, "monitor_frames"))
    }

    private fun finished(id: String, points: List<PointResult>) = ExperimentResult(
        id = id,
        kind = "link_probe",
        startedAtEpochMs = 0,
        durationMs = 1,
        roles = emptyMap(),
        channel = "ch6",
        bounds = ExperimentBounds(),
        points = points,
        verification = VerificationState.TX_VERIFIED,
        conclusion = "ok",
    )

    private fun point(
        label: String,
        delivery: Double?,
        frames: Int?,
        accepted: Int = 0,
    ) = PointResult(
        point = label,
        framesSent = 100,
        framesReceived = frames,
        deliveryRatio = delivery,
        txAccepted = accepted,
    )

    @Test
    fun `an experiment metric reads one point, a reduction, or null`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1)))
        val captures = CaptureService(radios, backgroundScope)
        val runner = ExperimentRunner(backgroundScope)
        runner.start("exp-1", "link_probe", totalPoints = 3) {
            finished(
                "exp-1",
                listOf(
                    point("6M", 1.0, 100, accepted = 100),
                    point("MCS7/20", 0.5, 50, accepted = 50),
                    // An unmeasured point: frames_received and delivery stay
                    // null, and must not read as zeros.
                    point("MCS9/20", null, null),
                ),
            )
        }
        runner.await("exp-1")

        val h = host(radios, captures, runner)
        assertEquals(0.5, h.experimentMetric("exp-1", "delivery_ratio", "MCS7/20", null, null))
        assertEquals(50.0, h.experimentMetric("exp-1", "frames_received", null, 1, null))
        // mean over measured points only: the null point is not a zero.
        assertEquals(0.75, h.experimentMetric("exp-1", "delivery_ratio", null, null, "mean"))
        // count is the number of points that produced a value, the same set
        // every other aggregate reduces over.
        assertEquals(2.0, h.experimentMetric("exp-1", "delivery_ratio", null, null, "count"))
        assertNull(h.experimentMetric("exp-1", "delivery_ratio", "not-a-point", null, null))
        assertNull(h.experimentMetric("exp-1", "delivery_ratio", "MCS9/20", null, null))
        assertNull(h.experimentMetric("exp-1", "no_such_metric", null, null, null))

        // A counter metric on the unmeasured point must be absent too: its
        // PointResult counters hold 0 defaults, and reporting one would
        // fabricate a measurement. Over the measured points, count is 2.
        assertNull(h.experimentMetric("exp-1", "tx_accepted", "MCS9/20", null, null))
        assertEquals(2.0, h.experimentMetric("exp-1", "tx_accepted", null, null, "count"))
        assertEquals(150.0, h.experimentMetric("exp-1", "tx_accepted", null, null, "sum"))
    }

    @Test
    fun `a running experiment has no result to read, and an unknown id is a fault`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1)))
        val captures = CaptureService(radios, backgroundScope)
        val runner = ExperimentRunner(backgroundScope)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        runner.start("exp-run", "link_probe", totalPoints = 1) { sink ->
            sink.startingPoint("6M")
            gate.await()
            finished("exp-run", listOf(point("6M", 1.0, 100)))
        }
        testScheduler.runCurrent()

        val h = host(radios, captures, runner)
        // In flight: nothing published yet, so null rather than a stale or
        // invented value.
        assertNull(h.experimentMetric("exp-run", "delivery_ratio", null, null, null))

        // Never ran in this session: a fault that names what did run.
        val e = assertFailsWith<IllegalStateException> {
            h.experimentMetric("exp-typo", "delivery_ratio", null, null, null)
        }
        assertTrue("no experiment 'exp-typo'" in e.message!!, e.message)

        gate.complete(Unit)
        testScheduler.runCurrent()
        runner.await("exp-run")
        assertEquals(1.0, h.experimentMetric("exp-run", "delivery_ratio", null, null, null))
    }
}

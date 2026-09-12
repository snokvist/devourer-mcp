package org.openipc.devourer.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.radio.FakeRadios
import org.openipc.devourer.radio.VerificationState

class ExperimentRunnerTest {

    private fun result(id: String) = ExperimentResult(
        id = id,
        kind = "test",
        startedAtEpochMs = 0,
        durationMs = 1,
        roles = emptyMap(),
        channel = "ch6",
        bounds = ExperimentBounds(),
        points = emptyList(),
        verification = VerificationState.DETECTED,
        conclusion = "nothing",
    )

    @Test
    fun `progress advances point by point`() = runTest {
        val runner = ExperimentRunner(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        runner.start("r1", "test", totalPoints = 3) { sink ->
            sink.startingPoint("6M")
            sink.finishedPoint()
            sink.startingPoint("24M")
            gate.await()
            sink.finishedPoint()
            result("r1")
        }

        testScheduler.runCurrent()
        val mid = runner.progress("r1")!!
        assertEquals(ExperimentRunner.Phase.RUNNING, mid.phase)
        assertEquals(1, mid.completedPoints)
        assertEquals(3, mid.totalPoints)
        assertEquals("24M", mid.currentPoint)

        gate.complete(Unit)
        testScheduler.runCurrent()
        runner.await("r1")
        assertEquals(ExperimentRunner.Phase.DONE, runner.progress("r1")!!.phase)
        assertNull(runner.progress("r1")!!.currentPoint)
    }

    @Test
    fun `a cancelled run is recorded as cancelled, with the reason`() = runTest {
        val runner = ExperimentRunner(backgroundScope)
        runner.start("r2", "test", 1) { delay(Long.MAX_VALUE / 2); result("r2") }
        testScheduler.runCurrent()

        assertTrue(runner.cancel("r2", "operator pressed stop"))
        assertFailsWith<CancellationException> { runner.await("r2") }
        // The run's own catch/finally still has to be dispatched before its
        // phase is settled — cancelling a Deferred is not the same as the
        // cancelled body having unwound.
        testScheduler.runCurrent()

        val p = runner.progress("r2")!!
        assertEquals(ExperimentRunner.Phase.CANCELLED, p.phase)
        assertEquals("operator pressed stop", p.message)
        // Cancelling twice is not an error, but it is not a second cancel.
        assertFalse(runner.cancel("r2"))
    }

    @Test
    fun `a failing run keeps the reason it failed`() = runTest {
        val runner = ExperimentRunner(backgroundScope)
        runner.start("r3", "test", 1) { error("the bridge fell over") }
        testScheduler.runCurrent()
        assertFailsWith<IllegalStateException> { runner.await("r3") }

        val p = runner.progress("r3")!!
        assertEquals(ExperimentRunner.Phase.FAILED, p.phase)
        assertTrue("the bridge fell over" in p.message!!, p.message)
        assertNull(runner.result("r3"), "a failed run has no result to read")
    }

    @Test
    fun `cancelling an unknown run says so rather than pretending`() = runTest {
        val runner = ExperimentRunner(backgroundScope)
        assertFalse(runner.cancel("nope"))
        assertNull(runner.progress("nope"))
    }

    @Test
    fun `cancellation reaches a real experiment and still restores the radio`() = runTest {
        // The property that matters: a stopped run does not leave a radio
        // transmitting without listening.
        val radios = FakeRadios(listOf(FakeRadios.realtek(1), FakeRadios.mediatek(2)))
        radios.onProbe = { delay(Long.MAX_VALUE / 2); FakeRadios.TxOutcome(0) }
        val runner = ExperimentRunner(backgroundScope)
        val probe = LinkProbe(radios, backgroundScope)
        val spec = ExperimentSpec(
            roles = mapOf(RadioRole.TX_PEER to 1, RadioRole.RX_PEER to 2),
            bounds = ExperimentBounds(framesPerPoint = 10, intervalUs = 100),
            basePoint = SweepPoint("6M", ChannelLabel.of(ChannelSpec(6)), 200, 100),
            carrierSense = false,
            safety = org.openipc.devourer.radio.SafetyLevel.EXPERIMENTAL,
        )

        runner.start("r4", "link_probe", 1) { sink -> probe.run(spec, sink) }
        testScheduler.runCurrent()
        assertTrue(radios.carrierSenseDisabled(1), "the run should have disabled it")

        runner.cancel("r4")
        assertFailsWith<CancellationException> { runner.await("r4") }
        testScheduler.runCurrent()
        testScheduler.runCurrent()

        assertFalse(radios.carrierSenseDisabled(1), "cancellation must not skip the restore")
        assertFalse(radios.isMonitoring(2))
    }

    @Test
    fun `finished runs are evicted, running ones never are`() = runTest {
        val runner = ExperimentRunner(backgroundScope)
        repeat(ExperimentRunner.MAX_RETAINED + 5) { i ->
            runner.start("done-$i", "test", 1) { result("done-$i") }
            testScheduler.runCurrent()
        }
        runner.start("live", "test", 1) { delay(Long.MAX_VALUE / 2); result("live") }
        testScheduler.runCurrent()
        repeat(5) { i ->
            runner.start("more-$i", "test", 1) { result("more-$i") }
            testScheduler.runCurrent()
        }

        assertTrue(runner.all().size <= ExperimentRunner.MAX_RETAINED + 1)
        assertTrue(runner.all().any { it.id == "live" }, "a running experiment was evicted")
        runner.cancelAll()
    }
}

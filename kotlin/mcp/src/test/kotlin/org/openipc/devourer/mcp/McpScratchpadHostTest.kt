package org.openipc.devourer.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.capture.CaptureService
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.radio.FakeRadios

/**
 * What a scratchpad can read, and what it is told when there is nothing there.
 *
 * The distinctions here are the point: a mistyped capture id, a capture that
 * has never seen a frame, and a quiet window are three different faults with
 * three different fixes, and collapsing them into one null is how a live view
 * comes to show a confident zero.
 */
class McpScratchpadHostTest {

    private suspend fun host(
        radios: FakeRadios,
        captures: CaptureService,
    ) = McpScratchpadHost(radios, captures)

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
}

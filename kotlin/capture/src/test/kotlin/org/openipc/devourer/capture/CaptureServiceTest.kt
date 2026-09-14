package org.openipc.devourer.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.radio.CapabilityException
import org.openipc.devourer.radio.FakeRadios

/**
 * The MCP module's first tests.
 *
 * Both of these are about the boundary the whole design rests on: frames are
 * consumed here, inside the server, and a capability the adapter does not have
 * is refused rather than silently downgraded.
 */
class CaptureServiceTest {

    private val realtek = FakeRadios.realtek(1)
    private val mediatek = FakeRadios.mediatek(2)

    @Test
    fun `frames land in the store and never cross a tool boundary to get there`() = runTest {
        val radios = FakeRadios(listOf(realtek))
        val captures = CaptureService(radios, backgroundScope)

        val capture = captures.start(1, ChannelSpec(6))
        radios.awaitCollector(1)
        radios.injectAmbient(1, 250)
        testScheduler.runCurrent()

        assertEquals(250, capture.store.size)
        assertEquals("ch6/20MHz", capture.store.summarizeAll().channel)
        assertTrue(radios.isMonitoring(1))
    }

    @Test
    fun `a width the adapter lacks is refused, not quietly downgraded`() = runTest {
        // The MediaTek fake has no 5MHz. Capturing at 20 instead and calling
        // it success would produce a capture whose every conclusion about
        // bandwidth is wrong, with nothing to detect it afterwards.
        val radios = FakeRadios(listOf(mediatek))
        val captures = CaptureService(radios, backgroundScope)

        val e = assertFailsWith<CapabilityException> {
            captures.start(2, ChannelSpec(36, ChannelWidth.W5))
        }
        assertTrue("supports 20, 40, 80MHz" in e.message!!, e.message)
        assertTrue(!radios.isMonitoring(2))
    }

    @Test
    fun `a capability caveat is carried on the capture, not printed once`() = runTest {
        // The MediaTek fake publishes no characterized TX-power range, which
        // makes "is this channel calibrated" UNKNOWN rather than known-bad.
        val radios = FakeRadios(listOf(mediatek))
        val capture = CaptureService(radios, backgroundScope).start(2, ChannelSpec(36))
        assertNotNull(capture.capabilityNote)
        assertTrue("UNKNOWN rather than known-bad" in capture.capabilityNote!!)
    }

    @Test
    fun `stopping keeps the evidence, discarding is the explicit way to lose it`() = runTest {
        val radios = FakeRadios(listOf(realtek))
        val captures = CaptureService(radios, backgroundScope)
        val capture = captures.start(1, ChannelSpec(6))
        radios.awaitCollector(1)
        radios.injectAmbient(1, 10)
        testScheduler.runCurrent()

        captures.stop(capture.id)
        assertEquals(10, captures.get(capture.id)?.store?.size)
        assertTrue(!radios.isMonitoring(1))

        assertTrue(captures.discard(capture.id))
        assertNull(captures.get(capture.id))
    }

    @Test
    fun `a restarted monitor is labelled, not presented as a quiet channel`() = runTest {
        // On the jaguar2 bench a second monitor on a session receives nothing.
        // An empty capture must carry that possibility rather than looking
        // like channel silence.
        val radios = FakeRadios(listOf(realtek))
        val captures = CaptureService(radios, backgroundScope)

        val first = captures.start(1, ChannelSpec(6))
        assertTrue(
            first.capabilityNote?.contains("restarted") != true,
            "a first capture is not a restart: ${first.capabilityNote}",
        )
        captures.stop(first.id)

        val second = captures.start(1, ChannelSpec(6))
        assertTrue(
            second.capabilityNote?.contains("restarted") == true,
            "a restarted monitor must say so: ${second.capabilityNote}",
        )
        assertTrue(
            second.capabilityNote?.contains("radio_close") == true,
            second.capabilityNote!!,
        )
    }

    @Test
    fun `stop releases the session's frame stream before it returns`() = runTest {
        // The bridge keeps one frame sink per session and a later attach
        // replaces it. A stopped capture that still holds that sink is the
        // state that made an experiment's witness read as silent, so
        // "stopped" must mean the session's frame stream is free.
        val radios = FakeRadios(listOf(realtek))
        val captures = CaptureService(radios, backgroundScope)

        val capture = captures.start(1, ChannelSpec(6))
        radios.awaitCollector(1)
        assertEquals(1, radios.collectorCount(1))

        captures.stop(capture.id)

        assertEquals(
            0,
            radios.collectorCount(1),
            "a stopped capture must not still hold the session's frame stream",
        )
    }
}

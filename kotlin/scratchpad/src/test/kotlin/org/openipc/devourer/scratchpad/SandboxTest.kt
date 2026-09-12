package org.openipc.devourer.scratchpad

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The capability boundary, tested as a boundary.
 *
 * These are the tests that matter most in this module. Everything else here is
 * a dashboard; this is the part that decides whether a model-authored program
 * can reach something it was not given.
 */
class SandboxTest {

    private fun grant(vararg caps: Capability, hosts: Set<String> = emptySet()) =
        CapabilityGrant(
            capabilities = caps.map { it.id }.toSet(),
            radioSessions = setOf(1),
            captureIds = setOf("cap-1"),
            httpHosts = hosts,
        )

    @Test
    fun `a capability not in the grant is refused`() {
        val g = grant(Capability.CAPTURE_READ)
        val e = assertFailsWith<CapabilityDeniedException> {
            g.require(Capability.HTTP_GET, "make HTTP requests")
        }
        assertTrue(e.message!!.contains("http.get"))
    }

    @Test
    fun `every advertised capability has an implementing step`() {
        // RADIO_TX, RADIO_MONITOR and STORAGE were advertised to the model for
        // months with nothing behind them: programs declaring them passed
        // validation and then silently did nothing, and a reviewer reading
        // `privileged: ["radio.tx"]` was scrutinising a gate that could not
        // gate. The catalogue must only contain what a step implements.
        val implemented = setOf(
            Capability.CAPTURE_READ,   // CaptureMetricSource
            Capability.RADIO_DESCRIBE, // RadioMetricSource
            Capability.HTTP_GET,       // HttpPollSource
            Capability.TIMER,          // every source is timer-driven
            Capability.METRICS,        // Computed
            Capability.UI,             // UiServer
        )
        assertEquals(
            implemented,
            Capability.entries.toSet(),
            "a capability with no step must be removed until its step exists",
        )
    }

    @Test
    fun `a radio outside the grant is refused even with the capability`() {
        // Holding radio.describe is not permission to describe EVERY radio. The
        // grant names the sessions, and a program handed session 1 must not be
        // able to read session 2 by asking for it.
        val g = grant(Capability.RADIO_DESCRIBE)
        g.requireRadio(1)
        val e = assertFailsWith<CapabilityDeniedException> { g.requireRadio(2) }
        assertTrue(e.message!!.contains("session 2"))
    }

    @Test
    fun `a capture outside the grant is refused`() {
        val g = grant(Capability.CAPTURE_READ)
        g.requireCapture("cap-1")
        assertFailsWith<CapabilityDeniedException> { g.requireCapture("cap-2") }
    }

    @Test
    fun `http is allowlisted by host and port, not by prefix`() {
        val g = grant(Capability.HTTP_GET, hosts = setOf("192.168.2.181", "cam.local:8080"))
        g.requireHttpTarget(URI("http://192.168.2.181/status"))
        g.requireHttpTarget(URI("http://cam.local:8080/api/v1/stats"))

        // A different host is refused even if the path looks familiar.
        assertFailsWith<CapabilityDeniedException> {
            g.requireHttpTarget(URI("http://evil.example.com/status"))
        }
        // The same host on an unlisted port is a different target.
        assertFailsWith<CapabilityDeniedException> {
            g.requireHttpTarget(URI("http://cam.local:9999/api"))
        }
        // A host that merely contains an allowed one must not pass.
        assertFailsWith<CapabilityDeniedException> {
            g.requireHttpTarget(URI("http://192.168.2.181.evil.com/status"))
        }
    }

    @Test
    fun `non-http schemes are refused`() {
        val g = grant(Capability.HTTP_GET, hosts = setOf("localhost"))
        // file: and ftp: would be filesystem and network reach by another name.
        assertFailsWith<CapabilityDeniedException> {
            g.requireHttpTarget(URI("file://localhost/etc/passwd"))
        }
        assertFailsWith<CapabilityDeniedException> {
            g.requireHttpTarget(URI("ftp://localhost/secrets"))
        }
    }

    @Test
    fun `a program using an undeclared capability is caught before it runs`() {
        val program = ScratchpadProgram(
            name = "sneaky",
            capabilities = listOf(Capability.TIMER.id), // no http.get
            sources = listOf(
                HttpPollSource(id = "poll", url = "http://192.168.2.181/x", everyMs = 500),
            ),
        )
        val undeclared = program.undeclared()
        assertTrue(Capability.HTTP_GET in undeclared)
        assertFalse(program.validate().isNotEmpty() && undeclared.isEmpty())
    }

    @Test
    fun `validation rejects a program that would measure nothing`() {
        val problems = ScratchpadProgram(name = "empty").validate()
        assertTrue(problems.any { it.contains("no sources") })
    }

    @Test
    fun `validation rejects duplicate series ids`() {
        val p = ScratchpadProgram(
            name = "dupes",
            capabilities = listOf("timer", "capture.read", "metrics"),
            sources = listOf(
                CaptureMetricSource(id = "x", captureId = "cap-1", metric = "frames"),
            ),
            computed = listOf(Computed(id = "x", expr = "1")),
        )
        assertTrue(p.validate().any { it.contains("duplicate series id") })
    }

    @Test
    fun `an http poll whose timeout exceeds its period is rejected`() {
        // Otherwise requests pile up: each period starts another before the last
        // finished, and the "latency" series becomes a measure of the backlog.
        val p = HttpPollSource(id = "p", url = "http://h/x", everyMs = 200, timeoutMs = 2000)
        assertTrue(p.validate().any { it.contains("polls would overlap") })
    }

    @Test
    fun `unknown metrics are rejected rather than silently returning nothing`() {
        val p = CaptureMetricSource(id = "m", captureId = "cap-1", metric = "vibes")
        assertTrue(p.validate().any { it.contains("unknown metric") })
    }
}

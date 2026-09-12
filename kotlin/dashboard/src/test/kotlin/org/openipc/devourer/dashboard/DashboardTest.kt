package org.openipc.devourer.dashboard

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.capture.CaptureService
import org.openipc.devourer.characterize.EvidenceStore
import org.openipc.devourer.experiment.ExperimentRunner
import org.openipc.devourer.protocol.BackendReport
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.HelloResult
import org.openipc.devourer.protocol.ProtocolVersion
import org.openipc.devourer.radio.FakeRadios
import org.openipc.devourer.scratchpad.ScratchpadHost
import org.openipc.devourer.scratchpad.ScratchpadService

class DashboardTest {

    private val hello = HelloResult(
        protocol = ProtocolVersion(1, 1),
        devourerCommit = "30d248eabcdef",
        frameRecordBytes = 88,
        backends = listOf(
            BackendReport("jaguar1", "8812AU", true),
            BackendReport("kestrel", "8852B", false),
        ),
    )

    private object NoHost : ScratchpadHost {
        override suspend fun captureMetric(
            captureId: String,
            metric: String,
            windowMs: Long,
            kind: String?,
            transmitter: String?,
        ): Double? = null

        override suspend fun radioMetric(session: Int, metric: String): Double? = null
    }

    private class Bench(val scope: CoroutineScope) : AutoCloseable {
        val radios = FakeRadios(listOf(FakeRadios.realtek(1), FakeRadios.mediatek(2)))
        val book = RadioBook()
        val activity = ActivityLog()
        val recording = RecordingRadios(radios, book)
        val captures = CaptureService(recording, scope)
        val experiments = ExperimentRunner(scope)
        val scratchpads = ScratchpadService(NoHost, scope, createTempDirectory("pads"))
        val evidence = EvidenceStore(createTempDirectory("evidence"))
        val dashboard = Dashboard(
            port = 0,
            hello = HelloResult(ProtocolVersion(1, 1), "30d248eabcdef", 88, emptyList()),
            radios = book,
            activity = activity,
            captures = captures,
            experiments = experiments,
            scratchpads = scratchpads,
            evidence = evidence,
        )

        override fun close() {
            dashboard.close()
            scratchpads.stopAll()
        }
    }

    private fun get(bench: Bench, path: String): Pair<Int, String> {
        val c = URI.create(bench.dashboard.url.trimEnd('/') + path).toURL()
            .openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        c.disconnect()
        return code to body
    }

    /**
     * A raw request, because `HttpURLConnection` silently drops `Origin` —
     * it is on the JDK's restricted-header list. A test that used it would
     * have reported the cross-origin guard as working while never sending
     * the header the guard inspects.
     */
    private fun request(
        bench: Bench,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = Socket("127.0.0.1", bench.dashboard.port).use { s ->
        val out = s.getOutputStream()
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(bench.dashboard.port).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.flush()
        val text = s.getInputStream().bufferedReader().readText()
        val status = text.substringAfter(' ').substringBefore(' ').toInt()
        status to text.substringAfter("\r\n\r\n")
    }

    private fun withBench(body: (Bench) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        Bench(scope).use(body)
        scope.cancel()
    }

    @Test
    fun `the page is served on loopback and carries a restrictive policy`() = withBench { b ->
        val c = URI.create(b.dashboard.url).toURL().openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"))
        val csp = c.getHeaderField("Content-Security-Policy")
        assertContains(csp, "default-src 'none'")
        // The load-bearing directive: any injection that still got through
        // could deface the page but could not post the capture anywhere.
        assertContains(csp, "connect-src 'self'")
        assertContains(c.inputStream.bufferedReader().readText(), "devourer instrument")
        c.disconnect()
        assertTrue(b.dashboard.url.startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `the page never writes markup from a string`() {
        // The one rule that keeps this surface safe by construction.
        assertFalse("innerHTML" in PAGE, "the dashboard must build DOM, not parse markup")
        assertFalse("outerHTML" in PAGE)
        assertFalse("document.write" in PAGE)
        // A Kotlin raw string interpolates a bare dollar sign. Shipping a JS
        // template literal here has produced literal Kotlin syntax in the
        // output twice in this repository.
        assertFalse("\${" in PAGE, "no template literal may survive into the page")
    }

    @Test
    fun `state reports what the control plane has seen`() = withBench { b ->
        // Note what is NOT done here: no describe after startMonitor. The
        // page used to show an adapter as idle and untuned while it was
        // monitoring, because the last describe predated the state change.
        runBlocking {
            b.recording.describe(1)
            b.recording.startMonitor(1, ChannelSpec(6))
        }
        val (code, body) = get(b, "/api/state")
        assertEquals(200, code)
        val state = Json.parseToJsonElement(body).jsonObject
        val radios = state.getValue("radios").jsonArray
        assertEquals(1, radios.size)
        val radio = radios[0].jsonObject
        assertEquals("RTL8812AU", radio.getValue("chip").jsonPrimitive.content)
        assertEquals(true, radio.getValue("monitoring").jsonPrimitive.content.toBoolean())
        assertEquals("ch6/20MHz", radio.getValue("channel").jsonPrimitive.content)
        assertEquals("1.1", state.getValue("bridge").jsonObject.getValue("protocol").jsonPrimitive.content)
    }

    @Test
    fun `a closed radio leaves the page rather than lingering`() = withBench { b ->
        runBlocking {
            b.recording.describe(1)
            b.recording.close(1)
        }
        val state = Json.parseToJsonElement(get(b, "/api/state").second).jsonObject
        assertEquals(0, state.getValue("radios").jsonArray.size)
    }

    @Test
    fun `a radio transmitting deaf is a warning, not a footnote`() = withBench { b ->
        runBlocking {
            b.recording.setCarrierSense(
                1, enabled = false, safety = org.openipc.devourer.radio.SafetyLevel.EXPERIMENTAL,
            )
        }
        val state = Json.parseToJsonElement(get(b, "/api/state").second).jsonObject
        val warnings = state.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { "carrier sense OFF" in it }, warnings.toString())
    }

    @Test
    fun `activity pages forward by sequence and never repeats`() = withBench { b ->
        b.activity.record("radio_list", "all=false", 3, true)
        b.activity.record("radio_open", "bus=1 address=4", 812, true)

        val first = Json.parseToJsonElement(get(b, "/api/activity?since=0").second).jsonObject
        assertEquals(2, first.getValue("entries").jsonArray.size)
        val latest = first.getValue("latest").jsonPrimitive.content

        b.activity.record("monitor_start", "session=1 channel=6", 140, false, "no_session: 1")
        val next = Json.parseToJsonElement(get(b, "/api/activity?since=$latest").second).jsonObject
        val entries = next.getValue("entries").jsonArray
        assertEquals(1, entries.size)
        assertEquals("monitor_start", entries[0].jsonObject.getValue("tool").jsonPrimitive.content)
        assertFalse(entries[0].jsonObject.getValue("ok").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `hostile text in a tool argument comes back as data, not as markup`() = withBench { b ->
        val nasty = """</script><img src=x onerror=alert(1)>"quoted"\backslash"""
        b.activity.record("scratchpad_run", nasty, 1, true)
        val c = URI.create(b.dashboard.url.trimEnd('/') + "/api/activity?since=0").toURL()
            .openConnection() as HttpURLConnection
        val body = c.inputStream.bufferedReader().readText()

        // It must survive intact rather than being mangled by an escaper.
        val entry = Json.parseToJsonElement(body).jsonObject
            .getValue("entries").jsonArray[0].jsonObject
        assertEquals(nasty, entry.getValue("arguments").jsonPrimitive.content)

        // And it must never reach a parser that would treat it as markup.
        // This response is data: typed as JSON, with sniffing disabled, and
        // the page writes it into the DOM as text. Note what is NOT relied
        // on here — the serializer does not escape "</script>", and does not
        // need to, because nothing ever inlines this into a document.
        assertEquals("application/json", c.getHeaderField("Content-Type"))
        assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"))
        assertFalse("<script" in PAGE.substringAfter("<script>").substringBefore("</script>"))
        c.disconnect()
    }

    @Test
    fun `stopping an experiment needs a POST that a foreign page cannot make`() = withBench { b ->
        val runner = b.experiments
        runner.start("exp-1", "link_probe", 3) { delay(60_000); error("unreachable") }
        Thread.sleep(60)

        assertEquals(405, request(b, "GET", "/api/experiment/exp-1/cancel").first)
        assertEquals(403, request(b, "POST", "/api/experiment/exp-1/cancel").first)
        assertEquals(
            403,
            request(
                b, "POST", "/api/experiment/exp-1/cancel",
                mapOf(Dashboard.GUARD_HEADER to "1", "Origin" to "https://evil.example"),
            ).first,
        )
        assertEquals(ExperimentRunner.Phase.RUNNING, runner.progress("exp-1")!!.phase)

        val (code, body) = request(
            b, "POST", "/api/experiment/exp-1/cancel", mapOf(Dashboard.GUARD_HEADER to "1"),
        )
        assertEquals(200, code)
        assertContains(body, "\"cancelled\":true")
    }

    @Test
    fun `an unknown path is a 404, not the page`() = withBench { b ->
        assertEquals(404, get(b, "/../etc/passwd").first)
        assertEquals(404, get(b, "/api/nope").first)
    }

    @Test
    fun `a capture appears with its live counters`() = withBench { b ->
        // Real dispatch, not virtual: the bench scope is a live one, so the
        // collector runs on a real thread and a test clock would never
        // advance it. The wait is then done with blocking polls of the
        // endpoint under test, which is the thing this is actually about —
        // whether the page shows the counters as they move.
        runBlocking {
            b.captures.start(1, ChannelSpec(6))
            b.radios.awaitCollector(1)
            b.radios.injectAmbient(1, 120)
        }

        var frames = -1
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && frames < 120) {
            val state = Json.parseToJsonElement(get(b, "/api/state").second).jsonObject
            val captures = state.getValue("captures").jsonArray
            assertEquals(1, captures.size)
            frames = captures[0].jsonObject.getValue("frames").jsonPrimitive.content.toInt()
            if (frames < 120) Thread.sleep(20)
        }
        assertEquals(120, frames)
    }
}

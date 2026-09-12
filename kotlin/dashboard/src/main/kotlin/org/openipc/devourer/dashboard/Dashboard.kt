package org.openipc.devourer.dashboard

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.openipc.devourer.capture.CaptureService
import org.openipc.devourer.characterize.EvidenceStore
import org.openipc.devourer.experiment.ExperimentRunner
import org.openipc.devourer.protocol.HelloResult
import org.openipc.devourer.scratchpad.ScratchpadService

/**
 * A persistent view of the whole instrument, on one fixed loopback port.
 *
 * The per-scratchpad UI that existed before this is ephemeral by design: it
 * appears on a random port when a micro-app starts and disappears when it
 * stops, so there was no address to keep open while watching a model work.
 * This is the address. It outlives every run and shows what the control plane
 * is doing as it does it.
 *
 * Three deliberate properties:
 *
 * - **It never talks to the bridge.** Everything on the page comes from
 *   in-process state ([RadioBook], [CaptureService], [ExperimentRunner],
 *   [ScratchpadService]). A page polling once a second must not queue behind
 *   the model on the serialized control connection, and must still render
 *   when the bridge has stopped answering — which is exactly when someone is
 *   looking at it.
 * - **The page is a constant.** No request data and no runtime value is ever
 *   interpolated into the HTML; the document is static and everything dynamic
 *   arrives as JSON built by the serializer and written into the DOM as text.
 *   The escaping bug class simply has nowhere to live.
 * - **One thing it can change.** `POST /api/experiment/{id}/cancel` stops a
 *   run. An instrument that transmits needs a stop control that does not go
 *   through the same queue as the thing being stopped, and stopping is the
 *   fail-safe direction. Everything else is read-only.
 */
public class Dashboard(
    port: Int,
    private val hello: HelloResult,
    private val radios: RadioBook,
    private val activity: ActivityLog,
    private val captures: CaptureService,
    private val experiments: ExperimentRunner,
    private val scratchpads: ScratchpadService,
    private val evidence: EvidenceStore?,
) : AutoCloseable {

    private val startedAtEpochMs = System.currentTimeMillis()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val server: HttpServer = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        BACKLOG,
    )

    public val port: Int get() = server.address.port
    public val url: String get() = "http://127.0.0.1:$port/"

    init {
        server.createContext("/") { ex ->
            handle(ex) {
                if (ex.requestURI.path != "/") notFound(ex) else html(ex, PAGE)
            }
        }
        server.createContext("/api/state") { ex ->
            handle(ex) { json(ex, json.encodeToString(DashboardState.serializer(), state())) }
        }
        server.createContext("/api/activity") { ex ->
            handle(ex) {
                val since = ex.requestURI.query
                    ?.split('&')
                    ?.firstOrNull { it.startsWith("since=") }
                    ?.removePrefix("since=")
                    ?.toLongOrNull()
                    ?: 0L
                json(
                    ex,
                    json.encodeToString(
                        ActivityPage.serializer(),
                        ActivityPage(activity.latest(), activity.since(since)),
                    ),
                )
            }
        }
        server.createContext("/api/experiment/") { ex -> handle(ex) { cancel(ex) } }
        server.executor = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "devourer-dashboard").apply { isDaemon = true }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
    }

    // ------------------------------------------------------------------ state

    private fun state(): DashboardState {
        val radioViews = radios.all().map { (radio, seen) ->
            RadioView(
                session = radio.session,
                label = radio.label,
                chip = radio.capabilities.chip.ifBlank { radio.device.usbId },
                backend = radio.capabilities.generation.ifBlank { radio.device.backend },
                locator = radio.device.locator,
                permanentMac = radio.permanentMac,
                channel = radio.channel?.let { "ch${it.channel}/${it.width}MHz" },
                monitoring = radio.state.monitoring,
                broughtUp = radio.state.broughtUp,
                carrierSenseDisabled = radio.state.carrierSenseDisabled,
                seenAtEpochMs = seen,
            )
        }
        val captureViews = captures.all().map { c ->
            val s = c.store.summarizeAll()
            CaptureView(
                id = c.id,
                session = c.session,
                radio = c.radioLabel,
                channel = c.channel.toString(),
                frames = s.frames,
                framesPerSecond = s.framesPerSecond,
                crcErrors = s.crcErrors,
                retries = s.retries,
                startedAtEpochMs = c.startedAtEpochMs,
                note = c.capabilityNote,
            )
        }
        val padViews = scratchpads.list().map { run ->
            ScratchpadView(
                id = run.id,
                title = run.program.name,
                running = run.job.isActive,
                capabilities = run.grant.capabilities.sorted(),
                uiUrl = run.ui?.url,
                series = run.state.latest(),
                logTail = run.state.logs().takeLast(LOG_TAIL),
                startedAtEpochMs = run.startedAtEpochMs,
            )
        }
        val records = evidence?.list().orEmpty().map { c ->
            CharacterizationView(
                key = c.key,
                chip = c.chip.ifBlank { c.identity.usbId },
                backend = c.backend,
                state = c.state.name,
                runs = c.runs.size,
                unverified = c.unverified.size,
                lastSeenEpochMs = c.lastSeenEpochMs,
            )
        }

        val warnings = buildList {
            radioViews.filter { it.carrierSenseDisabled }.forEach {
                add(
                    "${it.label} is transmitting with carrier sense OFF. It is not listening " +
                        "before it transmits, so it is talking over anything else on the " +
                        "channel, and every measurement taken now means something different.",
                )
            }
            experiments.running().forEach {
                add("experiment ${it.id} is running: ${it.completedPoints}/${it.totalPoints} points")
            }
        }

        return DashboardState(
            bridge = BridgeView(
                protocol = "${hello.protocol.major}.${hello.protocol.minor}",
                devourerCommit = hello.devourerCommit.take(12),
                backends = hello.backends.filter { it.compiled }.map { it.name },
                startedAtEpochMs = startedAtEpochMs,
            ),
            radios = radioViews,
            captures = captureViews,
            experiments = experiments.all(),
            scratchpads = padViews,
            characterizations = records,
            warnings = warnings,
        )
    }

    // ----------------------------------------------------------------- cancel

    private fun cancel(ex: HttpExchange) {
        val path = ex.requestURI.path.removePrefix("/api/experiment/")
        if (!path.endsWith("/cancel")) return notFound(ex)
        if (ex.requestMethod != "POST") return respond(ex, 405, "text/plain", "POST only")
        /*
         * A page on any origin can POST to a loopback port. The worst this
         * endpoint can do is STOP an experiment, which is the fail-safe
         * direction, but "fail-safe" is not a reason to leave it open: the
         * custom header forces a CORS preflight for anything cross-origin,
         * and no preflight is answered here.
         */
        if (ex.requestHeaders.getFirst(GUARD_HEADER) == null) {
            return respond(ex, 403, "text/plain", "missing $GUARD_HEADER")
        }
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin != null && origin !in setOf("http://127.0.0.1:$port", "http://localhost:$port")) {
            return respond(ex, 403, "text/plain", "cross-origin request refused")
        }
        val id = path.removeSuffix("/cancel")
        val stopped = experiments.cancel(id, "stopped from the dashboard")
        json(
            ex,
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("cancelled", JsonPrimitive(stopped))
                    put("id", JsonPrimitive(id))
                },
            ),
        )
    }

    // ------------------------------------------------------------------- http

    private inline fun handle(ex: HttpExchange, body: () -> Unit) {
        try {
            ex.use { body() }
        } catch (e: Throwable) {
            // A dashboard that takes the process down with it would be worse
            // than no dashboard at all.
            runCatching { respond(ex, 500, "text/plain", "dashboard error: ${e.message}") }
        }
    }

    private fun notFound(ex: HttpExchange) = respond(ex, 404, "text/plain", "not found")

    private fun html(ex: HttpExchange, body: String) =
        respond(ex, 200, "text/html; charset=utf-8", body)

    private fun json(ex: HttpExchange, body: String) =
        respond(ex, 200, "application/json", body)

    private fun respond(ex: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
                "connect-src 'self'; img-src 'none'; base-uri 'none'; form-action 'none'; " +
                "frame-ancestors 'none'",
        )
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    public companion object {
        /**
         * Not in any IANA range this bench uses, and not one of the waybeam
         * ports on this host.
         */
        public const val DEFAULT_PORT: Int = 8910

        /** Present on every state-changing request; see [cancel]. */
        public const val GUARD_HEADER: String = "X-Devourer-Dashboard"

        private const val BACKLOG = 8
        private const val LOG_TAIL = 12
    }
}

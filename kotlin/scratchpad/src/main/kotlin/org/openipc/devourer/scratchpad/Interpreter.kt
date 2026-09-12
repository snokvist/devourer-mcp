package org.openipc.devourer.scratchpad

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The services a scratchpad may reach, all of them capability-gated.
 *
 * Narrow on purpose. Every method here is something a program can reach, so the
 * interface is the security surface: adding one is a deliberate decision, not a
 * convenience. The MCP layer implements it over the real radio and capture
 * services; tests implement it with fixtures, which is what lets the whole
 * runtime be tested with no hardware.
 */
public interface ScratchpadHost {
    /** A live metric from a capture. Null when the capture has no data yet. */
    public suspend fun captureMetric(
        captureId: String,
        metric: String,
        windowMs: Long,
        kind: String?,
        transmitter: String?,
    ): Double?

    public suspend fun radioMetric(session: Int, metric: String): Double?
}

/**
 * Executes a [ScratchpadProgram].
 *
 * One coroutine per source, each on its own schedule, writing into a shared
 * series table; derived values are recomputed from the latest sample of each
 * input whenever the table changes. That is the whole execution model.
 *
 * Failures are recorded and the run continues. A camera that stops answering
 * should leave a gap in its series and a line in the log, not tear down the
 * radio monitoring next to it — the point of a live diagnostic is to keep
 * showing what still works while something is broken.
 */
public class Interpreter(
    private val host: ScratchpadHost,
    private val grant: CapabilityGrant,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            // No redirect following: a redirect is a way out of the allowlist,
            // and the allowlist is the only thing standing between a program
            // and an arbitrary host.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    public suspend fun run(
        program: ScratchpadProgram,
        state: RunState,
        parentScope: CoroutineScope,
    ) {
        val problems = program.validate()
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException("invalid program: ${problems.joinToString("; ")}")
        }
        val undeclared = program.undeclared()
        if (undeclared.isNotEmpty()) {
            throw CapabilityDeniedException(
                "this program uses capabilities it did not declare: " +
                    undeclared.joinToString(", ") { it.id } +
                    ". Add them to the capabilities list so the grant can be reviewed.",
            )
        }
        program.capabilities.forEach { id ->
            val c = Capability.byId(id) ?: return@forEach
            if (!grant.has(c)) {
                throw CapabilityDeniedException(
                    "the program declared '${c.id}' but the run was not granted it",
                )
            }
        }
        // Expressions may only read series that exist. A typo would otherwise
        // read as NaN forever and look like a dead sensor.
        val known = program.seriesIds()
        program.computed.forEach { c ->
            val missing = Expr.references(c.expr) - known
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException(
                    "computed '${c.id}' references unknown series ${missing.sorted()}; " +
                        "available: ${known.sorted()}",
                )
            }
        }

        val budget = minOf(program.durationMs, grant.maxRuntimeMs)
        if (budget < program.durationMs) {
            state.log("duration clamped from ${program.durationMs}ms to ${budget}ms by the grant")
        }

        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
        val jobs = mutableListOf<Job>()
        try {
            program.sources.forEach { source ->
                jobs += scope.launch { pump(source, state) }
            }
            if (program.computed.isNotEmpty()) {
                jobs += scope.launch { recompute(program, state) }
            }
            val deadline = System.currentTimeMillis() + budget
            while (scope.isActive && System.currentTimeMillis() < deadline && !state.stopRequested) {
                delay(100)
            }
        } finally {
            jobs.forEach { it.cancel() }
            scope.cancel()
            state.finish()
        }
    }

    private suspend fun pump(source: Source, state: RunState) {
        // Stagger start so a program with several 500ms sources does not fire
        // them all in the same millisecond every period.
        delay((source.id.hashCode().toLong() and 0x7f))
        while (currentCoroutineIsActive()) {
            val t0 = System.currentTimeMillis()
            runCatching { sample(source, state) }
                .onFailure { state.log("${source.id}: ${it.message ?: it::class.simpleName}") }
            val spent = System.currentTimeMillis() - t0
            delay((source.everyMs - spent).coerceAtLeast(10))
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    private suspend fun sample(source: Source, state: RunState) {
        when (source) {
            is CaptureMetricSource -> {
                grant.require(Capability.CAPTURE_READ, "read captures")
                grant.requireCapture(source.captureId)
                val v = host.captureMetric(
                    source.captureId, source.metric, source.windowMs,
                    source.frameKind, source.transmitter,
                )
                if (v == null) state.noteEmpty(source.id, "capture '${source.captureId}' " +
                    "returned no value for metric '${source.metric}' over the last " +
                    "${source.windowMs}ms")
                else state.record(source.id, v)
            }

            is RadioMetricSource -> {
                grant.require(Capability.RADIO_DESCRIBE, "read radio state")
                grant.requireRadio(source.session)
                val v = host.radioMetric(source.session, source.metric)
                if (v == null) state.noteEmpty(source.id, "radio session ${source.session} " +
                    "returned no value for metric '${source.metric}'")
                else state.record(source.id, v)
            }

            is HttpPollSource -> {
                grant.require(Capability.HTTP_GET, "make HTTP requests")
                val uri = URI(source.url)
                grant.requireHttpTarget(uri)
                val started = System.nanoTime()
                val response = withContext(Dispatchers.IO) {
                    http.send(
                        HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofMillis(source.timeoutMs))
                            .GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                }
                val latencyMs = (System.nanoTime() - started) / 1e6
                state.record("${source.id}.latency_ms", latencyMs)
                state.record("${source.id}.status", response.statusCode().toDouble())
                if (source.extract.isNotEmpty()) {
                    val body = runCatching {
                        json.parseToJsonElement(response.body()).jsonObject
                    }.getOrNull()
                    if (body == null) {
                        state.log("${source.id}: response was not a JSON object")
                    } else {
                        source.extract.forEach { (series, path) ->
                            dig(body, path)?.let { state.record("${source.id}.$series", it) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun recompute(program: ScratchpadProgram, state: RunState) {
        val period = program.sources.minOf { it.everyMs }.coerceAtLeast(100)
        while (currentCoroutineIsActive()) {
            delay(period)
            val scope = state.latest()
            program.computed.forEach { c ->
                runCatching { Expr.evaluate(c.expr, scope) }
                    .onSuccess { if (!it.isNaN()) state.record(c.id, it) }
                    .onFailure { state.log("computed ${c.id}: ${it.message}") }
            }
        }
    }

    /** Walks a dotted path into a flat-ish JSON object. */
    private fun dig(root: JsonObject, path: String): Double? {
        var node: kotlinx.serialization.json.JsonElement = root
        for (part in path.split('.')) {
            node = (node as? JsonObject)?.get(part) ?: return null
        }
        return (node as? JsonPrimitive)?.let {
            it.doubleOrNull ?: it.content.toDoubleOrNull()
        }
    }
}

/**
 * A run's live state: series, log and status.
 *
 * Each series is a bounded ring. A 30-minute run at 500 ms would otherwise hold
 * 3600 samples per series and grow without limit, and the oldest samples are
 * exactly the ones a live view does not show.
 */
public class RunState(private val maxSamples: Int = 10_000) {
    private val series = ConcurrentHashMap<String, Series>()
    private val logLines = ArrayDeque<String>()
    private val logLock = Any()

    @Volatile
    public var stopRequested: Boolean = false
        private set

    @Volatile
    public var finishedAtEpochMs: Long = 0
        private set

    public val startedAtEpochMs: Long = System.currentTimeMillis()

    public fun record(id: String, value: Double) {
        series.computeIfAbsent(id) { Series(it, maxSamples) }.add(value)
    }

    /**
     * Records that a source produced nothing.
     *
     * Logged once per source rather than every sample: a metric that is empty
     * at start-up is normal and becomes noise at two per second, but a source
     * that is silently empty for a whole run is a bug the operator must see.
     * Silence was in fact how a real one hid — a scratchpad reported no capture
     * series at all and logged nothing to say why.
     */
    public fun noteEmpty(sourceId: String, why: String) {
        if (emptyNoted.add(sourceId)) log("$sourceId produced no value: $why")
    }

    private val emptyNoted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    public fun log(message: String) {
        synchronized(logLock) {
            logLines.addLast("${System.currentTimeMillis() - startedAtEpochMs}ms  $message")
            while (logLines.size > MAX_LOG) logLines.removeFirst()
        }
    }

    public fun logs(): List<String> = synchronized(logLock) { logLines.toList() }

    public fun seriesNames(): List<String> = series.keys.sorted()

    public fun series(id: String): Series? = series[id]

    public fun all(): Map<String, Series> = series.toMap()

    /** The most recent value of every series, for expression evaluation. */
    public fun latest(): Map<String, Double> =
        series.mapNotNull { (k, v) -> v.last()?.let { k to it.value } }.toMap()

    public fun requestStop() {
        stopRequested = true
    }

    public fun finish() {
        if (finishedAtEpochMs == 0L) finishedAtEpochMs = System.currentTimeMillis()
    }

    private companion object {
        const val MAX_LOG = 500
    }
}

public data class Sample(val atEpochMs: Long, val value: Double)

public class Series(public val id: String, private val maxSamples: Int) {
    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    public fun add(value: Double) {
        synchronized(lock) {
            samples.addLast(Sample(System.currentTimeMillis(), value))
            while (samples.size > maxSamples) samples.removeFirst()
        }
    }

    public fun snapshot(): List<Sample> = synchronized(lock) { samples.toList() }

    public fun last(): Sample? = synchronized(lock) { samples.lastOrNull() }

    public fun count(): Int = synchronized(lock) { samples.size }

    /** Mean, min, max over the trailing [windowMs]. Null when the window is empty. */
    public fun stats(windowMs: Long? = null): Stats? = synchronized(lock) {
        val cutoff = windowMs?.let { System.currentTimeMillis() - it } ?: Long.MIN_VALUE
        val vals = samples.filter { it.atEpochMs >= cutoff }.map { it.value }
        if (vals.isEmpty()) null
        else Stats(vals.size, vals.min(), vals.max(), vals.average(), vals.last())
    }

    public data class Stats(
        val count: Int,
        val min: Double,
        val max: Double,
        val mean: Double,
        val last: Double,
    )
}

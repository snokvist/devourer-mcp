package org.openipc.devourer.scratchpad

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a scratchpad program is allowed to touch.
 *
 * The security model in one sentence: **a program receives only the primitives
 * it declared, and there is no primitive for "anything else".**
 *
 * This is not a sandbox in the usual sense — there is no untrusted machine code
 * to contain. A scratchpad is a declarative program interpreted by [Interpreter]
 * against a fixed set of typed steps. It cannot open a file, spawn a process,
 * load a class or reach the network except through a declared capability,
 * because no step exists that does those things. Nothing is being blocked; the
 * ability was never there.
 *
 * That choice is deliberate and costs expressiveness. The alternative —
 * embedding a scripting engine — would be far more flexible and would put
 * model-authored code inside the process that owns the radios, on a JVM whose
 * SecurityManager was removed in 17 and deleted in 21. There is no supported way
 * to contain that in-process, and the brief is explicit that this must not be
 * unrestricted code execution.
 */
@Serializable
public enum class Capability(
    public val id: String,
    public val description: String,
    public val privileged: Boolean = false,
) {
    /*
     * ONLY capabilities with an implementing step belong here.
     *
     * RADIO_TX, RADIO_MONITOR and STORAGE were declared for months with no
     * source or step behind them. That is worse than omitting them: the
     * catalogue handed to the model advertised file I/O and transmission that
     * silently did nothing, and a human reviewing `privileged: ["radio.tx"]`
     * was scrutinising a gate structurally incapable of gating. Re-add each
     * one in the same change that implements its step.
     */

    /** Read live per-frame metrics from a running capture. */
    CAPTURE_READ("capture.read", "read summaries and frames from a capture this program was given"),

    /**
     * Read a finished experiment's result by id.
     *
     * An experiment is where a rate sweep, a power sweep or a delivery
     * measurement actually lives. A live view that cannot see one can chart a
     * radio's raw counters but not the run's own answer.
     */
    EXPERIMENT_READ("experiment.read", "read point metrics from a granted experiment result"),

    /** Read a radio's identity, capabilities and state. */
    RADIO_DESCRIBE("radio.describe", "read a granted radio's capability and state report"),

    /**
     * HTTP GET against an explicitly listed host allowlist.
     *
     * Allowlisted rather than open: the motivating case is polling a camera on
     * the bench LAN, and a program that can reach arbitrary hosts is a data
     * exfiltration path out of a process that holds capture data.
     */
    HTTP_GET("http.get", "HTTP GET against hosts named in the program's allowlist"),

    /** Sample on a timer. Every program that polls needs this. */
    TIMER("timer", "run steps on a fixed interval"),

    /** Accumulate series and compute statistics over them. */
    METRICS("metrics", "accumulate time series and compute statistics"),

    /** Render a live view. */
    UI("ui", "present charts, gauges, tables and logs in a generated page"),
    ;

    public companion object {
        public fun byId(id: String): Capability? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The capabilities actually granted to one run, with their limits.
 *
 * A grant is not just a set of names: `http.get` without an allowlist is an open
 * proxy, so the resource lists live in the grant rather than being checked
 * somewhere else and hoped for.
 *
 * Critically, a grant is built by the CALLER, never from the program's own
 * `capabilities` list. It was briefly built from the program, which made every
 * downstream check a tautology — a program could grant itself anything by
 * naming it. See `Tools.kt`'s scratchpad_run.
 */
@Serializable
public data class CapabilityGrant(
    val capabilities: Set<String> = emptySet(),
    /** Radio sessions this program may address. Any other is refused. */
    @SerialName("radio_sessions") val radioSessions: Set<Int> = emptySet(),
    /** Capture ids this program may read. */
    @SerialName("capture_ids") val captureIds: Set<String> = emptySet(),
    /** Experiment ids this program may read results from. */
    @SerialName("experiment_ids") val experimentIds: Set<String> = emptySet(),
    /** Hosts (or host:port) this program may GET. Exact match, no wildcards. */
    @SerialName("http_hosts") val httpHosts: Set<String> = emptySet(),
    /** Wall-clock ceiling on the whole run. */
    @SerialName("max_runtime_ms") val maxRuntimeMs: Long = 120_000,
    /** Ceiling on samples retained per series, so a long run cannot exhaust memory. */
    @SerialName("max_samples") val maxSamples: Int = 10_000,
) {
    public fun has(c: Capability): Boolean = c.id in capabilities

    public fun require(c: Capability, what: String) {
        if (!has(c)) {
            throw CapabilityDeniedException(
                "this program did not declare '${c.id}', so it cannot $what. " +
                    "Declare it in the program's capabilities list and re-run.",
            )
        }
    }

    public fun requireRadio(session: Int) {
        if (session !in radioSessions) {
            throw CapabilityDeniedException(
                "radio session $session was not granted to this program " +
                    "(granted: ${radioSessions.sorted()})",
            )
        }
    }

    public fun requireCapture(id: String) {
        if (id !in captureIds) {
            throw CapabilityDeniedException(
                "capture '$id' was not granted to this program (granted: $captureIds)",
            )
        }
    }

    public fun requireExperiment(id: String) {
        if (id !in experimentIds) {
            throw CapabilityDeniedException(
                "experiment '$id' was not granted to this program (granted: $experimentIds)",
            )
        }
    }

    /**
     * Checks a URL against the allowlist.
     *
     * Matches on host and port only. Path is irrelevant to whether the request
     * leaves the machine, and matching on a path prefix would invite a
     * traversal that ends up somewhere else on the same host anyway.
     */
    public fun requireHttpTarget(url: java.net.URI) {
        val host = url.host
            ?: throw CapabilityDeniedException("'$url' has no host")
        val hostPort = if (url.port > 0) "$host:${url.port}" else host
        if (host !in httpHosts && hostPort !in httpHosts) {
            throw CapabilityDeniedException(
                "host '$hostPort' is not in this program's allowlist ($httpHosts)",
            )
        }
        if (url.scheme != "http" && url.scheme != "https") {
            throw CapabilityDeniedException("only http and https are allowed, not '${url.scheme}'")
        }
    }
}

/** A program tried to do something it did not declare. Always fatal to the run. */
public class CapabilityDeniedException(message: String) : SecurityException(message)

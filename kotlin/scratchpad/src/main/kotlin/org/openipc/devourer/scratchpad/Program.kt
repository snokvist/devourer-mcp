package org.openipc.devourer.scratchpad

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A scratchpad program.
 *
 * Shaped as *sources sampled on a schedule, derived values computed from them,
 * and a view over the result* rather than as a script, because that is what the
 * motivating requests actually are: "poll this camera every 500 ms while
 * monitoring its Wi-Fi and show me RSSI, retries and HTTP latency live" is a
 * dataflow, not an algorithm.
 *
 * The shape buys three things a script would not. It is inspectable before it
 * runs, so a reviewer can see exactly what it will touch. It is the same object
 * whether generated now or promoted and re-run next month. And there is nothing
 * in it that can do anything a declared capability does not name.
 *
 * What it gives up is control flow. A program that genuinely needs branching or
 * recursion cannot be expressed here, and that is the intended trade: such a
 * thing belongs in the experiment engine as a typed experiment, reviewed once,
 * rather than as model-authored code in the process that owns the radios.
 */
@Serializable
public data class ScratchpadProgram(
    val name: String,
    /** What this program is for. Kept with it so a promoted tool explains itself. */
    val purpose: String = "",
    /** Capability ids from [Capability]. Anything not listed is unavailable. */
    val capabilities: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
    val computed: List<Computed> = emptyList(),
    val ui: UiSpec? = null,
    /** Stop after this long. Clamped by the grant's own ceiling. */
    @SerialName("duration_ms") val durationMs: Long = 30_000,
) {
    public fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (name.isBlank()) problems += "name is required"
        capabilities.forEach {
            if (Capability.byId(it) == null) {
                problems += "unknown capability '$it' (have: " +
                    Capability.entries.joinToString(", ") { c -> c.id } + ")"
            }
        }
        if (sources.isEmpty()) problems += "a program with no sources would measure nothing"

        val declared = mutableSetOf<String>()
        (sources.map { it.id } + computed.map { it.id }).forEach {
            if (!declared.add(it)) problems += "duplicate series id '$it'"
        }
        sources.forEach { problems += it.validate() }
        computed.forEach { c ->
            if (c.expr.isBlank()) problems += "computed '${c.id}' has no expression"
        }
        ui?.let { problems += it.validate(seriesIds()) }
        if (durationMs !in 100..3_600_000) problems += "duration_ms must be 100..3600000"
        return problems
    }

    /**
     * Every series name this program will produce.
     *
     * Not simply the source ids: an [HttpPollSource] named `cam` yields
     * `cam.latency_ms`, `cam.status` and one series per extracted field. This is
     * the single definition of that expansion — it was briefly computed
     * separately in the validator and the interpreter, and the two drifted, so a
     * widget charting `cam.latency_ms` was rejected as referencing an unknown
     * series while the interpreter was happily producing it.
     */
    public fun seriesIds(): Set<String> = buildSet {
        sources.forEach { source ->
            add(source.id)
            if (source is HttpPollSource) {
                add("${source.id}.latency_ms")
                add("${source.id}.status")
                source.extract.keys.forEach { add("${source.id}.$it") }
            }
        }
        computed.forEach { add(it.id) }
    }

    /** Capabilities this program's steps actually need, derived from its content. */
    public fun requiredCapabilities(): Set<Capability> = buildSet {
        if (sources.isNotEmpty()) add(Capability.TIMER)
        if (computed.isNotEmpty()) add(Capability.METRICS)
        sources.forEach { addAll(it.requires()) }
        if (ui != null) add(Capability.UI)
    }

    /** Capabilities the program needs but did not declare. */
    public fun undeclared(): Set<Capability> =
        requiredCapabilities().filterNot { it.id in capabilities }.toSet()
}

/** A thing sampled on a schedule. */
@Serializable
public sealed interface Source {
    public val id: String

    /** Sampling period. */
    public val everyMs: Long

    public fun validate(): List<String>
    public fun requires(): Set<Capability>
}

/**
 * A scalar pulled from a live capture's rolling summary.
 *
 * [window_ms] matters: without it a long run's "mean RSSI" is the mean since
 * the program started, which stops responding to anything long before the run
 * ends. The window makes it a live measurement rather than a lifetime average.
 */
@Serializable
@SerialName("capture.metric")
public data class CaptureMetricSource(
    override val id: String,
    @SerialName("capture_id") val captureId: String,
    /** One of: frames, frames_per_second, rssi_mean, rssi_max, snr_mean,
     *  retries, retry_rate, crc_errors, crc_rate, aggregated, aggregation_rate. */
    val metric: String,
    @SerialName("every_ms") override val everyMs: Long = 500,
    @SerialName("window_ms") val windowMs: Long = 2_000,
    /**
     * Optional 802.11 frame-kind filter, e.g. "data/qos-data".
     *
     * Named `frame_kind` and not `kind` because `kind` is the polymorphic
     * discriminator for [Source] itself. When both were called `kind`,
     * deserializing `{"kind":"capture.metric", ...}` assigned the discriminator
     * value into this field, every frame was then filtered against a frame kind
     * named "capture.metric", and the series came back permanently empty with
     * nothing to indicate why.
     */
    @SerialName("frame_kind") val frameKind: String? = null,
    /** Optional transmitter MAC filter. */
    val transmitter: String? = null,
) : Source {
    override fun validate(): List<String> = buildList {
        if (metric !in METRICS) add("unknown metric '$metric' for '$id' (have: $METRICS)")
        if (everyMs !in 50..600_000) add("'$id' every_ms must be 50..600000")
        if (windowMs < everyMs) add("'$id' window_ms should be at least every_ms")
    }

    override fun requires(): Set<Capability> = setOf(Capability.CAPTURE_READ)

    public companion object {
        public val METRICS: List<String> = listOf(
            "frames", "frames_per_second", "rssi_mean", "rssi_max", "snr_mean",
            "retries", "retry_rate", "crc_errors", "crc_rate",
            "aggregated", "aggregation_rate",
        )
    }
}

/**
 * An HTTP GET, sampled on a timer.
 *
 * Records latency always, and optionally extracts numbers from the response.
 * The extractor is a flat JSON path (`a.b.c`) rather than a general JSONPath:
 * the needed cases are all flat, and a general matcher is another expression
 * language to get wrong.
 */
@Serializable
@SerialName("http.poll")
public data class HttpPollSource(
    override val id: String,
    val url: String,
    @SerialName("every_ms") override val everyMs: Long = 1_000,
    @SerialName("timeout_ms") val timeoutMs: Long = 2_000,
    /** series id -> dotted path into the JSON response. */
    val extract: Map<String, String> = emptyMap(),
) : Source {
    override fun validate(): List<String> = buildList {
        if (runCatching { java.net.URI(url) }.isFailure) add("'$id' has an unparseable url")
        if (everyMs !in 50..600_000) add("'$id' every_ms must be 50..600000")
        if (timeoutMs !in 10..60_000) add("'$id' timeout_ms must be 10..60000")
        if (timeoutMs > everyMs) {
            add("'$id' timeout_ms ($timeoutMs) exceeds every_ms ($everyMs): polls would overlap")
        }
    }

    override fun requires(): Set<Capability> = setOf(Capability.HTTP_GET)
}

/** A scalar read from a radio's live state. */
@Serializable
@SerialName("radio.metric")
public data class RadioMetricSource(
    override val id: String,
    val session: Int,
    /** One of: tx_submitted, tx_failed, monitor_frames, monitor_dropped. */
    val metric: String,
    @SerialName("every_ms") override val everyMs: Long = 1_000,
) : Source {
    override fun validate(): List<String> = buildList {
        if (metric !in METRICS) add("unknown radio metric '$metric' (have: $METRICS)")
        if (everyMs !in 50..600_000) add("'$id' every_ms must be 50..600000")
    }

    override fun requires(): Set<Capability> = setOf(Capability.RADIO_DESCRIBE)

    public companion object {
        public val METRICS: List<String> = listOf(
            "tx_submitted", "tx_failed", "monitor_frames", "monitor_dropped",
        )
    }
}

/**
 * A scalar read from a finished experiment's result.
 *
 * The experiment engine is where rate sweeps, power sweeps and delivery
 * measurements live; without this a live view can chart a radio's raw counters
 * but not the answer a run produced. Reads one metric from one point, or
 * reduces it across every point.
 */
@Serializable
@SerialName("experiment.metric")
public data class ExperimentMetricSource(
    override val id: String,
    @SerialName("experiment_id") val experimentId: String,
    /**
     * One of: delivery_ratio, frames_received, goodput_bytes_per_sec,
     * rssi_mean, snr_mean, tx_accepted, crc_errors, duplicates, out_of_order,
     * longest_gap.
     */
    val metric: String,
    @SerialName("every_ms") override val everyMs: Long = 1_000,
    /** Exact `PointResult.point` label to read, e.g. "MCS7/20". */
    val point: String? = null,
    /** Zero-based index into the result's points. */
    @SerialName("point_index") val pointIndex: Int? = null,
    /**
     * How to reduce across every point when neither [point] nor [pointIndex] is
     * given. One of last|first|mean|min|max|sum|count; default last. Every
     * aggregate reduces over the points that produced a value for this metric;
     * an unmeasured point is not a zero, and `count` counts only those.
     */
    val aggregate: String? = null,
) : Source {
    override fun validate(): List<String> = buildList {
        if (experimentId.isBlank()) add("'$id' needs an experiment_id")
        if (metric !in METRICS) {
            add("unknown experiment metric '$metric' for '$id' (have: $METRICS)")
        }
        if (everyMs !in 50..600_000) add("'$id' every_ms must be 50..600000")
        if (point != null && pointIndex != null) {
            add("'$id' takes either point or point_index, not both")
        }
        if (point != null && point.isBlank()) add("'$id' point must not be blank")
        if (pointIndex != null && pointIndex < 0) add("'$id' point_index must be >= 0")
        if (aggregate != null && (point != null || pointIndex != null)) {
            add("'$id' aggregate applies across points; drop point/point_index")
        }
        if (aggregate != null && aggregate !in AGGREGATES) {
            add("unknown aggregate '$aggregate' for '$id' (have: $AGGREGATES)")
        }
    }

    override fun requires(): Set<Capability> = setOf(Capability.EXPERIMENT_READ)

    public companion object {
        // Duplicated from the experiment module on purpose: the sandbox module
        // does not depend on :experiment, so the interpreter only ever sees a
        // primitive Double returned by its host.
        public val METRICS: List<String> = listOf(
            "delivery_ratio", "frames_received", "goodput_bytes_per_sec",
            "rssi_mean", "snr_mean", "tx_accepted", "crc_errors",
            "duplicates", "out_of_order", "longest_gap",
        )
        public val AGGREGATES: List<String> = listOf(
            "last", "first", "mean", "min", "max", "sum", "count",
        )
    }
}

/** A value derived from other series by a bounded arithmetic expression. */
@Serializable
public data class Computed(
    val id: String,
    /** e.g. `retries / max(frames, 1) * 100`. See [Expr] for the grammar. */
    val expr: String,
    val unit: String = "",
)

/** How to present the result. */
@Serializable
public data class UiSpec(
    val title: String = "",
    val widgets: List<Widget> = emptyList(),
) {
    public fun validate(knownSeries: Set<String>): List<String> = buildList {
        widgets.forEach { w ->
            w.series.forEach { s ->
                if (s !in knownSeries) add("widget references unknown series '$s'")
            }
        }
    }
}

@Serializable
public data class Widget(
    /** chart | gauge | table | log | stat */
    val kind: String,
    val title: String = "",
    val series: List<String> = emptyList(),
    val unit: String = "",
    val min: Double? = null,
    val max: Double? = null,
)

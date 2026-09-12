package org.openipc.devourer.experiment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.radio.SafetyLevel
import org.openipc.devourer.radio.parseChannelSpec

/**
 * One measurement point: every condition that varies, resolved.
 *
 * A point carries its own channel and frame shape rather than inheriting them
 * from the run, because a sweep over channels is the same machinery as a
 * sweep over rates and splitting the two would mean two runners.
 */
@Serializable
public data class SweepPoint(
    /** TX mode, e.g. `6M`, `MCS5/20`, `VHT1/80`. */
    val mode: String,
    val channel: ChannelLabel,
    @SerialName("frame_bytes") val frameBytes: Int,
    @SerialName("interval_us") val intervalUs: Int,
) {
    /** Stable, greppable, and short enough to be a chart axis. */
    public val label: String
        get() = buildString {
            append(mode)
            append(" @").append(channel.text)
            append(" ").append(frameBytes).append("B")
            if (intervalUs != DEFAULT_INTERVAL_US) append(" /").append(intervalUs).append("us")
        }

    public companion object {
        public const val DEFAULT_INTERVAL_US: Int = 1_000
    }
}

/** A channel that survives serialization as the text a caller wrote. */
@Serializable
public data class ChannelLabel(val text: String) {
    public fun spec(): ChannelSpec = parseChannelSpec(text)

    public companion object {
        public fun of(spec: ChannelSpec): ChannelLabel =
            ChannelLabel("ch${spec.channel}/${spec.width.mhz}")
    }
}

/**
 * The axes to sweep, expanded into points by [expand].
 *
 * Every axis left empty takes the run's single default, so the common case —
 * "sweep these three rates, hold everything else" — stays one list. The
 * expansion is a cartesian product, which is why it is bounded: four axes of
 * five values each is 625 points, and an experiment that would take nine
 * hours should be refused at the point it is described rather than discovered
 * by watching it.
 */
@Serializable
public data class Sweep(
    val modes: List<String> = emptyList(),
    /** Channel specs as text, e.g. `["ch1", "ch6/40"]`. */
    val channels: List<String> = emptyList(),
    @SerialName("frame_bytes") val frameBytes: List<Int> = emptyList(),
    @SerialName("interval_us") val intervalUs: List<Int> = emptyList(),
) {
    /** Which axes actually vary. Used to describe the run in its conclusion. */
    public val axes: List<String>
        get() = buildList {
            if (modes.size > 1) add("mode")
            if (channels.size > 1) add("channel")
            if (frameBytes.size > 1) add("frame_bytes")
            if (intervalUs.size > 1) add("interval_us")
        }

    public fun expand(default: SweepPoint): List<SweepPoint> {
        val m = modes.ifEmpty { listOf(default.mode) }
        val c = channels.ifEmpty { listOf(default.channel.text) }
        val b = frameBytes.ifEmpty { listOf(default.frameBytes) }
        val i = intervalUs.ifEmpty { listOf(default.intervalUs) }

        b.forEach {
            require(it in MIN_FRAME_BYTES..MAX_FRAME_BYTES) {
                "frame_bytes $it is outside $MIN_FRAME_BYTES..$MAX_FRAME_BYTES"
            }
        }
        i.forEach { require(it in 0..1_000_000) { "interval_us $it is outside 0..1000000" } }

        val total = m.size.toLong() * c.size * b.size * i.size
        if (total > MAX_POINTS) {
            throw ExperimentException(
                "that sweep expands to $total points (max $MAX_POINTS). Axes: " +
                    "${m.size} modes x ${c.size} channels x ${b.size} sizes x ${i.size} " +
                    "intervals. Narrow one axis, or run it as several experiments so each " +
                    "one's result stays interpretable.",
            )
        }

        val out = ArrayList<SweepPoint>(total.toInt())
        // Channel outermost: retuning is the expensive move (~130ms on a
        // Realtek), so ordering this way changes it once per group instead of
        // once per point.
        for (ch in c) {
            val label = ChannelLabel(ch)
            label.spec() // parse now, so a typo fails before any radio is touched
            for (mode in m) {
                for (bytes in b) {
                    for (us in i) out += SweepPoint(mode, label, bytes, us)
                }
            }
        }
        return out
    }

    public companion object {
        /**
         * Enough for a full rate ladder at two widths; far short of anything
         * that would outlive the operator's attention.
         */
        public const val MAX_POINTS: Int = 64

        /** A frame shorter than a 802.11 header cannot carry a probe tag. */
        public const val MIN_FRAME_BYTES: Int = 40

        /** Well under the 2304-byte MSDU limit; a probe is not a throughput test. */
        public const val MAX_FRAME_BYTES: Int = 1_500
    }
}

/**
 * Who plays what, and what varies.
 *
 * Roles are a map rather than two parameters because the useful experiments
 * on this bench are the multi-witness ones. [RadioRole.MONITOR] and
 * [RadioRole.MONITOR_2] listening to the same burst is how a receiver-side
 * doubt gets eliminated, and it is the only way to settle a question about
 * antennas: swap two receivers between positions with the transmitter fixed.
 */
@Serializable
public data class ExperimentSpec(
    val roles: Map<RadioRole, Int>,
    val sweep: Sweep = Sweep(),
    val bounds: ExperimentBounds = ExperimentBounds(),
    /** The point every unswept axis falls back to. */
    @SerialName("base_point") val basePoint: SweepPoint,
    @SerialName("carrier_sense") val carrierSense: Boolean = true,
    val safety: SafetyLevel = SafetyLevel.NORMAL,
) {
    val transmitter: Int
        get() = roles[RadioRole.TX_PEER]
            ?: throw ExperimentException("no TX_PEER: nothing would transmit")

    /**
     * Every adapter that listens, in a stable order.
     *
     * [RadioRole.RX_PEER] first — it is the witness the conclusion is drawn
     * from. The rest corroborate.
     */
    val witnesses: List<Pair<RadioRole, Int>>
        get() = WITNESS_ORDER.mapNotNull { role -> roles[role]?.let { role to it } }

    init {
        val tx = roles[RadioRole.TX_PEER]
            ?: throw ExperimentException(
                "no TX_PEER assigned: an experiment with nothing transmitting has no stimulus",
            )
        if (witnesses.isEmpty()) {
            throw ExperimentException(
                "no witness assigned. A transmitter cannot witness itself — assign at least " +
                    "RX_PEER to a different adapter, or the result proves nothing about the air.",
            )
        }
        witnesses.forEach { (role, session) ->
            if (session == tx) {
                throw ExperimentException(
                    "$role is the same adapter as TX_PEER (session $session). A radio " +
                        "cannot witness itself: it would report its own TX path, not the air.",
                )
            }
        }
        val duplicated = witnesses.groupBy { it.second }.filterValues { it.size > 1 }
        if (duplicated.isNotEmpty()) {
            val (session, roles) = duplicated.entries.first()
            throw ExperimentException(
                "session $session is assigned to ${roles.joinToString(" and ") { it.first.name }}. " +
                    "Two roles on one adapter would be counted as two independent witnesses.",
            )
        }
    }

    public companion object {
        private val WITNESS_ORDER = listOf(
            RadioRole.RX_PEER, RadioRole.MONITOR, RadioRole.MONITOR_2,
        )
    }
}

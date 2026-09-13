package org.openipc.devourer.experiment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.radio.VerificationState

/**
 * What an experiment is, in this system.
 *
 * The model declares *what to find out*; deterministic local code runs the
 * packet loops, the timing and the arithmetic. That split is the whole point —
 * a model sending frames one tool call at a time cannot measure anything at
 * millisecond scale, and its conclusions would be about the tool-call latency
 * rather than the radio.
 *
 * Every experiment is bounded, cancellable, and returns evidence compact enough
 * to reason over without ever moving the packets.
 */
@Serializable
public data class ExperimentBounds(
    /** Hard ceiling on the whole run. Enforced regardless of what the sweep asks for. */
    @SerialName("max_duration_ms") val maxDurationMs: Long = 60_000,
    /** Frames transmitted per measurement point. */
    @SerialName("frames_per_point") val framesPerPoint: Int = 200,
    /** Spacing between transmitted frames. */
    @SerialName("interval_us") val intervalUs: Int = 1_000,
    /** How long to keep listening after the last frame before declaring loss. */
    @SerialName("settle_ms") val settleMs: Long = 300,
) {
    init {
        require(maxDurationMs in 1..600_000) { "max_duration_ms must be 1..600000" }
        require(framesPerPoint in 1..100_000) { "frames_per_point must be 1..100000" }
        require(intervalUs in 0..1_000_000) { "interval_us must be 0..1000000" }
        require(settleMs in 0..60_000) { "settle_ms must be 0..60000" }
        // Each bound is individually legal and the product is not: 100000
        // frames at 1s spacing is a 27-hour point, which a 60-second run
        // ceiling does nothing about because the ceiling is only checked
        // between points. Refusing here is the difference between an
        // experiment that is bounded and one that merely looks bounded.
        require(estimatedPointMs <= maxDurationMs) {
            "one point would take ${estimatedPointMs}ms (${framesPerPoint} frames at " +
                "${intervalUs}us plus ${settleMs}ms settle) but the whole run is capped at " +
                "${maxDurationMs}ms. The cap is only tested between points, so this would " +
                "run to completion regardless — lower frames_per_point or interval_us."
        }
    }

    /** Wall time one point will take, ignoring per-call overhead. */
    public val estimatedPointMs: Long
        get() = (framesPerPoint.toLong() * intervalUs / 1000) + settleMs

    /**
     * Hard per-point deadline.
     *
     * Generous relative to the estimate on purpose: the point of this is to
     * bound a bridge that has stopped answering, not to fail a burst that ran
     * slow. A wedged adapter is a real failure mode here — an undrained
     * MT7612U receiver stops responding below the USB level — and without
     * this the run waits on it forever with the radio still claimed.
     */
    @Transient
    public val pointTimeoutMs: Long =
        (estimatedPointMs * 3 + 10_000).coerceAtMost(maxDurationMs + 10_000)
}

/**
 * The role an adapter plays.
 *
 * Named rather than positional because a two-radio experiment reads very
 * differently depending on which end is under test, and a result that does not
 * record the roles cannot be interpreted later.
 */
@Serializable
public enum class RadioRole {
    /** The adapter being characterized. */
    DUT,

    /** Transmits the stimulus. */
    TX_PEER,

    /** Receives it, and is the independent witness that makes TX_VERIFIED possible. */
    RX_PEER,

    /** Watches without participating. */
    MONITOR,

    /** A second independent observer, for cross-checking a monitor's own bias. */
    MONITOR_2,
}

/** One point in a sweep: the conditions, and what was measured under them. */
@Serializable
public data class PointResult(
    /** The swept variable's value, e.g. "MCS5/20" or "ch36/80". */
    val point: String,
    @SerialName("frames_sent") val framesSent: Int,
    /**
     * Distinct burst sequence numbers the receiver actually saw, or null when
     * this point was never measured.
     *
     * Null and zero are different answers and the difference is the whole
     * point of this project. A point whose transmit call did not return
     * within its deadline delivered no evidence at all; reporting that as
     * `0` puts a measured-looking dot on a chart where there is no
     * measurement. Serialization drops nulls, so an unmeasured point carries
     * neither field rather than a confident zero.
     */
    @SerialName("frames_received") val framesReceived: Int?,
    @SerialName("delivery_ratio") val deliveryRatio: Double?,
    /** Frames seen more than once — retransmission or a capture-side duplicate. */
    val duplicates: Int = 0,
    /** Received with a sequence lower than one already seen. */
    @SerialName("out_of_order") val outOfOrder: Int = 0,
    @SerialName("longest_gap") val longestGap: Int = 0,
    @SerialName("rssi_mean") val rssiMean: Double? = null,
    @SerialName("rssi_min") val rssiMin: Int? = null,
    @SerialName("rssi_max") val rssiMax: Int? = null,
    @SerialName("snr_mean") val snrMean: Double? = null,
    @SerialName("crc_errors") val crcErrors: Int = 0,
    @SerialName("tx_accepted") val txAccepted: Int = 0,
    @SerialName("tx_elapsed_ms") val txElapsedMs: Double = 0.0,
    /**
     * Frames the transmit loop could not start on their scheduled slot.
     *
     * Nonzero means the burst was not the cadence that was requested — frames
     * bunched to catch up after a stall — so any conclusion about timing,
     * spacing or inter-frame behaviour is about the transmitter's TX path, not
     * about the channel. Measured, not assumed: the MT7612U stalls for several
     * milliseconds periodically where an RTL8812AU does not.
     */
    @SerialName("tx_late_frames") val txLateFrames: Int = 0,
    @SerialName("tx_max_late_us") val txMaxLateUs: Long = 0,
    /** The probe MPDU size in bytes, from the point. */
    @SerialName("frame_bytes") val frameBytes: Int = 0,
    /**
     * Delivered MAC-layer goodput for this point, in bytes/s: the primary
     * witness's received frame count times [frameBytes] (the whole MPDU, not
     * just the payload body) over the transmit burst's elapsed time.
     *
     * Delivered bytes, not channel occupancy — that distinction is the whole
     * reason A-MPDU is interesting. Null when the point was not measured, so an
     * unmeasured point carries no throughput number.
     */
    @SerialName("goodput_bytes_per_sec") val goodputBytesPerSec: Double? = null,
    /**
     * Every witness that heard this point, keyed by role.
     *
     * The top-level fields above are the RX_PEER's view — the one the
     * conclusion is built from. This map is what makes a *simultaneous*
     * comparison possible, and simultaneity is the whole value: the
     * carrier-sense finding on this bench only became conclusive when two
     * MT7612U receivers witnessing the same burst agreed exactly (3/3, 7/7,
     * 13/13), which is what ruled out the receiver. Two runs, one per
     * witness, could not have done that — the air changes between them.
     */
    val witnesses: Map<String, WitnessResult> = emptyMap(),
    /**
     * What the TRANSMITTER's own PHY saw on this channel with nothing of ours
     * on the air.
     *
     * Here rather than on the witnesses on purpose. When a burst is accepted
     * and not aired, the radio that decided not to transmit is the
     * transmitter, and carrier sense acts on energy rather than on frames a
     * receiver could decode. This is that energy, measured by the deciding
     * radio, over a fixed idle dwell taken after tuning and before any burst.
     *
     * Null when the transmitter is not a Realtek part: no other family
     * exposes the counters, and a zero would be a fabricated reading.
     */
    @SerialName("channel_energy") val channelEnergy: RxEnergy? = null,
    /**
     * The TX-power offset this point requested, in quarter-dB, when the sweep
     * had a power axis. [powerAppliedQdb] is what the radio actually took after
     * quantization and rail clamps — they differ when a request fell between
     * steps or hit a rail.
     */
    @SerialName("power_offset_qdb") val powerOffsetQdb: Int? = null,
    @SerialName("power_applied_qdb") val powerAppliedQdb: Int? = null,
    val note: String? = null,
)

/** What one witness heard, for one point. */
@Serializable
public data class WitnessResult(
    /** The [RadioRole] name this adapter played. */
    val role: String,
    val label: String,
    val session: Int,
    @SerialName("frames_received") val framesReceived: Int,
    @SerialName("delivery_ratio") val deliveryRatio: Double,
    val duplicates: Int = 0,
    @SerialName("out_of_order") val outOfOrder: Int = 0,
    @SerialName("longest_gap") val longestGap: Int = 0,
    @SerialName("rssi_mean") val rssiMean: Double? = null,
    @SerialName("rssi_min") val rssiMin: Int? = null,
    @SerialName("rssi_max") val rssiMax: Int? = null,
    @SerialName("snr_mean") val snrMean: Double? = null,
    @SerialName("crc_errors") val crcErrors: Int = 0,
)

/**
 * A complete experiment run.
 *
 * [verification] is the claim this run supports, and it is computed from the
 * evidence rather than asserted: a link probe that received nothing yields
 * `FAILED`, not a quiet zero.
 */
@Serializable
public data class ExperimentResult(
    val id: String,
    val kind: String,
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    val roles: Map<String, String>,
    val channel: String,
    /**
     * Whether the transmitter's MAC carrier sense was on.
     *
     * Load-bearing for interpretation, not a footnote: with it on, the delivery
     * ratio includes the MAC's own decision not to transmit; with it off, the
     * measurement is of the radio link alone. The two are not comparable, and a
     * result that did not say which would be unusable later.
     */
    @SerialName("carrier_sense_enabled") val carrierSenseEnabled: Boolean = true,
    val bounds: ExperimentBounds,
    val points: List<PointResult>,
    val verification: VerificationState,
    /** The one-line answer to the question the experiment was asked. */
    val conclusion: String,
    /** Anything that limits how far the conclusion can be taken. */
    val caveats: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/** Raised when an experiment cannot be run as specified. */
public class ExperimentException(message: String) : RuntimeException(message)

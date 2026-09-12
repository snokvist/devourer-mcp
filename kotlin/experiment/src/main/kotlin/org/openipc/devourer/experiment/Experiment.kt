package org.openipc.devourer.experiment

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openipc.devourer.protocol.ChannelSpec
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
    }

    /** Wall time one point will take, ignoring per-call overhead. */
    public val estimatedPointMs: Long
        get() = (framesPerPoint.toLong() * intervalUs / 1000) + settleMs
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
    /** Distinct burst sequence numbers the receiver actually saw. */
    @SerialName("frames_received") val framesReceived: Int,
    @SerialName("delivery_ratio") val deliveryRatio: Double,
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
    val note: String? = null,
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

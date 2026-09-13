package org.openipc.devourer.radio

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.openipc.devourer.protocol.CcaGates
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.MonitorStats
import org.openipc.devourer.protocol.RadioListResult
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.protocol.RxGain
import org.openipc.devourer.protocol.UsbDevice

/** The channel a radio is currently tuned to, as the bridge reports it. */
@Serializable
public data class ChannelInfo(
    val channel: Int = 0,
    /** Bandwidth in MHz, matching what the tools accept. */
    val width: Int = 0,
    val offset: Int = 0,
    val band: Int = 0,
)

@Serializable
public data class RadioState(
    @SerialName("brought_up") val broughtUp: Boolean = false,
    val monitoring: Boolean = false,
    /**
     * Carrier sense is currently OFF on this radio.
     *
     * Surfaced because it is a state someone can walk away from: a radio left
     * transmitting without listening keeps doing so until the session closes.
     * It also changes how any measurement taken now must be read.
     */
    @SerialName("cca_disabled") val carrierSenseDisabled: Boolean = false,
)

@Serializable
public data class OpenRadio(
    val session: Int,
    val device: UsbDevice,
    val capabilities: RadioCapabilities,
    @SerialName("permanent_mac") val permanentMac: String? = null,
    val state: RadioState = RadioState(),
    val channel: ChannelInfo? = null,
) {
    /** A short, stable name for logs and MCP replies. */
    public val label: String
        get() = buildString {
            append(capabilities.chip.ifBlank { device.usbId })
            append(" @ ").append(device.locator)
        }
}

/**
 * Radios as concepts, not as bridge ops.
 *
 * The contract everything above the transport is written against. It exists
 * for a reason the test count makes concrete: while the only radio type was a
 * concrete class wrapping a concrete socket, `experiment`, `characterize` and
 * `mcp` had **zero** tests between them — none of them could be constructed
 * without an adapter on the bench. `scratchpad`, whose host is an interface,
 * had twenty-six.
 *
 * It is deliberately the *whole* radio surface rather than the subset one
 * caller happens to need. A narrow per-caller interface would push each new
 * consumer to reach for [RadioManager] directly and re-create the problem.
 */
public interface Radios {
    public suspend fun connect()

    /**
     * Every adapter devourer could plausibly drive.
     *
     * Note [UsbDevice.identification]: for Realtek 11ac parts the backend is
     * genuinely unknown until [open] reads the chip id. Callers must not
     * present `PROBE_REQUIRED` as an identification.
     */
    public suspend fun list(includeAll: Boolean = false): RadioListResult

    /** Opens and claims an adapter. Does not power the chip — monitoring does. */
    /**
     * @param noiseFloor ask devourer for an absolute frame-free noise floor in
     *  [rxEnergy]. Set at open because the backend reads its config once. It
     *  is unreachable on Realtek wave-1 parts through this bridge — see
     *  [RxEnergy.noiseFloorWhy], which says which kind of absent applies.
     */
    public suspend fun open(
        bus: Int,
        address: Int,
        reset: Boolean = true,
        noiseFloor: Boolean = false,
        adaptiveGain: Boolean = false,
    ): OpenRadio

    public suspend fun describe(session: Int): OpenRadio

    public suspend fun close(session: Int)

    public suspend fun sessions(): JsonObject

    /** Starts monitor capture, refusing anything the adapter cannot actually do. */
    public suspend fun startMonitor(session: Int, channel: ChannelSpec): JsonObject

    public suspend fun stopMonitor(session: Int): JsonObject

    public suspend fun stats(session: Int): MonitorStats

    public suspend fun retune(session: Int, channel: ChannelSpec): JsonObject

    public fun frames(session: Int): Flow<FrameRecord>

    /**
     * Transmits a probe burst: a structured TX mode plus an 802.11 MPDU, with
     * a per-frame sequence counter stamped by the implementation.
     *
     * Distinct from [sendFrame] in that the radiotap header is built from
     * [mode] using devourer's own builder rather than shipped pre-assembled by
     * the caller. Stamping happens below this interface because the counter
     * has to change between frames inside the tight send loop — doing it above
     * would mean one round trip per frame, and the measurement would be of
     * that.
     */
    public suspend fun sendProbe(
        session: Int,
        frameHex: String,
        mode: String,
        count: Int,
        intervalUs: Int,
        sequenceOffset: Int,
    ): JsonObject

    /**
     * Turn the MAC's carrier-sense gate off or on.
     *
     * EXPERIMENTAL, and antisocial with it: a radio with carrier sense off
     * transmits without listening, so it will talk over anyone sharing the
     * channel. Always restore it.
     *
     * It exists because it is sometimes the only way to measure anything. On
     * this bench an RTL8812AU aired 4-13% of the frames it accepted on a
     * channel carrying almost no traffic, while reporting 100 submitted and 0
     * failed. With carrier sense off the same burst delivered 88-100%. A
     * delivery ratio measured either way is a different quantity, which is why
     * every result records which.
     */
    public suspend fun setCarrierSense(
        session: Int,
        enabled: Boolean,
        safety: SafetyLevel = SafetyLevel.NORMAL,
    ): JsonObject

    /**
     * The receive-gain index, the envelope it may be clamped to, and whether an
     * adaptive loop is driving it — plus what that loop keys on, or why it is
     * inert. Read-only and ungated: seeing the gain changes nothing.
     *
     * The index is a relative register value, not a dBm figure, so it is only
     * meaningful alongside [RxGain.indexStepDb] and the [RxGain.indexName]
     * that says what the family calls it. `supported = false` names the
     * backend that has no such index; `valid = false` means the baseband is
     * not up yet, which a caller can fix by bringing the radio up.
     */
    public suspend fun rxGain(session: Int): RxGain

    /**
     * Clamp the receive-gain index to `[minIndex, maxIndex]`; `min == max`
     * pins it. Bounds are checked against the adapter's reported envelope, not
     * guessed, and an out-of-range or inverted request is refused rather than
     * coerced.
     *
     * This steers an adaptive loop rather than replacing it: within the clamp
     * the loop keeps running. It cannot manufacture sensitivity the hardware
     * does not have, and on Realtek it moves the EDCCA threshold too — the two
     * are coupled — so a gain change is a carrier-sense change as well.
     */
    public suspend fun clampRxGain(session: Int, minIndex: Int, maxIndex: Int): RxGain

    /**
     * The two carrier-sense gates reported separately. Read-only and ungated.
     *
     * `supported = false` means the backend has no split to report; a caller
     * then has only [setCarrierSense], which moves both gates together.
     */
    public suspend fun ccaGates(session: Int): CcaGates

    /**
     * Set the carrier-sense gates one bit at a time. A null argument leaves
     * that gate untouched; at least one must be supplied.
     *
     * EXPERIMENTAL when disabling either gate, for the same reason as
     * [setCarrierSense]: a radio with a gate off transmits without fully
     * listening. Turning a gate back ON is always allowed, so a failure path
     * can restore good behaviour without re-asking.
     */
    public suspend fun setCcaGates(
        session: Int,
        primaryCcaDisabled: Boolean? = null,
        edccaDisabled: Boolean? = null,
        safety: SafetyLevel = SafetyLevel.NORMAL,
    ): CcaGates

    /**
     * What this radio's own PHY sees on the channel, without decoding a frame.
     *
     * The measurement that separates "the channel is busy" from "a receiver
     * can decode a lot here" — two things a frame count cannot tell apart,
     * and the distinction carrier sense actually acts on.
     *
     * Counters are deltas since the previous call, which resets them: to
     * measure a window, read once and discard, wait, read again. [withNhm]
     * adds a 12-bucket in-band power histogram and costs about 2ms.
     */
    public suspend fun rxEnergy(session: Int, withNhm: Boolean = false): RxEnergy

    public suspend fun txStats(session: Int): JsonObject

    public suspend fun activeRxPaths(session: Int): JsonObject

    /**
     * Transmits a caller-assembled frame a bounded number of times.
     *
     * What comes back is the count the TX path ACCEPTED. That is not evidence
     * anything reached the air — only an independent receiver can establish
     * that, which is why [VerificationState.TX_VERIFIED] requires one.
     */
    public suspend fun sendFrame(
        session: Int,
        frameHex: String,
        count: Int = 1,
        safety: SafetyLevel = SafetyLevel.NORMAL,
    ): JsonObject
}

/**
 * The safety gates, in one place.
 *
 * Named functions rather than inline checks so that every implementation of
 * [Radios] — including the test fake — enforces the *same* rule rather than a
 * copy of it. A fake that reimplemented the gate would pass its own tests
 * while the production gate rotted, which is the failure this project has
 * already made twice in other guises.
 */
public object RadioSafety {
    /**
     * Turning carrier sense back ON is always allowed: restoring good
     * behaviour must never be blocked by a missing argument, including on a
     * failure path.
     */
    public fun gateCarrierSense(enabled: Boolean, safety: SafetyLevel) {
        if (!enabled) {
            SafetyLevelException.require(
                "disabling carrier sense", SafetyLevel.EXPERIMENTAL, safety,
            )
        }
    }

    /**
     * A pre-assembled radiotap header plus MPDU is validated against nothing —
     * not the adapter's capability report, not the channel, not the width.
     * That is the whole point of the path and exactly why it is not the normal
     * one.
     */
    public fun gateRawFrame(safety: SafetyLevel) {
        SafetyLevelException.require(
            "transmitting a raw pre-assembled frame", SafetyLevel.DEVELOPER, safety,
        )
    }

    /**
     * Disabling a split carrier-sense gate is the same antisocial act as
     * [gateCarrierSense], and requires the same level. A `null` argument means
     * "leave this gate alone" and must not trip the gate; enabling one (false)
     * is always allowed.
     */
    public fun gateCcaGates(
        primaryCcaDisabled: Boolean?,
        edccaDisabled: Boolean?,
        safety: SafetyLevel,
    ) {
        if (primaryCcaDisabled == true || edccaDisabled == true) {
            SafetyLevelException.require(
                "disabling a carrier-sense gate", SafetyLevel.EXPERIMENTAL, safety,
            )
        }
    }
}

/**
 * Checks a requested channel against the adapter's reported capability, and
 * reports whether the channel is merely tunable or actually calibrated.
 *
 * A free function rather than a [Radios] method: it is pure given the radio's
 * own capability report, so no implementation — least of all a fake — gets to
 * decide whether the gate applies.
 *
 * Returns a note when the request is legal but its TX power is extrapolated —
 * a caller doing power measurements needs to know that before it records a
 * number as a measurement.
 */
public fun requireChannelSupported(radio: OpenRadio, channel: ChannelSpec): String? {
    val caps = radio.capabilities
    if (!caps.supported) {
        throw CapabilityException(
            "tune", radio.label,
            "the backend reports no capability information for this chip",
        )
    }
    if (channel.width.mhz !in caps.widths) {
        throw CapabilityException(
            "use ${channel.width.mhz}MHz channels", radio.label,
            "it supports ${caps.widths.sorted().joinToString(", ")}MHz. " +
                "Refusing rather than silently capturing at a different width.",
        )
    }
    val mhz = centerFrequencyMhz(channel)
        ?: return "channel ${channel.channel} is outside the known numbering; " +
            "the radio may still tune it"
    if (!caps.canTune(mhz)) {
        throw CapabilityException(
            "tune ${mhz}MHz", radio.label,
            "its synthesizer covers ${caps.tune2g4.describe()} and ${caps.tune5g.describe()}",
        )
    }
    // "Outside the characterized range" and "this backend publishes no
    // characterized range" are different facts with different remedies, and
    // conflating them produces a warning that fires on every channel and so
    // gets ignored — which is worse than not warning at all.
    val hasAnyRange = caps.characterized2g4.valid || caps.characterized5g.valid
    if (!hasAnyRange) {
        return "${radio.label} publishes no TX-power characterized range, so whether " +
            "${mhz}MHz is calibrated is UNKNOWN rather than known-bad. Relative power " +
            "comparisons on one adapter remain meaningful; absolute dBm claims do not."
    }
    if (!caps.isCharacterized(mhz)) {
        return "${mhz}MHz is tunable but OUTSIDE this adapter's TX-power characterized " +
            "range (${caps.characterized2g4.describe()}, ${caps.characterized5g.describe()}). " +
            "Power there is extrapolated from the nearest calibrated channel — " +
            "treat absolute power readings as uncalibrated."
    }
    return null
}

/**
 * Channel number to centre frequency.
 *
 * Devourer drives the 5 GHz synthesizer far past the regulatory channels using
 * the vendor's `freq = 5000 + 5*chan` relation over channels 16..253, so that
 * rule is applied rather than a table of legal channels — the point of this
 * instrument is to reach where the hardware reaches, with the caller told when
 * it leaves calibrated air.
 */
public fun centerFrequencyMhz(channel: ChannelSpec): Int? = when {
    channel.band == 6 -> 5950 + 5 * channel.channel
    channel.channel == 14 -> 2484
    channel.channel in 1..13 -> 2407 + 5 * channel.channel
    channel.channel in 16..253 -> 5000 + 5 * channel.channel
    else -> null
}

private fun BandRange.describe(): String =
    if (valid) "$minMhz-$maxMhz MHz" else "none"

/** Parses a `ch36/80` style spec, the form a model is most likely to produce. */
public fun parseChannelSpec(text: String): ChannelSpec {
    val m = Regex("""^\s*(?:ch)?(\d+)\s*(?:[/@]\s*(\d+))?\s*(?:MHz)?\s*$""", RegexOption.IGNORE_CASE)
        .find(text)
        ?: throw IllegalArgumentException("cannot parse channel spec '$text' (try '36' or 'ch36/80')")
    val channel = m.groupValues[1].toInt()
    val width = m.groupValues[2].takeIf { it.isNotBlank() }?.toInt() ?: 20
    return ChannelSpec(channel = channel, width = ChannelWidth.ofMhz(width))
}

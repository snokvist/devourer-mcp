package org.openipc.devourer.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The bridge control plane: JSON Lines, one object per line.
 *
 * Requests are built as [JsonObject] rather than a sealed class per op on
 * purpose. The bridge is the authority on its own ops, and a closed Kotlin
 * hierarchy would have to be edited in lockstep with every native addition —
 * the coupling this boundary exists to avoid. Responses that matter are typed;
 * the rest stay as JSON for the layer that actually understands them.
 */
public object BridgeJson {
    public val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
}

@Serializable
public data class BridgeError(
    val code: String,
    val message: String,
)

@Serializable
public data class BridgeResponse(
    val id: Long? = null,
    val ok: Boolean = false,
    val result: JsonObject? = null,
    val error: BridgeError? = null,
)

/** Thrown when the bridge answers `ok:false`. [code] is a stable token. */
public class BridgeCallException(
    public val code: String,
    message: String,
    public val op: String,
) : RuntimeException("$op failed [$code]: $message")

@Serializable
public data class ProtocolVersion(val major: Int, val minor: Int)

@Serializable
public data class BackendReport(
    val name: String,
    val chips: String,
    /**
     * Whether this backend is in the running binary.
     *
     * `true` means it compiled — nothing more. It is not evidence that any
     * chip it covers was detected, initialized, or ever received a frame.
     */
    val compiled: Boolean,
)

@Serializable
public data class HelloResult(
    val protocol: ProtocolVersion,
    @SerialName("devourer_commit") val devourerCommit: String,
    @SerialName("frame_record_bytes") val frameRecordBytes: Int,
    val backends: List<BackendReport>,
)

/** How a device's backend was determined — see `Devices.h`. */
@Serializable
public enum class Identification {
    /** A static VID:PID table in the vendored source matched. Authoritative. */
    @SerialName("usb_id")
    USB_ID,

    /**
     * Plausibly a Realtek 11ac part, but those dispatch on a chip-id read over
     * USB. The backend is genuinely unknown until the device is opened —
     * treating this as an identification would be a guess wearing a fact's
     * clothes.
     */
    @SerialName("probe_required")
    PROBE_REQUIRED,

    @SerialName("none")
    NONE,
}

@Serializable
public data class UsbDevice(
    val bus: Int,
    val address: Int,
    @SerialName("port_path") val portPath: String = "",
    @SerialName("usb_id") val usbId: String,
    val vid: Int,
    val pid: Int,
    val speed: String = "unknown",
    val product: String = "",
    val serial: String = "",
    @SerialName("kernel_driver") val kernelDriver: String = "",
    val identification: Identification = Identification.NONE,
    val backend: String = "",
    val variant: String = "",
    @SerialName("backend_compiled_in") val backendCompiledIn: Boolean = false,
) {
    /** Stable handle for this physical port, survives re-enumeration better than address. */
    public val locator: String get() = "usb:$bus-$portPath"
}

@Serializable
public data class RadioListResult(
    val devices: List<UsbDevice>,
    val note: String = "",
)

@Serializable
public data class MonitorStats(
    val session: Int,
    val frames: Long,
    val bytes: Long,
    /** Whole records discarded because the reader could not keep up. */
    val dropped: Long,
    @SerialName("write_errors") val writeErrors: Long,
    @SerialName("tx_sent") val txSent: Long,
    @SerialName("tx_failed") val txFailed: Long,
    @SerialName("buffer_used") val bufferUsed: Long = 0,
    @SerialName("buffer_cap") val bufferCap: Long = 0,
    @SerialName("sink_attached") val sinkAttached: Boolean = false,
)

/**
 * What the chip's own PHY sees on the channel, without decoding a frame.
 *
 * The companion to a frame count, and a different quantity. A receiver's frame
 * rate says how much traffic it could *decode*; carrier sense defers on
 * *energy*, including energy that never resolves into a frame — a microwave,
 * an overlapping-channel emitter, a noisy port. This is the second one.
 *
 * Realtek only: it comes from `IRtlRadio::GetRxEnergy`, so a MediaTek reports
 * [supported] false rather than zeros. Every field carries its own validity
 * flag because the facilities differ by chip generation, and zero from a
 * generation that does not fill a counter is not a measurement of zero.
 *
 * [faOfdm], [faCck], [ccaOfdm] and [ccaCck] are DELTAS since the previous
 * read, which resets the hardware counters.
 */
@Serializable
public data class RxEnergy(
    val session: Int = 0,
    val supported: Boolean = false,
    val why: String? = null,
    val fallback: String? = null,
    val channel: Int = 0,
    @SerialName("valid_counters") val validCounters: Boolean = false,
    /** OFDM false alarms: energy that started a decode and was not a frame. */
    @SerialName("fa_ofdm") val faOfdm: Long? = null,
    @SerialName("fa_cck") val faCck: Long? = null,
    /** OFDM channel-busy count — the closest thing to "what CCA saw". */
    @SerialName("cca_ofdm") val ccaOfdm: Long? = null,
    @SerialName("cca_cck") val ccaCck: Long? = null,
    @SerialName("valid_igi") val validIgi: Boolean = false,
    /**
     * DIG initial-gain index: the AGC backs gain off as the in-band floor
     * rises, so a higher value means a noisier channel. A relative proxy for
     * the noise floor, not a dBm figure.
     */
    val igi: Int? = null,
    @SerialName("valid_noise_floor") val validNoiseFloor: Boolean = false,
    @SerialName("abs_noise_floor_dbm") val absNoiseFloorDbm: Int? = null,
    /** Which kind of absent, when [validNoiseFloor] is false. */
    @SerialName("noise_floor_why") val noiseFloorWhy: String? = null,
    @SerialName("valid_nhm") val validNhm: Boolean = false,
    /** 12 IGI-referenced in-band power buckets. Costs ~2ms to arm. */
    val nhm: List<Int>? = null,
    @SerialName("nhm_duration") val nhmDuration: Int? = null,
    val note: String? = null,
) {
    /** Channel-busy count across both modulations, when the chip reported it. */
    public val ccaTotal: Long?
        get() = if (validCounters) (ccaOfdm ?: 0) + (ccaCck ?: 0) else null

    public val faTotal: Long?
        get() = if (validCounters) (faOfdm ?: 0) + (faCck ?: 0) else null
}

/**
 * The receive-gain index, and whether anything is adjusting it.
 *
 * [supported] false means this backend has no gain index to report at all;
 * [valid] false with [supported] true means there is one but it cannot be read
 * yet (the baseband is not up), which is a different fact with a different
 * remedy. Absent values are omitted rather than zeroed, because gain index 0
 * is a real setting.
 *
 * [indexMin]/[indexMax] describe the whole supported envelope a caller may
 * clamp to, NOT the window in force now — that is [rangeMin]/[rangeMax].
 * Restoring state means remembering the live range, not the caps.
 *
 * [automaticInput] is the one field that makes this more than a number: it
 * says what the adaptive loop keys on, or that nothing is driving it and why.
 */
@Serializable
public data class RxGain(
    val session: Int = 0,
    val supported: Boolean = false,
    val why: String? = null,
    val settable: Boolean = false,
    @SerialName("index_name") val indexName: String = "",
    @SerialName("index_min") val indexMin: Int = 0,
    @SerialName("index_max") val indexMax: Int = 0,
    @SerialName("index_step_db") val indexStepDb: Int? = null,
    val automatic: Boolean = false,
    @SerialName("automatic_input") val automaticInput: String = "",
    val valid: Boolean = false,
    val index: Int? = null,
    @SerialName("range_min") val rangeMin: Int? = null,
    @SerialName("range_max") val rangeMax: Int? = null,
    val note: String? = null,
) {
    /**
     * The index sits at the bottom of a wider range, i.e. maximum gain. On
     * Realtek the EDCCA threshold is derived from it, so this state is also
     * the most sensitive carrier sense the adaptive loop can produce.
     */
    public val atMaximumGain: Boolean
        get() = valid && index != null && rangeMin != null && rangeMax != null &&
            index == rangeMin && rangeMin != rangeMax
}

/**
 * The two carrier-sense gates, separately.
 *
 * [supported] false means the backend has no split to report (a MediaTek, or a
 * Realtek that has not ported `GetCcaGates`); [why] says which, and the
 * combined [ccaDisabled] state is still carried so a radio with carrier sense
 * fully off cannot present as compliant.
 *
 * [primaryCcaDisabled] defers to a DECODABLE PREAMBLE, [edccaDisabled] to raw
 * in-band ENERGY. Which one matters is family-specific and the two measured
 * families disagree, so the bridge's [note] carries that warning and callers
 * should pass it through rather than paraphrase.
 */
@Serializable
public data class CcaGates(
    val session: Int = 0,
    val supported: Boolean = false,
    val why: String? = null,
    @SerialName("cca_disabled") val ccaDisabled: Boolean = false,
    @SerialName("primary_cca_disabled") val primaryCcaDisabled: Boolean? = null,
    @SerialName("edcca_disabled") val edccaDisabled: Boolean? = null,
    val note: String? = null,
    val warning: String? = null,
)

/**
 * Runtime TX power: the caps and the applied state of the index/offset model.
 *
 * Never dBm. [indexMax] and [stepQdb] describe the knob in hardware index
 * steps, and [stepMeasured] is the field that decides whether a power sweep is
 * evidence or just indices: when false the dB-per-step slope was never
 * validated on air for this family.
 *
 * [valid] false with [supported] true means the chip is not up yet. When it is
 * true, [flatIndex] is -1 for the efuse per-rate baseline or the forced flat
 * index otherwise; [offsetQdb]/[offsetSteps] are what was actually applied
 * after quantization; and [saturatedLow]/[saturatedHigh] say the last apply hit
 * a rail, which is how a controller learns the knob ran out of travel.
 * [hwReadback] false means the representative indices are the driver's
 * software shadow, not a register read.
 */
@Serializable
public data class TxPower(
    val session: Int = 0,
    val supported: Boolean = false,
    val why: String? = null,
    @SerialName("index_max") val indexMax: Int = 0,
    @SerialName("step_qdb") val stepQdb: Int = 0,
    @SerialName("step_measured") val stepMeasured: Boolean = false,
    @SerialName("offset_min_qdb") val offsetMinQdb: Int = 0,
    @SerialName("offset_max_qdb") val offsetMaxQdb: Int = 0,
    @SerialName("rate_diffs") val rateDiffs: Boolean = false,
    @SerialName("rate_diffs_hw_table") val rateDiffsHwTable: Boolean = false,
    @SerialName("rate_diffs_measured") val rateDiffsMeasured: Boolean = false,
    val valid: Boolean = false,
    @SerialName("flat_index") val flatIndex: Int? = null,
    @SerialName("offset_qdb") val offsetQdb: Int? = null,
    @SerialName("offset_steps") val offsetSteps: Int? = null,
    @SerialName("saturated_low") val saturatedLow: Boolean? = null,
    @SerialName("saturated_high") val saturatedHigh: Boolean? = null,
    @SerialName("cck_index") val cckIndex: Int? = null,
    @SerialName("ofdm_index") val ofdmIndex: Int? = null,
    @SerialName("mcs7_index") val mcs7Index: Int? = null,
    @SerialName("hw_readback") val hwReadback: Boolean? = null,
    @SerialName("rate_diffs_custom") val rateDiffsCustom: Boolean? = null,
    val note: String? = null,
)

/**
 * Caller-supplied per-rate TX-power diffs in quarter-dB, relative to the
 * reference anchor (HT MCS7, 1SS). REPLACES the chip's calibrated per-rate
 * shape: rates this struct does not describe — HT MCS8+, VHT/HE, 2SS+ — sit at
 * the anchor. Refused on a backend whose caps report `rate_diffs: false`.
 *
 * Resolution is the family step (one qdB on Jaguar3/Kestrel, 0.5 dB on
 * Jaguar1/2), so an odd qdB rounds. Diffs are **not** clamped to the regulatory
 * tables — the operator owns compliance, as with every TX-power knob.
 */
@Serializable
public data class TxRateDiffs(
    /** CCK 1..11M rows. */
    val cck: Int = 0,
    /** OFDM 6..54M control frames. */
    val legacy: Int = 0,
    /** HT MCS0..7, exactly 8 entries. */
    val mcs: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0, 0),
) {
    init {
        require(mcs.size == 8) { "mcs must be exactly 8 entries (MCS0..7), got ${mcs.size}" }
    }
}

/** Channel width in MHz. The bridge takes MHz; the enum keeps callers honest. */
public enum class ChannelWidth(public val mhz: Int) {
    W5(5), W10(10), W20(20), W40(40), W80(80), W160(160),
    ;

    public companion object {
        public fun ofMhz(mhz: Int): ChannelWidth =
            entries.firstOrNull { it.mhz == mhz }
                ?: throw IllegalArgumentException(
                    "unsupported channel width ${mhz}MHz (have ${entries.joinToString { it.mhz.toString() }})",
                )
    }
}

public data class ChannelSpec(
    val channel: Int,
    val width: ChannelWidth = ChannelWidth.W20,
    val offset: Int = 0,
    /** 0 = infer from channel number; 6 = 6 GHz, which cannot be inferred. */
    val band: Int = 0,
) {
    override fun toString(): String = "ch$channel/${width.mhz}MHz" +
        (if (band != 0) " band${band}G" else "")
}

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
 * The fused, windowed RX link-quality snapshot (`IRadio::GetRxQuality`).
 *
 * One read that a closed-loop controller would otherwise assemble from the
 * frame store: the per-frame RSSI/SNR/EVM aggregate, a passive noise-floor
 * estimate, the frame-free FA/CCA/IGI energy, and the plain-language
 * [verdict]. Realtek only — a non-Realtek reports [supported] false rather
 * than the default all-invalid snapshot, which would read as a real
 * `NO_SIGNAL`.
 *
 * The window DRAINS on every read, so a caller gets the interval since its
 * previous call. Also consumes the same FA/CCA/IGI delta as [RxEnergy]: do not
 * poll both on one cadence. [verdict]/[label]/[cause]/[fix] are the fused
 * verdict; [evmValid] distinguishes "EVM was measured" from a zero.
 */
@Serializable
public data class RxQuality(
    val session: Int = 0,
    val supported: Boolean = false,
    val why: String? = null,
    val fallback: String? = null,
    val valid: Boolean = false,
    val frames: Long = 0,
    @SerialName("rssi_mean_dbm") val rssiMeanDbm: Int = 0,
    @SerialName("rssi_max_dbm") val rssiMaxDbm: Int = 0,
    @SerialName("snr_mean_db") val snrMeanDb: Double = 0.0,
    @SerialName("snr_min_db") val snrMinDb: Double = 0.0,
    @SerialName("snr_valid") val snrValid: Boolean = false,
    @SerialName("evm_mean_db") val evmMeanDb: Double = 0.0,
    @SerialName("evm_valid") val evmValid: Boolean = false,
    @SerialName("noise_floor_dbm") val noiseFloorDbm: Double = 0.0,
    @SerialName("nf_valid") val nfValid: Boolean = false,
    @SerialName("abs_noise_floor_dbm") val absNoiseFloorDbm: Int = 0,
    @SerialName("abs_nf_valid") val absNfValid: Boolean = false,
    @SerialName("energy_valid") val energyValid: Boolean = false,
    @SerialName("fa_ofdm") val faOfdm: Long = 0,
    @SerialName("cca_ofdm") val ccaOfdm: Long = 0,
    @SerialName("igi_valid") val igiValid: Boolean = false,
    val igi: Int = 0,
    val verdict: String = "",
    val label: String = "",
    val cause: String = "",
    val fix: String = "",
    @SerialName("igi_at_floor") val igiAtFloor: Boolean = false,
    @SerialName("igi_at_ceiling") val igiAtCeiling: Boolean = false,
    val note: String? = null,
)

/**
 * The chip's thermal meter (`IRadio::GetThermalStatus`).
 *
 * [raw] is RF 0x42 thermal units (~1.5-2 C each), NOT absolute degrees;
 * [delta] is [raw] minus [baseline] and is the heat signal. [valid] false means
 * no baseline is available (only [raw] is meaningful). Telemetry, not a
 * calibrated temperature and not a validated degradation predictor.
 *
 * [supported] false means the backend returned no reading at all — distinct
 * from a meter that exists but has no baseline.
 */
@Serializable
public data class Thermal(
    val session: Int = 0,
    val supported: Boolean = false,
    val why: String? = null,
    val raw: Int = 0,
    val baseline: Int = 255,
    val delta: Int = 0,
    val valid: Boolean = false,
    val bucket: String = "",
    val note: String? = null,
)

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
    /**
     * The qdB the backend reported APPLYING, when its state getter is not
     * wired (the MT7612U's dBm model). Present only after an offset was set
     * this session; on a family with a real state readback, use [offsetQdb].
     */
    @SerialName("applied_offset_qdb") val appliedOffsetQdb: Int? = null,
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
        // The hardware field is 7-bit two's-complement; reject out of range
        // rather than let it wrap sign downstream.
        (listOf(cck, legacy) + mcs).forEach {
            require(it in -64..63) { "rate diffs must be -64..63 qdB, got $it" }
        }
    }
}

/**
 * One per-frame TX report (`tx.report`, the vendor CCX report): what the radio
 * did with a frame the host submitted.
 *
 * [state] 0 is delivered (ACKed, or complete for a broadcast/no-ack frame);
 * other values are retry-drop and firmware-specific states. [retries] is the
 * hardware retransmission count — the number `tx_stats` cannot see, because
 * tx_stats is the host's submission count and a retried frame counts once
 * there. [finalRate] is a hardware rate index, not a rate name.
 *
 * Entries exist only for REPORTED frames: with sampling N only every Nth is
 * reported, and the report stream itself can drop under load. On HalMAC
 * [tag] is the descriptor's SW_DEFINE echo, so consecutive reports should
 * differ by exactly N and a larger gap is a dropped report; [rtsRetries] is
 * HalMAC-only.
 */
@Serializable
public data class TxReceipt(
    /** Host monotonic milliseconds at report time — the report-rate timebase. */
    @SerialName("t_ms") val tMs: Long = 0,
    val state: Int = 0,
    val ok: Boolean = false,
    val retries: Int = 0,
    @SerialName("final_rate") val finalRate: Int = 0,
    @SerialName("queue_time_raw") val queueTimeRaw: Int = 0,
    val bmc: Boolean = false,
    val macid: Int = 0,
    val fmt: String = "",
    val tag: Int? = null,
    @SerialName("rts_retries") val rtsRetries: Int? = null,
)

/**
 * The buffered per-frame TX reports for one session.
 *
 * [enabled] false means the session was not opened with `tx_report`; the
 * reports do not exist to be read. [receipts] are the retained ring (bounded),
 * [total] is cumulative since open, and [dropped] counts evictions from the
 * ring — so eviction is visible rather than silent. The list is drained by
 * default on read.
 */
@Serializable
public data class TxReceipts(
    val session: Int = 0,
    val enabled: Boolean = false,
    val sampling: Int = 0,
    val total: Long = 0,
    val dropped: Long = 0,
    val buffered: Long = 0,
    val receipts: List<TxReceipt> = emptyList(),
    val why: String? = null,
    val note: String? = null,
)

/**
 * The hardware ACK responder: whether this adapter can be made to auto-ACK
 * unicast frames addressed to a chosen MAC, and whether it is armed.
 *
 * [supported] false means the backend does not report
 * `AdapterCaps.ack_responder_ok`. [armed] is the bridge's record of what it
 * armed — `IRadio` has no getter, so this is not a chip read. Clearing is best
 * effort and does not promise silence (see `IRadio::SetAckResponder`).
 */
@Serializable
public data class AckResponder(
    val session: Int = 0,
    val supported: Boolean = false,
    val armed: Boolean = false,
    val mac: String? = null,
    val why: String? = null,
    val note: String? = null,
)

/**
 * The 802.11 A-MPDU TX session mode (`IRadio::SetAmpduMode`): the bundle that
 * marks data frames aggregatable and programs the MAC pacing that gates net
 * goodput.
 *
 * There is no capability flag and the cleared state is byte-identical to the
 * unwired default, so a READ cannot tell "supported, off" from "not
 * implemented": [capability] is `supported`, `unsupported`, or `unknown` until
 * a set attempt settles it. This type is the request; see [AmpduState] for the
 * reply.
 */
@Serializable
public data class AmpduMode(
    val enabled: Boolean = true,
    /** QSEL/TID the aggregatable frames ride (0..7). */
    val tid: Int = 0,
    /** Max MPDUs per A-MPDU (1..31). */
    @SerialName("max_num") val maxNum: Int = 16,
    /** Min MPDU start spacing (0..7); 7 is bench-fastest. */
    val density: Int = 7,
    /** Broadcast/no-BlockAck case: per-frame retry limit 0. */
    @SerialName("no_ack") val noAck: Boolean = true,
    /** Aggregate-fill timer; 0x20 is the proven unlock, <=0x08 disables. */
    @SerialName("max_time") val maxTime: Int = 0x20,
    @SerialName("clear_burst_mode") val clearBurstMode: Boolean = true,
) {
    init {
        require(tid in 0..7) { "tid must be 0..7" }
        require(maxNum in 1..0x1f) { "maxNum must be 1..31" }
        require(density in 0..7) { "density must be 0..7" }
    }
}

/** The reported A-MPDU state. See [AmpduMode] for the capability caveat. */
@Serializable
public data class AmpduState(
    val session: Int = 0,
    /** "supported", "unsupported", or "unknown" (no set attempt yet). */
    val capability: String = "unknown",
    val enabled: Boolean = false,
    val tid: Int = 0,
    @SerialName("max_num") val maxNum: Int = 16,
    val density: Int = 7,
    @SerialName("no_ack") val noAck: Boolean = true,
    @SerialName("max_time") val maxTime: Int = 32,
    @SerialName("clear_burst_mode") val clearBurstMode: Boolean = true,
    val note: String? = null,
)

/**
 * The 64-bit MAC TSF in microseconds (`IRadio::ReadTsf`): the free-running MAC
 * clock, MAC-latched into each received frame's `tsfl`.
 *
 * [readable] false with [supported] true means the clock is not running yet
 * (the radio is not brought up) or the read returned 0; [why] says which as far
 * as the bridge can tell. Not synchronized to any external clock on its own.
 */
@Serializable
public data class Tsf(
    val session: Int = 0,
    val supported: Boolean = false,
    val readable: Boolean = false,
    @SerialName("tsf_us") val tsfUs: Long = 0,
    val why: String? = null,
    val note: String? = null,
)

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

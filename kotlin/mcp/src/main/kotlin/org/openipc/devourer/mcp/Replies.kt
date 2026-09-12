package org.openipc.devourer.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openipc.devourer.capture.CaptureSummary
import org.openipc.devourer.capture.StoredFrame
import org.openipc.devourer.protocol.FrameAddresses
import org.openipc.devourer.protocol.UsbDevice
import org.openipc.devourer.radio.VerificationState

/**
 * Shapes returned to the model.
 *
 * These are separate from the wire types on purpose: what a model needs to
 * reason well is not the same as what the bridge happens to send. The
 * difference shows up mostly as absence — a field a chip did not populate is
 * omitted rather than returned as a zero that reads like a measurement.
 */

@Serializable
internal data class RadioListReply(
    val devices: List<UsbDevice>,
    val note: String,
    @SerialName("verification_state") val verification: Map<String, VerificationState>,
)

@Serializable
internal data class MonitorStarted(
    @SerialName("capture_id") val captureId: String,
    val session: Int,
    val radio: String,
    val channel: String,
    @SerialName("capability_note") val capabilityNote: String? = null,
)

@Serializable
internal data class CaptureStatus(
    @SerialName("capture_id") val captureId: String,
    val session: Int,
    val radio: String,
    val channel: String,
    @SerialName("frames_stored") val framesStored: Int,
    @SerialName("frames_admitted") val framesAdmitted: Long,
    /** Lost from the front of the ring — the window no longer starts at the start. */
    @SerialName("evicted_from_ring") val evictedFromRing: Long,
    /** Dropped by the bridge because this process could not read fast enough. */
    @SerialName("bridge_dropped") val bridgeDropped: Long,
    @SerialName("bridge_frames") val bridgeFrames: Long,
    val running: Boolean,
)

@Serializable
internal data class SummaryReply(
    val summary: CaptureSummary,
    @SerialName("capability_note") val capabilityNote: String? = null,
)

/**
 * Answer to "what is this radio actually receiving on".
 *
 * Two independent methods, reported side by side rather than merged: the chip's
 * own estimator where a backend implements it, and an analysis of the captured
 * frames, which works everywhere. [source] says which one the answer came from,
 * because "the chip measured this" and "we inferred this from ambient traffic"
 * are different strengths of evidence and should never blur together.
 */
@Serializable
internal data class AntennaReply(
    @SerialName("source") val source: String,
    @SerialName("live_estimator_supported") val liveEstimatorSupported: Boolean,
    @SerialName("live_estimator") val liveEstimator: kotlinx.serialization.json.JsonObject,
    @SerialName("capture_derived_chain_balance")
    val captureDerived: org.openipc.devourer.capture.ChainBalance? = null,
)

@Serializable
internal data class FrameRow(
    val index: Long,
    val kind: String,
    @SerialName("host_ns") val hostNanos: Long,
    val length: Int,
    @SerialName("seq") val sequenceNumber: Int,
    @SerialName("rate_code") val rateCode: Int,
    val rssi: List<Int>,
    val retry: Boolean? = null,
    @SerialName("crc_error") val crcError: Boolean,
    val aggregated: Boolean,
    val transmitter: String? = null,
    val bssid: String? = null,
)

internal fun StoredFrame.toRow(): FrameRow {
    val r = record
    val fc = r.frameControl
    val addr = fc?.let { FrameAddresses.parse(r.payload, it) }
    return FrameRow(
        index = index,
        kind = fc?.name ?: "unparsed",
        hostNanos = r.hostNanos,
        length = r.packetLength,
        sequenceNumber = r.sequenceNumber,
        rateCode = r.dataRate,
        rssi = r.rssiByChain,
        retry = fc?.retry,
        crcError = r.crcError,
        aggregated = r.aggregated,
        transmitter = addr?.transmitter,
        bssid = addr?.bssid,
    )
}

@Serializable
internal data class FrameDetail(
    val index: Long,
    val kind: String,
    @SerialName("host_ns") val hostNanos: Long,
    @SerialName("length_bytes") val lengthBytes: Int,
    @SerialName("seq") val sequenceNumber: Int,
    @SerialName("rate_code") val rateCode: Int,
    val bandwidth: Int,
    @SerialName("short_gi") val shortGi: Boolean,
    val ldpc: Boolean,
    val stbc: Boolean,
    /** Silicon RF chains. NOT the antenna-connector count on the board. */
    @SerialName("rx_chains") val rxChains: Int = 0,
    @SerialName("rssi_per_chain") val rssiPerChain: List<Int>,
    @SerialName("snr_per_chain") val snrPerChain: List<Int>? = null,
    val evm: List<Int>? = null,
    @SerialName("cfo_tail_khz") val cfoTailKhz: Double? = null,
    /** Absent on chips with no RX timestamp, rather than a misleading zero. */
    @SerialName("chip_tsf") val chipTsf: Long? = null,
    @SerialName("tx_egress_tsf") val txEgressTsf: Long? = null,
    val aggregated: Boolean,
    @SerialName("ppdu_count") val ppduCount: Int,
    val retry: Boolean? = null,
    @SerialName("crc_error") val crcError: Boolean,
    /** False on MT7612U: the trailing 4 bytes are an FCE trailer, not an FCS. */
    @SerialName("fcs_present") val fcsPresent: Boolean,
    @SerialName("phy_status_present") val phyStatusPresent: Boolean,
    @SerialName("protected") val protectedFrame: Boolean? = null,
    val addresses: Map<String, String> = emptyMap(),
    val truncated: Boolean,
    @SerialName("raw_hex") val rawHex: String,
    @SerialName("raw_bytes_shown") val rawBytesShown: Int,
    @SerialName("raw_bytes_total") val rawBytesTotal: Int,
)

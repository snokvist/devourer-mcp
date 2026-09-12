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

/**
 * One line per characterized adapter, for the listing view.
 *
 * [unverified] is carried at the top level on purpose: a record showing
 * TX_VERIFIED with fourteen unexercised capabilities is a very different object
 * from one with none, and a summary that hid that would invite the reader to
 * treat the strongest claim as the whole story.
 */
@Serializable
internal data class CharacterizationSummary(
    val key: String,
    val identity: String,
    val chip: String,
    val backend: String,
    val state: VerificationState,
    val runs: Int,
    val unverified: Int,
    val summary: String,
)

// ----------------------------------------------------------------- scratchpad

/**
 * The scratchpad primitive catalogue, handed to the model before it writes one.
 *
 * Generated from the enums and metric lists rather than written out, so a
 * primitive added in code appears here automatically. A hand-maintained copy
 * would drift, and a model building against a stale catalogue produces programs
 * that fail validation for no visible reason.
 */
@Serializable
internal data class ScratchpadDoc(
    val model: String,
    val capabilities: List<CapabilityDoc>,
    @SerialName("source_kinds") val sourceKinds: List<SourceKindDoc>,
    @SerialName("widget_kinds") val widgetKinds: List<String>,
    @SerialName("expression_functions") val expressionFunctions: List<String>,
    val example: kotlinx.serialization.json.JsonObject,
) {
    companion object {
        fun build(): ScratchpadDoc = ScratchpadDoc(
            model = "A program is SOURCES sampled on a schedule, COMPUTED values derived from " +
                "them by arithmetic expressions, and a UI over the result. There is no control " +
                "flow and no way to reach anything a declared capability does not name. " +
                "Anything needing branching or iteration belongs in the experiment engine.",
            capabilities = org.openipc.devourer.scratchpad.Capability.entries.map {
                CapabilityDoc(it.id, it.description, it.privileged)
            },
            sourceKinds = listOf(
                SourceKindDoc(
                    kind = "capture.metric",
                    description = "a scalar from a live capture's trailing window",
                    fields = listOf(
                        "id", "capture_id", "metric", "every_ms", "window_ms", "kind", "transmitter",
                    ),
                    values = org.openipc.devourer.scratchpad.CaptureMetricSource.METRICS,
                ),
                SourceKindDoc(
                    kind = "http.poll",
                    description = "an HTTP GET on a timer; always records <id>.latency_ms and " +
                        "<id>.status, plus any numbers named in `extract`",
                    fields = listOf("id", "url", "every_ms", "timeout_ms", "extract"),
                    values = emptyList(),
                ),
                SourceKindDoc(
                    kind = "radio.metric",
                    description = "a scalar from a granted radio's live state",
                    fields = listOf("id", "session", "metric", "every_ms"),
                    values = org.openipc.devourer.scratchpad.RadioMetricSource.METRICS,
                ),
            ),
            widgetKinds = listOf("chart", "stat", "gauge", "table", "log"),
            expressionFunctions = listOf(
                "min", "max", "abs", "sqrt", "ln", "log10", "round", "floor", "ceil",
                "clamp(v,lo,hi)", "avg", "ratio(a,b) — guarded divide, 0 when b is 0",
            ),
            example = kotlinx.serialization.json.Json.parseToJsonElement(
                """
                {
                  "name": "camera-link-watch",
                  "purpose": "watch a camera's HTTP latency against its Wi-Fi link quality",
                  "capabilities": ["timer","metrics","capture.read","http.get","ui"],
                  "duration_ms": 60000,
                  "sources": [
                    {"kind":"capture.metric","id":"rssi","capture_id":"cap-1",
                     "metric":"rssi_mean","every_ms":500,"window_ms":2000},
                    {"kind":"capture.metric","id":"retries","capture_id":"cap-1",
                     "metric":"retry_rate","every_ms":500,"window_ms":2000},
                    {"kind":"http.poll","id":"cam","url":"http://192.168.2.181/status",
                     "every_ms":500,"timeout_ms":400,"extract":{"fps":"video.fps"}}
                  ],
                  "computed": [
                    {"id":"retry_pct","expr":"retries * 100","unit":"%"}
                  ],
                  "ui": {
                    "title": "Camera link",
                    "widgets": [
                      {"kind":"chart","title":"RSSI vs HTTP latency","series":["rssi","cam.latency_ms"]},
                      {"kind":"stat","title":"Retry rate","series":["retry_pct"],"unit":"%"},
                      {"kind":"table","title":"All series","series":[]},
                      {"kind":"log","title":"Run log"}
                    ]
                  }
                }
                """.trimIndent(),
            ).let { it as kotlinx.serialization.json.JsonObject },
        )
    }
}

@Serializable
internal data class CapabilityDoc(val id: String, val description: String, val privileged: Boolean)

@Serializable
internal data class SourceKindDoc(
    val kind: String,
    val description: String,
    val fields: List<String>,
    /** Legal values for the kind's `metric` field, where it has one. */
    val values: List<String>,
)

@Serializable
internal data class ScratchpadStarted(
    val run: org.openipc.devourer.scratchpad.ScratchpadService.RunHandle,
    val inspection: org.openipc.devourer.scratchpad.ScratchpadService.InspectionResult,
)

@Serializable
internal data class SeriesStats(
    val count: Int,
    val min: Double,
    val mean: Double,
    val max: Double,
    val last: Double,
)

@Serializable
internal data class ScratchpadResult(
    @SerialName("run_id") val runId: String,
    val name: String,
    val running: Boolean,
    @SerialName("elapsed_ms") val elapsedMs: Long,
    @SerialName("ui_url") val uiUrl: String? = null,
    val error: String? = null,
    val series: Map<String, SeriesStats>,
    val log: List<String>,
)

@Serializable
internal data class RunningPad(
    val id: String,
    val name: String,
    val running: Boolean,
    @SerialName("ui_url") val uiUrl: String? = null,
    val error: String? = null,
)

@Serializable
internal data class SavedPad(
    val file: String,
    val name: String,
    val purpose: String,
    val capabilities: List<String>,
)

@Serializable
internal data class ScratchpadListing(
    val running: List<RunningPad>,
    val saved: List<SavedPad>,
)

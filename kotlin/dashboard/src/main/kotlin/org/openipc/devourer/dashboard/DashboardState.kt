package org.openipc.devourer.dashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openipc.devourer.experiment.ExperimentRunner

/**
 * Everything the page draws, in one snapshot.
 *
 * Assembled from in-process state only. Nothing here costs a bridge round
 * trip, which is what lets the page poll once a second without competing with
 * the model for the control connection.
 */
@Serializable
public data class DashboardState(
    val bridge: BridgeView,
    val radios: List<RadioView>,
    val captures: List<CaptureView>,
    val experiments: List<ExperimentRunner.Progress>,
    val scratchpads: List<ScratchpadView>,
    val characterizations: List<CharacterizationView>,
    /** Set when something needs looking at right now. */
    val warnings: List<String> = emptyList(),
    @SerialName("at_epoch_ms") val atEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
public data class BridgeView(
    val protocol: String,
    @SerialName("devourer_commit") val devourerCommit: String,
    val backends: List<String>,
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
)

@Serializable
public data class RadioView(
    val session: Int,
    val label: String,
    val chip: String,
    val backend: String,
    val locator: String,
    @SerialName("permanent_mac") val permanentMac: String? = null,
    val channel: String? = null,
    val monitoring: Boolean,
    @SerialName("brought_up") val broughtUp: Boolean,
    /**
     * True while this radio transmits without listening first.
     *
     * Shown prominently because it is a state someone can walk away from, and
     * because everything measured while it is true means something different.
     */
    @SerialName("carrier_sense_disabled") val carrierSenseDisabled: Boolean,
    @SerialName("seen_at_epoch_ms") val seenAtEpochMs: Long,
)

@Serializable
public data class CaptureView(
    val id: String,
    val session: Int,
    val radio: String,
    val channel: String,
    val frames: Int,
    @SerialName("frames_per_second") val framesPerSecond: Double,
    @SerialName("crc_errors") val crcErrors: Int,
    val retries: Int,
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
    val note: String? = null,
)

@Serializable
public data class ScratchpadView(
    val id: String,
    val title: String,
    val running: Boolean,
    val capabilities: List<String>,
    @SerialName("ui_url") val uiUrl: String? = null,
    val series: Map<String, Double> = emptyMap(),
    @SerialName("log_tail") val logTail: List<String> = emptyList(),
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
)

@Serializable
public data class CharacterizationView(
    val key: String,
    val chip: String,
    val backend: String,
    val state: String,
    val runs: Int,
    val unverified: Int,
    @SerialName("last_seen_epoch_ms") val lastSeenEpochMs: Long,
)

@Serializable
public data class ActivityPage(
    val latest: Long,
    val entries: List<ActivityLog.Entry>,
)

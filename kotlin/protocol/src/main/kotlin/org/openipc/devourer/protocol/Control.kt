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

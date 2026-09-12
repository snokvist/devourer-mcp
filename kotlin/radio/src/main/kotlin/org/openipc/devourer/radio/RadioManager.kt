package org.openipc.devourer.radio

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.openipc.devourer.protocol.BridgeJson
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.MonitorStats
import org.openipc.devourer.protocol.RadioListResult
import org.openipc.devourer.protocol.UsbDevice

/**
 * Radios as concepts, not as bridge ops.
 *
 * This is the layer that enforces the two rules the MCP surface depends on:
 * every advanced operation is gated on what the adapter actually reports, and
 * nothing claims hardware evidence it does not have.
 */
public class RadioManager(private val bridge: BridgeClient) {

    @Serializable
    public data class OpenRadio(
        val session: Int,
        val device: UsbDevice,
        val capabilities: RadioCapabilities,
        @SerialName("permanent_mac") val permanentMac: String? = null,
    ) {
        /** A short, stable name for logs and MCP replies. */
        public val label: String
            get() = buildString {
                append(capabilities.chip.ifBlank { device.usbId })
                append(" @ ").append(device.locator)
            }
    }

    public suspend fun connect() {
        bridge.connect()
    }

    /**
     * Every adapter devourer could plausibly drive.
     *
     * Note the [UsbDevice.identification] field: for Realtek 11ac parts the
     * backend is genuinely unknown until [open] reads the chip id. Callers must
     * not present `PROBE_REQUIRED` as an identification.
     */
    public suspend fun list(includeAll: Boolean = false): RadioListResult {
        val result = bridge.call(
            "radio.list",
            buildJsonObject { put("all", JsonPrimitive(includeAll)) },
        )
        return BridgeJson.format.decodeFromJsonElement(RadioListResult.serializer(), result)
    }

    /** Opens and claims an adapter. Does not power the chip — monitoring does. */
    public suspend fun open(bus: Int, address: Int, reset: Boolean = true): OpenRadio {
        val result = bridge.call(
            "radio.open",
            buildJsonObject {
                put("bus", JsonPrimitive(bus))
                put("address", JsonPrimitive(address))
                put("reset", JsonPrimitive(reset))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(OpenRadio.serializer(), result)
    }

    public suspend fun describe(session: Int): OpenRadio {
        val result = bridge.call(
            "radio.describe",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(OpenRadio.serializer(), result)
    }

    public suspend fun close(session: Int) {
        bridge.call("radio.close", buildJsonObject { put("session", JsonPrimitive(session)) })
    }

    public suspend fun sessions(): JsonObject = bridge.call("sessions")

    /**
     * Starts monitor capture, refusing anything the adapter cannot actually do.
     *
     * The gate is not decoration. A radio asked for a width it lacks would
     * otherwise be configured to something else and keep reporting success,
     * and the resulting capture would be wrong in a way no later analysis
     * could detect.
     */
    public suspend fun startMonitor(session: Int, channel: ChannelSpec): JsonObject {
        val radio = describe(session)
        requireChannelSupported(radio, channel)
        return bridge.call("monitor.start", channel.toJson(session))
    }

    public suspend fun stopMonitor(session: Int): JsonObject =
        bridge.call("monitor.stop", buildJsonObject { put("session", JsonPrimitive(session)) })

    public suspend fun stats(session: Int): MonitorStats {
        val result = bridge.call(
            "monitor.stats",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(MonitorStats.serializer(), result)
    }

    public suspend fun retune(session: Int, channel: ChannelSpec): JsonObject {
        requireChannelSupported(describe(session), channel)
        return bridge.call("radio.channel", channel.toJson(session))
    }

    public fun frames(session: Int): Flow<FrameRecord> = bridge.frames(session)

    /**
     * Live per-chain activity estimate — which RF chains are actually hearing
     * anything right now.
     *
     * The only way to learn about antennas. [RadioCapabilities.rxChains] is the
     * silicon's chain count and says nothing about what is plugged into the
     * board: a 2T2R part may sit behind four connectors with diversity
     * switching, or behind two, or behind one and a 50-ohm load. That
     * difference is invisible to every static report and shows up only as a
     * chain whose RSSI sits at the noise floor while its neighbour does not.
     *
     * Requires a running monitor and ambient traffic.
     */
    public suspend fun activeRxPaths(session: Int): JsonObject =
        bridge.call("radio.rx_paths", buildJsonObject { put("session", JsonPrimitive(session)) })

    /**
     * Transmits a frame a bounded number of times.
     *
     * The bound is not a convenience: an unbounded send loop in the bridge
     * would have no cancellation path and no one watching it. Sustained
     * transmission belongs to the experiment engine, where a caller owns the
     * timeout.
     *
     * What comes back is the count the TX path ACCEPTED. That is not evidence
     * anything reached the air — only an independent receiver can establish
     * that, which is why [VerificationState.TX_VERIFIED] requires one.
     */
    public suspend fun sendFrame(session: Int, frameHex: String, count: Int = 1): JsonObject {
        require(count in 1..MAX_TX_COUNT) {
            "count must be 1..$MAX_TX_COUNT; sustained transmission belongs in a bounded experiment"
        }
        require(frameHex.isNotBlank() && frameHex.length % 2 == 0) {
            "frame_hex must be a non-empty even-length hex string (radiotap header + 802.11 MPDU)"
        }
        return bridge.call(
            "tx.send",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("frame_hex", JsonPrimitive(frameHex))
                put("count", JsonPrimitive(count))
            },
        )
    }

    /**
     * Checks a requested channel against the adapter's reported capability, and
     * reports whether the channel is merely tunable or actually calibrated.
     *
     * Returns a note when the request is legal but its TX power is
     * extrapolated — a caller doing power measurements needs to know that
     * before it records a number as a measurement.
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
        if (!caps.isCharacterized(mhz)) {
            return "${mhz}MHz is tunable but OUTSIDE the TX-power characterized range " +
                "(${caps.characterized2g4.describe()}, ${caps.characterized5g.describe()}). " +
                "Power there is extrapolated from the nearest calibrated channel — " +
                "treat absolute power readings as uncalibrated."
        }
        return null
    }

    public companion object {
        /** Hard ceiling on one tx_send call. Matches the bridge's own bound. */
        public const val MAX_TX_COUNT: Int = 100_000

        /**
         * Channel number to centre frequency.
         *
         * Devourer drives the 5 GHz synthesizer far past the regulatory
         * channels using the vendor's `freq = 5000 + 5*chan` relation over
         * channels 16..253, so that rule is applied rather than a table of
         * legal channels — the point of this instrument is to reach where the
         * hardware reaches, with the caller told when it leaves calibrated air.
         */
        public fun centerFrequencyMhz(channel: ChannelSpec): Int? = when {
            channel.band == 6 -> 5950 + 5 * channel.channel
            channel.channel == 14 -> 2484
            channel.channel in 1..13 -> 2407 + 5 * channel.channel
            channel.channel in 16..253 -> 5000 + 5 * channel.channel
            else -> null
        }
    }
}

private fun BandRange.describe(): String =
    if (valid) "$minMhz-$maxMhz MHz" else "none"

private fun ChannelSpec.toJson(session: Int): JsonObject = buildJsonObject {
    put("session", JsonPrimitive(session))
    put("channel", JsonPrimitive(channel))
    put("width_mhz", JsonPrimitive(width.mhz))
    put("offset", JsonPrimitive(offset))
    put("band", JsonPrimitive(band))
}

/** Parses a `ch36/80` style spec, the form a model is most likely to produce. */
public fun parseChannelSpec(text: String): ChannelSpec {
    val m = Regex("""^\s*(?:ch)?(\d+)\s*(?:[/@]\s*(\d+))?\s*(?:MHz)?\s*$""", RegexOption.IGNORE_CASE)
        .find(text)
        ?: throw IllegalArgumentException("cannot parse channel spec '$text' (try '36' or 'ch36/80')")
    val channel = m.groupValues[1].toInt()
    val width = m.groupValues[2].takeIf { it.isNotBlank() }?.toInt() ?: 20
    return ChannelSpec(channel = channel, width = ChannelWidth.ofMhz(width))
}

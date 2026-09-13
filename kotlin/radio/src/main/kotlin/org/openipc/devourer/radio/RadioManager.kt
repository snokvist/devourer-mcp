package org.openipc.devourer.radio

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.openipc.devourer.protocol.BridgeJson
import org.openipc.devourer.protocol.AckResponder
import org.openipc.devourer.protocol.AmpduMode
import org.openipc.devourer.protocol.AmpduState
import org.openipc.devourer.protocol.CcaGates
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.MonitorStats
import org.openipc.devourer.protocol.RadioListResult
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.protocol.RxGain
import org.openipc.devourer.protocol.RxQuality
import org.openipc.devourer.protocol.Thermal
import org.openipc.devourer.protocol.TxPower
import org.openipc.devourer.protocol.TxReceipts
import org.openipc.devourer.protocol.TxRateDiffs

/**
 * [Radios] over the real bridge.
 *
 * This is the layer that enforces the two rules the MCP surface depends on:
 * every advanced operation is gated on what the adapter actually reports, and
 * nothing claims hardware evidence it does not have. Both gates live in
 * shared functions ([requireChannelSupported], [RadioSafety]) rather than
 * here, so a different implementation cannot quietly skip them.
 */
public class RadioManager(private val bridge: BridgeClient) : Radios {

    override suspend fun connect() {
        bridge.connect()
    }

    override suspend fun list(includeAll: Boolean): RadioListResult {
        val result = bridge.call(
            "radio.list",
            buildJsonObject { put("all", JsonPrimitive(includeAll)) },
        )
        return BridgeJson.format.decodeFromJsonElement(RadioListResult.serializer(), result)
    }

    override suspend fun open(
        bus: Int,
        address: Int,
        reset: Boolean,
        noiseFloor: Boolean,
        adaptiveGain: Boolean,
        txReport: Int,
    ): OpenRadio {
        require(txReport in 0..255) { "txReport must be 0..255 (the report divisor)" }
        val result = bridge.call(
            "radio.open",
            buildJsonObject {
                put("bus", JsonPrimitive(bus))
                put("address", JsonPrimitive(address))
                put("reset", JsonPrimitive(reset))
                put("noise_floor", JsonPrimitive(noiseFloor))
                put("adaptive_gain", JsonPrimitive(adaptiveGain))
                put("tx_report", JsonPrimitive(txReport))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(OpenRadio.serializer(), result)
    }

    override suspend fun describe(session: Int): OpenRadio {
        val result = bridge.call(
            "radio.describe",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(OpenRadio.serializer(), result)
    }

    override suspend fun close(session: Int) {
        bridge.call("radio.close", buildJsonObject { put("session", JsonPrimitive(session)) })
    }

    override suspend fun sessions(): JsonObject = bridge.call("sessions")

    /**
     * The capability gate is not decoration. A radio asked for a width it
     * lacks would otherwise be configured to something else and keep reporting
     * success, and the resulting capture would be wrong in a way no later
     * analysis could detect.
     */
    override suspend fun startMonitor(session: Int, channel: ChannelSpec): JsonObject {
        requireChannelSupported(describe(session), channel)
        return bridge.call("monitor.start", channel.toJson(session))
    }

    override suspend fun stopMonitor(session: Int): JsonObject =
        bridge.call("monitor.stop", buildJsonObject { put("session", JsonPrimitive(session)) })

    override suspend fun stats(session: Int): MonitorStats {
        val result = bridge.call(
            "monitor.stats",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(MonitorStats.serializer(), result)
    }

    override suspend fun retune(session: Int, channel: ChannelSpec): JsonObject {
        requireChannelSupported(describe(session), channel)
        return bridge.call("radio.channel", channel.toJson(session))
    }

    override suspend fun fastRetune(session: Int, channel: Int): ChannelInfo {
        val result = bridge.call(
            "radio.fast_retune",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("channel", JsonPrimitive(channel))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(ChannelInfo.serializer(), result)
    }

    override suspend fun fastBandwidth(session: Int, widthMhz: Int): ChannelInfo {
        ChannelWidth.ofMhz(widthMhz) // reject an impossible width before the round trip
        val result = bridge.call(
            "radio.fast_bandwidth",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("width_mhz", JsonPrimitive(widthMhz))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(ChannelInfo.serializer(), result)
    }

    override fun frames(session: Int): Flow<FrameRecord> = bridge.frames(session)

    override suspend fun sendProbe(
        session: Int,
        frameHex: String,
        mode: String,
        count: Int,
        intervalUs: Int,
        sequenceOffset: Int,
    ): JsonObject {
        require(count in 1..MAX_TX_COUNT) { "count must be 1..$MAX_TX_COUNT" }
        return bridge.call(
            "tx.send",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("body_hex", JsonPrimitive(frameHex))
                put("mode", JsonPrimitive(mode))
                put("count", JsonPrimitive(count))
                put("interval_us", JsonPrimitive(intervalUs))
                put("seq_offset", JsonPrimitive(sequenceOffset))
            },
        )
    }

    override suspend fun setCarrierSense(
        session: Int,
        enabled: Boolean,
        safety: SafetyLevel,
    ): JsonObject {
        RadioSafety.gateCarrierSense(enabled, safety)
        return bridge.call(
            "radio.cca",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("disabled", JsonPrimitive(!enabled))
            },
        )
    }

    override suspend fun rxGain(session: Int): RxGain {
        val result = bridge.call(
            "radio.rx_gain",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(RxGain.serializer(), result)
    }

    override suspend fun clampRxGain(session: Int, minIndex: Int, maxIndex: Int): RxGain {
        // Checked before the round trip so an obviously bad request does not
        // depend on the backend to refuse it. The bridge validates against the
        // adapter's real envelope; this only catches inverted or negative.
        require(minIndex <= maxIndex) { "minIndex must be <= maxIndex" }
        require(minIndex >= 0 && maxIndex >= 0) { "gain indices cannot be negative" }
        val result = bridge.call(
            "radio.rx_gain",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("min_index", JsonPrimitive(minIndex))
                put("max_index", JsonPrimitive(maxIndex))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(RxGain.serializer(), result)
    }

    override suspend fun ccaGates(session: Int): CcaGates {
        val result = bridge.call(
            "radio.cca_gates",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(CcaGates.serializer(), result)
    }

    override suspend fun setCcaGates(
        session: Int,
        primaryCcaDisabled: Boolean?,
        edccaDisabled: Boolean?,
        safety: SafetyLevel,
    ): CcaGates {
        RadioSafety.gateCcaGates(primaryCcaDisabled, edccaDisabled, safety)
        require(primaryCcaDisabled != null || edccaDisabled != null) {
            "name at least one gate to change; use ccaGates() to read both"
        }
        val result = bridge.call(
            "radio.cca_gates",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                primaryCcaDisabled?.let { put("primary_cca_disabled", JsonPrimitive(it)) }
                edccaDisabled?.let { put("edcca_disabled", JsonPrimitive(it)) }
            },
        )
        return BridgeJson.format.decodeFromJsonElement(CcaGates.serializer(), result)
    }

    override suspend fun txPower(session: Int): TxPower {
        val result = bridge.call(
            "radio.tx_power",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(TxPower.serializer(), result)
    }

    override suspend fun setTxPower(
        session: Int,
        offsetQdb: Int?,
        indexOverride: Int?,
        rateDiffs: TxRateDiffs?,
        clearRateDiffs: Boolean,
        reapply: Boolean,
    ): TxPower {
        require(offsetQdb != null || indexOverride != null || rateDiffs != null ||
            clearRateDiffs || reapply) {
            "name at least one knob, rate diffs, or reapply; use txPower() to read"
        }
        val result = bridge.call(
            "radio.tx_power",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                offsetQdb?.let { put("offset_qdb", JsonPrimitive(it)) }
                indexOverride?.let { put("index_override", JsonPrimitive(it)) }
                rateDiffs?.let {
                    put(
                        "rate_diffs",
                        buildJsonObject {
                            put("cck", JsonPrimitive(it.cck))
                            put("legacy", JsonPrimitive(it.legacy))
                            put("mcs", JsonArray(it.mcs.map { d -> JsonPrimitive(d) }))
                        },
                    )
                }
                if (clearRateDiffs) put("clear_rate_diffs", JsonPrimitive(true))
                if (reapply) put("reapply", JsonPrimitive(true))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(TxPower.serializer(), result)
    }

    override suspend fun rxEnergy(session: Int, withNhm: Boolean): RxEnergy {
        val result = bridge.call(
            "radio.rx_energy",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("with_nhm", JsonPrimitive(withNhm))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(RxEnergy.serializer(), result)
    }

    override suspend fun rxQuality(session: Int): RxQuality {
        val result = bridge.call(
            "radio.rx_quality",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(RxQuality.serializer(), result)
    }

    override suspend fun thermal(session: Int): Thermal {
        val result = bridge.call(
            "radio.thermal",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(Thermal.serializer(), result)
    }

    override suspend fun txStats(session: Int): JsonObject =
        bridge.call("radio.tx_stats", buildJsonObject { put("session", JsonPrimitive(session)) })

    override suspend fun txReceipts(session: Int, clear: Boolean): TxReceipts {
        val result = bridge.call(
            "radio.tx_receipts",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("clear", JsonPrimitive(clear))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(TxReceipts.serializer(), result)
    }

    override suspend fun ackResponder(session: Int): AckResponder {
        val result = bridge.call(
            "radio.ack_responder",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(AckResponder.serializer(), result)
    }

    override suspend fun setAckResponder(
        session: Int,
        mac: String,
        safety: SafetyLevel,
    ): AckResponder {
        RadioSafety.gateAckResponder(safety)
        require(mac.isNotBlank()) { "mac is required to arm the responder" }
        val result = bridge.call(
            "radio.ack_responder",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("mac", JsonPrimitive(mac))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(AckResponder.serializer(), result)
    }

    override suspend fun clearAckResponder(session: Int): AckResponder {
        val result = bridge.call(
            "radio.ack_responder",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("clear", JsonPrimitive(true))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(AckResponder.serializer(), result)
    }

    override suspend fun ampdu(session: Int): AmpduState {
        val result = bridge.call(
            "radio.ampdu",
            buildJsonObject { put("session", JsonPrimitive(session)) },
        )
        return BridgeJson.format.decodeFromJsonElement(AmpduState.serializer(), result)
    }

    override suspend fun setAmpdu(session: Int, mode: AmpduMode): AmpduState {
        val result = bridge.call(
            "radio.ampdu",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put(
                    "mode",
                    buildJsonObject {
                        put("enabled", JsonPrimitive(mode.enabled))
                        put("tid", JsonPrimitive(mode.tid))
                        put("max_num", JsonPrimitive(mode.maxNum))
                        put("density", JsonPrimitive(mode.density))
                        put("no_ack", JsonPrimitive(mode.noAck))
                        put("max_time", JsonPrimitive(mode.maxTime))
                        put("clear_burst_mode", JsonPrimitive(mode.clearBurstMode))
                    },
                )
            },
        )
        return BridgeJson.format.decodeFromJsonElement(AmpduState.serializer(), result)
    }

    override suspend fun clearAmpdu(session: Int): AmpduState {
        val result = bridge.call(
            "radio.ampdu",
            buildJsonObject {
                put("session", JsonPrimitive(session))
                put("clear", JsonPrimitive(true))
            },
        )
        return BridgeJson.format.decodeFromJsonElement(AmpduState.serializer(), result)
    }

    override suspend fun activeRxPaths(session: Int): JsonObject =
        bridge.call("radio.rx_paths", buildJsonObject { put("session", JsonPrimitive(session)) })

    /**
     * The [MAX_TX_COUNT] bound is not a convenience: an unbounded send loop in
     * the bridge would have no cancellation path and no one watching it.
     * Sustained transmission belongs to the experiment engine, where a caller
     * owns the timeout.
     */
    override suspend fun sendFrame(
        session: Int,
        frameHex: String,
        count: Int,
        safety: SafetyLevel,
    ): JsonObject {
        RadioSafety.gateRawFrame(safety)
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

    public companion object {
        /** Hard ceiling on one tx_send call. Matches the bridge's own bound. */
        public const val MAX_TX_COUNT: Int = 100_000
    }
}

private fun ChannelSpec.toJson(session: Int): JsonObject = buildJsonObject {
    put("session", JsonPrimitive(session))
    put("channel", JsonPrimitive(channel))
    put("width_mhz", JsonPrimitive(width.mhz))
    put("offset", JsonPrimitive(offset))
    put("band", JsonPrimitive(band))
}

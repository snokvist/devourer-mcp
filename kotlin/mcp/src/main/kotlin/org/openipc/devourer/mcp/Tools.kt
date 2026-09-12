package org.openipc.devourer.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.capture.CaptureSummary
import org.openipc.devourer.capture.analyseChainBalance
import org.openipc.devourer.experiment.ExperimentBounds
import org.openipc.devourer.experiment.ExperimentResult
import org.openipc.devourer.experiment.LinkProbe
import org.openipc.devourer.capture.FrameQuery
import org.openipc.devourer.capture.PcapWriter
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.protocol.FrameAddresses
import org.openipc.devourer.radio.CapabilityException
import org.openipc.devourer.radio.RadioManager
import org.openipc.devourer.radio.VerificationState

/**
 * The MCP surface.
 *
 * Deliberately NOT a tool per bridge op. The model works in radio concepts —
 * discover, observe, inspect, transmit — and each tool here composes whatever
 * bridge calls that takes. Mirroring the native API one-to-one would push the
 * sequencing rules (open before monitor, bring up before transmit) onto the
 * model, where they would be re-derived, inconsistently, on every session.
 *
 * Results are returned as pretty JSON text: it reads well in a transcript and
 * parses cleanly when the model wants to compute on it.
 */
internal class Tools(
    private val radios: RadioManager,
    private val captures: CaptureService,
    private val exportDir: Path,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /**
     * `encodeDefaults` keeps counts we always compute — a zero CRC-error count
     * is a measurement, and omitting it would make "clean" indistinguishable
     * from "not looked at". `explicitNulls = false` drops nulls, which is the
     * opposite case: a null field is one this chip genuinely does not report,
     * and emitting it as 0 would invent a measurement.
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun registerAll(server: Server) {
        registerDiscover(server)
        registerObserve(server)
        registerInspect(server)
        registerTransmit(server)
        registerExperiment(server)
    }

    // ---------------------------------------------------------------- DISCOVER

    private fun registerDiscover(server: Server) {
        server.addTool(
            name = "radio_list",
            description = """
                List USB Wi-Fi adapters this system could drive with Devourer.

                Read the `identification` field carefully:
                  usb_id         — matched a static VID:PID table in Devourer's source. Authoritative.
                  probe_required — a plausible Realtek 11ac part. Devourer identifies those by reading
                                   a chip-id register over USB, so the backend is genuinely UNKNOWN
                                   until radio_open. Do not report it as identified.
                `backend_compiled_in` says the backend is in this build. That is not evidence that any
                hardware was detected, initialized, or ever received a frame.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put(
                        "include_unsupported",
                        schema("boolean", "Also list devices no Devourer backend claims (triage)."),
                    )
                },
            ),
        ) { request ->
            val all = request.boolOr("include_unsupported", false)
            val result = radios.list(includeAll = all)
            text(
                json.encodeToString(
                    RadioListReply.serializer(),
                    RadioListReply(
                        devices = result.devices,
                        note = result.note,
                        verification = result.devices.associate {
                            it.locator to if (it.backendCompiledIn) {
                                VerificationState.DETECTED
                            } else {
                                VerificationState.UNAVAILABLE
                            }
                        },
                    ),
                ),
            )
        }

        server.addTool(
            name = "radio_open",
            description = """
                Claim an adapter and read its identity and capabilities.

                This is what turns `probe_required` into a real backend: Devourer detaches the kernel
                driver, reads the chip id and constructs the matching backend. The chip is claimed but
                NOT powered up — monitor_start does that.

                Returns the adapter's full capability report. Use it to decide what is possible; every
                advanced operation is gated on these values rather than on chipset assumptions.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("bus", schema("integer", "USB bus number from radio_list."))
                    put("address", schema("integer", "USB device address from radio_list."))
                    put(
                        "reset",
                        schema("boolean", "USB-reset during claim. Default true; a warm pickup may set false."),
                    )
                },
                required = listOf("bus", "address"),
            ),
        ) { request ->
            val radio = radios.open(
                bus = request.intOr("bus", -1),
                address = request.intOr("address", -1),
                reset = request.boolOr("reset", true),
            )
            text(json.encodeToString(RadioManager.OpenRadio.serializer(), radio))
        }

        server.addTool(
            name = "radio_describe",
            description = "Re-read an open radio's identity, capabilities and current state.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("session", schema("integer", "Session id from radio_open.")) },
                required = listOf("session"),
            ),
        ) { request ->
            text(
                json.encodeToString(
                    RadioManager.OpenRadio.serializer(),
                    radios.describe(request.intOr("session", -1)),
                ),
            )
        }

        server.addTool(
            name = "radio_close",
            description = "Release an adapter: de-initializes the chip cleanly so it re-enumerates.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("session", schema("integer", "Session id.")) },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            captures.all().filter { it.session == session }.forEach { captures.stop(it.id) }
            radios.close(session)
            text("""{"closed": $session}""")
        }
    }

    // ----------------------------------------------------------------- OBSERVE

    private fun registerObserve(server: Server) {
        server.addTool(
            name = "monitor_start",
            description = """
                Bring up the radio in monitor mode and start capturing frames locally.

                Frames stay on this machine. They are reachable through capture_summary,
                capture_query, frame_inspect and capture_export_pcap — MCP carries conclusions and
                references, never the packet stream.

                Refuses channels or widths the adapter cannot do, rather than quietly capturing on
                something else. If the channel is tunable but outside the TX-power calibrated range,
                the reply carries a `capability_note` saying so.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id from radio_open."))
                    put("channel", schema("integer", "Channel number, e.g. 6 or 36."))
                    put("width_mhz", schema("integer", "5, 10, 20, 40, 80 or 160. Default 20."))
                    put("band", schema("integer", "6 for 6 GHz. A 6 GHz channel number collides with a 5 GHz one, so it cannot be inferred."))
                    put("capacity", schema("integer", "Max frames retained in the ring. Default 200000."))
                },
                required = listOf("session", "channel"),
            ),
        ) { request ->
            val capture = captures.start(
                session = request.intOr("session", -1),
                channel = ChannelSpec(
                    channel = request.intOr("channel", -1),
                    width = ChannelWidth.ofMhz(request.intOr("width_mhz", 20)),
                    band = request.intOr("band", 0),
                ),
                capacity = request.intOr("capacity", 200_000),
            )
            text(
                json.encodeToString(
                    MonitorStarted.serializer(),
                    MonitorStarted(
                        captureId = capture.id,
                        session = capture.session,
                        radio = capture.radioLabel,
                        channel = capture.channel.toString(),
                        capabilityNote = capture.capabilityNote,
                    ),
                ),
            )
        }

        server.addTool(
            name = "monitor_stop",
            description = "Stop a capture's radio. The captured frames stay queryable until discarded.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("capture_id", schema("string", "From monitor_start."))
                    put("discard", schema("boolean", "Also drop the stored frames. Default false."))
                },
                required = listOf("capture_id"),
            ),
        ) { request ->
            val id = request.stringOr("capture_id", "")
            if (request.boolOr("discard", false)) {
                text("""{"capture_id": "$id", "stopped": true, "discarded": ${captures.discard(id)}}""")
            } else {
                val c = captures.stop(id)
                    ?: return@addTool text("""{"error": "no capture $id"}""", isError = true)
                text(
                    """{"capture_id": "$id", "stopped": true, "frames_retained": ${c.store.size}}""",
                )
            }
        }

        server.addTool(
            name = "monitor_status",
            description = """
                Health of the capture pipeline: frames seen, and frames DROPPED because the reader
                could not keep up. A nonzero drop count means the summaries below describe an
                incomplete window — check it before drawing conclusions about rates or loss.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("capture_id", schema("string", "From monitor_start.")) },
            ),
        ) { request ->
            val id = request.stringOr("capture_id", "")
            val list = if (id.isBlank()) captures.all() else listOfNotNull(captures.get(id))
            val rows = list.map { c ->
                val stats = runCatching { radios.stats(c.session) }.getOrNull()
                CaptureStatus(
                    captureId = c.id,
                    session = c.session,
                    radio = c.radioLabel,
                    channel = c.channel.toString(),
                    framesStored = c.store.size,
                    framesAdmitted = c.store.totalAdmitted,
                    evictedFromRing = c.store.droppedOldest,
                    bridgeDropped = stats?.dropped ?: -1,
                    bridgeFrames = stats?.frames ?: -1,
                    running = c.job.isActive,
                )
            }
            text(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CaptureStatus.serializer()), rows))
        }

        server.addTool(
            name = "antenna_check",
            description = """
                Measure which RF chains are actually receiving, on a radio that is monitoring.

                This is the only way to learn anything about ANTENNAS. An adapter's capability report
                gives the silicon's chain count (rx_chains) and cannot see what is attached to the
                board: two identical MT7612U adapters, same USB id and same 2T2R chip, may have two
                antenna connectors or four with diversity switching. Nothing static distinguishes
                them — only a chain sitting at the noise floor while its neighbour does not.

                Best-effort and easy to fool: strong near-field traffic can light a chain whose
                antenna is missing, through coupling. Repeat across channels and signal levels before
                treating a single window as a verdict.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a radio that is currently monitoring."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val live = radios.activeRxPaths(session)
            // Prefer the chip's own estimator; fall back to the capture when the
            // backend never implemented it (MT7612U, today). Returning the
            // estimator's zeroed struct as though it were a measurement would
            // be the exact failure this tool exists to avoid.
            val supported = live["supported"]?.jsonPrimitive?.boolean ?: false
            val capture = captures.all().firstOrNull { it.session == session }
            val balance = capture?.let { analyseChainBalance(it.store.summarizeAll()) }
            text(
                json.encodeToString(
                    AntennaReply.serializer(),
                    AntennaReply(
                        liveEstimator = live,
                        liveEstimatorSupported = supported,
                        captureDerived = balance,
                        source = when {
                            supported -> "chip estimator (IRadio::GetActiveRxPaths)"
                            balance != null ->
                                "capture-derived chain balance — this backend does not " +
                                    "implement the chip estimator"
                            else -> "nothing available: no chip estimator and no running capture"
                        },
                    ),
                ),
                isError = !supported && balance == null,
            )
        }
    }

    // ----------------------------------------------------------------- INSPECT

    private fun registerInspect(server: Server) {
        server.addTool(
            name = "capture_summary",
            description = """
                Compact statistical account of a capture: frame kinds, per-chain RSSI and SNR, retry
                and CRC-error counts, aggregation, top transmitters and BSSIDs.

                Signal means are computed only over frames that actually reported a measurement.
                A radio fills the PHY-status block on some frames and not others (on an A-MPDU, only
                the first subframe), so averaging the unreported zeros would understate every link.
                `evicted_before_window` nonzero means the ring lost the start of the capture.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("capture_id", schema("string", "From monitor_start."))
                    put("kind", schema("string", "Optional filter, e.g. 'mgmt/beacon' or 'data/qos-data'."))
                    put("transmitter", schema("string", "Optional MAC filter."))
                    put("bssid", schema("string", "Optional BSSID filter."))
                },
                required = listOf("capture_id"),
            ),
        ) { request ->
            val capture = captures.get(request.stringOr("capture_id", ""))
                ?: return@addTool text("""{"error": "no such capture"}""", isError = true)
            val summary = capture.store.summarize(request.toQuery())
            text(
                json.encodeToString(
                    SummaryReply.serializer(),
                    SummaryReply(summary, capture.capabilityNote),
                ),
            )
        }

        server.addTool(
            name = "capture_query",
            description = """
                Frames matching a filter, newest first, with their PHY metadata and decoded addresses.

                Returns frame REFERENCES (stable indices) alongside the metadata. Use frame_inspect
                with an index for the raw bytes — summaries are an optimization, the bytes are the
                evidence and stay reachable.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("capture_id", schema("string", "From monitor_start."))
                    put("kind", schema("string", "e.g. 'mgmt/beacon', 'ctrl/block-ack', 'data/qos-data'."))
                    put("transmitter", schema("string", "MAC address filter."))
                    put("bssid", schema("string", "BSSID filter."))
                    put("crc_error", schema("boolean", "Only frames that failed / passed FCS."))
                    put("retry", schema("boolean", "Only retransmissions."))
                    put("aggregated", schema("boolean", "Only frames from an A-MPDU."))
                    put("min_rssi", schema("integer", "Minimum best-chain RSSI."))
                    put("limit", schema("integer", "Max rows. Default 20."))
                },
                required = listOf("capture_id"),
            ),
        ) { request ->
            val capture = captures.get(request.stringOr("capture_id", ""))
                ?: return@addTool text("""{"error": "no such capture"}""", isError = true)
            val rows = capture.store
                .query(request.toQuery(), limit = request.intOr("limit", 20))
                .map { it.toRow() }
            text(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FrameRow.serializer()), rows))
        }

        server.addTool(
            name = "frame_inspect",
            description = """
                One frame in full: every PHY/MAC field the chip reported, decoded addresses, and the
                raw MPDU as hex.

                Fields a given chip does not populate are reported as absent rather than zero —
                an MT7612U has no RX timestamp and strips the FCS, and reading those zeros as
                measurements would be wrong.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("capture_id", schema("string", "From monitor_start."))
                    put("index", schema("integer", "Frame index from capture_query."))
                    put("max_hex_bytes", schema("integer", "Cap on raw hex returned. Default 256."))
                },
                required = listOf("capture_id", "index"),
            ),
        ) { request ->
            val capture = captures.get(request.stringOr("capture_id", ""))
                ?: return@addTool text("""{"error": "no such capture"}""", isError = true)
            val index = request.longOr("index", -1)
            val stored = capture.store.frame(index)
                ?: return@addTool text(
                    """{"error": "frame $index is no longer in the ring (evicted). Its bytes are gone; re-capture or export sooner."}""",
                    isError = true,
                )
            val r = stored.record
            val fc = r.frameControl
            val addr = fc?.let { FrameAddresses.parse(r.payload, it) }
            val cap = request.intOr("max_hex_bytes", 256)
            text(
                json.encodeToString(
                    FrameDetail.serializer(),
                    FrameDetail(
                        index = stored.index,
                        kind = fc?.name ?: "unparsed",
                        hostNanos = r.hostNanos,
                        lengthBytes = r.packetLength,
                        sequenceNumber = r.sequenceNumber,
                        rateCode = r.dataRate,
                        bandwidth = r.bandwidth,
                        shortGi = r.shortGi != 0,
                        ldpc = r.ldpc != 0,
                        stbc = r.stbc != 0,
                        rssiPerChain = r.rssiByChain,
                        snrPerChain = r.snrByChain.takeIf { c -> c.any { it != 0 } },
                        evm = r.evm.take(r.rxChains.coerceIn(1, 4))
                            .takeIf { c -> c.any { it != 0 } },
                        rxChains = r.rxChains,
                        cfoTailKhz = if (r.cfoTail != 0) r.cfoTail * 2.5 else null,
                        chipTsf = r.tsfl.takeIf { it != 0L },
                        txEgressTsf = r.txEgressTsf,
                        aggregated = r.aggregated,
                        ppduCount = r.ppduCount,
                        retry = fc?.retry,
                        crcError = r.crcError,
                        fcsPresent = r.fcsPresent,
                        phyStatusPresent = r.phyStatus,
                        protectedFrame = fc?.protectedFrame,
                        addresses = addr?.let {
                            mapOf(
                                "receiver" to it.receiver,
                                "transmitter" to it.transmitter,
                                "bssid" to it.bssid,
                                "source" to it.source,
                                "destination" to it.destination,
                            ).filterValues { v -> v != null }.mapValues { e -> e.value!! }
                        } ?: emptyMap(),
                        truncated = r.truncated,
                        rawHex = r.payload.take(cap).joinToString("") { "%02x".format(it) },
                        rawBytesShown = minOf(cap, r.payload.size),
                        rawBytesTotal = r.payload.size,
                    ),
                ),
            )
        }

        server.addTool(
            name = "capture_export_pcap",
            description = """
                Write a capture to a libpcap file with synthesized radiotap headers, openable in
                Wireshark or tshark. Raw MPDU bytes are written unmodified.

                Returns the path. The file stays on this machine; MCP never carries the packets.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("capture_id", schema("string", "From monitor_start."))
                    put("filename", schema("string", "Optional name; defaults to the capture id."))
                    put("kind", schema("string", "Optional filter, as in capture_query."))
                    put("limit", schema("integer", "Max frames. Default 100000."))
                },
                required = listOf("capture_id"),
            ),
        ) { request ->
            val capture = captures.get(request.stringOr("capture_id", ""))
                ?: return@addTool text("""{"error": "no such capture"}""", isError = true)
            val frames = capture.store
                .query(request.toQuery().copy(newestFirst = false), limit = request.intOr("limit", 100_000))
                .map { it.record }
            val name = request.stringOr("filename", "").ifBlank { "${capture.id}.pcap" }
            val path = exportDir.resolve(name.substringAfterLast('/'))
            val written = PcapWriter.write(
                path = path,
                frames = frames,
                centerFrequencyMhz = RadioManager.centerFrequencyMhz(capture.channel),
            )
            text(
                """{"path": "$path", "frames": $written, "radio": "${capture.radioLabel}", "channel": "${capture.channel}"}""",
            )
        }
    }

    // ---------------------------------------------------------------- TRANSMIT

    private fun registerTransmit(server: Server) {
        server.addTool(
            name = "tx_send",
            description = """
                Transmit a frame a bounded number of times on an open, brought-up radio.

                The frame is a radiotap header followed by an 802.11 MPDU, as hex. `count` is capped:
                this is not a path for sustained transmission — that belongs to a bounded, cancellable
                experiment with a caller watching.

                SUCCESS HERE IS NOT PROOF OF TRANSMISSION. A clean submission into the TX path says
                the driver accepted the frame, not that anything reached the air. Only an independent
                receiver — a second adapter monitoring the same channel — can establish that.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a radio that has been brought up."))
                    put("frame_hex", schema("string", "Radiotap header + 802.11 MPDU, hex encoded."))
                    put("count", schema("integer", "Repetitions, 1..100000. Default 1."))
                },
                required = listOf("session", "frame_hex"),
            ),
        ) { request ->
            val result = radios.sendFrame(
                session = request.intOr("session", -1),
                frameHex = request.stringOr("frame_hex", ""),
                count = request.intOr("count", 1),
            )
            text(
                json.encodeToString(JsonObject.serializer(), result) +
                    "\n\nNote: this reports frames accepted by the TX path. Confirm on an " +
                    "independent monitor radio before treating it as TX_VERIFIED.",
            )
        }
    }

    // -------------------------------------------------------------- EXPERIMENT

    private fun registerExperiment(server: Server) {
        server.addTool(
            name = "experiment_link_probe",
            description = """
                Transmit a bounded burst on one radio and count what a SECOND, independent radio
                hears. Sweeps TX modes if given several.

                This is the only tool that can establish TX_VERIFIED. A transmitting radio
                reporting success proves its TX path accepted the frames — not that a photon left
                the antenna. The receiver here is a different physical adapter, which is what makes
                the result evidence rather than self-report.

                Probe frames are broadcast, so they are never ACKed and never retried: what the
                receiver counts is what the transmitter actually aired, once each. That makes the
                delivery ratio a clean one-way measurement, and NOT a throughput figure.

                Give several modes to find the highest reliable one, e.g.
                ["6M","MCS0/20","MCS3/20","MCS5/20","MCS7/20"]. The whole run is bounded by
                max_duration_ms and stops cleanly when it expires.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("tx_session", schema("integer", "Transmitting radio's session id."))
                    put("rx_session", schema("integer", "Independent receiving radio. Must differ from tx_session."))
                    put("channel", schema("integer", "Channel both radios use."))
                    put("width_mhz", schema("integer", "5, 10, 20, 40, 80 or 160. Default 20."))
                    put("modes", schema("array", "TX mode specs, e.g. [\"6M\",\"MCS5/20\"]. Default [\"6M\"]."))
                    put("frames_per_point", schema("integer", "Frames transmitted per mode. Default 200."))
                    put("interval_us", schema("integer", "Spacing between frames. Default 1000."))
                    put("frame_bytes", schema("integer", "Probe MPDU size. Default 200."))
                    put("max_duration_ms", schema("integer", "Hard ceiling on the run. Default 60000."))
                },
                required = listOf("tx_session", "rx_session", "channel"),
            ),
        ) { request ->
            val modes = request.stringList("modes").ifEmpty { listOf("6M") }
            val bounds = ExperimentBounds(
                maxDurationMs = request.longOr("max_duration_ms", 60_000),
                framesPerPoint = request.intOr("frames_per_point", 200),
                intervalUs = request.intOr("interval_us", 1_000),
            )
            val result = LinkProbe(radios, scope).run(
                txSession = request.intOr("tx_session", -1),
                rxSession = request.intOr("rx_session", -1),
                channel = ChannelSpec(
                    channel = request.intOr("channel", -1),
                    width = ChannelWidth.ofMhz(request.intOr("width_mhz", 20)),
                    band = request.intOr("band", 0),
                ),
                modes = modes,
                bounds = bounds,
                frameBytes = request.intOr("frame_bytes", 200),
            )
            text(
                json.encodeToString(ExperimentResult.serializer(), result),
                isError = result.verification == VerificationState.FAILED,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun text(body: String, isError: Boolean = false) =
        CallToolResult(content = listOf(TextContent(body)), isError = isError.takeIf { it })

    private fun schema(type: String, description: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(description))
    }
}

/** Reads arguments defensively: a missing or wrong-typed field falls back. */
internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.intOr(key: String, default: Int): Int =
    runCatching { params.arguments?.get(key)?.jsonPrimitive?.int }.getOrNull() ?: default

internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.longOr(key: String, default: Long): Long =
    runCatching { params.arguments?.get(key)?.jsonPrimitive?.content?.toLong() }.getOrNull() ?: default

internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.boolOr(key: String, default: Boolean): Boolean =
    runCatching { params.arguments?.get(key)?.jsonPrimitive?.boolean }.getOrNull() ?: default

internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.stringOr(key: String, default: String): String =
    runCatching { params.arguments?.get(key)?.jsonPrimitive?.content }.getOrNull() ?: default

internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.stringList(key: String): List<String> =
    runCatching {
        (params.arguments?.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content }
    }.getOrNull() ?: emptyList()

internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.toQuery(): FrameQuery = FrameQuery(
    kind = stringOr("kind", "").ifBlank { null },
    transmitter = stringOr("transmitter", "").ifBlank { null },
    bssid = stringOr("bssid", "").ifBlank { null },
    crcError = runCatching { params.arguments?.get("crc_error")?.jsonPrimitive?.boolean }.getOrNull(),
    retry = runCatching { params.arguments?.get("retry")?.jsonPrimitive?.boolean }.getOrNull(),
    aggregated = runCatching { params.arguments?.get("aggregated")?.jsonPrimitive?.boolean }.getOrNull(),
    minRssi = runCatching { params.arguments?.get("min_rssi")?.jsonPrimitive?.int }.getOrNull(),
)

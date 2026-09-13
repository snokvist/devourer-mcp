package org.openipc.devourer.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.capture.CaptureSummary
import org.openipc.devourer.capture.analyseChainBalance
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import org.openipc.devourer.experiment.ChannelLabel
import org.openipc.devourer.experiment.ExperimentBounds
import org.openipc.devourer.experiment.ExperimentException
import org.openipc.devourer.experiment.ExperimentResult
import org.openipc.devourer.experiment.ExperimentRunner
import org.openipc.devourer.experiment.ExperimentSpec
import org.openipc.devourer.experiment.RadioRole
import org.openipc.devourer.experiment.Sweep
import org.openipc.devourer.experiment.SweepPoint
import org.openipc.devourer.characterize.AdapterIdentity
import org.openipc.devourer.characterize.Characterization
import org.openipc.devourer.characterize.Characterizer
import org.openipc.devourer.characterize.EvidenceStore
import org.openipc.devourer.experiment.LinkProbe
import org.openipc.devourer.scratchpad.Capability
import org.openipc.devourer.scratchpad.CapabilityGrant
import org.openipc.devourer.scratchpad.ScratchpadProgram
import org.openipc.devourer.scratchpad.ScratchpadService
import org.openipc.devourer.capture.FrameQuery
import org.openipc.devourer.capture.PcapWriter
import org.openipc.devourer.protocol.CcaGates
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.protocol.FrameAddresses
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.protocol.RxGain
import org.openipc.devourer.protocol.RxQuality
import org.openipc.devourer.protocol.Thermal
import org.openipc.devourer.protocol.TxPower
import org.openipc.devourer.protocol.TxRateDiffs
import org.openipc.devourer.radio.CapabilityException
import org.openipc.devourer.radio.ChannelInfo
import org.openipc.devourer.radio.OpenRadio
import org.openipc.devourer.radio.Radios
import org.openipc.devourer.radio.centerFrequencyMhz
import org.openipc.devourer.radio.SafetyLevel
import org.openipc.devourer.radio.SpectrumScanner
import org.openipc.devourer.radio.SpectrumSweep
import org.openipc.devourer.radio.VerificationState
import org.openipc.devourer.capture.CaptureService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import org.openipc.devourer.dashboard.ActivityLog

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
    private val radios: Radios,
    private val captures: CaptureService,
    private val exportDir: Path,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val evidence: EvidenceStore,
    private val scratchpads: ScratchpadService,
    private val experiments: ExperimentRunner,
    private val activity: ActivityLog,
) {
    /**
     * `encodeDefaults` keeps counts we always compute — a zero CRC-error count
     * is a measurement, and omitting it would make "clean" indistinguishable
     * from "not looked at". `explicitNulls = false` drops nulls, which is the
     * opposite case: a null field is one this chip genuinely does not report,
     * and emitting it as 0 would invent a measurement.
     */
    private companion object {
        /** Ceiling on rows a single capture_query may return. */
        const val MAX_QUERY_ROWS = 500

        /** Ceiling on raw hex from frame_inspect. */
        const val MAX_HEX_BYTES = 4096

        /** Ceiling on one argument value in the activity feed. */
        const val MAX_ARG_VALUE = 60
    }

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
        registerCharacterize(server)
        registerScratchpad(server)
    }

    // ---------------------------------------------------------------- DISCOVER

    private fun registerDiscover(server: Server) {
        register(
            server,
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

        register(
            server,
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
                    put(
                        "noise_floor",
                        schema(
                            "boolean",
                            "Ask for an ABSOLUTE frame-free noise floor in channel_energy. Must be " +
                                "set at open: the backend reads its config once. Reaches a live " +
                                "reading on Jaguar2 only; on Realtek wave-1 (8812AU) the vendor " +
                                "measurement lives in a bring-up path this bridge does not use, and " +
                                "channel_energy will say so rather than return a zero. Default false.",
                        ),
                    )
                    put(
                        "adaptive_gain",
                        schema(
                            "boolean",
                            "Run devourer's phydm watchdog (DIG + EDCCA threshold tracking) on this " +
                                "radio. Default false, which is devourer's default and NOT a neutral " +
                                "one: without it the initial-gain index stays pinned at its bring-up " +
                                "value and carrier sense keeps the static threshold set then, whatever " +
                                "the channel is doing. Turn it on when you are asking why a " +
                                "transmitter defers. Jaguar1 only; it writes BB registers from a " +
                                "background thread.",
                        ),
                    )
                },
                required = listOf("bus", "address"),
            ),
        ) { request ->
            val radio = radios.open(
                bus = request.intOr("bus", -1),
                address = request.intOr("address", -1),
                reset = request.boolOr("reset", true),
                noiseFloor = request.boolOr("noise_floor", false),
                adaptiveGain = request.boolOr("adaptive_gain", false),
            )
            text(json.encodeToString(OpenRadio.serializer(), radio))
        }

        register(
            server,
            name = "radio_describe",
            description = "Re-read an open radio's identity, capabilities and current state.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("session", schema("integer", "Session id from radio_open.")) },
                required = listOf("session"),
            ),
        ) { request ->
            text(
                json.encodeToString(
                    OpenRadio.serializer(),
                    radios.describe(request.intOr("session", -1)),
                ),
            )
        }

        register(
            server,
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
            text(reply("closed" to session))
        }
    }

    // ----------------------------------------------------------------- OBSERVE

    private fun registerObserve(server: Server) {
        register(
            server,
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
                // An unclamped capacity means the ring never evicts and grows
                // until the JVM dies.
                capacity = request.intOr("capacity", 200_000).coerceIn(1_000, 2_000_000),
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

        register(
            server,
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
                text(reply("capture_id" to id, "stopped" to true, "discarded" to captures.discard(id)))
            } else {
                val c = captures.stop(id)
                    ?: return@register text(noSuchCapture(id), isError = true)
                text(reply("capture_id" to id, "stopped" to true,
                    "frames_retained" to c.store.size))
            }
        }

        register(
            server,
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

        register(
            server,
            name = "radio_fast_retune",
            description = """
                Move a brought-up radio to another channel on the SAME band without the full
                retune. The width, offset and band are kept; only the RF channel changes.

                This is the lean path a dwell or scan loop uses — a full SetMonitorChannel
                costs ~130 ms on a Realtek, which dominates a per-dwell sweep. It requires the
                radio already brought up; use monitor_start for the first tune.

                On a band change, or where the adapter has no lean path, devourer falls back
                to a full retune. That is not an error: the reply's `fast_retune` says whether
                this adapter has the lean path at all, so a timing conclusion can tell a hop
                from a full tune. The reply carries the resulting channel/width/offset/band.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a brought-up radio."))
                    put("channel", schema("integer", "Target channel on the current band."))
                },
                required = listOf("session", "channel"),
            ),
        ) { request ->
            val channel = request.intOr("channel", -1)
            if (channel < 0) {
                return@register text(errorReply("channel is required"), isError = true)
            }
            val result = radios.fastRetune(request.intOr("session", -1), channel)
            text(json.encodeToString(ChannelInfo.serializer(), result))
        }

        register(
            server,
            name = "radio_fast_bandwidth",
            description = """
                Toggle a brought-up radio between 20 MHz and 5/10 MHz narrowband without the
                full retune (the bandwidth analogue of radio_fast_retune).

                On chips with the fast path the switch is a single baseband re-clock; any
                other endpoint falls back to a full SetMonitorChannel. The reply carries the
                resulting width (and the channel it stayed on).
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a brought-up radio."))
                    put("width_mhz", schema("integer", "5, 10, 20, 40, 80 or 160."))
                },
                required = listOf("session", "width_mhz"),
            ),
        ) { request ->
            val width = request.intOr("width_mhz", -1)
            if (width < 0) {
                return@register text(errorReply("width_mhz is required"), isError = true)
            }
            val result = radios.fastBandwidth(request.intOr("session", -1), width)
            text(json.encodeToString(ChannelInfo.serializer(), result))
        }

        register(
            server,
            name = "channel_energy",
            description = """
                What this radio's own PHY sees on its channel, WITHOUT decoding a frame.

                A different quantity from a frame count, and the one that actually matters when a
                transmitter will not transmit. capture_summary tells you how much traffic a receiver
                could decode; carrier sense defers on ENERGY, including energy that never resolves
                into a frame — a microwave, an overlapping-channel emitter, a noisy port. This reads
                the chip's own false-alarm and channel-busy counters, plus the AGC's initial-gain
                index as a relative noise-floor proxy, and optionally a 12-bucket in-band power
                histogram.

                The counters are deltas, and the hardware resets them on every read, so this tool
                owns the protocol: it reads once to reset, dwells, and reads again. A single raw read
                would be a delta since some unknown earlier moment.

                Realtek only. A MediaTek says so rather than reporting zeros — "this silicon has no
                such counter" and "the channel is quiet" are opposite claims.

                Read it on the radio whose behaviour you are asking about. If a transmitter is
                deferring, it is that transmitter's PHY whose view decides, not a witness's.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id from radio_open."))
                    put("dwell_ms", schema("integer", "Measurement window. Default 500, max 10000."))
                    put("with_nhm", schema("boolean", "Include the power histogram (~2ms extra). Default true."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val dwell = request.longOr("dwell_ms", 500).coerceIn(10, 10_000)
            val withNhm = request.boolOr("with_nhm", true)
            val first = radios.rxEnergy(session)
            if (!first.supported) {
                text(json.encodeToString(RxEnergy.serializer(), first))
            } else {
                kotlinx.coroutines.delay(dwell)
                val measured = radios.rxEnergy(session, withNhm = withNhm)
                text(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("dwell_ms", JsonPrimitive(dwell))
                            put(
                                "energy",
                                json.encodeToJsonElement(RxEnergy.serializer(), measured),
                            )
                        },
                    ),
                )
            }
        }

        register(
            server,
            name = "spectrum_sweep",
            description = """
                Dwell each of several channels on the CURRENT band and read the chip's own
                frame-free ENERGY at each, then say which looked clearest.

                The single-channel read channel_energy already does, run as a sweep. Hops use
                the lean retune (radio_fast_retune) where the adapter has it, so a coarse survey
                is cheap; the starting channel is restored when it finishes.

                This is a CLEAR-CHANNEL survey, not a link-quality one. `cca_total` is the
                channel-busy count over the dwell and `fa_total` the false alarms; both are
                energy, not decoded frames, and a receiver that cannot decode reports every
                channel as quiet. `quietest_channel` is a hint for where to look, never a
                throughput prediction.

                Realtek only. The radio must be brought up (monitor_start or radio_fast_retune
                first); a MediaTek reports `supported:false` rather than a picture of zeros.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a brought-up radio."))
                    put(
                        "channels",
                        schema(
                            "array",
                            "Channels to dwell, on the current band, e.g. [1,6,11]. " +
                                "The width/band are kept; only the RF channel moves.",
                        ),
                    )
                    put("dwell_ms", schema("integer", "Dwell per channel. Default 50, 10..10000."))
                    put("with_nhm", schema("boolean", "Include the 12-bucket power histogram per bin (~2ms each). Default false."))
                },
                required = listOf("session", "channels"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val channels = request.strictIntList("channels").orEmpty()
            if (channels.isEmpty()) {
                return@register text(
                    errorReply("channels is required (an array of channel numbers)"),
                    isError = true,
                )
            }
            val dwell = request.intOr("dwell_ms", 50).coerceIn(10, 10_000)
            val result = SpectrumScanner(radios).scan(
                session = session,
                channels = channels,
                dwellMs = dwell,
                withNhm = request.boolOr("with_nhm", false),
            )
            text(json.encodeToString(SpectrumSweep.serializer(), result))
        }

        register(
            server,
            name = "radio_rx_gain",
            description = """
                Read the receive-gain index, the envelope it may be clamped to, and whether an
                adaptive loop is driving it.

                `automatic_input` is the field that makes this more than a number: it says what the
                loop keys on, or that nothing is adapting the gain and why. Two absences are
                distinct and mean opposite things — `supported:false` ("this backend has no gain
                index") versus `valid:false` ("there is one, but the baseband is not up yet"). The
                index is a relative register value, not a dBm figure.

                Pass `min_index` and `max_index` together to clamp it. `min == max` pins the gain.
                The clamp STEERS an adaptive loop rather than replacing it, and on Realtek the
                receive gain and the EDCCA carrier-sense threshold are coupled — raising the gain
                floor also makes carrier sense less sensitive. The caps' min/max is the whole
                supported envelope, not the window in force now; state restored by a caller must
                use the live range.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id from radio_open."))
                    put("min_index", schema("integer", "Lower bound of the clamp. Omit to just read."))
                    put("max_index", schema("integer", "Upper bound of the clamp. `min == max` pins."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val args = request.params.arguments.orEmpty()
            val hasMin = args.containsKey("min_index")
            val hasMax = args.containsKey("max_index")
            val result = when {
                !hasMin && !hasMax -> radios.rxGain(session)
                hasMin && hasMax -> radios.clampRxGain(
                    session,
                    request.intOr("min_index", 0),
                    request.intOr("max_index", 0),
                )
                else -> return@register text(
                    errorReply(
                        "give both min_index and max_index to clamp, or neither to read",
                        "hint" to "the value you set pins the gain when both are equal",
                    ),
                    isError = true,
                )
            }
            text(json.encodeToString(RxGain.serializer(), result))
        }

        register(
            server,
            name = "radio_cca_gates",
            description = """
                Read and set the two carrier-sense gates SEPARATELY.

                primary_cca_disabled defers to a decodable PREAMBLE; edcca_disabled defers to raw
                in-band ENERGY. Which one matters is FAMILY-SPECIFIC and the two measured families
                disagree — on one, turning EDCCA off recovers almost all lost delivery while primary
                CCA alone does little; on another it inverts. `note` carries the measured warning;
                read it before choosing.

                Omit both to read. Supply one or both to change them; the gate you do not name is
                left alone. DISABLING a gate requires safety_level="experimental" — the radio then
                transmits without fully listening and will talk over others on the channel.
                Re-enabling is always allowed, so a cleanup path never needs to re-ask.

                Realtek only. A backend without the split reports `supported:false` with a reason,
                and `cca_disabled` still reflects the combined state — a radio with carrier sense
                fully off cannot present as compliant.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a brought-up radio."))
                    put("primary_cca_disabled", schema("boolean", "Disable the preamble-deferring gate. Omit to leave unchanged."))
                    put("edcca_disabled", schema("boolean", "Disable the energy-deferring gate. Omit to leave unchanged."))
                    put("safety_level", schema("string", "Must be \"experimental\" to disable either gate."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val args = request.params.arguments.orEmpty()
            val hasPrimary = args.containsKey("primary_cca_disabled")
            val hasEdcca = args.containsKey("edcca_disabled")
            val result = if (!hasPrimary && !hasEdcca) {
                radios.ccaGates(session)
            } else {
                radios.setCcaGates(
                    session,
                    primaryCcaDisabled = if (hasPrimary) {
                        request.boolOr("primary_cca_disabled", false)
                    } else {
                        null
                    },
                    edccaDisabled = if (hasEdcca) {
                        request.boolOr("edcca_disabled", false)
                    } else {
                        null
                    },
                    safety = SafetyLevel.parse(request.stringOr("safety_level", "")),
                )
            }
            text(json.encodeToString(CcaGates.serializer(), result))
        }

        register(
            server,
            name = "radio_rx_quality",
            description = """
                The fused, windowed RX link-quality snapshot for one radio: the per-frame
                RSSI/SNR/EVM aggregate, a passive noise-floor estimate, the frame-free
                FA/CCA energy, and a plain-language `verdict` with `cause`/`fix`.

                One read that would otherwise be assembled from the frame store — and one the
                device computes over the window since the PREVIOUS read, so it DRAINS. To
                measure an interval: call once and discard, wait, call again.

                The saturation tell is EVM, not SNR: strong RSSI with a poor EVM means back
                power OFF, the opposite of the weak-link response. `snr_valid`/`evm_valid` say
                whether those were actually measured rather than zero.

                Realtek only. A MediaTek reports `supported:false` with a reason rather than a
                fabricated NO_SIGNAL. Do not poll this and channel_energy on the same cadence:
                on Realtek they consume the same hardware counters.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id from radio_open."))
                    put("dwell_ms", schema("integer", "Measurement window. Default 500, max 10000."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val dwell = request.longOr("dwell_ms", 500).coerceIn(10, 10_000)
            // The window drains on read, so the first call clears whatever
            // accumulated before the caller asked; the second is the window.
            val first = radios.rxQuality(session)
            if (!first.supported) {
                text(json.encodeToString(RxQuality.serializer(), first))
            } else {
                kotlinx.coroutines.delay(dwell)
                val measured = radios.rxQuality(session)
                text(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("dwell_ms", JsonPrimitive(dwell))
                            put(
                                "quality",
                                json.encodeToJsonElement(RxQuality.serializer(), measured),
                            )
                        },
                    ),
                )
            }
        }

        register(
            server,
            name = "radio_thermal",
            description = """
                Read the chip's thermal meter (RF 0x42).

                `raw` is in thermal units (~1.5-2 C each), NOT absolute degrees; `delta` is
                raw minus the baseline and is the heat signal; `bucket` is the coarse
                cool/warm/hot/critical label. `valid:false` means no baseline is available, so
                only `raw` is meaningful.

                This is TELEMETRY, not a validated degradation predictor. Delivery has been
                measured drifting with no relation to this meter, and a reading beside a rate
                ceiling is a companion, never the cause. `supported:false` means no meter is
                wired on this backend — not that the chip is cool.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id from radio_open."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val result = radios.thermal(request.intOr("session", -1))
            text(json.encodeToString(Thermal.serializer(), result))
        }

        register(
            server,
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
        register(
            server,
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
                ?: return@register text(noSuchCapture(request.stringOr("capture_id", "")), isError = true)
            val summary = capture.store.summarize(request.toQuery())
            text(
                json.encodeToString(
                    SummaryReply.serializer(),
                    SummaryReply(summary, capture.capabilityNote),
                ),
            )
        }

        register(
            server,
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
                ?: return@register text(noSuchCapture(request.stringOr("capture_id", "")), isError = true)
            val rows = capture.store
                // Clamped, not merely defaulted. "MCP is the control plane, not
                // the packet data plane" is a rule of the architecture, and a
                // default a caller can override is not a rule. At ~300 bytes a
                // row, an unclamped limit pulled the whole 200k ring — ~60 MB —
                // through a single tool result. Bulk goes via capture_export_pcap.
                .query(request.toQuery(), limit = request.intOr("limit", 20).coerceIn(1, MAX_QUERY_ROWS))
                .map { it.toRow() }
            text(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FrameRow.serializer()), rows))
        }

        register(
            server,
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
                ?: return@register text(noSuchCapture(request.stringOr("capture_id", "")), isError = true)
            val index = request.longOr("index", -1)
            val stored = capture.store.frame(index)
                ?: return@register text(
                    """{"error": "frame $index is no longer in the ring (evicted). Its bytes are gone; re-capture or export sooner."}""",
                    isError = true,
                )
            val r = stored.record
            val fc = r.frameControl
            val addr = fc?.let { FrameAddresses.parse(r.payload, it) }
            val cap = request.intOr("max_hex_bytes", 256).coerceIn(16, MAX_HEX_BYTES)
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

        register(
            server,
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
                ?: return@register text(noSuchCapture(request.stringOr("capture_id", "")), isError = true)
            val frames = capture.store
                .query(
                    request.toQuery().copy(newestFirst = false),
                    limit = request.intOr("limit", 100_000).coerceIn(1, 1_000_000),
                )
                .map { it.record }
            val name = request.stringOr("filename", "").ifBlank { "${capture.id}.pcap" }
            val path = exportDir.resolve(name.substringAfterLast('/'))
            val written = PcapWriter.write(
                path = path,
                frames = frames,
                centerFrequencyMhz = centerFrequencyMhz(capture.channel),
            )
            text(
                reply(
                    "path" to path.toString(),
                    "frames" to written,
                    "radio" to capture.radioLabel,
                    "channel" to capture.channel.toString(),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- TRANSMIT

    private fun registerTransmit(server: Server) {
        register(
            server,
            name = "radio_tx_power",
            description = """
                Read and set the runtime TX-power knobs on an open radio.

                An INDEX/OFFSET model, never dBm. `step_qdb` and `step_measured` describe the
                knob: when `step_measured` is false the dB-per-step slope has not been validated
                on air for this family, so an absolute dBm claim built on it is an extrapolation.
                Relative index comparisons remain real.

                Omit all arguments to read. To set:

                  offset_qdb         relative to the efuse-calibrated per-rate table, preserving
                                     its shape. This is the closed-loop controller's knob.
                  index_override     >= 0 forces one flat absolute index for every rate; -1
                                     reverts to the per-rate table.
                  reapply            re-program at the current channel without moving a knob.

                `flat_index` reads -1 for the calibrated baseline; `saturated_low`/`saturated_high`
                say the last apply hit a rail, i.e. the knob is out of travel in that direction.
                `hw_readback` false means the indices are the driver's shadow, not a register read.

                `rate_diffs` REPLACES the calibrated per-rate shape — an object with `cck`,
                `legacy` and exactly 8 `mcs` entries, each a signed quarter-dB diff against the
                HT-MCS7 reference. Every rate the table does not describe sits at the anchor.
                `clear_rate_diffs:true` restores the chip's own shape. Only a backend whose caps
                report `rate_diffs:true` accepts either.

                Nothing here is regulatory-clamped — compliance is the operator's. On Realtek the
                receive gain and the EDCCA threshold are coupled, so transmit power and
                carrier-sense sensitivity are not fully independent.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id from radio_open."))
                    put("offset_qdb", schema("integer", "Relative power offset in quarter-dB. Omit to leave unchanged."))
                    put("index_override", schema("integer", "Flat absolute TXAGC index (>= 0), or -1 to clear. Omit to leave unchanged."))
                    put("rate_diffs", schema("object", "Replace the per-rate shape: {cck, legacy, mcs:[8 signed qdB diffs vs the MCS7 anchor]}. Omit to leave unchanged."))
                    put("clear_rate_diffs", schema("boolean", "Restore the chip's calibrated per-rate shape."))
                    put("reapply", schema("boolean", "Re-program TX power at the current channel. Needs the chip brought up."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val session = request.intOr("session", -1)
            val offset = request.optionalInt("offset_qdb")
            val index = request.optionalInt("index_override")
            val clearRateDiffs = request.optionalBoolean("clear_rate_diffs") ?: false
            // Absent and JSON-null both mean "leave the configured shape alone";
            // only clear_rate_diffs restores the chip's own.
            val diffsElement = request.params.arguments?.get("rate_diffs")
            val rateDiffs = if (diffsElement == null || diffsElement is JsonNull) {
                null
            } else {
                json.decodeFromJsonElement(TxRateDiffs.serializer(), diffsElement)
            }
            val reapply = request.optionalBoolean("reapply") ?: false
            val isRead = offset == null && index == null && rateDiffs == null &&
                !clearRateDiffs && !reapply
            val result = if (isRead) {
                radios.txPower(session)
            } else {
                radios.setTxPower(
                    session,
                    offsetQdb = offset,
                    indexOverride = index,
                    rateDiffs = rateDiffs,
                    clearRateDiffs = clearRateDiffs,
                    reapply = reapply,
                )
            }
            text(json.encodeToString(TxPower.serializer(), result))
        }

        register(
            server,
            name = "tx_send",
            description = """
                Transmit a frame a bounded number of times on an open, brought-up radio.

                The frame is a radiotap header followed by an 802.11 MPDU, as hex. `count` is capped:
                this is not a path for sustained transmission — that belongs to a bounded, cancellable
                experiment with a caller watching.

                SUCCESS HERE IS NOT PROOF OF TRANSMISSION. A clean submission into the TX path says
                the driver accepted the frame, not that anything reached the air. Only an independent
                receiver — a second adapter monitoring the same channel — can establish that.

                This is the RAW path: you assemble the radiotap header yourself and nothing checks it
                against the adapter's capability report. It therefore requires
                safety_level="developer". For ordinary transmission use experiment_link_probe, which
                builds the header from a structured TX mode and measures the result.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "Session id of a radio that has been brought up."))
                    put("frame_hex", schema("string", "Radiotap header + 802.11 MPDU, hex encoded."))
                    put("count", schema("integer", "Repetitions, 1..100000. Default 1."))
                    put("safety_level", schema("string", "Must be \"developer\" for this raw path. Omitted means \"normal\", which is refused."))
                },
                required = listOf("session", "frame_hex", "safety_level"),
            ),
        ) { request ->
            val result = radios.sendFrame(
                session = request.intOr("session", -1),
                frameHex = request.stringOr("frame_hex", ""),
                count = request.intOr("count", 1),
                safety = SafetyLevel.parse(request.stringOr("safety_level", "")),
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
        register(
            server,
            name = "experiment_link_probe",
            description = """
                Transmit a bounded burst on one radio and count what OTHER, independent radios
                hear. Sweeps up to four axes: TX mode, channel, frame size and frame spacing.

                This is the only tool that can establish TX_VERIFIED. A transmitting radio
                reporting success proves its TX path accepted the frames — not that a photon left
                the antenna. Every receiver here is a different physical adapter, which is what
                makes the result evidence rather than self-report.

                Probe frames are broadcast, so they are never ACKed and never retried: what a
                receiver counts is what the transmitter actually aired, once each. That makes the
                delivery ratio a clean one-way measurement, and NOT a throughput figure.

                Give several modes to find the highest reliable one, e.g.
                ["6M","MCS0/20","MCS3/20","MCS5/20","MCS7/20"].

                Give witness_sessions to add a SECOND and THIRD independent receiver hearing the
                same burst simultaneously. That is a qualitatively different measurement, not just
                a repeat: when two receivers agree frame-for-frame, the missing frames were never
                aired and the fault is at the transmitter. Two separate runs cannot show that,
                because the air changes between them.

                The run is bounded three ways: max_duration_ms for the whole run, a hard per-point
                deadline that catches a wedged adapter, and a ceiling on how many points a sweep
                may expand to. It can also be stopped early — see experiment_cancel.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("tx_session", schema("integer", "Transmitting radio's session id."))
                    put("rx_session", schema("integer", "Independent receiving radio. Must differ from tx_session."))
                    put(
                        "witness_sessions",
                        schema(
                            "array",
                            "Additional independent receivers (up to 2), each hearing the same " +
                                "burst at the same time. Use this to rule the receiver out: two " +
                                "witnesses agreeing exactly means the loss is transmit-side.",
                        ),
                    )
                    put("channel", schema("integer", "Channel every radio uses, unless sweep_channels is given."))
                    put("width_mhz", schema("integer", "5, 10, 20, 40, 80 or 160. Default 20."))
                    put("modes", schema("array", "TX mode specs to sweep, e.g. [\"6M\",\"MCS5/20\"]. Default [\"6M\"]."))
                    put("sweep_channels", schema("array", "Channels to sweep, as text: [\"ch1\",\"ch6/40\"]. Overrides channel."))
                    put("sweep_frame_bytes", schema("array", "Probe MPDU sizes to sweep, 40..1500."))
                    put("sweep_interval_us", schema("array", "Frame spacings to sweep, 0..1000000."))
                    put(
                        "sweep_power_qdb",
                        schema(
                            "array",
                            "TX-power offsets to sweep, in quarter-dB relative to the " +
                                "adapter's calibrated per-rate table (a single value pins it). " +
                                "The envelope is the adapter's; an out-of-range request is " +
                                "refused before anything transmits. This is what produces " +
                                "delivery-vs-power; the shape is real, the absolute dB needs " +
                                "radio_tx_power's step_measured.",
                        ),
                    )
                    put("frames_per_point", schema("integer", "Frames transmitted per point. Default 200."))
                    put("interval_us", schema("integer", "Spacing between frames. Default 1000."))
                    put("frame_bytes", schema("integer", "Probe MPDU size. Default 200."))
                    put("max_duration_ms", schema("integer", "Hard ceiling on the run. Default 60000."))
                    put("safety_level", schema("string", "\"normal\" (default) or \"experimental\". Required to be \"experimental\" if carrier_sense is false."))
                    put(
                        "carrier_sense",
                        schema(
                            "boolean",
                            "Default true. Requires safety_level=\"experimental\" when false: the transmitter stops " +
                                "listening before it transmits, so it will talk over anyone " +
                                "sharing the channel. Use only on a channel you control. It is " +
                                "restored automatically when the run ends. Set it false when " +
                                "delivery is poor but tx_stats shows every frame submitted and " +
                                "none failed — that pattern means the MAC is deferring, not that " +
                                "the link is bad.",
                        ),
                    )
                },
                required = listOf("tx_session", "rx_session"),
            ),
        ) { request ->
            try {
                val modes = request.stringList("modes").ifEmpty { listOf("6M") }
                val baseChannel = ChannelSpec(
                    channel = request.intOr("channel", -1),
                    width = ChannelWidth.ofMhz(request.intOr("width_mhz", 20)),
                    band = request.intOr("band", 0),
                )
                val sweepChannels = request.stringList("sweep_channels")
                if (sweepChannels.isEmpty() && baseChannel.channel < 0) {
                    throw ExperimentException("give either channel or sweep_channels")
                }
                val intervalUs = request.intOr("interval_us", 1_000)
                val bounds = ExperimentBounds(
                    maxDurationMs = request.longOr("max_duration_ms", 60_000),
                    framesPerPoint = request.intOr("frames_per_point", 200),
                    intervalUs = intervalUs,
                )
                // Strict: a malformed power axis must fail, not silently vanish
                // and leave a "power sweep" that swept nothing.
                val sweepPower = request.strictIntList("sweep_power_qdb") ?: emptyList()
                val witnessRoles = listOf(RadioRole.MONITOR, RadioRole.MONITOR_2)
                val extra = request.intList("witness_sessions")
                if (extra.size > witnessRoles.size) {
                    throw ExperimentException(
                        "at most ${witnessRoles.size} extra witnesses (roles " +
                            "${witnessRoles.joinToString()}); got ${extra.size}",
                    )
                }
                val spec = ExperimentSpec(
                    roles = buildMap {
                        put(RadioRole.TX_PEER, request.intOr("tx_session", -1))
                        put(RadioRole.RX_PEER, request.intOr("rx_session", -1))
                        extra.forEachIndexed { i, s -> put(witnessRoles[i], s) }
                    },
                    sweep = Sweep(
                        modes = modes,
                        channels = sweepChannels,
                        frameBytes = request.intList("sweep_frame_bytes"),
                        intervalUs = request.intList("sweep_interval_us"),
                        powerOffsetQdb = sweepPower,
                    ),
                    bounds = bounds,
                    basePoint = SweepPoint(
                        mode = modes.first(),
                        channel = sweepChannels.firstOrNull()?.let { ChannelLabel(it) }
                            ?: ChannelLabel.of(baseChannel),
                        frameBytes = request.intOr("frame_bytes", 200),
                        intervalUs = intervalUs,
                        powerOffsetQdb = sweepPower.firstOrNull(),
                    ),
                    carrierSense = request.boolOr("carrier_sense", true),
                    safety = SafetyLevel.parse(request.stringOr("safety_level", "")),
                )
                val points = spec.sweep.expand(spec.basePoint).size
                val id = "exp-${System.currentTimeMillis().toString(36)}"
                val probe = LinkProbe(radios, scope)
                // Registered before it is awaited so that the dashboard can
                // see it run, and experiment_cancel can reach it.
                experiments.start(id, "link_probe", points) { sink -> probe.run(spec, sink) }
                val result = experiments.await(id)
                text(
                    json.encodeToString(ExperimentResult.serializer(), result),
                    isError = result.verification == VerificationState.FAILED,
                )
            } catch (e: CancellationException) {
                text(
                    errorReply("the experiment was cancelled", "cancelled" to true),
                    isError = true,
                )
            } catch (e: Exception) {
                text(errorReply(e.message), isError = true)
            }
        }

        register(
            server,
            name = "experiment_status",
            description = """
                What experiments have run in this session, and what is running right now.

                Shows each run's phase, how many sweep points are done, and which point is in
                flight. A finished run's full result stays retrievable here until it is evicted.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", schema("string", "One run. Omit to list all."))
                    put("include_result", schema("boolean", "Include the full result of a finished run. Default false."))
                },
            ),
        ) { request ->
            val id = request.stringOr("id", "")
            if (id.isBlank()) {
                text(
                    json.encodeToString(
                        ListSerializer(ExperimentRunner.Progress.serializer()),
                        experiments.all(),
                    ),
                )
            } else {
                val progress = experiments.progress(id)
                    ?: return@register text(errorReply("no experiment '$id'"), isError = true)
                val result = experiments.result(id).takeIf { request.boolOr("include_result", false) }
                text(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("progress", json.encodeToJsonElement(ExperimentRunner.Progress.serializer(), progress))
                            result?.let {
                                put("result", json.encodeToJsonElement(ExperimentResult.serializer(), it))
                            }
                        },
                    ),
                )
            }
        }

        register(
            server,
            name = "experiment_cancel",
            description = """
                Stop a running experiment.

                Cancellation lands at the next suspension point — between transmitted frames or
                during the settle wait — so a burst already handed to the bridge finishes airing.
                The cleanup path still runs: monitors stop and carrier sense is restored, which is
                the part that matters if the run had it disabled.

                Note that a synchronous experiment_link_probe call occupies this session until it
                returns; the reliable way to stop a run mid-flight is the dashboard's stop button,
                which does not share that queue.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("id", schema("string", "The run id, from experiment_status."))
                    put("reason", schema("string", "Recorded against the run."))
                },
                required = listOf("id"),
            ),
        ) { request ->
            val id = request.stringOr("id", "")
            val reason = request.stringOr("reason", "cancelled by the model")
            if (experiments.cancel(id, reason)) {
                text(reply("cancelled" to id, "reason" to reason))
            } else {
                text(
                    errorReply(
                        "no running experiment '$id' — it may have already finished",
                        "known" to experiments.all().joinToString(", ") { "${it.id}:${it.phase}" },
                    ),
                    isError = true,
                )
            }
        }
    }

    // ------------------------------------------------------------ CHARACTERIZE

    private fun registerCharacterize(server: Server) {
        register(
            server,
            name = "characterize_run",
            description = """
                Walk an adapter up the verification ladder and file the evidence.

                Establishes, separately and without merging them:
                  what it is            — chip, backend, and a stable identity
                  what the SOURCE claims — devourer's capability report
                  what the HARDWARE showed — frames actually received, frames actually
                                             witnessed by another radio
                  what remains UNKNOWN   — claimed capabilities nothing exercised, each
                                           with why

                RX is attempted across several channels because an empty channel is
                indistinguishable from a deaf receiver. TX needs peer_session: a transmitter
                cannot witness itself, so without a second adapter TX is recorded as
                unverifiable-here rather than failed.

                If TX delivers almost nothing, the run says so and tells you the likely cause —
                a MAC deferring rather than a link failing — but it will NOT retry with carrier
                sense off on its own. That transmits without listening and talks over anyone
                sharing the channel, so ask for it: retry_without_carrier_sense=true plus
                safety_level="experimental".

                Results accumulate per adapter. Nothing is overwritten.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("session", schema("integer", "The adapter to characterize."))
                    put(
                        "peer_session",
                        schema("integer", "A SECOND adapter to witness transmission. Without it, TX cannot be verified."),
                    )
                    put("rx_channels", schema("array", "Channels to try for RX. Default [1,6,11,36]."))
                    put("rx_dwell_ms", schema("integer", "Listen time per channel. Default 4000."))
                    put("tx_channel", schema("integer", "Channel for the TX test. Default 6."))
                    put("tx_modes", schema("array", "TX modes to try. Default [\"6M\",\"MCS0/20\"]."))
                    put("retry_without_carrier_sense", schema("boolean", "Default false. Retry a failing TX test with carrier sense DISABLED to tell MAC deferral from a bad link. Needs safety_level=\"experimental\"."))
                    put("safety_level", schema("string", "\"normal\" (default) or \"experimental\"."))
                },
                required = listOf("session"),
            ),
        ) { request ->
            val peer = request.intOr("peer_session", -1).takeIf { it >= 0 }
            val channels = request.intList("rx_channels").ifEmpty { listOf(1, 6, 11, 36) }
            val modes = request.stringList("tx_modes").ifEmpty { listOf("6M", "MCS0/20") }
            val result = Characterizer(radios, evidence, scope).run(
                session = request.intOr("session", -1),
                options = Characterizer.Options(
                    rxChannels = channels,
                    rxDwellMs = request.longOr("rx_dwell_ms", 4_000),
                    txPeerSession = peer,
                    txChannel = request.intOr("tx_channel", 6),
                    txModes = modes,
                    retryWithoutCarrierSense = request.boolOr("retry_without_carrier_sense", false),
                    safety = SafetyLevel.parse(request.stringOr("safety_level", "")),
                ),
            )
            text(json.encodeToString(Characterization.serializer(), result))
        }

        register(
            server,
            name = "characterize_report",
            description = """
                Read the stored characterization for one adapter, or list every adapter on
                file.

                This is the accumulated hardware evidence database. Each record keeps the
                source's claims and the hardware's demonstrations apart, so a capability the
                vendor lists but nothing ever exercised stays visibly unverified rather than
                quietly becoming a fact.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("key", schema("string", "Record key from a previous run, or a session id via 'session'."))
                    put("session", schema("integer", "An open adapter; reports its stored record."))
                },
            ),
        ) { request ->
            val key = request.stringOr("key", "")
            val session = request.intOr("session", -1)
            when {
                key.isNotBlank() -> {
                    val rec = evidence.load(key)
                        ?: return@register text(
                            errorReply("no record with key $key"), isError = true,
                        )
                    text(json.encodeToString(Characterization.serializer(), rec))
                }
                session >= 0 -> {
                    val identity = AdapterIdentity.of(radios.describe(session))
                    val rec = evidence.load(identity)
                        ?: return@register text(
                            errorReply(
                            "no stored characterization for ${identity.describe()}; " +
                                "run characterize_run first",
                            "identity_key" to identity.key,
                        ),
                        isError = true,
                        )
                    text(json.encodeToString(Characterization.serializer(), rec))
                }
                else -> {
                    val all = evidence.list()
                    text(
                        json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(CharacterizationSummary.serializer()),
                            all.map {
                                CharacterizationSummary(
                                    key = it.identity.key,
                                    identity = it.identity.describe(),
                                    chip = it.chip,
                                    backend = it.backend,
                                    state = it.state,
                                    runs = it.runs.size,
                                    unverified = it.unverified.size,
                                    summary = it.summary(),
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    // --------------------------------------------------------------- SCRATCHPAD

    private fun registerScratchpad(server: Server) {
        register(
            server,
            name = "scratchpad_capabilities",
            description = """
                List the primitives a scratchpad program can be built from, and the shape of a
                program.

                Read this before writing one. A scratchpad is NOT code — it is a declarative
                program of sources sampled on a schedule, values computed from them, and a view
                over the result. It cannot open a file, run a process or reach a host that is not
                in its allowlist, because no step exists that does those things. Anything needing
                real control flow belongs in the experiment engine instead.
            """.trimIndent(),
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            text(json.encodeToString(ScratchpadDoc.serializer(), ScratchpadDoc.build()))
        }

        register(
            server,
            name = "scratchpad_run",
            description = """
                Validate a scratchpad program, grant it exactly the capabilities it declared, run
                it, and serve a live view.

                The grant is per-run and explicit: radio sessions, capture ids and HTTP hosts are
                named here, not in the program. A program that names a capture it was not granted
                is refused, so a generated program cannot widen its own reach.

                Returns a loopback URL for the live view. Nothing is exposed off this machine.
                Use scratchpad_result for the numbers, scratchpad_promote to keep one worth reusing.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("program", schema("object", "The program. See scratchpad_capabilities for its shape."))
                    put("radio_sessions", schema("array", "Radio session ids this program may read."))
                    put("capture_ids", schema("array", "Capture ids this program may read."))
                    put("http_hosts", schema("array", "Hosts or host:port this program may GET. Exact match."))
                    put("grant_capabilities", schema("array", "Capabilities the CALLER allows. Defaults to all non-privileged ones. A program declaring anything outside this set is refused — a program cannot grant itself."))
                    put("max_runtime_ms", schema("integer", "Ceiling on the run. Default 120000."))
                    put("ui", schema("boolean", "Serve the live view. Default true."))
                },
                required = listOf("program"),
            ),
        ) { request ->
            val programJson = request.params.arguments?.get("program")
                ?: return@register text(errorReply("program is required"), isError = true)
            val program = try {
                scratchpads.json.decodeFromJsonElement(ScratchpadProgram.serializer(), programJson)
            } catch (e: Exception) {
                return@register text(
                    errorReply(
                        "could not parse the program: ${e.message}",
                        "hint" to "call scratchpad_capabilities for the exact shape",
                    ),
                    isError = true,
                )
            }
            // The grant is what the CALLER allows, intersected with what the
            // program asked for — never the program's own list. Building it from
            // `program.capabilities` made every downstream check compare the
            // program against itself, so a program granted itself anything it
            // named. `grant_capabilities` defaults to the non-privileged set so
            // ordinary use needs no extra argument, while anything privileged
            // has to be named by the caller.
            val requested = program.capabilities.toSet()
            val allowed = request.stringList("grant_capabilities").toSet()
                .ifEmpty { Capability.entries.filterNot { it.privileged }.map { it.id }.toSet() }
            val grant = CapabilityGrant(
                capabilities = requested intersect allowed,
                radioSessions = request.intList("radio_sessions").toSet(),
                captureIds = request.stringList("capture_ids").toSet(),
                httpHosts = request.stringList("http_hosts").toSet(),
                maxRuntimeMs = request.longOr("max_runtime_ms", 120_000).coerceIn(100, 3_600_000),
            )
            val refused = requested - allowed
            if (refused.isNotEmpty()) {
                return@register text(
                    errorReply(
                        "the program declares capabilities the caller did not grant: " +
                            refused.sorted().joinToString(", "),
                        "hint" to "pass grant_capabilities to allow them explicitly",
                    ),
                    isError = true,
                )
            }
            val inspection = scratchpads.inspect(program)
            if (!inspection.valid) {
                return@register text(
                    json.encodeToString(ScratchpadService.InspectionResult.serializer(), inspection),
                    isError = true,
                )
            }
            val handle = try {
                scratchpads.start(program, grant, withUi = request.boolOr("ui", true))
            } catch (e: Exception) {
                return@register text(errorReply(e.message), isError = true)
            }
            text(
                json.encodeToString(ScratchpadStarted.serializer(), ScratchpadStarted(handle, inspection)),
            )
        }

        register(
            server,
            name = "scratchpad_inspect",
            description = """
                Validate a program and report exactly what it would touch, WITHOUT running it.

                The review step. It enumerates every external thing the program reaches — each
                capture, radio and URL — and flags privileged capabilities, so a generated program
                can be read before it is trusted.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("program", schema("object", "The program to inspect.")) },
                required = listOf("program"),
            ),
        ) { request ->
            val programJson = request.params.arguments?.get("program")
                ?: return@register text(errorReply("program is required"), isError = true)
            val program = try {
                scratchpads.json.decodeFromJsonElement(ScratchpadProgram.serializer(), programJson)
            } catch (e: Exception) {
                return@register text(errorReply("could not parse the program: ${e.message}", "hint" to "call scratchpad_capabilities for the exact shape"), isError = true)
            }
            text(json.encodeToString(ScratchpadService.InspectionResult.serializer(), scratchpads.inspect(program)))
        }

        register(
            server,
            name = "scratchpad_result",
            description = """
                Current values of a running or finished scratchpad: per series, the last value and
                its min, mean, max and sample count, plus the run log.

                Pass window_ms for a trailing window rather than the whole run.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("run_id", schema("string", "From scratchpad_run."))
                    put("window_ms", schema("integer", "Trailing window. Omit for the whole run."))
                },
                required = listOf("run_id"),
            ),
        ) { request ->
            val id = request.stringOr("run_id", "")
            val run = scratchpads.get(id)
                ?: return@register text(errorReply("no run $id"), isError = true)
            val window = request.longOr("window_ms", 0).takeIf { it > 0 }
            text(
                json.encodeToString(
                    ScratchpadResult.serializer(),
                    ScratchpadResult(
                        runId = id,
                        name = run.program.name,
                        running = run.state.finishedAtEpochMs == 0L,
                        elapsedMs = (run.state.finishedAtEpochMs.takeIf { it > 0 }
                            ?: System.currentTimeMillis()) - run.state.startedAtEpochMs,
                        uiUrl = run.ui?.url,
                        error = run.error,
                        series = scratchpads.results(id, window).mapValues {
                            SeriesStats(it.value.count, it.value.min, it.value.mean, it.value.max, it.value.last)
                        },
                        log = run.state.logs().takeLast(40),
                    ),
                ),
            )
        }

        register(
            server,
            name = "scratchpad_stop",
            description = "Stop a running scratchpad and close its live view.",
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("run_id", schema("string", "From scratchpad_run.")) },
                required = listOf("run_id"),
            ),
        ) { request ->
            val id = request.stringOr("run_id", "")
            text(reply("run_id" to id, "stopped" to scratchpads.stop(id)))
        }

        register(
            server,
            name = "scratchpad_promote",
            description = """
                Save a scratchpad as a reusable tool.

                What is stored is the program itself, so it re-runs identically rather than
                approximately. The capability GRANT is deliberately not stored: permissions are
                given per run against the radios and captures that exist then, and a saved grant
                would be a standing permission nobody reviewed.
            """.trimIndent(),
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("run_id", schema("string", "The run to promote."))
                    put("save_as", schema("string", "Optional name. Defaults to the program's name."))
                },
                required = listOf("run_id"),
            ),
        ) { request ->
            val path = try {
                scratchpads.promote(request.stringOr("run_id", ""), request.stringOr("save_as", "").ifBlank { null })
            } catch (e: Exception) {
                return@register text(errorReply(e.message), isError = true)
            }
            text(reply("saved" to path.toString(), "note" to "re-run it with scratchpad_run; capabilities are granted again per run"))
        }

        register(
            server,
            name = "scratchpad_list",
            description = "List running scratchpads and previously promoted ones.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) { _ ->
            text(
                json.encodeToString(
                    ScratchpadListing.serializer(),
                    ScratchpadListing(
                        running = scratchpads.list().map {
                            RunningPad(
                                it.id, it.program.name,
                                it.state.finishedAtEpochMs == 0L,
                                it.ui?.url, it.error,
                            )
                        },
                        saved = scratchpads.saved().map {
                            SavedPad(it.file, it.name, it.purpose, it.capabilities)
                        },
                    ),
                ),
            )
        }
    }

    /**
     * Registers one tool, and puts its call on the activity feed.
     *
     * Wrapping at registration rather than instrumenting each handler is what
     * makes the feed trustworthy: a tool added later is recorded whether or
     * not its author remembered to, and there is no second place for the
     * error path to diverge from the success path.
     *
     * A handler that throws is recorded as a failure and then rethrown — the
     * feed must never be the reason an error is swallowed.
     */
    private fun register(
        server: Server,
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (CallToolRequest) -> CallToolResult,
    ) {
        server.addTool(name, description, inputSchema) { request ->
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            try {
                val result = handler(request)
                activity.record(
                    tool = name,
                    arguments = summarize(request),
                    durationMs = elapsed(),
                    ok = result.isError != true,
                    error = (result.content.firstOrNull() as? TextContent)
                        ?.text?.takeIf { result.isError == true }?.let(::errorText),
                )
                result
            } catch (e: Throwable) {
                activity.record(
                    tool = name,
                    arguments = summarize(request),
                    durationMs = elapsed(),
                    ok = false,
                    error = "${e::class.simpleName}: ${e.message}",
                )
                throw e
            }
        }
    }

    /**
     * The human-readable part of a failed reply.
     *
     * Error bodies are pretty-printed JSON, so their first line is `{` — the
     * feed showed that, which told a reader nothing at all. Pull the `error`
     * field out when it is there and fall back to the first line that
     * carries something.
     */
    private fun errorText(body: String): String =
        runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
            ?: body.lineSequence().map { it.trim() }
                .firstOrNull { it.length > 1 && it != "{" && it != "}" }
            ?: body.take(200)

    /**
     * Arguments as one readable line.
     *
     * Values are clipped hard. A `tx_send` carries a hex frame and a
     * scratchpad carries a whole program; a feed that held those verbatim
     * would be unreadable and unbounded.
     */
    private fun summarize(request: CallToolRequest): String =
        request.params.arguments.orEmpty().entries.joinToString(" ") { (k, v) ->
            val text = (v as? JsonPrimitive)?.content ?: v.toString()
            k + "=" + if (text.length > MAX_ARG_VALUE) text.take(MAX_ARG_VALUE) + "\u2026" else text
        }

    // ------------------------------------------------------------------ helpers

    private fun text(body: String, isError: Boolean = false) =
        CallToolResult(content = listOf(TextContent(body)), isError = isError.takeIf { it })

    /**
     * A JSON reply built through the serializer, never by string concatenation.
     *
     * Exception messages routinely contain quotes, braces and newlines — a
     * refused HTTP host quotes the host, a parse failure quotes the offending
     * token — and splicing one into a hand-written JSON literal produces output
     * the caller cannot parse, at exactly the moment it most needs to read the
     * error.
     */
    private fun reply(vararg fields: Pair<String, Any?>): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                fields.forEach { (k, v) ->
                    when (v) {
                        null -> {}
                        is Boolean -> put(k, JsonPrimitive(v))
                        is Int -> put(k, JsonPrimitive(v))
                        is Long -> put(k, JsonPrimitive(v))
                        is Double -> put(k, JsonPrimitive(v))
                        else -> put(k, JsonPrimitive(v.toString()))
                    }
                }
            },
        )

    private fun errorReply(message: String?, vararg extra: Pair<String, Any?>): String =
        reply("error" to (message ?: "unknown error"), *extra)

    /**
     * The refusal for a capture-id argument that names nothing.
     *
     * Says which argument it wanted and what is open, because the mistake
     * this is usually reporting is a session id passed where a capture id
     * belongs — the two tools sit next to each other and take different
     * handles. "no capture" alone leaves the caller to guess which.
     */
    private fun noSuchCapture(id: String): String = errorReply(
        if (id.isBlank()) {
            "capture_id is required (a capture id like \"cap-1\" from monitor_start, " +
                "NOT a radio session id)"
        } else {
            "no capture '$id' — if that was a session id, this tool takes the " +
                "capture_id monitor_start returned"
        },
        "open_captures" to captures.all().joinToString(", ") { it.id }.ifEmpty { "none" },
    )

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

/**
 * A present argument as an Int, or null when it is absent.
 *
 * Throws when the value is present but not an integer. The lenient [intOr] must
 * not be used for a field whose PRESENCE means "write": a quoted `"4"` or a
 * boolean would fall back to a default, the write would proceed with a value
 * the caller never named, and the reply would read as success. That is the
 * silent-success failure this project keeps having to remove, and the bridge's
 * own wrong-type guards cannot see it because the coercion happens here.
 */
internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalInt(key: String): Int? {
    val element = params.arguments?.get(key) ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.intOrNull == null) {
        throw IllegalArgumentException("$key must be an integer")
    }
    return primitive.intOrNull
}

/**
 * A present argument as a Boolean, or null when it is absent or JSON null.
 *
 * Throws on a wrong-typed value, for the same reason [optionalInt] does: a
 * field whose presence means "do the write" must not silently fall back to
 * `false` and turn the write into a read that reports success.
 */
internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.optionalBoolean(key: String): Boolean? {
    val element = params.arguments?.get(key) ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) {
        throw IllegalArgumentException("$key must be a boolean")
    }
    return primitive.booleanOrNull ?: throw IllegalArgumentException("$key must be a boolean")
}

/**
 * A present array-of-integers argument, or null when absent.
 *
 * Strict, unlike [intList]: a non-array, or ANY non-integer element, throws
 * instead of quietly becoming an empty list. For the power axis that matters —
 * a silently-dropped `sweep_power_qdb` would run the experiment with no power
 * axis and still report TX_VERIFIED, which is worse than an error.
 */
internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.strictIntList(key: String): List<Int>? {
    val element = params.arguments?.get(key) ?: return null
    if (element is JsonNull) return null
    val array = element as? kotlinx.serialization.json.JsonArray
        ?: throw IllegalArgumentException("$key must be an array of integers")
    return array.map {
        val p = it as? JsonPrimitive
        if (p == null || p.isString || p.intOrNull == null) {
            throw IllegalArgumentException("$key must be an array of integers")
        }
        p.intOrNull!!
    }
}

internal fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.intList(key: String): List<Int> =
    runCatching {
        (params.arguments?.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.int }
    }.getOrNull() ?: emptyList()

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

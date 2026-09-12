package org.openipc.devourer.characterize

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openipc.devourer.capture.CaptureStore
import org.openipc.devourer.capture.analyseChainBalance
import org.openipc.devourer.experiment.ExperimentBounds
import org.openipc.devourer.experiment.LinkProbe
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.ChannelWidth
import org.openipc.devourer.radio.RadioManager
import org.openipc.devourer.radio.SafetyLevel
import org.openipc.devourer.radio.SafetyLevelException
import org.openipc.devourer.radio.VerificationClaim
import org.openipc.devourer.radio.VerificationState

/**
 * Walks one adapter up the verification ladder and files the evidence.
 *
 * Each rung is attempted only if the one below it succeeded, and a rung that
 * cannot be attempted is recorded as skipped with a reason rather than left
 * absent. "We never tried" and "it failed" are different facts, and a database
 * that cannot tell them apart will be re-derived from scratch by whoever reads
 * it next.
 */
public class Characterizer(
    private val radios: RadioManager,
    private val store: EvidenceStore,
    private val scope: CoroutineScope,
) {
    public data class Options(
        /**
         * Channels tried for RX, in order.
         *
         * More than one because an empty channel is indistinguishable from a
         * deaf receiver: on this bench ch6 carries ~1 frame in 4 s while ch1
         * carries thousands. Declaring an adapter broken because it was pointed
         * at a quiet channel is the mistake this list exists to prevent.
         */
        val rxChannels: List<Int> = listOf(1, 6, 11, 36),
        val rxDwellMs: Long = 4_000,
        /** Minimum frames to call RX demonstrated rather than ambiguous. */
        val rxMinFrames: Int = 20,
        /** A second adapter to witness transmission. Without one, TX is unverifiable. */
        val txPeerSession: Int? = null,
        val txChannel: Int = 6,
        val txModes: List<String> = listOf("6M", "MCS0/20"),
        /**
         * Retry TX with carrier sense off when it delivers almost nothing.
         *
         * Defaults to FALSE, and that default is load-bearing. Disabling
         * carrier sense makes the radio transmit without listening; it is the
         * right diagnostic when a MAC is deferring rather than a link failing,
         * but it talks over anyone sharing the channel. It was briefly on by
         * default here, with no way for an MCP caller to decline — a
         * characterization run on a weak link would silently jam the air and
         * report it as evidence-gathering. A caller now asks for it by name,
         * and must also pass [SafetyLevel.EXPERIMENTAL].
         */
        val retryWithoutCarrierSense: Boolean = false,
        /** The level the caller explicitly asked for. */
        val safety: SafetyLevel = SafetyLevel.NORMAL,
    )

    public suspend fun run(session: Int, options: Options = Options()): Characterization {
        val started = System.currentTimeMillis()
        val runId = "char-${started.toString(36)}"
        val claims = mutableListOf<VerificationClaim>()
        val notes = mutableListOf<String>()
        val skipped = mutableMapOf<String, String>()
        val conditions = mutableMapOf<String, String>()

        var radio = radios.describe(session)
        val caps = radio.capabilities

        // --- DETECTED: it enumerated and a backend claimed it ------------------
        claims += claim(
            "detection",
            VerificationState.DETECTED,
            "enumerated on USB as ${radio.device.usbId} at ${radio.device.locator}",
            mapOf(
                "usb_id" to radio.device.usbId,
                "locator" to radio.device.locator,
                "identification" to radio.device.identification.name,
            ),
        )

        if (!caps.supported) {
            skipped["everything below detection"] =
                "the backend reports no capability information for this chip"
            return file(radio, runId, started, conditions, claims, notes, skipped, emptyList())
        }

        // --- INITIALIZED: bring-up, which is also what reveals a MediaTek MAC --
        val bringUpChannel = ChannelSpec(options.rxChannels.first(), ChannelWidth.W20)
        radios.retune(session, bringUpChannel)
        radio = radios.describe(session) // re-read: the MAC may only exist now
        claims += claim(
            "initialization",
            VerificationState.INITIALIZED,
            "chip powered up and tuned to $bringUpChannel",
            mapOf("channel" to bringUpChannel.toString()),
        )

        val identity = AdapterIdentity.of(radio)
        conditions["identity_basis"] = identity.confidence.name
        if (identity.confidence != IdentityConfidence.PERMANENT_MAC) {
            notes += "Identity rests on ${identity.confidence.explanation}. This record " +
                "cannot be reattached to the adapter if it moves ports."
        }

        // --- RX_VERIFIED: real frames, on a channel that actually has traffic --
        val rx = demonstrateRx(session, options, notes)
        if (rx != null) {
            claims += rx.claim
            conditions["rx_channel"] = rx.channel.toString()
            conditions["rx_dwell_ms"] = options.rxDwellMs.toString()
            if (rx.chainNote != null) notes += rx.chainNote
        } else {
            skipped["rx_verification"] =
                "no channel in ${options.rxChannels} carried at least ${options.rxMinFrames} " +
                    "frames in ${options.rxDwellMs}ms. The adapter may be fine and the air quiet."
        }

        // --- TX_VERIFIED: requires an independent witness ----------------------
        val peer = options.txPeerSession
        if (peer == null) {
            skipped["tx_verification"] =
                "no peer radio was given. A transmitter cannot witness itself, so TX " +
                    "cannot be verified with one adapter — this is a bench limitation, " +
                    "not a fault of the hardware."
        } else if (peer == session) {
            skipped["tx_verification"] = "the peer session is this same radio"
        } else {
            val tx = demonstrateTx(session, peer, options, notes, conditions)
            claims += tx
        }

        return file(radio, runId, started, conditions, claims, notes, skipped,
            unverifiedFrom(caps, claims, skipped))
    }

    private data class RxOutcome(
        val claim: VerificationClaim,
        val channel: ChannelSpec,
        val chainNote: String?,
    )

    private suspend fun demonstrateRx(
        session: Int,
        options: Options,
        notes: MutableList<String>,
    ): RxOutcome? {
        for (ch in options.rxChannels) {
            val spec = ChannelSpec(ch, ChannelWidth.W20)
            val supported = runCatching {
                radios.requireChannelSupported(radios.describe(session), spec)
            }.getOrElse {
                notes += "channel $ch skipped: ${it.message}"
                continue
            }
            supported?.let { notes += "channel $ch: $it" }

            val store = CaptureStore("char-rx-$ch", capacity = 50_000)
            radios.startMonitor(session, spec)
            val job: Job = scope.launch { radios.frames(session).collect { store.add(it) } }
            delay(options.rxDwellMs)
            job.cancel()
            val stats = runCatching { radios.stats(session) }.getOrNull()
            radios.stopMonitor(session)

            if (store.size < options.rxMinFrames) {
                notes += "channel $ch: only ${store.size} frames in ${options.rxDwellMs}ms"
                continue
            }
            val summary = store.summarizeAll()
            val balance = analyseChainBalance(summary)
            return RxOutcome(
                claim = claim(
                    "rx",
                    VerificationState.RX_VERIFIED,
                    "${summary.frames} frames received and parsed on $spec " +
                        "(${"%.0f".format(summary.framesPerSecond)}/s)",
                    buildMap {
                        put("channel", spec.toString())
                        put("frames", summary.frames.toString())
                        put("frames_per_second", "%.1f".format(summary.framesPerSecond))
                        put("crc_errors", summary.crcErrors.toString())
                        put("frame_kinds", summary.byKind.keys.take(6).joinToString(","))
                        put("chains_reporting", summary.rssi.size.toString())
                        stats?.let { put("bridge_dropped", it.dropped.toString()) }
                        summary.rssi.forEach { c ->
                            put("rssi_${c.chain}_mean", "%.1f".format(c.mean))
                        }
                    },
                ),
                channel = spec,
                chainNote = balance.verdict.takeIf { balance.chains > 0 },
            )
        }
        return null
    }

    private suspend fun demonstrateTx(
        session: Int,
        peer: Int,
        options: Options,
        notes: MutableList<String>,
        conditions: MutableMap<String, String>,
    ): VerificationClaim {
        val channel = ChannelSpec(options.txChannel, ChannelWidth.W20)
        conditions["tx_channel"] = channel.toString()
        conditions["tx_peer_session"] = peer.toString()

        val probe = LinkProbe(radios, scope)
        var result = probe.run(
            txSession = session,
            rxSession = peer,
            channel = channel,
            modes = options.txModes,
            bounds = ExperimentBounds(framesPerPoint = 100, intervalUs = 2_000),
        )
        var carrierSense = true

        // A near-total loss with carrier sense on is the signature of a MAC
        // deferring rather than a bad link — measured on this bench, an
        // RTL8812AU went from 4-13% to 88-100% with it off. Retrying separates
        // the two instead of filing "TX failed" for a radio that works.
        val best = result.points.maxOfOrNull { it.deliveryRatio } ?: 0.0
        if (best < 0.5 && !options.retryWithoutCarrierSense) {
            notes += "TX delivered only ${"%.0f".format(best * 100)}% with carrier sense on. " +
                "That pattern — every frame submitted, none failed, almost none received — is " +
                "usually a MAC deferring rather than a link that cannot carry. Re-run with " +
                "retry_without_carrier_sense=true and safety_level=\"experimental\" to tell " +
                "the two apart. Not done automatically: it transmits without listening."
        }
        if (best < 0.5 && options.retryWithoutCarrierSense) {
            SafetyLevelException.require(
                "retrying with carrier sense disabled",
                SafetyLevel.EXPERIMENTAL,
                options.safety,
            )
            notes += "TX delivered only ${"%.0f".format(best * 100)}% with carrier sense on; " +
                "retrying with it off to separate MAC deferral from a poor link."
            result = probe.run(
                txSession = session,
                rxSession = peer,
                channel = channel,
                modes = options.txModes,
                bounds = ExperimentBounds(framesPerPoint = 100, intervalUs = 2_000),
                carrierSense = false,
                safety = options.safety,
            )
            carrierSense = false
            conditions["tx_carrier_sense"] = "disabled"
            val retryBest = result.points.maxOfOrNull { it.deliveryRatio } ?: 0.0
            if (retryBest >= 0.5) {
                notes += "With carrier sense off delivery reached " +
                    "${"%.0f".format(retryBest * 100)}%. This adapter transmits; its MAC " +
                    "was declining to, on a channel it judged busy."
            }
        } else {
            conditions["tx_carrier_sense"] = "enabled"
        }

        val delivered = result.points.maxOfOrNull { it.deliveryRatio } ?: 0.0
        val heard = result.points.any { it.framesReceived > 0 }
        return claim(
            "tx",
            if (heard) VerificationState.TX_VERIFIED else VerificationState.FAILED,
            if (heard) {
                "an independent receiver saw frames from this adapter on $channel; " +
                    "best delivery ${"%.0f".format(delivered * 100)}%"
            } else {
                "no independent receiver heard anything from this adapter on $channel"
            },
            buildMap {
                put("experiment_id", result.id)
                put("channel", channel.toString())
                put("carrier_sense", carrierSense.toString())
                put("witness", result.roles["RX_PEER"] ?: peer.toString())
                result.points.forEach {
                    put("delivery_${it.point}", "%.2f".format(it.deliveryRatio))
                }
            },
        )
    }

    /**
     * Claimed capabilities this run did not exercise.
     *
     * Derived from the capability report rather than a hand-kept list, so a
     * capability devourer starts reporting shows up here automatically instead
     * of being quietly absent.
     */
    private fun unverifiedFrom(
        caps: org.openipc.devourer.radio.RadioCapabilities,
        claims: List<VerificationClaim>,
        skipped: Map<String, String>,
    ): List<UnverifiedCapability> {
        val out = mutableListOf<UnverifiedCapability>()
        val txVerified = claims.any { it.state == VerificationState.TX_VERIFIED }

        caps.widths.filter { it != 20 }.forEach {
            out += UnverifiedCapability(
                capability = "bandwidth_${it}mhz",
                claim = "the capability report lists ${it}MHz",
                reason = "this run only exercised 20MHz",
            )
        }
        if (caps.txPower.supported && !caps.txPower.stepMeasured) {
            out += UnverifiedCapability(
                capability = "tx_power_step",
                claim = "an index/offset power knob with step ${caps.txPower.stepQdb} qdB",
                reason = "devourer reports the dB-per-step slope as NOT on-air measured for " +
                    "this family, and nothing here measured it either. Absolute power " +
                    "claims built on it are extrapolation.",
            )
        }
        caps.features.filterValues { it }.keys
            .filterNot { it == "per_chain_rssi" }
            .forEach { feature ->
                out += UnverifiedCapability(
                    capability = feature,
                    claim = "reported as supported by the adapter",
                    reason = "no experiment in this run exercises it",
                )
            }
        if (!txVerified) {
            out += UnverifiedCapability(
                capability = "transmit",
                claim = "the capability report lists ${caps.tx.spatialStreams} TX streams",
                reason = skipped["tx_verification"] ?: "TX was not demonstrated in this run",
                blocked = skipped.containsKey("tx_verification"),
            )
        }
        return out
    }

    private fun claim(
        subject: String,
        state: VerificationState,
        detail: String,
        evidence: Map<String, String>,
    ) = VerificationClaim(
        subject = subject,
        state = state,
        detail = detail,
        observedAtEpochMs = System.currentTimeMillis(),
        evidence = evidence,
    )

    private fun file(
        radio: RadioManager.OpenRadio,
        runId: String,
        started: Long,
        conditions: MutableMap<String, String>,
        claims: List<VerificationClaim>,
        notes: List<String>,
        skipped: Map<String, String>,
        unverified: List<UnverifiedCapability>,
    ): Characterization {
        conditions["chip"] = radio.capabilities.chip
        conditions["backend"] = radio.capabilities.generation
        return store.record(
            identity = AdapterIdentity.of(radio),
            chip = radio.capabilities.chip,
            backend = radio.capabilities.generation,
            marketingNames = radio.capabilities.marketingNames,
            sourceClaims = radio.capabilities,
            run = CharacterizationRun(
                id = runId,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                conditions = conditions,
                claims = claims,
                notes = notes,
                skipped = skipped,
            ),
            unverified = unverified,
        )
    }
}

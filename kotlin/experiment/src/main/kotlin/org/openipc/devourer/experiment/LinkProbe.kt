package org.openipc.devourer.experiment

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.radio.RadioManager
import org.openipc.devourer.radio.VerificationState

/**
 * Transmit a bounded burst on one radio, count what an independent radio hears.
 *
 * This is the experiment that makes [VerificationState.TX_VERIFIED] reachable.
 * A transmitting radio reporting success proves only that its own TX path
 * accepted the frames; the PA could be dead, the antenna disconnected, the
 * channel misprogrammed. A second adapter hearing tagged frames on the air is
 * the first evidence that anything was actually transmitted — which is why the
 * receiver here is a different physical device, never the transmitter itself.
 *
 * It is also the primitive the rate sweeps are built from: "the highest
 * reliable MCS" is this, run once per rate.
 */
public class LinkProbe(
    private val radios: RadioManager,
    private val scope: CoroutineScope,
) {
    /**
     * @param txSession the transmitter.
     * @param rxSession the independent receiver. Must not be [txSession] — a
     *  radio cannot witness itself, and allowing it would produce a result that
     *  looks like evidence and is not.
     * @param modes TX mode specs to sweep, e.g. `["6M", "MCS0/20", "MCS5/20"]`.
     */
    public suspend fun run(
        txSession: Int,
        rxSession: Int,
        channel: ChannelSpec,
        modes: List<String> = listOf("6M"),
        bounds: ExperimentBounds = ExperimentBounds(),
        frameBytes: Int = 200,
        carrierSense: Boolean = true,
    ): ExperimentResult {
        if (txSession == rxSession) {
            throw ExperimentException(
                "the transmitter cannot be its own witness: tx and rx must be " +
                    "different adapters, or the result proves nothing about the air",
            )
        }
        if (modes.isEmpty()) throw ExperimentException("no TX modes given")

        val runId = Random.nextInt(1, 0xFFFFFF)
        val started = System.currentTimeMillis()
        val id = "exp-${started.toString(36)}-${runId.toString(16)}"

        val tx = radios.describe(txSession)
        val rx = radios.describe(rxSession)
        val caveats = mutableListOf<String>()

        radios.requireChannelSupported(tx, channel)?.let { caveats += "transmitter: $it" }
        radios.requireChannelSupported(rx, channel)?.let { caveats += "receiver: $it" }
        if (!tx.capabilities.tx.supported) {
            throw ExperimentException("${tx.label} reports no TX capability")
        }

        // Both ends on the same channel. The receiver monitors; the transmitter
        // only needs bring-up, which retune performs.
        radios.retune(txSession, channel)
        radios.startMonitor(rxSession, channel)

        if (!carrierSense) {
            radios.setCarrierSense(txSession, enabled = false)
            caveats += "Carrier sense was DISABLED on the transmitter for this run. The " +
                "delivery ratio therefore measures the radio link alone, with the MAC's " +
                "own decision to defer removed. It is not comparable to a run with " +
                "carrier sense on, and it is not what this adapter would achieve on a " +
                "shared channel."
        }

        val frame = ProbeFrame.build(runId, frameBytes)
        val frameHex = ProbeFrame.toHex(frame)
        val points = mutableListOf<PointResult>()
        var truncated = false

        // One collector for the whole run, started before any transmission so
        // nothing is missed between points.
        val seen = Collector(runId)
        val job: Job = scope.launch {
            radios.frames(rxSession).collect { seen.offer(it) }
        }
        try {
            for (mode in modes) {
                if (System.currentTimeMillis() - started > bounds.maxDurationMs) {
                    truncated = true
                    caveats += "stopped after ${bounds.maxDurationMs}ms: " +
                        "${modes.size - points.size} of ${modes.size} modes not run"
                    break
                }
                points += runPoint(txSession, mode, frameHex, bounds, seen)
            }
        } finally {
            job.cancel()
            runCatching { radios.stopMonitor(rxSession) }
            // Restore carrier sense even if the run threw. Leaving a radio
            // transmitting without listening is not a state to walk away from.
            if (!carrierSense) runCatching { radios.setCarrierSense(txSession, enabled = true) }
        }

        return conclude(
            id = id,
            started = started,
            txLabel = tx.label,
            rxLabel = rx.label,
            channel = channel,
            bounds = bounds,
            points = points,
            caveats = caveats,
            truncated = truncated,
            carrierSense = carrierSense,
        )
    }

    private suspend fun runPoint(
        txSession: Int,
        mode: String,
        frameHex: String,
        bounds: ExperimentBounds,
        seen: Collector,
    ): PointResult {
        seen.reset()
        val txResult: JsonObject = radios.sendProbe(
            session = txSession,
            frameHex = frameHex,
            mode = mode,
            count = bounds.framesPerPoint,
            intervalUs = bounds.intervalUs,
            sequenceOffset = ProbeFrame.SEQUENCE_OFFSET,
        )
        val accepted = txResult["sent"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val elapsedNs = txResult["elapsed_ns"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val lateFrames = txResult["late_frames"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val maxLateUs = txResult["max_late_us"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        // Frames in flight when the last one is submitted still have to arrive,
        // be parsed and cross the frame socket. Declaring loss without waiting
        // would systematically under-count delivery at every rate.
        delay(bounds.settleMs)

        val s = seen.snapshot()
        return PointResult(
            point = mode,
            framesSent = bounds.framesPerPoint,
            framesReceived = s.distinct,
            deliveryRatio = if (bounds.framesPerPoint > 0) {
                s.distinct.toDouble() / bounds.framesPerPoint
            } else {
                0.0
            },
            duplicates = s.duplicates,
            outOfOrder = s.outOfOrder,
            longestGap = s.longestGap,
            rssiMean = s.rssiMean,
            rssiMin = s.rssiMin,
            rssiMax = s.rssiMax,
            snrMean = s.snrMean,
            crcErrors = s.crcErrors,
            txAccepted = accepted,
            txElapsedMs = elapsedNs / 1e6,
            txLateFrames = lateFrames,
            txMaxLateUs = maxLateUs,
            note = if (accepted < bounds.framesPerPoint) {
                "TX path accepted only $accepted of ${bounds.framesPerPoint}"
            } else {
                null
            },
        )
    }

    private fun conclude(
        id: String,
        started: Long,
        txLabel: String,
        rxLabel: String,
        channel: ChannelSpec,
        bounds: ExperimentBounds,
        points: List<PointResult>,
        caveats: MutableList<String>,
        truncated: Boolean,
        carrierSense: Boolean,
    ): ExperimentResult {
        val best = points.maxByOrNull { it.deliveryRatio }
        val anyHeard = points.any { it.framesReceived > 0 }

        // The verification state is derived from the evidence, never asserted.
        // Nothing heard means FAILED — a zero delivery ratio reported as a
        // successful experiment is exactly the kind of quiet lie this project
        // is built to avoid.
        val verification = when {
            !anyHeard -> VerificationState.FAILED
            else -> VerificationState.TX_VERIFIED
        }

        val conclusion = when {
            !anyHeard ->
                "$txLabel transmitted but $rxLabel heard nothing on $channel. The TX " +
                    "path accepted the frames, so this is NOT TX_VERIFIED: check antennas, " +
                    "that both radios are on the same channel and width, and that they are " +
                    "in range of each other."
            best != null ->
                "TX_VERIFIED: $rxLabel independently received frames from $txLabel on " +
                    "$channel. Best delivery ${"%.1f".format(best.deliveryRatio * 100)}% " +
                    "at ${best.point}" +
                    (best.rssiMean?.let { ", mean RSSI ${"%.1f".format(it)}" } ?: "") + "."
            else -> "no measurement points ran"
        }

        val poor = points.isNotEmpty() &&
            points.count { it.deliveryRatio < 0.5 } * 2 > points.size
        if (carrierSense && (poor || !anyHeard)) {
            caveats += "Delivery was poor with carrier sense ON. Before blaming the link, " +
                "re-run with carrier_sense=false: a MAC whose EDCCA threshold is too " +
                "sensitive defers nearly every transmission while still reporting every " +
                "frame as submitted and none as failed. Measured on this bench, an " +
                "RTL8812AU went from 4-13% to 88-100% delivery on an otherwise quiet " +
                "channel with carrier sense off."
        }
        if (anyHeard) {
            caveats += "Delivery ratio here is one-way and broadcast: frames are never " +
                "ACKed, so nothing was retried. It is not a throughput figure and not " +
                "comparable to a rate an associated link would sustain."
            val worstLate = points.maxOfOrNull { it.txLateFrames } ?: 0
            if (worstLate > 0) {
                val maxLate = points.maxOfOrNull { it.txMaxLateUs } ?: 0
                caveats += "The transmitter could not hold the requested frame spacing: " +
                    "up to $worstLate frames per point started late, worst miss " +
                    "${maxLate / 1000}ms. Frames bunched to catch up, so treat this run as " +
                    "a delivery measurement and not a timing one."
            }
            if (points.any { it.duplicates > 0 }) {
                caveats += "Duplicate sequence numbers were seen. On a broadcast frame " +
                    "that should not happen from retries, so suspect a second receiver " +
                    "path or reflection rather than MAC-layer retransmission."
            }
        }

        return ExperimentResult(
            id = id,
            kind = "link_probe",
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            roles = mapOf(
                RadioRole.TX_PEER.name to txLabel,
                RadioRole.RX_PEER.name to rxLabel,
            ),
            channel = channel.toString(),
            bounds = bounds,
            points = points,
            verification = verification,
            conclusion = conclusion,
            caveats = caveats,
            truncated = truncated,
            carrierSenseEnabled = carrierSense,
        )
    }

    /**
     * Counts what arrived, per burst.
     *
     * Sequence numbers are tracked as a set rather than a high-water mark so
     * duplicates and reordering are visible. A max-only counter would report a
     * burst that delivered frames 0 and 199 as complete.
     */
    private class Collector(private val runId: Int) {
        private val lock = Any()
        private var sequences = HashSet<Int>()
        private var dupes = 0
        private var reordered = 0
        private var highest = -1
        private var crcErrors = 0
        private val rssi = mutableListOf<Int>()
        private val snr = mutableListOf<Int>()
        private val total = AtomicInteger(0)

        fun offer(record: FrameRecord) {
            val seq = ProbeFrame.sequenceOf(record, runId) ?: return
            synchronized(lock) {
                total.incrementAndGet()
                if (!sequences.add(seq)) dupes++
                if (seq < highest) reordered++ else highest = seq
                if (record.crcError) crcErrors++
                // Only frames that actually reported a signal contribute; an
                // unfilled PHY-status block would otherwise drag every mean
                // toward zero.
                record.rssiByChain.maxOrNull()?.takeIf { it != 0 }?.let { rssi += it }
                record.snrByChain.maxOrNull()?.takeIf { it != 0 }?.let { snr += it }
            }
        }

        fun reset() = synchronized(lock) {
            sequences = HashSet()
            dupes = 0
            reordered = 0
            highest = -1
            crcErrors = 0
            rssi.clear()
            snr.clear()
            total.set(0)
        }

        fun snapshot(): Snapshot = synchronized(lock) {
            Snapshot(
                distinct = sequences.size,
                duplicates = dupes,
                outOfOrder = reordered,
                longestGap = longestGap(sequences),
                crcErrors = crcErrors,
                rssiMean = rssi.averageOrNull(),
                rssiMin = rssi.minOrNull(),
                rssiMax = rssi.maxOrNull(),
                snrMean = snr.averageOrNull(),
            )
        }

        /** Longest run of consecutive missing sequence numbers — a burst loss. */
        private fun longestGap(seen: Set<Int>): Int {
            if (seen.isEmpty()) return 0
            val sorted = seen.sorted()
            var worst = 0
            for (i in 1 until sorted.size) {
                worst = maxOf(worst, sorted[i] - sorted[i - 1] - 1)
            }
            return worst
        }

        private fun List<Int>.averageOrNull(): Double? =
            if (isEmpty()) null else average()

        data class Snapshot(
            val distinct: Int,
            val duplicates: Int,
            val outOfOrder: Int,
            val longestGap: Int,
            val crcErrors: Int,
            val rssiMean: Double?,
            val rssiMin: Int?,
            val rssiMax: Int?,
            val snrMean: Double?,
        )
    }
}

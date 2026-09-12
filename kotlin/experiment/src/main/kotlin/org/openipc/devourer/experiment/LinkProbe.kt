package org.openipc.devourer.experiment

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.radio.OpenRadio
import org.openipc.devourer.radio.Radios
import org.openipc.devourer.radio.SafetyLevel
import org.openipc.devourer.radio.VerificationState
import org.openipc.devourer.radio.requireChannelSupported

/**
 * Transmit a bounded burst on one radio, count what independent radios hear.
 *
 * This is the experiment that makes [VerificationState.TX_VERIFIED]
 * reachable. A transmitting radio reporting success proves only that its own
 * TX path accepted the frames; the PA could be dead, the antenna
 * disconnected, the channel misprogrammed. A second adapter hearing tagged
 * frames on the air is the first evidence that anything was actually
 * transmitted — which is why every receiver here is a different physical
 * device, never the transmitter itself.
 *
 * It is also the primitive the sweeps are built from: "the highest reliable
 * MCS", "the channel with the best margin", "does frame size matter here" are
 * all this, run once per point.
 */
public class LinkProbe(
    private val radios: Radios,
    private val scope: CoroutineScope,
) {
    public suspend fun run(
        spec: ExperimentSpec,
        progress: ExperimentRunner.ProgressSink? = null,
    ): ExperimentResult {
        val points = spec.sweep.expand(spec.basePoint)
        if (points.isEmpty()) throw ExperimentException("the sweep expanded to no points")

        val runId = Random.nextInt(1, 0xFFFFFF)
        val started = System.currentTimeMillis()
        val id = progress?.id ?: newId(started, runId)

        val tx = radios.describe(spec.transmitter)
        val witnesses = spec.witnesses.map { (role, session) ->
            Witness(role, session, radios.describe(session), runId)
        }
        val caveats = mutableListOf<String>()

        if (!tx.capabilities.tx.supported) {
            throw ExperimentException("${tx.label} reports no TX capability")
        }
        // Check every channel the sweep will visit, on every radio, before
        // transmitting anything. Discovering on point 9 of 12 that a witness
        // cannot tune ch149 wastes the run and leaves it half-comparable.
        points.map { it.channel }.distinct().forEach { label ->
            val ch = label.spec()
            requireChannelSupported(tx, ch)?.let { caveats += "transmitter at ${label.text}: $it" }
            witnesses.forEach { w ->
                requireChannelSupported(w.radio, ch)?.let {
                    caveats += "${w.role} at ${label.text}: $it"
                }
            }
        }

        if (!spec.carrierSense) {
            radios.setCarrierSense(spec.transmitter, enabled = false, safety = spec.safety)
            caveats += "Carrier sense was DISABLED on the transmitter for this run. The " +
                "delivery ratio therefore measures the radio link alone, with the MAC's " +
                "own decision to defer removed. It is not comparable to a run with " +
                "carrier sense on, and it is not what this adapter would achieve on a " +
                "shared channel."
        }

        val results = mutableListOf<PointResult>()
        val channelEnergy = mutableMapOf<String, RxEnergy?>()
        var truncated = false
        var tuned: String? = null
        // One collector per witness for the whole run, started before any
        // transmission so nothing is missed between points.
        val jobs = mutableListOf<Job>()
        try {
            witnesses.forEach { w ->
                jobs += scope.launch { radios.frames(w.session).collect { w.offer(it) } }
            }

            for (point in points) {
                if (System.currentTimeMillis() - started > spec.bounds.maxDurationMs) {
                    truncated = true
                    caveats += "stopped after ${spec.bounds.maxDurationMs}ms: " +
                        "${points.size - results.size} of ${points.size} points not run"
                    break
                }
                progress?.startingPoint(point.label)

                if (point.channel.text != tuned) {
                    retuneAll(spec, witnesses, point.channel.spec())
                    tuned = point.channel.text
                    channelEnergy[point.channel.text] = idleEnergy(spec.transmitter)
                }

                val measured = try {
                    withTimeout(spec.bounds.pointTimeoutMs) {
                        measure(spec, point, runId, witnesses,
                            channelEnergy[point.channel.text])
                    }
                } catch (e: TimeoutCancellationException) {
                    truncated = true
                    caveats += "point '${point.label}' did not complete within " +
                        "${spec.bounds.pointTimeoutMs}ms and the run was stopped there. A " +
                        "transmit call that does not return is a wedged adapter or a " +
                        "stalled bridge, not a slow link — check monitor.stats and the " +
                        "bridge log before re-running."
                    results += PointResult(
                        point = point.label,
                        framesSent = spec.bounds.framesPerPoint,
                        framesReceived = null,
                        deliveryRatio = null,
                        note = "timed out after ${spec.bounds.pointTimeoutMs}ms. NOT a " +
                            "measurement: the transmit call never returned, so nothing " +
                            "about delivery at this point is known.",
                    )
                    break
                }
                results += measured
                progress?.finishedPoint()
            }
        } finally {
            // Cleanup must survive cancellation: a cancelled run that left
            // carrier sense off would keep the radio transmitting deaf until
            // someone noticed.
            withContext(NonCancellable) {
                // cancelAndJoin, not cancel: a frame collector holds a socket
                // to the bridge, and returning while it is still unwinding
                // leaves the previous run's sink attached during the next
                // one. Joining makes "the run is over" mean it.
                //
                // Bounded, because an unbounded join in a cleanup path is how
                // a run hangs with the radio still claimed — which it did,
                // for ten minutes, when a collector could not be cancelled
                // out of a blocking socket read. That cause is fixed in
                // BridgeClient; this is so the next one degrades instead.
                val joined = withTimeoutOrNull(CLEANUP_JOIN_MS) {
                    jobs.forEach { it.cancelAndJoin() }
                    true
                }
                if (joined == null) {
                    jobs.forEach { it.cancel() }
                    caveats += "A frame collector did not stop within " +
                        "${CLEANUP_JOIN_MS}ms of being cancelled. The run's " +
                        "measurements stand, but something is holding a frame " +
                        "socket open — check monitor.stats and the bridge log."
                }
                witnesses.forEach { w -> runCatching { radios.stopMonitor(w.session) } }
                if (!spec.carrierSense) {
                    runCatching { radios.setCarrierSense(spec.transmitter, enabled = true) }
                }
            }
        }

        return conclude(id, started, tx, witnesses, spec, points, results, caveats, truncated)
    }

    /**
     * The common two-radio case, without building a spec by hand.
     *
     * Kept because most callers — including characterization — want exactly
     * "this transmitter, that witness, these rates".
     */
    public suspend fun simple(
        txSession: Int,
        rxSession: Int,
        channel: ChannelSpec,
        modes: List<String> = listOf("6M"),
        bounds: ExperimentBounds = ExperimentBounds(),
        frameBytes: Int = 200,
        carrierSense: Boolean = true,
        safety: SafetyLevel = SafetyLevel.NORMAL,
    ): ExperimentResult {
        if (modes.isEmpty()) throw ExperimentException("no TX modes given")
        return run(
            ExperimentSpec(
                roles = mapOf(RadioRole.TX_PEER to txSession, RadioRole.RX_PEER to rxSession),
                sweep = Sweep(modes = modes),
                bounds = bounds,
                basePoint = SweepPoint(
                    mode = modes.first(),
                    channel = ChannelLabel.of(channel),
                    frameBytes = frameBytes,
                    intervalUs = bounds.intervalUs,
                ),
                carrierSense = carrierSense,
                safety = safety,
            ),
        )
    }

    private suspend fun retuneAll(
        spec: ExperimentSpec,
        witnesses: List<Witness>,
        channel: ChannelSpec,
    ) {
        // Both ends on the same channel. The witnesses monitor; the
        // transmitter only needs bring-up, which retune performs.
        radios.retune(spec.transmitter, channel)
        witnesses.forEach { w ->
            // Stop unconditionally, including before the first point. The
            // bridge refuses monitor.start on a session already monitoring,
            // so an experiment would otherwise fail outright because someone
            // left a capture running on one of its witnesses — a failure that
            // depends on what happened before the run, which is the kind that
            // only appears when it matters.
            runCatching { radios.stopMonitor(w.session) }
            radios.startMonitor(w.session, channel)
        }
    }

    /**
     * The transmitter's own view of the channel, with nothing of ours on it.
     *
     * Two reads around a fixed dwell: the first resets the hardware counters
     * and is discarded, the second is the delta over the dwell. Taken after
     * tuning and before the first burst, so it describes the channel the MAC
     * is about to decide about — not the channel plus our own transmissions.
     *
     * Costs nothing on a radio that cannot do it: the first read says
     * unsupported and the dwell is skipped.
     */
    private suspend fun idleEnergy(session: Int): RxEnergy? {
        val reset = runCatching { radios.rxEnergy(session) }.getOrNull() ?: return null
        if (!reset.supported) return reset
        delay(ENERGY_DWELL_MS)
        return runCatching { radios.rxEnergy(session, withNhm = true) }.getOrNull()
    }

    private suspend fun measure(
        spec: ExperimentSpec,
        point: SweepPoint,
        runId: Int,
        witnesses: List<Witness>,
        channelEnergy: RxEnergy?,
    ): PointResult {
        witnesses.forEach { it.reset() }
        val frameHex = ProbeFrame.toHex(ProbeFrame.build(runId, point.frameBytes))
        val txResult: JsonObject = radios.sendProbe(
            session = spec.transmitter,
            frameHex = frameHex,
            mode = point.mode,
            count = spec.bounds.framesPerPoint,
            intervalUs = point.intervalUs,
            sequenceOffset = ProbeFrame.SEQUENCE_OFFSET,
        )
        val accepted = txResult.int("sent")
        val elapsedNs = txResult.long("elapsed_ns")

        // Frames in flight when the last one is submitted still have to
        // arrive, be parsed and cross the frame socket. Declaring loss without
        // waiting would systematically under-count delivery at every rate.
        delay(spec.bounds.settleMs)

        val sent = spec.bounds.framesPerPoint
        val perWitness = witnesses.associate { w ->
            w.role.name to w.result(sent)
        }
        val primary = perWitness[RadioRole.RX_PEER.name] ?: perWitness.values.first()
        return PointResult(
            point = point.label,
            framesSent = sent,
            framesReceived = primary.framesReceived,
            deliveryRatio = primary.deliveryRatio,
            duplicates = primary.duplicates,
            outOfOrder = primary.outOfOrder,
            longestGap = primary.longestGap,
            rssiMean = primary.rssiMean,
            rssiMin = primary.rssiMin,
            rssiMax = primary.rssiMax,
            snrMean = primary.snrMean,
            crcErrors = primary.crcErrors,
            txAccepted = accepted,
            txElapsedMs = elapsedNs / 1e6,
            txLateFrames = txResult.int("late_frames"),
            txMaxLateUs = txResult.long("max_late_us"),
            witnesses = perWitness,
            channelEnergy = channelEnergy,
            note = if (accepted < sent) "TX path accepted only $accepted of $sent" else null,
        )
    }

    private fun conclude(
        id: String,
        started: Long,
        tx: OpenRadio,
        witnesses: List<Witness>,
        spec: ExperimentSpec,
        planned: List<SweepPoint>,
        points: List<PointResult>,
        caveats: MutableList<String>,
        truncated: Boolean,
    ): ExperimentResult {
        val measured = points.filter { it.deliveryRatio != null }
        val best = measured.maxByOrNull { it.deliveryRatio ?: 0.0 }
        val anyHeard = measured.any { p -> p.witnesses.values.any { it.framesReceived > 0 } }
        val primaryLabel = witnesses.first().radio.label

        // The verification state is derived from the evidence, never asserted.
        // Nothing heard means FAILED — a zero delivery ratio reported as a
        // successful experiment is exactly the kind of quiet lie this project
        // is built to avoid.
        val verification =
            if (anyHeard) VerificationState.TX_VERIFIED else VerificationState.FAILED

        val axes = spec.sweep.axes
        val swept = if (axes.isEmpty()) "" else " (swept ${axes.joinToString(", ")})"
        val conclusion = when {
            measured.isEmpty() -> "no point produced a measurement"
            !anyHeard ->
                "${tx.label} transmitted but no witness heard anything$swept. The TX path " +
                    "accepted the frames, so this is NOT TX_VERIFIED: check antennas, that " +
                    "every radio is on the same channel and width, and that they are in " +
                    "range of each other."
            best != null ->
                "TX_VERIFIED: $primaryLabel independently received frames from ${tx.label}" +
                    "$swept. Best delivery ${pct(best.deliveryRatio ?: 0.0)} at ${best.point}" +
                    (best.rssiMean?.let { ", mean RSSI ${"%.1f".format(it)}" } ?: "") + "."
            else -> "no point produced a measurement"
        }

        val poor = measured.isNotEmpty() &&
            measured.count { (it.deliveryRatio ?: 0.0) < 0.5 } * 2 > measured.size
        if (spec.carrierSense && (poor || !anyHeard)) {
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
            if (witnesses.size > 1) caveats += disagreementNote(points, witnesses)
        }

        return ExperimentResult(
            id = id,
            kind = "link_probe",
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            roles = buildMap {
                put(RadioRole.TX_PEER.name, tx.label)
                witnesses.forEach { put(it.role.name, it.radio.label) }
            },
            channel = planned.map { it.channel.text }.distinct().joinToString(", "),
            bounds = spec.bounds,
            points = points,
            verification = verification,
            conclusion = conclusion,
            caveats = caveats,
            truncated = truncated,
            carrierSenseEnabled = spec.carrierSense,
        )
    }

    /**
     * What two simultaneous witnesses disagreeing about means.
     *
     * Agreement is the load-bearing result: when two receivers hear exactly
     * the same frames from one burst, the missing frames were never
     * transmitted, and the transmitter is the fault. Disagreement is the
     * opposite finding and is equally useful — it localises the difference to
     * the receivers or their positions.
     */
    private fun disagreementNote(points: List<PointResult>, witnesses: List<Witness>): String {
        val spreads = points.filter { it.deliveryRatio != null }.mapNotNull { p ->
            val counts = p.witnesses.values.map { it.framesReceived }
            if (counts.size < 2) null else (counts.max() - counts.min())
        }
        val worst = spreads.maxOrNull() ?: 0
        val roles = witnesses.joinToString(" and ") { it.role.name }
        return if (worst == 0) {
            "$roles received exactly the same frames at every point. Two independent " +
                "receivers agreeing frame-for-frame means the frames that are missing " +
                "were never aired: the loss is at the transmitter, not in the air or the " +
                "receiver."
        } else {
            "$roles disagreed by up to $worst frames at a point. The loss is therefore " +
                "not purely transmit-side; the difference belongs to the receivers, their " +
                "antennas or their positions — which is only separable by swapping them " +
                "and re-running."
        }
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)

    private fun newId(started: Long, runId: Int) =
        "exp-${started.toString(36)}-${runId.toString(16)}"

    private companion object {
        /** How long cleanup waits for a frame collector to stop. */
        const val CLEANUP_JOIN_MS = 5_000L

        /**
         * Idle dwell for the channel-energy sample, per channel visited.
         *
         * Long enough that the counters accumulate something and short enough
         * that a three-channel sweep does not pay a second and a half for it.
         */
        const val ENERGY_DWELL_MS = 500L
    }

    /**
     * One listening adapter, and what it heard.
     *
     * Sequence numbers are tracked as a set rather than a high-water mark so
     * duplicates and reordering are visible. A max-only counter would report a
     * burst that delivered frames 0 and 199 as complete.
     */
    private class Witness(
        val role: RadioRole,
        val session: Int,
        val radio: OpenRadio,
        private val runId: Int,
    ) {
        private val lock = Any()
        private var sequences = HashSet<Int>()
        private var dupes = 0
        private var reordered = 0
        private var highest = -1
        private var crcErrors = 0
        private val rssi = mutableListOf<Int>()
        private val snr = mutableListOf<Int>()

        fun offer(record: FrameRecord) {
            val seq = ProbeFrame.sequenceOf(record, runId) ?: return
            synchronized(lock) {
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

        fun reset(): Unit = synchronized(lock) {
            sequences = HashSet()
            dupes = 0
            reordered = 0
            highest = -1
            crcErrors = 0
            rssi.clear()
            snr.clear()
        }

        fun result(sent: Int): WitnessResult = synchronized(lock) {
            WitnessResult(
                role = role.name,
                label = radio.label,
                session = session,
                framesReceived = sequences.size,
                deliveryRatio = if (sent > 0) sequences.size.toDouble() / sent else 0.0,
                duplicates = dupes,
                outOfOrder = reordered,
                longestGap = longestGap(sequences),
                rssiMean = rssi.averageOrNull(),
                rssiMin = rssi.minOrNull(),
                rssiMax = rssi.maxOrNull(),
                snrMean = snr.averageOrNull(),
                crcErrors = crcErrors,
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

        private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
    }
}

private fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

private fun JsonObject.long(key: String): Long =
    this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

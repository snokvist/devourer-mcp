package org.openipc.devourer.capture

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import org.openipc.devourer.protocol.FrameAddresses
import org.openipc.devourer.protocol.FrameControl
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.FrameType

/**
 * Frames held locally, addressable by reference.
 *
 * MCP is the control and reasoning plane — frames never travel over it. They
 * live here, and a model reaches them through summaries, queries and exports.
 * That is not only a bandwidth argument: a capture is evidence, and evidence
 * has to stay inspectable after the summary that described it.
 *
 * The store is a bounded ring. When it fills, the oldest frames go — and
 * [droppedOldest] counts them, because a summary computed over a window that
 * silently lost its beginning is a different measurement than one that did not.
 */
public class CaptureStore(
    public val id: String,
    public val capacity: Int = 200_000,
    public val radioLabel: String = "",
    public val channelLabel: String = "",
) {
    private val lock = Any()
    private val ring = ArrayDeque<StoredFrame>(minOf(capacity, 8192))
    private val nextIndex = AtomicLong(0)
    private var dropped = 0L

    public val startedAtEpochMs: Long = System.currentTimeMillis()

    /** Frames discarded to make room. Nonzero means the window is incomplete. */
    public val droppedOldest: Long get() = synchronized(lock) { dropped }

    public val size: Int get() = synchronized(lock) { ring.size }

    /** Total ever admitted, including those since evicted. */
    public val totalAdmitted: Long get() = nextIndex.get()

    public fun add(record: FrameRecord) {
        val stored = StoredFrame(nextIndex.getAndIncrement(), record)
        synchronized(lock) {
            if (ring.size >= capacity) {
                ring.removeFirst()
                dropped++
            }
            ring.addLast(stored)
        }
    }

    public fun snapshot(): List<StoredFrame> = synchronized(lock) { ring.toList() }

    /**
     * One frame by its stable index, or null once it has been evicted.
     *
     * Index arithmetic, not a scan. Indices are assigned monotonically and the
     * ring preserves order, so the offset is exact — the previous linear
     * `firstOrNull` walked up to 200 000 entries *while holding the lock the
     * ingest thread needs*, which stalled RX for the length of the scan.
     */
    public fun frame(index: Long): StoredFrame? = synchronized(lock) {
        val first = ring.firstOrNull() ?: return null
        val offset = index - first.index
        if (offset < 0 || offset >= ring.size) null else ring[offset.toInt()]
    }

    /**
     * The frames inside a trailing time window, copied under the lock.
     *
     * Binary search rather than a full copy-then-filter. `hostNanos` is
     * monotonic across the ring (the bridge stamps it on one thread in
     * arrival order), so the window start can be found in log n and only the
     * window is copied. The previous `snapshot().filter{}` copied the entire
     * ring twice before narrowing — on every sample of every scratchpad
     * source, twice a second.
     */
    private fun windowSnapshot(sinceHostNanos: Long?): List<StoredFrame> = synchronized(lock) {
        if (sinceHostNanos == null) return ring.toList()
        var lo = 0
        var hi = ring.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ring[mid].record.hostNanos < sinceHostNanos) lo = mid + 1 else hi = mid
        }
        if (lo >= ring.size) emptyList() else ring.subList(lo, ring.size).toList()
    }

    public fun query(q: FrameQuery, limit: Int = 100): List<StoredFrame> {
        val window = windowSnapshot(q.sinceHostNanos)
        return if (q.newestFirst) {
            // Walk backwards and stop at `limit` rather than materialising every
            // match and discarding all but the tail.
            val out = ArrayList<StoredFrame>(minOf(limit, window.size))
            for (i in window.indices.reversed()) {
                val f = window[i]
                if (!q.matches(f)) continue
                out += f
                if (out.size >= limit) break
            }
            out
        } else {
            window.asSequence().filter { q.matches(it) }.take(limit).toList()
        }
    }

    public fun summarize(q: FrameQuery = FrameQuery()): CaptureSummary =
        summarizeFrames(windowSnapshot(q.sinceHostNanos).filter { q.matches(it) })

    public fun summarizeAll(): CaptureSummary = summarizeFrames(snapshot())

    private fun summarizeFrames(frames: List<StoredFrame>): CaptureSummary {
        if (frames.isEmpty()) {
            return CaptureSummary(
                captureId = id,
                radio = radioLabel,
                channel = channelLabel,
                frames = 0,
                windowSeconds = 0.0,
                note = "no frames matched",
            )
        }
        val firstNs = frames.first().record.hostNanos
        val lastNs = frames.last().record.hostNanos
        val windowSec = (lastNs - firstNs).coerceAtLeast(0) / 1e9

        val byKind = mutableMapOf<String, Int>()
        val byRate = mutableMapOf<Int, Int>()
        val transmitters = mutableMapOf<String, Int>()
        val bssids = mutableMapOf<String, Int>()
        var crcErrors = 0
        var retries = 0
        var aggregated = 0
        var truncated = 0

        // RSSI is only averaged over frames that actually reported it. A radio
        // fills the signal block only on frames carrying a PHY status — on an
        // A-MPDU that is the first subframe alone — so folding the zeros in
        // would drag every mean toward zero and make a strong link look weak.
        val rssiPerChain = Array(4) { mutableListOf<Int>() }
        val snrPerChain = Array(4) { mutableListOf<Int>() }

        for (f in frames) {
            val r = f.record
            if (r.crcError) crcErrors++
            if (r.aggregated) aggregated++
            if (r.truncated) truncated++
            byRate[r.dataRate] = (byRate[r.dataRate] ?: 0) + 1
            // Bounded by the radio's real chain count, never by "non-zero":
            // the unused slots on a 2-chain part carry unrelated data that is
            // often non-zero (see FrameRecord.rxChains).
            r.rssiByChain.forEachIndexed { c, v -> if (v != 0) rssiPerChain[c].add(v) }
            r.snrByChain.forEachIndexed { c, v -> if (v != 0) snrPerChain[c].add(v) }
            val fc = f.frameControl
            if (fc != null) {
                byKind[fc.name] = (byKind[fc.name] ?: 0) + 1
                if (fc.retry) retries++
                // Parsed once at ingest, not once per summary call.
                val addr = f.addresses
                addr?.transmitter?.let { transmitters[it] = (transmitters[it] ?: 0) + 1 }
                addr?.bssid?.let { bssids[it] = (bssids[it] ?: 0) + 1 }
            }
        }

        return CaptureSummary(
            captureId = id,
            radio = radioLabel,
            channel = channelLabel,
            frames = frames.size,
            windowSeconds = windowSec,
            framesPerSecond = if (windowSec > 0) frames.size / windowSec else 0.0,
            firstIndex = frames.first().index,
            lastIndex = frames.last().index,
            crcErrors = crcErrors,
            retries = retries,
            aggregated = aggregated,
            truncated = truncated,
            byKind = byKind.toList().sortedByDescending { it.second }.toMap(),
            topRateCodes = byRate.toList().sortedByDescending { it.second }.take(8).toMap(),
            rssi = rssiPerChain.mapIndexed { i, v -> chainStats("chain${'A' + i}", v) }
                .filter { it.count > 0 },
            snr = snrPerChain.mapIndexed { i, v -> chainStats("chain${'A' + i}", v) }
                .filter { it.count > 0 },
            topTransmitters = transmitters.toList().sortedByDescending { it.second }.take(8).toMap(),
            topBssids = bssids.toList().sortedByDescending { it.second }.take(8).toMap(),
            evictedBeforeWindow = droppedOldest,
        )
    }

    private fun chainStats(name: String, values: List<Int>): ChainStats =
        if (values.isEmpty()) {
            ChainStats(name, 0, 0, 0, 0.0)
        } else {
            ChainStats(name, values.size, values.min(), values.max(), values.average())
        }
}

/**
 * A frame in the store, with its decode done once.
 *
 * [frameControl] and [addresses] are computed on the ingest thread at `add()`
 * and cached. They were previously re-derived on every query and every summary
 * — for every frame in the ring, several times a second — which made the
 * analysis path cost scale with ring size rather than with result size.
 */
public class StoredFrame(
    public val index: Long,
    public val record: FrameRecord,
) {
    public val frameControl: FrameControl? = record.frameControl
    public val addresses: FrameAddresses? =
        frameControl?.let { FrameAddresses.parse(record.payload, it) }
}

/**
 * Per-chain receive balance, derived from the frames themselves.
 *
 * Works on any backend, including those that never implemented devourer's live
 * RX-path estimator. The question it answers is "are all this radio's chains
 * hearing comparably", which is the closest a capture can get to "are the
 * antennas all connected".
 *
 * It cannot count antenna connectors. A 2T2R part behind four antennas with
 * diversity switching has two chains and will report two; the extra connectors
 * change which antenna feeds a chain, not how many chains exist. What this does
 * catch is a chain sitting far below its neighbours — a missing, unscrewed or
 * blocked antenna, or a dead front end.
 */
@Serializable
public data class ChainBalance(
    val chains: Int,
    val perChain: List<ChainStats>,
    /** Largest mean-RSSI gap between chains, in raw units (~dB). */
    val spread: Double,
    /** Chains more than [weakThreshold] below the strongest. */
    val weakChains: List<String>,
    val weakThreshold: Double,
    val verdict: String,
    val caveat: String = CAVEAT,
) {
    public companion object {
        public const val CAVEAT: String =
            "Derived from ambient traffic in one capture window. Signals arrive " +
                "from one direction at a time, so a real antenna can look weak on " +
                "a single window; a strong nearby transmitter can also couple into " +
                "a disconnected chain and make it look fine. Repeat across " +
                "channels and transmitter positions before treating this as a " +
                "verdict. Counts CHAINS, never antenna connectors."
    }
}

/** Analyses per-chain balance from a summary's chain statistics. */
public fun analyseChainBalance(
    summary: CaptureSummary,
    weakThreshold: Double = 10.0,
): ChainBalance {
    val chains = summary.rssi.filter { it.count > 0 }
    if (chains.isEmpty()) {
        return ChainBalance(
            chains = 0,
            perChain = emptyList(),
            spread = 0.0,
            weakChains = emptyList(),
            weakThreshold = weakThreshold,
            verdict = "no frames reported a per-chain RSSI, so nothing can be said",
        )
    }
    val strongest = chains.maxOf { it.mean }
    val weakest = chains.minOf { it.mean }
    val weak = chains.filter { strongest - it.mean > weakThreshold }.map { it.chain }
    val verdict = when {
        chains.size == 1 ->
            "only one chain reported signal; this radio is receiving on a single chain"
        weak.isEmpty() ->
            "all ${chains.size} chains within ${"%.1f".format(strongest - weakest)} of each " +
                "other — consistent with every chain having a working antenna"
        else ->
            "${weak.joinToString(", ")} more than $weakThreshold below the strongest — " +
                "consistent with a missing, loose or blocked antenna, or a dead front end"
    }
    return ChainBalance(
        chains = chains.size,
        perChain = chains,
        spread = strongest - weakest,
        weakChains = weak,
        weakThreshold = weakThreshold,
        verdict = verdict,
    )
}

@Serializable
public data class ChainStats(
    val chain: String,
    val count: Int,
    val min: Int,
    val max: Int,
    val mean: Double,
)

/**
 * A compact, model-readable account of a capture window.
 *
 * [evictedBeforeWindow] and [truncated] are carried deliberately: a summary
 * that silently sat on a lossy window would be indistinguishable from a clean
 * one, and a model reasoning about link quality would have no way to know.
 */
@Serializable
public data class CaptureSummary(
    val captureId: String,
    val radio: String = "",
    val channel: String = "",
    val frames: Int,
    val windowSeconds: Double,
    val framesPerSecond: Double = 0.0,
    val firstIndex: Long = -1,
    val lastIndex: Long = -1,
    val crcErrors: Int = 0,
    val retries: Int = 0,
    val aggregated: Int = 0,
    val truncated: Int = 0,
    val byKind: Map<String, Int> = emptyMap(),
    val topRateCodes: Map<Int, Int> = emptyMap(),
    val rssi: List<ChainStats> = emptyList(),
    val snr: List<ChainStats> = emptyList(),
    val topTransmitters: Map<String, Int> = emptyMap(),
    val topBssids: Map<String, Int> = emptyMap(),
    val evictedBeforeWindow: Long = 0,
    val note: String = "",
)

/**
 * A filter over stored frames.
 *
 * Every field is optional and ANDed. Kept as data rather than a predicate so a
 * query can be echoed back, stored with a result, and re-run — a summary whose
 * filter cannot be reproduced is not evidence.
 */
@Serializable
public data class FrameQuery(
    val type: FrameType? = null,
    /** Matches [FrameControl.name], e.g. "mgmt/beacon". */
    val kind: String? = null,
    val transmitter: String? = null,
    val bssid: String? = null,
    val minRssi: Int? = null,
    val crcError: Boolean? = null,
    val retry: Boolean? = null,
    val aggregated: Boolean? = null,
    val sinceHostNanos: Long? = null,
    val untilHostNanos: Long? = null,
    val newestFirst: Boolean = true,
) {
    public fun matches(f: StoredFrame): Boolean {
        val r = f.record
        sinceHostNanos?.let { if (r.hostNanos < it) return false }
        untilHostNanos?.let { if (r.hostNanos > it) return false }
        crcError?.let { if (r.crcError != it) return false }
        aggregated?.let { if (r.aggregated != it) return false }
        minRssi?.let { if ((r.rssiByChain.maxOrNull() ?: 0) < it) return false }

        val fc = f.frameControl
        if (type != null || kind != null || retry != null ||
            transmitter != null || bssid != null
        ) {
            if (fc == null) return false
            type?.let { if (fc.type != it) return false }
            kind?.let { if (!fc.name.equals(it, ignoreCase = true)) return false }
            retry?.let { if (fc.retry != it) return false }
            if (transmitter != null || bssid != null) {
                val a = f.addresses ?: return false
                transmitter?.let { if (!it.equals(a.transmitter, ignoreCase = true)) return false }
                bssid?.let { if (!it.equals(a.bssid, ignoreCase = true)) return false }
            }
        }
        return true
    }
}

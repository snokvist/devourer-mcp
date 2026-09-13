package org.openipc.devourer.radio

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One bin of a coarse energy survey: what the chip's own PHY saw while parked
 * on a channel with nothing of ours on the air.
 *
 * Deliberately energy, not frames. A frame count says how much traffic a
 * receiver could DECODE; this says how much energy the channel carried,
 * including energy that never resolves into a frame. Neither substitutes for
 * the other, and a quiet reading on a deaf receiver looks the same as a quiet
 * channel — which is why [SpectrumSweep.quietestChannel] is a hint, not a
 * verdict.
 */
@Serializable
public data class SpectrumPoint(
    val channel: Int,
    /** Channel-busy count over the dwell, both modulations. */
    @SerialName("cca_total") val ccaTotal: Long? = null,
    /** False alarms: energy that started a decode and was not a frame. */
    @SerialName("fa_total") val faTotal: Long? = null,
    /** DIG initial-gain index: higher means a noisier channel. */
    val igi: Int? = null,
    @SerialName("valid_counters") val validCounters: Boolean = false,
    @SerialName("valid_igi") val validIgi: Boolean = false,
    /** Optional 12-bucket in-band power histogram for this bin. */
    val nhm: List<Int>? = null,
    @SerialName("nhm_duration") val nhmDuration: Int? = null,
) {
    public val label: String get() = "ch$channel"
}

/**
 * A swept energy picture, and the channel that looked clearest.
 *
 * [quietestChannel] is picked by the lowest channel-busy count among bins that
 * actually reported counters. All bins share a dwell, so the counts are
 * comparable — but this is the chip's energy view, not a throughput answer:
 * the channel with the least energy is not automatically the one a link will
 * do best on, and a receiver that cannot decode will report every channel as
 * quiet.
 */
@Serializable
public data class SpectrumSweep(
    val session: Int,
    val supported: Boolean,
    val why: String? = null,
    @SerialName("dwell_ms") val dwellMs: Int,
    /** Bins in the order scanned. */
    val points: List<SpectrumPoint> = emptyList(),
    @SerialName("quietest_channel") val quietestChannel: Int? = null,
    val note: String? = null,
)

/**
 * Dwells a list of channels and reads the chip's frame-free energy at each.
 *
 * Built on [Radios.fastRetune], so the hops are the lean ones where the family
 * has them; the starting channel is restored when the scan ends. Realtek-only
 * in practice — it reads [Radios.rxEnergy], which is `IRtlRadio` — so a
 * backend without it reports `supported:false` rather than a picture full of
 * zeros.
 *
 * Each bin is a read-dwell-read: the first read clears the hardware counters
 * (they are deltas that reset on every read), and the second is the dwell's
 * delta.
 */
public class SpectrumScanner(private val radios: Radios) {

    public suspend fun scan(
        session: Int,
        channels: List<Int>,
        dwellMs: Int,
        withNhm: Boolean = false,
    ): SpectrumSweep {
        require(channels.isNotEmpty()) { "give at least one channel to scan" }
        require(channels.all { it in 0..255 }) { "channels must be 0..255" }
        require(dwellMs in MIN_DWELL_MS..MAX_DWELL_MS) {
            "dwell_ms must be $MIN_DWELL_MS..$MAX_DWELL_MS"
        }

        val probe = radios.rxEnergy(session)
        if (!probe.supported) {
            return SpectrumSweep(
                session = session,
                supported = false,
                why = probe.why,
                dwellMs = dwellMs,
                note = probe.fallback,
            )
        }

        val start = runCatching { radios.describe(session).channel?.channel }.getOrNull()
        val points = ArrayList<SpectrumPoint>(channels.size)
        try {
            for (channel in channels) {
                radios.fastRetune(session, channel)
                // Post-tune read clears counters; the dwell's reading is next.
                radios.rxEnergy(session)
                delay(dwellMs.toLong())
                val e = radios.rxEnergy(session, withNhm = withNhm)
                points += SpectrumPoint(
                    channel = channel,
                    ccaTotal = e.ccaTotal,
                    faTotal = e.faTotal,
                    igi = e.igi.takeIf { e.validIgi },
                    validCounters = e.validCounters,
                    validIgi = e.validIgi,
                    nhm = e.nhm,
                    nhmDuration = e.nhmDuration,
                )
            }
        } finally {
            // Leave the radio where it was found, best effort.
            if (start != null) runCatching { radios.fastRetune(session, start) }
        }

        val quietest = points
            .filter { it.validCounters && it.ccaTotal != null }
            .minByOrNull { it.ccaTotal!! }
            ?.channel
        return SpectrumSweep(
            session = session,
            supported = true,
            dwellMs = dwellMs,
            points = points,
            quietestChannel = quietest,
            note = "These are channel-busy and false-alarm counts over one dwell each, not " +
                "decoded frames: energy that never becomes a frame is exactly what a frame " +
                "count misses. The quietest channel is a hint for where to look, not a " +
                "throughput prediction — and a receiver that cannot decode reports every " +
                "channel as quiet.",
        )
    }

    private companion object {
        const val MIN_DWELL_MS = 10
        const val MAX_DWELL_MS = 10_000
    }
}

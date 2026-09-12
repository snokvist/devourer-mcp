package org.openipc.devourer.mcp

import org.openipc.devourer.capture.FrameQuery
import org.openipc.devourer.radio.RadioManager
import org.openipc.devourer.scratchpad.ScratchpadHost

/**
 * The bridge between a scratchpad's declared primitives and the real services.
 *
 * Deliberately thin and total: it translates metric names into reads and
 * returns null when there is nothing to report. Every capability check happens
 * before this is reached, in the interpreter, so nothing here needs to decide
 * whether a program is allowed — only what a permitted read answers.
 */
internal class McpScratchpadHost(
    private val radios: RadioManager,
    private val captures: CaptureService,
) : ScratchpadHost {

    override suspend fun captureMetric(
        captureId: String,
        metric: String,
        windowMs: Long,
        kind: String?,
        transmitter: String?,
    ): Double? {
        // "No such capture" and "this window was empty" are different faults
        // with different fixes, and returning null for both makes a typo in a
        // capture id look identical to a quiet channel. The first throws, so it
        // surfaces in the run log with its cause.
        val capture = captures.get(captureId)
            ?: throw IllegalStateException(
                "no capture '$captureId' is open (running: " +
                    captures.all().joinToString(", ") { it.id }.ifEmpty { "none" } + ")",
            )
        // A trailing window, not the whole run: "mean RSSI" over a 20-minute
        // capture stops responding to the air long before the run ends, and a
        // live view of a dead average is worse than no view.
        val since = System.currentTimeMillis() - windowMs
        val query = FrameQuery(
            kind = kind,
            transmitter = transmitter,
            sinceHostNanos = since * 1_000_000L,
        )
        val s = capture.store.summarize(query)
        if (s.frames == 0) {
            // Distinguish "nothing arrived recently" from "this capture has
            // never seen anything": the second means the radio or channel is
            // wrong, not the window, and it should not be reported as a quiet
            // moment.
            if (capture.store.size == 0) {
                throw IllegalStateException(
                    "capture '$captureId' has received no frames at all — check the radio " +
                        "is monitoring and that the channel carries traffic",
                )
            }
            return null
        }

        return when (metric) {
            "frames" -> s.frames.toDouble()
            "frames_per_second" -> s.framesPerSecond
            "rssi_mean" -> s.rssi.firstOrNull()?.mean
            "rssi_max" -> s.rssi.maxOfOrNull { it.max.toDouble() }
            "snr_mean" -> s.snr.firstOrNull()?.mean
            "retries" -> s.retries.toDouble()
            "retry_rate" -> if (s.frames > 0) s.retries.toDouble() / s.frames else null
            "crc_errors" -> s.crcErrors.toDouble()
            "crc_rate" -> if (s.frames > 0) s.crcErrors.toDouble() / s.frames else null
            "aggregated" -> s.aggregated.toDouble()
            "aggregation_rate" -> if (s.frames > 0) s.aggregated.toDouble() / s.frames else null
            else -> null
        }
    }

    override suspend fun radioMetric(session: Int, metric: String): Double? = when (metric) {
        "tx_submitted", "tx_failed" -> runCatching {
            val stats = radios.txStats(session)
            val key = if (metric == "tx_submitted") "submitted" else "failed"
            stats[key]?.toString()?.trim('"')?.toDoubleOrNull()
        }.getOrNull()

        "monitor_frames" -> runCatching { radios.stats(session).frames.toDouble() }.getOrNull()
        "monitor_dropped" -> runCatching { radios.stats(session).dropped.toDouble() }.getOrNull()
        else -> null
    }
}

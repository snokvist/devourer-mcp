package org.openipc.devourer.experiment

import org.openipc.devourer.protocol.SyntheticFrames
import org.openipc.devourer.radio.FakeRadios

/**
 * Puts a fraction of a transmitted burst into a witness's frame stream.
 *
 * It replays the bytes the transmitter actually handed over, stamping the
 * per-frame counter at the same offset the bridge stamps it — so the receiving
 * side is exercised exactly as it would be on air. A helper that synthesized
 * its own "probe-shaped" frame instead would pass even if [ProbeFrame]'s tag
 * and its matcher disagreed.
 */
public suspend fun FakeRadios.deliverTo(
    probe: FakeRadios.Probe,
    to: Int,
    frames: Int,
    rssi: Int = 70,
    firstSequence: Int = 0,
    duplicateEvery: Int = 0,
) {
    awaitCollector(to)
    val body = probe.frameHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    for (i in 0 until frames) {
        val seq = firstSequence + i
        val payload = body.copyOf()
        writeLe32(payload, probe.sequenceOffset, seq)
        val record = SyntheticFrames.record(
            index = seq.toLong(),
            session = to,
            rssi = intArrayOf(rssi, rssi - 2, 0, 0),
            payload = payload,
        )
        inject(to, record)
        if (duplicateEvery > 0 && seq % duplicateEvery == 0) inject(to, record)
    }
}

private fun writeLe32(b: ByteArray, at: Int, v: Int) {
    b[at] = (v and 0xff).toByte()
    b[at + 1] = ((v shr 8) and 0xff).toByte()
    b[at + 2] = ((v shr 16) and 0xff).toByte()
    b[at + 3] = ((v shr 24) and 0xff).toByte()
}

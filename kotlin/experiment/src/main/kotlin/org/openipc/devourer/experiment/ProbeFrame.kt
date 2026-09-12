package org.openipc.devourer.experiment

import org.openipc.devourer.protocol.FrameAddresses
import org.openipc.devourer.protocol.FrameRecord

/**
 * The frame an experiment puts on the air, and how a receiver recognises it.
 *
 * Design constraints, each of which rules something out:
 *
 * **Broadcast destination.** A unicast frame solicits an ACK, and a missing ACK
 * makes the transmitter retry — which turns a delivery measurement into a
 * measurement of the retry policy. Broadcast frames are never ACKed, so what a
 * receiver counts is what the transmitter actually aired, once each.
 *
 * **A run-unique source MAC.** Two adapters on one bench, or a rerun overlapping
 * the tail of a previous one, must not have their frames confused. The address
 * is locally administered (bit 1 of the first octet) so it cannot collide with
 * real hardware.
 *
 * **Our own sequence counter in the payload.** The 802.11 sequence-control field
 * is the MAC's to assign and may be rewritten in hardware. A counter we place
 * ourselves is the only one we can trust to mean "the Nth frame of this burst",
 * which is what loss, duplication and reordering are all measured against.
 */
public object ProbeFrame {

    /** "DVRX" — present so a stray frame cannot be mistaken for ours. */
    private val MAGIC = byteArrayOf(0x44, 0x56, 0x52, 0x58)

    public const val HEADER_BYTES: Int = 24
    private const val MAGIC_AT = HEADER_BYTES
    private const val RUN_ID_AT = HEADER_BYTES + 4
    public const val SEQUENCE_OFFSET: Int = HEADER_BYTES + 8
    public const val MIN_BYTES: Int = HEADER_BYTES + 12

    /**
     * A source MAC unique to this run.
     *
     * `0x02` sets the locally-administered bit and clears the multicast bit, so
     * this can never collide with a real vendor OUI.
     */
    public fun sourceMac(runId: Int): ByteArray = byteArrayOf(
        0x02,
        0x44, // 'D'
        0x56, // 'V'
        ((runId shr 16) and 0xff).toByte(),
        ((runId shr 8) and 0xff).toByte(),
        (runId and 0xff).toByte(),
    )

    public fun macString(mac: ByteArray): String =
        mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Builds the 802.11 MPDU (no radiotap — the bridge adds that from the
     * structured TX mode).
     *
     * @param totalBytes total MPDU length; padded with a repeating pattern
     *  rather than zeros, so a truncated or corrupted frame is visible on
     *  inspection instead of blending into empty space.
     */
    public fun build(runId: Int, totalBytes: Int = 200): ByteArray {
        require(totalBytes >= MIN_BYTES) {
            "a probe frame needs at least $MIN_BYTES bytes (802.11 header + tag)"
        }
        val f = ByteArray(totalBytes)
        f[0] = 0x08 // type=data, subtype=0
        f[1] = 0x00 // no to-DS/from-DS: an IBSS-style frame needing no AP
        // duration/id left zero; the MAC fills what it needs
        for (i in 0 until 6) f[4 + i] = 0xFF.toByte() // addr1: broadcast, never ACKed
        val src = sourceMac(runId)
        System.arraycopy(src, 0, f, 10, 6) // addr2: transmitter
        System.arraycopy(src, 0, f, 16, 6) // addr3: BSSID, same as us
        System.arraycopy(MAGIC, 0, f, MAGIC_AT, 4)
        writeLe32(f, RUN_ID_AT, runId)
        writeLe32(f, SEQUENCE_OFFSET, 0) // the bridge stamps this per frame
        for (i in (SEQUENCE_OFFSET + 4) until totalBytes) {
            f[i] = (0xA5 xor (i and 0xff)).toByte()
        }
        return f
    }

    /**
     * The burst sequence number carried by [record], or null when this frame is
     * not one of ours.
     *
     * Checks the source address, the magic and the run id. All three, because
     * ambient traffic on a busy channel will otherwise eventually produce a
     * frame that matches one of them by accident, and a single false positive
     * inflates a delivery ratio above what was actually received.
     */
    public fun sequenceOf(record: FrameRecord, runId: Int): Int? {
        val p = record.payload
        if (p.size < MIN_BYTES) return null
        val fc = record.frameControl ?: return null
        val transmitter = FrameAddresses.parse(p, fc).transmitter ?: return null
        if (!transmitter.equals(macString(sourceMac(runId)), ignoreCase = true)) return null
        for (i in 0 until 4) if (p[MAGIC_AT + i] != MAGIC[i]) return null
        if (readLe32(p, RUN_ID_AT) != runId) return null
        return readLe32(p, SEQUENCE_OFFSET)
    }

    public fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun writeLe32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xff).toByte()
        b[at + 1] = ((v shr 8) and 0xff).toByte()
        b[at + 2] = ((v shr 16) and 0xff).toByte()
        b[at + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun readLe32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)
}

package org.openipc.devourer.experiment

import org.openipc.devourer.protocol.FrameAddresses
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.FrameType

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
 *
 * **Optionally QoS, so the MAC can aggregate.** A plain data frame carries no
 * TID, and the A-MPDU engine has nothing to aggregate it under. Passing a TID
 * switches to the QoS Data shape (FC 0x88) whose QoS control field names the
 * TID `AmpduMode` keys on. The tag and the counter move by the two QoS-control
 * bytes, so the receive side derives the offsets from the frame's own control
 * field rather than assuming one shape.
 */
public object ProbeFrame {

    /** "DVRX" — present so a stray frame cannot be mistaken for ours. */
    private val MAGIC = byteArrayOf(0x44, 0x56, 0x52, 0x58)

    public const val HEADER_BYTES: Int = 24

    /** QoS Data header: the plain header plus the 2-byte QoS control field. */
    public const val QOS_HEADER_BYTES: Int = 26
    public const val SEQUENCE_OFFSET: Int = HEADER_BYTES + 8
    public const val QOS_SEQUENCE_OFFSET: Int = QOS_HEADER_BYTES + 8
    public const val MIN_BYTES: Int = HEADER_BYTES + 12
    public const val QOS_MIN_BYTES: Int = QOS_HEADER_BYTES + 12

    /** The 802.11 header length [build] emits for the given TID choice. */
    public fun headerBytes(qosTid: Int?): Int =
        if (qosTid == null) HEADER_BYTES else QOS_HEADER_BYTES

    /**
     * Where the bridge stamps the per-frame sequence counter, relative to the
     * MPDU the caller handed over.
     */
    public fun sequenceOffset(qosTid: Int?): Int =
        if (qosTid == null) SEQUENCE_OFFSET else QOS_SEQUENCE_OFFSET

    /** The shortest frame [build] will produce for the given TID choice. */
    public fun minBytes(qosTid: Int?): Int =
        if (qosTid == null) MIN_BYTES else QOS_MIN_BYTES

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
     * @param qosTid when non-null, build a QoS Data frame carrying this TID
     *  (0..7) in its QoS control field. Required for the MAC to aggregate the
     *  frames into A-MPDUs; a plain data frame has no TID to aggregate under.
     */
    public fun build(runId: Int, totalBytes: Int = 200, qosTid: Int? = null): ByteArray {
        if (qosTid != null) {
            require(qosTid in 0..7) {
                "qosTid must be 0..7 — the A-MPDU engine aggregates by the TID in the " +
                    "QoS control field"
            }
        }
        val header = headerBytes(qosTid)
        val magicAt = header
        val runIdAt = header + 4
        val sequenceAt = header + 8
        val min = minBytes(qosTid)
        require(totalBytes >= min) {
            "a probe frame needs at least $min bytes (802.11 header + tag)"
        }
        val f = ByteArray(totalBytes)
        f[0] = if (qosTid == null) 0x08.toByte() else 0x88.toByte() // data / QoS data
        f[1] = 0x00 // no to-DS/from-DS: an IBSS-style frame needing no AP
        // duration/id left zero; the MAC fills what it needs
        for (i in 0 until 6) f[4 + i] = 0xFF.toByte() // addr1: broadcast, never ACKed
        val src = sourceMac(runId)
        System.arraycopy(src, 0, f, 10, 6) // addr2: transmitter
        System.arraycopy(src, 0, f, 16, 6) // addr3: BSSID, same as us
        if (qosTid != null) {
            // QoS control: TID in bits 0..3, ack policy 00 (normal). The RA is
            // broadcast so no ACK is possible either way; the retry-limit half
            // of the A-MPDU recipe belongs to the descriptor (radio_ampdu).
            f[24] = qosTid.toByte()
            f[25] = 0x00
        }
        System.arraycopy(MAGIC, 0, f, magicAt, 4)
        writeLe32(f, runIdAt, runId)
        writeLe32(f, sequenceAt, 0) // the bridge stamps this per frame
        for (i in (sequenceAt + 4) until totalBytes) {
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
        val fc = record.frameControl ?: return null
        val qos = fc.type == FrameType.DATA && (fc.subtype and 0x08) != 0
        // The header length depends on the frame's own addresses, not on the
        // shape we emit. A 4-address frame (to-DS and from-DS, i.e. WDS) carries
        // Address4 between sequence control and the QoS control field, so both
        // the QoS field and the tag move by 6. A QoS frame with the order bit
        // set carries the 4-byte HT control field as well. We build neither
        // form, but a parser that assumed our shape would read ambient WDS
        // traffic at the wrong offset — rejecting is correct, misreading is not.
        val fourAddress = fc.type == FrameType.DATA && fc.toDs && fc.fromDs
        val base = if (fourAddress) HEADER_BYTES + 6 else HEADER_BYTES
        val header = when {
            !qos -> base
            fc.order -> base + 2 + 4
            else -> base + 2
        }
        val magicAt = header
        val runIdAt = header + 4
        val sequenceAt = header + 8
        val p = record.payload
        if (p.size < header + 12) return null
        val transmitter = FrameAddresses.parse(p, fc).transmitter ?: return null
        if (!transmitter.equals(macString(sourceMac(runId)), ignoreCase = true)) return null
        for (i in 0 until 4) if (p[magicAt + i] != MAGIC[i]) return null
        if (readLe32(p, runIdAt) != runId) return null
        return readLe32(p, sequenceAt)
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

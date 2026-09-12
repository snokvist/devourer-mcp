package org.openipc.devourer.capture

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.openipc.devourer.protocol.FrameRecord

/**
 * Exports captured frames as libpcap with a synthesized radiotap header, so a
 * capture opens in Wireshark, tshark or anything else that reads DLT 127.
 *
 * Synthesizing radiotap is the honest way to carry devourer's per-frame PHY
 * data out of this system: the fields are standard, every tool understands
 * them, and nothing is invented — a field the chip did not fill is simply not
 * present in the header, rather than emitted as zero. "Absent" and "measured
 * zero" are different claims, and a monitor capture that confuses them will
 * mislead whoever reads it later.
 *
 * The raw MPDU is written unmodified. Summaries are an optimization; the bytes
 * are the evidence.
 */
public object PcapWriter {

    private const val LINKTYPE_IEEE802_11_RADIOTAP = 127
    private const val PCAP_MAGIC_NANOS = 0xa1b23c4d.toInt()

    // Radiotap "present" bitmask positions (radiotap.org field ordering).
    private const val TSFT = 0
    private const val FLAGS = 1
    private const val RATE = 2
    private const val CHANNEL = 3
    private const val DBM_ANTSIGNAL = 5
    private const val DBM_ANTNOISE = 6
    private const val ANTENNA = 11
    private const val MCS = 19

    private const val FLAG_FCS_AT_END = 0x10
    private const val FLAG_BAD_FCS = 0x40
    private const val FLAG_SHORT_GI = 0x80

    /**
     * @param frames frames to write, in capture order.
     * @param centerFrequencyMhz the channel the radio was tuned to. Radiotap
     *  has no way to say "unknown channel", so when this is null the channel
     *  field is omitted rather than guessed.
     * @param rssiIsDbm whether [FrameRecord.rssi] is already a dBm magnitude.
     *  Devourer reports a positive 0..100-ish quality on these parts, so the
     *  default converts it; passing true writes it through untouched.
     */
    public fun write(
        path: Path,
        frames: List<FrameRecord>,
        centerFrequencyMhz: Int? = null,
        rssiIsDbm: Boolean = false,
    ): Long {
        Files.createDirectories(path.parent ?: Path.of("."))
        BufferedOutputStream(Files.newOutputStream(path)).use { out ->
            writeGlobalHeader(out)
            var written = 0L
            for (f in frames) {
                val radiotap = buildRadiotap(f, centerFrequencyMhz, rssiIsDbm)
                writePacketHeader(out, f.hostNanos, radiotap.size + f.payload.size)
                out.write(radiotap)
                out.write(f.payload)
                written++
            }
            out.flush()
            return written
        }
    }

    private fun writeGlobalHeader(out: OutputStream) {
        val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(PCAP_MAGIC_NANOS) // nanosecond-resolution timestamps
        b.putShort(2) // version major
        b.putShort(4) // version minor
        b.putInt(0) // thiszone
        b.putInt(0) // sigfigs
        b.putInt(262144) // snaplen
        b.putInt(LINKTYPE_IEEE802_11_RADIOTAP)
        out.write(b.array())
    }

    private fun writePacketHeader(out: OutputStream, hostNanos: Long, length: Int) {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt((hostNanos / 1_000_000_000L).toInt())
        b.putInt((hostNanos % 1_000_000_000L).toInt())
        b.putInt(length)
        b.putInt(length)
        out.write(b.array())
    }

    private fun buildRadiotap(
        f: FrameRecord,
        centerFrequencyMhz: Int?,
        rssiIsDbm: Boolean,
    ): ByteArray {
        var present = 0
        val body = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)

        // Fields must appear in bit order, each aligned to its own size.
        fun align(n: Int) {
            while (body.position() % n != 0) body.put(0)
        }

        // TSFT — only where the chip actually timestamps. The MT7612U does not,
        // and emitting its constant zero would look like a stopped clock.
        if (f.tsfl != 0L) {
            present = present or (1 shl TSFT)
            align(8)
            body.putLong(f.tsfl)
        }

        present = present or (1 shl FLAGS)
        var flags = 0
        if (f.fcsPresent) flags = flags or FLAG_FCS_AT_END
        if (f.crcError) flags = flags or FLAG_BAD_FCS
        if (f.shortGi != 0) flags = flags or FLAG_SHORT_GI
        body.put(flags.toByte())

        // Legacy rate, in 500 kbps units, for CCK/OFDM codes only. An HT/VHT/HE
        // frame's rate is not expressible here and goes in the MCS field.
        val legacy = legacyRateHalfMbps(f.dataRate)
        if (legacy != null) {
            present = present or (1 shl RATE)
            body.put(legacy.toByte())
        }

        if (centerFrequencyMhz != null) {
            present = present or (1 shl CHANNEL)
            align(2)
            body.putShort(centerFrequencyMhz.toShort())
            val flagsCh = if (centerFrequencyMhz < 3000) 0x0080 or 0x0020 else 0x0100 or 0x0040
            body.putShort(flagsCh.toShort())
        }

        val best = f.rssi.maxOrNull() ?: 0
        if (best != 0) {
            present = present or (1 shl DBM_ANTSIGNAL)
            body.put(toDbm(best, rssiIsDbm).toByte())
        }
        val bestSnr = f.snr.maxOrNull() ?: 0
        if (bestSnr != 0 && best != 0) {
            present = present or (1 shl DBM_ANTNOISE)
            body.put((toDbm(best, rssiIsDbm) - bestSnr).toByte())
        }
        if (f.rssiChains > 0) {
            present = present or (1 shl ANTENNA)
            body.put(0)
        }

        if (f.dataRate >= 0x80) {
            present = present or (1 shl MCS)
            val known = 0x01 or 0x02 or 0x04 // bandwidth, MCS index, guard interval
            var mcsFlags = 0
            mcsFlags = mcsFlags or (f.bandwidth and 0x03)
            if (f.shortGi != 0) mcsFlags = mcsFlags or 0x04
            body.put(known.toByte())
            body.put(mcsFlags.toByte())
            body.put((f.dataRate and 0x7f).toByte())
        }

        val bodyBytes = ByteArray(body.position())
        body.flip()
        body.get(bodyBytes)

        val header = ByteBuffer.allocate(8 + bodyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put(0) // version
        header.put(0) // pad
        header.putShort((8 + bodyBytes.size).toShort())
        header.putInt(present)
        header.put(bodyBytes)
        return header.array()
    }

    /** Devourer reports a positive signal quality; radiotap wants signed dBm. */
    private fun toDbm(raw: Int, alreadyDbm: Boolean): Int =
        if (alreadyDbm) raw else raw - 110

    /**
     * Legacy 802.11a/b/g rate in 500 kbps units, or null for HT/VHT/HE codes.
     *
     * Devourer's rate codes below 0x80 are the classic 12-entry legacy table.
     */
    private fun legacyRateHalfMbps(code: Int): Int? = when (code) {
        0 -> 2 // 1 Mbps
        1 -> 4 // 2
        2 -> 11 // 5.5
        3 -> 22 // 11
        4 -> 12 // 6
        5 -> 18 // 9
        6 -> 24 // 12
        7 -> 36 // 18
        8 -> 48 // 24
        9 -> 72 // 36
        10 -> 96 // 48
        11 -> 108 // 54
        else -> null
    }
}

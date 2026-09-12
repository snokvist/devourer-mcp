package org.openipc.devourer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synthetic frames, built the way the bridge builds real ones.
 *
 * Shared rather than copied per module on purpose: this writes the same
 * hand-maintained byte offsets that `Protocol.h` and [FrameRecord] agree on by
 * convention, and a second copy would be a second place for them to drift. It
 * goes through [FrameRecord.decode] for the same reason — a builder that
 * constructed the data class directly would keep passing after the decoder
 * broke.
 */
public object SyntheticFrames {

    public fun record(
        index: Long = 0,
        hostNanos: Long = index * 1_000_000L,
        session: Int = 1,
        rssi: IntArray = intArrayOf(70, 68, 0, 0),
        snr: IntArray = intArrayOf(0, 0, 0, 0),
        crcError: Boolean = false,
        aggregated: Boolean = false,
        dataRate: Int = 0,
        tsfl: Long = 12345,
        fcsPresent: Boolean = true,
        rxChains: Int = 2,
        payload: ByteArray = beacon(),
    ): FrameRecord {
        val b = ByteBuffer.allocate(FrameRecord.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, FrameRecord.MAGIC)
        b.putLong(8, index)
        b.putLong(16, hostNanos)
        b.putInt(24, session)
        b.putInt(28, payload.size)
        b.putShort(32, payload.size.toShort())
        b.putShort(36, dataRate.toShort())
        b.putInt(40, tsfl.toInt())
        for (i in 0 until 4) b.put(52 + i, rssi[i].toByte())
        for (i in 0 until 4) b.put(56 + i, snr[i].toByte())
        b.put(66, if (crcError) 1 else 0)
        b.put(73, if (aggregated) 1 else 0)
        b.put(74, if (fcsPresent) 1 else 0)
        b.put(78, rxChains.toByte())
        return FrameRecord.decode(b, payload)
    }

    /** A beacon with a known BSSID and transmitter. */
    public fun beacon(bssid: Int = 0xAA): ByteArray {
        val f = ByteArray(40)
        f[0] = 0x80.toByte() // mgmt / beacon
        f[1] = 0x00
        for (i in 0 until 6) f[4 + i] = 0xFF.toByte() // addr1 broadcast
        for (i in 0 until 6) f[10 + i] = (0x10 + i).toByte() // addr2 transmitter
        for (i in 0 until 6) f[16 + i] = (bssid + i).toByte() // addr3 bssid
        return f
    }

    public fun qosData(retry: Boolean): ByteArray {
        val f = ByteArray(34)
        f[0] = 0x88.toByte() // data / qos-data
        f[1] = if (retry) 0x08 else 0x00
        for (i in 0 until 6) f[4 + i] = (0x20 + i).toByte()
        for (i in 0 until 6) f[10 + i] = (0x30 + i).toByte()
        for (i in 0 until 6) f[16 + i] = (0x40 + i).toByte()
        return f
    }
}

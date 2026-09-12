package org.openipc.devourer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These tests pin the byte offsets of `bridge::FrameRecord`.
 *
 * The Kotlin reader hardcodes offsets into a packed C struct; nothing in the
 * type system connects the two. A silent offset drift would not crash — it
 * would quietly report the wrong RSSI, the wrong rate, the wrong retry flag,
 * and every conclusion drawn from a capture after that would be wrong while
 * looking entirely plausible. So each field is written at its documented offset
 * with a distinct value and read back.
 *
 * The other half of this guard is at runtime: `hello` reports
 * `frame_record_bytes`, and the client refuses a bridge whose record size
 * differs from [FrameRecord.HEADER_BYTES].
 */
class FrameRecordTest {

    /** Builds a header with a distinct value in every field, at its offset. */
    private fun goldenHeader(
        hasTxTsf: Boolean = true,
        magic: Int = FrameRecord.MAGIC,
    ): ByteBuffer {
        val b = ByteBuffer.allocate(FrameRecord.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, magic)
        b.putInt(4, 88 - 8 + 3) // record_len: header remainder + payload
        b.putLong(8, 0x0102_0304_0506_0708L) // seq
        b.putLong(16, 0x1112_1314_1516_1718L) // host_ns
        b.putInt(24, 7) // session
        b.putInt(28, 3) // frame_len
        b.putShort(32, 1500) // pkt_len
        b.putShort(34, 4095) // seq_num
        b.putShort(36, 0x0180) // data_rate: an HE code, needs all 9 bits
        b.put(38, 5) // frag_num
        b.put(39, 6) // priority
        b.putInt(40, 0xDEAD_BEEF.toInt()) // tsfl — must read back unsigned
        b.put(44, 2) // bw
        b.put(45, 1) // stbc
        b.put(46, 1) // ldpc
        b.put(47, 1) // sgi
        b.put(48, 7) // ppdu_type
        b.put(49, 3) // ppdu_cnt
        b.put(50, 0x5A) // scrambler
        b.put(51, (-12).toByte()) // cfo_tail — signed
        for (i in 0 until 4) b.put(52 + i, (200 + i).toByte()) // rssi, unsigned
        for (i in 0 until 4) b.put(56 + i, (-10 - i).toByte()) // snr, signed
        for (i in 0 until 4) b.put(60 + i, (-20 - i).toByte()) // evm, signed
        b.put(64, 1) // physt
        b.put(65, 2) // phy_fill
        b.put(66, 1) // crc_err
        b.put(67, 1) // icv_err
        b.put(68, 1) // bdecrypted
        b.put(69, 4) // encrypt
        b.put(70, 1) // qos
        b.put(71, 1) // mdata
        b.put(72, 1) // mfrag
        b.put(73, 1) // paggr
        b.put(74, 1) // fcs_present
        b.put(75, 2) // pkt_rpt_type -> TX_REPORT
        b.put(76, if (hasTxTsf) 1 else 0)
        b.put(77, 1) // truncated
        b.put(78, 2) // rx_chains — a 2T2R part
        b.putLong(80, 0x2122_2324_2526_2728L) // tx_egress_tsf
        return b
    }

    @Test
    fun `every field decodes from its documented offset`() {
        val payload = byteArrayOf(0x80.toByte(), 0x00, 0x2A)
        val r = FrameRecord.decode(goldenHeader(), payload)

        assertEquals(0x0102_0304_0506_0708L, r.sequence)
        assertEquals(0x1112_1314_1516_1718L, r.hostNanos)
        assertEquals(7, r.session)
        assertEquals(3, r.frameLength)
        assertEquals(1500, r.packetLength)
        assertEquals(4095, r.sequenceNumber)
        assertEquals(0x0180, r.dataRate)
        assertEquals(5, r.fragmentNumber)
        assertEquals(6, r.priority)
        assertEquals(2, r.bandwidth)
        assertEquals(1, r.stbc)
        assertEquals(1, r.ldpc)
        assertEquals(1, r.shortGi)
        assertEquals(7, r.ppduType)
        assertEquals(3, r.ppduCount)
        assertEquals(0x5A, r.scrambler)
        assertEquals(-12, r.cfoTail)
        assertTrue(r.phyStatus)
        assertTrue(r.crcError)
        assertTrue(r.icvError)
        assertTrue(r.decrypted)
        assertEquals(4, r.encryption)
        assertTrue(r.qos)
        assertTrue(r.moreData)
        assertTrue(r.moreFragments)
        assertTrue(r.aggregated)
        assertTrue(r.fcsPresent)
        assertEquals(PacketReportType.TX_REPORT, r.reportType)
        assertTrue(r.truncated)
        assertEquals(0x2122_2324_2526_2728L, r.txEgressTsf)
        assertTrue(payload.contentEquals(r.payload))
    }

    @Test
    fun `tsfl is unsigned - a 32-bit TSF past 2^31 must not read negative`() {
        // The chip's TSF wraps through the top half of the u32 range every
        // ~71 minutes. Reading it signed would make timing arithmetic jump
        // backwards by 4.3 seconds for half of every wrap period.
        val r = FrameRecord.decode(goldenHeader(), ByteArray(3))
        assertEquals(0xDEAD_BEEFL, r.tsfl)
        assertTrue(r.tsfl > 0)
    }

    @Test
    fun `rssi is unsigned and snr and evm are signed`() {
        val r = FrameRecord.decode(goldenHeader(), ByteArray(3))
        // RSSI 200 read as a signed byte would be -56: a plausible-looking dBm
        // value, which is exactly why this is worth a test.
        assertEquals(listOf(200, 201, 202, 203), r.rssi.toList())  // raw slots
        assertEquals(listOf(-10, -11, -12, -13), r.snr.toList())
        assertEquals(listOf(-20, -21, -22, -23), r.evm.toList())
        assertEquals(2, r.rssiChains) // bounded by rx_chains, not by non-zero
    }

    @Test
    fun `absent tx egress tsf is null and not zero`() {
        val r = FrameRecord.decode(goldenHeader(hasTxTsf = false), ByteArray(3))
        assertNull(r.txEgressTsf)
    }

    @Test
    fun `a bad magic fails loudly instead of returning garbage`() {
        val e = assertFailsWith<FrameDesyncException> {
            FrameRecord.decode(goldenHeader(magic = 0x4B4F4F4C), ByteArray(3))
        }
        assertTrue(e.message!!.contains("desynchronized"))
    }

    @Test
    fun `chain views are bounded by the radio's real chain count`() {
        // snr[2]/snr[3] are non-zero in the fixture but are NOT path C/D SNR on
        // a 2-chain part — they carry stream CSI. Counting non-zero slots would
        // report four chains and turn CSI into a published SNR measurement.
        val r = FrameRecord.decode(goldenHeader(), ByteArray(3))
        assertEquals(2, r.rxChains)
        assertEquals(2, r.rssiByChain.size)
        assertEquals(2, r.snrByChain.size)
        assertEquals(2, r.rssiChains)
        assertEquals(listOf(200, 201), r.rssiByChain)
    }

    @Test
    fun `zero chains are reported as zero rather than averaged in`() {
        val b = goldenHeader()
        b.put(52 + 1, 0) // chain B silent this frame
        val r = FrameRecord.decode(b, ByteArray(3))
        assertEquals(1, r.rssiChains, "a silent chain must not be counted as reporting")
    }

    @Test
    fun `records with equal bytes compare equal`() {
        val a = FrameRecord.decode(goldenHeader(), byteArrayOf(1, 2, 3))
        val b = FrameRecord.decode(goldenHeader(), byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = FrameRecord.decode(goldenHeader(), byteArrayOf(1, 2, 4))
        assertFalse(a == c)
    }
}

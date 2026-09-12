package org.openipc.devourer.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcapWriterTest {

    private fun tempPcap() = Files.createTempFile("devourer-test", ".pcap").also {
        it.toFile().deleteOnExit()
    }

    @Test
    fun `writes a readable pcap with the radiotap link type`() {
        val out = tempPcap()
        val frames = listOf(Fixtures.record(index = 0), Fixtures.record(index = 1))
        val n = PcapWriter.write(out, frames, centerFrequencyMhz = 2437)
        assertEquals(2, n)

        val bytes = Files.readAllBytes(out)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xa1b23c4d.toInt(), b.getInt(0), "nanosecond pcap magic")
        assertEquals(2, b.getShort(4).toInt())
        assertEquals(127, b.getInt(20), "DLT_IEEE802_11_RADIOTAP")
        assertTrue(bytes.size > 24)
    }

    @Test
    fun `raw MPDU bytes survive the round trip unmodified`() {
        // Summaries are an optimization; the bytes are the evidence. If export
        // mutated them, deeper analysis of a stored capture would be worthless.
        val out = tempPcap()
        val payload = Fixtures.beacon(bssid = 0x5C)
        PcapWriter.write(out, listOf(Fixtures.record(payload = payload)), 2437)
        val bytes = Files.readAllBytes(out)

        val rtLen = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(24 + 16 + 2)
        val mpduStart = 24 + 16 + rtLen
        val mpdu = bytes.copyOfRange(mpduStart, mpduStart + payload.size)
        assertTrue(payload.contentEquals(mpdu))
    }

    @Test
    fun `a chip with no RX timestamp omits TSFT instead of writing a zero`() {
        // The MT7612U reports tsfl=0 always. Emitting that as a TSFT field
        // would show every frame arriving at time zero — a stopped clock that
        // looks like data.
        val out = tempPcap()
        PcapWriter.write(out, listOf(Fixtures.record(tsfl = 0)), 2437)
        val b = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN)
        val present = b.getInt(24 + 16 + 4)
        assertEquals(0, present and 1, "TSFT bit must be clear when the chip has no timestamp")

        val out2 = tempPcap()
        PcapWriter.write(out2, listOf(Fixtures.record(tsfl = 999)), 2437)
        val b2 = ByteBuffer.wrap(Files.readAllBytes(out2)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, b2.getInt(24 + 16 + 4) and 1, "TSFT present when the chip does stamp")
    }

    @Test
    fun `a bad FCS is flagged so a corrupt frame is not read as clean`() {
        val out = tempPcap()
        PcapWriter.write(out, listOf(Fixtures.record(crcError = true)), 2437)
        val bytes = Files.readAllBytes(out)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val present = b.getInt(24 + 16 + 4)
        assertTrue((present shr 1) and 1 == 1, "FLAGS field present")
        // FLAGS is the first field emitted when TSFT is present (tsfl defaults
        // non-zero in the fixture), so it sits just past the 8-byte TSFT.
        val flags = bytes[24 + 16 + 8 + 8].toInt()
        assertTrue(flags and 0x40 != 0, "BAD_FCS set")
    }

    @Test
    fun `no channel given means no channel field rather than a guessed one`() {
        val out = tempPcap()
        PcapWriter.write(out, listOf(Fixtures.record()), centerFrequencyMhz = null)
        val b = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN)
        val present = b.getInt(24 + 16 + 4)
        assertFalse((present shr 3) and 1 == 1, "CHANNEL must be absent, not fabricated")
    }

    @Test
    fun `empty capture still writes a valid header`() {
        val out = tempPcap()
        assertEquals(0, PcapWriter.write(out, emptyList(), 2437))
        assertEquals(24, Files.size(out))
    }
}

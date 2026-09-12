package org.openipc.devourer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameControlTest {

    private fun frame(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `beacon is decoded as management subtype 8`() {
        val fc = FrameControl(0x80.toByte(), 0x00)
        assertEquals(FrameType.MANAGEMENT, fc.type)
        assertEquals(8, fc.subtype)
        assertEquals("mgmt/beacon", fc.name)
        assertFalse(fc.retry)
    }

    @Test
    fun `qos data with retry and protection set`() {
        // 0x88 = type data (2), subtype 8 (QoS data); 0x48 = retry | protected
        val fc = FrameControl(0x88.toByte(), 0x48.toByte())
        assertEquals(FrameType.DATA, fc.type)
        assertEquals("data/qos-data", fc.name)
        assertTrue(fc.retry)
        assertTrue(fc.protectedFrame)
        assertFalse(fc.toDs)
    }

    @Test
    fun `an unknown subtype still gets a stable greppable name`() {
        val fc = FrameControl(0x60.toByte(), 0x00) // mgmt subtype 6, reserved
        assertEquals("mgmt/0x6", fc.name)
    }

    @Test
    fun `a CTS carries only a receiver address`() {
        // Reading addr2 from a CTS would return FCS bytes formatted as a MAC —
        // a real-looking address that was never on the air.
        val cts = frame(0xC4, 0x00, 0x00, 0x00, 1, 2, 3, 4, 5, 6, 0xAA, 0xBB, 0xCC, 0xDD)
        val fc = FrameControl(cts[0], cts[1])
        assertEquals("ctrl/cts", fc.name)
        val a = FrameAddresses.parse(cts, fc)
        assertEquals("01:02:03:04:05:06", a.receiver)
        assertNull(a.transmitter)
        assertNull(a.bssid)
    }

    @Test
    fun `an RTS carries receiver and transmitter`() {
        val rts = frame(
            0xB4, 0x00, 0x00, 0x00,
            1, 2, 3, 4, 5, 6,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        )
        val fc = FrameControl(rts[0], rts[1])
        assertEquals("ctrl/rts", fc.name)
        val a = FrameAddresses.parse(rts, fc)
        assertEquals("01:02:03:04:05:06", a.receiver)
        assertEquals("0a:0b:0c:0d:0e:0f", a.transmitter)
    }

    @Test
    fun `from-DS data frame maps addresses to the right roles`() {
        // from-DS: addr1 = destination, addr2 = BSSID, addr3 = source
        val f = frame(
            0x08, 0x02, 0x00, 0x00,
            0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, // addr1
            0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5, // addr2
            0x50, 0x51, 0x52, 0x53, 0x54, 0x55, // addr3
        )
        val fc = FrameControl(f[0], f[1])
        assertTrue(fc.fromDs)
        assertFalse(fc.toDs)
        val a = FrameAddresses.parse(f, fc)
        assertEquals("d0:d1:d2:d3:d4:d5", a.destination)
        assertEquals("50:51:52:53:54:55", a.source)
        assertEquals("b0:b1:b2:b3:b4:b5", a.bssid)
    }

    @Test
    fun `a truncated frame yields nulls rather than reading past the end`() {
        val short = frame(0x80, 0x00, 0x00)
        val fc = FrameControl(short[0], short[1])
        val a = FrameAddresses.parse(short, fc)
        assertNull(a.receiver)
    }
}

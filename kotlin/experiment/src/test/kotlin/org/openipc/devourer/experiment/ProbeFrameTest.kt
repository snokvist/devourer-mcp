package org.openipc.devourer.experiment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.SyntheticFrames

/**
 * The probe tag and matcher must agree on where the counter lives.
 *
 * A drift here is silent: the wrong offset reads payload filler as a sequence
 * number and a burst that delivered perfectly reports one frame received and
 * the rest as duplicates. The QoS form moves every offset by two bytes, which
 * is exactly the kind of change that drifts.
 */
class ProbeFrameTest {

    private val runId = 0x0DDBA6

    @Test
    fun `a plain probe is a data frame at the documented offsets`() {
        val f = ProbeFrame.build(runId, 200)
        assertEquals(0x08, f[0].toInt() and 0xff)
        assertEquals(0x00, f[1].toInt() and 0xff)
        assertEquals(24, ProbeFrame.headerBytes(null))
        assertEquals(32, ProbeFrame.sequenceOffset(null))
        assertEquals(36, ProbeFrame.minBytes(null))
    }

    @Test
    fun `a QoS probe is FC 0x88 with the TID in its control field`() {
        val f = ProbeFrame.build(runId, 200, qosTid = 5)
        assertEquals(0x88, f[0].toInt() and 0xff, "QoS Data")
        assertEquals(0x00, f[1].toInt() and 0xff, "no to-DS/from-DS")
        assertEquals(5, f[24].toInt() and 0x0f, "TID in QoS control")
        assertEquals(0, f[25].toInt() and 0xff, "ack policy stays normal")
        assertEquals(26, ProbeFrame.headerBytes(5))
        assertEquals(34, ProbeFrame.sequenceOffset(5))
        assertEquals(38, ProbeFrame.minBytes(5))
    }

    @Test
    fun `a plain probe round-trips through its own offsets`() {
        val f = ProbeFrame.build(runId, 200)
        stamp(f, ProbeFrame.SEQUENCE_OFFSET, 9)
        assertEquals(9, ProbeFrame.sequenceOf(record(f), runId))
        assertNull(ProbeFrame.sequenceOf(record(f), runId + 1), "the run id is part of the match")
    }

    @Test
    fun `a QoS probe round-trips through the QoS offsets`() {
        val f = ProbeFrame.build(runId, 200, qosTid = 5)
        stamp(f, ProbeFrame.QOS_SEQUENCE_OFFSET, 11)
        // Reading the tag at the plain offsets would hit the QoS control field
        // and report no match at all — hence this asserts a value, not a type.
        assertEquals(11, ProbeFrame.sequenceOf(record(f), runId))
    }

    @Test
    fun `a QoS frame carrying HT control is rejected, not misread`() {
        val f = ProbeFrame.build(runId, 200, qosTid = 0)
        stamp(f, ProbeFrame.QOS_SEQUENCE_OFFSET, 3)
        f[1] = 0x80.toByte() // order bit: the header grows by the 4-byte HT control field
        assertNull(ProbeFrame.sequenceOf(record(f), runId))
    }

    @Test
    fun `a 4-address QoS frame is read at its own header length`() {
        // to-DS + from-DS (WDS): Address4 sits between sequence control and the
        // QoS control field, so the tag and counter are 6 bytes further along
        // than in our own 3-address frames.
        val three = ProbeFrame.build(runId, 200, qosTid = 2)
        val wds = ByteArray(three.size + 6)
        three.copyInto(wds, 0, 0, 24)
        three.copyInto(wds, 30, 24, three.size)
        wds[1] = 0x03 // to-DS | from-DS
        stamp(wds, 32 + 8, 7)
        assertEquals(7, ProbeFrame.sequenceOf(record(wds), runId))

        // Setting the DS bits without inserting Address4 points the parser six
        // bytes past the tag; it rejects the frame instead of counting it at
        // the wrong offset.
        val lying = ProbeFrame.build(runId, 200, qosTid = 2)
        lying[1] = 0x03
        stamp(lying, ProbeFrame.QOS_SEQUENCE_OFFSET, 5)
        assertNull(ProbeFrame.sequenceOf(record(lying), runId))
    }

    @Test
    fun `the QoS minimum length reflects the two extra header bytes`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ProbeFrame.build(runId, totalBytes = 37, qosTid = 0)
        }
        assertTrue("38" in e.message!!, e.message)
    }

    @Test
    fun `a TID outside the A-MPDU space is refused`() {
        assertFailsWith<IllegalArgumentException> { ProbeFrame.build(runId, qosTid = 8) }
        assertFailsWith<IllegalArgumentException> { ProbeFrame.build(runId, qosTid = -1) }
    }

    private fun record(frame: ByteArray): FrameRecord = SyntheticFrames.record(payload = frame)

    private fun stamp(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xff).toByte()
        b[at + 1] = ((v shr 8) and 0xff).toByte()
        b[at + 2] = ((v shr 16) and 0xff).toByte()
        b[at + 3] = ((v shr 24) and 0xff).toByte()
    }
}

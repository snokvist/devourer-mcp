package org.openipc.devourer.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.FrameType

/** Builds synthetic frames so the whole analysis path is testable with no radio. */
internal object Fixtures {

    fun record(
        index: Long = 0,
        hostNanos: Long = index * 1_000_000L,
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
        b.putInt(24, 1)
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
    fun beacon(bssid: Int = 0xAA): ByteArray {
        val f = ByteArray(40)
        f[0] = 0x80.toByte() // mgmt / beacon
        f[1] = 0x00
        for (i in 0 until 6) f[4 + i] = 0xFF.toByte() // addr1 broadcast
        for (i in 0 until 6) f[10 + i] = (0x10 + i).toByte() // addr2 transmitter
        for (i in 0 until 6) f[16 + i] = (bssid + i).toByte() // addr3 bssid
        return f
    }

    fun qosData(retry: Boolean): ByteArray {
        val f = ByteArray(34)
        f[0] = 0x88.toByte() // data / qos-data
        f[1] = if (retry) 0x08 else 0x00
        for (i in 0 until 6) f[4 + i] = (0x20 + i).toByte()
        for (i in 0 until 6) f[10 + i] = (0x30 + i).toByte()
        for (i in 0 until 6) f[16 + i] = (0x40 + i).toByte()
        return f
    }
}

class CaptureStoreTest {

    @Test
    fun `summary counts frame kinds retries and errors`() {
        val store = CaptureStore("t1", radioLabel = "RTL8812A", channelLabel = "ch6/20MHz")
        repeat(5) { store.add(Fixtures.record(index = it.toLong())) }
        repeat(3) {
            store.add(
                Fixtures.record(
                    index = (10 + it).toLong(),
                    payload = Fixtures.qosData(retry = true),
                    aggregated = true,
                ),
            )
        }
        store.add(Fixtures.record(index = 20, crcError = true))

        val s = store.summarizeAll()
        assertEquals(9, s.frames)
        assertEquals(3, s.retries)
        assertEquals(3, s.aggregated)
        assertEquals(1, s.crcErrors)
        assertEquals(6, s.byKind["mgmt/beacon"])
        assertEquals(3, s.byKind["data/qos-data"])
        assertEquals("ch6/20MHz", s.channel)
    }

    @Test
    fun `rssi means exclude unreported chains rather than averaging in zeros`() {
        // A 2-chain radio leaves chains C and D at zero. Folding those into the
        // mean would report a link ~35 dB weaker than it is.
        val store = CaptureStore("t2")
        store.add(Fixtures.record(index = 0, rssi = intArrayOf(70, 68, 0, 0)))
        store.add(Fixtures.record(index = 1, rssi = intArrayOf(72, 66, 0, 0)))

        val s = store.summarizeAll()
        assertEquals(2, s.rssi.size, "only the two reporting chains should appear")
        assertEquals(71.0, s.rssi[0].mean)
        assertEquals(67.0, s.rssi[1].mean)
    }

    @Test
    fun `frames that report no signal at all do not drag the mean`() {
        val store = CaptureStore("t3")
        store.add(Fixtures.record(index = 0, rssi = intArrayOf(70, 0, 0, 0)))
        store.add(Fixtures.record(index = 1, rssi = intArrayOf(0, 0, 0, 0))) // no PHY status
        val s = store.summarizeAll()
        assertEquals(1, s.rssi.size)
        assertEquals(1, s.rssi[0].count, "the unreported frame must not be counted")
        assertEquals(70.0, s.rssi[0].mean)
    }

    @Test
    fun `eviction is counted so a lossy window is never mistaken for a clean one`() {
        val store = CaptureStore("t4", capacity = 10)
        repeat(25) { store.add(Fixtures.record(index = it.toLong())) }
        assertEquals(10, store.size)
        assertEquals(15, store.droppedOldest)
        assertEquals(15, store.summarizeAll().evictedBeforeWindow)
        assertEquals(25, store.totalAdmitted)
    }

    @Test
    fun `raw frame bytes stay reachable by index`() {
        val store = CaptureStore("t5", capacity = 5)
        repeat(3) { store.add(Fixtures.record(index = it.toLong())) }
        val f = store.frame(1)
        assertNotNull(f)
        assertEquals(0x80.toByte(), f.record.payload[0])
        // Evicted frames report absent rather than returning something else.
        repeat(10) { store.add(Fixtures.record(index = (100 + it).toLong())) }
        assertNull(store.frame(1))
    }

    @Test
    fun `queries filter by kind transmitter and error state`() {
        val store = CaptureStore("t6")
        repeat(4) { store.add(Fixtures.record(index = it.toLong())) }
        store.add(Fixtures.record(index = 9, payload = Fixtures.qosData(retry = true)))
        store.add(Fixtures.record(index = 10, crcError = true))

        assertEquals(5, store.query(FrameQuery(type = FrameType.MANAGEMENT), limit = 100).size)
        assertEquals(1, store.query(FrameQuery(kind = "data/qos-data"), limit = 100).size)
        assertEquals(1, store.query(FrameQuery(crcError = true), limit = 100).size)
        assertEquals(1, store.query(FrameQuery(retry = true), limit = 100).size)
        assertEquals(
            5,
            store.query(FrameQuery(transmitter = "10:11:12:13:14:15"), limit = 100).size,
        )
    }

    @Test
    fun `a query that matches nothing summarizes as empty rather than failing`() {
        val store = CaptureStore("t7")
        store.add(Fixtures.record())
        val s = store.summarize(FrameQuery(kind = "mgmt/deauth"))
        assertEquals(0, s.frames)
        assertTrue(s.note.isNotBlank())
    }

    @Test
    fun `chain stats never exceed the radio's real chain count`() {
        // The regression this pins: on a 2T2R RTL8812A the snr[2] and snr[3]
        // slots carry stream CSI, not path C/D SNR, and they are routinely
        // non-zero. A reader that counts non-zero slots reports four chains on
        // a two-chain radio and publishes CSI values as SNR measurements.
        val store = CaptureStore("chains")
        repeat(5) {
            store.add(
                Fixtures.record(
                    index = it.toLong(),
                    rxChains = 2,
                    rssi = intArrayOf(70, 68, 0, 0),
                    snr = intArrayOf(30, 28, 19, 6), // [2],[3] are CSI, not SNR
                ),
            )
        }
        val s = store.summarizeAll()
        assertEquals(2, s.rssi.size)
        assertEquals(2, s.snr.size, "chains C and D do not exist on a 2T2R part")
        assertEquals(listOf("chainA", "chainB"), s.snr.map { it.chain })
    }

    @Test
    fun `a real four-chain radio keeps all four`() {
        val store = CaptureStore("chains4")
        store.add(
            Fixtures.record(
                rxChains = 4,
                rssi = intArrayOf(70, 68, 66, 64),
                snr = intArrayOf(30, 28, 26, 24),
            ),
        )
        val s = store.summarizeAll()
        assertEquals(4, s.rssi.size)
        assertEquals(4, s.snr.size)
    }

    @Test
    fun `chain balance flags a chain far below its neighbours`() {
        val store = CaptureStore("bal")
        repeat(10) {
            store.add(
                Fixtures.record(
                    index = it.toLong(),
                    rxChains = 2,
                    rssi = intArrayOf(70, 40, 0, 0), // B ~30 down: loose antenna
                ),
            )
        }
        val b = analyseChainBalance(store.summarizeAll())
        assertEquals(2, b.chains)
        assertEquals(listOf("chainB"), b.weakChains)
        assertTrue(b.verdict.contains("missing, loose or blocked"))
        assertTrue(b.caveat.contains("never antenna connectors"))
    }

    @Test
    fun `balanced chains are not flagged`() {
        val store = CaptureStore("bal2")
        repeat(10) {
            store.add(Fixtures.record(index = it.toLong(), rxChains = 2, rssi = intArrayOf(70, 67, 0, 0)))
        }
        val b = analyseChainBalance(store.summarizeAll())
        assertTrue(b.weakChains.isEmpty())
        assertTrue(b.verdict.contains("consistent with every chain having a working antenna"))
    }

    @Test
    fun `chain balance says nothing when no frame reported a signal`() {
        val store = CaptureStore("bal3")
        store.add(Fixtures.record(rssi = intArrayOf(0, 0, 0, 0)))
        val b = analyseChainBalance(store.summarizeAll())
        assertEquals(0, b.chains)
        assertTrue(b.verdict.contains("nothing can be said"))
    }
}

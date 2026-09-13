package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.protocol.TxReceipt

/**
 * Per-frame TX receipts. They exist only if the session asked for them at
 * open, so "not enabled" must be a distinct answer from "enabled, nothing
 * arrived" — the same absence-versus-zero distinction the rest of the tree
 * keeps.
 */
class TxReceiptsTest {

    private fun realtek() = FakeRadios(listOf(FakeRadios.realtek(1)))

    @Test
    fun `receipts read disabled when the session was not opened for them`() = runTest {
        val radios = realtek()
        radios.open(bus = 1, address = 4) // txReport defaults to 0

        val receipts = radios.txReceipts(1)

        assertFalse(receipts.enabled)
        assertNotNull(receipts.why)
        assertTrue(receipts.receipts.isEmpty())
    }

    @Test
    fun `a session opened with tx_report buffers and drains its receipts`() = runTest {
        val radios = realtek()
        radios.open(bus = 1, address = 4, txReport = 1)
        radios.receipts[1] = mutableListOf(
            TxReceipt(tMs = 10, state = 0, ok = true, retries = 0, finalRate = 0x0b),
            TxReceipt(tMs = 11, state = 1, ok = false, retries = 7, finalRate = 0x03),
        )

        val first = radios.txReceipts(1)
        assertTrue(first.enabled)
        assertEquals(1, first.sampling)
        assertEquals(2, first.receipts.size)
        assertEquals(7, first.receipts[1].retries)

        // Drained by default.
        assertTrue(radios.txReceipts(1).receipts.isEmpty())
    }

    @Test
    fun `clear false peeks without emptying the ring`() = runTest {
        val radios = realtek()
        radios.open(bus = 1, address = 4, txReport = 4)
        radios.receipts[1] = mutableListOf(TxReceipt(state = 0, ok = true))

        val peeked = radios.txReceipts(1, clear = false)

        assertEquals(1, peeked.receipts.size)
        assertEquals(1, radios.txReceipts(1, clear = false).receipts.size)
    }
}

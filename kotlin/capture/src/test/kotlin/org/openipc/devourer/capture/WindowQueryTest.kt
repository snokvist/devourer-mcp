package org.openipc.devourer.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trailing-window queries, which the scratchpad's live metrics depend on
 * entirely.
 *
 * A live "mean RSSI" computed over the whole run stops responding to the air
 * long before the run ends, so every capture metric a scratchpad samples is a
 * windowed query. If the window filter is wrong in either direction the failure
 * is silent: too strict and every series stays empty with no error, too loose
 * and the dashboard shows a lifetime average while claiming to be live.
 */
class WindowQueryTest {

    private fun storeSpanningSeconds(seconds: Int): Pair<CaptureStore, Long> {
        val store = CaptureStore("window")
        val now = System.currentTimeMillis()
        // One frame per 100ms over the span, timestamped in real epoch nanos —
        // the same clock the bridge stamps with (CLOCK_REALTIME).
        var i = 0L
        for (msAgo in (seconds * 1000) downTo 0 step 100) {
            store.add(
                Fixtures.record(
                    index = i++,
                    hostNanos = (now - msAgo) * 1_000_000L,
                    rssi = intArrayOf(70, 68, 0, 0),
                ),
            )
        }
        return store to now
    }

    @Test
    fun `a trailing window selects only recent frames`() {
        val (store, now) = storeSpanningSeconds(10)
        assertEquals(101, store.size)

        val twoSeconds = store.summarize(
            FrameQuery(sinceHostNanos = (now - 2_000) * 1_000_000L),
        )
        // 2s at one frame per 100ms, inclusive of both ends.
        assertTrue(twoSeconds.frames in 20..22, "expected ~21 frames, got ${twoSeconds.frames}")
        assertTrue(twoSeconds.rssi.isNotEmpty(), "windowed summary must still report RSSI")
        assertEquals(70.0, twoSeconds.rssi[0].mean)
    }

    @Test
    fun `a window wider than the capture returns everything`() {
        val (store, now) = storeSpanningSeconds(5)
        val all = store.summarize(FrameQuery(sinceHostNanos = (now - 60_000) * 1_000_000L))
        assertEquals(store.size, all.frames)
    }

    @Test
    fun `a window in the future returns nothing rather than everything`() {
        // The failure mode worth guarding: an off-by-a-million in the ms-to-ns
        // conversion makes the cutoff astronomically large, every frame is
        // excluded, and the caller sees empty series with no error to explain it.
        val (store, now) = storeSpanningSeconds(5)
        val none = store.summarize(FrameQuery(sinceHostNanos = (now + 60_000) * 1_000_000L))
        assertEquals(0, none.frames)
        assertTrue(none.note.isNotBlank(), "an empty window must say so")
    }

    @Test
    fun `nanosecond scale is not confused with milliseconds`() {
        // Passing a millisecond value where nanoseconds are expected makes the
        // cutoff ~1e6 times too small, so it matches everything and the window
        // silently becomes the whole capture.
        val (store, now) = storeSpanningSeconds(10)
        val wrongScale = store.summarize(FrameQuery(sinceHostNanos = now - 2_000))
        assertEquals(store.size, wrongScale.frames, "a millisecond cutoff matches every frame")

        val rightScale = store.summarize(FrameQuery(sinceHostNanos = (now - 2_000) * 1_000_000L))
        assertTrue(rightScale.frames < wrongScale.frames)
    }
}

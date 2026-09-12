package org.openipc.devourer.capture

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.openipc.devourer.protocol.SyntheticFrames as Fixtures

/**
 * The analysis path's cost, pinned as behaviour rather than left to chance.
 *
 * `capture_summary` is called by every scratchpad capture source on its sample
 * period — default 500 ms, floor 50 ms — and `antenna_check` calls it with no
 * window at all. It used to copy the whole 200k ring twice before narrowing,
 * and re-derive every frame's addresses with a `java.util.Formatter` per byte:
 * roughly 4.8M Formatter allocations per call on a full ring. That is not a
 * micro-optimisation; it is the difference between a live view and a host that
 * stalls its own capture. These tests fail if that shape comes back.
 */
class HotPathTest {

    private fun fill(store: CaptureStore, n: Int, spacingMs: Long = 1): Long {
        val t0 = System.currentTimeMillis() - n * spacingMs
        repeat(n) { i ->
            store.add(
                Fixtures.record(
                    index = i.toLong(),
                    hostNanos = (t0 + i * spacingMs) * 1_000_000L,
                    payload = if (i % 3 == 0) Fixtures.beacon() else Fixtures.qosData(retry = i % 7 == 0),
                ),
            )
        }
        return t0
    }

    @Test
    fun `a windowed summary over a large ring stays cheap`() {
        val store = CaptureStore("hot", capacity = 200_000)
        fill(store, 150_000)
        val since = (System.currentTimeMillis() - 2_000) * 1_000_000L

        // Warm the JIT so the measurement is of the algorithm, not of startup.
        repeat(3) { store.summarize(FrameQuery(sinceHostNanos = since)) }

        val windowed = store.summarize(FrameQuery(sinceHostNanos = since))
        assertTrue(windowed.frames in 1..4_000, "window should be ~2000 frames, got ${windowed.frames}")

        val elapsed = measureTimeMillis {
            repeat(20) { store.summarize(FrameQuery(sinceHostNanos = since)) }
        }
        // 20 calls at a scratchpad's 50ms floor is one second of sampling. If a
        // windowed summary scales with ring size rather than window size, this
        // is seconds, not milliseconds.
        assertTrue(
            elapsed < 1_000,
            "20 windowed summaries over a 150k ring took ${elapsed}ms — the window " +
                "narrowing is not happening before the copy",
        )
    }

    @Test
    fun `the window contains exactly the frames inside it`() {
        val store = CaptureStore("win", capacity = 10_000)
        val t0 = fill(store, 5_000, spacingMs = 1)
        // Frames are 1ms apart, so a 1000ms window holds ~1000 of them.
        val cutoff = t0 + 4_000
        val s = store.summarize(FrameQuery(sinceHostNanos = cutoff * 1_000_000L))
        assertTrue(s.frames in 990..1_010, "expected ~1000 frames in the window, got ${s.frames}")
    }

    @Test
    fun `frame lookup is by index arithmetic, not a scan`() {
        val store = CaptureStore("idx", capacity = 100_000)
        fill(store, 100_000)
        val elapsed = measureTimeMillis {
            repeat(10_000) { store.frame((it * 7).toLong()) }
        }
        assertTrue(elapsed < 500, "10k lookups took ${elapsed}ms — frame() is still scanning")

        assertEquals(42L, store.frame(42)!!.index)
        assertNull(store.frame(-1))
        assertNull(store.frame(999_999))
    }

    @Test
    fun `frame lookup stays correct after eviction shifts the base index`() {
        val store = CaptureStore("evict", capacity = 100)
        fill(store, 250)
        // Indices 0..149 are gone; 150..249 remain.
        assertNull(store.frame(149))
        assertNotNull(store.frame(150))
        assertEquals(200L, store.frame(200)!!.index)
        assertNotNull(store.frame(249))
        assertNull(store.frame(250))
    }

    @Test
    fun `newest-first query stops at the limit instead of materialising every match`() {
        val store = CaptureStore("q", capacity = 200_000)
        fill(store, 150_000)
        val elapsed = measureTimeMillis {
            repeat(50) { store.query(FrameQuery(newestFirst = true), limit = 20) }
        }
        assertTrue(elapsed < 1_000, "50 limit-20 queries took ${elapsed}ms")

        val rows = store.query(FrameQuery(newestFirst = true), limit = 20)
        assertEquals(20, rows.size)
        // Newest first: descending index.
        assertTrue(rows[0].index > rows[19].index)
    }

    @Test
    fun `cached addresses match what a fresh parse would produce`() {
        // The cache is only safe if it is identical to the old per-call parse.
        val store = CaptureStore("cache")
        store.add(Fixtures.record(payload = Fixtures.beacon(bssid = 0x5C)))
        store.add(Fixtures.record(index = 1, payload = Fixtures.qosData(retry = true)))
        store.snapshot().forEach { f ->
            val fc = f.record.frameControl
            val fresh = fc?.let {
                org.openipc.devourer.protocol.FrameAddresses.parse(f.record.payload, it)
            }
            assertEquals(fresh, f.addresses)
            assertEquals(fc, f.frameControl)
        }
    }
}

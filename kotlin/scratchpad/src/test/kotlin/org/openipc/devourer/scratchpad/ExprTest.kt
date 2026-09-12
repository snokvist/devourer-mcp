package org.openipc.devourer.scratchpad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExprTest {

    private val scope = mapOf(
        "frames" to 1000.0,
        "retries" to 250.0,
        "rssi" to 62.5,
        "poll.latency_ms" to 12.0,
    )

    private fun eval(e: String) = Expr.evaluate(e, scope)

    @Test
    fun `arithmetic and precedence`() {
        assertEquals(7.0, eval("1 + 2 * 3"))
        assertEquals(9.0, eval("(1 + 2) * 3"))
        assertEquals(8.0, eval("2 ^ 3"))
        assertEquals(-6.0, eval("-2 * 3"))
        assertEquals(1.0, eval("7 % 3"))
    }

    @Test
    fun `series read from scope, including dotted names`() {
        assertEquals(1000.0, eval("frames"))
        assertEquals(12.0, eval("poll.latency_ms"))
        assertEquals(25.0, eval("retries / frames * 100"))
    }

    @Test
    fun `division by zero yields NaN rather than throwing`() {
        // A live dashboard whose denominator is momentarily zero should show a
        // gap for one sample, not end the run.
        assertTrue(eval("1 / 0").isNaN())
        assertTrue(eval("frames / (frames - frames)").isNaN())
    }

    @Test
    fun `ratio guards the common rate case`() {
        assertEquals(0.25, eval("ratio(retries, frames)"))
        assertEquals(0.0, eval("ratio(retries, 0)"))
    }

    @Test
    fun `functions and constants`() {
        assertEquals(1000.0, eval("max(frames, retries)"))
        assertEquals(250.0, eval("min(frames, retries)"))
        assertEquals(5.0, eval("sqrt(25)"))
        assertEquals(63.0, eval("round(rssi + 0.5)"))
        assertEquals(10.0, eval("clamp(50, 0, 10)"))
    }

    @Test
    fun `an unknown series is NaN, not an error`() {
        // A source that has not produced its first sample is normal at start-up.
        assertTrue(eval("never_sampled").isNaN())
    }

    @Test
    fun `an unknown function is an error, because it is a typo not a race`() {
        val e = assertFailsWith<ExprException> { eval("frobnicate(1)") }
        assertTrue(e.message!!.contains("unknown function"))
    }

    @Test
    fun `malformed input is rejected with position`() {
        assertFailsWith<ExprException> { eval("1 +") }
        assertFailsWith<ExprException> { eval("(1 + 2") }
        assertFailsWith<ExprException> { eval("1 2") }
        val e = assertFailsWith<ExprException> { eval("1 $ 2") }
        assertTrue(e.message!!.contains("offset"))
    }

    @Test
    fun `deep nesting is capped rather than overflowing the stack`() {
        // A pathological expression must not take the server down with it.
        val deep = "(".repeat(200) + "1" + ")".repeat(200)
        val e = assertFailsWith<ExprException> { eval(deep) }
        assertTrue(e.message!!.contains("too deeply"))
    }

    @Test
    fun `references lists series but not functions or constants`() {
        val refs = Expr.references("max(retries, 1) / frames * pi")
        assertEquals(setOf("retries", "frames"), refs)
    }
}

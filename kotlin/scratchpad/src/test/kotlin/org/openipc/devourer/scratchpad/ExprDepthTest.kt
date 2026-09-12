package org.openipc.devourer.scratchpad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The depth cap on every recursive path, and the sign handling it nearly broke.
 *
 * The cap originally guarded only parentheses; `unary` and `power` recursed
 * freely, and 50 000 unary operators overflowed the stack in milliseconds. The
 * fix threads a guard through all three — and the first attempt at it consumed
 * a `-` in a dead branch, turning `-5` into `5`. Both halves are pinned here:
 * a deep expression must be refused, and an ordinary signed one must still be
 * arithmetically right.
 */
class ExprDepthTest {

    private fun eval(e: String) = Expr.evaluate(e, mapOf("x" to 10.0))

    @Test
    fun `signs still work — the guard must not swallow an operator`() {
        assertEquals(-5.0, eval("-5"))
        assertEquals(5.0, eval("--5"))
        assertEquals(-5.0, eval("---5"))
        assertEquals(5.0, eval("+5"))
        assertEquals(-10.0, eval("-x"))
        assertEquals(3.0, eval("8 + -5"))
        assertEquals(13.0, eval("8 - -5"))
        assertEquals(-50.0, eval("-5 * x"))
        assertEquals(0.5, eval("-5 / -x"))
    }

    @Test
    fun `power is still right-associative and still signs correctly`() {
        assertEquals(512.0, eval("2 ^ 3 ^ 2"))   // 2^(3^2), not (2^3)^2 = 64
        assertEquals(0.5, eval("2 ^ -1"))
        assertEquals(-8.0, eval("-2 ^ 3"))       // -(2^3)
    }

    @Test
    fun `a deep unary chain is refused instead of overflowing the stack`() {
        val deep = "-".repeat(50_000) + "1"
        val e = assertFailsWith<ExprException> { eval(deep) }
        assertTrue(e.message!!.contains("too deeply"), "got: ${e.message}")
    }

    @Test
    fun `a deep plus chain is refused`() {
        val e = assertFailsWith<ExprException> { eval("+".repeat(50_000) + "1") }
        assertTrue(e.message!!.contains("too deeply"))
    }

    @Test
    fun `a deep power chain is refused`() {
        val e = assertFailsWith<ExprException> { eval("2^".repeat(50_000) + "1") }
        assertTrue(e.message!!.contains("too deeply"))
    }

    @Test
    fun `deep parentheses are still refused`() {
        val e = assertFailsWith<ExprException> { eval("(".repeat(200) + "1" + ")".repeat(200)) }
        assertTrue(e.message!!.contains("too deeply"))
    }

    @Test
    fun `the guard unwinds, so a wide expression is not mistaken for a deep one`() {
        // 200 sequential parenthesised terms nest only one level at a time. If
        // `depth` were incremented without a matching decrement, this would be
        // refused — and every realistic dashboard expression with it.
        val wide = (1..200).joinToString(" + ") { "($it)" }
        assertEquals((1..200).sum().toDouble(), eval(wide))
    }
}

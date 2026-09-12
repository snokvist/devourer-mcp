package org.openipc.devourer.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The safety boundary, which for a long time existed only as prose.
 *
 * The failure this prevents is specific and it happened: carrier-sense disable
 * — a radio transmitting without listening — was reachable as an automatic
 * retry inside `characterize_run`, with no argument a caller could set to
 * decline it, while the docs claimed it was gated.
 */
class SafetyLevelTest {

    @Test
    fun `an absent or unparseable level fails closed to NORMAL`() {
        // A typo in "experimental" must never widen access.
        assertEquals(SafetyLevel.NORMAL, SafetyLevel.parse(null))
        assertEquals(SafetyLevel.NORMAL, SafetyLevel.parse(""))
        assertEquals(SafetyLevel.NORMAL, SafetyLevel.parse("experimentl"))
        assertEquals(SafetyLevel.NORMAL, SafetyLevel.parse("root"))
    }

    @Test
    fun `levels parse case-insensitively and tolerate surrounding space`() {
        assertEquals(SafetyLevel.EXPERIMENTAL, SafetyLevel.parse("experimental"))
        assertEquals(SafetyLevel.EXPERIMENTAL, SafetyLevel.parse("EXPERIMENTAL"))
        assertEquals(SafetyLevel.DEVELOPER, SafetyLevel.parse(" Developer "))
    }

    @Test
    fun `a higher level permits a lower requirement, never the reverse`() {
        assertTrue(SafetyLevel.DEVELOPER.permits(SafetyLevel.EXPERIMENTAL))
        assertTrue(SafetyLevel.EXPERIMENTAL.permits(SafetyLevel.NORMAL))
        assertFalse(SafetyLevel.NORMAL.permits(SafetyLevel.EXPERIMENTAL))
        assertFalse(SafetyLevel.EXPERIMENTAL.permits(SafetyLevel.DEVELOPER))
    }

    @Test
    fun `a refusal names the level to pass, so the caller can act on it`() {
        val e = assertFailsWith<SafetyLevelException> {
            SafetyLevelException.require(
                "disabling carrier sense", SafetyLevel.EXPERIMENTAL, SafetyLevel.NORMAL,
            )
        }
        assertTrue(e.message!!.contains("disabling carrier sense"))
        assertTrue(e.message!!.contains("EXPERIMENTAL"))
        assertTrue(e.message!!.contains("safety_level=\"experimental\""))
    }

    @Test
    fun `granting the required level lets it through`() {
        SafetyLevelException.require("x", SafetyLevel.EXPERIMENTAL, SafetyLevel.EXPERIMENTAL)
        SafetyLevelException.require("x", SafetyLevel.EXPERIMENTAL, SafetyLevel.DEVELOPER)
        SafetyLevelException.require("x", SafetyLevel.NORMAL, SafetyLevel.NORMAL)
    }
}

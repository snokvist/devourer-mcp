package org.openipc.devourer.scratchpad

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * A small arithmetic expression language for [Computed] series.
 *
 * Total and bounded by construction. There are no variables but the series in
 * scope, no assignment, no calls but the fixed function table below, no loops,
 * and no way to name anything outside the expression. Evaluating one cannot
 * fail to terminate, cannot allocate unboundedly, and cannot reach anything.
 *
 * It exists because the alternative for `retries / frames * 100` is embedding a
 * scripting engine, and that would put model-authored code inside the process
 * holding the radios. A hundred lines of recursive descent is the cheaper half
 * of that trade.
 *
 * Grammar:
 *   expr    := term (('+' | '-') term)*
 *   term    := unary (('*' | '/' | '%') unary)*
 *   unary   := ('-' | '+')? power
 *   power   := primary ('^' unary)?
 *   primary := number | identifier | func '(' args ')' | '(' expr ')'
 *
 * Division by zero yields NaN rather than throwing: a live dashboard whose
 * denominator is momentarily zero should show a gap, not stop the run.
 */
public object Expr {

    public fun evaluate(source: String, scope: Map<String, Double>): Double =
        Parser(source, scope).parse()

    /** Series names an expression reads, for validation before a run starts. */
    public fun references(source: String): Set<String> {
        val out = mutableSetOf<String>()
        val m = Regex("[A-Za-z_][A-Za-z0-9_.]*").findAll(source)
        for (t in m) {
            val name = t.value
            if (name !in FUNCTIONS && name !in CONSTANTS) out += name
        }
        return out
    }

    private val CONSTANTS = mapOf("pi" to Math.PI, "e" to Math.E, "nan" to Double.NaN)

    private val FUNCTIONS: Map<String, (List<Double>) -> Double> = mapOf(
        "min" to { a -> a.minOrNull() ?: Double.NaN },
        "max" to { a -> a.maxOrNull() ?: Double.NaN },
        "abs" to { a -> abs(a.first()) },
        "sqrt" to { a -> sqrt(a.first()) },
        "ln" to { a -> ln(a.first()) },
        "log10" to { a -> log10(a.first()) },
        "round" to { a -> a.first().roundToLong().toDouble() },
        "floor" to { a -> kotlin.math.floor(a.first()) },
        "ceil" to { a -> kotlin.math.ceil(a.first()) },
        "clamp" to { a -> min(max(a[0], a[1]), a[2]) },
        "avg" to { a -> if (a.isEmpty()) Double.NaN else a.average() },
        /* Guarded divide: the shape every rate in a dashboard wants. */
        "ratio" to { a -> if (a[1] == 0.0) 0.0 else a[0] / a[1] },
    )

    private class Parser(private val s: String, private val scope: Map<String, Double>) {
        private var i = 0
        private var depth = 0

        fun parse(): Double {
            val v = expr()
            skipWs()
            if (i != s.length) fail("unexpected '${s[i]}'")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                when {
                    eat('+') -> v += term()
                    eat('-') -> v -= term()
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                skipWs()
                when {
                    eat('*') -> v *= unary()
                    eat('/') -> {
                        val d = unary()
                        v = if (d == 0.0) Double.NaN else v / d
                    }
                    eat('%') -> {
                        val d = unary()
                        v = if (d == 0.0) Double.NaN else v % d
                    }
                    else -> return v
                }
            }
        }

        /*
         * The depth cap guards THREE recursive paths, not one. It originally
         * covered only parentheses, so `----…----1` and `2^2^2^…` recursed
         * freely: 50 000 unary operators overflowed the stack in 4 ms. It was
         * contained only accidentally, by a `runCatching` upstream that happens
         * to catch Throwable — and the run then repeated the blow-up every
         * sample period for the rest of its life.
         */
        private fun unary(): Double {
            skipWs()
            // `eat` has a side effect, so it must be called exactly once per
            // branch test — a stray extra `eat('-')` silently swallows the sign
            // and turns -5 into 5.
            return when {
                eat('-') -> guarded { -unary() }
                eat('+') -> guarded { unary() }
                else -> power()
            }
        }

        private fun power(): Double {
            val base = primary()
            skipWs()
            return if (eat('^')) guarded { base.pow(unary()) } else base
        }

        private inline fun <T> guarded(body: () -> T): T {
            if (++depth > MAX_DEPTH) fail("expression nested too deeply")
            try {
                return body()
            } finally {
                depth--
            }
        }

        private fun primary(): Double {
            skipWs()
            if (i >= s.length) fail("expression ended early")

            if (eat('(')) {
                // Depth cap: a pathological expression must not recurse the
                // parser into a stack overflow that takes the server with it.
                if (++depth > MAX_DEPTH) fail("expression nested too deeply")
                val v = expr()
                skipWs()
                if (!eat(')')) fail("expected ')'")
                depth--
                return v
            }

            val c = s[i]
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') return identifierOrCall()
            fail("unexpected '$c'")
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }

        private fun identifierOrCall(): Double {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val name = s.substring(start, i)
            skipWs()
            if (eat('(')) {
                val fn = FUNCTIONS[name] ?: fail("unknown function '$name'")
                val args = mutableListOf<Double>()
                skipWs()
                if (!eat(')')) {
                    if (++depth > MAX_DEPTH) fail("expression nested too deeply")
                    do {
                        args += expr()
                        skipWs()
                    } while (eat(','))
                    if (!eat(')')) fail("expected ')' closing $name(")
                    depth--
                }
                if (args.isEmpty() && name != "avg") fail("$name() needs arguments")
                return runCatching { fn(args) }.getOrElse {
                    fail("$name() called with ${args.size} argument(s)")
                }
            }
            CONSTANTS[name]?.let { return it }
            // An unknown series reads as NaN rather than throwing: a source that
            // has not produced its first sample yet is normal at start-up, and
            // killing the run for it would make every dashboard racy.
            return scope[name] ?: Double.NaN
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun eat(c: Char): Boolean {
            skipWs()
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private fun fail(why: String): Nothing =
            throw ExprException("$why (at offset $i in \"$s\")")

        companion object {
            const val MAX_DEPTH = 32
        }
    }
}

public class ExprException(message: String) : IllegalArgumentException(message)

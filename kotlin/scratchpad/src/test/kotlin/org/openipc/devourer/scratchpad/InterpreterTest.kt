package org.openipc.devourer.scratchpad

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The execution half of the capability boundary.
 *
 * [SandboxTest] covers what a grant allows; this covers what the interpreter
 * does when a program reaches past it during a run. A program that names an
 * experiment it was not granted must stop, not poll and log the same denial
 * every period — `CapabilityDeniedException` is load-bearing, not advisory.
 */
class InterpreterTest {

    private class Host : ScratchpadHost {
        override suspend fun captureMetric(
            captureId: String,
            metric: String,
            windowMs: Long,
            kind: String?,
            transmitter: String?,
        ): Double? = null

        override suspend fun radioMetric(session: Int, metric: String): Double? = null

        override suspend fun experimentMetric(
            experimentId: String,
            metric: String,
            point: String?,
            pointIndex: Int?,
            aggregate: String?,
        ): Double? = 1.0
    }

    @Test
    fun `an experiment id outside the grant stops the run instead of polling`() = runTest {
        val program = ScratchpadProgram(
            name = "peek",
            capabilities = listOf("timer", "experiment.read"),
            sources = listOf(
                ExperimentMetricSource(
                    id = "d",
                    experimentId = "exp-2",
                    metric = "delivery_ratio",
                    everyMs = 50,
                ),
            ),
            durationMs = 5_000,
        )
        val grant = CapabilityGrant(
            capabilities = setOf("timer", "experiment.read"),
            experimentIds = setOf("exp-1"),
        )
        val state = RunState()

        Interpreter(Host(), grant).run(program, state, backgroundScope)

        assertTrue(state.stopRequested, "a denied capability must stop the run")
        assertTrue(
            state.logs().any { "stopping the run" in it },
            "the denial must be named in the log: ${state.logs()}",
        )
    }
}

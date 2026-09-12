package org.openipc.devourer.experiment

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The registry that makes a running experiment something other than a blocked
 * tool call.
 *
 * Without it an experiment is invisible while it runs and unstoppable once
 * started: the only bound is the duration ceiling, and the only way to learn
 * anything is to wait. That is the wrong shape for a thing that transmits.
 *
 * There is no `Experiment` interface here on purpose. The seam a new
 * experiment needs is a function — it is handed a [ProgressSink] and returns
 * an [ExperimentResult] — and an interface with one implementation would add
 * a type without adding a capability.
 */
public class ExperimentRunner(scope: CoroutineScope) {

    /**
     * Experiments are siblings, not dependents.
     *
     * A supervisor of the caller's scope rather than the caller's scope
     * itself: an experiment that throws must not cancel the MCP server's
     * other work, and a caller should not have to know that to use this
     * safely.
     */
    private val scope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
    )

    @Serializable
    public enum class Phase { RUNNING, DONE, FAILED, CANCELLED }

    @Serializable
    public data class Progress(
        val id: String,
        val kind: String,
        @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
        val phase: Phase,
        @SerialName("total_points") val totalPoints: Int,
        @SerialName("completed_points") val completedPoints: Int,
        @SerialName("current_point") val currentPoint: String? = null,
        @SerialName("elapsed_ms") val elapsedMs: Long = 0,
        /** Present on FAILED, and on CANCELLED to say who cancelled it. */
        val message: String? = null,
    )

    /** What a running experiment reports as it goes. */
    public interface ProgressSink {
        public val id: String
        public fun startingPoint(label: String)
        public fun finishedPoint()
    }

    private class Run(
        override val id: String,
        val kind: String,
        val startedAtEpochMs: Long,
        val totalPoints: Int,
    ) : ProgressSink {
        val completed = AtomicInteger(0)
        val current = AtomicReference<String?>(null)
        val phase = AtomicReference(Phase.RUNNING)
        val message = AtomicReference<String?>(null)
        var finishedAtEpochMs: Long = 0
        lateinit var job: Deferred<ExperimentResult>

        override fun startingPoint(label: String) {
            current.set(label)
        }

        override fun finishedPoint() {
            completed.incrementAndGet()
            current.set(null)
        }

        fun snapshot() = Progress(
            id = id,
            kind = kind,
            startedAtEpochMs = startedAtEpochMs,
            phase = phase.get(),
            totalPoints = totalPoints,
            completedPoints = completed.get(),
            currentPoint = current.get(),
            elapsedMs = (finishedAtEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis()) -
                startedAtEpochMs,
            message = message.get(),
        )
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val results = ConcurrentHashMap<String, ExperimentResult>()

    /**
     * Starts an experiment and returns its handle immediately.
     *
     * The caller normally awaits it in the same tool call; the handle exists
     * so that something else — the dashboard's stop button, another tool
     * call — can reach it while it runs.
     */
    public fun start(
        id: String,
        kind: String,
        totalPoints: Int,
        body: suspend (ProgressSink) -> ExperimentResult,
    ): String {
        prune()
        val run = Run(id, kind, System.currentTimeMillis(), totalPoints)
        runs[id] = run
        run.job = scope.async {
            try {
                val result = body(run)
                run.phase.set(Phase.DONE)
                results[id] = result
                result
            } catch (e: CancellationException) {
                run.phase.set(Phase.CANCELLED)
                run.message.set(e.message ?: "cancelled")
                throw e
            } catch (e: Throwable) {
                run.phase.set(Phase.FAILED)
                run.message.set("${e::class.simpleName}: ${e.message}")
                throw e
            } finally {
                run.finishedAtEpochMs = System.currentTimeMillis()
                run.current.set(null)
                // Also on completion, not only on the next start: a session
                // that runs 40 experiments and then stops would otherwise
                // hold every result until it happened to start another.
                prune()
            }
        }
        return id
    }

    /** Waits for a started experiment. Throws whatever the experiment threw. */
    public suspend fun await(id: String): ExperimentResult {
        val run = runs[id] ?: throw ExperimentException("no experiment '$id'")
        return run.job.await()
    }

    /**
     * Stops a run.
     *
     * Cancellation reaches the experiment at its next suspension point, which
     * in practice is between transmitted frames or during the settle wait —
     * so a burst already handed to the bridge finishes airing. The cleanup
     * path still runs: monitors stop and carrier sense is restored. Returns
     * false if there was no such run, or it had already finished.
     */
    public fun cancel(id: String, reason: String = "cancelled by operator"): Boolean {
        val run = runs[id] ?: return false
        if (run.phase.get() != Phase.RUNNING) return false
        run.message.set(reason)
        run.job.cancel(CancellationException(reason))
        return true
    }

    public fun progress(id: String): Progress? = runs[id]?.snapshot()

    /** Newest first. Finished runs stay listed until [MAX_RETAINED] is exceeded. */
    public fun all(): List<Progress> =
        runs.values.map { it.snapshot() }.sortedByDescending { it.startedAtEpochMs }

    public fun result(id: String): ExperimentResult? = results[id]

    public fun running(): List<Progress> = all().filter { it.phase == Phase.RUNNING }

    public fun cancelAll(reason: String = "shutting down"): Int =
        runs.keys.count { cancel(it, reason) }

    /** Drops the oldest finished runs. Running ones are never evicted. */
    private fun prune() {
        val finished = runs.values
            .filter { it.phase.get() != Phase.RUNNING }
            .sortedBy { it.finishedAtEpochMs }
        (finished.size - MAX_RETAINED).takeIf { it > 0 }?.let { excess ->
            finished.take(excess).forEach {
                runs.remove(it.id)
                results.remove(it.id)
            }
        }
    }

    public companion object {
        public const val MAX_RETAINED: Int = 32
    }
}

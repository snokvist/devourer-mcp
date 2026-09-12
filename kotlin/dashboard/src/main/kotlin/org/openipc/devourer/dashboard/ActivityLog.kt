package org.openipc.devourer.dashboard

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every tool call the model made, in order, with what it asked for and what
 * came back.
 *
 * This is the part of the dashboard that answers "what is it actually doing?"
 * — the question you have while a model is three minutes into building
 * something and the transcript has scrolled past. An experiment's progress bar
 * tells you a burst is running; this tells you which tool asked for it and
 * with what arguments.
 *
 * Arguments are summarized, never stored whole: a `tx_send` carries a hex
 * frame and a scratchpad carries an entire program, and a feed that held those
 * would grow without bound and be unreadable besides.
 */
public class ActivityLog(private val capacity: Int = 400) {

    @Serializable
    public data class Entry(
        /** Monotonic within a session; the page polls for everything after N. */
        val seq: Long,
        @SerialName("at_epoch_ms") val atEpochMs: Long,
        val tool: String,
        /** A one-line rendering of the arguments, truncated. */
        val arguments: String,
        @SerialName("duration_ms") val durationMs: Long,
        val ok: Boolean,
        /** First line of the failure, when it failed. */
        val error: String? = null,
    )

    private val seq = AtomicLong(0)
    private val entries = ArrayDeque<Entry>()

    public fun record(
        tool: String,
        arguments: String,
        durationMs: Long,
        ok: Boolean,
        error: String? = null,
    ): Entry {
        val entry = Entry(
            seq = seq.incrementAndGet(),
            atEpochMs = System.currentTimeMillis(),
            tool = tool,
            arguments = arguments.take(MAX_ARGUMENT_CHARS),
            durationMs = durationMs,
            ok = ok,
            error = error?.lineSequence()?.firstOrNull()?.take(MAX_ERROR_CHARS),
        )
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
        return entry
    }

    /** Everything after [after], oldest first. */
    public fun since(after: Long): List<Entry> = synchronized(entries) {
        entries.filter { it.seq > after }
    }

    public fun latest(): Long = seq.get()

    public fun all(): List<Entry> = synchronized(entries) { entries.toList() }

    private companion object {
        const val MAX_ARGUMENT_CHARS = 300
        const val MAX_ERROR_CHARS = 300
    }
}

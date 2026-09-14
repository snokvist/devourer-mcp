package org.openipc.devourer.capture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.radio.Radios
import org.openipc.devourer.radio.requireChannelSupported

/**
 * Ties a monitoring radio session to a local [CaptureStore].
 *
 * This is where "MCP is the control plane, not the data plane" is actually
 * enforced: the frame flow is consumed here, inside the server process, and
 * only summaries, query results and references ever cross the MCP boundary.
 */
public class CaptureService(
    private val radios: Radios,
    private val scope: CoroutineScope,
) {
    private val captures = ConcurrentHashMap<String, ActiveCapture>()
    private val counter = AtomicInteger(0)

    /**
     * Sessions that have had a monitor before, so a restart can be labelled.
     *
     * The RTL8822B (jaguar2) arms an RX loop that never receives when a monitor
     * is started on a session that monitored earlier in the same session
     * (measured; tools/monitor-restart-test.py). Nothing in the API surfaces
     * that, so a restart says so on the capture instead of presenting an empty
     * capture as a quiet channel. Kept across stops and discards on purpose:
     * it is session history, not capture state.
     */
    private val monitoredBefore = ConcurrentHashMap.newKeySet<Int>()

    public data class ActiveCapture(
        val id: String,
        val session: Int,
        val channel: ChannelSpec,
        val store: CaptureStore,
        val job: Job,
        val radioLabel: String,
        val startedAtEpochMs: Long = System.currentTimeMillis(),
        val capabilityNote: String? = null,
    )

    public fun get(id: String): ActiveCapture? = captures[id]

    public fun all(): List<ActiveCapture> = captures.values.sortedBy { it.id }

    /**
     * Starts monitoring and begins collecting into a new store.
     *
     * Any capability caveat the radio layer raised — for example, tuning to a
     * channel outside the TX-power characterized range — is carried on the
     * capture itself, so every later summary can repeat it rather than leaving
     * a caller to remember a warning printed once at start.
     */
    public suspend fun start(
        session: Int,
        channel: ChannelSpec,
        capacity: Int = 200_000,
    ): ActiveCapture {
        val radio = radios.describe(session)
        val restartNote = if (!monitoredBefore.add(session)) {
            "this session had a monitor earlier. On the RTL8822B (jaguar2) a restarted " +
                "monitor has been measured to receive nothing; if this capture stays empty, " +
                "radio_close + radio_open restores reception."
        } else {
            null
        }
        val note = listOfNotNull(requireChannelSupported(radio, channel), restartNote)
            .joinToString("\n")
            .ifEmpty { null }
        radios.startMonitor(session, channel)

        val id = "cap-${counter.incrementAndGet()}"
        val store = CaptureStore(
            id = id,
            capacity = capacity,
            radioLabel = radio.label,
            channelLabel = channel.toString(),
        )
        val job = scope.launch {
            radios.frames(session).collect { store.add(it) }
        }
        val capture = ActiveCapture(
            id = id,
            session = session,
            channel = channel,
            store = store,
            job = job,
            radioLabel = radio.label,
            capabilityNote = note,
        )
        captures[id] = capture
        return capture
    }

    /**
     * Stops the radio and the collector, but keeps the store.
     *
     * Deliberate: a stopped capture is still evidence, and discarding it on
     * stop would mean every analysis had to be decided on before the radio was
     * released. [discard] is the explicit way to let it go.
     */
    public suspend fun stop(id: String): ActiveCapture? {
        val capture = captures[id] ?: return null
        runCatching { radios.stopMonitor(capture.session) }
        // Join, not just cancel. Closing the frame flow releases the client
        // socket, but the bridge replaces its per-session sink only on the
        // NEXT attach — so what joining buys is narrower and still worth it:
        // a not-yet-landed attach from this capture cannot land after a later
        // user's attach and silently take the session's frames back. Bounded,
        // because a collector wedged in a socket read must not hold the
        // control path; on timeout the collector is cancelled without a join.
        val joined = withTimeoutOrNull(STOP_JOIN_MS) { capture.job.cancelAndJoin() }
        if (joined == null) capture.job.cancel()
        return capture
    }

    public suspend fun discard(id: String): Boolean {
        stop(id)
        return captures.remove(id) != null
    }

    public suspend fun stopAll() {
        for (c in captures.values) runCatching { stop(c.id) }
    }

    private companion object {
        /**
         * How long [stop] waits for the collector to release its frame
         * socket. Bounded because a collector wedged in a blocking socket
         * read must not hold the control path; on timeout the collector is
         * cancelled without a join, which is what [stop] always did.
         */
        const val STOP_JOIN_MS = 5_000L
    }
}

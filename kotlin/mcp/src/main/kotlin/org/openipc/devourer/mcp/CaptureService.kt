package org.openipc.devourer.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.openipc.devourer.capture.CaptureStore
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.radio.RadioManager

/**
 * Ties a monitoring radio session to a local [CaptureStore].
 *
 * This is where "MCP is the control plane, not the data plane" is actually
 * enforced: the frame flow is consumed here, inside the server process, and
 * only summaries, query results and references ever cross the MCP boundary.
 */
public class CaptureService(
    private val radios: RadioManager,
    private val scope: CoroutineScope,
) {
    private val captures = ConcurrentHashMap<String, ActiveCapture>()
    private val counter = AtomicInteger(0)

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
        val note = radios.requireChannelSupported(radio, channel)
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
        capture.job.cancel()
        return capture
    }

    public suspend fun discard(id: String): Boolean {
        stop(id)
        return captures.remove(id) != null
    }

    public suspend fun stopAll() {
        for (c in captures.values) runCatching { stop(c.id) }
    }
}

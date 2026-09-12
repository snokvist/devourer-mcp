package org.openipc.devourer.radio

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.protocol.BridgeCallException
import org.openipc.devourer.protocol.BridgeJson
import org.openipc.devourer.protocol.BridgeResponse
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.HelloResult

/**
 * Talks to `devourer-bridge` over its Unix socket.
 *
 * One persistent control connection with requests serialized by a mutex — the
 * bridge answers one request per connection at a time, so pipelining would
 * only move the queue. Frame streams get their own connections, which is the
 * point of the split: a capture running at a thousand frames a second cannot
 * delay a `monitor.stop`.
 *
 * Blocking socket I/O is confined to [ioDispatcher]; nothing here blocks a
 * caller's thread.
 */
public class BridgeClient(
    private val socketPath: Path,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val address = UnixDomainSocketAddress.of(socketPath)
    private val callMutex = Mutex()
    private var control: SocketChannel? = null
    private var nextId = 1L

    /**
     * Verified once at connect: the bridge's protocol major must match, and its
     * record size must equal what [FrameRecord] decodes. A mismatch means every
     * frame would be silently misread, so it is a hard failure.
     */
    public var hello: HelloResult? = null
        private set

    public suspend fun connect(): HelloResult = callMutex.withLock {
        if (control == null) control = withContext(ioDispatcher) { openChannel() }
        val result = callLocked("hello", buildJsonObject {})
        val h = BridgeJson.format.decodeFromJsonElement(HelloResult.serializer(), result)
        require(h.protocol.major == PROTOCOL_MAJOR) {
            "bridge speaks protocol ${h.protocol.major}.${h.protocol.minor}, " +
                "this client speaks $PROTOCOL_MAJOR.x — incompatible"
        }
        require(h.frameRecordBytes == FrameRecord.HEADER_BYTES) {
            "bridge frame record is ${h.frameRecordBytes} bytes, this client decodes " +
                "${FrameRecord.HEADER_BYTES}. Every frame would be misread; refusing to attach."
        }
        hello = h
        h
    }

    /** Sends one op and returns its `result`, or throws [BridgeCallException]. */
    public suspend fun call(op: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        callMutex.withLock {
            if (control == null) control = withContext(ioDispatcher) { openChannel() }
            callLocked(op, params)
        }

    private suspend fun callLocked(op: String, params: JsonObject): JsonObject {
        val id = nextId++
        val request = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("op", JsonPrimitive(op))
            params.forEach { (k, v) -> put(k, v) }
        }
        val line = withContext(ioDispatcher) {
            val ch = control ?: throw IOException("control channel closed")
            writeLine(ch, BridgeJson.format.encodeToString(JsonObject.serializer(), request))
            readLine(ch) ?: throw IOException("bridge closed the control connection during $op")
        }
        val response = BridgeJson.format.decodeFromString(BridgeResponse.serializer(), line)
        if (!response.ok) {
            val e = response.error
            throw BridgeCallException(e?.code ?: "unknown", e?.message ?: "no message", op)
        }
        return response.result ?: JsonObject(emptyMap())
    }

    /**
     * Frames for [session], as they arrive.
     *
     * A fresh connection per stream; closing the flow closes it, which makes
     * the bridge drop its sink and reset the buffer. Backpressure here is real:
     * a slow collector fills the bridge's buffer and it starts dropping whole
     * records, which `monitor.stats` reports. That is deliberate — blocking the
     * bridge's RX callback instead would wedge the adapter.
     */
    public fun frames(session: Int): Flow<FrameRecord> = callbackFlow {
        val ch = openChannel()
        try {
            writeLine(ch, """{"attach":$session}""")
            val ack = readLine(ch) ?: throw IOException("bridge closed before acknowledging attach")
            val parsed = BridgeJson.format.decodeFromString(BridgeResponse.serializer(), ack)
            if (!parsed.ok) {
                val e = parsed.error
                throw BridgeCallException(e?.code ?: "unknown", e?.message ?: "", "attach")
            }

            val header = ByteBuffer.allocate(FrameRecord.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            while (true) {
                if (!readFully(ch, header)) break
                header.flip()
                val frameLength = header.getInt(28)
                require(frameLength in 0..MAX_FRAME_BYTES) {
                    "frame length $frameLength out of range — stream desynchronized"
                }
                val payload = ByteArray(frameLength)
                if (frameLength > 0) {
                    val body = ByteBuffer.wrap(payload)
                    if (!readFully(ch, body)) break
                }
                /*
                 * send, not trySend. The old code threw the moment the
                 * channel was full, which killed the whole capture — at
                 * 3300 frames/s the default ~64 slots are 20ms of slack, so
                 * any GC pause or slow consumer ended the stream and the
                 * caller saw a broken flow rather than a drop count.
                 *
                 * Suspending here is the policy the rest of the system is
                 * already built around: the reader stops draining the
                 * socket, the bridge's own buffer fills, and the bridge
                 * drops whole records and reports them in monitor.stats.
                 * That path is bounded and counted. Blocking the bridge's RX
                 * callback is what must never happen, and it cannot happen
                 * from here: the bridge's writer is non-blocking and its
                 * buffer swap is O(1).
                 */
                send(FrameRecord.decode(header, payload))
                header.clear()
            }
            close()
        } catch (e: Throwable) {
            close(e)
        }
        awaitClose { runCatching { ch.close() } }
    }
        /*
         * Fuses with the callbackFlow channel rather than adding a second
         * one. Sized as time, not as frames: ~0.6s of slack at the 3300
         * frames/s this bench has measured, which absorbs a GC pause or a
         * summarize() without reaching the bridge, and costs a few MB.
         */
        .buffer(FRAME_BUFFER_SLOTS)
        .flowOn(ioDispatcher)

    private fun openChannel(): SocketChannel {
        if (!socketPath.toFile().exists()) {
            throw IOException(
                "no bridge socket at $socketPath — start it with tools/host/bridge-ctl.sh start",
            )
        }
        return SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(address) }
    }

    override fun close() {
        runCatching { control?.close() }
        control = null
    }

    private companion object {
        const val PROTOCOL_MAJOR = 1

        /**
         * A sanity bound on a single frame, not an 802.11 limit. The largest
         * legal A-MSDU is 11454 bytes; anything far past that means the reader
         * lost alignment and is interpreting payload as a length.
         */
        const val MAX_FRAME_BYTES = 65535

        /** Frames held locally before backpressure reaches the bridge. */
        const val FRAME_BUFFER_SLOTS = 2048

        fun writeLine(ch: SocketChannel, line: String) {
            val buf = ByteBuffer.wrap((line + "\n").toByteArray())
            while (buf.hasRemaining()) ch.write(buf)
        }

        /** Reads one newline-terminated line, or null at end of stream. */
        fun readLine(ch: SocketChannel): String? {
            val out = StringBuilder()
            val one = ByteBuffer.allocate(1)
            while (true) {
                one.clear()
                if (ch.read(one) <= 0) return out.takeIf { it.isNotEmpty() }?.toString()
                val c = one.get(0).toInt().toChar()
                if (c == '\n') return out.toString()
                out.append(c)
                if (out.length > MAX_LINE) throw IOException("control line exceeded $MAX_LINE bytes")
            }
        }

        const val MAX_LINE = 1 shl 22

        /** Fills [buf] completely; false only at a clean end of stream. */
        fun readFully(ch: SocketChannel, buf: ByteBuffer): Boolean {
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) return false
            }
            return true
        }
    }
}

/** Convenience for the common `result["field"]` string reads. */
public fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

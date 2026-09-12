package org.openipc.devourer.radio

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.Identification
import org.openipc.devourer.protocol.MonitorStats
import org.openipc.devourer.protocol.RadioListResult
import org.openipc.devourer.protocol.SyntheticFrames
import org.openipc.devourer.protocol.UsbDevice

/**
 * A [Radios] with no hardware behind it.
 *
 * Exists so that the layers that reason *about* radios — experiments,
 * characterization, the MCP tools — can be tested at all. Before it, those
 * three modules had zero tests between them, for the simple reason that
 * constructing any of them required an adapter on a shared bench.
 *
 * Three properties it keeps deliberately:
 *
 * - **It enforces the real gates.** Capability checks go through the
 *   production [requireChannelSupported] and safety checks through the
 *   production [RadioSafety]. A fake with its own copy of a gate passes its
 *   own tests while the real one rots.
 * - **It refuses what the bridge refuses.** An unknown session, monitoring a
 *   radio that is not open, transmitting more than the bound — all throw here
 *   too, so a test cannot pass against behaviour the hardware would reject.
 * - **It records what was asked of it.** [calls] is the ordered op log, which
 *   is how a test pins "carrier sense was restored even though the run threw"
 *   without needing a radio to observe.
 *
 * It does NOT model the air. Delivery is whatever [onProbe] injects; there is
 * no propagation model here and there should not be one, because a simulated
 * delivery ratio is exactly the kind of number this project refuses to let
 * look like evidence.
 */
public class FakeRadios(radios: List<OpenRadio> = emptyList()) : Radios {

    /*
     * Concurrent, not plain maps. Production collects frames on whatever
     * dispatcher the caller's scope carries, so `frames()` and `inject()` run
     * on different threads from the control calls. Two racing getOrPut calls
     * on a HashMap produced two different flows for one session, and the
     * collector then waited forever on the one nothing was published to.
     */
    private val open: MutableMap<Int, OpenRadio> =
        ConcurrentHashMap(radios.associateBy { it.session })
    private val streams = ConcurrentHashMap<Int, MutableSharedFlow<FrameRecord>>()
    private val counters = ConcurrentHashMap<Int, Counters>()

    /*
     * computeIfAbsent, not getOrPut. Production collects frames on whatever
     * dispatcher the caller's scope carries, so `frames()` runs on one thread
     * while `inject()` and `awaitCollector()` run on another. Kotlin's
     * getOrPut is get-then-put and is NOT atomic even on a ConcurrentHashMap:
     * two racing callers each built a flow, the second overwrote the first,
     * and the collector then sat subscribed to an object nothing published
     * to. It reproduced as a test that passed alone and hung in a suite.
     */
    private fun stream(session: Int): MutableSharedFlow<FrameRecord> =
        streams.computeIfAbsent(session) { MutableSharedFlow(extraBufferCapacity = 1024) }

    private fun counters(session: Int): Counters =
        counters.computeIfAbsent(session) { Counters() }

    /** Every op, in order, as `op(args)`. */
    public val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** Devices [list] reports. Defaults to the open radios' own USB devices. */
    public var listed: List<UsbDevice> = radios.map { it.device }

    /**
     * What a probe burst does.
     *
     * Called with the burst description; whatever it injects via [inject] is
     * what the receiving session's collector sees. The default accepts every
     * frame and delivers none — the honest null hypothesis, and the state a
     * dead antenna produces.
     */
    public var onProbe: (suspend (Probe) -> TxOutcome)? = null

    /**
     * Called at the end of [startMonitor].
     *
     * The hook a test needs to make one channel busy and another quiet, which
     * is the distinction characterization exists to draw. It must not suspend
     * waiting for a collector: the caller launches its collector *after*
     * startMonitor returns, so a hook that waited here would deadlock. Launch
     * instead.
     */
    public var onMonitorStart: ((Int, ChannelSpec) -> Unit)? = null

    /** Set to throw from the next call to the named op, once. */
    public var failNext: MutableMap<String, Throwable> = mutableMapOf()

    public data class Probe(
        val session: Int,
        val frameHex: String,
        val mode: String,
        val count: Int,
        val intervalUs: Int,
        val sequenceOffset: Int,
    )

    /** What the TX path reports back. Accepting is not transmitting. */
    public data class TxOutcome(
        val accepted: Int,
        val elapsedNs: Long = 0,
        val lateFrames: Int = 0,
        val maxLateUs: Long = 0,
    )

    private class Counters {
        var frames: Long = 0
        var bytes: Long = 0
        var dropped: Long = 0
        var txSent: Long = 0
        var monitoring: Boolean = false
    }

    // --- test-side controls ------------------------------------------------

    /** Delivers one frame to whoever is collecting [session]. */
    public suspend fun inject(session: Int, record: FrameRecord) {
        val stream = stream(session)
        counters(session).let {
            it.frames++
            it.bytes += record.frameLength
        }
        stream.emit(record)
    }

    /**
     * Plain beacons on [session], for tests that just need traffic.
     *
     * Timestamped against the wall clock because the bridge stamps
     * `host_ns` from CLOCK_REALTIME, and every trailing-window query is
     * computed against epoch time. Frames dated 1970 would fall outside every
     * window a live view asks for.
     */
    public suspend fun injectAmbient(
        session: Int,
        count: Int,
        rssi: Int = 70,
        atEpochMs: Long = System.currentTimeMillis(),
    ) {
        repeat(count) { i ->
            inject(
                session,
                SyntheticFrames.record(
                    index = i.toLong(),
                    hostNanos = atEpochMs * 1_000_000L + i * 1_000L,
                    session = session,
                    rssi = intArrayOf(rssi, rssi - 2, 0, 0),
                ),
            )
        }
    }

    /**
     * Suspends until something is collecting [session]'s frames.
     *
     * A shared flow drops what it emits with no subscriber, so injecting the
     * instant a burst is requested races the collector the caller launched a
     * moment earlier. Waiting here makes the fake deterministic instead of
     * making every test that uses it flaky.
     */
    public suspend fun awaitCollector(session: Int) {
        stream(session)
            .subscriptionCount.first { it > 0 }
    }

    public fun isMonitoring(session: Int): Boolean = counters[session]?.monitoring == true

    public fun carrierSenseDisabled(session: Int): Boolean =
        radio(session).state.carrierSenseDisabled

    /** Replaces a radio's reported state, the way a re-read from the bridge would. */
    public fun update(session: Int, transform: (OpenRadio) -> OpenRadio) {
        open[session] = transform(radio(session))
    }

    // --- Radios ------------------------------------------------------------

    override suspend fun connect() {
        record("connect")
    }

    override suspend fun list(includeAll: Boolean): RadioListResult {
        record("list", "all=$includeAll")
        return RadioListResult(devices = listed)
    }

    override suspend fun open(bus: Int, address: Int, reset: Boolean): OpenRadio {
        record("open", "$bus/$address")
        return open.values.firstOrNull { it.device.bus == bus && it.device.address == address }
            ?: throw IllegalArgumentException("no fake radio at bus $bus address $address")
    }

    override suspend fun describe(session: Int): OpenRadio {
        record("describe", "$session")
        return radio(session)
    }

    override suspend fun close(session: Int) {
        record("close", "$session")
        radio(session)
        open.remove(session)
    }

    override suspend fun sessions(): JsonObject = buildJsonObject {
        record("sessions")
        put("count", JsonPrimitive(open.size))
    }

    override suspend fun startMonitor(session: Int, channel: ChannelSpec): JsonObject {
        record("startMonitor", "$session,$channel")
        requireChannelSupported(radio(session), channel)
        counters(session).monitoring = true
        // Starting a monitor tunes the radio, and the bridge reports the
        // channel back on the next describe. A fake that left it unset would
        // let a caller pass while showing an untuned radio as monitoring.
        update(session) {
            it.copy(
                state = it.state.copy(broughtUp = true, monitoring = true),
                channel = ChannelInfo(
                    channel.channel, channel.width.mhz, channel.offset, channel.band,
                ),
            )
        }
        onMonitorStart?.invoke(session, channel)
        return buildJsonObject { put("ok", JsonPrimitive(true)) }
    }

    override suspend fun stopMonitor(session: Int): JsonObject {
        record("stopMonitor", "$session")
        counters(session).monitoring = false
        update(session) { it.copy(state = it.state.copy(monitoring = false)) }
        return buildJsonObject { put("ok", JsonPrimitive(true)) }
    }

    override suspend fun stats(session: Int): MonitorStats {
        record("stats", "$session")
        radio(session)
        val c = counters(session)
        return MonitorStats(
            session = session,
            frames = c.frames,
            bytes = c.bytes,
            dropped = c.dropped,
            writeErrors = 0,
            txSent = c.txSent,
            txFailed = 0,
            sinkAttached = c.monitoring,
        )
    }

    override suspend fun retune(session: Int, channel: ChannelSpec): JsonObject {
        record("retune", "$session,$channel")
        requireChannelSupported(radio(session), channel)
        update(session) {
            it.copy(
                state = it.state.copy(broughtUp = true),
                channel = ChannelInfo(channel.channel, channel.width.mhz, channel.offset, channel.band),
            )
        }
        return buildJsonObject { put("ok", JsonPrimitive(true)) }
    }

    override fun frames(session: Int): Flow<FrameRecord> =
        stream(session)

    override suspend fun sendProbe(
        session: Int,
        frameHex: String,
        mode: String,
        count: Int,
        intervalUs: Int,
        sequenceOffset: Int,
    ): JsonObject {
        record("sendProbe", "$session,$mode,n=$count")
        require(count in 1..RadioManager.MAX_TX_COUNT) {
            "count must be 1..${RadioManager.MAX_TX_COUNT}"
        }
        radio(session)
        val probe = Probe(session, frameHex, mode, count, intervalUs, sequenceOffset)
        val outcome = onProbe?.invoke(probe) ?: TxOutcome(accepted = count)
        counters(session).txSent += outcome.accepted
        return buildJsonObject {
            put("sent", JsonPrimitive(outcome.accepted))
            put("elapsed_ns", JsonPrimitive(outcome.elapsedNs))
            put("late_frames", JsonPrimitive(outcome.lateFrames))
            put("max_late_us", JsonPrimitive(outcome.maxLateUs))
        }
    }

    override suspend fun setCarrierSense(
        session: Int,
        enabled: Boolean,
        safety: SafetyLevel,
    ): JsonObject {
        record("setCarrierSense", "$session,enabled=$enabled,$safety")
        RadioSafety.gateCarrierSense(enabled, safety)
        update(session) { it.copy(state = it.state.copy(carrierSenseDisabled = !enabled)) }
        return buildJsonObject { put("ok", JsonPrimitive(true)) }
    }

    override suspend fun txStats(session: Int): JsonObject {
        record("txStats", "$session")
        radio(session)
        val c = counters(session)
        return buildJsonObject {
            put("submitted", JsonPrimitive(c.txSent))
            put("failed", JsonPrimitive(0))
        }
    }

    override suspend fun activeRxPaths(session: Int): JsonObject {
        record("activeRxPaths", "$session")
        // Not implemented on MediaTek. Reporting "unsupported" rather than
        // "0 chains active" is the distinction the whole project turns on.
        throw CapabilityException(
            "report active RX paths", radio(session).label, "the backend does not implement it",
        )
    }

    override suspend fun sendFrame(
        session: Int,
        frameHex: String,
        count: Int,
        safety: SafetyLevel,
    ): JsonObject {
        record("sendFrame", "$session,n=$count")
        RadioSafety.gateRawFrame(safety)
        require(count in 1..RadioManager.MAX_TX_COUNT) { "count out of range" }
        require(frameHex.isNotBlank() && frameHex.length % 2 == 0) { "frame_hex must be hex" }
        counters(session).txSent += count
        return buildJsonObject { put("sent", JsonPrimitive(count)) }
    }

    private fun radio(session: Int): OpenRadio =
        open[session] ?: throw IllegalArgumentException("no_session: $session")

    private fun record(op: String, args: String = "") {
        calls += if (args.isEmpty()) op else "$op($args)"
        failNext.remove(op)?.let { throw it }
    }

    public companion object {
        /**
         * A 2T2R Realtek, modelled on the bench RTL8812AU.
         *
         * Reports a permanent MAC from construction and publishes a
         * characterized TX-power range — both true of the real part, and both
         * things the MediaTek does not do.
         */
        public fun realtek(session: Int = 1, bus: Int = 1, address: Int = 4): OpenRadio = OpenRadio(
            session = session,
            device = UsbDevice(
                bus = bus,
                address = address,
                portPath = "$bus.1",
                usbId = "0bda:8812",
                vid = 0x0bda,
                pid = 0x8812,
                product = "802.11n NIC",
                identification = Identification.PROBE_REQUIRED,
                backend = "jaguar1",
                backendCompiledIn = true,
            ),
            capabilities = RadioCapabilities(
                supported = true,
                chip = "RTL8812AU",
                marketingNames = "RTL8812AU",
                generation = "jaguar1",
                txChains = 2,
                rxChains = 2,
                bandwidthsMhz = listOf("5", "10", "20", "40", "80"),
                tune2g4 = BandRange(true, 2412, 2484),
                tune5g = BandRange(true, 5180, 5825),
                characterized2g4 = BandRange(true, 2412, 2484),
                characterized5g = BandRange(true, 5180, 5825),
                tx = TxCapabilities(supported = true, spatialStreams = 2, maxWidthMhz = 80),
                txPower = TxPowerCapabilities(
                    supported = true, indexMax = 63, stepQdb = 2, stepMeasured = false,
                ),
                features = mapOf("per_chain_rssi" to true, "hw_rx_timestamp" to true),
            ),
            // Locally administered and invented: a fixture should not carry a
            // real adapter's address into a public repository.
            permanentMac = "02:0d:b0:c4:a7:6a",
        )

        /**
         * A 2T2R MediaTek, modelled on the bench MT7612U.
         *
         * No permanent MAC: MediaTek reads its EEPROM during chip init, so the
         * address does not exist until bring-up. No RX timestamp either. Both
         * absences are real and both are load-bearing in tests.
         */
        public fun mediatek(session: Int = 2, bus: Int = 2, address: Int = 3): OpenRadio = OpenRadio(
            session = session,
            device = UsbDevice(
                bus = bus,
                address = address,
                portPath = "$bus.2",
                usbId = "0e8d:7612",
                vid = 0x0e8d,
                pid = 0x7612,
                product = "Wireless",
                serial = "000000000",
                identification = Identification.USB_ID,
                backend = "mt7612u",
                backendCompiledIn = true,
            ),
            capabilities = RadioCapabilities(
                supported = true,
                chip = "MT7612U",
                marketingNames = "MT7612U",
                generation = "mt7612u",
                txChains = 2,
                rxChains = 2,
                bandwidthsMhz = listOf("20", "40", "80"),
                tune2g4 = BandRange(true, 2412, 2484),
                tune5g = BandRange(true, 5180, 5825),
                tx = TxCapabilities(supported = true, spatialStreams = 2, maxWidthMhz = 80),
                features = mapOf("per_chain_rssi" to true),
            ),
            permanentMac = null,
        )

        /** An adapter devourer enumerated but has no backend for. */
        public fun unsupported(session: Int = 9): OpenRadio = OpenRadio(
            session = session,
            device = UsbDevice(
                bus = 3, address = 2, portPath = "3.1", usbId = "1234:5678",
                vid = 0x1234, pid = 0x5678,
            ),
            capabilities = RadioCapabilities(supported = false),
        )
    }
}

package org.openipc.devourer.radio

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.openipc.devourer.protocol.AckResponder
import org.openipc.devourer.protocol.AmpduMode
import org.openipc.devourer.protocol.AmpduState
import org.openipc.devourer.protocol.Beacon
import org.openipc.devourer.protocol.CcaGates
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.FrameRecord
import org.openipc.devourer.protocol.Identification
import org.openipc.devourer.protocol.MonitorStats
import org.openipc.devourer.protocol.RadioListResult
import org.openipc.devourer.protocol.RxEnergy
import org.openipc.devourer.protocol.RxGain
import org.openipc.devourer.protocol.RxQuality
import org.openipc.devourer.protocol.SyntheticFrames
import org.openipc.devourer.protocol.Thermal
import org.openipc.devourer.protocol.Tsf
import org.openipc.devourer.protocol.TxReceipt
import org.openipc.devourer.protocol.TxReceipts
import org.openipc.devourer.protocol.TxPower
import org.openipc.devourer.protocol.TxRateDiffs
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

    /** What [rxEnergy] reports for a Realtek session, keyed by session. */
    public val energy: MutableMap<Int, RxEnergy> = ConcurrentHashMap()

    /**
     * Per-CHANNEL energy, keyed by channel number. Takes precedence over
     * [energy] when the radio is tuned to that channel, so a spectrum scan can
     * be given a different reading per bin.
     */
    public val energyByChannel: MutableMap<Int, RxEnergy> = ConcurrentHashMap()

    /** What [rxQuality] reports for a Realtek session, keyed by session. */
    public val quality: MutableMap<Int, RxQuality> = ConcurrentHashMap()

    /** What [thermal] reports for a Realtek session, keyed by session. */
    public val thermalBySession: MutableMap<Int, Thermal> = ConcurrentHashMap()

    /**
     * What [rxGain] reports for a Realtek session, keyed by session.
     *
     * Seeded lazily so a test that only needs "the gain is readable" does not
     * have to build a struct; absent means the modelled default below.
     */
    public val gain: MutableMap<Int, RxGain> = ConcurrentHashMap()

    /** The TX-power knob state per session, keyed by session. */
    public val txPowerState: MutableMap<Int, TxPower> = ConcurrentHashMap()

    /** The split gates per session: `first` = primary CCA, `second` = EDCCA. */
    private val gates: MutableMap<Int, Pair<Boolean, Boolean>> = ConcurrentHashMap()

    private fun defaultTxPower(session: Int, radio: OpenRadio): TxPower {
        val caps = radio.capabilities.txPower
        return TxPower(
            session = session,
            supported = true,
            indexMax = caps.indexMax,
            stepQdb = caps.stepQdb,
            stepMeasured = caps.stepMeasured,
            offsetMinQdb = caps.offsetMinQdb,
            offsetMaxQdb = caps.offsetMaxQdb,
            rateDiffs = caps.rateDiffs,
            rateDiffsHwTable = true,
            rateDiffsMeasured = caps.rateDiffsMeasured,
            valid = radio.state.broughtUp,
            flatIndex = -1,
            offsetQdb = 0,
            offsetSteps = 0,
            saturatedLow = false,
            saturatedHigh = false,
            cckIndex = 0x30,
            ofdmIndex = 0x2e,
            mcs7Index = 0x34,
            hwReadback = true,
            rateDiffsCustom = false,
        )
    }

    private fun defaultGain(session: Int): RxGain = RxGain(
        session = session,
        supported = true,
        settable = true,
        indexName = "igi",
        indexMin = 0x1c,
        indexMax = 0x3e,
        indexStepDb = 1,
        automatic = false,
        automaticInput = "nothing: the phydm watchdog is not running",
        valid = true,
        index = 0x1c,
        rangeMin = 0x1c,
        rangeMax = 0x2a,
    )

    /** Whether the last [open] asked for an absolute noise floor. */
    public var noiseFloorRequested: Boolean = false
        private set

    /** Whether the last [open] asked for adaptive gain (the phydm watchdog). */
    public var adaptiveGainRequested: Boolean = false
        private set

    /** The per-frame hardware retry limit the last [open] asked for. */
    public var txRetryLimitRequested: Int = 0
        private set

    /** The USB TX-aggregation depth the last [open] asked for (0 = off). */
    public var usbAggMaxRequested: Int = 0
        private set

    /** The tx.report divisor requested at open, per session (0 = off). */
    private val txReportSampling: MutableMap<Int, Int> = ConcurrentHashMap()

    /** Injected TX receipts per session, for tests that want a populated ring. */
    public val receipts: MutableMap<Int, MutableList<TxReceipt>> = ConcurrentHashMap()

    /** Armed ACK-responder state per session. */
    public val ackResponders: MutableMap<Int, AckResponder> = ConcurrentHashMap()

    /** A-MPDU state per session, once a mode has been set. */
    public val ampduStates: MutableMap<Int, AmpduState> = ConcurrentHashMap()

    /** MAC TSF per session, in microseconds. */
    public val tsfBySession: MutableMap<Int, Long> = ConcurrentHashMap()

    /** Armed hardware beacon per session, once one has been started. */
    public val beaconsBySession: MutableMap<Int, Beacon> = ConcurrentHashMap()

    /** Set to throw from the next call to the named op, once. */
    public var failNext: MutableMap<String, Throwable> = mutableMapOf()

    public data class Probe(
        val session: Int,
        val frameHex: String,
        val mode: String,
        val count: Int,
        val intervalUs: Int,
        val sequenceOffset: Int,
        val batch: Boolean = false,
        val pktPowerDb: Int? = null,
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

    override suspend fun open(
        bus: Int,
        address: Int,
        reset: Boolean,
        noiseFloor: Boolean,
        adaptiveGain: Boolean,
        txReport: Int,
        txRetryLimit: Int,
        txAckTimeoutUs: Int,
        txRetryFallbackOff: Boolean,
        usbAggMax: Int,
    ): OpenRadio {
        record("open", "$bus/$address")
        noiseFloorRequested = noiseFloor
        adaptiveGainRequested = adaptiveGain
        txRetryLimitRequested = txRetryLimit
        usbAggMaxRequested = usbAggMax
        val r = open.values.firstOrNull { it.device.bus == bus && it.device.address == address }
            ?: throw IllegalArgumentException("no fake radio at bus $bus address $address")
        txReportSampling[r.session] = txReport
        return r
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
        // The bridge refuses this rather than retuning, so the fake must too:
        // an experiment that assumed it could just start monitoring passed
        // here and failed on the bench whenever a capture had been left
        // running on one of its witnesses.
        if (counters(session).monitoring) {
            throw IllegalStateException("monitor_failed: already monitoring")
        }
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

    override suspend fun fastRetune(session: Int, channel: Int): ChannelInfo {
        record("fastRetune", "$session,ch=$channel")
        val radio = radio(session)
        check(radio.state.broughtUp) { "radio is not brought up" }
        val info = ChannelInfo(
            channel = channel,
            width = radio.channel?.width ?: 20,
            offset = radio.channel?.offset ?: 0,
            band = radio.channel?.band ?: 0,
            fastRetune = true,
        )
        update(session) { it.copy(channel = info) }
        return info
    }

    override suspend fun fastBandwidth(session: Int, widthMhz: Int): ChannelInfo {
        record("fastBandwidth", "$session,$widthMhz")
        val radio = radio(session)
        check(radio.state.broughtUp) { "radio is not brought up" }
        require(widthMhz in radio.capabilities.widths) {
            "width ${widthMhz}MHz is not supported by ${radio.label}"
        }
        val info = ChannelInfo(
            channel = radio.channel?.channel ?: 0,
            width = widthMhz,
            offset = radio.channel?.offset ?: 0,
            band = radio.channel?.band ?: 0,
            fastRetune = true,
        )
        update(session) { it.copy(channel = info) }
        return info
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
        batch: Boolean,
        pktPowerDb: Int?,
    ): JsonObject {
        record("sendProbe", "$session,$mode,n=$count${if (batch) ",batch" else ""}")
        require(count in 1..RadioManager.MAX_TX_COUNT) {
            "count must be 1..${RadioManager.MAX_TX_COUNT}"
        }
        // Mirror the production boundary, so an experiment test cannot pass a
        // combination RadioManager would reject on the real path.
        require(!(batch && intervalUs > 0)) {
            "batch is a deep unpaced feed; give intervalUs 0 or use the paced path"
        }
        require(pktPowerDb == null || pktPowerDb in -128..127) {
            "pktPowerDb must be -128..127 (an int8 radiotap field)"
        }
        radio(session)
        val probe = Probe(session, frameHex, mode, count, intervalUs, sequenceOffset, batch, pktPowerDb)
        val outcome = onProbe?.invoke(probe) ?: TxOutcome(accepted = count)
        counters(session).txSent += outcome.accepted
        return buildJsonObject {
            put("sent", JsonPrimitive(outcome.accepted))
            put("elapsed_ns", JsonPrimitive(outcome.elapsedNs))
            put("late_frames", JsonPrimitive(outcome.lateFrames))
            put("max_late_us", JsonPrimitive(outcome.maxLateUs))
            put("batch", JsonPrimitive(batch))
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

    override suspend fun rxGain(session: Int): RxGain {
        record("rxGain", "$session")
        if (radio(session).capabilities.generation !in REALTEK_GENERATIONS) {
            return RxGain(
                session = session,
                supported = false,
                why = "this backend does not report a receive-gain index",
            )
        }
        return gain[session] ?: defaultGain(session)
    }

    override suspend fun clampRxGain(session: Int, minIndex: Int, maxIndex: Int): RxGain {
        record("clampRxGain", "$session,$minIndex..$maxIndex")
        val radio = radio(session)
        if (radio.capabilities.generation !in REALTEK_GENERATIONS) {
            throw CapabilityException(
                "clamp receive gain", radio.label, "the backend reports no gain index",
            )
        }
        require(minIndex <= maxIndex) { "minIndex must be <= maxIndex" }
        val current = gain[session] ?: defaultGain(session)
        require(minIndex >= current.indexMin && maxIndex <= current.indexMax) {
            "range must lie inside [${current.indexMin}, ${current.indexMax}]"
        }
        val updated = current.copy(
            valid = true,
            index = minIndex,
            rangeMin = minIndex,
            rangeMax = maxIndex,
        )
        gain[session] = updated
        return updated
    }

    override suspend fun ccaGates(session: Int): CcaGates {
        record("ccaGates", "$session")
        val radio = radio(session)
        if (radio.capabilities.generation !in REALTEK_GENERATIONS) {
            return CcaGates(
                session = session,
                supported = false,
                why = "splitting the carrier-sense gate is a Realtek 0x520 facility",
                ccaDisabled = radio.state.carrierSenseDisabled,
            )
        }
        if (!radio.state.broughtUp) {
            return CcaGates(
                session = session,
                supported = false,
                why = "the radio is not brought up — set a channel first; " +
                    "the gate register is meaningless before then",
                ccaDisabled = radio.state.carrierSenseDisabled,
            )
        }
        val (primary, edcca) = gates[session] ?: (false to false)
        return CcaGates(
            session = session,
            supported = true,
            ccaDisabled = primary || edcca,
            primaryCcaDisabled = primary,
            edccaDisabled = edcca,
        )
    }

    override suspend fun setCcaGates(
        session: Int,
        primaryCcaDisabled: Boolean?,
        edccaDisabled: Boolean?,
        safety: SafetyLevel,
    ): CcaGates {
        record(
            "setCcaGates",
            "$session,primary=$primaryCcaDisabled,edcca=$edccaDisabled,$safety",
        )
        RadioSafety.gateCcaGates(primaryCcaDisabled, edccaDisabled, safety)
        val radio = radio(session)
        if (radio.capabilities.generation !in REALTEK_GENERATIONS) {
            throw CapabilityException(
                "split the carrier-sense gate", radio.label,
                "the backend does not implement the gate split",
            )
        }
        check(radio.state.broughtUp) {
            "unsupported: the radio is not brought up — set a channel first"
        }
        val (oldPrimary, oldEdcca) = gates[session] ?: (false to false)
        val primary = primaryCcaDisabled ?: oldPrimary
        val edcca = edccaDisabled ?: oldEdcca
        gates[session] = primary to edcca
        update(session) {
            it.copy(state = it.state.copy(carrierSenseDisabled = primary || edcca))
        }
        return CcaGates(
            session = session,
            supported = true,
            ccaDisabled = primary || edcca,
            primaryCcaDisabled = primary,
            edccaDisabled = edcca,
        )
    }

    override suspend fun txPower(session: Int): TxPower {
        record("txPower", "$session")
        val radio = radio(session)
        if (!radio.capabilities.txPower.supported) {
            return TxPower(
                session = session,
                supported = false,
                why = "this backend does not wire the runtime TX-power knobs",
            )
        }
        return txPowerState[session] ?: defaultTxPower(session, radio)
    }

    override suspend fun setTxPower(
        session: Int,
        offsetQdb: Int?,
        indexOverride: Int?,
        rateDiffs: TxRateDiffs?,
        clearRateDiffs: Boolean,
        reapply: Boolean,
    ): TxPower {
        record(
            "setTxPower",
            "$session,offset=$offsetQdb,index=$indexOverride,diffs=${rateDiffs != null}," +
                "clear=$clearRateDiffs,reapply=$reapply",
        )
        val radio = radio(session)
        val caps = radio.capabilities.txPower
        if (!caps.supported) {
            throw CapabilityException(
                "set TX power", radio.label, "the backend does not wire the knobs",
            )
        }
        require(
            offsetQdb != null || indexOverride != null || rateDiffs != null ||
                clearRateDiffs || reapply,
        ) { "name at least one knob, rate diffs, or reapply" }
        if ((rateDiffs != null || clearRateDiffs) && !caps.rateDiffs) {
            throw CapabilityException(
                "set per-rate TX-power diffs", radio.label,
                "the backend does not honour them (caps.rate_diffs is false)",
            )
        }
        if (indexOverride != null) {
            require(caps.indexMax != 0) {
                "this backend has no flat TXAGC index (the dBm model); indexOverride is not a knob"
            }
            require(indexOverride == -1 || indexOverride in 0..caps.indexMax) {
                "indexOverride must be -1 (clear) or 0..${caps.indexMax}"
            }
        }
        if (offsetQdb != null) {
            require(offsetQdb in caps.offsetMinQdb..caps.offsetMaxQdb) {
                "offsetQdb must be ${caps.offsetMinQdb}..${caps.offsetMaxQdb}"
            }
        }
        if (reapply && !radio.state.broughtUp) {
            error("unsupported: the chip is not brought up")
        }
        val cur = txPowerState[session] ?: defaultTxPower(session, radio)
        val flat = when {
            indexOverride == null -> cur.flatIndex
            indexOverride < 0 -> -1
            else -> indexOverride
        }
        // Match devourer's quantize_offset_qdb: round to nearest step, ties away
        // from zero, so a request that vanishes on the hardware vanishes here
        // too rather than the fake reporting an offset the chip never took.
        val requested = offsetQdb ?: cur.offsetQdb ?: 0
        val steps = if (caps.stepQdb > 0) {
            val q = requested.toDouble() / caps.stepQdb
            val rounded = if (q >= 0) kotlin.math.floor(q + 0.5) else kotlin.math.ceil(q - 0.5)
            rounded.toInt()
        } else {
            0
        }
        val custom = when {
            rateDiffs != null -> true
            clearRateDiffs -> false
            else -> cur.rateDiffsCustom ?: false
        }
        val next = cur.copy(
            valid = radio.state.broughtUp,
            flatIndex = flat,
            offsetQdb = steps * caps.stepQdb,
            offsetSteps = steps,
            rateDiffsCustom = custom,
        )
        txPowerState[session] = next
        return next
    }

    /**
     * Frame-free energy, on the Realtek fake only.
     *
     * The MediaTek fake refuses it the way the bridge does, because that
     * refusal is the interesting case: a caller that treats "no counter on
     * this silicon" as "zero channel activity" has invented a measurement.
     */
    override suspend fun rxEnergy(session: Int, withNhm: Boolean): RxEnergy {
        record("rxEnergy", "$session,nhm=$withNhm")
        val radio = radio(session)
        if (radio.capabilities.generation !in REALTEK_GENERATIONS) {
            return RxEnergy(
                session = session,
                supported = false,
                why = "frame-free energy is a Realtek phydm facility " +
                    "(IRtlRadio::GetRxEnergy); this backend is not a Realtek radio",
            )
        }
        val tuned = radio.channel?.channel ?: -1
        val e = energyByChannel[tuned]
            ?: energy[session]
            ?: RxEnergy(session = session, supported = true)
        return e.copy(
            session = session,
            supported = true,
            channel = if (tuned >= 0) tuned else 0,
            nhm = if (withNhm) e.nhm else null,
            validNhm = withNhm && e.validNhm,
        )
    }

    override suspend fun rxQuality(session: Int): RxQuality {
        record("rxQuality", "$session")
        val radio = radio(session)
        if (radio.capabilities.generation !in RX_QUALITY_GENERATIONS) {
            return RxQuality(
                session = session,
                supported = false,
                why = "the fused windowed link-quality feed is a Realtek phy facility",
            )
        }
        return quality[session] ?: RxQuality(
            session = session,
            supported = true,
            valid = true,
            frames = 100,
            rssiMeanDbm = -40,
            rssiMaxDbm = -38,
            snrMeanDb = 30.0,
            snrMinDb = 25.0,
            snrValid = true,
            evmMeanDb = -30.0,
            evmValid = true,
            noiseFloorDbm = -70.0,
            nfValid = true,
            energyValid = true,
            igiValid = true,
            igi = 0x1c,
            verdict = "HEALTHY",
            label = "HEALTHY",
        )
    }

    override suspend fun thermal(session: Int): Thermal {
        record("thermal", "$session")
        val radio = radio(session)
        if (radio.capabilities.generation !in THERMAL_GENERATIONS) {
            return Thermal(
                session = session,
                supported = false,
                why = "this backend does not wire GetThermalStatus",
            )
        }
        return thermalBySession[session] ?: Thermal(
            session = session,
            supported = true,
            raw = 20,
            baseline = 18,
            delta = 2,
            valid = true,
            bucket = "cool",
        )
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

    override suspend fun txReceipts(session: Int, clear: Boolean): TxReceipts {
        record("txReceipts", "$session,clear=$clear")
        radio(session)
        val sampling = txReportSampling[session] ?: 0
        if (sampling <= 0) {
            return TxReceipts(
                session = session,
                enabled = false,
                why = "tx.report was not enabled at open",
            )
        }
        val ring = receipts[session]
        val out = TxReceipts(
            session = session,
            enabled = true,
            sampling = sampling,
            total = (ring?.size ?: 0).toLong(),
            buffered = (ring?.size ?: 0).toLong(),
            receipts = ring?.toList() ?: emptyList(),
        )
        if (clear) ring?.clear()
        return out
    }

    override suspend fun ackResponder(session: Int): AckResponder {
        record("ackResponder", "$session")
        val radio = radio(session)
        if (!radio.capabilities.hasFeature("ack_responder")) {
            return AckResponder(
                session = session,
                supported = false,
                why = "this adapter does not report a hardware ACK responder",
            )
        }
        return ackResponders[session]
            ?: AckResponder(session = session, supported = true, armed = false)
    }

    override suspend fun setAckResponder(
        session: Int,
        mac: String,
        safety: SafetyLevel,
    ): AckResponder {
        record("setAckResponder", "$session,$mac,$safety")
        RadioSafety.gateAckResponder(safety)
        val radio = radio(session)
        if (!radio.capabilities.hasFeature("ack_responder")) {
            throw CapabilityException(
                "arm the hardware ACK responder", radio.label,
                "the backend does not report one",
            )
        }
        require(mac.isNotBlank()) { "mac is required" }
        check(radio.state.broughtUp) { "bring the radio up before arming the responder" }
        val next = AckResponder(session = session, supported = true, armed = true, mac = mac)
        ackResponders[session] = next
        return next
    }

    override suspend fun clearAckResponder(session: Int): AckResponder {
        record("clearAckResponder", "$session")
        val radio = radio(session)
        if (!radio.capabilities.hasFeature("ack_responder")) {
            throw CapabilityException(
                "clear the hardware ACK responder", radio.label,
                "the backend does not report one",
            )
        }
        val next = AckResponder(session = session, supported = true, armed = false)
        ackResponders[session] = next
        return next
    }

    override suspend fun ampdu(session: Int): AmpduState {
        record("ampdu", "$session")
        val radio = radio(session)
        if (radio.capabilities.generation !in AMPDU_GENERATIONS) {
            return AmpduState(
                session = session, capability = "unsupported",
                note = "this backend does not wire SetAmpduMode",
            )
        }
        return ampduStates[session]
            ?: AmpduState(session = session, capability = "unknown", enabled = false)
    }

    override suspend fun setAmpdu(session: Int, mode: AmpduMode): AmpduState {
        record("setAmpdu", "$session,enabled=${mode.enabled}")
        val radio = radio(session)
        if (radio.capabilities.generation !in AMPDU_GENERATIONS) {
            throw CapabilityException(
                "enable A-MPDU", radio.label, "the backend does not wire SetAmpduMode",
            )
        }
        check(radio.state.broughtUp) { "bring the radio up before enabling A-MPDU" }
        val next = AmpduState(
            session = session, capability = "supported", enabled = mode.enabled,
            tid = mode.tid, maxNum = mode.maxNum, density = mode.density,
            noAck = mode.noAck, maxTime = mode.maxTime,
            clearBurstMode = mode.clearBurstMode,
        )
        ampduStates[session] = next
        return next
    }

    override suspend fun clearAmpdu(session: Int): AmpduState {
        record("clearAmpdu", "$session")
        val radio = radio(session)
        if (radio.capabilities.generation !in AMPDU_GENERATIONS) {
            return AmpduState(session = session, capability = "unsupported")
        }
        check(radio.state.broughtUp) { "bring the radio up before changing A-MPDU" }
        val next = AmpduState(session = session, capability = "supported", enabled = false)
        ampduStates[session] = next
        return next
    }

    override suspend fun tsf(session: Int): Tsf {
        record("tsf", "$session")
        val radio = radio(session)
        if (!radio.state.broughtUp) {
            return Tsf(
                session = session, supported = true, readable = false,
                why = "the radio is not brought up",
            )
        }
        return Tsf(
            session = session, supported = true, readable = true,
            tsfUs = tsfBySession[session] ?: 1_000L,
        )
    }

    override suspend fun writeTsf(session: Int, tsfUs: Long): Tsf {
        record("writeTsf", "$session,$tsfUs")
        val radio = radio(session)
        check(radio.state.broughtUp) { "the radio is not brought up" }
        tsfBySession[session] = tsfUs
        return Tsf(
            session = session, supported = true, readable = true, tsfUs = tsfUs,
            wrote = true, requestedUs = tsfUs, took = true, deltaUs = 0,
        )
    }

    override suspend fun beacon(session: Int): Beacon {
        record("beacon", "$session")
        val radio = radio(session)
        if (!radio.capabilities.supported) {
            return Beacon(
                session = session, supported = false,
                why = "the backend reports no capability information for this chip",
            )
        }
        return beaconsBySession[session]
            ?: Beacon(session = session, supported = true, active = false)
    }

    override suspend fun startBeacon(
        session: Int,
        frameHex: String,
        intervalTu: Int,
        safety: SafetyLevel,
    ): Beacon {
        record("startBeacon", "$session,$intervalTu")
        RadioSafety.gateBeacon(safety)
        val radio = radio(session)
        check(radio.state.broughtUp) { "bring the radio up before arming a beacon" }
        require(frameHex.isNotBlank() && frameHex.length % 2 == 0) {
            "frameHex must be a non-empty even-length hex string"
        }
        val next = Beacon(
            session = session, supported = true, active = true,
            intervalTu = intervalTu, frameBytes = frameHex.length / 2,
            mpduBytes = frameHex.length / 2,
            action = "start", started = true,
        )
        beaconsBySession[session] = next
        return next
    }

    override suspend fun updateBeaconPayload(session: Int, frameHex: String): Beacon {
        record("updateBeaconPayload", "$session")
        val radio = radio(session)
        check(radio.state.broughtUp) { "bring the radio up before updating a beacon" }
        val current = beaconsBySession[session]
            ?: throw IllegalStateException(
                "no beacon is active on this session — start one first",
            )
        val next = current.copy(
            active = true, frameBytes = frameHex.length / 2,
            mpduBytes = frameHex.length / 2,
            action = "update", updated = true, started = null, stopped = null,
        )
        beaconsBySession[session] = next
        return next
    }

    override suspend fun stopBeacon(session: Int): Beacon {
        record("stopBeacon", "$session")
        val radio = radio(session)
        check(radio.state.broughtUp) { "bring the radio up before stopping a beacon" }
        val existed = beaconsBySession.remove(session) != null
        return Beacon(
            session = session, supported = true, active = false,
            action = "stop", stopped = existed,
        )
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
        /** Generations whose backend derives from IRtlRadio. */
        private val REALTEK_GENERATIONS = setOf(
            "jaguar1", "jaguar2", "jaguar3", "rtl8733b", "kestrel",
        )

        /**
         * Generations that override `IRadio::GetRxQuality`. Note this is NOT
         * the IRtlRadio set: rtl8733b derives from IRtlRadio and does not
         * override it, which is exactly the distinction the bridge has to
         * draw too.
         */
        private val RX_QUALITY_GENERATIONS = setOf(
            "jaguar1", "jaguar2", "jaguar3", "kestrel",
        )

        /** Generations that override `IRadio::GetThermalStatus`. */
        private val THERMAL_GENERATIONS = setOf("jaguar1", "jaguar2", "jaguar3")

        /**
         * Generations whose `SetAmpduMode` is wired. Not the IRtlRadio set:
         * kestrel and rtl8733b inherit the refusing default, and the MT7612U
         * deliberately refuses (its aggregation is descriptor state not yet
         * plumbed through send_packet).
         */
        private val AMPDU_GENERATIONS = setOf("jaguar1", "jaguar2", "jaguar3")

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
                    offsetMinQdb = -32, offsetMaxQdb = 16, rateDiffs = true,
                ),
                features = mapOf(
                    "per_chain_rssi" to true,
                    "hw_rx_timestamp" to true,
                    "hw_beacon_txtsf" to true,
                    "ack_responder" to true,
                ),
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
                features = mapOf(
                    "per_chain_rssi" to true,
                    "hw_beacon_txtsf" to true,
                ),
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

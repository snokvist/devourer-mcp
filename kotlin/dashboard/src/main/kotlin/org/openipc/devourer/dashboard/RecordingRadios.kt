package org.openipc.devourer.dashboard

import kotlinx.serialization.json.JsonObject
import org.openipc.devourer.protocol.CcaGates
import org.openipc.devourer.protocol.ChannelSpec
import org.openipc.devourer.protocol.RxGain
import org.openipc.devourer.protocol.TxPower
import org.openipc.devourer.protocol.TxRateDiffs
import org.openipc.devourer.radio.ChannelInfo
import org.openipc.devourer.radio.OpenRadio
import org.openipc.devourer.radio.Radios
import org.openipc.devourer.radio.SafetyLevel

/**
 * Keeps [RadioBook] current by watching what passes through.
 *
 * A decorator rather than three call sites in the tool layer, because the
 * describes that matter most are the ones nobody types: an experiment
 * re-reads a radio between sweep points, and a characterization re-reads it
 * after bring-up to see the MAC that only exists once the chip is powered. A
 * book updated only by `radio_open` would show a stale channel for the entire
 * run — which is precisely the run someone is watching.
 */
public class RecordingRadios(
    private val delegate: Radios,
    private val book: RadioBook,
) : Radios by delegate {

    override suspend fun open(
        bus: Int,
        address: Int,
        reset: Boolean,
        noiseFloor: Boolean,
        adaptiveGain: Boolean,
        txReport: Int,
    ): OpenRadio =
        delegate.open(bus, address, reset, noiseFloor, adaptiveGain, txReport).also { book.record(it) }

    override suspend fun describe(session: Int): OpenRadio =
        delegate.describe(session).also { book.record(it) }

    override suspend fun close(session: Int) {
        delegate.close(session)
        book.forget(session)
    }

    /*
     * Every state-changing op re-reads the radio.
     *
     * Without this the page showed an adapter as idle and untuned while it
     * was monitoring — the last describe happened before the state changed,
     * and the three facts anyone actually looks for (monitoring, channel,
     * carrier sense) were the three that were stale. A re-read is one extra
     * round trip per state change, not per poll, and it is the bridge's
     * answer rather than our assumption about what the op did.
     */
    override suspend fun startMonitor(session: Int, channel: ChannelSpec): JsonObject =
        delegate.startMonitor(session, channel).also { refresh(session) }

    override suspend fun stopMonitor(session: Int): JsonObject =
        delegate.stopMonitor(session).also { refresh(session) }

    override suspend fun retune(session: Int, channel: ChannelSpec): JsonObject =
        delegate.retune(session, channel).also { refresh(session) }

    override suspend fun fastRetune(session: Int, channel: Int): ChannelInfo =
        delegate.fastRetune(session, channel).also { refresh(session) }

    override suspend fun fastBandwidth(session: Int, widthMhz: Int): ChannelInfo =
        delegate.fastBandwidth(session, widthMhz).also { refresh(session) }

    override suspend fun setCarrierSense(
        session: Int,
        enabled: Boolean,
        safety: SafetyLevel,
    ): JsonObject = delegate.setCarrierSense(session, enabled, safety).also { refresh(session) }

    override suspend fun clampRxGain(session: Int, minIndex: Int, maxIndex: Int): RxGain =
        delegate.clampRxGain(session, minIndex, maxIndex).also { refresh(session) }

    override suspend fun setCcaGates(
        session: Int,
        primaryCcaDisabled: Boolean?,
        edccaDisabled: Boolean?,
        safety: SafetyLevel,
    ): CcaGates = delegate
        .setCcaGates(session, primaryCcaDisabled, edccaDisabled, safety)
        .also { refresh(session) }

    override suspend fun setTxPower(
        session: Int,
        offsetQdb: Int?,
        indexOverride: Int?,
        rateDiffs: TxRateDiffs?,
        clearRateDiffs: Boolean,
        reapply: Boolean,
    ): TxPower = delegate
        .setTxPower(session, offsetQdb, indexOverride, rateDiffs, clearRateDiffs, reapply)
        .also { refresh(session) }

    /** Best effort: a stale page is a nuisance, a failed radio op is not. */
    private suspend fun refresh(session: Int) {
        runCatching { book.record(delegate.describe(session)) }
    }
}

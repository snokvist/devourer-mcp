package org.openipc.devourer.characterize

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.openipc.devourer.experiment.deliverTo
import org.openipc.devourer.radio.FakeRadios
import org.openipc.devourer.radio.SafetyLevel
import org.openipc.devourer.radio.SafetyLevelException
import org.openipc.devourer.radio.VerificationState

/**
 * Characterization, offline.
 *
 * The properties under test are all about honesty rather than arithmetic:
 * that a quiet channel is not recorded as a deaf adapter, that "we could not
 * try" is stored differently from "it failed", and that the run does not
 * quietly transmit without listening in order to make its own numbers better.
 */
class CharacterizerTest {

    private val dut = FakeRadios.realtek(session = 1)
    private val peer = FakeRadios.mediatek(session = 2)

    private fun store() = EvidenceStore(createTempDirectory("evidence"))

    /** Makes exactly the named channels carry traffic. */
    private fun FakeRadios.airOn(scope: CoroutineScope, vararg busy: Pair<Int, Int>) {
        val byChannel = busy.toMap()
        onMonitorStart = { session, channel ->
            val frames = byChannel[channel.channel] ?: 0
            if (frames > 0) {
                scope.launch {
                    awaitCollector(session)
                    injectAmbient(session, frames)
                }
            }
        }
    }

    @Test
    fun `a chip with no capability report stops at detection and says why`() = runTest {
        val radios = FakeRadios(listOf(FakeRadios.unsupported(9)))
        val c = Characterizer(radios, store(), backgroundScope).run(9)

        assertEquals(VerificationState.DETECTED, c.state)
        assertEquals(1, c.runs.single().claims.size)
        assertTrue("everything below detection" in c.runs.single().skipped.keys)
        // Nothing was brought up, so nothing was transmitted or tuned.
        assertEquals(0, radios.calls.count { it.startsWith("retune") })
    }

    @Test
    fun `a quiet channel is recorded as quiet, not as a deaf receiver`() = runTest {
        // Ch6 on this bench carries about one frame in four seconds while ch1
        // carries thousands. An adapter pointed at ch6 looks broken.
        val radios = FakeRadios(listOf(dut))
        radios.airOn(backgroundScope, 11 to 400)

        val c = Characterizer(radios, store(), backgroundScope)
            .run(1, Characterizer.Options(rxChannels = listOf(1, 6, 11)))

        val rx = c.runs.single().claims.single { it.subject == "rx" }
        assertEquals(VerificationState.RX_VERIFIED, rx.state)
        assertEquals("ch11/20MHz", rx.evidence["channel"])
        val notes = c.runs.single().notes
        assertTrue(notes.any { "channel 1: only 0 frames" in it }, notes.toString())
        assertTrue(notes.any { "channel 6: only 0 frames" in it }, notes.toString())
    }

    @Test
    fun `an adapter that hears nothing anywhere is skipped, not failed`() = runTest {
        val radios = FakeRadios(listOf(dut))
        val c = Characterizer(radios, store(), backgroundScope)
            .run(1, Characterizer.Options(rxChannels = listOf(1, 6)))

        assertNull(c.runs.single().claims.firstOrNull { it.subject == "rx" })
        val reason = c.runs.single().skipped.getValue("rx_verification")
        assertTrue("The adapter may be fine and the air quiet" in reason, reason)
    }

    @Test
    fun `without a peer, TX is unverifiable-here rather than failed`() = runTest {
        val radios = FakeRadios(listOf(dut))
        radios.airOn(backgroundScope, 1 to 100)

        val c = Characterizer(radios, store(), backgroundScope)
            .run(1, Characterizer.Options(rxChannels = listOf(1)))

        assertEquals(VerificationState.RX_VERIFIED, c.state)
        val reason = c.runs.single().skipped.getValue("tx_verification")
        assertTrue("bench limitation" in reason, reason)
        val tx = c.unverified.single { it.capability == "transmit" }
        assertTrue(tx.blocked, "a blocked capability is not the same as an untried one")
    }

    @Test
    fun `the adapter under test may not be its own peer`() = runTest {
        val radios = FakeRadios(listOf(dut))
        val c = Characterizer(radios, store(), backgroundScope)
            .run(1, Characterizer.Options(rxChannels = listOf(1), txPeerSession = 1))
        assertEquals("the peer session is this same radio", c.runs.single().skipped["tx_verification"])
    }

    @Test
    fun `a peer that hears the burst carries the adapter to TX_VERIFIED`() = runTest {
        val radios = FakeRadios(listOf(dut, peer))
        radios.airOn(backgroundScope, 1 to 100)
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 95)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val c = Characterizer(radios, store(), backgroundScope).run(
            1,
            Characterizer.Options(rxChannels = listOf(1), txPeerSession = 2, txModes = listOf("6M")),
        )

        assertEquals(VerificationState.TX_VERIFIED, c.state)
        val tx = c.runs.single().claims.single { it.subject == "tx" }
        assertEquals("enabled", c.runs.single().conditions["tx_carrier_sense"])
        assertTrue(tx.detail.contains("independent receiver"))
    }

    @Test
    fun `a poor TX run explains the likely cause instead of jamming the channel`() = runTest {
        // This is the regression that matters: the retry used to fire on its
        // own, so a characterization of a weak link silently transmitted
        // without listening and filed the result as evidence.
        val radios = FakeRadios(listOf(dut, peer))
        radios.airOn(backgroundScope, 1 to 100)
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 4)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val c = Characterizer(radios, store(), backgroundScope).run(
            1,
            Characterizer.Options(rxChannels = listOf(1), txPeerSession = 2),
        )

        assertTrue(
            c.runs.single().notes.any { "Not done automatically" in it },
            c.runs.single().notes.toString(),
        )
        assertEquals(
            0,
            radios.calls.count { it.contains("setCarrierSense") && it.contains("enabled=false") },
        )
    }

    @Test
    fun `asking for the retry without the safety level is refused`() = runTest {
        val radios = FakeRadios(listOf(dut, peer))
        radios.airOn(backgroundScope, 1 to 100)
        radios.onProbe = { p ->
            radios.deliverTo(p, to = 2, frames = 4)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        assertFailsWith<SafetyLevelException> {
            Characterizer(radios, store(), backgroundScope).run(
                1,
                Characterizer.Options(
                    rxChannels = listOf(1),
                    txPeerSession = 2,
                    retryWithoutCarrierSense = true,
                    safety = SafetyLevel.NORMAL,
                ),
            )
        }
    }

    @Test
    fun `with the level, the retry runs and the record says carrier sense was off`() = runTest {
        val radios = FakeRadios(listOf(dut, peer))
        radios.airOn(backgroundScope, 1 to 100)
        radios.onProbe = { p ->
            val delivered = if (radios.carrierSenseDisabled(1)) 92 else 4
            radios.deliverTo(p, to = 2, frames = delivered)
            FakeRadios.TxOutcome(accepted = p.count)
        }

        val c = Characterizer(radios, store(), backgroundScope).run(
            1,
            Characterizer.Options(
                rxChannels = listOf(1),
                txPeerSession = 2,
                txModes = listOf("6M"),
                retryWithoutCarrierSense = true,
                safety = SafetyLevel.EXPERIMENTAL,
            ),
        )

        assertEquals("disabled", c.runs.single().conditions["tx_carrier_sense"])
        assertTrue(
            c.runs.single().notes.any { "its MAC was declining to" in it },
            c.runs.single().notes.toString(),
        )
        // And it was put back.
        assertTrue(!radios.carrierSenseDisabled(1))
    }

    @Test
    fun `an adapter with no readable MAC is filed by position, and the record says so`() = runTest {
        // A MediaTek only reads its EEPROM during chip init, so two
        // indistinguishable MT7612U units can only be told apart by where
        // they are plugged in.
        val radios = FakeRadios(listOf(peer))
        radios.airOn(backgroundScope, 1 to 100)

        val c = Characterizer(radios, store(), backgroundScope)
            .run(2, Characterizer.Options(rxChannels = listOf(1)))

        assertEquals(IdentityConfidence.PHYSICAL_PORT, c.identity.confidence)
        assertTrue(c.key.startsWith("port-"), c.key)
        assertTrue(
            c.runs.single().notes.any { "cannot be reattached" in it },
            c.runs.single().notes.toString(),
        )
    }

    @Test
    fun `capabilities the run never exercised are listed with a reason`() = runTest {
        val radios = FakeRadios(listOf(dut))
        radios.airOn(backgroundScope, 1 to 100)

        val c = Characterizer(radios, store(), backgroundScope)
            .run(1, Characterizer.Options(rxChannels = listOf(1)))

        val widths = c.unverified.filter { it.capability.startsWith("bandwidth_") }
        assertEquals(
            setOf("bandwidth_5mhz", "bandwidth_10mhz", "bandwidth_40mhz", "bandwidth_80mhz"),
            widths.map { it.capability }.toSet(),
        )
        assertNotNull(c.unverified.firstOrNull { it.capability == "tx_power_step" })
        // per_chain_rssi IS exercised by an RX run, so it must not appear.
        assertNull(c.unverified.firstOrNull { it.capability == "per_chain_rssi" })
    }
}

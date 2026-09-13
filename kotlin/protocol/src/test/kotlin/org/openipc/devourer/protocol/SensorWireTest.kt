package org.openipc.devourer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The snake_case bridge replies for the fused RX sensor and the thermal meter,
 * pinned to their Kotlin fields.
 *
 * The same class of bug as the TX-power `rate_diffs` mapping: a missing
 * [kotlinx.serialization.SerialName] silently defaults and the value reads as a
 * plausible zero. These two types are field-heavy, so the guard matters.
 */
class SensorWireTest {

    @Test
    fun `a fused RX-quality reply decodes every field`() {
        val json = """
            {
              "session": 3, "supported": true, "valid": true, "frames": 100,
              "rssi_mean_dbm": -40, "rssi_max_dbm": -38,
              "snr_mean_db": 30.0, "snr_min_db": 25.0, "snr_valid": true,
              "evm_mean_db": -30.0, "evm_valid": true,
              "noise_floor_dbm": -70.0, "nf_valid": true,
              "abs_noise_floor_dbm": -95, "abs_nf_valid": false,
              "energy_valid": true, "fa_ofdm": 12, "cca_ofdm": 34,
              "igi_valid": true, "igi": 28,
              "verdict": "HEALTHY", "label": "HEALTHY", "cause": "", "fix": "",
              "igi_at_floor": true, "igi_at_ceiling": false
            }
        """.trimIndent()

        val q = BridgeJson.format.decodeFromString(RxQuality.serializer(), json)

        assertTrue(q.supported)
        assertTrue(q.valid)
        assertEquals(100, q.frames)
        assertEquals(-40, q.rssiMeanDbm)
        assertEquals(-38, q.rssiMaxDbm)
        assertEquals(30.0, q.snrMeanDb)
        assertEquals(25.0, q.snrMinDb)
        assertTrue(q.snrValid)
        assertEquals(-30.0, q.evmMeanDb)
        assertTrue(q.evmValid)
        assertEquals(-70.0, q.noiseFloorDbm)
        assertTrue(q.nfValid)
        assertEquals(-95, q.absNoiseFloorDbm)
        assertFalse(q.absNfValid)
        assertTrue(q.energyValid)
        assertEquals(12, q.faOfdm)
        assertEquals(34, q.ccaOfdm)
        assertTrue(q.igiValid)
        assertEquals(28, q.igi)
        assertEquals("HEALTHY", q.verdict)
        assertEquals("HEALTHY", q.label)
        assertTrue(q.igiAtFloor)
        assertFalse(q.igiAtCeiling)
    }

    @Test
    fun `a non-realtek RX-quality reply keeps the absence honest`() {
        val json = """{"session":2,"supported":false,"why":"not a Realtek radio"}"""

        val q = BridgeJson.format.decodeFromString(RxQuality.serializer(), json)

        assertFalse(q.supported)
        assertEquals("not a Realtek radio", q.why)
        assertFalse(q.valid)
    }

    @Test
    fun `a thermal reply decodes every field`() {
        val json = """
            {"session":3,"supported":true,"raw":20,"baseline":18,"delta":2,
             "valid":true,"bucket":"cool"}
        """.trimIndent()

        val t = BridgeJson.format.decodeFromString(Thermal.serializer(), json)

        assertTrue(t.supported)
        assertEquals(20, t.raw)
        assertEquals(18, t.baseline)
        assertEquals(2, t.delta)
        assertTrue(t.valid)
        assertEquals("cool", t.bucket)
    }
}

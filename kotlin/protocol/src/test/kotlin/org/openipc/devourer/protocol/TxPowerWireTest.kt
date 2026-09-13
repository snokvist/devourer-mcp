package org.openipc.devourer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TX-power reply is snake_case on the wire and camelCase in Kotlin, so a
 * field with no [kotlinx.serialization.SerialName] silently decodes to its
 * default. That happened: `rate_diffs` lost its mapping and every radio read
 * back as `rateDiffs = false` regardless of what the bridge sent, which would
 * have hidden the per-rate-diff capability behind a plausible-looking false.
 *
 * This pins the wire names to the fields the way FrameRecordTest pins the byte
 * offsets: change one side and the test fails instead of the value quietly
 * becoming a default.
 */
class TxPowerWireTest {

    @Test
    fun `a full served reply decodes every field by its wire name`() {
        val json = """
            {
              "session": 3, "supported": true,
              "index_max": 127, "step_qdb": 1, "step_measured": true,
              "offset_min_qdb": -127, "offset_max_qdb": 127,
              "rate_diffs": true, "rate_diffs_hw_table": true, "rate_diffs_measured": true,
              "valid": true, "flat_index": -1, "offset_qdb": 6, "offset_steps": 6,
              "saturated_low": false, "saturated_high": true,
              "cck_index": 48, "ofdm_index": 46, "mcs7_index": 52,
              "hw_readback": false, "rate_diffs_custom": true,
              "note": "hw_readback is false"
            }
        """.trimIndent()

        val p = BridgeJson.format.decodeFromString(TxPower.serializer(), json)

        assertEquals(3, p.session)
        assertTrue(p.supported)
        assertEquals(127, p.indexMax)
        assertEquals(1, p.stepQdb)
        assertTrue(p.stepMeasured)
        assertEquals(-127, p.offsetMinQdb)
        assertEquals(127, p.offsetMaxQdb)
        // The field the missing SerialName hid.
        assertTrue(p.rateDiffs)
        assertTrue(p.rateDiffsHwTable)
        assertTrue(p.rateDiffsMeasured)
        assertTrue(p.valid)
        assertEquals(-1, p.flatIndex)
        assertEquals(6, p.offsetQdb)
        assertEquals(6, p.offsetSteps)
        assertFalse(p.saturatedLow!!)
        assertTrue(p.saturatedHigh!!)
        assertEquals(48, p.cckIndex)
        assertEquals(46, p.ofdmIndex)
        assertEquals(52, p.mcs7Index)
        assertFalse(p.hwReadback!!)
        assertTrue(p.rateDiffsCustom!!)
        assertEquals("hw_readback is false", p.note)
    }

    @Test
    fun `a state-less backend's applied offset decodes`() {
        // The MT7612U's dBm model: no GetTxPowerState override, so the reply
        // carries `applied_offset_qdb` instead of a flat/offset state triple.
        val json = """
            {"session":1,"supported":true,"index_max":0,"step_qdb":4,
             "offset_min_qdb":-80,"offset_max_qdb":40,"rate_diffs":false,
             "valid":false,"applied_offset_qdb":-16,
             "why":"the backend reported no applied state"}
        """.trimIndent()

        val p = BridgeJson.format.decodeFromString(TxPower.serializer(), json)

        assertTrue(p.supported)
        assertFalse(p.valid)
        assertEquals(-16, p.appliedOffsetQdb)
        assertEquals(null, p.offsetQdb)
    }

    @Test
    fun `an unsupported reply keeps the absence honest`() {
        val json = """{"session":1,"supported":false,"why":"not ported here"}"""

        val p = BridgeJson.format.decodeFromString(TxPower.serializer(), json)

        assertFalse(p.supported)
        assertEquals("not ported here", p.why)
        // Nothing was applied, so no state field should be invented.
        assertEquals(null, p.flatIndex)
        assertFalse(p.valid)
    }
}

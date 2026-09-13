package org.openipc.devourer.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `experiment_link_probe` argument wiring, without radios or hardware.
 *
 * The tool handler used to build the [org.openipc.devourer.experiment.ExperimentSpec]
 * inline, where a dropped argument (a `qos_tid` that never reached the spec,
 * say) would only surface as a silent behavioural difference on the bench. This
 * pins the mapping at the level the MCP caller actually uses.
 */
class LinkProbeSpecTest {

    private fun request(arguments: JsonObject): CallToolRequest =
        CallToolRequest(
            Json.decodeFromJsonElement(
                CallToolRequestParams.serializer(),
                buildJsonObject {
                    put("name", "experiment_link_probe")
                    put("arguments", arguments)
                },
            ),
        )

    @Test
    fun `qos_tid and batch reach the experiment spec`() {
        val spec = linkProbeSpec(
            request(
                buildJsonObject {
                    put("tx_session", 1)
                    put("rx_session", 2)
                    put("channel", 6)
                    put("qos_tid", 3)
                    put("batch", true)
                },
            ),
        )

        assertEquals(3, spec.qosTid)
        assertTrue(spec.batch)
        // batch is the deep unpaced feed; the spec must have forced spacing to 0.
        assertEquals(0, spec.bounds.intervalUs)
    }

    @Test
    fun `qos_tid is absent when not asked`() {
        val spec = linkProbeSpec(
            request(
                buildJsonObject {
                    put("tx_session", 1)
                    put("rx_session", 2)
                    put("channel", 6)
                },
            ),
        )

        assertNull(spec.qosTid)
    }

    @Test
    fun `a quoted qos_tid is refused rather than dropped`() {
        val e = assertFailsWith<IllegalArgumentException> {
            linkProbeSpec(
                request(
                    buildJsonObject {
                        put("tx_session", 1)
                        put("rx_session", 2)
                        put("channel", 6)
                        put("qos_tid", "3")
                    },
                ),
            )
        }

        assertTrue("qos_tid" in (e.message ?: ""), e.message)
    }
}

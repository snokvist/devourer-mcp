package org.openipc.devourer.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openipc.devourer.scratchpad.ScratchpadService

/**
 * The `scratchpad_run` reply shape.
 *
 * The run id is nested at `run.id`, but every follow-up tool takes an argument
 * named `run_id`. A caller that has to translate one into the other will
 * eventually translate it wrong, so the reply repeats it under the exact name
 * the next call wants.
 */
class ScratchpadReplyTest {

    @Test
    fun `the reply carries run_id at the top level as well as run id`() {
        val handle = ScratchpadService.RunHandle(
            id = "pad-7",
            name = "watch",
            running = true,
            granted = listOf("timer"),
            durationMs = 1_000,
            note = "live",
        )
        val inspection = ScratchpadService.InspectionResult(
            valid = true,
            problems = emptyList(),
            declared = emptyList(),
            required = emptyList(),
            privileged = emptyList(),
            touches = emptyList(),
        )
        val body = Json { encodeDefaults = true; explicitNulls = false }
            .encodeToString(
                ScratchpadStarted.serializer(),
                ScratchpadStarted(handle.id, handle, inspection),
            )
        val obj = Json.parseToJsonElement(body).jsonObject

        assertEquals("pad-7", obj["run_id"]!!.jsonPrimitive.content)
        assertEquals("pad-7", obj["run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
}

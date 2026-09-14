package org.openipc.devourer.scratchpad

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Series-name derivation, which is load-bearing in two places at once.
 *
 * The validator uses it to reject a widget or expression naming a series that
 * will never exist; the interpreter uses it to reject an expression referencing
 * one. When those two were computed separately they drifted, and a perfectly
 * good program charting `cam.latency_ms` was rejected while the interpreter was
 * producing exactly that series. One definition, tested here.
 */
class ProgramTest {

    private fun program(extract: Map<String, String> = emptyMap()) = ScratchpadProgram(
        name = "p",
        capabilities = listOf("timer", "metrics", "capture.read", "http.get", "ui"),
        sources = listOf(
            CaptureMetricSource(id = "rssi", captureId = "cap-1", metric = "rssi_mean"),
            HttpPollSource(id = "cam", url = "http://h/x", everyMs = 500, timeoutMs = 400, extract = extract),
        ),
        computed = listOf(Computed(id = "doubled", expr = "rssi * 2")),
    )

    @Test
    fun `an http source expands to latency, status and each extracted field`() {
        val ids = program(mapOf("fps" to "video.fps", "bitrate" to "video.bitrate")).seriesIds()
        assertContains(ids, "cam")
        assertContains(ids, "cam.latency_ms")
        assertContains(ids, "cam.status")
        assertContains(ids, "cam.fps")
        assertContains(ids, "cam.bitrate")
        assertContains(ids, "rssi")
        assertContains(ids, "doubled")
    }

    @Test
    fun `an experiment source round-trips through the production JSON`() {
        // The subclass registration is the whole wire format: without it a
        // `{"kind":"experiment.metric"}` program fails to decode, and a
        // decoded source that lost its experiment id would read the wrong run.
        val p = ScratchpadProgram(
            name = "p",
            capabilities = listOf("timer", "experiment.read"),
            sources = listOf(
                ExperimentMetricSource(
                    id = "delivery",
                    experimentId = "exp-7",
                    metric = "delivery_ratio",
                    point = "MCS7/20",
                ),
            ),
        )
        val text = ScratchpadJson.format.encodeToString(ScratchpadProgram.serializer(), p)
        val back = ScratchpadJson.format.decodeFromString(ScratchpadProgram.serializer(), text)
        val source = back.sources.single() as ExperimentMetricSource

        assertEquals("exp-7", source.experimentId)
        assertEquals("MCS7/20", source.point)
        assertEquals(setOf("delivery"), back.seriesIds())
        assertTrue(Capability.EXPERIMENT_READ in back.requiredCapabilities())
        assertEquals(emptySet(), back.undeclared())
    }

    @Test
    fun `a widget charting a derived http series validates`() {
        val p = program().copy(
            ui = UiSpec(
                title = "t",
                widgets = listOf(Widget(kind = "chart", series = listOf("rssi", "cam.latency_ms"))),
            ),
        )
        assertEquals(emptyList(), p.validate())
    }

    @Test
    fun `a widget naming a series that will never exist is rejected`() {
        val p = program().copy(
            ui = UiSpec(widgets = listOf(Widget(kind = "chart", series = listOf("cam.bitrate")))),
        )
        assertTrue(p.validate().any { it.contains("unknown series 'cam.bitrate'") })
    }

    @Test
    fun `required capabilities are derived from content, not trusted from the declaration`() {
        val p = ScratchpadProgram(
            name = "p",
            capabilities = emptyList(),
            sources = listOf(HttpPollSource(id = "h", url = "http://x/y", everyMs = 500, timeoutMs = 100)),
            ui = UiSpec(),
        )
        val required = p.requiredCapabilities()
        assertContains(required, Capability.HTTP_GET)
        assertContains(required, Capability.TIMER)
        assertContains(required, Capability.UI)
        assertEquals(required, p.undeclared())
    }
}

/**
 * The polymorphic-discriminator collision, pinned.
 *
 * `Source` is serialized with `classDiscriminator = "kind"`. A subclass field
 * also called `kind` silently receives the discriminator's value instead of the
 * caller's, and the failure is invisible: the program validates, the run starts,
 * and every series stays empty because the filter is comparing against a frame
 * kind named "capture.metric".
 */
class DiscriminatorTest {

    // The production configuration, not a copy of it: a test that rebuilt this
    // would not have caught the collision it exists to pin.
    private val json = ScratchpadJson.format

    @Test
    fun `a capture source with no frame filter deserializes with a null filter`() {
        val src = json.decodeFromString(
            Source.serializer(),
            """{"kind":"capture.metric","id":"f","capture_id":"cap-1","metric":"frames"}""",
        ) as CaptureMetricSource
        assertEquals("cap-1", src.captureId)
        assertEquals("frames", src.metric)
        kotlin.test.assertNull(
            src.frameKind,
            "an unset frame filter must be null, not the discriminator value",
        )
    }

    @Test
    fun `an explicit frame filter round-trips`() {
        val src = json.decodeFromString(
            Source.serializer(),
            """{"kind":"capture.metric","id":"f","capture_id":"c","metric":"frames","frame_kind":"data/qos-data"}""",
        ) as CaptureMetricSource
        assertEquals("data/qos-data", src.frameKind)
    }
}

package org.openipc.devourer.scratchpad

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Owns scratchpad runs: validation, grants, execution, the live view, and
 * promotion of the ones worth keeping.
 *
 * Promotion is the point of the whole subsystem. A generated program that
 * answered a question once is a throwaway; the same program, saved with its
 * capability list and its purpose, is a tool the next session starts from. The
 * saved form is exactly the program that ran — not a summary of it — so what is
 * reused is what was verified.
 */
public class ScratchpadService(
    private val host: ScratchpadHost,
    private val scope: CoroutineScope,
    private val varDir: Path,
    /** 0 lets the OS pick a free loopback port per run. */
    private val uiPort: Int = 0,
) {
    public val json: Json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
        classDiscriminator = "kind"
        serializersModule = SerializersModule {
            polymorphic(Source::class) {
                subclass(CaptureMetricSource::class)
                subclass(HttpPollSource::class)
                subclass(RadioMetricSource::class)
            }
        }
    }

    private val runs = ConcurrentHashMap<String, Run>()
    private val counter = AtomicInteger(0)
    private val savedDir: Path = varDir.resolve("scratchpads")

    init {
        Files.createDirectories(savedDir)
    }

    public class Run(
        public val id: String,
        public val program: ScratchpadProgram,
        public val grant: CapabilityGrant,
        public val state: RunState,
        public val ui: UiServer?,
        public val job: Job,
        public val startedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        @Volatile
        public var error: String? = null
    }

    @Serializable
    public data class RunHandle(
        val id: String,
        val name: String,
        @SerialName("ui_url") val uiUrl: String? = null,
        val running: Boolean,
        @SerialName("granted_capabilities") val granted: List<String>,
        @SerialName("duration_ms") val durationMs: Long,
        val note: String,
    )

    /**
     * Validates a program without running it.
     *
     * Separate from [start] because "what would this be allowed to do" is a
     * question worth answering before anything runs — it is the review step that
     * makes a generated program inspectable rather than merely sandboxed.
     */
    public fun inspect(program: ScratchpadProgram): InspectionResult {
        val problems = program.validate()
        val required = program.requiredCapabilities()
        return InspectionResult(
            valid = problems.isEmpty() && program.undeclared().isEmpty(),
            problems = problems + program.undeclared().map {
                "uses '${it.id}' without declaring it"
            },
            declared = program.capabilities,
            required = required.map { it.id },
            privileged = required.filter { it.privileged }.map { it.id },
            touches = buildList {
                program.sources.filterIsInstance<CaptureMetricSource>()
                    .mapTo(this) { "capture ${it.captureId}" }
                program.sources.filterIsInstance<RadioMetricSource>()
                    .mapTo(this) { "radio session ${it.session}" }
                program.sources.filterIsInstance<HttpPollSource>()
                    .mapTo(this) { "http ${it.url}" }
            }.distinct(),
        )
    }

    @Serializable
    public data class InspectionResult(
        val valid: Boolean,
        val problems: List<String>,
        val declared: List<String>,
        val required: List<String>,
        /** Capabilities that can affect the outside world. Worth a second look. */
        val privileged: List<String>,
        /** Every external thing this program would touch, enumerated. */
        val touches: List<String>,
    )

    public fun start(
        program: ScratchpadProgram,
        grant: CapabilityGrant,
        withUi: Boolean = true,
    ): RunHandle {
        val inspection = inspect(program)
        if (!inspection.valid) {
            throw IllegalArgumentException(
                "program rejected: ${inspection.problems.joinToString("; ")}",
            )
        }
        val id = "pad-${counter.incrementAndGet()}"
        val state = RunState(grant.maxSamples)
        val ui = if (withUi && grant.has(Capability.UI)) {
            runCatching {
                UiServer(program.ui?.title?.ifBlank { program.name } ?: program.name,
                    state, program, uiPort)
            }.getOrNull()
        } else {
            null
        }

        lateinit var run: Run
        val job = scope.launch {
            try {
                Interpreter(host, grant).run(program, state, scope)
            } catch (e: Throwable) {
                run.error = e.message ?: e::class.simpleName
                state.log("run failed: ${run.error}")
                state.finish()
            }
        }
        run = Run(id, program, grant, state, ui, job)
        runs[id] = run

        return RunHandle(
            id = id,
            name = program.name,
            uiUrl = ui?.url,
            running = true,
            granted = grant.capabilities.sorted(),
            durationMs = minOf(program.durationMs, grant.maxRuntimeMs),
            note = if (ui != null) {
                "Live view at ${ui.url} — loopback only, so it is reachable from this " +
                    "machine and nowhere else."
            } else {
                "No UI for this run."
            },
        )
    }

    public fun get(id: String): Run? = runs[id]

    public fun list(): List<Run> = runs.values.sortedBy { it.id }

    public fun stop(id: String): Boolean {
        val run = runs[id] ?: return false
        run.state.requestStop()
        run.job.cancel()
        run.ui?.close()
        run.state.finish()
        return true
    }

    public fun stopAll() {
        runs.keys.toList().forEach { stop(it) }
    }

    /** Snapshot of a run's series, for a caller that wants the numbers not the page. */
    public fun results(id: String, windowMs: Long? = null): Map<String, Series.Stats> {
        val run = runs[id] ?: return emptyMap()
        return run.state.all().mapNotNull { (k, v) -> v.stats(windowMs)?.let { k to it } }.toMap()
    }

    /**
     * Saves a program as a reusable tool.
     *
     * What is stored is the program itself, so a promoted scratchpad re-runs
     * identically rather than approximately. The grant is deliberately NOT
     * stored: capabilities are granted per run, against the radios and captures
     * that exist at that moment, and a saved grant would be a standing
     * permission nobody reviewed.
     */
    public fun promote(runId: String, saveAs: String? = null): Path {
        val run = runs[runId] ?: throw IllegalArgumentException("no run $runId")
        return save(run.program, saveAs ?: run.program.name)
    }

    public fun save(program: ScratchpadProgram, name: String): Path {
        val safe = name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
        require(safe.isNotEmpty()) { "'$name' has no usable characters for a filename" }
        val target = savedDir.resolve("$safe.json")
        val tmp = target.resolveSibling("$safe.json.tmp")
        Files.writeString(tmp, json.encodeToString(ScratchpadProgram.serializer(), program))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return target
    }

    public fun saved(): List<SavedProgram> =
        Files.list(savedDir).use { s ->
            s.filter { it.fileName.toString().endsWith(".json") }.toList()
        }.mapNotNull { p ->
            runCatching {
                val prog = json.decodeFromString(ScratchpadProgram.serializer(), Files.readString(p))
                SavedProgram(
                    file = p.fileName.toString().removeSuffix(".json"),
                    name = prog.name,
                    purpose = prog.purpose,
                    capabilities = prog.capabilities,
                    program = prog,
                )
            }.getOrNull()
        }.sortedBy { it.file }

    public fun load(file: String): ScratchpadProgram? {
        val p = savedDir.resolve("${file.removeSuffix(".json")}.json")
        if (!Files.exists(p)) return null
        return runCatching {
            json.decodeFromString(ScratchpadProgram.serializer(), Files.readString(p))
        }.getOrNull()
    }

    @Serializable
    public data class SavedProgram(
        val file: String,
        val name: String,
        val purpose: String,
        val capabilities: List<String>,
        val program: ScratchpadProgram,
    )
}

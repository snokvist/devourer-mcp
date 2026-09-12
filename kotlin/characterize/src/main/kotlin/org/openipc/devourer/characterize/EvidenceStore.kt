package org.openipc.devourer.characterize

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * The hardware evidence database: one JSON file per adapter, on disk.
 *
 * A real database would buy transactions and queries this does not need, and
 * cost the thing that matters most here — that the evidence stays readable and
 * diffable by a person six months later, without the tool that wrote it. These
 * files are the durable artefact; the runtime is replaceable.
 *
 * Runs accumulate rather than overwrite. An adapter that passed last week and
 * fails today is the single most interesting thing this database can show, and
 * a store that kept only the latest result would erase exactly that.
 */
public class EvidenceStore(private val root: Path) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    init {
        Files.createDirectories(root)
    }

    public fun path(identity: AdapterIdentity): Path = root.resolve("${identity.key}.json")

    public fun load(identity: AdapterIdentity): Characterization? = load(identity.key)

    public fun load(key: String): Characterization? {
        val p = root.resolve("$key.json")
        if (!Files.exists(p)) return null
        return runCatching {
            json.decodeFromString(Characterization.serializer(), Files.readString(p))
        }.getOrNull()
    }

    public fun list(): List<Characterization> =
        Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }
                .map { p ->
                    runCatching {
                        json.decodeFromString(Characterization.serializer(), Files.readString(p))
                    }.getOrNull()
                }
                .toList()
                .filterNotNull()
        }.sortedBy { it.identity.key }

    /**
     * Writes atomically: a temp file plus a rename.
     *
     * A characterization run can take minutes and is often the only record that
     * it happened. Truncating the previous file and dying mid-write would lose
     * both the new result and the history it was appending to.
     */
    public fun save(record: Characterization) {
        val target = path(record.identity)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(Characterization.serializer(), record))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Merges a new run into whatever is already on file for this adapter.
     *
     * Demonstrated claims are merged by subject keeping the STRONGEST state
     * ever reached, not the most recent: an adapter that reached TX_VERIFIED in
     * March and was only monitored in April has still been shown to transmit.
     * A regression is not silently swallowed either — it stays in [runs], where
     * a comparison across runs can find it.
     */
    public fun record(
        identity: AdapterIdentity,
        chip: String,
        backend: String,
        marketingNames: String,
        sourceClaims: org.openipc.devourer.radio.RadioCapabilities,
        run: CharacterizationRun,
        unverified: List<UnverifiedCapability>,
    ): Characterization {
        val now = System.currentTimeMillis()
        val existing = load(identity)

        val merged = buildMap {
            existing?.demonstrated?.forEach { put(it.subject, it) }
            run.claims.forEach { fresh ->
                val prior = get(fresh.subject)
                put(
                    fresh.subject,
                    if (prior != null && prior.state.rank > fresh.state.rank) prior else fresh,
                )
            }
        }

        val record = Characterization(
            key = identity.key,
            identity = identity,
            chip = chip,
            backend = backend,
            marketingNames = marketingNames,
            sourceClaims = sourceClaims,
            demonstrated = merged.values.sortedByDescending { it.state.rank },
            unverified = unverified,
            runs = ((existing?.runs ?: emptyList()) + run).takeLast(MAX_RUNS),
            firstSeenEpochMs = existing?.firstSeenEpochMs?.takeIf { it > 0 } ?: now,
            lastSeenEpochMs = now,
        )
        save(record)
        return record
    }

    private companion object {
        /** Enough to see a trend; bounded so a nightly job cannot grow forever. */
        const val MAX_RUNS = 50
    }
}

package org.openipc.devourer.characterize

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openipc.devourer.radio.RadioCapabilities
import org.openipc.devourer.radio.VerificationClaim
import org.openipc.devourer.radio.VerificationState

/**
 * Everything known about one physical adapter, and how each part came to be
 * known.
 *
 * The master question this answers, in four separable pieces:
 *
 *   what it is                 -> [identity], [chip], [backend]
 *   what the SOURCE claims     -> [sourceClaims]
 *   what the HARDWARE showed   -> [demonstrated]
 *   what remains unknown       -> [unverified]
 *
 * Keeping the third and fourth apart from the second is the whole point. A
 * capability report is the vendor's description of the silicon; it is not
 * evidence that this particular adapter, with this antenna, on this host, does
 * the thing. Merging them produces a database that looks authoritative and
 * cannot be audited.
 */
@Serializable
public data class Characterization(
    /**
     * The handle this record is filed and looked up under.
     *
     * Serialized rather than left computed: it is what a caller passes to
     * characterize_report later, and a field that exists only in memory is one
     * a consumer of the JSON cannot use.
     */
    val key: String,
    val identity: AdapterIdentity,
    val chip: String,
    val backend: String,
    @SerialName("marketing_names") val marketingNames: String = "",
    /** What devourer's capability report says this part supports. */
    @SerialName("source_claims") val sourceClaims: RadioCapabilities,
    /** What this adapter actually did, each with its evidence. */
    val demonstrated: List<VerificationClaim> = emptyList(),
    /** Claimed capabilities no run here has exercised, and why not. */
    val unverified: List<UnverifiedCapability> = emptyList(),
    /** Every run against this adapter, newest last. */
    val runs: List<CharacterizationRun> = emptyList(),
    @SerialName("first_seen_epoch_ms") val firstSeenEpochMs: Long = 0,
    @SerialName("last_seen_epoch_ms") val lastSeenEpochMs: Long = 0,
) {
    /** The strongest state any run reached. */
    public val state: VerificationState
        get() = demonstrated.maxByOrNull { it.state.rank }?.state
            ?: VerificationState.IMPLEMENTED_IN_SOURCE

    public fun summary(): String = buildString {
        append(chip.ifBlank { identity.usbId })
        append(" (").append(backend).append(") — ").append(state.name)
        if (unverified.isNotEmpty()) {
            append("; ").append(unverified.size).append(" claimed capabilities unverified")
        }
    }
}

/**
 * A capability the source claims but no run has demonstrated.
 *
 * [reason] is required because "unverified" covers two very different
 * situations — nothing has tried yet, versus it cannot be tried on this bench
 * (no peer radio, no spectrum analyser, no second band) — and only the second
 * is a reason to stop asking.
 */
@Serializable
public data class UnverifiedCapability(
    val capability: String,
    val claim: String,
    val reason: String,
    val blocked: Boolean = false,
)

/** One characterization pass: what it did, what it saw, how to repeat it. */
@Serializable
public data class CharacterizationRun(
    val id: String,
    @SerialName("started_at_epoch_ms") val startedAtEpochMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    /** Enough to reproduce: channel, dwell, peers used, software versions. */
    val conditions: Map<String, String> = emptyMap(),
    val claims: List<VerificationClaim> = emptyList(),
    val notes: List<String> = emptyList(),
    /** Set when a step could not run, rather than dropping it silently. */
    val skipped: Map<String, String> = emptyMap(),
)

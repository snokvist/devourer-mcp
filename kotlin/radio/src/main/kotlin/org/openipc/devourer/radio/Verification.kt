package org.openipc.devourer.radio

import kotlinx.serialization.Serializable

/**
 * What is actually known about a chipset, backend or capability.
 *
 * The whole point of this ladder is that **compilation is not verification**.
 * A backend that builds proves a compiler accepted it and nothing else — not
 * that hardware exists, not that it initializes, and certainly not that a
 * frame ever crossed the air. Collapsing these into a boolean "supported" is
 * how a tool ends up confidently reporting capabilities no one ever observed.
 *
 * Each state is strictly stronger than the one before it, except [UNAVAILABLE]
 * and [FAILED], which are terminal outcomes rather than rungs.
 */
@Serializable
public enum class VerificationState(public val rank: Int, public val explanation: String) {
    /** Devourer's source has a backend for it. Says nothing about this build. */
    IMPLEMENTED_IN_SOURCE(1, "a backend exists in the vendored devourer source"),

    /** It compiled and linked here. Still no hardware involved. */
    BUILDS(2, "compiled into this build — no hardware claim whatsoever"),

    /** A matching physical adapter enumerated on USB. */
    DETECTED(3, "a physical adapter matching it was found on the bus"),

    /** The chip powered up, firmware loaded, bring-up returned. */
    INITIALIZED(4, "the chip powered up and completed bring-up"),

    /** Real frames were received from the air and parsed. */
    RX_VERIFIED(5, "frames were received off the air and parsed"),

    /**
     * Transmitted frames were observed by an *independent* receiver.
     *
     * A radio reporting its own TX success is not evidence anything reached
     * the air — the TX path can report a clean submission into a dead PA. Only
     * a second adapter or monitor oracle can promote to this state.
     */
    TX_VERIFIED(6, "transmitted frames were observed by an independent receiver"),

    /** A characterization run completed and its evidence is stored. */
    CHARACTERIZED(7, "a characterization run completed and was persisted"),

    /** No hardware present to test. An absence of evidence, not a failure. */
    UNAVAILABLE(0, "no hardware available to test this"),

    /** Attempted and did not work. The reason is part of the record. */
    FAILED(-1, "attempted and failed"),
    ;

    public val isEvidenceFromHardware: Boolean
        get() = rank >= DETECTED.rank

    /** True when this state was reached without any hardware being involved. */
    public val isSourceOnlyClaim: Boolean
        get() = this == IMPLEMENTED_IN_SOURCE || this == BUILDS
}

/**
 * One claim about one thing, with how it was established.
 *
 * [observedAt] and [evidence] exist so a stored result can be re-read months
 * later and still say what was actually done, rather than leaving a bare
 * enum that has to be trusted.
 */
@Serializable
public data class VerificationClaim(
    val subject: String,
    val state: VerificationState,
    val detail: String = "",
    val observedAtEpochMs: Long = 0,
    val evidence: Map<String, String> = emptyMap(),
) {
    public fun describe(): String = buildString {
        append(subject).append(": ").append(state.name)
        if (detail.isNotBlank()) append(" — ").append(detail)
        if (state.isSourceOnlyClaim) append(" [no hardware evidence]")
    }
}

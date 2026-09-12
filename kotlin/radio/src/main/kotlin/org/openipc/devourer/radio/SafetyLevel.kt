package org.openipc.devourer.radio

import kotlinx.serialization.Serializable

/**
 * How far outside ordinary, well-behaved radio operation a caller is asking to go.
 *
 * This exists because the alternative was prose. The project's own docs promised
 * three levels for weeks while the code had none, and in that state the single
 * most antisocial operation in the system — disabling the MAC's carrier sense so
 * the radio transmits without listening — was reachable *by accident*, as an
 * automatic retry inside `characterize_run`, with no way for a caller to decline.
 *
 * The rule the type enforces: **an operation that can affect anyone else's air
 * must name its level explicitly at the call site.** Not a default, not a
 * config value someone set once. If a tool argument is absent, the level is
 * [NORMAL] and the privileged path is refused.
 */
@Serializable
public enum class SafetyLevel(public val explanation: String) {
    /**
     * Structured, typed frame and PHY descriptions. Bounded transmission on a
     * channel the adapter reports it can tune, with carrier sense on.
     *
     * Everything a well-behaved station does. The default everywhere.
     */
    NORMAL("ordinary operation; carrier sense on, capability-gated"),

    /**
     * Paths whose behaviour is unverified on this hardware, or which are
     * deliberately antisocial on a shared medium.
     *
     * Carrier-sense disable lives here: it is the difference between measuring
     * a link and measuring a MAC's willingness to use it, and on this bench it
     * took an RTL8812AU from 4% to 97% delivery. Legitimate and sometimes the
     * only way to get a number — but it talks over anyone sharing the channel,
     * so it is asked for by name or not at all.
     */
    EXPERIMENTAL("unverified or deliberately antisocial; affects others on the channel"),

    /**
     * The low-level escape hatch: raw radiotap frames the caller assembled
     * byte-for-byte, bypassing the structured TX description.
     *
     * Never the normal interface, and never reachable by accident. A frame
     * built here is not validated against the adapter's capability report
     * beyond what the hardware itself refuses.
     */
    DEVELOPER("raw, unvalidated access for development; no structural guard rails"),
    ;

    public fun permits(required: SafetyLevel): Boolean = ordinal >= required.ordinal

    public companion object {
        /**
         * Parses a caller-supplied level. Anything absent or unrecognised is
         * [NORMAL] — an unparseable level must never widen access, and a typo
         * in `"experimental"` should fail closed rather than silently grant.
         */
        public fun parse(raw: String?): SafetyLevel =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NORMAL
    }
}

/**
 * Refusal because the caller did not ask for the level the operation needs.
 *
 * Distinct from [CapabilityException]: that one means the hardware cannot, this
 * one means the caller did not say they wanted to. Conflating them would let a
 * reader think a refusal was a hardware limit and go looking for other hardware.
 */
public class SafetyLevelException(
    public val required: SafetyLevel,
    public val provided: SafetyLevel,
    public val operation: String,
) : RuntimeException(
    "$operation requires safety level ${required.name} (${required.explanation}) " +
        "but the call provided ${provided.name}. Pass safety_level=\"${required.name.lowercase()}\" " +
        "to ask for it explicitly.",
) {
    public companion object {
        public fun require(operation: String, required: SafetyLevel, provided: SafetyLevel) {
            if (!provided.permits(required)) {
                throw SafetyLevelException(required, provided, operation)
            }
        }
    }
}

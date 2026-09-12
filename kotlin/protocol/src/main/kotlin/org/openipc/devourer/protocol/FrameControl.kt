package org.openipc.devourer.protocol

/**
 * The 802.11 Frame Control field — the two bytes that say what a frame is.
 *
 * Decoded here rather than in the bridge because it is pure parsing of bytes we
 * already carry, and keeping it on the Kotlin side means a replayed capture and
 * a live one go through exactly the same code.
 */
public data class FrameControl(private val b0: Byte, private val b1: Byte) {
    private val v0: Int get() = b0.toInt() and 0xff
    private val v1: Int get() = b1.toInt() and 0xff

    public val protocolVersion: Int get() = v0 and 0x03
    public val type: FrameType get() = FrameType.of((v0 shr 2) and 0x03)
    public val subtype: Int get() = (v0 shr 4) and 0x0f

    public val toDs: Boolean get() = (v1 and 0x01) != 0
    public val fromDs: Boolean get() = (v1 and 0x02) != 0
    public val moreFragments: Boolean get() = (v1 and 0x04) != 0

    /**
     * The retry bit: this frame is a retransmission.
     *
     * Distinct from the per-frame retry *count* a TX report carries — this is
     * what the transmitter admitted on air, and it is the only retry signal a
     * passive monitor gets.
     */
    public val retry: Boolean get() = (v1 and 0x08) != 0
    public val powerManagement: Boolean get() = (v1 and 0x10) != 0
    public val moreData: Boolean get() = (v1 and 0x20) != 0
    public val protectedFrame: Boolean get() = (v1 and 0x40) != 0
    public val order: Boolean get() = (v1 and 0x80) != 0

    /** A stable, greppable name such as `mgmt/beacon` or `ctrl/block-ack`. */
    public val name: String
        get() = NAMES[type to subtype] ?: "${type.short}/0x${subtype.toString(16)}"

    public companion object {
        private val NAMES: Map<Pair<FrameType, Int>, String> = mapOf(
            (FrameType.MANAGEMENT to 0) to "mgmt/assoc-req",
            (FrameType.MANAGEMENT to 1) to "mgmt/assoc-resp",
            (FrameType.MANAGEMENT to 2) to "mgmt/reassoc-req",
            (FrameType.MANAGEMENT to 3) to "mgmt/reassoc-resp",
            (FrameType.MANAGEMENT to 4) to "mgmt/probe-req",
            (FrameType.MANAGEMENT to 5) to "mgmt/probe-resp",
            (FrameType.MANAGEMENT to 8) to "mgmt/beacon",
            (FrameType.MANAGEMENT to 9) to "mgmt/atim",
            (FrameType.MANAGEMENT to 10) to "mgmt/disassoc",
            (FrameType.MANAGEMENT to 11) to "mgmt/auth",
            (FrameType.MANAGEMENT to 12) to "mgmt/deauth",
            (FrameType.MANAGEMENT to 13) to "mgmt/action",
            (FrameType.CONTROL to 7) to "ctrl/wrapper",
            (FrameType.CONTROL to 8) to "ctrl/block-ack-req",
            (FrameType.CONTROL to 9) to "ctrl/block-ack",
            (FrameType.CONTROL to 10) to "ctrl/ps-poll",
            (FrameType.CONTROL to 11) to "ctrl/rts",
            (FrameType.CONTROL to 12) to "ctrl/cts",
            (FrameType.CONTROL to 13) to "ctrl/ack",
            (FrameType.CONTROL to 14) to "ctrl/cf-end",
            (FrameType.DATA to 0) to "data/data",
            (FrameType.DATA to 4) to "data/null",
            (FrameType.DATA to 8) to "data/qos-data",
            (FrameType.DATA to 12) to "data/qos-null",
        )
    }
}

public enum class FrameType(public val short: String) {
    MANAGEMENT("mgmt"),
    CONTROL("ctrl"),
    DATA("data"),
    EXTENSION("ext"),
    ;

    public companion object {
        public fun of(raw: Int): FrameType = entries.getOrElse(raw) { EXTENSION }
    }
}

/**
 * Addresses carried by a frame, as far as its type allows.
 *
 * 802.11 address semantics depend on to-DS/from-DS, and control frames carry
 * fewer than three addresses — an ACK has only a receiver. Returning nulls
 * rather than zeros keeps "this frame has no such address" distinct from "the
 * address is 00:00:00:00:00:00", which is a real address a broken device emits.
 */
public data class FrameAddresses(
    val receiver: String?,
    val transmitter: String?,
    val destination: String?,
    val source: String?,
    val bssid: String?,
) {
    public companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * Subtypes that carry no addr2. Hoisted out of [parse] — it used to
         * allocate this set per control frame.
         */
        private val NO_TRANSMITTER = setOf(12, 13) // cts, ack

        public fun parse(payload: ByteArray, fc: FrameControl): FrameAddresses {
            fun mac(at: Int): String? {
                if (payload.size < at + 6) return null
                /*
                 * A nibble table, not String.format.
                 *
                 * `"%02x".format(b)` builds a java.util.Formatter per call —
                 * 24 of them per frame across four addresses. Summarising a
                 * full 200k-frame ring therefore allocated ~4.8M Formatters,
                 * and a scratchpad samples that twice a second. This writes
                 * into one 17-char array instead.
                 */
                val out = CharArray(17)
                var o = 0
                for (i in 0 until 6) {
                    if (i > 0) out[o++] = ':'
                    val v = payload[at + i].toInt() and 0xff
                    out[o++] = HEX[v ushr 4]
                    out[o++] = HEX[v and 0x0f]
                }
                return String(out)
            }

            val a1 = mac(4)
            // A control frame's layout is subtype-specific: CTS and ACK stop
            // after addr1. Reading addr2 there would return FCS bytes as a MAC.
            if (fc.type == FrameType.CONTROL) {
                val hasA2 = fc.subtype !in NO_TRANSMITTER
                return FrameAddresses(
                    receiver = a1,
                    transmitter = if (hasA2) mac(10) else null,
                    destination = null,
                    source = null,
                    bssid = null,
                )
            }

            val a2 = mac(10)
            val a3 = mac(16)
            return when {
                fc.type == FrameType.MANAGEMENT -> FrameAddresses(a1, a2, a1, a2, a3)
                !fc.toDs && !fc.fromDs -> FrameAddresses(a1, a2, a1, a2, a3)
                !fc.toDs && fc.fromDs -> FrameAddresses(a1, a2, a1, a3, a2)
                fc.toDs && !fc.fromDs -> FrameAddresses(a1, a2, a3, a2, a1)
                // to-DS and from-DS: a 4-address WDS frame; addr4 follows the
                // sequence-control field at offset 24.
                else -> FrameAddresses(a1, a2, a3, mac(24), null)
            }
        }
    }
}

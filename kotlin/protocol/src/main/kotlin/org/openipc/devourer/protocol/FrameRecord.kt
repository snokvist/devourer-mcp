package org.openipc.devourer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One received frame as the bridge delivers it: `native/bridge/src/Protocol.h`
 * `FrameRecord`, then [frameLength] bytes of 802.11 MPDU.
 *
 * The field set is the whole of devourer's `rx_pkt_attrib`, deliberately. Which
 * fields carry a measurement is a per-chip, per-frame question — the MT7612U
 * reports no [tsfl] and strips the FCS, while a Realtek fills [tsfl] but often
 * leaves [snr] at zero — and answering it is the analysis layer's job. A
 * transport that pre-selected "useful" fields would silently cap what the
 * instrument can ever reason about.
 *
 * Zero is not "absent". A radio that fills only part of the PHY-status block
 * leaves the rest at zero, and averaging an unfilled field drags the mean
 * toward it. Use [rssiChains]/[snrChains] and the per-radio capability report
 * to decide what is real before aggregating.
 */
public data class FrameRecord(
    val sequence: Long,
    val hostNanos: Long,
    val session: Int,
    val frameLength: Int,
    /** The chip's reported length; exceeds [frameLength] when [truncated]. */
    val packetLength: Int,
    val sequenceNumber: Int,
    /** 9-bit rate code: HT from 0x80, VHT from 0x100, HE from 0x180. */
    val dataRate: Int,
    val fragmentNumber: Int,
    val priority: Int,
    /** Chip RX timestamp, TSF low 32 bits. Always 0 where the chip has none. */
    val tsfl: Long,
    val bandwidth: Int,
    val stbc: Int,
    val ldpc: Int,
    val shortGi: Int,
    /** Kestrel PPDU format classification; 0xff on generations without one. */
    val ppduType: Int,
    val ppduCount: Int,
    val scrambler: Int,
    /** Signed hardware units; kHz = raw * 2.5. */
    val cfoTail: Int,
    val rssi: IntArray,
    val snr: IntArray,
    val evm: IntArray,
    /** Raw RX-descriptor PHY-status bit: the PHY wrote a report for this frame. */
    val phyStatus: Boolean,
    val crcError: Boolean,
    val icvError: Boolean,
    val decrypted: Boolean,
    val encryption: Int,
    val qos: Boolean,
    val moreData: Boolean,
    val moreFragments: Boolean,
    /** This MPDU arrived inside an aggregated PPDU. */
    val aggregated: Boolean,
    /** False on MT7612U: the trailing 4 bytes are an FCE trailer, not an FCS. */
    val fcsPresent: Boolean,
    val reportType: PacketReportType,
    /** Sender's hardware TX-egress TSF; only beacons and probe responses. */
    val txEgressTsf: Long?,
    val truncated: Boolean,
    /**
     * RF chains this adapter actually has — the authoritative width of [rssi],
     * [snr] and [evm].
     *
     * Counting non-zero slots instead would be wrong. On a 2T2R RTL8812A the
     * [2] and [3] SNR slots are filled from `csi_current`, which carries stream
     * CSI on that part and is path C/D SNR only on an 8814AU. Those bytes are
     * routinely non-zero, so a naive reader reports four chains on a two-chain
     * radio and publishes CSI values as SNR measurements.
     *
     * Note this is the SILICON's chain count, not the number of antenna
     * connectors on the board. A 2T2R part can sit behind four antennas with
     * diversity switching; nothing in the chip's capability report can see
     * that. Antenna topology is a board fact, established by measurement or by
     * being told, never inferred from the chip.
     */
    val rxChains: Int,
    val payload: ByteArray,
) {
    /** Chains reporting a signal, never more than the radio actually has. */
    public val rssiChains: Int
        get() = (0 until effectiveChains).count { rssi[it] != 0 }

    public val snrChains: Int
        get() = (0 until effectiveChains).count { snr[it] != 0 }

    /** [rxChains], or a conservative 4 when the bridge did not report it. */
    private val effectiveChains: Int get() = if (rxChains in 1..4) rxChains else 4

    /** RSSI for real chains only, in chain order. */
    public val rssiByChain: List<Int> get() = rssi.take(effectiveChains)

    public val snrByChain: List<Int> get() = snr.take(effectiveChains)

    /** 802.11 frame control, when the payload is long enough to carry it. */
    public val frameControl: FrameControl?
        get() = if (payload.size >= 2) FrameControl(payload[0], payload[1]) else null

    // Generated equals/hashCode would compare arrays by identity, which makes
    // two records holding identical bytes unequal. Records are compared in
    // tests and deduplicated in the capture store, so this has to be by value.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameRecord) return false
        return sequence == other.sequence &&
            hostNanos == other.hostNanos &&
            session == other.session &&
            frameLength == other.frameLength &&
            packetLength == other.packetLength &&
            sequenceNumber == other.sequenceNumber &&
            dataRate == other.dataRate &&
            fragmentNumber == other.fragmentNumber &&
            priority == other.priority &&
            tsfl == other.tsfl &&
            bandwidth == other.bandwidth &&
            stbc == other.stbc &&
            ldpc == other.ldpc &&
            shortGi == other.shortGi &&
            ppduType == other.ppduType &&
            ppduCount == other.ppduCount &&
            scrambler == other.scrambler &&
            cfoTail == other.cfoTail &&
            rxChains == other.rxChains &&
            rssi.contentEquals(other.rssi) &&
            snr.contentEquals(other.snr) &&
            evm.contentEquals(other.evm) &&
            phyStatus == other.phyStatus &&
            crcError == other.crcError &&
            icvError == other.icvError &&
            decrypted == other.decrypted &&
            encryption == other.encryption &&
            qos == other.qos &&
            moreData == other.moreData &&
            moreFragments == other.moreFragments &&
            aggregated == other.aggregated &&
            fcsPresent == other.fcsPresent &&
            reportType == other.reportType &&
            txEgressTsf == other.txEgressTsf &&
            truncated == other.truncated &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = sequence.hashCode()
        h = 31 * h + session
        h = 31 * h + sequenceNumber
        h = 31 * h + rssi.contentHashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    public companion object {
        /** Must equal `sizeof(bridge::FrameRecord)`; `hello` reports the truth. */
        public const val HEADER_BYTES: Int = 88

        /** 'D','V','R','F' little-endian. */
        public const val MAGIC: Int = 0x46525644

        /**
         * Decodes one record from [header] (exactly [HEADER_BYTES]) plus its
         * already-read [payload].
         *
         * @throws FrameDesyncException if the magic is wrong — which means the
         * reader lost record alignment, and every subsequent frame would be
         * garbage. Failing here is the only honest option.
         */
        public fun decode(header: ByteBuffer, payload: ByteArray): FrameRecord {
            val b = header.order(ByteOrder.LITTLE_ENDIAN)
            val magic = b.getInt(0)
            if (magic != MAGIC) throw FrameDesyncException(magic)

            fun u8(at: Int) = b.get(at).toInt() and 0xff
            fun i8(at: Int) = b.get(at).toInt()
            fun u16(at: Int) = b.getShort(at).toInt() and 0xffff
            fun u32(at: Int) = b.getInt(at).toLong() and 0xffff_ffffL
            fun bytes4(at: Int, signed: Boolean) =
                IntArray(4) { if (signed) i8(at + it) else u8(at + it) }

            val hasTxTsf = u8(76) != 0
            return FrameRecord(
                sequence = b.getLong(8),
                hostNanos = b.getLong(16),
                session = b.getInt(24),
                frameLength = b.getInt(28),
                packetLength = u16(32),
                sequenceNumber = u16(34),
                dataRate = u16(36),
                fragmentNumber = u8(38),
                priority = u8(39),
                tsfl = u32(40),
                bandwidth = u8(44),
                stbc = u8(45),
                ldpc = u8(46),
                shortGi = u8(47),
                ppduType = u8(48),
                ppduCount = u8(49),
                scrambler = u8(50),
                cfoTail = i8(51),
                rssi = bytes4(52, signed = false),
                snr = bytes4(56, signed = true),
                evm = bytes4(60, signed = true),
                phyStatus = u8(64) != 0,
                crcError = u8(66) != 0,
                icvError = u8(67) != 0,
                decrypted = u8(68) != 0,
                encryption = u8(69),
                qos = u8(70) != 0,
                moreData = u8(71) != 0,
                moreFragments = u8(72) != 0,
                aggregated = u8(73) != 0,
                fcsPresent = u8(74) != 0,
                reportType = PacketReportType.of(u8(75)),
                txEgressTsf = if (hasTxTsf) b.getLong(80) else null,
                truncated = u8(77) != 0,
                rxChains = u8(78),
                payload = payload,
            )
        }
    }
}

/** `RX_PACKET_TYPE` — not every record off the air is a received frame. */
public enum class PacketReportType {
    NORMAL_RX,
    TX_REPORT_CCX,
    TX_REPORT,
    HISR_REPORT,
    C2H_PACKET,
    UNKNOWN,
    ;

    public companion object {
        public fun of(raw: Int): PacketReportType =
            entries.getOrElse(raw) { UNKNOWN }
    }
}

public class FrameDesyncException(magic: Int) : IllegalStateException(
    "frame stream desynchronized: expected magic DVRF, read 0x${magic.toUInt().toString(16)}. " +
        "Every record after this point would be misread, so the stream is unusable.",
)

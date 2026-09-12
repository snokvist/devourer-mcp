#ifndef DEVOURER_BRIDGE_PROTOCOL_H
#define DEVOURER_BRIDGE_PROTOCOL_H

/* The bridge wire protocol — the whole Kotlin<->native boundary.
 *
 * Two planes over one Unix socket path, distinguished by the first line a
 * client sends:
 *
 *   CONTROL  requests and responses as JSON Lines, one object per line.
 *            {"id":N,"op":"radio.list", ...}
 *            -> {"id":N,"ok":true,"result":{...}}
 *            -> {"id":N,"ok":false,"error":{"code":"...","message":"..."}}
 *            Unsolicited events carry no id: {"event":"radio.gone", ...}
 *
 *   FRAMES   a one-way binary stream of received frames for one session.
 *            The client sends {"attach":<session>} and then reads records.
 *
 * Why two planes and not one: frames arrive at up to tens of thousands per
 * second with a variable-length payload each. Base64 inside JSON would cost
 * ~33% inflation plus an encode/decode step per frame on the hot path, and
 * would interleave a high-rate stream with the request/response traffic that
 * has to stay responsive. Splitting them keeps control latency independent of
 * capture load, and the binary record maps onto a Kotlin ByteBuffer with no
 * parsing at all.
 *
 * Endianness is little-endian throughout: every target this runs on (x86_64,
 * aarch64, Steam Deck, Android) is LE, and the JVM's ByteBuffer reads it
 * directly with ByteOrder.LITTLE_ENDIAN. A big-endian host would need a byte
 * swap here, not a protocol change.
 *
 * PROTOCOL_VERSION is the compatibility gate. The Kotlin side refuses to
 * attach to a bridge whose major version differs. Bump the major on any
 * incompatible change to a record layout or an op's contract; bump the minor
 * when adding ops or optional fields that an older client can ignore. */

#include <cstdint>

namespace bridge {

inline constexpr int kProtocolVersionMajor = 1;
/* 2: radio.rx_gain and radio.cca_gates reject a field they do not recognise
 * instead of ignoring it. Strictly this narrows an op's contract, which the
 * rule above would put in a major bump — but both ops are newer than the
 * last release and have no client, and the behaviour being removed was a
 * misspelled request silently doing nothing and reporting the unchanged
 * state as if it had. Nothing that worked stops working, so: minor, and
 * recorded here rather than decided silently. */
inline constexpr int kProtocolVersionMinor = 2;

/* 'D','V','R','F' — present on every frame record so a desynchronized reader
 * fails loudly at the next record instead of interpreting payload as a
 * header. */
inline constexpr uint32_t kFrameMagic = 0x46525644u;

/* Frame record: this fixed header, then `frame_len` bytes of 802.11 MPDU.
 * `record_len` counts everything after itself (header remainder + payload), so
 * a reader that does not understand a future, larger header can still skip to
 * the next record.
 *
 * The field set is deliberately the whole of devourer's rx_pkt_attrib rather
 * than a chosen subset: which fields are meaningful is a per-chip, per-frame
 * question (PhyStsFill decides whether the signal block is a measurement or
 * still zero), and that judgement belongs to the analysis layer, not to the
 * transport. Dropping a field here would silently cap what the instrument can
 * ever reason about. */
#pragma pack(push, 1)
struct FrameRecord {
  uint32_t magic;      /* kFrameMagic */
  uint32_t record_len; /* bytes following this field */

  uint64_t seq;      /* bridge-assigned, monotonic per session, never reused */
  uint64_t host_ns;  /* CLOCK_REALTIME at parse; host clock, NOT the chip's */
  uint32_t session;  /* which radio produced this */
  uint32_t frame_len;/* payload bytes following the header */

  /* --- identity / framing --- */
  uint16_t pkt_len;  /* the chip's reported length; may exceed frame_len when
                        the payload was truncated by max_frame_bytes */
  uint16_t seq_num;
  uint16_t data_rate;/* 9-bit rate code: HT 0x80+, VHT 0x100+, HE 0x180+ */
  uint8_t  frag_num;
  uint8_t  priority;

  /* --- PHY --- */
  uint32_t tsfl;     /* chip RX timestamp, TSF low 32 bits */
  uint8_t  bw;
  uint8_t  stbc;
  uint8_t  ldpc;
  uint8_t  sgi;
  uint8_t  ppdu_type;/* Kestrel PPDU format; 0xff where the chip has no field */
  uint8_t  ppdu_cnt;
  uint8_t  scrambler;
  int8_t   cfo_tail; /* signed HW units; kHz = raw * 2.5 */

  uint8_t  rssi[4];  /* per RF path A..D; [2..3] zero on 2-path chips */
  int8_t   snr[4];
  int8_t   evm[4];

  /* --- flags (one byte each: a bitfield would save 2 bytes and cost every
   *     reader a mask table, on a record already dominated by its payload) --- */
  uint8_t  physt;       /* raw descriptor PHY-status bit */
  /* Was phy_fill, carrying devourer's PhyStsFill (None/Power/Full) — which of
   * the signal fields a PHY-status report actually wrote. It shipped
   * hardwired to 0, i.e. "nothing filled", on every frame from every chip,
   * which is a worse answer than no answer: it contradicts physt and a
   * non-zero rssi, and a reader that believed it would discard real
   * measurements.
   *
   * It cannot be filled from here. PhyStsFill is a local at devourer's parse
   * site (jaguar2, jaguar3; no other generation computes one) and never
   * reaches rx_pkt_attrib, so surfacing it means a vendored patch adding a
   * field to that struct and setting it in both device paths — worth doing
   * upstream, and not worth faking here. Neither adapter on this bench is a
   * Jaguar2/3 part, so such a patch could not be verified either.
   *
   * Kept as a reserved byte rather than removed so that every offset after
   * it, and sizeof(FrameRecord), stay exactly where FrameRecord.kt and
   * FrameRecordTest expect them. */
  uint8_t  _reserved_phy_fill;
  uint8_t  crc_err;
  uint8_t  icv_err;
  uint8_t  bdecrypted;
  uint8_t  encrypt;     /* 0 = none */
  uint8_t  qos;
  uint8_t  mdata;
  uint8_t  mfrag;
  uint8_t  paggr;       /* arrived inside an aggregated PPDU */
  uint8_t  fcs_present; /* false on MT7612U: those 4 bytes are an FCE trailer */
  uint8_t  pkt_rpt_type;/* RX_PACKET_TYPE: 0 normal, 1/2 TX report, 3 HISR, 4 C2H */

  /* --- derived, computed once here so every consumer agrees --- */
  uint8_t  has_tx_egress_tsf; /* beacon/probe-resp carry the sender's TX TSF */
  uint8_t  truncated;         /* payload was cut to max_frame_bytes */

  /* How many RF chains this adapter actually has — the authoritative width of
   * the rssi/snr/evm arrays above.
   *
   * Counting non-zero slots instead is WRONG, and quietly so. On a 2T2R
   * RTL8812A the [2] and [3] SNR slots are not path C/D SNR at all: devourer
   * fills them from csi_current, which on 8812 carries stream 1/2 CSI and is
   * meaningful only on an 8814AU (src/jaguar1/FrameParser.cpp says exactly
   * this). Those bytes are frequently non-zero, so a "count the non-zero
   * chains" reader reports four chains on a two-chain radio and publishes CSI
   * numbers as SNR measurements.
   *
   * Carried per-record rather than looked up per-session so a replayed or
   * exported capture stays self-describing. */
  uint8_t  rx_chains;
  uint8_t  _pad;
  uint64_t tx_egress_tsf;
};
#pragma pack(pop)

static_assert(sizeof(FrameRecord) == 88, "FrameRecord layout is wire-visible; "
                                         "the Kotlin reader hardcodes offsets");

} // namespace bridge

#endif /* DEVOURER_BRIDGE_PROTOCOL_H */

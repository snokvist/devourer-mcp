#ifndef DEVOURER_BRIDGE_RADIOTAP_POWER_H
#define DEVOURER_BRIDGE_RADIOTAP_POWER_H

/* Attach a per-frame radiotap DBM_TX_POWER field to a header produced by
 * devourer::build_stream_radiotap.
 *
 * Separated from main.cpp so it is unit-testable: the first implementation
 * wrote the field under presence bit 5, which is DBM_ANTSIGNAL; DBM_TX_POWER is
 * bit 10. The consumer matches the latter, so a wrong bit is completely silent
 * — the field is discarded and the feature only looks inert — and it shipped
 * until a hardware run and a review caught it. The byte layout is therefore
 * pinned by native/bridge/tests/radiotap_power_test.cpp.
 *
 * The field must appear in bit order — bit 10 is below TX_FLAGS (bit 15) — so
 * appending it would be out of order and a strict parser rejects that; each of
 * the builder's four layouts is known exactly, so this rebuilds the header with
 * the field in place.
 *
 * `db` is a signed whole-dB delta against the calibrated per-rate table (the
 * radiotap convention the backends parse; a backend multiplies by 4 to reach
 * its qdB units). It applies per frame; the session-wide radio_tx_power offset
 * is a separate knob and the two compose.
 *
 * J1 CAVEAT: lengthening the 13-byte HT radiotap trips `radiotap_length !=
 * 0x0d` in RtlJaguarDevice, which still derives the TX-descriptor RATE_ID from
 * that guess rather than the parsed rate family — so on an 8814A (the only J1
 * die with per-packet power) an HT frame carrying per-packet power would be
 * grouped as VHT. Recorded as a vendored finding; J2/J3 compute rate_id from
 * the parsed rate (rateid_for_mgn) and are unaffected. */

#include <cstdint>
#include <string>
#include <vector>

namespace bridge {

inline bool insert_dbm_tx_power(std::vector<uint8_t> &rt, int db,
                                std::string &err) {
  if (rt.size() < 8) {
    err = "radiotap header too short to carry per-packet power";
    return false;
  }
  const uint32_t present =
      static_cast<uint32_t>(rt[4]) | (static_cast<uint32_t>(rt[5]) << 8) |
      (static_cast<uint32_t>(rt[6]) << 16) | (static_cast<uint32_t>(rt[7]) << 24);
  constexpr uint32_t kRate = 1u << 2, kDbm = 1u << 10, kTxFlags = 1u << 15;
  constexpr uint32_t kMcs = 1u << 19, kVht = 1u << 21, kHe = 1u << 23;
  if (present & kDbm)
    return true; /* already present */
  const uint8_t d = static_cast<uint8_t>(static_cast<int8_t>(db));
  std::vector<uint8_t> out;
  if ((present & kMcs) || (present & kVht) || (present & kHe)) {
    /* HT / VHT / HE all carry TX_FLAGS first; DBM (bit 10) goes ahead of it,
     * with a pad so TX_FLAGS stays 2-byte aligned. */
    out.reserve(rt.size() + 2);
    out.insert(out.end(), rt.begin(), rt.begin() + 8);
    out.push_back(d);
    out.push_back(0);
    out.insert(out.end(), rt.begin() + 8, rt.end());
    const uint32_t np = present | kDbm;
    out[4] = static_cast<uint8_t>(np);
    out[5] = static_cast<uint8_t>(np >> 8);
    out[6] = static_cast<uint8_t>(np >> 16);
    out[7] = static_cast<uint8_t>(np >> 24);
    const uint16_t nl = static_cast<uint16_t>(out.size());
    out[2] = static_cast<uint8_t>(nl);
    out[3] = static_cast<uint8_t>(nl >> 8);
  } else if ((present & kRate) && (present & kTxFlags)) {
    /* Legacy: RATE(1) then a pad before TX_FLAGS; the DBM byte takes the pad
     * slot, so the length is unchanged and TX_FLAGS stays aligned. */
    rt[9] = d;
    const uint32_t np = present | kDbm;
    rt[4] = static_cast<uint8_t>(np);
    rt[5] = static_cast<uint8_t>(np >> 8);
    rt[6] = static_cast<uint8_t>(np >> 16);
    rt[7] = static_cast<uint8_t>(np >> 24);
    return true;
  } else {
    err = "radiotap header has no rate/MCS field to attach per-packet power to";
    return false;
  }
  rt.swap(out);
  return true;
}

} // namespace bridge

#endif /* DEVOURER_BRIDGE_RADIOTAP_POWER_H */

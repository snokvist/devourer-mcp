/* Pins the radiotap DBM_TX_POWER insertion byte layout.
 *
 * This exists because the first implementation used presence bit 5
 * (DBM_ANTSIGNAL) instead of bit 10 (DBM_TX_POWER): a wrong bit is completely
 * silent — consumers match bit 10 and simply see nothing — so the Kotlin tests
 * could not catch it and it took a hardware run plus a review. These cases
 * assert the presence bit and the exact field offsets for all four layouts the
 * devourer builder produces, so a recurrence fails in CI. */

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "RadiotapBuilder.h"
#include "RadiotapPower.h"
#include "TxMode.h"

namespace {

int g_failures = 0;

void check(bool ok, const char *what) {
  if (ok) {
    std::printf("  PASS %s\n", what);
  } else {
    std::printf("  FAIL %s\n", what);
    ++g_failures;
  }
}

uint32_t present_of(const std::vector<uint8_t> &rt) {
  return static_cast<uint32_t>(rt[4]) | (static_cast<uint32_t>(rt[5]) << 8) |
         (static_cast<uint32_t>(rt[6]) << 16) |
         (static_cast<uint32_t>(rt[7]) << 24);
}

uint16_t len_of(const std::vector<uint8_t> &rt) {
  return static_cast<uint16_t>(rt[2] | (rt[3] << 8));
}

constexpr uint32_t kDbm = 1u << 10;    /* DBM_TX_POWER */
constexpr uint32_t kAntSignal = 1u << 5; /* DBM_ANTSIGNAL — the bug */

/* HT / VHT / HE: the field is inserted before TX_FLAGS, so the header grows by
 * two (DBM + pad) and every byte from TX_FLAGS on shifts by two. */
void check_shifted(const char *name, const char *spec, int db) {
  std::vector<uint8_t> original =
      devourer::build_stream_radiotap(devourer::parse_tx_mode_str(spec));
  std::vector<uint8_t> rt = original;
  std::string err;
  const bool ok = bridge::insert_dbm_tx_power(rt, db, err);
  const uint8_t d = static_cast<uint8_t>(static_cast<int8_t>(db));

  check(ok, (std::string(name) + ": insert returns true").c_str());
  check(len_of(rt) == len_of(original) + 2,
        (std::string(name) + ": it_len grows by 2").c_str());
  check((present_of(rt) & kDbm) != 0,
        (std::string(name) + ": presence bit 10 set").c_str());
  check((present_of(rt) & kAntSignal) == 0,
        (std::string(name) + ": presence bit 5 NOT set").c_str());
  check(rt[8] == d, (std::string(name) + ": DBM byte at offset 8").c_str());
  check(rt[9] == 0, (std::string(name) + ": alignment pad at offset 9").c_str());
  bool tail_ok = rt.size() >= 10 &&
                 std::equal(original.begin() + 8, original.end(), rt.begin() + 10);
  check(tail_ok, (std::string(name) + ": TX_FLAGS.. shifted intact").c_str());
}

}  // namespace

int main() {
  check_shifted("HT", "MCS3/20", -12);
  check_shifted("VHT", "VHT1SS_MCS3/80", -12);
  check_shifted("HE", "HE1SS_MCS3/20", -12);

  /* Legacy: RATE occupies offset 8 and a pad sits before TX_FLAGS; the DBM byte
   * reuses that pad, so the length does not change. */
  {
    std::vector<uint8_t> original =
        devourer::build_stream_radiotap(devourer::parse_tx_mode_str("6M"));
    std::vector<uint8_t> rt = original;
    std::string err;
    check(bridge::insert_dbm_tx_power(rt, -12, err), "legacy: insert returns true");
    check(len_of(rt) == len_of(original),
          "legacy: it_len unchanged (pad slot reused)");
    check((present_of(rt) & kDbm) != 0, "legacy: presence bit 10 set");
    check((present_of(rt) & kAntSignal) == 0, "legacy: presence bit 5 NOT set");
    check(rt[8] == original[8], "legacy: RATE at offset 8 unchanged");
    check(rt[9] == 0xF4, "legacy: DBM byte at offset 9 (= -12)");
    check(rt[10] == original[10] && rt[11] == original[11],
          "legacy: TX_FLAGS at offset 10 unchanged");
  }

  /* Inserting twice is a no-op (idempotent guard). */
  {
    std::vector<uint8_t> rt =
        devourer::build_stream_radiotap(devourer::parse_tx_mode_str("MCS3/20"));
    std::string err;
    bridge::insert_dbm_tx_power(rt, -6, err);
    const std::vector<uint8_t> once = rt;
    check(bridge::insert_dbm_tx_power(rt, -40, err),
          "double insert: second call returns true");
    check(rt == once, "double insert: second call is a no-op");
  }

  std::printf("%s\n", g_failures == 0 ? "all radiotap power checks passed"
                                       : "radiotap power checks FAILED");
  return g_failures == 0 ? 0 : 1;
}

#ifndef RX_GAIN_H
#define RX_GAIN_H

#include <cstdint>

/* Receive-gain reporting and clamping. Jaguar1 and Jaguar3 currently expose
 * this contract; the other backends retain the honest unsupported defaults.
 *
 * Every backend in this tree has some form of receive-gain index, but its
 * runtime differs: jaguar1's DIG watchdog is opt-in, Jaguar3's RX tick and
 * Jaguar2's ~100 ms dig_step adapt, Kestrel has no DIG monitor, and the mt76
 * port's 1 Hz tracker uses a fixed RSSI input in monitor mode. Those facts
 * motivate this interface; they do not imply every backend implements it.
 *
 * That matters because the index decides more than sensitivity. On Realtek
 * the EDCCA threshold is re-derived from IGI, so a gain pinned at the DIG
 * floor is also a carrier-sense threshold pinned at its most trigger-happy
 * value — measured on an RTL8812AU at bench distance as a transmitter that
 * accepted every frame and aired none of them, on three channels, while its
 * own receiver decoded 98% of what a neighbour sent it.
 *
 * The knob here is therefore a RANGE rather than a value. A range is the one
 * operation correct on every generation: where an adaptive loop exists it
 * steers the loop instead of fighting it (all three loops in this tree
 * already clamp to exactly such bounds internally), where none exists
 * min == max simply sets the gain, and "pin it" needs no second entry point.
 */
namespace devourer {

struct RxGainCaps {
  bool supported = false; /* the index can be read */
  bool settable = false;  /* and clamped */

  /* Hardware/driver limits for the clamp. Higher index is LESS gain on every
   * family that implements this, and that is the only cross-family guarantee
   * made here — the units themselves are native and not comparable. */
  uint8_t index_min = 0;
  uint8_t index_max = 0;
  /* "igi" (Realtek, 7-bit, BB 0xc50/0xe50) or "gain_class" (mt76, 0..2). */
  const char *index_name = "";
  /* dB per index step, 0 when the steps are not uniform or not characterised.
   * Same honesty as TxPowerCaps::step_measured: a caller converting index
   * deltas to dB needs to know whether it may. */
  uint8_t index_step_db = 0;

  /* Is anything actually moving the index, and keyed on what.
   *
   * The distinction this struct exists to publish: "a periodic loop exists"
   * and "a periodic loop is doing something" are different facts, and in this
   * tree all but one backend are the second kind. [automatic_input] says what
   * the loop keys on, or why it is inert. */
  bool automatic = false;
  const char *automatic_input = "";
};

struct RxGainState {
  bool valid = false;
  uint8_t index = 0;     /* read back from the hardware */
  uint8_t range_min = 0; /* the clamp in force */
  uint8_t range_max = 0;
  bool automatic = false; /* a loop is running inside that clamp */
};

} // namespace devourer

#endif /* RX_GAIN_H */

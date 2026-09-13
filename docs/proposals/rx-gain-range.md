# Upstream proposal: a vendor-neutral receive-gain clamp

For OpenIPC/devourer. **Implemented and verified on this bench before
proposing** — the patch is `vendor/patches/0001-rx-gain-range.patch`, applied
to the pinned tree and exercised on two families.

| Backend | Adapter | Result |
|---|---|---|
| jaguar1 | RTL8812AU | implemented, verified: index read, clamp applied, DIG steered, out-of-range refused |
| jaguar3 | RTL8822C (sold as 8812CU) | implemented, verified: index moved 0x20 -> 0x2c -> 0x36 -> 0x22, `rx_energy` agreeing independently each time |
| mt7612u | MT7612U | not implemented: reports `supported:false` via the not-ported default, which is the behaviour under test |
| jaguar2, kestrel, rtl8733b | — | no hardware here; not implemented, not claimed |

The upstream PR will name only the two families actually verified and leave
the rest to the maintainer.

Written after an RTL8812AU refused to transmit on three channels while its
receiver worked perfectly. The hypothesis was that its receive gain, pinned at
maximum, held carrier sense at its most trigger-happy. **Building this is what
proved that wrong** — see "What the knob measured", below. The API is proposed
on the strength of being able to answer the question at all, not on the
strength of the answer.

## The survey that decides the design

Every backend in the tree has a receive-gain index. On five of six, nothing
moves it.

| Family | Index | Bounds | Periodic loop | Default state |
|---|---|---|---|---|
| jaguar1 | IGI `0xc50`/`0xe50` | DIG `[0x1c, 0x2a]` | `PhydmWatchdog`, 2 s | **loop off by default**; floor `0x1c` hard-written at bring-up |
| jaguar2 | IGI `0xc50`/`0xe50` | DIG `[0x1c, 0x3e]` | `dig_step()`, ~100 ms, on by default | genuinely adapting |
| jaguar3 | IGI `0x1d70` | — | none | static — "no background DIG, so IGI is static" |
| kestrel | — | — | none | "no phydm FA/CCA/IGI DIG monitor on this generation" |
| rtl8733b | `igi_toggle()` is a BB-reset helper, not gain | — | none | static |
| mt7612u | `low_gain` class | `0..2` | `mt7612u_phy_tick`, 1 Hz | loop runs; **input pinned** at `avg = -75` → class 1 |

Two things fall out of that table.

**Jaguar2 is the only family where receive gain actually adapts.** Everywhere
else it is a constant chosen at bring-up. On jaguar1 that constant is `0x1c`,
DIG's *floor* — maximum sensitivity — written deliberately to match the kernel
(`phydm_SetIgiFloor_Jaguar`, "runs ~4 dB less sensitive than the kernel driver
… match kernel by writing the floor once here"). Correct for a distant link.
At 30 cm it holds EDCCA at "busy" and the MAC never transmits.

**The MT7612U is not winning because its loop adapts.** `phy_update_channel_gain`
runs every second, but `const int avg = -75` — a monitor consumer has no
associated-station table, so mt76 substitutes -75 and the port pins it. With
20 MHz thresholds of -68/-82 that fixes `low_gain` at 1, the middle class, and
the port notes the `low_gain == 2` arms are unreachable. It works at bench
distance because it is pinned in a *better place*, not because it is adaptive.

So the one shape shared by all six is **an index with bounds**. What differs is
only whether anything moves it between them.

## The proposal

Make the **bounds** the vendor-neutral knob, not the value.

```cpp
/* RxGain.h */
namespace devourer {

/* What a backend can tell you, and let you do, about its receive gain. */
struct RxGainCaps {
  bool supported = false;   /* the index can be read */
  bool settable  = false;   /* and clamped */
  uint8_t index_min = 0;    /* most gain the hardware allows */
  uint8_t index_max = 0;    /* least gain */
  /* Native units, named so a caller cannot mistake one family's index for
   * another's: "igi" (Realtek, 7-bit, 0xc50) or "gain_class" (mt76, 0..2).
   * Higher is always LESS gain on both, and that is the only cross-family
   * guarantee this contract makes. */
  const char *index_name = "";
  /* Is anything moving it, and keyed on what. The field that matters most:
   * "a 1 Hz loop exists" and "a 1 Hz loop is doing something" are different
   * facts, and every current backend except jaguar2 is the second kind. */
  bool automatic = false;
  const char *automatic_input = "";
};

struct RxGainState {
  bool valid = false;
  uint8_t index = 0;        /* as read from the hardware now */
  uint8_t range_min = 0;    /* the clamp in force */
  uint8_t range_max = 0;
  bool automatic = false;   /* a loop is running inside that clamp */
};

} // namespace devourer
```

```cpp
/* IRadio.h — three virtuals, not-ported defaults, per the house rule */

virtual devourer::RxGainCaps GetRxGainCaps() { return {}; }
virtual devourer::RxGainState GetRxGainState() { return {}; }

/* Clamp the receive-gain index to [min, max].
 *
 * Deliberately a range rather than a value, because that is the one operation
 * correct on every generation:
 *   - where a loop exists (jaguar1 DIG, jaguar2 dig_step, mt76 phy_tick) it
 *     STEERS the loop instead of fighting it — all three already clamp to
 *     exactly such bounds internally;
 *   - where none exists it simply sets the gain, with min == max;
 *   - and "pin it" needs no second entry point: min == max is the pin.
 *
 * Returns false where the backend cannot do it. Caps describe the supported
 * envelope, not the backend's initial window; callers that need restoration
 * remember the initial GetRxGainState() range before changing it. */
virtual bool SetRxGainRange(uint8_t min, uint8_t max) {
  (void)min; (void)max;
  return false;
}
```

`DeviceConfig.rx.igi` — which exists, is documented as "fixed initial-gain
index override", and today has exactly one consumer (`HalJaguar2.cpp:2597`) —
becomes the bring-up form of the same thing: `SetRxGainRange(igi, igi)` applied
before the first RX. That makes `DEVOURER_IGI` mean the same on every family
instead of silently doing nothing on five of six.

## Why not the alternatives

**A single `SetRxGain(index)`.** Pins, but cannot express "keep adapting, just
never go below this" — which is the actual fix for jaguar1, whose DIG is fine
and whose floor is wrong. It also defeats jaguar2's working loop rather than
bounding it.

**A normalised 0-100 "sensitivity".** Invents an equivalence between a 7-bit
Realtek IGI and a 3-value mt76 gain class that no measurement supports. The
tree's own style is raw vendor units plus caps describing them — `TxPowerCaps`
with `index_max`, `step_qdb`, `step_measured` is the precedent, and the reason
`step_measured` exists is exactly this.

**Feeding the mt76 loop a real RSSI.** Tempting — it is the variable that
matters at bench distance, and phydm's DIG keying on false-alarm rate is what
makes it conclude "clean, use maximum sensitivity" precisely when a strong near
neighbour makes that wrong. But this port already tried it: an EMA of `rssi[0]`
over every parsed frame, removed because "one -40 dBm neighbour AP drove
low_gain=2 … against a wanted peer at -80 dBm". In monitor mode "the station I
care about" does not exist, so there is no correct input to synthesise. The
honest position is to let the operator bound the loop and say so, which is what
the clamp does.

## What the knob measured

Worth stating plainly, because it is the reason to build the thing and also
the reason not to oversell it.

The EDCCA coupling on Jaguar1 is `L2H = th_l2h_ini + (0x32 - IGI)`, clamped to
10, with `th_l2h_ini = -17`. That formula reproduces every threshold devourer
logged as the clamp moved: IGI 0x1c -> +5, 0x22 -> -1, 0x28 -> -7, 0x2e -> -13.
So a HIGHER gain index gives a LOWER carrier-sense threshold and MORE
deferral, which is the opposite of what was assumed — and backing the gain off
made things dramatically worse: OFDM CCA counts 98 -> 1150 -> 8462, and a
300-frame burst taking 0.3 s -> 13 s -> 36 s -> over two minutes.

Going the other way, to the permissive end of the clamp, helps a little and
not much. With a **fresh radio open per point** — necessary, because a first
sweep that kept one session showed delivery climbing to 96% and its own
return-to-baseline control came back at 90% instead of the 8% it started at —
three interleaved pairs gave:

| igi | L2H | delivered |
|---|---|---|
| 0x1c | +5 | 1.7%, 0.0%, 0.7% |
| 0x14 | +10 | 3.0%, 4.3%, 5.3% |

Consistent in all three pairs, in the direction the coupling predicts, and
about four percentage points. Disabling carrier sense on the same link gives
90%. **Receive gain is not the lever for this deferral**, and the honest
version of that sentence only exists because the knob does.

## Implementation sketch

Ordered by value, and only the first two need to land together.

**jaguar1** — implemented and verified. `PhydmWatchdog` holds the clamp as one
packed atomic, `_rx_gain_range` (high byte `min`, low byte `max`, default
`0x1c2a` — DIG's own window) plus `_gain_range_pinned`; `PinGainRange` updates
it and re-applies the current index under `_dig_mu`, so a host change cannot
race a tick, and `DigTick` clamps to it. `RtlJaguarDevice::SetRxGainRange`
validates against the caps envelope, refuses without corrupting state, and
stores `_rx_gain_min`/`_rx_gain_max`; `ApplyConfiguredRxGain()` applies that
stored clamp on both bring-up paths and after `SetCcaMode`, and seeds a pin
from `rx.igi` only when the configured value is present and in range. Read via
the existing `ReadBBReg(0xc50, 0x7f)`.

Caps: `index_name = "igi"`, `index_min = 0x1c`, `index_max = 0x3e` (the
supported envelope — the default unlinked DIG window is the narrower
`[0x1c, 0x2a]`), `automatic = <watchdog running>`,
`automatic_input = "phydm DIG, keyed on the false-alarm rate"`.

Because caps describe the supported envelope and not the initial window, a
caller restoring state must remember the initial `GetRxGainState()` range
rather than assume the caps' own limits are the default.

**mt7612u** — NOT implemented here; no hardware time was spent on it and the
not-ported default is what it reports. Sketch retained because it is the
family that documents the trap. Clamp `low_gain` right after
it is computed in `phy_update_channel_gain`, store the range in `d->cal`, and
reprogram on the next tick. Caps: `index_name = "gain_class"`, `0..2`,
`automatic = true`, and the string worth the whole patch:
`automatic_input = "min avg RSSI — pinned to -75; no associated-station table in monitor mode"`.

**jaguar3** — implemented and verified. `PhydmRuntimeJaguar3` already has
`get_igi`/`set_igi` and a DIG that runs from the RX tick, so the change is the
same shape as jaguar1's: atomics for the window, `dig()` clamping to them, and
the three virtuals on the device. Caps `[0x1e, 0x3e]`, `automatic = true`.

**jaguar2** — not implemented, no hardware. `dig_step()` already bounds to
`[0x1c, 0x3e]`; making those bounds members and honouring `rx.igi` would be
the same change again.

**kestrel, rtl8733b** — `supported`/`settable` false via the not-ported
default, or read-only caps where the index is readable but static.
Saying "static, nothing adapts it" is itself worth publishing.

## What it would let a caller finally do

- **Read the gain on any backend**, and learn whether anything is adjusting it
  and what that thing keys on. None of that is reachable today; on jaguar1 it
  took reading a log line at bring-up to discover the value was at its floor.
- **Raise the floor without defeating the loop** — `SetRxGainRange(0x24, 0x2a)`
  on jaguar1 keeps DIG running and stops it choosing maximum sensitivity.
- **Sweep gain against delivery**, which is the measurement that turns "the
  gain is at the floor" from a consistent hypothesis into a result. On this
  bench that is one `experiment_link_probe` with an extra axis.

## Verification done

Both families were driven from the bridge with the radio live:

- caps self-consistent, `settable` honoured, out-of-range and `min > max`
  refused with a reason;
- the clamp moves the index and the loop keeps running inside it
  (`Jaguar3 dig: IGI=0x2c (fa=329 cca=359)` after clamping to `[0x2c,0x3e]`);
- a second, independent read path — `GetRxEnergy`'s `igi` field — agreed with
  `GetRxGainState` at every point;
- reading before bring-up returns `valid = false` rather than a number. That
  one was a real defect found in testing: jaguar3 returned `0x6a` from an
  unpowered baseband, which is outside DIG's window entirely and would have
  read as a measurement.

Still to do upstream: a selftest per family asserting caps are self-consistent (`index_min <=
index_max`, `settable` implies `supported`, a non-empty `automatic_input`
whenever `automatic`), plus a round trip on any backend reporting `settable`:
clamp, read back, restore, read back. On hardware, the sweep above with an
independent witness — delivery against index, which either shows the knee or
shows the hypothesis was wrong.

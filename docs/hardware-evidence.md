# Hardware evidence

What the local bench hardware has actually demonstrated, at the verification
levels defined in `CLAUDE.md`. Anything not listed is `UNAVAILABLE` — no
hardware to test with — which is an absence of evidence, not a failure.

Compilation appears here only as the `BUILDS` rung. Every backend in the
vendored source compiles; that is the least interesting thing we know.

## Status

| Adapter | USB id | Backend | State | Evidence |
|---|---|---|---|---|
| Realtek RTL8812AU | `0bda:8812` | jaguar1 (chip-id 0x04) | `TX_VERIFIED` | RX: 8997 frames / 10 s. TX: witnessed by two independent MT7612U receivers |
| MediaTek MT7612U ×2 | `0e8d:7612` | mt7612u | `TX_VERIFIED` | RX: 5905–20890 frames / 10 s. TX: 99–100% delivery witnessed by the RTL8812AU |
| Everything else devourer implements | — | — | `UNAVAILABLE` | no hardware present |

TX_VERIFIED here means what the ladder says: an *independent* adapter received
tagged frames off the air. No radio was allowed to witness itself.

## The bench

Ambient traffic, 4 s per channel, measured on all three adapters:

| Adapter | ch1 | ch6 | ch11 |
|---|---|---|---|
| RTL8812A | 5617 | 4 | 10616 |
| MT7612U (bus5) | 6042 | 20 | 2778 |
| MT7612U (bus2) | 7834 | 1 | 10239 |

**Channel 6 is the quietest here**, which makes it the right channel for
injection measurements and a trap for anything that assumes silence means a
broken receiver.

It is not as empty as it was. Re-measured 2026-09-12, 5 s dwell per channel on
an MT7612U: ch1 183 frames (36.8/s), **ch6 88 (17.8/s)**, ch11 190 (38.1/s) —
mostly beacons. The "~1 frame per 4 s" figure above was true when it was taken
and is not true now. Re-measure occupancy before drawing anything from it;
that is what `q1-occupancy` does at the top of every run below.

## Carrier sense was hiding a 90% transmit loss

The most useful thing this instrument has found so far.

The RTL8812AU accepted 100 frames, reported `submitted=100, failed=0`, and
**aired 4–13 of them** on a channel carrying almost no traffic. Two MT7612U
receivers witnessing *simultaneously* agreed exactly (3/3, 7/7, 13/13 probe
frames), which is what ruled out the receiver and pointed at the transmitter.

Disabling the MAC's carrier-sense gate (`IRadio::SetCcaMode`) on the same link:

| TX mode | carrier sense ON | carrier sense OFF |
|---|---|---|
| 6M | 4% | 94–96% |
| 24M | 7% | 88–100% |
| MCS0/20 | 13% | 92–100% |

Devourer logs the threshold at bring-up: `Jaguar1: EDCCA thresholds L2H/H2L =
5/-2 (igi=0x1c)`. On this bench that deferred nearly every transmission on an
idle channel while every host-side counter reported success.

**Why this matters beyond one adapter.** `tx_stats` showing everything
submitted and nothing failed is *consistent with the radio transmitting
nothing*. That is the concrete case behind the rule that `tx_send` can never
grant TX_VERIFIED on its own.

The MT7612U does not show this: it delivered 99–100% with carrier sense on, at
every interval from 500 µs to 20 ms.

Carrier-sense control now requires `safety_level="experimental"` at the call
site, is recorded in every experiment result (`carrier_sense_enabled`), and is
restored automatically when a run ends — including when it throws.

An earlier version of this page claimed it was "gated as experimental" when
nothing gated it at all: `characterize_run` disabled carrier sense
*automatically* whenever first-pass delivery fell below 50%, with no argument a
caller could set to decline. The retry is now opt-in by name and needs the
level. The claim and the code agree as of the remediation pass.

## Carrier sense: the gain is at the bottom of its adaptive range

The obvious explanation for the 90% transmit loss above is that the MAC is
deferring to traffic. Three channels, two MT7612U witnesses hearing every
burst simultaneously, 200 broadcast frames at 6M per point — and, since the
second pass, the transmitter's own frame-free energy view of each channel
(`channel_energy`, `IRtlRadio::GetRxEnergy`).

| Channel | frames/3 s (RTL) | cca_ofdm | cca_cck | fa_ofdm | igi | delivered | burst took |
|---|---|---|---|---|---|---|---|
| ch1 | 170 | 35 | 43 | 14 | 28 | **0.0%** | 398 ms |
| ch6 | 0 | 62 | 0 | 61 | 28 | **6.0%** | 398 ms |
| ch11 | 120 | 51 | 343 | 15 | 28 | **45.0%** | 10 294 ms |
| ch6, carrier sense OFF | — | — | — | — | 28 | **90.5%** | 398 ms |

`tx_stats` reported `submitted=200, failed=0` on every one of those lines.

**Nothing measurable orders with delivery.** Not the decodable frame count —
the busiest channel delivered best and the one in between delivered nothing.
Not the channel-busy counters — ch11 had the *fewest* OFDM CCA events during
its own sweep point and delivered the most. Not the false-alarm rate. And not
the gain index, which brings us to the answer.

**IGI is pinned at 0x1C on every channel, because that is DIG's floor.**
Devourer logs both halves at bring-up:

```
PhydmWatchdog::DigInit cur_ig=0x1c bounds=[0x1c,0x2a]
Jaguar1: EDCCA thresholds L2H/H2L = 5/-2 (igi=0x1c)
PhydmWatchdog: EDCCA L2H/H2L re-tracked to 5/-2 (igi=0x1c)
```

The adaptive loop is not broken and it is not asleep. It is working and it is
parked at the bottom of its range: DIG raises the initial-gain index when the
false-alarm rate climbs, the false-alarm rate here is tens of events per
second, so the gain stays at maximum — and the EDCCA threshold, which the
vendor re-derives from IGI every watchdog tick, therefore sits at the most
trigger-happy value the algorithm can produce. The NHM power histogram agrees:
every sample lands in its top bucket on all three channels, which is what a
receiver running at maximum gain sees.

So the deferral is not a mis-adaptation to a busy channel. **It is what the
bottom of this part's adaptive range does on this bench**, and no amount of
channel choice moves it, because the loop is already as sensitive as it goes.

Running the watchdog explicitly (`adaptive_gain=true` at open) changes
nothing measurable: ch1 0%, ch6 6%, ch11 38.5% against 45%, all within
run-to-run spread. Devourer warns that the watchdog's periodic BB traffic
shares libusb's transfer queue with the TX bulk path — it measured
4500 → 1000 TX submits in 10 s under sustained TX — so leave it off outside
this kind of investigation; our 200-frame bursts are too short to show it.

### What it would take to drive the gain

Not a missing capability in devourer — a missing way in. Everything needed is
already implemented:

| Piece | Where |
|---|---|
| the IGI write | `PhydmWatchdog::DigWriteIgi` — `phy_set_bb_reg(0xc50/0xe50, 0xff, igi)` |
| the floor write at bring-up | `HalModule::phydm_SetIgiFloor_Jaguar()`, hard-coded `0x1c` |
| DIG's clamps | `PhydmWatchdog::_rx_gain_range_min/_max` |
| a documented config field for exactly this | `DeviceConfig.rx.igi` ("fixed initial-gain index override") |

**`rx.igi` has exactly one consumer in the whole tree: `HalJaguar2.cpp:2597`.**
Jaguar1 never reads it. So the override is documented, plumbed as far as the
config struct, and ignored by the family that needs it here.

**It is not reachable from this side.** `RtlJaguarDevice` publishes
`ReadBBReg(addr, mask)` and no write; every BB write goes through its private
`_device`. We can read the gain — `channel_energy` already reports it — and we
cannot set it without changing devourer.

The smallest change that fixes it is two lines: make
`phydm_SetIgiFloor_Jaguar()` write `_cfg.rx.igi.value_or(0x1c)` instead of a
literal `0x1c`. Existing field, unchanged default, and `DEVOURER_IGI` then
means the same thing on Jaguar1 as on Jaguar2. With that, this bridge sets it
at open the way it already sets `noise_floor` and `adaptive_gain`, and IGI
becomes a sweepable axis from 0x1C to 0x2A against delivery — which is the
measurement that would turn the gain hypothesis into a result.

Worth knowing why the floor is written at all. The comment at the call site is
explicit: without phydm's watchdog "devourer's IGI never moves from the 0x20
BB-table seed and runs ~4 dB less sensitive than the kernel driver. Match
kernel by writing the floor once here." It is a deliberate choice to maximise
sensitivity, which is right for a distant link and wrong at 30 cm.

### The MT7612U is pinned too — just in a better place

The obvious reading of the role swap is that MediaTek's 1 Hz gain tracker
adapts and Realtek's does not. That is not what the port does.
`mt7612u_phy_tick` runs `phy_update_channel_gain` every second, but its input
is hard-coded:

```c
const int avg = -75;   /* mt76's monitor-mode substitute */
```

A monitor consumer has no associated-station table, so mt76 substitutes -75
and this port pins it there. With thresholds of -68/-82 at 20 MHz that fixes
`low_gain` at 1 — the MIDDLE of three gain classes — and the port notes the
`low_gain == 2` arms are unreachable today.

So both radios run at a fixed gain. The Realtek is pinned at **maximum**
(IGI 0x1C, DIG's floor); the MT7612U is pinned at the **middle class**. That
difference, not adaptivity, is the most likely reason one transmits at bench
distance and the other does not.

MediaTek's structure is still the better one for this problem, for a reason
worth separating from the measurement: it keys gain off received signal
strength, which is the variable that matters at 30 cm, while phydm's DIG keys
off the false-alarm rate and therefore concludes "clean, use maximum
sensitivity" exactly when a strong near neighbour makes that wrong. The port
says the RSSI arms "become live the moment a real per-peer RSSI source
exists".

### It is not deafness

Worth being precise, because "over-sensitive at bench distance" suggests a
receiver too saturated to decode, and that is not what was measured. The
RTL8812AU took in 192, 200 and 198 of 200 frames from an adapter inches away,
and 3194 of 3200 in the pacing sweep. It hears perfectly well.

What saturates is the energy detector feeding CCA: the NHM histogram put every
sample in its top bucket on all three channels. The symptom is not a radio
that cannot hear — it is a "channel busy" verdict stuck at yes.

**Two absences worth recording rather than re-deriving.** The absolute
frame-free noise floor is unreachable on this part through this bridge:
devourer measures it inside `IRadio::Init` and the bridge brings radios up
with `InitWrite` + `StartRxLoop`. And devourer's fixed-IGI override
(`DEVOURER_IGI`) is Jaguar2 only, so there is no supported knob for raising
this adapter's gain above DIG's floor — moving the EDCCA threshold would mean
writing BB 0x8a4 directly, which is below the boundary this project keeps.

It also moves between runs. An identical sweep twelve minutes earlier gave
ch1 0%, ch6 2.5% and **no measurement at all** for ch11 — the transmit call
did not return inside its 12.1 s per-point deadline, and the run recorded that
point as absent rather than as zero. The 10-second ch11 burst is the
reproducible part: three runs, all ~10 s for a burst asked to take 400 ms, and
it is the channel that delivers.

### Transmit, not receive — the roles swapped

The cleanest arm of the whole investigation, and the one that removes every
remaining doubt about which end is at fault. Each channel is run twice, minutes
apart at most: once with the Realtek transmitting, once with an MT7612U
transmitting, and the same three adapters filling the other roles.

| Channel | Transmitter | Delivered | witness A | witness B | Burst took |
|---|---|---|---|---|---|
| ch1 | RTL8812AU | **0.0%** | 0 | 0 | 398 ms |
| ch1 | MT7612U | **96.0%** | 192 | 192 | 398 ms |
| ch6 | RTL8812AU | **0.0%** | 0 | 0 | 398 ms |
| ch6 | MT7612U | **100.0%** | 200 | 200 | 406 ms |
| ch11 | RTL8812AU | **0.0%** | 0 | 0 | 11 227 ms |
| ch11 | MT7612U | **97.5%** | 195 | 198 | 398 ms |

In the MT7612U rounds the **Realtek is witness B**, and it received 192, 200
and 198 of 200 — 98.3% across the three channels it had just failed to
transmit on. Together with the pacing sweep, where the same adapter took in
3194 of 3200 frames as a third witness (99.8%), that is a receiver in good
health.

So on each channel, at the same minute: the air carries a burst (the MT7612U
gets 96-100% through it), the witnesses hear it, and the Realtek hears it.
Only the Realtek's own transmission is missing. **The fault is transmit-side
and it is this adapter's**, and disabling its carrier sense recovers it to
89-90.5%.

Worth keeping in mind when reading the gain result above: EDCCA is a RECEIVE
measurement made inside the TRANSMITTER, which is what makes "the gain is at
its floor" a statement about the transmitting radio's own listening circuit
rather than about any receiver in the experiment.

**And the two failure shapes are real.** On ch1 and ch6 the transmit loop
finishes exactly on schedule with the frames consumed and not aired; on ch11
the same loop blocks for ten seconds and 43-45% get out. A stalled queue and a
silent discard report identically to the host.

## The MT7612U's pacing floor is airtime plus ~112 us a frame

Sixteen points, MT7612U to MT7612U on ch6 at MCS5/20, with an RTL8812AU as a
third witness. Delivery was 99.5-100% at every point — the air was never the
constraint. What changes with frame size is the shortest spacing the
transmitter can actually hold:

| Frame | shortest achieved spacing | max frames/s | payload rate |
|---|---|---|---|
| 64 B | <100 us (floor not found) | >10 000 | >5.1 Mbit/s |
| 300 B | 160 us | 6 250 | 15.0 Mbit/s |
| 800 B | 250 us | 4 000 | 25.6 Mbit/s |
| 1500 B | 354 us | 2 825 | 33.9 Mbit/s |

Those three measured floors are linear in frame size:
**`per frame ~= 112 us + 0.159 us x bytes`**. The slope is 50.3 Mbit/s against
MCS5/20's 52.0 — the size-dependent part is airtime, near enough exactly. The
~112 us that is left is per-frame overhead: preamble, IFS, and getting the
frame across USB.

Asking for less does not fail loudly. At 1500 B and 100 us requested the burst
simply takes 3.5x as long, and the cadence quietly becomes something else.

**Do not use `tx_late_frames`.** Across two runs of the identical sweep the
same point reported 166 late frames and then 5, while `tx_elapsed_ms` for that
point was 398 ms both times. The late counter measures scheduler jitter around
a burst that finished on schedule; elapsed time reproduced to within 0.2%.

## Backend differences confirmed by measurement

| | RTL8812AU | MT7612U |
|---|---|---|
| `fcs_present` | true | **false** — the MAC strips it; the trailing 4 bytes are an FCE trailer |
| `tsfl` (chip RX timestamp) | populated | **0**, and `hw_rx_timestamp` is absent from its features |
| per-stream SNR | often empty | populated |
| `GetPermanentMacAddress` | `20:0d:b0:c4:a7:6a` | not implemented — absent, not a zero MAC |
| `GetActiveRxPaths` | implemented | not implemented — reported unsupported, never "0 chains active" |
| TX deferral under default CCA | ~90% of frames deferred | none observed |
| `send_packet` cost (idle queue) | 6.6 µs | 136 µs |

## Two bugs this found in our own code

**Chain miscount.** On the 2T2R RTL8812A, `snr[2]`/`snr[3]` are not path C/D
SNR — devourer fills them from `csi_current`, which carries stream CSI on 8812
and is path C/D SNR only on an 8814AU. Those bytes are routinely non-zero, and
the first summary reported them as "chainC SNR 19.6, chainD SNR 6.0" on a
two-chain radio. Fixed by stamping the real `rx_chains` into every frame record
and bounding every chain view by it.

**A frame collector that could not be cancelled.** Joining a cancelled frame
collector hung an experiment for ten minutes with the radio still claimed. A
thread blocked in `SocketChannel.read` is not interruptible by coroutine
cancellation, and `callbackFlow`'s `awaitClose` — the only place teardown can
be registered — sat after a `while(true)` loop that never reached it. On a
channel with no traffic the read never returns, so nothing was ever
registered and nothing could stop it. The read loop is now a child coroutine,
so the block reaches `awaitClose` immediately and cancellation closes the
socket. Found by watching the dashboard, not by a test.

**An MT7612U wedged by our own backpressure.** The frame writer copied the whole
pending buffer while holding the mutex the RX callback needs, so a slow consumer
blocked `on_packet` for a multi-megabyte memcpy. Devourer states that an
undrained MT7612U receiver wedges below the USB level, and it did:
`rx.pool_exhaust`, MCU command timeouts, dead adapter. Both units recovered
through the open-path USB reset (the soft wedge; the hard one needs a replug).
Fixed with an O(1) buffer swap. Removing that contention also removed a 13.8 s
burst time that had nothing to do with the radio.

## What happens when the reader stops reading

The failure mode this whole architecture is arranged around, finally provoked
on purpose rather than by accident. `tools/stall-test.py` attaches a frame
sink that never reads, shrinks the bridge's buffer to its 1MiB minimum, and
drives a bounded broadcast burst from the peer adapter so the buffer actually
fills — ambient traffic here is nowhere near enough, and a test whose buffer
never fills proves nothing about what happens when it does.

| | RTL8812AU | MT7612U bus5 | MT7612U bus2 |
|---|---|---|---|
| records dropped, sink stalled | 38 554 | 35 695 | 38 496 |
| worst control call during it | 0 ms | 0 ms | 0 ms |
| `monitor.stop`, sink still stalled | 1 ms | 52 ms | 51 ms |
| `radio.close` | 4 ms | 49 ms | 26 ms |
| sink reset → buffer | 1 MiB → 0 | 1 MiB → 0 | 1 MiB → 0 |
| receiving afterwards | 5 987 frames | 5 368 | 6 000 |

Both MT7612U units came through it. That matters specifically: Devourer warns
that an undrained MT7612U receiver wedges below the USB level, and this
project wedged both of them once already by holding a lock across a copy on
the RX path. A stalled consumer now costs dropped records and nothing else.

The reset case is the one that was quietly wrong before: the bridge counted a
write error and kept the dead fd. It now closes it and clears the buffer, and
the test asserts both (`write_errors` 0→1, buffer 1 MiB→0, `sink_attached`
false).

## Sustained capture rate: ~6 100 frames/s

`tools/backpressure-test.py` points one MT7612U at another transmitting flat
out on ch6 and runs a real MCP capture with a deliberately small 5 000-frame
ring:

```
admitted=6081  stored=5000  evicted=1081   bridge_dropped=0  running=True
admitted=12134 stored=5000  evicted=7134   bridge_dropped=0  running=True
...
111 468 frames admitted over 12s, ring held 5 000, bridge dropped 0
```

Two facts in that. The rate is roughly double the 1 500–3 300 frames/s
recorded earlier, and the bridge dropped *nothing*, so the JVM reader kept up
with a saturating transmitter. Both came from replacing `trySend(...)
.getOrThrow()` with a suspending `send`: the old code ended the whole capture
the moment its ~64-slot channel filled, which at these rates is about 20 ms of
slack.

The test's real assertion is not the rate. It is that the admitted count keeps
*rising* for the whole burst — a flow that died would freeze within the first
second and never move again, while the radio kept receiving.

## Antennas are not chains

Both MT7612U units report 2T2R and two active chains, though one board carries
four antenna connectors and the other two. Expected: the MT7612U is 2T2R
silicon, and a four-connector board switches antennas into two chains rather
than adding chains. **No static report distinguishes the two boards** — same USB
id, same chip, same capability output.

They do differ consistently in received level from the same transmitter, measured
simultaneously in the two-witness runs above: bus5 ≈ 76, bus2 ≈ 55. That is a
real ~20 dB difference, but position was not controlled, so it does not yet
attribute to the antenna configuration. Settling it needs a fixed transmitter and
both receivers swapped between positions.

## Reproducing

```sh
tools/host/bridge-ctl.sh start
./gradlew :mcp:installDist
tools/smoke-test.py          # RX path, all adapters
tools/stall-test.py          # a sink that stops reading, all adapters
tools/backpressure-test.py   # sustained overload through a real capture
tools/host/devourer-mcp      # MCP on stdio; dashboard on 127.0.0.1:8910
```

The transmitting tests default to channel 6 because this bench measures it as
empty. They are bounded bursts of broadcast frames from our own adapters; none
of them disables carrier sense.

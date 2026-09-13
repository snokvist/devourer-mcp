# Roadmap

Where this project is, and what is left. Written to be picked up cold — by a
person or by a model — without re-deriving what was already established.

Status words mean what `CLAUDE.md` says they mean. `BUILDS` is never evidence
about hardware.

---

## Where it is

The architecture is proven end to end on real hardware:

```
LLM ──MCP(stdio)──▶ Kotlin runtime ──UDS──▶ devourer-bridge ──libusb──▶ adapter
```

31 MCP tools across DISCOVER / OBSERVE / INSPECT / TRANSMIT / EXPERIMENT /
CHARACTERIZE / BUILD TOOL. 188 offline tests plus 63 vendored Devourer
selftests, none of which need hardware. Three hardware tests that refuse to
pass vacuously: the end-to-end smoke test, a stalled-sink test, and a
sustained-overload test.

A persistent dashboard runs on `127.0.0.1:8910` for as long as the server
does. It shows the open radios, live capture counters, experiment progress
with a stop button, running scratchpads, and every MCP tool call as it
happens. It reads in-process state only — it never touches the bridge, so
watching the instrument cannot slow it and a stalled bridge does not take the
page down with it.

| Subsystem | State | Notes |
|---|---|---|
| Vendored Devourer | done | pinned at `45f4022`, one local RX-gain patch |
| `devourer-bridge` | done | separate process, protocol v1.6, session ownership |
| Radio discovery + capabilities | done | derived from source, never a hand-kept table |
| Monitor capture | done | ~1500–3300 frames/s, zero drops |
| Capture store, query, PCAP | done | radiotap synthesized; raw bytes always reachable |
| TX (structured + raw) | done | paced against absolute deadlines |
| Experiment engine | one experiment, four sweep axes | `link_probe`, multi-witness, cancellable; see below |
| Characterization DB | done | per-adapter JSON, accumulating runs |
| Scratchpad runtime | done | declarative, capability-gated, live UI |
| Dynamic UI | done | loopback HTTP, charts/stats/tables/log |
| Dashboard | done | fixed loopback port, live state + tool-call feed, read-only bar one stop button |

### Hardware proven

| Adapter | State |
|---|---|
| RTL8812CU (jaguar3) | `TX_VERIFIED` — 100% at 6M–MCS7 on ch6, witnessed by both MT7612U simultaneously |
| MT7612U ×2 | `TX_VERIFIED` — 99–100% delivery witnessed by the Realtek |
| Everything else | `UNAVAILABLE` — no hardware, which is not a failure |

The RTL8812AU (jaguar1) that produced the earlier TX evidence left the bench on
2026-09-13; its results stay in `hardware-evidence.md` as history.

Full evidence, including the findings below, is in
[`hardware-evidence.md`](hardware-evidence.md).

---

## The big one: `IRadio` coverage

**The bridge calls 22 of `IRadio`'s 55 virtual methods.** That single number is
the most useful measure of what is left, and it is why this does not yet fully
replace Devourer's own `rxdemo`/`txdemo` as research instruments. Those two are
thin loops over the same API: 76 bring-up knobs in `DeviceConfig` (77 `env:`
tags), the runtime `IRadio` setters below, and each demo's own telemetry and
pacing code. There is no separate capability hiding in them.

Most of the gap is volume rather than difficulty — a bridge op, a Kotlin method,
an MCP tool, each following the pattern `radio.tx_stats` and `radio.cca` already
set. Roughly in value order:

| Gap | Effort | Why it matters |
|---|---|---|
| TX power: offset / flat index / reapply / state | done | Exposed as `radio_tx_power`. Note `step_measured=false` on most families — the slope is uncalibrated, and results must say so. |
| TX power: per-rate `SetTxPowerRateDiffs` | done | `radio_tx_power` takes a structured `{cck, legacy, mcs[8]}` table, or `clear_rate_diffs`. Jaguar1/2/3 (both dies) and Kestrel honour it; MT7612U/RTL8733B refuse it. |
| TX power: a `link_probe` power axis | done | `sweep_power_qdb` produces a point per offset in one experiment, records requested vs applied qdB, and restores the pre-run offset. Delivery-vs-power with an independent witness is now first-class. |
| `GetRxQuality` / `LinkHealth` | done | Exposed as `radio_rx_quality`. Subsumes `GetRxEnergy`; do not poll both on one cadence (shared counters). |
| `GetThermalStatus` | done | Exposed as `radio_thermal`. Telemetry only, not a degradation predictor. |
| TX receipts: per-frame `tx.report` | medium | The TX-side sensor (hardware retry count, final rate, queue time, per-frame correlation). The library emits it as a JSONL *event* through a shared `FILE*` sink, so the bridge needs an event-capture pipe + parser plus a `cfg.tx.report` opt-in that sets SPE_RPT in every TX descriptor. `GetRxQuality` (the RX-side half) is done; this half is its own slice. |
| `FastRetune` + channel sweep | medium | Scanning and survey. `FastRetune` is the lean path Devourer added for dwell loops; a naive `SetMonitorChannel` per dwell costs ~130 ms. |
| `SetAckResponder` | medium | Required for any bidirectional or associated-link work. |
| `SetAmpduMode` | medium | Aggregation is observable on RX today but not controllable on TX. |
| Frequency hopping / FHSS | large | Substantial in both demos, with adaptive policy. Real algorithms, not register access. |
| Spectrum sensing: sweep `channel_energy` into a survey | medium | The single-channel read exists (`IRtlRadio::GetRxEnergy` — FA/CCA, IGI, NHM histogram). What is missing is the sweep: dwell per channel, build a coarse energy picture, and say which channel is actually clear rather than which one a receiver decodes least on. Realtek only; nothing equivalent exists on MediaTek. |
| Absolute noise floor | medium | Blocked on bring-up, not on the API. Devourer measures it inside `IRadio::Init` and this bridge uses `InitWrite` + `StartRxLoop`; reaching it means moving bring-up onto `Init`, which is the one path currently sustaining 6000 frames/s. |
| Beamforming (`StartSounding`, `RegisterBeamformee`) | large | 8814/Kestrel territory; no hardware here to verify against. |
| HE trigger / TWT / UL-OFDMA | large | Kestrel only — `UNAVAILABLE` until an 11ax adapter exists on this bench. |
| CSI / LA capture | large | Devourer has both; nothing here surfaces them. |
| PCIe transport (`CreateRadioPcie`) | medium | Compiled OFF. Needs an RTL8821CE and vfio binding. |

---

## Acceptance gate: replacing `rxdemo`/`txdemo`

The instrument exists to make Devourer's two demo binaries unnecessary, so the
gate is parity plus what a demo cannot do. The staged plan, the capability
matrix and the per-milestone acceptance tests are in
[`rxdemo-txdemo-parity.md`](rxdemo-txdemo-parity.md); the coverage table above
is its effort estimate.

The core RX/TX loop meets the gate today on jaguar3 and mt7612u (see
`hardware-evidence.md`). Beyond the demos, the same MCP surface already carries
the verification ladder, multi-witness counting, persistent capture/query/PCAP,
capability gating and the experiment engine.

This gate does **not** promise parity with everything under `examples/`. The
adaptive hopset, channel migration, TDMA scheduling and fused FEC are
algorithms, not knob sets; they belong in the experiment engine or in
scratchpad programs promoted to saved tools, not in a wall of MCP arguments.

---

## Experiment engine

`link_probe` is the primitive the rest build on. It now sweeps five axes — TX
mode, channel, frame size and frame spacing — expanded as a bounded cartesian
product with the channel outermost, because retuning costs ~130ms on a Realtek
and a sweep that interleaved channels would pay it on every point.

Roles are a `Map<RadioRole, Int>`, so one burst can be heard by up to three
independent receivers simultaneously. That is a qualitatively different
measurement rather than a repeat: two witnesses agreeing frame-for-frame means
the missing frames were never aired, which localises the loss to the
transmitter. It is how the carrier-sense finding became conclusive, and it is
what an answer to the open antenna question needs.

Each point has a hard deadline and the whole run is registered, so it can be
stopped from the dashboard while it runs. Cancellation still restores carrier
sense and stops the monitors — that cleanup runs under `NonCancellable`, which
is the difference between a stopped run and a radio left transmitting deaf.

What it still does not do:

- **`experiment.compare`.** Two results, one diff, with the caveats that make
  them comparable or not. Nothing compares runs today.
- **Persisted results.** Experiments are returned and retained in memory for
  the session, not stored. They should land next to characterizations so a
  sweep can be re-read later.
- **A power axis, but not a per-rate power *measurement*.** `sweep_power_qdb`
  sweeps the offset and records requested vs applied qdB; what is still missing
  is the reverse direction — asking whether the ratio between two rates' RSSI
  matches the table, i.e. using rate diffs as a sweep axis rather than a manual
  set-then-measure.
- **Anything but delivery.** Every axis is still swept against the same
  measurement; the axes multiply but the metric does not change.

---

## Known open questions on this bench

**Which MT7612U board has four antennas.** Still unsettled, deliberately. The
two units differ consistently in received level from the same transmitter
(≈76 vs ≈55, measured simultaneously), but position was never controlled, so
that difference does not attribute to the antenna configuration. Settling it
needs a fixed transmitter and the two receivers swapped between positions — a
multi-witness experiment, which is the feature above.

**Whether the RTL8812AU's EDCCA threshold can be raised rather than bypassed.**
Answered. EDCCA is the gate that blocks injection on Jaguar1 (94% recovered by
turning it off alone, with primary CCA left on and still deferring properly to
a real flooder), devourer enables it where the vendor driver ships it off, and
the thresholds it hard-codes are vendor module parameters. See
[`proposals/cca-gates-and-adaptivity.md`](proposals/cca-gates-and-adaptivity.md).
The receive-gain work below was the right gate and the wrong lever.

Previously recorded as answered halfway: The deferral is now understood: the gain index sits at
0x1C, which is the bottom of DIG's range, and the vendor re-derives the EDCCA
threshold from it — so the threshold is the most sensitive value the adaptive
loop can produce, on every channel, and no channel choice moves it. See
`hardware-evidence.md`.

The lever is now implemented end to end by the remaining local vendor patch:
`IRadio::SetRxGainRange`, with state/capability reporting on Jaguar1 and
Jaguar3; the bridge ops `radio.rx_gain` and `radio.cca_gates`; and the MCP
tools `radio_rx_gain` and `radio_cca_gates`. `radio.cca` stays as the portable
all-or-nothing carrier-sense control. The full design and remaining backend
gaps are in
[`proposals/rx-gain-range.md`](proposals/rx-gain-range.md). Every family has a
receive-gain index and on five of six nothing moves it — jaguar2 is the only
one whose gain genuinely adapts. The MT7612U is not winning because its 1 Hz
loop adapts either; its input is hard-coded (`const int avg = -75`), pinning
it at the middle gain class rather than, like the Realtek, at maximum. So the
vendor-neutral knob is the *bounds*, not the value: one `SetRxGainRange`
steers a loop where one exists, sets the gain where none does, and needs no
separate pin operation.

---

## Smaller gaps

- **Replay.** `CLAUDE.md` promises captured-data replay for CI. PCAP export
  exists; nothing reads one back. This is the cheapest way to grow offline test
  coverage of the analysis layer.
- **Hardware-tagged tests.** The Gradle build excludes JUnit tag `hardware`
  unless `-PwithHardware`, but no test carries the tag yet — hardware testing is
  the Python smoke test. Worth converting.
- **A second experiment.** The engine's seam is a function, deliberately, and
  there is exactly one experiment through it. A spectrum dwell or a retune
  timing measurement would be the first test of whether that seam is the right
  shape.
- **Android.** The architecture does NOT currently hold, contrary to an earlier
  claim here. Six concrete blockers: `UnixDomainSocketAddress` — the entire
  Kotlin↔native transport — is Android **API 34**, not 28;
  `com.sun.net.httpserver` (the scratchpad UI) does not exist on Android;
  `/tmp` and `XDG_RUNTIME_DIR` are assumed for the socket and the USB lock;
  device enumeration reads `/sys/bus/usb/devices` and `/proc/<pid>/comm`, which
  an unrooted app cannot; the bridge is launched by a bash script using
  `setsid` and a PID file, and there is no `ProcessBuilder` anywhere in Kotlin;
  and `native/CMakeLists.txt` has no Android toolchain support. Vendored
  Devourer *does* carry an Android `UsbDeviceConnection` fd-import path
  (issue #330) that our bridge ignores by calling `libusb_open()` directly.
  Abstracting the transport behind an interface with a loopback-TCP
  implementation would remove both the API-34 and the `sun_path`-length
  problems cheaply.
- **`radio_list` before open.** Realtek 11ac parts report `probe_required` and
  cannot be identified without opening them. Correct and honest, but a caller
  wanting an inventory must open every candidate.
- **Bridge protocol versioning.** v1.6 with a major-version gate. No
  negotiation, no capability discovery beyond `hello`.

---

## What not to do

Recorded because each was considered and rejected for a reason that has not
changed.

- **Do not put a scripting engine in the scratchpad.** The JVM SecurityManager
  was removed in 17 and deleted in 21; there is no supported way to contain
  in-process model-authored code. The declarative model is the deliberate trade.
- **Do not make MCP carry frames.** It is the control and reasoning plane.
  References, queries, summaries and exports — never the packet stream.
- **Do not count non-zero array slots as chains.** On a 2T2R RTL8812A the unused
  SNR slots carry stream CSI and are routinely non-zero. Use `rx_chains`.
- **Do not hold a lock across a copy on the RX path.** This wedged an MT7612U
  below the USB level. Double-buffer and swap.
- **Do not let `tx_send` success imply transmission.** The Realtek reported
  `submitted=100, failed=0` while airing four frames.

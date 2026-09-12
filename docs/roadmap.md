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

26 MCP tools across DISCOVER / OBSERVE / INSPECT / TRANSMIT / EXPERIMENT /
CHARACTERIZE / BUILD TOOL. 150 offline tests plus 63 vendored Devourer
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
| Vendored Devourer | done | pinned at `30d248e`, sync script, patch dir (empty) |
| `devourer-bridge` | done | separate process, protocol v1.1, session ownership |
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
| RTL8812AU (jaguar1) | `TX_VERIFIED` — witnessed by two independent receivers |
| MT7612U ×2 | `TX_VERIFIED` — 99–100% delivery witnessed by the Realtek |
| Everything else | `UNAVAILABLE` — no hardware, which is not a failure |

Full evidence, including the findings below, is in
[`hardware-evidence.md`](hardware-evidence.md).

---

## The big one: `IRadio` coverage

**The bridge calls 11 of `IRadio`'s 52 virtual methods.** That single number is
the most useful measure of what is left, and it is why this does not yet replace
Devourer's own `rxdemo`/`txdemo` as research instruments (those expose ~60 and
~85 environment knobs respectively).

Most of the gap is volume rather than difficulty — a bridge op, a Kotlin method,
an MCP tool, each following the pattern `radio.tx_stats` and `radio.cca` already
set. Roughly in value order:

| Gap | Effort | Why it matters |
|---|---|---|
| TX power: `SetTxPower`, `SetTxPowerOffsetQdb`, `GetTxPowerState` | small | Turns link probes into power sweeps. Note `step_measured=false` on most families — the slope is uncalibrated, and results must say so. |
| `GetRxQuality` / `LinkHealth` | small | Windowed link aggregates Devourer already computes, plus its fused verdict; today we recompute a weaker version from frames. Subsumes `GetRxEnergy`, which `channel_energy` already exposes. |
| `GetThermalStatus` | small | Long experiments drift thermally and nothing currently notices. |
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

## Experiment engine

`link_probe` is the primitive the rest build on. It now sweeps four axes — TX
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
- **Anything but delivery.** Every axis is swept against the same measurement.
  A power sweep needs `SetTxPower` first; see the coverage table above.

---

## Known open questions on this bench

**Which MT7612U board has four antennas.** Still unsettled, deliberately. The
two units differ consistently in received level from the same transmitter
(≈76 vs ≈55, measured simultaneously), but position was never controlled, so
that difference does not attribute to the antenna configuration. Settling it
needs a fixed transmitter and the two receivers swapped between positions — a
multi-witness experiment, which is the feature above.

**Whether the RTL8812AU's EDCCA threshold can be raised rather than bypassed.**
Answered halfway. The deferral is now understood: the gain index sits at
0x1C, which is the bottom of DIG's range, and the vendor re-derives the EDCCA
threshold from it — so the threshold is the most sensitive value the adaptive
loop can produce, on every channel, and no channel choice moves it. See
`hardware-evidence.md`.

What is left is the lever, and it is a two-line change in devourer rather than
a missing capability. `DeviceConfig.rx.igi` is documented as a fixed
initial-gain override and has exactly one consumer in the tree
(`HalJaguar2.cpp:2597`); Jaguar1 ignores it and
`HalModule::phydm_SetIgiFloor_Jaguar()` hard-writes `0x1c`. Making that
`_cfg.rx.igi.value_or(0x1c)` costs nothing by default and makes IGI a
sweepable axis here, because this bridge already passes DeviceConfig at open.

It is not reachable without that change: `RtlJaguarDevice` publishes
`ReadBBReg` and no write. This is an upstream contribution, not a local patch
— `vendor/patches/` is empty and should stay that way.

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
- **Bridge protocol versioning.** v1.1 with a major-version gate. No
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

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

39 MCP tools across DISCOVER / OBSERVE / INSPECT / TRANSMIT / EXPERIMENT /
CHARACTERIZE / BUILD TOOL. 248 Kotlin tests (one hardware-tagged, excluded
unless `-PwithHardware`) plus 64 native selftests (63
vendored Devourer selftests and the bridge's radiotap-layout test), none of
which need hardware. Three hardware tests that refuse to
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
| `devourer-bridge` | done | separate process, protocol v1.14, session ownership |
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
| RTL8812CU (jaguar3) | `TX_VERIFIED` — 100% at 6M–MCS7 on ch6; also STBC TX and narrowband TX on the post-swap bench |
| RTL8822B (jaguar2) | `TX_VERIFIED`, `RX_VERIFIED` — STBC decode monitor; narrowband 5/10 MHz peer |
| MT7612U ×1 | `TX_VERIFIED` — 99–100% delivery witnessed by the Realtek |
| Everything else | `UNAVAILABLE` — no hardware, which is not a failure |

The RTL8812AU (jaguar1) that produced the earlier TX evidence left the bench on
2026-09-13, as did the second MT7612U (swapped for the RTL8822B); their results
stay in `hardware-evidence.md` as history.

Full evidence, including the findings below, is in
[`hardware-evidence.md`](hardware-evidence.md).

---

## The big one: `IRadio` coverage

**The bridge calls 35 of `IRadio`'s 55 virtual methods.** That single number is
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
| TX receipts: per-frame `tx.report` | done | `radio_tx_receipts` plus `radio_open`'s `tx_report` divisor. Per-session `EventSink` capture (temp file, drained on read), 200/200 reports on a burst on the 8822C. The TX-side sensor `tx_stats` cannot be. |
| `FastRetune` | done | `radio_fast_retune`; 21 ms on the 8822C, full-retune fallback elsewhere. The scan/survey built on it is still open. |
| `FastSetBandwidth` | done | `radio_fast_bandwidth`; 20<->5/10 narrowband, capability-gated on the adapter's width set. |
| Channel sweep / spectrum survey | done | `spectrum_sweep` dwells channels with `FastRetune` and reads the frame-free energy per bin; Realtek only, and the quietest channel is a hint not a throughput answer. |
| Beacons / AP mode | done | Exposed as `radio_beacon` (arm/update/stop; the `IRadio::StartBeacon` family). On both the MT7612U and the RTL8822C (Jaguar3) a second MT7612U decoded the beacon, its 102.4 ms cadence and its live TX-egress TSF stamp, and the updated SSID after `update`; the host MT7922 on its stock kernel driver independently saw the beacon and its TSF. Station association is a separate feature: it needs an AP responder (probe/auth/assoc), not exposed here. |
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

### Path to the gate (next steps)

Ordered by what unblocks the most, and by what a demo cannot do — the whole
point of the comparison. The per-milestone tables in
[`rxdemo-txdemo-parity.md`](rxdemo-txdemo-parity.md) carry each row's status.

1. **M4 A-MPDU goodput — done.** `ProbeFrame` builds the QoS Data form the
   MAC needs a TID for, and `tools/ampdu-goodput-test.py` measures delivered
   payload against both A-MPDU-off controls with an independent witness:
   **+32–35% at MCS7/20** across three runs (6.54/6.47/6.67 vs 4.89/4.91/4.94 MB/s), no
   gain at MCS0/20 as expected. It is a broadcast, no-ack measurement, stated
   as such. The result also records the transmitter's `ampdu` state and labels
   a non-aggregated QoS run as single-MPDU (see `hardware-evidence.md`).
2. **M4 loose ends — done.** STBC airs and decodes on an independent monitor
   (`tools/stbc-test.py`: every decoded probe is `stbc=0` on the control and
   `stbc=1` on the `/STBC` arm — hundreds each, counts vary per run), and
   no-ack semantics are documented as the retry-limit-0 state that
   `tx_retry_limit:0` and `AmpduMode.no_ack` share. Recorded in
   `hardware-evidence.md`: a jaguar2 transmitter can stick in a TX state where
   every frame is accepted and nothing airs; a `radio_close`/`radio_open` does
   not clear it but a VBUS power cycle does (after the 2026-09-14 replug three
   sequential jaguar2 TX runs all delivered), so the multi-run tools prefer
   jaguar3.
3. **Multi-witness role in `LinkProbe` — done.** Roles were already first-class
   in the API (`rx_session` + `witness_sessions` map to `RX_PEER`/`MONITOR`),
   and `tools/multi-witness-test.py` now proves it on hardware: one RTL8822C
   transmitter, both independent receivers decode the same burst (253/300 and 300/300
   in the validating run, delivery 0.84–0.96), and the result carries the two-witness
   agreement/disagreement note that localises the loss. The open antenna
   question it was also meant to settle (`hardware-evidence.md`, "which
   MT7612U board has four antennas") is now **UNAVAILABLE**: the second MT7612U
   was swapped for the RTL8822B, and one board cannot be compared with itself.
   A demo cannot do multi-witness at all, which is the "strictly better" half
   of the gate.
4. **M3 remainders — done.** Narrowband 5/10 MHz TX+RX is verified in both
   directions at both widths on the post-swap pair (RTL8822C jaguar3 + RTL8822B
   jaguar2) by `tools/narrowband-test.py` (the reverse width is selected per
   `--jaguar2-width` run): 10 MHz 0.98 forward / 0.99 reverse,
   5 MHz 0.995 forward / 1.000 reverse, and the MT7612U is refused as a 5 MHz
   witness rather than capturing wide. J1 is gone from the bench, so the
   original "on J1/J3" wording is `UNAVAILABLE` for J1. The absolute noise
   floor stays blocked on the bring-up path (`Init` vs `InitWrite`) and is now
   recorded with the measured reason and the `igi` relative proxy
   (`hardware-evidence.md`); moving bring-up onto `Init` remains the unstarted
   fix.
5. **Run the acceptance matrix — done, gate met.** `tools/acceptance-matrix.py`
   drives `rxdemo`/`txdemo` and the MCP equivalent on the same bench and
   compares decoded counts on the *same* receiver, both arms sending the same
   200-byte QoS Data PSDU: the MCP capture heard 200/200 at 6M and MCS7/20
   while rxdemo's own count varied (100–200, never ahead); the TX plane's
   same-monitor counts were 185 vs the demo's 194 at 6M and 160 vs 183 at
   MCS7/20, with the MCP peer witness decoding the same rate (4/19), 0.98/0.995
   delivery and `TX_VERIFIED`, every capture at zero evictions/drops. A demo's
   own `submitted` count includes ~50 bring-up submissions, so the comparison
   is count-based, and the honest verdict is "not materially worse" within an
   explicit band (max 15% or 25 frames), not identical. Where the demo cannot
   measure it — independent witness/`TX_VERIFIED`, multi-witness localisation,
   persistent capture/query/PCAP, capability gating, the cancellable experiment
   engine — MCP is strictly better, and the matrix prints that list every run.
   The full table and gate statement are in `docs/rxdemo-txdemo-parity.md`; it
   stays a documented hardware run, never a unit test.
6. **Optional: hardware-tagged JUnit — seed done.** The `hardware` tag now has
   its first carrier: `kotlin/mcp/src/test/.../HardwareSmokeTest.kt` walks
   list → open → describe → monitor → close through the real bridge, excluded
   unless `-PwithHardware` and skipping (`UNAVAILABLE`, not a failure) when the
   bridge or an adapter is absent. The Python harnesses remain the full
   hardware runs; converting them wholesale onto the tag is still open.

Out of scope, decided: **AP/station association** (needs a probe/auth/assoc
responder, which the instrument does not expose — see the M6 note above) and
the **M5 hopping/sensing algorithms** (they belong in the experiment engine or
the scratchpad, not MCP arguments). **M7** (CSI/LA, beamforming, HE
trigger/TWT/UL-OFDMA, PCIe) stays `UNAVAILABLE` until hardware exists.

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
what the antenna question needed — now **UNAVAILABLE**, because the second
MT7612U was swapped for the RTL8822B and one board cannot be compared with
itself.

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

**Which MT7612U board has four antennas.** `UNAVAILABLE`, not settled. The two
units differed consistently in received level from the same transmitter (≈76 vs
≈55, measured simultaneously), but position was never controlled, so that
difference never attributed to the antenna configuration. Settling it needs a
fixed transmitter and the two receivers swapped between positions — and the
second MT7612U left the bench on 2026-09-13 (swapped for the RTL8822B), so one
board cannot be compared with itself. The two-witness feature that would have
run the experiment is done; the question is not answerable on this bench.

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
- **Bridge protocol versioning.** v1.11 with a major-version gate. No
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

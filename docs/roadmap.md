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

23 MCP tools across DISCOVER / OBSERVE / INSPECT / TRANSMIT / EXPERIMENT /
CHARACTERIZE / BUILD TOOL. 53 offline tests plus 63 vendored Devourer selftests,
none of which need hardware. A hardware smoke test that refuses to pass
vacuously.

| Subsystem | State | Notes |
|---|---|---|
| Vendored Devourer | done | pinned at `30d248e`, sync script, patch dir (empty) |
| `devourer-bridge` | done | separate process, protocol v1.1, session ownership |
| Radio discovery + capabilities | done | derived from source, never a hand-kept table |
| Monitor capture | done | ~1500–3300 frames/s, zero drops |
| Capture store, query, PCAP | done | radiotap synthesized; raw bytes always reachable |
| TX (structured + raw) | done | paced against absolute deadlines |
| Experiment engine | first experiment | `link_probe` only; see below |
| Characterization DB | done | per-adapter JSON, accumulating runs |
| Scratchpad runtime | done | declarative, capability-gated, live UI |
| Dynamic UI | done | loopback HTTP, charts/stats/tables/log |

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

**The bridge calls 9 of `IRadio`'s 52 virtual methods.** That single number is
the most useful measure of what is left, and it is why this does not yet replace
Devourer's own `rxdemo`/`txdemo` as research instruments (those expose ~60 and
~85 environment knobs respectively).

Most of the gap is volume rather than difficulty — a bridge op, a Kotlin method,
an MCP tool, each following the pattern `radio.tx_stats` and `radio.cca` already
set. Roughly in value order:

| Gap | Effort | Why it matters |
|---|---|---|
| TX power: `SetTxPower`, `SetTxPowerOffsetQdb`, `GetTxPowerState` | small | Turns link probes into power sweeps. Note `step_measured=false` on most families — the slope is uncalibrated, and results must say so. |
| `GetRxQuality` / `LinkHealth` | small | Windowed link aggregates Devourer already computes; today we recompute a weaker version from frames. |
| `GetThermalStatus` | small | Long experiments drift thermally and nothing currently notices. |
| `FastRetune` + channel sweep | medium | Scanning and survey. `FastRetune` is the lean path Devourer added for dwell loops; a naive `SetMonitorChannel` per dwell costs ~130 ms. |
| `SetAckResponder` | medium | Required for any bidirectional or associated-link work. |
| `SetAmpduMode` | medium | Aggregation is observable on RX today but not controllable on TX. |
| Frequency hopping / FHSS | large | Substantial in both demos, with adaptive policy. Real algorithms, not register access. |
| Spectrum sensing (`RxSense`, NHM, noise floor) | large | The missing half of "why is this link bad" — we can see frames but not the noise between them. |
| Beamforming (`StartSounding`, `RegisterBeamformee`) | large | 8814/Kestrel territory; no hardware here to verify against. |
| HE trigger / TWT / UL-OFDMA | large | Kestrel only — `UNAVAILABLE` until an 11ax adapter exists on this bench. |
| CSI / LA capture | large | Devourer has both; nothing here surfaces them. |
| PCIe transport (`CreateRadioPcie`) | medium | Compiled OFF. Needs an RTL8821CE and vfio binding. |

---

## Experiment engine

`link_probe` works and is the primitive the rest build on. What it does not yet
do:

- **Sweep more than TX mode.** The master model calls for channel, bandwidth,
  packet size, retries, TX power, aggregation and timing. The `PointResult`
  shape already supports arbitrary point labels; the runner sweeps one
  dimension.
- **More than two roles.** `RadioRole` defines DUT / TX_PEER / RX_PEER /
  MONITOR / MONITOR_2, and the two-witness test was run by hand against the
  bridge. Multi-witness belongs in `LinkProbe` — it is what made the
  carrier-sense finding conclusive.
- **`experiment.compare`.** Two results, one diff, with the caveats that make
  them comparable or not. Nothing compares runs today.
- **Cancellation mid-run.** Bounded by duration, but a caller cannot stop one
  early.
- **Persisted results.** Experiments are returned, not stored. They should land
  next to characterizations so a sweep can be re-read later.

---

## Known open questions on this bench

**Which MT7612U board has four antennas.** Still unsettled, deliberately. The
two units differ consistently in received level from the same transmitter
(≈76 vs ≈55, measured simultaneously), but position was never controlled, so
that difference does not attribute to the antenna configuration. Settling it
needs a fixed transmitter and the two receivers swapped between positions — a
multi-witness experiment, which is the feature above.

**Whether the RTL8812AU's EDCCA threshold is tunable.** Carrier sense was
deferring ~90% of transmissions on an idle channel. Disabling it is the current
workaround, and it is antisocial. Devourer logs the threshold
(`L2H/H2L = 5/-2`); whether it can be raised rather than bypassed is unexplored
and would be the better fix.

---

## Smaller gaps

- **Replay.** `CLAUDE.md` promises captured-data replay for CI. PCAP export
  exists; nothing reads one back. This is the cheapest way to grow offline test
  coverage of the analysis layer.
- **Hardware-tagged tests.** The Gradle build excludes JUnit tag `hardware`
  unless `-PwithHardware`, but no test carries the tag yet — hardware testing is
  the Python smoke test. Worth converting.
- **Scratchpad primitives.** `tx`, `tcp/udp` and `storage` are declared in
  `Capability` but have no source or step implementing them. Declared and
  unimplemented is a worse state than absent; either build them or drop them.
- **Android.** The architecture holds — same protocol, different launcher — but
  nothing has been attempted. Inherit `minSdk 28` / NDK r26+ from Devourer's own
  Android harness. No JDK constraint comes from Devourer; JDK 21 is our choice.
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

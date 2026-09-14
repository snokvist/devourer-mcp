# Plan: rxdemo / txdemo parity

The goal of this project is that the two canonical Devourer demo binaries are
no longer needed — that everything `rxdemo` and `txdemo` can do is reachable
through MCP, with the things a demo cannot do (independent witnesses, the
verification ladder, persistent captures, capability gating) on top.

This is the checklist for that. It is deliberately scoped to **the two
binaries**, not to everything under `vendor/devourer/examples/`. The algorithm
demos (`chanmig`, `tdma`, `timesync`, `svctx`, hopset, fused-FEC) are separate
targets and are called out where they overlap.

## What "parity" means here

`rxdemo` and `txdemo` are thin loops over the same library the bridge links:
`DeviceConfig` bring-up knobs (76 `env:` tags), the runtime `IRadio` setters,
and each demo's own telemetry/pacing code. There is no capability hiding in
them. So parity is three checks, per capability:

1. **Reachable** — the same effect can be produced through MCP.
2. **Honest** — the reply says what it does *not* prove (`step_measured`,
   `hw_readback`, "accepted is not aired", capability `supported:false`).
3. **Verified** — where it transmits, an *independent* receiver confirms it.

A capability counts as done only when all three hold on hardware. The coverage
table in `roadmap.md` is the effort estimate; this document is the order and
the acceptance test.

## Where it is now (2026-09-13)

- 39 MCP tools, 28 bridge ops, 35 of 55 `IRadio` methods called.
- **Proven end to end on the current bench**: discover/open/describe; monitor
  RX with per-frame telemetry and raw bytes; capture store/query/PCAP; frame
  inspection; TX structured and raw; split carrier-sense gates; receive-gain
  clamp; TX-power offset/index/reapply and per-packet `pkt_power_db`;
  frame-free channel energy; active RX paths; tx_stats; per-frame TX receipts;
  hardware ACK/ARQ; hardware beacon; multi-witness `experiment_link_probe`;
  characterization DB; scratchpad; dashboard.
- **TX_VERIFIED**: RTL8812CU (jaguar3) and MT7612U, witnessed independently.

## Milestones

Each milestone is a small, hardware-verifiable slice. Ordered by value, and by
what later milestones build on.

### M2 — Finish the power story (done)

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| Per-rate power diffs | `TX_RATE_DIFFS` | **Done**: structured `rate_diffs` on `radio.tx_power` | — | Clamp MCS0, watch that rate's witness RSSI move while MCS7 holds (7.8 dB drop / 0.0 dB anchor) |
| Power sweep axis | `TX_PWR_START/STEP/STOP/STEP_MS`, `TX_PWR_OFFSET_QDB` | **Done**: `sweep_power_qdb` in `experiment_link_probe` | Axes bounded by the adapter caps, checked up front; per-point requested/applied qdB recorded; pre-run offset restored | Delivery/RSSI vs power curve on the 8812CU, witnessed by an MT7612U (52.7 / 63.0 / 81.1 dBm at −64/0/+64 qdB) |
| RX link health | `RXQUALITY`, `LINKHEALTH`, `RX_ENERGY_MS` | **Done**: `radio_rx_quality` (fused verdict) | — | Verdict/cause/fix beside frame telemetry; drains, so read-dwell-read |
| TX receipts | `TX_RECEIPTS`, `TX_REPORT` | **Done**: `radio_tx_receipts` + `radio_open.tx_report` | Per-session `EventSink` capture, drained on read | 200/200 reports on a 200-frame burst; state/retries/final_rate/tag per frame |
| Thermal status | `THERMAL_POLL_MS`, `THERMAL_WARN_DELTA` | **Done**: `radio_thermal` (`GetThermalStatus`) | — | Read the meter; telemetry, not a degradation predictor |

### M3 — Retune and survey

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| `FastRetune` | `HOP_FAST` (non-FH use) | **Done**: `radio_fast_retune` | — | Same-band hop: 21 ms lean on the 8822C vs ~130 ms full retune |
| `FastSetBandwidth` | `NB_BW` | **Done**: `radio_fast_bandwidth` | Capability-gated on the adapter's widths | 20<->5/10 toggle; refused on a 20/40/80 adapter |
| Spectrum sweep | `RX_SWEEP`, `RX_SWEEP_DWELL_MS`, `RX_SWEEP_FULL` | **Done**: `spectrum_sweep` dwells channels via `FastRetune` | — | Survey ch1/6/11 on the 8822C; quietest ch11 (cca 31 vs 128/172) |
| Absolute noise floor | `RX_NOISE_FLOOR` | `noise_floor` at open; `channel_energy` reports `valid_noise_floor:false` with why | blocked on bring-up path (`Init` vs `InitWrite`) | Attempted on both Realteks: not populated (the vendor CAL never runs); `igi` is the usable relative proxy (30 jaguar3 / 52 jaguar2) |
| Narrowband | `NB_BW/ADC/DAC` | **Done**: `experiment_link_probe width_mhz` 5/10 | — | `tools/narrowband-test.py`: 10 MHz 0.92–0.99, 5 MHz 0.990 forward and 5 MHz 1.000 reverse (jaguar3 ↔ jaguar2), and a 20/40/80-only MT7612U is refused as a 5 MHz witness |
| Multi-witness link probe | — | **Done**: `experiment_link_probe` `rx_session` + `witness_sessions` map to `RX_PEER`/`MONITOR`, and the result carries the two-witness localisation note | — | `tools/multi-witness-test.py`: RTL8822C TX, RTL8822B + MT7612U both decode it (253/300 and 300/300 in the validating run), delivery 0.84–0.96, `TX_VERIFIED` |

### M4 — MAC features that change what a burst *is*

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| A-MPDU | `TX_AMPDU`, `TX_AMPDU_MODE` | **Done**: `radio_ampdu` control, QoS probe frames (`ProbeFrame` TID form), deep feeder (`radio_open usb_agg`, `experiment_link_probe batch:true`) and `goodput_bytes_per_sec`; the result records the transmitter's `ampdu` state and labels a non-aggregated QoS run single-MPDU | — | `tools/ampdu-goodput-test.py`: **+32–35%** at MCS7/20 across three runs (6.54/6.47/6.67 vs 4.89/4.91/4.94 MB/s) vs both A-MPDU-off controls on an independent MT7612U witness; no gain at MCS0/20, as expected |
| Hardware ACK / ARQ | `ACK_RESPONDER` | **Done**: `radio_ack_responder` + `radio_open` retry knobs (`tx_retry_limit`, `tx_ack_timeout_us`, `tx_retry_fallback_off`) | — | `tools/tx-retry-arq-test.py`: no responder → retries pinned at the limit, retry-drop; MT responder armed → retries 0/1, delivered |
| QoS / no-ack / STBC | `TX_QOS_*`, `TX_STBC_TOGGLE` | **Done**: QoS probe frames carry a TID (`experiment_link_probe qos_tid`, see A-MPDU); no-ack is the retry-limit-0 state (`tx_retry_limit:0` / `AmpduMode.no_ack`, semantics in `hardware-evidence.md`); STBC is in the mode grammar | — | `tools/stbc-test.py`: control `stbc=0` ×362 vs `/STBC` `stbc=1` ×357 decoded on an independent RTL8822B monitor (counts vary per run; every probe in an arm agrees); both arms TX_VERIFIED by an MT7612U peer |
| Per-packet TX power | `TX_PKT_PWR_DB/QDB`, `TX_PKT_OFSET` | **Done**: `experiment_link_probe pkt_power_db` composes the per-frame radiotap `DBM_TX_POWER` (bit 10), capability-gated on `per_packet_txpower` | — | Witness RSSI tracks the request: 0→43, −6→37, −12→33 (bank floor) on the 8812CU; structured path 0→62, −12→52 |

### M5 — Hopping and sensing (algorithms, not knobs)

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| `FastRetune` hopping | `HOP_CHANNELS/ROUNDS/DWELL_FRAMES` | Gap | experiment engine (`dwell` experiment), not an MCP argument wall | Hop rate + per-channel delivery with a witness |
| Adaptive hopset | `HOP_POLICY_*`, `HOP_SEED`, `HOP_SLOT_MS` | Gap | experiment engine + scratchpad tool | Reproduce a keyed schedule; lockstep RX |
| Sense-before-burst | `TX_SENSE_*` | Gap | experiment/scratchpad over `channel_energy` | Decide-and-inject policy with a logged reason |

### M6 — Time, beacons, AP mode

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| TSF read + adoption | (rx telemetry `tsfl`) | **Read done**, **adoption done** (`radio_tsf`, `set_tsf_us`) | Adoption verified on the 8822C; the MT7612U's `WriteTsf` override is a silent no-op (recorded, upstream fix) | Read advances at wall-clock rate; 8822C write took (+1014us readback) |
| Beacons | — | **Done**: `radio_beacon` (arm/update/stop via `StartBeacon`/`StopBeacon`/`UpdateBeaconPayload`) | `tools/beacon-test.py` | Both the MT7612U and the RTL8822C (Jaguar3) armed; an independent MT7612U decoded the beacon (102.4 ms cadence, live TX-egress TSF, updated SSID on air, quiet after stop) and the host MT7922 on its stock kernel driver independently saw the beacon and its TSF. Association needs an AP responder and is a separate feature |
| TDMA / timesync | separate binaries | Gap | experiment engine (`tdma`/`timesync` are not rxdemo/txdemo) | Out of this plan's scope; listed so it is not mistaken for done |

### M7 — Long tail (large, mostly `UNAVAILABLE` hardware)

CSI/LA capture, fused FEC / corrupt-frame salvage, beamforming
(`StartSounding`/`RegisterBeamformee`), HE trigger/TWT/UL-OFDMA, PCIe. Each
needs hardware this bench does not have to verify, so they stay
`UNAVAILABLE`/`IMPLEMENTED_IN_SOURCE` until then. **Do not advertise them
before they can be exercised** — a scratchpad written against an advertisement
is the failure mode `CLAUDE.md` names.

## Explicitly out of scope

- **Register/debug dumps** (`BB_DUMP`, `DUMP_*`, `PCTR`, `GAINTAB_DBG`, …).
  Diagnostics for driver development, not an instrument surface.
- **`streamtx` / `duplex` / `svctx`.** Separate stdin-driven data-plane demos;
  the application-FEC half lives in `tools/precoder/`.
- **Algorithm demos as binaries.** `chanmig`, `tdma`, `timesync` are their own
  projects. Their *primitives* (`FastRetune`, `ReadTsf`, beacons) are M3/M6
  because rxdemo/txdemo use them too.

## The acceptance test

The gate for declaring rxdemo/txdemo replaceable is a matrix, not a checklist
tick: for each capability, the same measurement run through the demo and
through MCP, compared. Where the demo cannot measure it (anything needing a
witness), MCP must be strictly better, and that is the point.

CI stays hardware-free: this matrix is a documented, repeatable hardware run
like `tools/smoke-test.py` and `tools/rx-gain-cca-test.py`, never a unit test
that could pass without a radio.

### Acceptance run — gate met (2026-09-14)

`tools/acceptance-matrix.py` on the current bench, channel 6, two repetitions
per arm compared best-of. Every row compares the *same* receiver under the two
paths, and every comparison is a decoded-frame count, not a ratio: a demo's own
`submitted` count includes ~50 bring-up submissions, so it is not a usable
denominator (the monitor sees roughly 150–200 test frames for a 200-frame
burst). Both arms send the same 200-byte QoS Data PSDU, so the airtime is
comparable, and every MCP capture is checked for ring eviction and bridge drops
(0/0 on these rows). The script pins only the transmitter's jaguar3 generation
and prints the roles it assigned; on this run T = `0bda:c812` (RTL8812CU),
receiver A = `0e8d:7612` (MT7612U), monitor B = `0bda:b812` (RTL8822B).

| Plane | Mode | Demo path | MCP path | Result |
|---|---|---|---|---|
| RX (same radio, MT7612U) | 6M | 100–200 heard | 200 heard | MCP not behind |
| RX (same radio, MT7612U) | MCS7/20 | 100–200 heard | 200 heard | MCP not behind |
| TX (same monitor, RTL8822B) | 6M | 194 heard | 185 heard; peer witness delivery 0.98, rate 4, `TX_VERIFIED` | inside band, verified |
| TX (same monitor, RTL8822B) | MCS7/20 | 183 heard | 160 heard; peer witness delivery 0.995, rate 19, `TX_VERIFIED` | inside band, verified |

The demo column cannot produce the right-hand column's evidence at all: a demo
reports *submission*, never whether anything reached the air, and it has no
capability model, no persistent capture, no second witness, and no
cancellation. The demo receiver's own count is the more variable one — rxdemo
heard 100–200 of the 250-frame bursts across repetitions while the MCP capture
read a full 200/200 each time — which is exactly why the verdict is
**not materially worse** (the gate's accepted band: max 15% or 25 frames, plus
an upper bound), checked best-of, rather than a claim of statistical equality.
The RX plane is never behind and the TX plane sits inside that band while
adding independent `TX_VERIFIED` evidence the demo cannot produce. So the gate
is met, and the matrix prints that strictly-better list on every run.


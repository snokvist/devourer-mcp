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

- 36 MCP tools, 25 bridge ops, 26 of 55 `IRadio` methods called.
- **Proven end to end on the current bench**: discover/open/describe; monitor
  RX with per-frame telemetry and raw bytes; capture store/query/PCAP; frame
  inspection; TX structured and raw; split carrier-sense gates; receive-gain
  clamp; TX-power offset/index/reapply; frame-free channel energy; active RX
  paths; tx_stats; multi-witness `experiment_link_probe`; characterization DB;
  scratchpad; dashboard.
- **TX_VERIFIED**: RTL8812CU (jaguar3) and MT7612U, witnessed independently.

## Milestones

Each milestone is a small, hardware-verifiable slice. Ordered by value, and by
what later milestones build on.

### M2 — Finish the power story (in progress)

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
| Absolute noise floor | `RX_NOISE_FLOOR` | Open arg only, unreachable pre-`Init` | blocked on bring-up path (`Init` vs `InitWrite`) | Compare to the meter on a quiet channel |
| Narrowband | `NB_BW/ADC/DAC` | Gap | open arg / channel width | 5/10 MHz TX+RX on J1/J3, witnessed |

### M4 — MAC features that change what a burst *is*

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| A-MPDU | `TX_AMPDU`, `TX_AMPDU_MODE` | RX-visible only | bridge op; experiment axis | Goodput at the same PHY rate, payload delivered not occupancy |
| Hardware ACK / ARQ | `ACK_RESPONDER` | **Partial**: `radio_ack_responder` arms/clears | `TX_RETRY_LIMIT`/`TX_RETRY_FALLBACK` bring-up knobs still open; an ARQ e2e experiment uses the receipts | Per-frame ledger on a witness; ACKed-but-undelivered must be visible |
| QoS / no-ack / STBC | `TX_QOS_*`, `TX_STBC_TOGGLE` | Mode spec covers some | widen the `TxMode` grammar | Decoded rate/flags on the witness |
| Per-packet TX power | `TX_PKT_PWR_DB/QDB`, `TX_PKT_OFSET` | Gap | scratchpad/experiment: radiotap `DBM_TX_POWER` per frame | Witness RSSI per rate/frame |

### M5 — Hopping and sensing (algorithms, not knobs)

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| `FastRetune` hopping | `HOP_CHANNELS/ROUNDS/DWELL_FRAMES` | Gap | experiment engine (`dwell` experiment), not an MCP argument wall | Hop rate + per-channel delivery with a witness |
| Adaptive hopset | `HOP_POLICY_*`, `HOP_SEED`, `HOP_SLOT_MS` | Gap | experiment engine + scratchpad tool | Reproduce a keyed schedule; lockstep RX |
| Sense-before-burst | `TX_SENSE_*` | Gap | experiment/scratchpad over `channel_energy` | Decide-and-inject policy with a logged reason |

### M6 — Time, beacons, AP mode

| Capability | Demo knobs | Now | Where | Verify |
|---|---|---|---|---|
| TSF read | (rx telemetry `tsfl`) | Gap | `IRadio::ReadTsf` | Compare to a second adapter's TSF |
| Beacons | — | Gap | `StartBeacon`/`StopBeacon`/`UpdateBeaconPayload` | A station associates, or a witness decodes the beacon |
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

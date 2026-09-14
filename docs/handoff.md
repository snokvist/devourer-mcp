# Handoff

Enough to resume cold. Read `CLAUDE.md` for the rules,
[`roadmap.md`](roadmap.md) for what is left, and
[`hardware-evidence.md`](hardware-evidence.md) for what the bench has actually
shown.

---

## Get it running

```sh
cmake -S native -B build/native-bridge -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-bridge -j          # devourer + the bridge
./gradlew :mcp:installDist                    # the MCP server

tools/host/bridge-ctl.sh start                # start|stop|restart|status|log
tools/smoke-test.py                           # end-to-end against real adapters
```

`tools/host/devourer-mcp` is the command an MCP host should point at. It pins
the JDK and starts the bridge itself. While it runs, the dashboard is at
<http://127.0.0.1:8910/> — radios, captures, experiments, scratchpads and a
live feed of every tool call. `--dashboard-port N` moves it, a negative value
turns it off, and a port already in use is logged and skipped rather than
being fatal.

Needs a JDK 21 toolchain and the Gradle wrapper does the rest. Gradle
auto-detects the usual locations and honours `JAVA_HOME` / `DEVOURER_MCP_JDK`;
for a JDK elsewhere, add `org.gradle.java.installations.paths=...` to
`~/.gradle/gradle.properties`. Nothing is installed system-wide except the udev
rules.

### Three things that will bite

**The socket path must stay under ~100 bytes.** `sun_path` is 108. A path under
a session scratch directory will exceed it and the bridge exits with "socket
path too long".

**Adapters need the udev rules.** `tools/host/70-devourer-usb.rules`, already
installed to `/etc/udev/rules.d/`. Without them everything needs root. They
grant the supported USB ids to `plugdev` and blacklist nothing.

**The RTL8812CU is held by the ground station when it runs.** `waybeam-hub`
(`systemctl` unit `waybeam-hub`) claims it, so `radio.open` fails with `busy`.
Free it with `sudo systemctl stop waybeam-hub`, do the radio work, then
`sudo systemctl start waybeam-hub` to restore the link. The MT7612U and the
RTL8822B are usually free.

---

## The map

```
LLM ──MCP(stdio)──▶ Kotlin runtime ──UDS control + frame stream──▶ devourer-bridge ──libusb──▶ adapter
```

| Path | What |
|---|---|
| `vendor/devourer/` | Pinned upstream at `45f4022`. Read-mostly; sync with `tools/host/vendor-devourer.sh`. |
| `native/bridge/` | The C++ helper. `Protocol.h` is the entire boundary contract. |
| `kotlin/protocol/` | Wire types. `FrameRecord` hardcodes byte offsets — see below. |
| `kotlin/radio/` | Bridge client, capability model, verification ladder. |
| `kotlin/capture/` | Frame store, queries, summaries, PCAP. |
| `kotlin/experiment/` | `LinkProbe`, sweeps, roles, and the run registry that makes an experiment cancellable. |
| `kotlin/characterize/` | Evidence database, one JSON per adapter. |
| `kotlin/scratchpad/` | Declarative micro-app runtime + live UI. |
| `kotlin/dashboard/` | The persistent dashboard on `127.0.0.1:8910`. Reads in-process state only; never calls the bridge. |
| `kotlin/mcp/` | The 39 tools. The only process the model talks to. |
| `var/` | Runtime state: captures, `characterization/`, `scratchpads/`. Gitignored. |

### Why a separate bridge process

A native fault or a wedged adapter must not take down the thing the model is
talking to — during exactly the low-level experimentation this exists for. It
also keeps frames off the JVM heap and lets the bridge hold USB privileges while
the Kotlin side stays unprivileged. The cost is a socket hop on control calls,
which is noise next to a 130 ms channel set.

---

## Three things that are easy to break

**`FrameRecord` offsets are hand-maintained on both sides.** `Protocol.h` and
`FrameRecord.kt` agree by convention, not by type. A drift would not crash — it
would silently report the wrong RSSI, rate and retry flag while looking
plausible. Two guards: `FrameRecordTest` writes a distinct value at every
documented offset and reads it back, and `hello` reports
`frame_record_bytes` which the client refuses to mismatch. **Change both sides
together and bump the protocol version.**

**The RX callback must never block.** Devourer states that an undrained MT7612U
receiver wedges below the USB level, and it did exactly that here when the frame
writer copied its buffer under the lock `on_packet` needs. It is double-buffered
now, with an O(1) swap. Anything added to `Session::on_packet` must stay
constant-time.

**Chain counts come from `rx_chains`, never from counting non-zero slots.** On a
2T2R RTL8812A, `snr[2]`/`snr[3]` hold stream CSI, not path C/D SNR, and they are
routinely non-zero.

---

## What the bench established

Both findings below are the instrument working, and both are recorded with
evidence in `hardware-evidence.md`.

**Carrier sense was hiding a 90% transmit loss.** The RTL8812AU reported
`submitted=100, failed=0` and aired 4–13 frames on an idle channel. Two MT7612U
receivers witnessing simultaneously agreed exactly, which is what ruled out the
receiver. With carrier sense off: 88–100%. This is the concrete case behind the
rule that `tx_send` can never grant `TX_VERIFIED`.

**Channel 6 is empty on this bench** (ch1 and ch11 carry thousands of frames per
4 s; ch6 carries ~1). Good for injection measurements, and a trap: a quiet
channel looks exactly like a deaf receiver.

**The MT7612U is indistinguishable from other MT7612U units by USB descriptors**
— same id, same product string, serial `000000000`. (Only one remains on the
bench since 2026-09-13.) Its EEPROM MAC differs per unit, but MediaTek reads
the EEPROM during chip init, so the address does not exist until the adapter is
brought up. Realtek reports its MAC from construction.

---

## Testing

```sh
./gradlew test                                   # 248 Kotlin tests, one hardware-tagged
ctest --test-dir build/native-bridge             # 64 native selftests (63 vendored + radiotap layout)
tools/mcp-verify.py                              # every MCP tool, real requests, artifacts + dashboard
tools/smoke-test.py                              # RX path, all adapters; never passes vacuously
tools/rx-gain-cca-test.py                        # receive-gain clamp + split CCA gates
tools/tx-power-test.py                           # TX-power knobs + a sweep measured on a witness
tools/rx-quality-thermal-test.py                 # fused RX sensor + thermal meter
tools/fast-retune-test.py                        # lean same-band hop + narrowband toggle
tools/spectrum-sweep-test.py                     # coarse per-channel energy survey
tools/tx-receipts-test.py                        # per-frame TX reports (needs a Jaguar TX)
tools/tx-retry-arq-test.py                       # retry-limit knob + hardware ARQ (needs a Jaguar TX)
tools/ack-responder-test.py                      # hardware ACK responder + safety gate
tools/ampdu-test.py                              # A-MPDU read/enable/clear + capability tri-state
tools/ampdu-goodput-test.py                      # A-MPDU goodput vs both controls, measured on a witness
tools/stbc-test.py                               # /STBC airs and decodes on an independent monitor
tools/narrowband-test.py                         # 5/10 MHz TX+RX witnessed, plus the width gate
tools/multi-witness-test.py                      # two independent witnesses + the localisation note
tools/acceptance-matrix.py                       # demo-vs-MCP acceptance run; prints the strictly-better list
./gradlew :mcp:test -PwithHardware               # hardware-tagged JUnit: list/open/describe/monitor/close
tools/tsf-test.py                                # MAC TSF read + adoption
tools/beacon-test.py                             # hardware beacon, decoded by an independent witness
tools/stall-test.py / tools/backpressure-test.py # sink stops reading / sustained overload

# A/B a vendor change against a pristine build before proposing it — the
# pattern that caught a 96% result which turned out to be session state:
#   git worktree add --detach <scratch> <commit-before-the-patch>
#   build both, run the same battery against each, alternate the builds
```

Hardware-dependent tests are excluded unless `-PwithHardware` so their absence
can never read as a pass. The tag exists; nothing carries it yet — hardware
testing is currently the Python smoke test.

---

## Conventions worth keeping

- **Absent is not zero.** A field a chip does not populate is omitted, not
  returned as 0. Means are computed only over frames that actually reported.
- **Derive, don't hardcode.** Supported devices and capabilities come from
  Devourer's source and capability reports. There is no chipset table here and
  there should not be one.
- **Every claim carries its evidence.** The verification ladder is stored, not
  asserted in prose, and `BUILDS` never implies anything about hardware.
- **Errors say what the hardware did.** "Failed" is not a diagnosis. A busy
  adapter names the process holding it.
- **Experiments are bounded and restore state.** Carrier sense is put back even
  on the failure path.

---

## Where the carrier-sense investigation landed

Settled, with evidence in [`hardware-evidence.md`](hardware-evidence.md):

- **EDCCA, not primary CCA, is what stops a Jaguar1 injector.** Turning it
  off alone recovers 94%; primary CCA off alone recovers almost nothing. That
  inverts what devourer documents (measured on Jaguar3, where the 8812AU was
  only ever the flooder).
- **You do not disable carrier sense to fix it.** EDCCA off with primary CCA
  left on gives 95% idle and still defers to a real flooder (78%). Both gates
  off is *worse* — the injector collides, 0.3%.
- **Normal drivers do not hit this because they never enable the feature.**
  The Realtek vendor driver ships `CONFIG_RTW_ADAPTIVITY_EN 0` and exposes
  `th_l2h_ini` / `th_edcca_hl_diff` as module parameters; devourer enables
  EDCCA at Jaguar1 bring-up and hard-codes both.
- **Receive gain was the right gate and the wrong lever.** IGI moves the
  EDCCA threshold only across a narrow coupled range (`L2H = th_l2h_ini +
  0x32 - IGI`, clamped to 10) and even its permissive end leaves delivery at
  ~4%.

One vendored patch remains: `0001-rx-gain-range.patch` (the hardware-verified
`RxGain*` contract). The gate split landed upstream through
[OpenIPC/devourer#427](https://github.com/OpenIPC/devourer/pull/427) and its
[follow-up #429](https://github.com/OpenIPC/devourer/pull/429) — see
[`hardware-evidence.md`](hardware-evidence.md) for what the bench said about
each point, including the one maintainer claim that did not survive
measurement. The EDCCA-at-bring-up policy question is
[#428](https://github.com/OpenIPC/devourer/issues/428). The RX-gain patch was
rebased onto the merged CCA/watchdog code; its Jaguar1 implementation now owns
the small amount of gate-state memory needed when a gain change re-derives the
EDCCA threshold.

## Picking up (2026-09-13)

State: **39 MCP tools, 28 bridge ops, protocol v1.14, the bridge calls 35 of 55
`IRadio` methods, 248 Kotlin tests + 64 native selftests.** The current bench is
an RTL8812CU (Jaguar3), an RTL8822B (Jaguar2) and one MT7612U; the second
MT7612U was swapped out on 2026-09-13 for the RTL8822B, because 5/10 MHz
narrowband needs two Realteks (the MT7612U cannot do it). The 8812AU/Jaguar1
results below are history. Everything merged in PRs #10–#25.

### What this session added, end to end

| Area | Exposed as | Verified |
|---|---|---|
| RX gain clamp, split CCA gates | `radio_rx_gain`, `radio_cca_gates` | 8812CU + MT (honest absence) |
| TX power: offset / flat index / reapply | `radio_tx_power` | 8812CU |
| TX power: per-rate diffs | `radio_tx_power.rate_diffs` | 8812CU, rate-selective on a witness |
| TX power: sweep axis | `experiment_link_probe.sweep_power_qdb` | 8812CU → MT witness |
| Fused RX quality, thermal | `radio_rx_quality`, `radio_thermal` | 8812CU + MT |
| Per-frame TX receipts (`tx.report`) | `radio_tx_receipts` + `radio_open.tx_report` | 8822C, 200/200 on a burst |
| Lean retune | `radio_fast_retune`, `radio_fast_bandwidth` | 21 ms hop vs 130 ms full |
| Energy survey | `spectrum_sweep` | quietest channel on ch1/6/11 |
| Hardware ACK responder | `radio_ack_responder` | all three arm/clear |
| A-MPDU control | `radio_ampdu` | 8822C enables; MT refuses honestly |
| A-MPDU goodput | `ProbeFrame` QoS form + `experiment_link_probe qos_tid` | +32–35% at MCS7/20 vs both A-MPDU-off controls (three runs), independent witness; broadcast/no-ack, and the result records the armed state |
| STBC | mode grammar `/STBC` | airs and decodes: control `stbc=0` ×362 vs `/STBC` `stbc=1` ×357 on an independent RTL8822B monitor (`tools/stbc-test.py`; counts vary per run, every probe in an arm agrees) |
| Multi-witness link probe | `experiment_link_probe.rx_session` + `witness_sessions` | RTL8822C TX, RTL8822B + MT7612U both decode it (253/300 and 300/300, delivery 0.84–0.96), result carries the two-witness localisation note (`tools/multi-witness-test.py`) |
| Narrowband 5/10 MHz | `experiment_link_probe width_mhz` | both directions at both widths on RTL8822C ↔ RTL8822B (10 MHz 0.98/0.99, 5 MHz 0.995/1.000); MT7612U refused as a 5 MHz witness (`tools/narrowband-test.py`) |
| Acceptance matrix (demo vs MCP) | `tools/acceptance-matrix.py` | gate met: same-radio RX MCP 200/200 at 6M and MCS7/20 while rxdemo varied 100–200 (never ahead); same-monitor TX 185 vs 194 (6M) and 160 vs 183 (MCS7/20), peer `TX_VERIFIED` at rates 4/19, delivery 0.98/0.995, zero capture evictions/drops; count-based, matched 200-byte QoS Data frames, "not materially worse" |
| MAC TSF read + adoption | `radio_tsf` (+ `set_tsf_us`) | reads all; write 8822C only |
| Hardware beacon (arm/update/stop) | `radio_beacon` | MT7612U and RTL8822C (Jaguar3) armed, witnessed by an independent MT7612U (~30 beacons, 102.4 ms cadence, live TX-egress TSF; `update` swapped the SSID on air; quiet after stop) and by the host MT7922 on its stock kernel driver (`iw scan` + monitor capture, TSF delta 102399 µs) |
| Whole-surface verification | `tools/mcp-verify.py` | 46/46 |

`tools/rxdemo-txdemo-parity.md` is the staged plan; M2 is complete, M3 is
complete (retune/survey primitives, multi-witness evidence, narrowband 5/10 MHz
TX+RX at both widths and in both directions — the reverse width is chosen per
`tools/narrowband-test.py --jaguar2-width` run; the absolute noise floor is
recorded blocked on the bring-up path with `igi` as the relative proxy), M4's A-MPDU
goodput, hardware ARQ, STBC and no-ack semantics are measured (a jaguar2
stuck-TX condition, cleared by a VBUS power cycle, is recorded in
`hardware-evidence.md`), and the M5
acceptance matrix is **met on the bench** (`tools/acceptance-matrix.py`): the RX
plane matches exactly on the same radio and the TX plane is not materially worse
by decoded count on the same monitor, while adding witness `TX_VERIFIED`
evidence a demo cannot produce. M6 has started.

### Next

The ordered plan lives in
[`roadmap.md`](roadmap.md) under **"Path to the gate (next steps)"**. Steps 1–5
are done and the gate is met; the only remaining item is the optional step 6.

1. **A-MPDU goodput — done.** `ProbeFrame` builds the QoS Data form (a TID for
   the aggregator), and `tools/ampdu-goodput-test.py` measured delivered
   payload against both A-MPDU-off controls: **+32–35% at MCS7/20** across three
   runs, no gain at MCS0/20 as expected, on an independent MT7612U witness.
   `LinkProbe` records the transmitter's `ampdu` state and labels a
   non-aggregated QoS run single-MPDU. See `hardware-evidence.md`.
2. **M4 loose ends — done.** STBC verified on an independent monitor
   (`tools/stbc-test.py`: control `stbc=0`, `/STBC` `stbc=1`); no-ack semantics
   documented as the retry-limit-0 state shared by `tx_retry_limit:0` and
   `AmpduMode.no_ack`. Follow-up: a jaguar2 can stick in a TX state that a
   VBUS power cycle clears (three sequential runs passed after the replug).
3. **Multi-witness role in `LinkProbe` — done.** Roles were already first-class
   (`rx_session` + `witness_sessions` → `RX_PEER`/`MONITOR`), and
   `tools/multi-witness-test.py` proves it on hardware: one RTL8822C
   transmitter, both receivers decode it (253/300 and 300/300, delivery 0.84–0.96), and the result
   carries the two-witness localisation note. The two-board antenna swap is
   `UNAVAILABLE` — only one MT7612U remains.
 4. **M3 remainders — done.** Narrowband 5/10 MHz verified TX+RX in both
    directions at both widths on the jaguar3 + jaguar2 pair
    (`tools/narrowband-test.py`: 10 MHz 0.98/0.99, 5 MHz 0.995/1.000), with
   the wide-only MT7612U refused as a 5 MHz witness. The absolute noise floor
   was attempted and is recorded blocked: the vendor CAL lives in
   `IRadio::Init` and the bridge uses `InitWrite` + `StartRxLoop`, so
   `channel_energy` reports `valid_noise_floor:false` with the reason and
   offers `igi` as the relative proxy.
5. **Acceptance matrix — done, gate met.** `tools/acceptance-matrix.py` runs the
   demo and MCP paths on the same bench and compares decoded counts on the same
   receiver, both arms sending the same 200-byte QoS Data PSDU: the MCP capture
   heard 200/200 at 6M and MCS7/20 while rxdemo's own count varied (100–200,
   never ahead); TX was 185 vs the demo's 194 (6M) and 160 vs 183 (MCS7/20) on
   the same monitor, with the MCP peer witness decoding rates 4/19, 0.98/0.995
   delivery and `TX_VERIFIED`, and every MCP capture at zero evictions/drops.
   Count-based because a demo's `submitted` includes ~50 bring-up frames;
   verdict "not materially worse" within an explicit band (max 15% or 25
   frames), and the strictly-better list prints on every run. Full table:
   `docs/rxdemo-txdemo-parity.md` "The acceptance test".
6. **Optional: hardware-tagged JUnit — seed done.** `HardwareSmokeTest` (in
   `:mcp`) is tagged `hardware`, runs list → open → describe → monitor → close
   against the real bridge with `./gradlew :mcp:test -PwithHardware`, and skips
   when the bridge or adapters are absent. The Python checks remain the full
   hardware runs; converting them all onto the tag is still open.

Already done and independently verified this session: M6 beacons
(`radio_beacon` + `tools/beacon-test.py`, plus the host MT7922 as an
independent-generation witness) and the non-A-MPDU half of M4 — hardware ARQ
(`tools/tx-retry-arq-test.py`) and per-packet TX power (`pkt_power_db`, radiotap
`DBM_TX_POWER` bit 10). Station *association* and the M5 algorithms are
deliberately out of scope; the reasons are in the roadmap section above.

### Open findings (recorded, not fixed — vendored)

- **8822C raw PWDB > 127.** `parse_phy_sts_jgr3` assumes the 0..127 convention,
  so a byte ≥128 converts to a >17 dBm reading and can drive a bogus
  `SATURATED` verdict. The bridge flags the out-of-range window; the parser fix
  belongs upstream.
- **MT7612U `WriteTsf` is a silent no-op.** The override calls
  `mt7612u_write_tsf()`, which writes the same `DW0`/`DW1` the read path reads,
  yet the value does not stick, and the method is `void`. `radio_tsf` reports
  `took:false`; the fix belongs upstream.

### Working rules that did not change

Derive, don't hardcode. Capability-gate every advanced op; a backend that
cannot do something must say so. Absent is not zero. Every claim carries its
evidence. A new bridge op bumps the additive protocol minor; the next one is
1.14 → 1.15.

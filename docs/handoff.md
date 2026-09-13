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

### Two things that will bite

**The socket path must stay under ~100 bytes.** `sun_path` is 108. A path under
a session scratch directory will exceed it and the bridge exits with "socket
path too long".

**Adapters need the udev rules.** `tools/host/70-devourer-usb.rules`, already
installed to `/etc/udev/rules.d/`. Without them everything needs root. They
grant the supported USB ids to `plugdev` and blacklist nothing.

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
| `kotlin/mcp/` | The 38 tools. The only process the model talks to. |
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

**The two MT7612U adapters are indistinguishable by USB descriptors** — same id,
same product string, serial `000000000`. Their EEPROM MACs differ, but MediaTek
reads its EEPROM during chip init, so the address does not exist until the
adapter is brought up. Realtek has it from construction.

---

## Testing

```sh
./gradlew test                                   # 220 Kotlin tests, no hardware
ctest --test-dir build/native-bridge             # 63 vendored selftests
tools/mcp-verify.py                              # every MCP tool, real requests, artifacts + dashboard
tools/smoke-test.py                              # RX path, all adapters; never passes vacuously
tools/rx-gain-cca-test.py                        # receive-gain clamp + split CCA gates
tools/tx-power-test.py                           # TX-power knobs + a sweep measured on a witness
tools/rx-quality-thermal-test.py                 # fused RX sensor + thermal meter
tools/fast-retune-test.py                        # lean same-band hop + narrowband toggle
tools/spectrum-sweep-test.py                     # coarse per-channel energy survey
tools/tx-receipts-test.py                        # per-frame TX reports (needs a Jaguar TX)
tools/ack-responder-test.py                      # hardware ACK responder + safety gate
tools/ampdu-test.py                              # A-MPDU read/enable/clear + capability tri-state
tools/tsf-test.py                                # MAC TSF read + adoption
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

State: **38 MCP tools, 27 bridge ops, protocol v1.12, the bridge calls 31 of 55
`IRadio` methods, 220 Kotlin tests + 63 native selftests.** The current bench is
an RTL8812CU (Jaguar3) plus two MT7612U; the 8812AU/Jaguar1 results below are
history. Everything merged in PRs #10–#25.

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
| MAC TSF read + adoption | `radio_tsf` (+ `set_tsf_us`) | reads all; write 8822C only |
| Whole-surface verification | `tools/mcp-verify.py` | 46/46 |

`tools/rxdemo-txdemo-parity.md` is the staged plan; M2 is complete, M3's
retune/survey primitives are done, M4 is partial, M6 has started.

### Next

1. **M6 beacons + AP mode** (`StartBeacon`/`StopBeacon`/`UpdateBeaconPayload`):
   verify with a second adapter decoding the autonomous beacon and its TSF
   stamp, and a station associating.
2. **M4 remainders**, each with a known blocker: A-MPDU *goodput* needs a deep
   TX feeder (`send_packets` with frames in flight); per-packet TX power is
   raw-path only because `build_stream_radiotap` cannot carry `DBM_TX_POWER`
   and appending it flips `send_packet`'s length heuristic; TX retry-limit/
   fallback are bring-up knobs whose effect needs structured unicast.
3. **Multi-witness role in `LinkProbe`** — the two-witness run that settled the
   carrier-sense question was done by hand at the bridge; making it a
   first-class role would also settle the open antenna question.

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
1.12 → 1.13.

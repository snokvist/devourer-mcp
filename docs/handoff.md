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
| `vendor/devourer/` | Pinned upstream at `30d248e`. Read-mostly; sync with `tools/host/vendor-devourer.sh`. |
| `native/bridge/` | The C++ helper. `Protocol.h` is the entire boundary contract. |
| `kotlin/protocol/` | Wire types. `FrameRecord` hardcodes byte offsets — see below. |
| `kotlin/radio/` | Bridge client, capability model, verification ladder. |
| `kotlin/capture/` | Frame store, queries, summaries, PCAP. |
| `kotlin/experiment/` | `LinkProbe`, sweeps, roles, and the run registry that makes an experiment cancellable. |
| `kotlin/characterize/` | Evidence database, one JSON per adapter. |
| `kotlin/scratchpad/` | Declarative micro-app runtime + live UI. |
| `kotlin/dashboard/` | The persistent dashboard on `127.0.0.1:8910`. Reads in-process state only; never calls the bridge. |
| `kotlin/mcp/` | The 25 tools. The only process the model talks to. |
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
./gradlew test                                   # 147 Kotlin tests, no hardware
ctest --test-dir build/native-bridge             # 63 vendored selftests
tools/smoke-test.py                              # needs adapters; never passes vacuously
tools/stall-test.py                              # needs adapters; a sink that stops reading
tools/backpressure-test.py                       # needs two adapters; sustained overload
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

## Picking up

The highest-value next step is closing `IRadio` coverage — the bridge calls 11 of
52 methods, and that single number explains most of what this cannot yet do.
`radio.tx_stats` and `radio.cca` are the pattern to copy: a bridge op, a
`RadioManager` method, an MCP tool with a description that says what the result
does *not* prove.

After that, multi-witness experiments. The two-witness run that settled the
carrier-sense question was done by hand against the bridge; making it a first-
class role in `LinkProbe` would also settle the open antenna question.

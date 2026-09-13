# devourer-bridge

The native half of the boundary: one process that owns libusb and every open
radio, speaking a small versioned protocol over a Unix socket.

## Why a process and not JNI

Binding the JVM directly to `IRadio` would be less code. It would also put the
USB/radio layer inside the MCP server's address space, where a native fault or
a wedged adapter takes down the thing the model is talking to — during exactly
the kind of low-level experimentation this project exists to do. A separate
process buys crash isolation, keeps high-rate frame data off the JVM heap,
lets the bridge hold USB privileges while the Kotlin runtime stays
unprivileged, and maps onto Android later (same protocol, different launcher).

The cost is a socket hop on control calls, which is noise next to a
channel-set (~130 ms) or an IQK calibration (~257 ms).

## Protocol

`src/Protocol.h` is the contract. Two planes over one socket, chosen by the
client's first line:

| First line | Plane |
|---|---|
| `{"attach":<session>}` | binary frame stream for that session |
| anything else | JSON-Lines control; that line is the first request |

Control is request/response with an optional `id` echoed back:

```
{"id":1,"op":"radio.list"}
{"id":1,"ok":true,"result":{...}}
{"id":1,"ok":false,"error":{"code":"busy","message":"..."}}
```

Frames are fixed 88-byte `FrameRecord` headers followed by `frame_len` bytes of
802.11 MPDU. Little-endian, packed, magic `DVRF` on every record so a
desynchronized reader fails at the next record instead of reading payload as a
header.

### Ops

| Op | Notes |
|---|---|
| `hello` | protocol version, vendored devourer commit, compiled backends |
| `radio.list` | `all:true` also lists devices nothing claims |
| `radio.open` | `bus`, `address`; opens + claims, does NOT power the chip |
| `radio.describe` | AdapterCaps / TxCaps / TxPowerCaps + permanent MAC |
| `radio.close` | |
| `radio.channel` | retunes; brings up if needed |
| `radio.fast_retune` | lean same-band hop (`IRadio::FastRetune`); width/offset/band kept, requires an already-up radio, falls back to a full retune on a band change or where the family has no lean path. The reply's `fast_retune` says whether the lean path exists. |
| `radio.fast_bandwidth` | bandwidth analogue (`IRadio::FastSetBandwidth`), 20<->5/10 narrowband; capability-gated on the adapter's width set, falls back to a full retune otherwise. |
| `monitor.start` | `channel`, `width_mhz`, optional `offset`, `band` |
| `monitor.stop` / `monitor.stats` | |
| `tx.send` | structured `mode` + `body_hex`, or raw `frame_hex`/`frame_b64`; bounded `count` ≤ 100000 and a 30 s wall-clock budget |
| `radio.rx_paths` | live per-chain activity estimate; reports `supported:false` where a backend has not ported it |
| `radio.tx_stats` | devourer's driver-side `TxStats` — submitted vs failed, i.e. host-side only |
| `radio.tx_receipts` | per-frame `tx.report` receipts (the CCX C2H account of each transmission): delivery state, hardware retries, final rate, queue time, HalMAC tag. Only when opened with `radio.open`'s `tx_report` (0 = off); drained on read (`clear:false` peeks). The TX-side sensor `tx_stats` cannot be. Jaguar generations only; needs an RX loop. |
| `radio.cca_gates` | the carrier-sense gate one bit at a time: `primary_cca_disabled` (0x520[14], defers to a decodable preamble) and `edcca_disabled` (0x520[15], defers to raw energy). Realtek only; `radio.cca` still turns both off together. |
| `radio.rx_gain` | receive-gain index, its bounds, and whether any loop is adjusting it (`automatic_input` says what that loop keys on, or why it is inert). Pass `min_index`/`max_index` to clamp it — `min == max` pins. Vendor-neutral (`IRadio`). |
| `radio.tx_power` | runtime TX power as the index/offset model (`TxPower.h`), never dBm: caps + applied state (`step_measured` says whether the slope is measured or documented). Pass `offset_qdb` (relative, shape-preserving), `index_override` (flat index; -1 clears), `rate_diffs` (`{cck, legacy, mcs:[8]}` signed qdB vs the MCS7 anchor; REPLACES the calibrated shape) or `clear_rate_diffs`, and/or `reapply` (re-program at the current channel). Vendor-neutral (`IRadio`); a backend without the knobs reports `supported:false`, and one without the per-rate table refuses `rate_diffs`. |
| `radio.rx_energy` | frame-free channel energy from the chip's own PHY: phydm FA/CCA counters, the DIG initial-gain index, optionally the NHM power histogram (`with_nhm`). Realtek only — `IRtlRadio`, not `IRadio` — and reports `supported:false` elsewhere rather than zeros. FA/CCA are deltas since the previous read, which resets them. |
| `radio.rx_quality` | the fused windowed RX sensor (`IRadio::GetRxQuality`): per-frame RSSI/SNR/EVM aggregate, a passive noise floor, FA/CCA/IGI, and the LinkHealth verdict. DRAINS on read, so read once and discard, dwell, read again; consumes the same counters as `radio.rx_energy` — do not poll both on one cadence. Realtek only. Flags an out-of-range peak RSSI rather than passing a bad-derived verdict clean. |
| `radio.thermal` | the RF 0x42 thermal meter: raw thermal units, baseline, delta, and a coarse bucket. Telemetry, not a calibrated temperature and not a validated degradation predictor. `supported:false` where no meter is wired. |
| `radio.cca` | MAC carrier-sense gate. Antisocial when disabled; the Kotlin layer requires `SafetyLevel.EXPERIMENTAL` |
| `radio.ack_responder` | arm/clear the hardware ACK responder (`IRadio::SetAckResponder`): auto-ACK unicast frames to `mac` with no host involvement, the reliable-unicast enabler. Omit both to read. Unicast only; capability-gated on `AdapterCaps.ack_responder_ok`. Arming is EXPERIMENTAL in the Kotlin layer (it answers others' air); clearing is always allowed and best-effort. |
| `sessions` / `shutdown` | |

## Two things that are correctness, not style

**Identification is not enumeration.** MT7612U, RTL8733B and Kestrel have
static VID:PID tables in the vendored source, so `radio.list` answers them
exactly (`identification: "usb_id"`). The Realtek 11ac families are dispatched
on a SYS_CFG2 chip-id read over USB, which needs an open — those report
`identification: "probe_required"` and an empty `backend` until `radio.open`
fills it in. Presenting a guess as an identification would be the
"compilation means verification" error wearing a different hat.

**The receiver must never run undrained.** Devourer's MT7612U notes are blunt
about this: a receiver with nothing reading it wedges the part. So the RX
callback never blocks on a slow client. Frames go into a bounded buffer under a
short lock and a separate writer thread drains it; when the buffer is full a
*whole record* is dropped and counted (`monitor.stats.dropped`). Partial
records are never written — one would desynchronize the reader for every frame
after it.

## Build and run

```sh
cmake -S native -B build/native-bridge -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-bridge -j
tools/host/bridge-ctl.sh start     # socket in $XDG_RUNTIME_DIR/devourer-mcp/
```

Every backend the vendored source implements is compiled in, including
MT7612U, which upstream defaults OFF.

Keep the socket path under ~100 bytes: `sun_path` is 108 and a path under a
session scratch directory will blow past it.

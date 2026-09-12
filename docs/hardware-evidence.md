# Hardware evidence

What the local bench hardware has actually demonstrated, at the verification
levels defined in `CLAUDE.md`. Anything not listed is `UNAVAILABLE` — no
hardware to test with — which is an absence of evidence, not a failure.

Compilation appears here only as the `BUILDS` rung. Every backend in the
vendored source compiles; that is the least interesting thing we know.

## 2026-09-12 — first vertical slice

| Adapter | USB id | Backend | State | Evidence |
|---|---|---|---|---|
| Realtek RTL8812AU | `0bda:8812` | jaguar1 (chip-id 0x04) | `RX_VERIFIED` | 8997 frames / 10 s on ch1/20, 0 dropped; 12141 frames at 1522/s through MCP |
| MediaTek MT7612U (USB3 port) | `0e8d:7612` | mt7612u | `RX_VERIFIED` | 5905 frames / 10 s on ch1/20, 0 dropped |
| MediaTek MT7612U (USB2 port) | `0e8d:7612` | mt7612u | `RX_VERIFIED` | 20890 frames / 10 s on ch1/20, 0 dropped |
| Everything else devourer implements | — | — | `UNAVAILABLE` | no hardware present |

TX is `IMPLEMENTED_IN_SOURCE` + `BUILDS` on all three. It is **not**
`TX_VERIFIED`: nothing has yet been observed by an independent receiver.

## Backend differences confirmed by measurement

The MT7612U and the RTL8812AU were pointed at the same air and disagreed in
exactly the ways their capability reports predict. That agreement is the useful
part — the capability model checked against reality rather than trusted.

| | RTL8812AU | MT7612U |
|---|---|---|
| `fcs_present` | true | **false** — the MAC strips it; the trailing 4 bytes are an FCE trailer |
| `tsfl` (chip RX timestamp) | populated | **0**, and `hw_rx_timestamp` is absent from its features |
| per-stream SNR | often empty | populated |
| `GetPermanentMacAddress` | `20:0d:b0:c4:a7:6a` | not implemented — reported absent, not as a zero MAC |
| `GetActiveRxPaths` | implemented | not implemented — reported unsupported, never as "0 chains active" |

Both decoded qos-data, block-ack, rts/cts, beacons and probe-requests, with
per-chain RSSI, A-MPDU markers, retry flags and beacon TX-egress TSF.

## A correctness bug this found

On the 2T2R RTL8812A, `snr[2]` and `snr[3]` are **not** path C/D SNR. Devourer
fills them from `csi_current`, which carries stream CSI on 8812 and is path C/D
SNR only on an 8814AU (`src/jaguar1/FrameParser.cpp` says so explicitly). Those
bytes are routinely non-zero, and the first version of the summary reported them
as "chainC SNR 19.6, chainD SNR 6.0" on a two-chain radio.

Fix: the bridge stamps the adapter's real `rx_chains` into every frame record,
and every chain view is bounded by it. Counting non-zero slots is never correct.
Pinned by `FrameRecordTest."chain views are bounded by the radio's real chain
count"` and `CaptureStoreTest."chain stats never exceed the radio's real chain
count"`.

## Antennas are not chains

Both MT7612U units report 2T2R and two active chains, though one board carries
four antenna connectors and the other two. That is expected: the MT7612U is 2T2R
silicon, and a four-connector board switches antennas into two chains rather
than adding chains. **No static report distinguishes the two boards** — same USB
id, same chip, same capability output, same permanent-MAC behaviour.

Per-chain balance over ambient traffic (ch1/20, 10 s each, *not simultaneous*,
so absolute levels are not comparable between rows):

| Adapter | chain A mean | chain B mean | spread |
|---|---|---|---|
| MT7612U (USB2 port) | 62.6 | 62.6 | 0.0 |
| MT7612U (USB3 port) | 41.7 | 48.5 | 6.8 |

Neither shows a dead chain. This does **not** establish which board has four
connectors: the runs were at different times and positions, so the difference in
spread is not attributable to the boards. Settling it needs a controlled
experiment — one transmitter, both receivers monitoring simultaneously, repeated
across several positions and channels. That is the experiment engine's job, and
it is not built yet.

## Reproducing

```sh
tools/host/bridge-ctl.sh start
./gradlew :mcp:installDist
tools/host/devourer-mcp           # speak MCP on stdio
```

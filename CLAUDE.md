# devourer-mcp

Turn supported USB Wi-Fi adapters into an **AI-operable, programmable wireless
instrument**. A Kotlin control runtime exposes [OpenIPC
Devourer](https://github.com/OpenIPC/devourer) through MCP so a model can
discover radios, observe traffic, inspect PHY/MAC behaviour, transmit
controlled frames, run bounded experiments, characterize hardware, and build
small purpose-made tools when the fixed MCP surface is not enough.

> Give an AI eyes and hands at the 802.11 MAC/PHY boundary using inexpensive
> USB Wi-Fi hardware.

## Architecture

```text
LLM ──MCP(stdio)──▶ Kotlin runtime ──UDS control + frame stream──▶ devourer-bridge ──libusb──▶ adapter
                     (JVM, unprivileged)                            (C++, links vendored Devourer)
```

Three rules that shape everything:

1. **Devourer stays native and vendored.** `vendor/devourer` is a pinned copy.
   We never rewrite its radio/USB logic.
2. **The Kotlin↔native boundary is narrow and versioned.** Kotlin talks to a
   separate `devourer-bridge` process over a documented protocol. It does not
   bind to Devourer's C++ internals, and it does not share an address space
   with the USB/radio code — a wedged adapter or a native fault must not take
   down the MCP server.
3. **MCP is the control and reasoning plane, never the packet data plane.**
   Frames stay local. MCP carries references, queries, summaries and evidence.

### Module layout

| Path | What |
|---|---|
| `vendor/devourer/` | Pinned upstream Devourer. Read-mostly — see *Vendoring*. |
| `native/bridge/` | `devourer-bridge`: C++ helper linking Devourer, speaking the bridge protocol. |
| `kotlin/protocol/` | Bridge wire types + codec. The single definition of the boundary. |
| `kotlin/radio/` | Radio abstraction, bridge client, adapter lifecycle, capability model. |
| `kotlin/capture/` | Frame store, query engine, summaries, PCAP/PCAPNG export. |
| `kotlin/experiment/` | Experiment engine: deterministic local execution of AI-defined experiments. |
| `kotlin/characterize/` | Adapter characterization + the evidence database. |
| `kotlin/scratchpad/` | Sandboxed micro-app runtime (capability-gated). |
| `kotlin/ui/` | Dynamic UI service for on-demand instruments. |
| `kotlin/mcp/` | MCP server; composes the above. The only process the LLM talks to. |
| `tools/host/` | Host setup: udev rules, helper scripts. |
| `var/` | Runtime state: captures, evidence DB, scratchpads. Not source. |

### Build and run

The JVM toolchain is user-local; nothing is installed system-wide.

```sh
export JAVA_HOME=/home/snokvist/dev-tools/jdk-21
./gradlew build                  # Kotlin: compile + test
./gradlew :kotlin:mcp:run        # MCP server on stdio
tools/host/build-bridge.sh       # CMake build of vendor/devourer + native/bridge
ctest --test-dir build/native    # native tests
```

Gradle 9.7.1 and Temurin JDK 21 live in `/home/snokvist/dev-tools/`.
Devourer needs `libusb-1.0-dev`, CMake ≥ 3.15 and a C++17 compiler.

## Verification states — never blur these

Compilation is not verification. Every claim about a chipset, backend or
capability carries exactly one of these states, and the state is stored as
evidence, not asserted in prose:

| State | Means |
|---|---|
| `IMPLEMENTED_IN_SOURCE` | Devourer source has a backend for it. |
| `BUILDS` | It compiles and links in our configuration. |
| `DETECTED` | A physical adapter matching it enumerated on USB. |
| `INITIALIZED` | The chip powered up, firmware loaded, `Init` returned. |
| `RX_VERIFIED` | Real frames were received and parsed from the air. |
| `TX_VERIFIED` | Transmitted frames were observed by an independent receiver. |
| `CHARACTERIZED` | A characterization run completed and is stored. |
| `UNAVAILABLE` | No hardware present to test. Not a failure. |
| `FAILED` | Attempted and did not work. Record why. |

**`BUILDS` never implies `DETECTED`.** `TX_VERIFIED` specifically requires an
*independent* receiver (a second adapter or monitor oracle) — a radio
reporting its own TX success is not evidence that anything reached the air.

Do not maintain a hand-written chipset list. Derive supported devices and
capabilities from Devourer's source and build system, and record what the
hardware actually demonstrates alongside what the source claims.

## Bench hardware

Owned by the user, physically attached, and the routine subject of this work.

| Device | USB ID | Kernel driver | Devourer backend |
|---|---|---|---|
| MediaTek MT7612U ×2 | `0e8d:7612` | `mt76x2u` | `src/mt7612u` (C, standalone) |
| Realtek RTL8812AU | `0bda:8812` | `rtw_8812au` | Jaguar1 |
| MediaTek MT7922 (internal) | `0e8d:0616` | — | **off limits** |

The internal MT7922 carries the host's own connectivity. Never claim, unbind
or retune it.

`tools/host/70-devourer-usb.rules` (installed to `/etc/udev/rules.d/`) grants
the bench adapters to the `plugdev` group, so the whole stack runs unprivileged
— **no `sudo` for normal radio work.** The rules blacklist nothing; the
adapters keep working as ordinary Wi-Fi interfaces until Devourer claims one
and detaches the kernel driver at open time.

Two MT7612U units means peer TX/RX and monitor-oracle experiments are possible
today. Everything else Devourer supports is `UNAVAILABLE`, not broken.

## MCP surface

Expose radio *concepts*, not a 1:1 wrapper over Devourer's functions. The
model is:

```text
DISCOVER → OBSERVE → INSPECT → TRANSMIT → EXPERIMENT → CHARACTERIZE → BUILD TOOL
```

Captured frames stay local and remain reachable by reference, query, summary
and export. Raw frame bytes must always be recoverable for deeper analysis —
summarizing is an optimization, never a lossy replacement.

## Capability gating

The model must not need hardcoded chipset knowledge. Adapters and backends
report their real capabilities (Devourer's `AdapterCaps` / `TxCaps` /
`TxPowerCaps` are the source of truth), and **every advanced operation is
capability-gated**. An operation an adapter cannot do fails with a clear
capability error, never with silent degradation or a plausible-looking fake
result. Experimental and unverified behaviour stays visibly distinct from
verified behaviour.

## Safety boundary

For owned and authorized hardware: development, diagnostics, FPV, embedded
networking, radio experimentation.

Three levels, always explicit:

- **`normal`** — structured, typed frame and PHY descriptions. The default.
- **`experimental`** — unverified or partially characterized paths. Loud.
- **`developer`** — the privileged low-level escape hatch for genuine
  development work. Never the normal interface, and never reachable by
  accident.

Arbitrary register access is not a normal interface. Enforce hardware limits
and regulatory configuration, bound every experiment, honour timeouts, cancel
cleanly, and always leave USB/radio state recoverable. **Do not build
disruptive attack presets** — no deauth floods, no jammer features, no mass
beacon spam.

Every TX path must be bounded by duration or packet count, and cancellable.

## Scratchpad runtime

When the fixed MCP surface cannot answer a request, the model composes a
temporary program from primitives (radio, capture, tx, http, tcp/udp, timers,
metrics, storage, math, UI).

This is **not** unrestricted code execution in the MCP process. Generated
programs declare the capabilities they need, are validated against that
declaration, and run in an isolated worker receiving only the APIs they asked
for. No ambient filesystem, no process execution, no native/JNI, no arbitrary
host access. A scratchpad that proves useful can be promoted to a saved,
reusable tool.

## Testing

The project must stay useful with no hardware attached. Mocks, replay of
captured data, and experiment fixtures carry CI. Real-hardware validation is
**separately identifiable** — never let a hardware-dependent test masquerade as
a unit test, and never let its absence look like a pass. When hardware is
available, capture evidence rather than asserting from assumption.

## Vendoring

`vendor/devourer` is a pinned copy, recorded in `vendor/DEVOURER_VERSION`.
Sync with `tools/host/vendor-devourer.sh`. Local changes to vendored code live
as patches in `vendor/patches/` so an upstream bump stays reviewable — never
as unrecorded edits in the tree.

The sibling checkouts `/home/snokvist/dev/devourer*` are the user's own
upstream working trees. **Never write to them.**

## Conventions

- Kotlin explicit API mode in library modules; `kotlinx.serialization` for all
  wire formats; `kotlinx.coroutines` with structured concurrency and real
  cancellation on every long-running radio operation.
- The bridge protocol is versioned. A protocol change touches
  `kotlin/protocol/` and `native/bridge/` together, and bumps the version.
- Errors say what the hardware did. "Failed" is not a diagnosis.
- Don't over-design ahead of the vertical slice: vendor → discover → capabilities
  → monitor → summarize/inspect through MCP, working end to end, before
  building TX, experiments, characterization and the scratchpad on top.

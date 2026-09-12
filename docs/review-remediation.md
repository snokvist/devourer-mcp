# Review remediation — status and continuation

Three adversarial reviews (architecture/goal-fidelity, native-bridge correctness,
sandbox/MCP security) ran on 2026-09-12 against commit `dad1773`. Every finding
below was independently verified against the source before being recorded here.

This file is the working plan. **Update the status column as items land** — it is
what a cold session reads to resume.

---

## Where this stands

21 of 25 items are done and verified offline (82 Kotlin tests, 63 native
selftests, `scripts/check-docs.sh` green). Four bridge items are **written and
compiling but unverified**, because proving them needs an open radio session:
they are the teardown-hang, the lifecycle race, the RX-thread exception guard
and the write-error fd leak. Three bridge fixes WERE verifiable without a radio
and were checked: argument range-rejection, the connection cap, and SIGTERM
actually stopping an idle bridge (which was impossible before).

Item 5 (frame-flow overflow policy) and item 18 (`phy_fill`) and item 14
(`Radios` interface) and item 19 (experiment seam) remain.

## Ground rule while this list is open

Items marked **[HW]** cannot be verified without the bench adapters. The bench is
shared with another session, so those are staged but NOT run. Everything else is
verifiable with `./gradlew test` and a CMake build.

---

## Status

| # | Item | Severity | Needs HW | Status |
|---|---|---|---|---|
| 1 | `SafetyLevel` exists and gates TX / CCA | critical | no (verify: yes) | **DONE** `SafetyLevel` + tests |
| 2 | `characterize_run` no longer auto-disables carrier sense | critical | no | **DONE** default now false, needs EXPERIMENTAL |
| 3 | Scratchpad grant comes from the caller, not the program | critical | no | **DONE** caller intersects; `grant_capabilities` |
| 4 | Generated UI escaped + CSP | critical | no | **DONE** escaped + CSP + locale fix |
| 5 | Frame flow: real overflow policy, never dies | critical | **yes** | TODO |
| 6 | `capture_summary` O(n)+Formatter storm | critical | no (fixture bench) | **DONE** window-in-lock, hex table, cached addrs, bench tests |
| 7 | Bridge: non-blocking sink, no join-on-blocked-write | critical | **yes** | **CODE DONE, [HW] to verify** non-blocking + shutdown() |
| 8 | Bridge: `Session` lifecycle mutex | critical | **yes** | **CODE DONE, [HW] to verify** `_life_mu`, idempotent close |
| 9 | Bridge: try/catch on RX thread + dispatch | critical | **yes** | **CODE DONE, [HW] to verify** RX thread + dispatch + dtor |
| 10 | `UiServer` closed on natural completion; run caps | high | no | **DONE** closed in finally; 8 concurrent / 16 retained |
| 11 | `Expr` depth cap on unary/power | high | no | **DONE** guard on all 3 paths + tests |
| 12 | Delete unimplemented capabilities + `txFrameBudget` | high | no | **DONE** deleted; test pins the catalogue |
| 13 | Clamp `capture_query` limit, `capacity`, `max_hex_bytes` | high | no | **DONE** query 500, hex 4096, capacity 2M |
| 14 | Extract `Radios` interface; fakes; tests for exp/char/mcp | high | no | TODO |
| 15 | Bridge: `sigaction` without `SA_RESTART`; wake `accept()` | high | **yes** | **DONE** sigaction + self-pipe; SIGTERM verified |
| 16 | Bridge: close sink fd on write error | high | **yes** | **CODE DONE, [HW] to verify** closes fd, clears buffer |
| 17 | Bridge: range-check `integer()` before narrowing | high | no | **DONE** `ranged()`; verified bus 257 / buffer -1 rejected |
| 18 | `phy_fill` — plumb it or delete it | high | **yes** (plumb) | TODO |
| 19 | Experiment engine seam: roles, sweeps, per-point timeout | high | no | TODO |
| 20 | Doc corrections + `scripts/check-docs.sh` | high | no | **DONE** `scripts/check-docs.sh` green |
| 21 | Regulatory stance: pick one, make docs match | medium | no | **DONE** documented as deliberately unenforced |
| 22 | Hand-built JSON in `Tools.kt` (5 sites, 2 injectable) | medium | no | **DONE** all 5 via `reply()`/`errorReply()` |
| 23 | `CaptureStore.frame(index)` O(n) → O(1) | medium | no | **DONE** index arithmetic + eviction test |
| 24 | Android claim corrected in roadmap | medium | no | **DONE** six blockers listed in roadmap |
| 25 | Bridge: TX budget bounds wall-clock, checks `g_stop` | medium | **yes** | **DONE** wall-clock budget + `g_stop` (code verified) |

---

## Verified evidence (do not re-derive)

**Safety boundary does not exist.** No `SafetyLevel`/regdomain/DFS type anywhere;
three prose comments only. `Characterizer.kt:49` `retryWithoutCarrierSense = true`,
fired at `:217` when delivery < 50%; `Tools.kt` builds `Characterizer.Options(...)`
**without passing it**, so a model cannot opt out. `docs/hardware-evidence.md`
claims "gated as experimental" — false.

**Frame flow dies, not backpressures.** `BridgeClient.kt:135`
`trySend(...).getOrThrow()`, no `.buffer()`. ~128 slots ≈ 39 ms at 3300 fps. The
comment at `:106` claims the opposite.

**`capture_summary` cost.** `CaptureStore.kt:71` `snapshot().filter{}` copies the
full ring (cap 200 000) before narrowing; `FrameControl.kt:98` formats up to 4 MACs
via `"%02x".format(...)` = a `java.util.Formatter` **per byte**, 24/frame. Called
per `CaptureMetricSource` per `everyMs` (default 500, floor 50) from
`McpScratchpadHost.kt:45`, and unwindowed by `antenna_check` (`Tools.kt:286`).

**Grant is self-asserted.** `Tools.kt:814` `capabilities = program.capabilities.toSet()`.
Every downstream check compares the program to itself. `SandboxTest` builds the
grant by hand, so it never exercises the production path.

**UI XSS.** `UiServer.kt:103` `quote()` escapes `"` `\` `\n` `\r` `\t` + controls —
not `<`/`>`. Five unescaped `innerHTML` sinks: `:185`, `:244`, `:263`, `:270`,
`:275`. Table is the default widget (`:181`), series names are unconstrained
(`Program.kt:38`). No CSP (`:54-60`).

**Bridge criticals.** No `O_NONBLOCK`/`fcntl` anywhere; `stop_writer()` joins a
thread that can be parked in `::write` (`Session.cpp:589`), and shutdown does that
under `g_mu`. `Session` has only `_buf_mu`/`_stats_mu` — no lifecycle lock, so
concurrent `close()` is UB on `join()` plus a double `_dev.reset()`, and
`send_frame` derefs `_radio` unlocked. `grep "try\|catch" main.cpp` → nothing;
`Mt7612uRadio::StartRxLoop` throws on three conditions. `std::signal` implies
`SA_RESTART` so `accept()` never sees `EINTR`. Write-error path increments a
counter but does not close the fd, despite the comment saying it drops the sink.
`budget_us = count * interval_us` is 0 when `interval_us` is 0.

**`phy_fill` dead both sides.** `Session.cpp:477` hardwires 0; zero `phyFill`
references in Kotlin; `FrameRecord.decode` skips offset 65. So "absent is not zero"
has no mechanism — everything falls back to `!= 0` heuristics.

**Unbounded MCP payload.** `Tools.kt:405` `limit = request.intOr("limit", 20)`,
unclamped; `FrameRow` ~300 B → ~60 MB in one tool result.

**Experiment engine.** Duration checked only at `LinkProbe.kt:99` (top of the
per-mode loop); `ExperimentBounds` allows `framesPerPoint` 100 000 ×
`intervalUs` 1 000 000 = 27 h for one point. `withTimeoutOrNull` imported at
`LinkProbe.kt:9`, used nowhere. `estimatedPointMs` (`Experiment.kt:38`) read
nowhere. Three of five `RadioRole`s unreferenced.

**Unimplemented-but-advertised.** `RADIO_TX` (privileged), `RADIO_MONITOR`,
`STORAGE` have no implementing step; `txFrameBudget` is never read.
`Interpreter.sample()` has exactly three arms.

**Measured numbers (docs were wrong).**

| Module | test files | `@Test` |
|---|---|---|
| protocol | 2 | 15 |
| radio | 0 | **0** |
| capture | 3 | 22 |
| experiment | 0 | **0** |
| characterize | 0 | **0** |
| scratchpad | 3 | 26 |
| mcp | 0 | **0** |
| **total** | 8 | **63** |

Docs said 53. IRadio: bridge calls **11** of **52** (docs said 9 of 52). No test
carries `@Tag("hardware")`, so the Gradle exclusion filters nothing. No PCAP
reader exists, so "captured-data replay" is absent.

**Why exp/char/mcp have no tests:** `RadioManager` is a concrete class over a
concrete `BridgeClient` that opens a real socket, so `LinkProbe(radios, scope)`
and `Characterizer(radios, evidence, scope)` cannot be built without hardware.
`ScratchpadHost` *is* an interface — and that module has 26 tests. Item 14 fixes
the asymmetry.

**Android claim is wrong on six counts** (roadmap says "the architecture holds"):
`UnixDomainSocketAddress` is Android **API 34**, not 28; `com.sun.net.httpserver`
does not exist there; `/tmp` and `XDG_RUNTIME_DIR` assumptions; sysfs and
`/proc/<pid>/comm` enumeration; the bridge is launched by a bash script with
`setsid`; no Android build exists on our side. Vendored Devourer *does* have an
Android fd-import path we ignore.

---

## Cleared by review (do not re-investigate)

- No execution primitive anywhere: `ProcessBuilder`/`exec`/`Class.forName`/
  `ScriptEngine` absent in Kotlin; `system`/`popen`/`exec*`/`fork` absent in C++.
- HTTP redirects off; no allowlist TOCTOU; URI parsing fail-closed on userinfo,
  trailing dot, case, unicode lookalikes.
- **No RF payload text reaches the model** — no SSID/IE decoding exists; frames
  are hex. Worth an explicit invariant + test so nobody adds it later.
- HTTP response bodies cannot reach the run log as text.
- `seq_offset` bounds proof holds for both TX paths.
- libusb teardown order correct on every early-return in `Session::open`.
- No lock-order inversion between `_buf_mu` and `_stats_mu` today.
- `ScratchpadService.save()` cannot traverse (`/` and `\` stripped).
- RX hot path itself is correct: bounded append under the lock, O(1) swap in the
  writer, `FrameRecord h{}` value-initialised so no stack garbage reaches the wire.

---

## Continuation

When resuming: read this file, then `docs/handoff.md`. Pick the top TODO whose
**Needs HW** is `no`. Before any **[HW]** item, ask — the bench is shared.

# Review remediation — status and continuation

Three adversarial reviews (architecture/goal-fidelity, native-bridge correctness,
sandbox/MCP security) ran on 2026-09-12 against commit `dad1773`. Every finding
below was independently verified against the source before being recorded here.

This file is the working plan. **Update the status column as items land** — it is
what a cold session reads to resume.

---

## Where this stands

**All 25 items are done.** The Kotlin suite then held 150 tests, 63 native selftests,
`scripts/check-docs.sh` green, and the four bridge fixes that could only be
proven with a radio are now proven on all three adapters.

The bridge items were the interesting ones. They were written against a
failure mode nobody had reproduced on purpose: a client that attaches to the
frame stream and then stops reading. `tools/stall-test.py` provokes it — a
1MiB frame buffer, a sink that never reads, and a bounded broadcast burst from
the peer adapter — and measures what the review said was broken.

| What | RTL8812AU | MT7612U bus5 | MT7612U bus2 |
|---|---|---|---|
| records dropped with the sink stalled | 38 554 | 35 695 | 38 496 |
| worst control-plane call during it | 0 ms | 0 ms | 0 ms |
| `monitor.stop` with the sink still stalled | 1 ms | 52 ms | 51 ms |
| `radio.close` | 4 ms | 49 ms | 26 ms |
| sink reset → dropped, buffer cleared | 1 MiB → 0 | 1 MiB → 0 | 1 MiB → 0 |
| still receiving afterwards | 5 987 frames | 5 368 | 6 000 |

Both MT7612U units survived this. That is the specific adapter Devourer warns
wedges below the USB level when its receiver is not drained, and the one this
project has already wedged once with its own backpressure.

Item 5 is verified separately by `tools/backpressure-test.py`, which runs a
real capture under a burst far larger than the ring: **111 468 frames admitted
at ~6 100 frames/s, collector never dying, zero bridge drops.** The old code
would have ended the stream within the first second. That rate is also roughly
double the previously recorded peak, because the reader no longer throws away
its own flow under load.

## Ground rule while this list is open

Nothing is left staged. Items marked **[HW]** were verified on the bench
adapters on 2026-09-12; the scripts that did it are checked in and repeatable.

---

## Status

| # | Item | Severity | Needs HW | Status |
|---|---|---|---|---|
| 1 | `SafetyLevel` exists and gates TX / CCA | critical | no | **DONE** `SafetyLevel` + tests |
| 2 | `characterize_run` no longer auto-disables carrier sense | critical | no | **DONE** default false, needs EXPERIMENTAL, pinned by `CharacterizerTest` |
| 3 | Scratchpad grant comes from the caller, not the program | critical | no | **DONE** caller intersects; `grant_capabilities` |
| 4 | Generated UI escaped + CSP | critical | no | **DONE** escaped + CSP + locale fix |
| 5 | Frame flow: real overflow policy, never dies | critical | **yes** | **DONE + VERIFIED** `send` not `trySend`, 2048-slot buffer; 111k frames at 6.1k/s, collector alive |
| 6 | `capture_summary` O(n)+Formatter storm | critical | no | **DONE** window-in-lock, hex table, cached addrs, bench tests |
| 7 | Bridge: non-blocking sink, no join-on-blocked-write | critical | **yes** | **DONE + VERIFIED** `monitor.stop` 1–52ms with the sink stalled |
| 8 | Bridge: `Session` lifecycle mutex | critical | **yes** | **DONE + VERIFIED** `radio.close` 4–49ms mid-stall, three adapters |
| 9 | Bridge: try/catch on RX thread + dispatch | critical | **yes** | **DONE + VERIFIED** control plane 0ms worst through 38k drops |
| 10 | `UiServer` closed on natural completion; run caps | high | no | **DONE** closed in finally; 8 concurrent / 16 retained |
| 11 | `Expr` depth cap on unary/power | high | no | **DONE** guard on all 3 paths + tests |
| 12 | Delete unimplemented capabilities + `txFrameBudget` | high | no | **DONE** deleted; test pins the catalogue |
| 13 | Clamp `capture_query` limit, `capacity`, `max_hex_bytes` | high | no | **DONE** query 500, hex 4096, capacity 2M |
| 14 | Extract `Radios` interface; fakes; tests for exp/char/mcp | high | no | **DONE** `Radios` + `FakeRadios`; 0 → 55 tests across those three modules |
| 15 | Bridge: `sigaction` without `SA_RESTART`; wake `accept()` | high | **yes** | **DONE** sigaction + self-pipe; SIGTERM verified |
| 16 | Bridge: close sink fd on write error | high | **yes** | **DONE + VERIFIED** RST → `write_errors` 0→1, buffer 1MiB→0, sink detached |
| 17 | Bridge: range-check `integer()` before narrowing | high | no | **DONE** `ranged()`; bus 257 / buffer -1 rejected |
| 18 | `phy_fill` — plumb it or delete it | high | **yes** (plumb) | **DONE** retired to a reserved byte: it shipped hardwired to "nothing filled" and cannot be filled from the bridge — see `Protocol.h` |
| 19 | Experiment engine seam: roles, sweeps, per-point timeout | high | no | **DONE** 4-axis `Sweep`, `Map<RadioRole,Int>`, per-point deadline, `ExperimentRunner` + cancel |
| 20 | Doc corrections + `scripts/check-docs.sh` | high | no | **DONE** green |
| 21 | Regulatory stance: pick one, make docs match | medium | no | **DONE** documented as deliberately unenforced |
| 22 | Hand-built JSON in `Tools.kt` (5 sites, 2 injectable) | medium | no | **DONE** all 5 via `reply()`/`errorReply()` |
| 23 | `CaptureStore.frame(index)` O(n) → O(1) | medium | no | **DONE** index arithmetic + eviction test |
| 24 | Android claim corrected in roadmap | medium | no | **DONE** six blockers listed in roadmap |
| 25 | Bridge: TX budget bounds wall-clock, checks `g_stop` | medium | **yes** | **DONE** wall-clock budget + `g_stop`; exercised by every flood in the stall test |

### Found while fixing these

Six defects found after the reviews, none of which were in them. The last
three were found by *using* the instrument — two by the dashboard, one by
asking it a question it had not been asked before:

- **A collector could outlive its run.** `LinkProbe` cancelled its frame
  collectors without joining them, so the previous run's sink was still
  attached during the next one. It surfaced as a characterization retry
  measuring zero delivery. Now `cancelAndJoin`, under `NonCancellable`.
- **`FakeRadios` was not thread-safe.** `getOrPut` is get-then-put and is not
  atomic even on a `ConcurrentHashMap`; two racing callers each built a frame
  flow, the second overwrote the first, and a collector sat subscribed to an
  object nothing published to. It passed alone and hung in a suite.
- **The dashboard showed a monitoring radio as idle.** The radio book was
  updated only on open and describe, so the three facts anyone looks for —
  monitoring, channel, carrier sense — were the three that were stale.
- **A frame collector could not be cancelled at all**, which hung an
  experiment for ten minutes with the radio still claimed. A thread blocked
  in `SocketChannel.read` is not interruptible by coroutine cancellation, and
  `callbackFlow`'s `awaitClose` sat after a `while(true)` loop that never
  reached it — so on a quiet channel nothing was registered and nothing could
  stop it. The read loop is a child coroutine now. This was a regression
  introduced by the `cancelAndJoin` fix above, and the dashboard's experiment
  panel is what showed it: a run sitting at 1/1 points, RUNNING, for ten
  minutes.
- **A tool error read as `{` in the activity feed.** Error bodies are
  pretty-printed JSON, so their first line is a brace. The feed now pulls the
  `error` field out. Until it did, a real failure — `monitor.start` refusing
  because the session was already monitoring — was invisible while a script
  swallowed it.
- **An experiment failed because a capture had been left running** on one of
  its witnesses. The bridge refuses `monitor.start` on a session already
  monitoring; the probe only stopped monitors between points, not before the
  first. A failure that depends on what happened before the run is the kind
  that only appears when it matters.
- **A timed-out point reported `delivery_ratio: 0.0`.** That reads as "heard
  nothing" and would have put a measured-looking zero on a chart where there
  was no measurement. `frames_received` and `delivery_ratio` are nullable
  now, and serialization drops them — a point that was never measured carries
  neither field. Found by charting a real sweep and looking at the result.

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

This list is closed. What comes next is in [`roadmap.md`](roadmap.md); the
largest single item there is still `IRadio` coverage, at 20 of 55 methods.

Three things worth keeping from how this went:

- **The bench is shared.** Ask before using the adapters, and prefer ch6 for
  anything that transmits — it is measurably empty here.
- **Provoke the failure, do not wait for it.** Every one of the four bridge
  fixes looked verified when the test relied on ambient traffic: the buffer
  never filled, so teardown was only ever measured against an idle sink. The
  test now generates its own load and fails if the buffer did not saturate.
- **A test that passes alone and hangs in a suite is a concurrency bug in the
  test harness, not a flake.** It was, twice.

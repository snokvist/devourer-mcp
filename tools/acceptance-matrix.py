#!/usr/bin/env python3
"""The demo-vs-MCP acceptance matrix: rxdemo/txdemo against the MCP surface.

This is the artifact the parity gate names (`docs/rxdemo-txdemo-parity.md`,
"The acceptance test"): for each capability the same measurement is run through
the demo and through MCP on the same bench, and compared. Where the demo cannot
measure it at all (anything needing an independent witness), MCP must be
strictly better, and that is recorded rather than compared.

What it runs live, with three adapters (transmitter T, demo receiver A, MCP
receiver B):

  RX plane   One txdemo burst; rxdemo counts `rx.txhit`, an MCP monitor on a
             second radio counts the same frames. The SAME receiver A is used
             for both paths so the comparison measures the paths, not two
             radios; each arm is repeated and the best count is compared.
  TX plane   A txdemo burst, then an experiment_link_probe burst with the same
             TX mode. The SAME monitor B decodes both and the two counts are
             compared, so the demo and MCP arms are seen by one receiver. The
             MCP arm additionally has to be independently TX_VERIFIED with
             delivery >= 0.8 and the same decoded rate.

Counts, not submitted/received ratios, are compared: txdemo reports a constant
~50 non-test submissions at bring-up on top of DEVOURER_TX_FRAMES (measured:
limit N -> submitted N+50 for N=50,100,200,500), so its `submitted` is not a
frame-for-frame denominator. The monitor's count of the test frames is the
air-visible measure, and it is the same predicate for both arms.

Both arms are made like-for-like: the demo sends a QoS Data frame padded to
`--frame-bytes` (DEVOURER_TX_QOS_DATA + DEVOURER_TX_PAYLOAD_BYTES) and the MCP
probe is asked for the same `frame_bytes`, so neither arm is compared at a
different MPDU size. Each arm is repeated and compared best-of because a single
burst on this bench swings by tens of frames at high MCS; the tolerance
(max 15% or 25 frames) is the gate's accepted band, not a statistical claim.
Each arm may be retried up to 2x its repetition count for a transient bring-up
failure, and every failed attempt is printed. Each MCP capture's
`monitor_status` is recorded and a nonzero ring-evict or bridge-drop fails the
row, so a low count cannot hide behind an incomplete window.

Each row is repeated per mode. The demo paths are the reference; a demo path
that cannot be run here is reported, never silently passed. The delegated
per-feature checks and the MCP-strictly-better rows are listed at the end.

    tools/acceptance-matrix.py [--channel 6] [--frames 200] [--frame-bytes 200]

It NEVER passes vacuously: no adapters, a missing demo binary, an empty mode
list, a non-jaguar3 transmitter, a bad frame size, or a row that cannot be
measured is a failure. Every session and capture is cleaned up.
"""

import argparse
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

DEMO_DIR = os.path.join(ROOT, "build", "native-bridge", "devourer")
CANONICAL_SA = "57:42:75:05:d6:00"
DVRX_MAGIC = "44565258"
# kotlin/mcp Tools.kt MAX_QUERY_ROWS: capture_query clamps to 500.
CAPTURE_LIMIT = 500

RESULTS = []


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def note(msg):
    print(f"  \033[33mNOTE\033[0m {msg}")


def check(row, good, detail):
    RESULTS.append((row, bool(good), detail))
    (ok if good else bad)(f"{row}: {detail}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def demo_path(name):
    p = os.path.join(DEMO_DIR, name)
    return p if os.access(p, os.X_OK) else None


def demo_env(bus, usb_id, extra):
    """A whitelisted environment.

    The demos default to Realtek VID 0x0bda, so a MediaTek MT7612U must be named
    explicitly or the open loop matches no device. Only the variables a demo
    needs are passed: inheriting the parent environment would let an exported
    DEVOURER_TX_BATCH / DEVOURER_SKIP_RESET / DEVOURER_RX_* silently change the
    demo arm and break the comparison.
    """
    vid, pid = usb_id.split(":")
    base = {k: os.environ[k] for k in
            ("PATH", "HOME", "LANG", "LC_ALL", "LD_LIBRARY_PATH") if k in os.environ}
    return {**base, "DEVOURER_USB_BUS": str(bus), "DEVOURER_EVENTS": "stdout",
            "DEVOURER_LOG_LEVEL": "error", "DEVOURER_VID": f"0x{vid}",
            "DEVOURER_PID": f"0x{pid}", **extra}


def demo_tx_env(usb_id, bus, channel, mode, frames, frame_bytes):
    """The demo transmitter's environment, matched to the MCP arm's stimulus.

    DEVOURER_TX_QOS_DATA=1 makes it a data-plane frame (QoS Data, broadcast)
    rather than the default management probe request, and
    DEVOURER_TX_PAYLOAD_BYTES pads the PSDU to exactly `frame_bytes` - the same
    size experiment_link_probe sends - so the two arms are like-for-like.
    """
    return demo_env(bus, usb_id, {
        "DEVOURER_CHANNEL": str(channel), "DEVOURER_TX_RATE": mode,
        "DEVOURER_TX_FRAMES": str(frames), "DEVOURER_TX_GAP_US": "2000",
        "DEVOURER_TX_QOS_DATA": "1", "DEVOURER_TX_PAYLOAD_BYTES": str(frame_bytes),
    })


def run_bounded(argv, env, timeout):
    """Run a demo that exits on its own (txdemo with a frame bound)."""
    p = subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        out, err = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.terminate()
        out, err = p.communicate(timeout=15)
    return p.returncode, out or "", err or ""


def start_rx(argv, env):
    return subprocess.Popen(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)


def stop_rx(p):
    if p.poll() is None:
        p.terminate()
        try:
            out, err = p.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            p.kill()
            out, err = p.communicate()
    else:
        out, err = p.communicate()
    return out or "", err or ""


def rx_hits(text):
    """rxdemo's rx.txhit is rate-limited (first 10, then every 100th), so the
    frame count is the `hits` field of the last one, not the event count."""
    hits = 0
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if ev.get("ev") == "rx.txhit":
            hits = max(hits, int(ev.get("hits") or 0))
    return hits


def demo_submitted(text):
    """txdemo logs tx.frame only for the first few frames; the count that
    matters is the final tx.stats `submitted`. It includes ~50 bring-up
    submissions, so it is reported for context, never used as a denominator."""
    total = 0
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if ev.get("ev") == "tx.stats":
            total = max(total, int(ev.get("submitted") or 0))
    return total


def open_radio(c, d):
    r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
    if r.get("_isError") or "session" not in r:
        return None
    return {"session": r["session"], "chip": r["capabilities"].get("chip", "?"),
            "generation": r["capabilities"].get("generation", "?"), "dev": d}


def capture_frames(c, capture_id):
    reply = c.tool("capture_query", {"capture_id": capture_id, "limit": CAPTURE_LIMIT})
    return reply if isinstance(reply, list) else reply.get("frames", [])


def capture_health(c, capture_id):
    """monitor_status before the capture is discarded.

    A nonzero `evicted_from_ring` or `bridge_dropped` means the window is
    incomplete, so a low count could be the capture's fault rather than the
    path's. `frames_admitted` is the air-visible total the monitor saw.
    """
    reply = c.tool("monitor_status", {"capture_id": capture_id})
    if isinstance(reply, dict):
        if errored(reply):
            return None
        row = reply
    elif isinstance(reply, list) and reply and isinstance(reply[0], dict):
        row = reply[0]
    else:
        return None
    return {
        "stored": row.get("frames_stored"),
        "admitted": row.get("frames_admitted"),
        "evicted": row.get("evicted_from_ring") or 0,
        "dropped": row.get("bridge_dropped") or 0,
    }


def health_ok(health):
    return health is not None and health.get("evicted", 0) == 0 and health.get("dropped", 0) == 0


def count_canonical(c, capture_id):
    """The test frames a demo burst leaves: txdemo's canonical source address.

    This is the demo arm's monitor-side count, and it is also what the MCP arm
    of the RX plane counts (it captures the demo's burst too).
    """
    rows = capture_frames(c, capture_id)
    hits = [r for r in rows
            if not r.get("crc_error")
            and (r.get("transmitter") or "").lower() == CANONICAL_SA]
    rate = None
    if hits:
        detail = c.tool("frame_inspect", {"capture_id": capture_id, "index": hits[0]["index"]})
        rate = detail.get("rate_code")
    return len(hits), rate


def count_probe(c, capture_id):
    """The MCP arm's monitor-side count: ProbeFrame data frames by magic.

    `ProbeFrame.sequenceOf` verifies source address + magic + run id before
    counting at the receiver; the experiment result's own `TX_VERIFIED` carries
    that check (the peer helper reads it below). Here the magic alone is enough
    to separate our probes from ambient data frames on the monitor.
    """
    seen = set()
    hits = 0
    rate = None
    for row in capture_frames(c, capture_id):
        idx = row.get("index")
        if idx is None or idx in seen:
            continue
        seen.add(idx)
        if not str(row.get("kind", "")).startswith("data") or row.get("crc_error"):
            continue
        detail = c.tool("frame_inspect", {"capture_id": capture_id, "index": idx})
        if errored(detail):
            continue
        if DVRX_MAGIC in (detail.get("raw_hex") or "").lower():
            hits += 1
            if rate is None:
                rate = detail.get("rate_code")
    return hits, rate


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--frames", type=int, default=200)
    ap.add_argument("--frame-bytes", type=int, default=200,
                    help="PSDU bytes for BOTH arms (90..400). txdemo pads its QoS Data PSDU "
                         "to exactly this and the MCP probe is given the same frame_bytes and "
                         "qos_tid 0, so both arms air the same-size, same-shape QoS Data frame; "
                         "comparing a 100-byte management frame against a 200-byte data frame "
                         "would confound the count.")
    ap.add_argument("--modes", default="6M,MCS7/20")
    ap.add_argument("--rx-reps", type=int, default=2,
                    help="Bursts per RX arm; high-MCS delivery swings run to run.")
    ap.add_argument("--tx-reps", type=int, default=2,
                    help="Bursts per TX arm; a single delivery probe is worth a few points.")
    ap.add_argument("--timeout", type=int, default=90)
    args = ap.parse_args()

    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    if not modes:
        print("--modes is empty; a matrix that runs no modes would pass vacuously.")
        return 2
    if args.frames < 1 or args.frames > 300:
        print(f"--frames must be 1..300 (got {args.frames}); larger bursts can outrun "
              f"the {CAPTURE_LIMIT}-row capture query before it is read.")
        return 2
    if args.frame_bytes < 90 or args.frame_bytes > 400:
        print(f"--frame-bytes must be 90..400 (got {args.frame_bytes}); 90 is "
              f"txdemo's QoS Data body floor and DEVOURER_TX_PAYLOAD_BYTES only pads up, "
              f"so a smaller value would air a 90-byte demo frame against a smaller probe.")
        return 2
    if args.rx_reps < 1 or args.tx_reps < 1:
        print("--rx-reps and --tx-reps must be >= 1.")
        return 2

    rxdemo = demo_path("rxdemo")
    txdemo = demo_path("txdemo")
    if rxdemo is None or txdemo is None:
        print(f"rxdemo/txdemo not built under {DEMO_DIR}. Build the native tree first.")
        return 2

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 3:
            print(f"Need three adapters (transmitter + demo receiver + MCP receiver); "
                  f"found {len(devices)}. This matrix needs hardware.")
            return 2

        # TX prefers jaguar3 (multi-run safe); the two receivers are the rest.
        def pref(d):
            return 0 if d.get("usb_id") == "0bda:c812" else 1

        ordered = sorted(devices, key=pref)
        T, A, B = ordered[0], ordered[1], ordered[2]
        print(f"  transmitter T: {T['usb_id']} bus{T['bus']}")
        print(f"  demo RX     A: {A['usb_id']} bus{A['bus']}   (rxdemo)")
        print(f"  MCP RX      B: {B['usb_id']} bus{B['bus']}   (monitor/witness)")

        # The TX plane reuses T's session for several link probes; a jaguar2
        # wedges on the second run (hardware-evidence.md, the OPEN finding), so
        # a jaguar3 transmitter is a precondition, not a preference.
        probe_t = open_radio(c, T)
        if probe_t is None:
            print(f"Could not open the transmitter {T['usb_id']} to check its generation.")
            return 1
        t_gen = probe_t["generation"]
        c.tool("radio_close", {"session": probe_t["session"]})
        if "jaguar3" not in t_gen:
            print(f"The transmitter {T['usb_id']} is {t_gen}, not jaguar3. The TX plane "
                  f"needs a multi-run-safe transmitter (see hardware-evidence.md).")
            return 2

        live_sessions = set()

        def close_all(s):
            for o in (s or {}).values():
                c.tool("radio_close", {"session": o["session"]})
                if o["session"] in live_sessions:
                    live_sessions.discard(o["session"])

        def collect(fn, reps, label):
            """Run an arm until `reps` results or 2*reps attempts.

            An open or bring-up can fail transiently on this bench (one
            `mcp_tx_burst` mode returned nothing in trial 6 and worked on the
            next run). Every failed attempt and its reason is printed, so a
            retry is visible rather than absorbed.
            """
            out = []
            attempts = 0
            for _ in range(reps * 2):
                attempts += 1
                r = fn()
                if r is not None:
                    out.append(r)
                    if len(out) >= reps:
                        break
                else:
                    print(f"       ({label}: attempt {attempts} produced no result)")
            return out, attempts

        try:
            # ---- RX plane: same receiver, demo path then MCP path ----------
            # Comparing two different radios would measure the receivers, not
            # the RX paths, so the same radio decodes the burst under rxdemo
            # and under an MCP capture. MCS7 delivery on this bench swings tens
            # of points between bursts (the receiver's modulation cliff, not
            # the path), so each arm is repeated and compared best-of and the
            # verdict is "MCP not materially worse", not exact agreement.
            def demo_rx_burst(mode):
                rx = start_rx([rxdemo], demo_env(
                    A["bus"], A["usb_id"], {"DEVOURER_CHANNEL": str(args.channel)}))
                time.sleep(3.5)  # rxdemo bring-up + RX loop
                rc, out, err = run_bounded(
                    [txdemo], demo_tx_env(T["usb_id"], T["bus"], args.channel, mode,
                                          args.frames, args.frame_bytes), args.timeout)
                time.sleep(1.5)
                rx_out, rx_err = stop_rx(rx)
                hits = rx_hits(rx_out)
                if hits <= 0:
                    print(f"       (RX demo arm [{mode}]: rxdemo heard nothing; "
                          f"stderr {rx_err.strip()[-120:]})")
                    return None
                return {"submitted": demo_submitted(out), "hits": hits, "err": rx_err}

            def mcp_rx_burst(mode):
                oa = open_radio(c, A)
                if oa is None:
                    return None
                live_sessions.add(oa["session"])
                mstart = c.tool("monitor_start", {"session": oa["session"],
                                                  "channel": args.channel, "width_mhz": 20},
                                timeout=180)
                if errored(mstart):
                    c.tool("radio_close", {"session": oa["session"]})
                    live_sessions.discard(oa["session"])
                    return None
                cap = mstart["capture_id"]
                try:
                    run_bounded(
                        [txdemo], demo_tx_env(T["usb_id"], T["bus"], args.channel, mode,
                                              args.frames, args.frame_bytes), args.timeout)
                    time.sleep(1.0)
                    hits, rate = count_canonical(c, cap)
                    health = capture_health(c, cap)
                    if hits <= 0:
                        print(f"       (RX MCP arm [{mode}]: monitor heard nothing; "
                              f"admitted {(health or {}).get('admitted')})")
                        return None
                    return {"hits": hits, "rate": rate, "health": health}
                finally:
                    c.tool("monitor_stop", {"capture_id": cap, "discard": True})
                    c.tool("radio_close", {"session": oa["session"]})
                    live_sessions.discard(oa["session"])

            for mode in modes:
                row = f"RX plane [{mode}]"
                demo_reps, demo_attempts = collect(lambda: demo_rx_burst(mode), args.rx_reps, row)
                mcp_reps, mcp_attempts = collect(lambda: mcp_rx_burst(mode), args.rx_reps, row)
                if not demo_reps or not mcp_reps:
                    check(row, False, f"an RX arm could not run (demo attempts {demo_attempts}, "
                          f"MCP attempts {mcp_attempts})")
                    continue
                demo_best = max(demo_reps, key=lambda r: r["hits"])
                mcp_best = max(mcp_reps, key=lambda r: r["hits"])
                demo_hits = demo_best["hits"]
                mcp_hits = mcp_best["hits"]
                h = mcp_best["health"]
                tolerance = max(0.15 * demo_hits, 25)
                ceiling = args.frames + max(5, 0.05 * args.frames)
                print(f"       {row}: rxdemo heard {[r['hits'] for r in demo_reps]}; "
                      f"MCP capture heard {[r['hits'] for r in mcp_reps]} "
                      f"(rate_code {mcp_best['rate']}, admitted {(h or {}).get('admitted')}, "
                      f"evicted {(h or {}).get('evicted')}, bridge_dropped {(h or {}).get('dropped')})")
                goods = (demo_hits > 0 and mcp_hits > 0
                         and mcp_hits >= demo_hits - tolerance
                         and mcp_hits <= ceiling
                         and health_ok(h))
                check(row, goods,
                      f"same radio {A['usb_id']}: demo best {demo_hits} vs MCP best "
                      f"{mcp_hits} (MCP must not trail by more than {tolerance:.0f} or "
                      f"exceed {ceiling:.0f}; capture evicted {(h or {}).get('evicted')}, "
                      f"dropped {(h or {}).get('dropped')})")

            # ---- TX plane: same monitor B, demo then MCP -------------------
            # Both arms are measured by monitor B and compared by the count it
            # decoded, so the demo's `submitted` (which over-counts bring-up
            # traffic) never enters the verdict. The MCP arm's own peer witness
            # supplies the independent delivery check.
            def demo_tx_burst(mode):
                ob = open_radio(c, B)
                if ob is None:
                    print(f"       ({mode} demo TX arm: receiver B did not open)")
                    return None
                live_sessions.add(ob["session"])
                cap = None
                try:
                    started = c.tool("monitor_start", {"session": ob["session"],
                                                       "channel": args.channel, "width_mhz": 20},
                                     timeout=180)
                    if errored(started):
                        print(f"       ({mode} demo TX arm: monitor_start failed: "
                              f"{str(started.get('_text', started))[:140]})")
                        return None
                    cap = started["capture_id"]
                    rc, out, _ = run_bounded(
                        [txdemo], demo_tx_env(T["usb_id"], T["bus"], args.channel, mode,
                                              args.frames, args.frame_bytes), args.timeout)
                    time.sleep(1.0)
                    hits, rate = count_canonical(c, cap)
                    health = capture_health(c, cap)
                    if rc != 0:
                        print(f"       ({mode} demo TX arm: txdemo exited {rc})")
                    if not hits:
                        return None
                    return {"submitted": demo_submitted(out), "hits": hits, "rate": rate,
                            "health": health}
                finally:
                    if cap is not None:
                        c.tool("monitor_stop", {"capture_id": cap, "discard": True})
                    c.tool("radio_close", {"session": ob["session"]})
                    live_sessions.discard(ob["session"])

            def mcp_tx_burst(mode):
                s = {}
                missing = None
                for name, d in (("T", T), ("A", A), ("B", B)):
                    o = open_radio(c, d)
                    if o is None:
                        missing = name
                        break
                    s[name] = o
                    live_sessions.add(o["session"])
                if missing is not None:
                    print(f"       ({mode} MCP TX arm: {missing} did not open)")
                    close_all(s)
                    return None
                mcap = None
                try:
                    mstart = c.tool("monitor_start", {"session": s["B"]["session"],
                                                      "channel": args.channel, "width_mhz": 20},
                                    timeout=180)
                    if errored(mstart):
                        print(f"       ({mode} MCP TX arm: monitor_start failed: "
                              f"{str(mstart.get('_text', mstart))[:140]})")
                        return None
                    mcap = mstart["capture_id"]
                    res = c.tool("experiment_link_probe", {
                        "tx_session": s["T"]["session"], "rx_session": s["A"]["session"],
                        "channel": args.channel, "width_mhz": 20, "modes": [mode],
                        "frames_per_point": args.frames, "frame_bytes": args.frame_bytes,
                        "qos_tid": 0,
                        "interval_us": 2000,
                        "max_duration_ms": max(15000, args.frames * 6),
                    }, timeout=args.timeout + 60)
                    if errored(res):
                        print(f"       ({mode} MCP TX arm: link_probe failed: "
                              f"{str(res.get('_text', res))[:140]})")
                        return None
                    hits, rate = count_probe(c, mcap)
                    health = capture_health(c, mcap)
                    delivery = None
                    if res.get("points"):
                        delivery = res["points"][0].get("delivery_ratio")
                    if not hits:
                        print(f"       ({mode} MCP TX arm: monitor heard no DVRX probe; "
                              f"admitted {(health or {}).get('admitted')})")
                        return None
                    return {
                        "hits": hits,
                        "rate": rate,
                        "delivery": delivery,
                        "verified": res.get("verification") == "TX_VERIFIED",
                        "health": health,
                    }
                finally:
                    if mcap is not None:
                        c.tool("monitor_stop", {"capture_id": mcap, "discard": True})
                    close_all(s)

            for mode in modes:
                row = f"TX plane [{mode}]"
                demo_reps, demo_attempts = collect(lambda: demo_tx_burst(mode), args.tx_reps, row)
                mcp_reps, mcp_attempts = collect(lambda: mcp_tx_burst(mode), args.tx_reps, row)
                if not demo_reps or not mcp_reps:
                    check(row, False,
                          f"a TX arm produced no result (demo attempts {demo_attempts}, "
                          f"MCP attempts {mcp_attempts})")
                    continue

                demo_best = max(demo_reps, key=lambda r: r["hits"])
                best_mcp = max(mcp_reps, key=lambda r: r["hits"])
                demo_hits = demo_best["hits"]
                mcp_hits = best_mcp["hits"]
                same_rate = demo_best["rate"] is not None and demo_best["rate"] == best_mcp["rate"]
                tolerance = max(0.15 * demo_hits, 25)
                ceiling = args.frames + max(5, 0.05 * args.frames)
                h = best_mcp["health"]
                print(f"       {row}: monitor B saw demo "
                      f"{[(r['hits'], r['submitted']) for r in demo_reps]}; "
                      f"MCP {[r['hits'] for r in mcp_reps]} "
                      f"(peer delivery {best_mcp['delivery']}, "
                      f"rate {demo_best['rate']}=={best_mcp['rate']}, "
                      f"admitted {(h or {}).get('admitted')}, evicted {(h or {}).get('evicted')}, "
                      f"bridge_dropped {(h or {}).get('dropped')})")
                goods = (demo_hits > 0 and mcp_hits > 0
                         and mcp_hits >= demo_hits - tolerance
                         and demo_hits <= ceiling and mcp_hits <= ceiling
                         and health_ok(h)
                         and best_mcp["verified"]
                         and (best_mcp["delivery"] or 0) >= 0.8
                         and same_rate)
                check(row, goods,
                      f"same monitor {B['usb_id']}: demo saw {demo_hits}, MCP saw {mcp_hits} "
                      f"(MCP must not trail by more than {tolerance:.0f} or exceed "
                      f"{ceiling:.0f}); peer {A['usb_id']} delivery {best_mcp['delivery']}, "
                      f"rate {demo_best['rate']}=={best_mcp['rate']}, "
                      f"TX_VERIFIED {best_mcp['verified']}, capture evicted "
                      f"{(h or {}).get('evicted')}, dropped {(h or {}).get('dropped')}")
        finally:
            for sid in list(live_sessions):
                c.tool("radio_close", {"session": sid})

    # ---- strictly better than a demo (no comparison possible) -------------
    # Printed as the gate's second half. Only TX_VERIFIED is actually asserted
    # by the rows above; the rest are properties the MCP surface has and the
    # demos structurally cannot, listed so the claim is explicit.
    print("\n  MCP only (a demo has no equivalent measurement; printed, not asserted):")
    for name, why in (
        ("independent witness / TX_VERIFIED", "asserted in the TX rows: the demo reports submission, not what reached the air"),
        ("multi-witness localisation", "two receivers at once; a demo runs one"),
        ("persistent capture/query/PCAP", "a demo streams to stdout and forgets"),
        ("capability gating + refusal", "a demo has no capability model"),
        ("experiment engine + cancellation", "bounded, cancellable, structured"),
    ):
        note(f"{name}: {why}")

    print("\n" + "=" * 60)
    if not RESULTS:
        print("MATRIX FAILED: no rows ran — nothing was measured.")
        return 1
    failed = [r for r in RESULTS if not r[1]]
    if failed:
        print(f"MATRIX FAILED ({len(failed)}): " + "; ".join(r[0] for r in failed))
        return 1
    print("Acceptance matrix met: the MCP RX/TX paths are not materially worse "
          "than the demos, and the MCP TX path is independently TX_VERIFIED.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

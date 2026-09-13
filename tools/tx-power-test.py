#!/usr/bin/env python3
"""Hardware test for the runtime TX-power knobs, through the real MCP server.

Checks the read/set/refuse behaviour of `radio_tx_power`, then does the thing
that makes the knob worth having: changes it and watches an INDEPENDENT
receiver's RSSI move. A radio reporting that its own TXAGC index changed is not
evidence that anything reached the air for a different amount of power.

The sweep is relative, not absolute. On most families `step_measured` is false,
so the dB-per-step slope is vendor documentation rather than a measurement; the
test asserts direction, not a calibrated dB figure, and prints the raw RSSI so
the shape speaks for itself.

    tools/tx-power-test.py [--channel 6] [--width 20] [--frames 300]

It NEVER passes vacuously: no tx-power-capable transmitter or no independent
receiver means failure, and every knob it moves is restored before it exits.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--width", type=int, default=20)
    ap.add_argument("--frames", type=int, default=300)
    args = ap.parse_args()

    failures = []
    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 2:
            print("Need a transmitter and an independent receiver. Found "
                  f"{len(devices)} adapter(s). This test needs hardware.")
            return 2

        opened = []
        for d in devices:
            r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
            if r.get("_isError") or "session" not in r:
                bad(f"open {d['usb_id']} bus{d['bus']}: {r.get('_text', r)}")
                continue
            power = c.tool("radio_tx_power", {"session": r["session"]})
            print(
                f"  {r['capabilities']['chip']} {r['capabilities']['generation']} "
                f"session={r['session']} tx_power.supported={power.get('supported')}"
            )
            print(f"       {json.dumps(power)}")
            opened.append(
                {
                    "session": r["session"],
                    "chip": r["capabilities"]["chip"],
                    "generation": r["capabilities"]["generation"],
                    "power": power,
                }
            )

        capable = [o for o in opened if o["power"].get("supported")]
        unsupported = [o for o in opened if not o["power"].get("supported")]
        # Prefer a family whose dB-per-step slope is measured, then the widest
        # index range: that is where a sweep can actually say something.
        capable.sort(
            key=lambda o: (o["power"].get("step_measured", False), o["power"].get("index_max", 0)),
            reverse=True,
        )
        txo = capable[0] if capable else None
        rxo = next((o for o in opened if txo and o["session"] != txo["session"]), None)

        try:
            if txo is None:
                bad("no adapter reports the TX-power knobs — nothing was verified")
                failures.append("no tx-power-capable transmitter")
                return _finish(failures)
            if rxo is None:
                bad("no independent receiver available")
                failures.append("no independent receiver")
                return _finish(failures)

            print(f"\n  transmitter: {txo['chip']} session {txo['session']}")
            print(f"  witness:     {rxo['chip']} session {rxo['session']}\n")
            failures += check_knobs(c, txo)
            if txo["power"].get("rate_diffs"):
                failures += check_rate_diffs(c, txo, rxo, args)
            else:
                print("       (this family reports rate_diffs:false; per-rate table skipped)")
            if unsupported:
                failures += check_unsupported(c, unsupported)
            else:
                print("       every adapter here wires TX power; the unsupported "
                      "path is not exercisable on this bench")
            failures += sweep(c, txo, rxo, args)
        finally:
            # Restore the baseline and leave nothing transmitting.
            c.tool("radio_tx_power", {"session": txo["session"], "offset_qdb": 0, "index_override": -1})
            for o in opened:
                c.tool("radio_close", {"session": o["session"]})

    return _finish(failures)


def _finish(failures):
    print("=" * 60)
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All TX-power hardware checks passed.")
    return 0


def check_knobs(c, txo):
    tx = txo["session"]
    caps = txo["power"]
    failures = []
    if caps.get("valid"):
        ok("state readable before any knob was moved")
    else:
        print(f"       (not brought up yet: {caps.get('why', '')[:80]})")

    # A wrong-typed knob must be refused, not skipped-and-reported-as-success.
    mistyped = c.tool("radio_tx_power", {"session": tx, "offset_qdb": "4"})
    if errored(mistyped):
        ok("a wrong-typed offset was refused before it reached the radio")
    else:
        bad(f"wrong-typed offset was accepted: {json.dumps(mistyped)}")
        failures.append("txpower: wrong-typed offset")

    lo = caps.get("offset_min_qdb", -16)
    hi = caps.get("offset_max_qdb", 16)
    step = caps.get("step_qdb", 1) or 1
    target = -(2 * step) if lo <= -(2 * step) else lo
    applied = c.tool("radio_tx_power", {"session": tx, "offset_qdb": target})
    if applied.get("offset_steps") == target // step and applied.get("offset_qdb") == target:
        ok(f"offset {target} qdB applied as {applied.get('offset_qdb')} qdB / "
           f"{applied.get('offset_steps')} steps")
    else:
        bad(f"offset did not apply cleanly: {json.dumps(applied)}")
        failures.append("txpower: offset apply")

    out = c.tool("radio_tx_power", {"session": tx, "offset_qdb": hi + 999})
    if errored(out):
        ok("an out-of-range offset was refused")
    else:
        bad(f"out-of-range offset accepted: {json.dumps(out)}")
        failures.append("txpower: offset bound")

    if caps.get("index_max", 0) == 0:
        print("       (no flat index on this dBm-model family; override skipped)")
        return failures
    idx = min(caps["index_max"], 20)
    flat = c.tool("radio_tx_power", {"session": tx, "index_override": idx})
    if flat.get("flat_index") == idx:
        ok(f"a flat index override to {idx} took")
    else:
        bad(f"flat index override failed: {json.dumps(flat)}")
        failures.append("txpower: index override")
    cleared = c.tool("radio_tx_power", {"session": tx, "index_override": -1})
    if cleared.get("flat_index") == -1:
        ok("-1 cleared the override back to the per-rate table")
    else:
        bad(f"override clear failed: {json.dumps(cleared)}")
        failures.append("txpower: index clear")
    return failures


def check_rate_diffs(c, txo, rxo, args):
    """Trim one rate and confirm only that rate's received level moved.

    This is the per-rate claim: the table REPLACES the calibrated shape, so a
    cut on MCS0 must lower MCS0's RSSI at an independent receiver while MCS7 —
    the anchor — stays put.
    """
    tx, rx = txo["session"], rxo["session"]
    print(f"\n  per-rate diffs: {txo['chip']} tx {tx} -> {rxo['chip']} witness {rx}")

    def probe():
        res = c.tool(
            "experiment_link_probe",
            {
                "tx_session": tx, "rx_session": rx,
                "channel": args.channel, "width_mhz": args.width,
                "modes": ["MCS0/20", "MCS7/20"],
                "frames_per_point": args.frames, "interval_us": 1000,
                "max_duration_ms": 30000,
            },
            timeout=180,
        )
        if res.get("_isError") or not res.get("points"):
            return None
        return {
            p["point"].split()[0]: (p.get("witnesses", {}).get("RX_PEER", {}) or {}).get("rssi_mean")
            for p in res["points"]
        }

    failures = []
    base = probe()
    if not base or base.get("MCS0/20") is None or base.get("MCS7/20") is None:
        bad("baseline probe produced no witness RSSI")
        return ["txpower: rate-diff baseline"]

    trimmed = c.tool(
        "radio_tx_power",
        {"session": tx, "rate_diffs": {"cck": 0, "legacy": 0, "mcs": [-32, 0, 0, 0, 0, 0, 0, 0]}},
    )
    if trimmed.get("rate_diffs_custom") is True:
        ok("a -32 qdB MCS0 diff was accepted and is reported configured")
    else:
        bad(f"rate_diffs did not register: {json.dumps(trimmed)}")
        failures.append("txpower: rate diffs not configured")

    after = probe()
    if not after or after.get("MCS0/20") is None:
        bad("post-trim probe produced no witness RSSI")
        failures.append("txpower: rate-diff probe")
    else:
        drop = base["MCS0/20"] - after["MCS0/20"]
        anchor_shift = abs(after["MCS7/20"] - base["MCS7/20"])
        print(f"       MCS0 {base['MCS0/20']:.1f} -> {after['MCS0/20']:.1f} "
              f"(drop {drop:.1f} dB); MCS7 {base['MCS7/20']:.1f} -> {after['MCS7/20']:.1f} "
              f"(shift {anchor_shift:.1f} dB)")
        if drop >= 2.0:
            ok(f"MCS0 dropped {drop:.1f} dB while the anchor held")
        else:
            bad(f"MCS0 did not drop on the witness (drop {drop:.1f} dB)")
            failures.append("txpower: rate diff did not move MCS0")
        if anchor_shift <= 3.0:
            ok(f"MCS7 anchor unchanged within noise ({anchor_shift:.1f} dB)")
        else:
            bad(f"MCS7 anchor moved {anchor_shift:.1f} dB — the table is not per-rate")
            failures.append("txpower: anchor moved")

    cleared = c.tool("radio_tx_power", {"session": tx, "clear_rate_diffs": True})
    if cleared.get("rate_diffs_custom") is False:
        ok("clear restored the calibrated shape")
    else:
        bad(f"clear did not take: {json.dumps(cleared)}")
        failures.append("txpower: rate-diff clear")
    return failures


def check_unsupported(c, devices):
    failures = []
    for o in devices:
        s = o["session"]
        power = c.tool("radio_tx_power", {"session": s})
        if power.get("supported") is False and power.get("why"):
            ok(f"session {s}: no TX-power knobs, with a reason")
        else:
            bad(f"session {s}: expected supported:false with a reason: {json.dumps(power)}")
            failures.append(f"txpower: session {s} absence not honest")
        attempt = c.tool("radio_tx_power", {"session": s, "offset_qdb": 0})
        if errored(attempt):
            ok(f"session {s}: a set attempt was refused")
        else:
            bad(f"session {s}: set silently no-op'd: {json.dumps(attempt)}")
            failures.append(f"txpower: session {s} set not refused")
    return failures


def sweep(c, txo, rxo, args):
    """Move the knob and watch the independent receiver's RSSI follow."""
    tx, rx = txo["session"], rxo["session"]
    print(f"\n  power sweep: {txo['chip']} tx session {tx} -> "
          f"{rxo['chip']} witness session {rx} on ch{args.channel}")
    caps = txo["power"]
    # A swing big enough to clear receiver AGC and probe noise: the caps range
    # can be far wider than is useful, and the adapters are inches apart.
    lo = max(caps.get("offset_min_qdb", -16), -64)
    hi = min(caps.get("offset_max_qdb", 16), 64)
    if hi <= lo:
        lo, hi = caps.get("offset_min_qdb", -4), caps.get("offset_max_qdb", 4)
    points = sorted(set([lo, 0, hi]))

    measured = []
    for offset in points:
        c.tool("radio_tx_power", {"session": tx, "offset_qdb": offset})
        res = c.tool(
            "experiment_link_probe",
            {
                "tx_session": tx,
                "rx_session": rx,
                "channel": args.channel,
                "width_mhz": args.width,
                "modes": ["6M"],
                "frames_per_point": args.frames,
                "interval_us": 1000,
                "max_duration_ms": 30000,
            },
            timeout=180,
        )
        if res.get("_isError") or not res.get("points"):
            bad(f"link_probe at offset {offset}: {res.get('_text', json.dumps(res))[:120]}")
            return ["txpower: sweep link_probe"]
        point = res["points"][0]
        witness = point.get("witnesses", {}).get("RX_PEER", {})
        rssi = witness.get("rssi_mean")
        measured.append((offset, rssi, point.get("delivery_ratio")))
        print(f"       offset {offset:>4} qdB -> witness rssi {rssi} delivery {point.get('delivery_ratio'):.3f}")

    failures = []
    rssis = [(o, r) for o, r, _ in measured if r is not None]
    if len(rssis) >= 2:
        low_offset, low_rssi = rssis[0]
        high_offset, high_rssi = rssis[-1]
        if high_rssi > low_rssi:
            ok(f"witness RSSI followed the knob: {low_rssi:.1f} dBm at {low_offset} qdB "
               f"-> {high_rssi:.1f} dBm at {high_offset} qdB")
        else:
            bad(f"witness RSSI did NOT follow the knob ({low_rssi} at {low_offset} -> "
                f"{high_rssi} at {high_offset})")
            failures.append("txpower: sweep did not move RSSI")
    else:
        bad("the sweep produced no witness RSSI")
        failures.append("txpower: no sweep RSSI")
    return failures


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

#!/usr/bin/env python3
"""Hardware test that the multi-witness link probe is first-class through MCP.

One transmitter, two independent receivers hearing the same burst at the same
time. This is a qualitatively different measurement from two separate runs: when
two receivers agree frame-for-frame the missing frames were never aired, and
`LinkProbe` says so in the result. A demo cannot do this at all, which is the
"strictly better" half of the parity gate.

The test refuses to pass vacuously: it requires both witnesses to have heard the
burst, requires the result to carry the two-witness localisation note, and (like
every multi-run test here) prefers a jaguar3 transmitter because a jaguar2
transmitter wedges on a second run in one session (recorded in
`docs/hardware-evidence.md`).

    tools/multi-witness-test.py [--channel 6] [--width 20] [--frames 300]

Every radio is closed before it exits.
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


def tx_pref(gen, chip):
    if "jaguar3" in gen:
        return 0
    if "jaguar" in gen:
        return 1
    return 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--width", type=int, default=20)
    ap.add_argument("--frames", type=int, default=300)
    ap.add_argument("--mode", default="MCS0/20")
    args = ap.parse_args()

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 3:
            print(
                f"Need three adapters (transmitter + two independent witnesses); found "
                f"{len(devices)}. This test needs hardware."
            )
            return 2

        opened = []
        try:
            for d in devices:
                r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
                if r.get("_isError") or "session" not in r:
                    bad(f"open {d['usb_id']} bus{d['bus']}: {str(r.get('_text', r))[:120]}")
                    continue
                caps = r.get("capabilities", {})
                opened.append(
                    {
                        "session": r["session"],
                        "chip": caps.get("chip", "?"),
                        "generation": caps.get("generation", "?"),
                    }
                )
        except McpError:
            for o in opened:
                c.tool("radio_close", {"session": o["session"]})
            raise
        try:
            if len(opened) < 3:
                bad(f"only {len(opened)} of {len(devices)} adapters opened; need three")
                return 1

            opened.sort(key=lambda o: tx_pref(o["generation"], o["chip"]))
            tx = opened[0]
            w1, w2 = opened[1], opened[2]
            print(
                f"  transmitter: {tx['chip']} session {tx['session']}\n"
                f"  witness A:   {w1['chip']} session {w1['session']}\n"
                f"  witness B:   {w2['chip']} session {w2['session']}"
            )

            res = c.tool(
                "experiment_link_probe",
                {
                    "tx_session": tx["session"],
                    "rx_session": w1["session"],
                    "witness_sessions": [w2["session"]],
                    "channel": args.channel,
                    "width_mhz": args.width,
                    "modes": [args.mode],
                    "frames_per_point": args.frames,
                    "interval_us": 1000,
                    "max_duration_ms": max(10000, min(60000, args.frames * 4)),
                },
                timeout=300,
            )
            if errored(res) or not res.get("points"):
                bad(f"experiment_link_probe failed: {str(res.get('_text', res))[:200]}")
                return 1

            failures = []
            roles = res.get("roles", {})
            print(f"\n  result roles: {json.dumps(roles)}")
            point = res["points"][0]
            counts = {k: v.get("frames_received") for k, v in point.get("witnesses", {}).items()}
            print(f"  witness frames: {json.dumps(counts)}  delivery {point.get('delivery_ratio')}")
            print(f"  verification: {res.get('verification')}")

            if res.get("verification") != "TX_VERIFIED":
                bad(f"verification is {res.get('verification')}, not TX_VERIFIED")
                failures.append("not TX_VERIFIED")
            if roles.get("TX_PEER") is None or roles.get("RX_PEER") is None or roles.get("MONITOR") is None:
                bad(f"the result does not name all three roles: {json.dumps(roles)}")
                failures.append("roles missing")
            # Both witnesses must have decoded a real share of the burst. >0 alone
            # would call "each heard 1 of 300" verified, which is role plumbing,
            # not a delivery result.
            floor = max(1, int(0.25 * args.frames))
            if len(counts) < 2 or any((n or 0) < floor for n in counts.values()):
                bad(f"both witnesses must hear the burst (>= {floor}); got {json.dumps(counts)}")
                failures.append("a witness heard too little")

            # The two-witness note is the whole point: it must be present, and it
            # must be the localisation sentence, not a generic caveat.
            note = next((cv for cv in res.get("caveats", []) if "exactly the same frames" in cv
                         or "disagreed by up to" in cv), None)
            if note is None:
                bad("no two-witness localisation note in the caveats")
                failures.append("no multi-witness note")
            else:
                agreement = "exactly the same frames" in note
                ok(f"two-witness note present ({'agree' if agreement else 'disagree'}): "
                   f"{note[:160]}")
                if agreement:
                    ok("the run localises the loss to the transmitter (frame-for-frame agreement)")

            print("\n" + "=" * 60)
            if failures:
                print(f"FAILED ({len(failures)}): " + "; ".join(failures))
                return 1
            print("Multi-witness link probe verified through MCP.")
            return 0
        finally:
            for o in opened:
                c.tool("radio_close", {"session": o["session"]})


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

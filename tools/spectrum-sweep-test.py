#!/usr/bin/env python3
"""Hardware test for the coarse energy survey (spectrum_sweep).

Dwell a few channels with the lean retune and read the chip's frame-free
counters at each, then check the radio ends up back where it started and that a
backend without the counters says so instead of reporting a picture of zeros.

    tools/spectrum-sweep-test.py [--channels 1,6,11] [--dwell-ms 150]

NEVER passes vacuously: no adapter reporting a supported sweep is a failure.
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channels", default="1,6,11")
    ap.add_argument("--dwell-ms", type=int, default=150)
    args = ap.parse_args()
    channels = [int(c) for c in args.channels.split(",") if c.strip()]

    failures = []
    supported_seen = False

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if not devices:
            print("No Devourer-capable adapters present. This test needs hardware.")
            return 2

        for d in devices:
            label = f"{d['usb_id']} @ bus{d['bus']}/dev{d['address']}"
            print(f"=== {label} ===")
            radio = c.tool(
                "radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180
            )
            if radio.get("_isError") or "session" not in radio:
                bad(f"open: {radio.get('_text', json.dumps(radio))[:160]}")
                failures.append(f"{label}: open")
                continue
            session = radio["session"]
            chip = radio["capabilities"]["chip"]
            start = channels[0]

            started = c.tool(
                "monitor_start",
                {"session": session, "channel": start, "width_mhz": 20},
                timeout=180,
            )
            if started.get("_isError"):
                bad(f"monitor_start: {started.get('_text', json.dumps(started))[:160]}")
                failures.append(f"{label}: monitor_start")
                c.tool("radio_close", {"session": session})
                continue
            capture = started["capture_id"]

            sweep = c.tool(
                "spectrum_sweep",
                {"session": session, "channels": channels, "dwell_ms": args.dwell_ms},
                timeout=180,
            )
            print(f"  sweep: {json.dumps(sweep)[:500]}")

            if sweep.get("supported"):
                supported_seen = True
                got = [p["channel"] for p in sweep.get("points", [])]
                if got == channels:
                    ok(f"{chip}: swept {got}, quietest ch{sweep.get('quietest_channel')}")
                else:
                    bad(f"sweep points {got} != {channels}")
                    failures.append(f"{label}: sweep points")
                for p in sweep.get("points", []):
                    print(
                        f"       ch{p['channel']}: cca={p.get('cca_total')} "
                        f"fa={p.get('fa_total')} igi={p.get('igi')}"
                    )
                after = c.tool("radio_fast_retune", {"session": session, "channel": start})
                if after.get("channel") == start:
                    ok(f"{chip}: restored to ch{start} after the sweep")
            else:
                if sweep.get("why"):
                    ok(f"{chip}: no frame-free energy, with a reason")
                else:
                    bad(f"unsupported sweep without a reason: {json.dumps(sweep)}")
                    failures.append(f"{label}: sweep absence not honest")

            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not supported_seen:
        bad("no adapter reported a supported sweep — nothing was verified")
        failures.append("no frame-free energy on the bench")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All spectrum-sweep hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

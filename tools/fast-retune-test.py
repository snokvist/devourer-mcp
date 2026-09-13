#!/usr/bin/env python3
"""Hardware test for the lean retune paths: fast_retune and fast_bandwidth.

Both are unconditional calls — devourer falls back to a full SetMonitorChannel
where a family has no lean path — so the interesting facts are that the move
lands, and that `fast_retune` tells the truth about which path was available.
The hop is also timed, because the whole point of the lean path is the cost.

    tools/fast-retune-test.py [--channel 6] [--hop-channel 1]

NEVER passes vacuously: no adapter reporting the lean path is a failure.
"""

import argparse
import json
import os
import sys
import time

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
    ap.add_argument("--hop-channel", type=int, default=1)
    args = ap.parse_args()

    failures = []
    lean_seen = False

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

            started = c.tool(
                "monitor_start",
                {"session": session, "channel": args.channel, "width_mhz": 20},
                timeout=180,
            )
            if started.get("_isError"):
                bad(f"monitor_start: {started.get('_text', json.dumps(started))[:160]}")
                failures.append(f"{label}: monitor_start")
                c.tool("radio_close", {"session": session})
                continue
            capture = started["capture_id"]

            t0 = time.time()
            hop = c.tool(
                "radio_fast_retune", {"session": session, "channel": args.hop_channel}
            )
            hop_ms = (time.time() - t0) * 1000
            if hop.get("channel") == args.hop_channel:
                ok(f"{chip}: hopped to ch{args.hop_channel} in {hop_ms:.0f} ms "
                   f"(lean={hop.get('fast_retune')})")
            else:
                bad(f"fast_retune did not land: {json.dumps(hop)}")
                failures.append(f"{label}: fast_retune")
            if hop.get("fast_retune"):
                lean_seen = True

            # Narrowband is genuinely optional; where the adapter lacks it the
            # width change must be refused, not reported as a width it kept the
            # channel at.
            widths = set(radio["capabilities"]["bandwidths_mhz"])
            if "5" in widths:
                bw = c.tool("radio_fast_bandwidth", {"session": session, "width_mhz": 5})
                if bw.get("width") == 5:
                    ok(f"{chip}: narrowband toggle to {bw.get('width')} MHz")
                else:
                    bad(f"fast_bandwidth did not land: {json.dumps(bw)}")
                    failures.append(f"{label}: fast_bandwidth")
                c.tool("radio_fast_bandwidth", {"session": session, "width_mhz": 20})
            else:
                refused = c.tool("radio_fast_bandwidth", {"session": session, "width_mhz": 5})
                if errored(refused):
                    ok(f"{chip}: 5 MHz refused (supports {sorted(widths)})")
                else:
                    bad(f"5 MHz accepted on a {sorted(widths)} adapter: {json.dumps(refused)}")
                    failures.append(f"{label}: width not gated")

            # Back to the start channel, so the radio is left where it was found.
            c.tool("radio_fast_retune", {"session": session, "channel": args.channel})

            # At least one adapter must actually have the lean path, or this
            # test is only exercising the fallback.
            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not lean_seen:
        bad("no adapter reported the lean FastRetune path — only the fallback ran")
        failures.append("no lean path on the bench")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All fast-retune hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

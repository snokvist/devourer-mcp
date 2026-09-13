#!/usr/bin/env python3
"""Hardware test for the MAC TSF read.

Checks the TSF is reported as not-readable before bring-up (rather than a bare
zero), that it reads on a brought-up radio, and that it advances at roughly
wall-clock rate.

    tools/tsf-test.py [--channel 6] [--seconds 0.3]

NEVER passes vacuously: no adapter with a readable TSF is a failure.
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--seconds", type=float, default=0.3)
    args = ap.parse_args()

    failures = []
    readable_seen = False
    adopted_seen = False

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if not devices:
            print("No Devourer-capable adapters present. This test needs hardware.")
            return 2

        for d in devices:
            label = f"{d['usb_id']} @ bus{d['bus']}/dev{d['address']}"
            print(f"=== {label} ===")
            radio = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
            if radio.get("_isError") or "session" not in radio:
                bad(f"open: {radio.get('_text', json.dumps(radio))[:160]}")
                failures.append(f"{label}: open")
                continue
            session = radio["session"]
            chip = radio["capabilities"]["chip"]

            cold = c.tool("radio_tsf", {"session": session})
            if cold.get("readable") is False and cold.get("why"):
                ok(f"{chip}: reports not-readable before bring-up, with a reason")
            else:
                bad(f"cold TSF not honest: {json.dumps(cold)}")
                failures.append(f"{label}: cold tsf")

            started = c.tool("monitor_start",
                             {"session": session, "channel": args.channel, "width_mhz": 20},
                             timeout=180)
            if started.get("_isError"):
                bad(f"monitor_start: {started.get('_text', json.dumps(started))[:160]}")
                failures.append(f"{label}: monitor_start")
                c.tool("radio_close", {"session": session})
                continue
            capture = started["capture_id"]

            first = c.tool("radio_tsf", {"session": session})
            time.sleep(args.seconds)
            second = c.tool("radio_tsf", {"session": session})
            if first.get("readable") and second.get("readable"):
                readable_seen = True
                delta_us = second["tsf_us"] - first["tsf_us"]
                expected_us = args.seconds * 1e6
                # Generous: the two reads straddle the sleep, and a control
                # round trip costs a little more. Only a non-advancing or
                # wildly-off clock fails.
                if expected_us * 0.5 < delta_us < expected_us * 1.5 + 200_000:
                    ok(f"{chip}: TSF advanced {delta_us / 1000:.0f} ms over "
                       f"{args.seconds * 1000:.0f} ms")
                else:
                    bad(f"TSF delta {delta_us}us for a {expected_us:.0f}us sleep")
                    failures.append(f"{label}: tsf rate")

                # Adoption: shift the clock forward and check the readback.
                target = second["tsf_us"] + 10_000_000  # +10 s
                wrote = c.tool("radio_tsf", {"session": session, "set_tsf_us": target})
                if wrote.get("took") is True:
                    adopted_seen = True
                    ok(f"{chip}: WriteTsf adoption took (readback +{wrote.get('delta_us')}us)")
                elif wrote.get("took") is False and wrote.get("note"):
                    # A backend that does not wire WriteTsf — honest, not a pass
                    # of adoption, but not a failure of this adapter alone.
                    print(f"  {chip}: WriteTsf did not take, reported honestly")
                else:
                    bad(f"tsf write not honest: {json.dumps(wrote)[:200]}")
                    failures.append(f"{label}: tsf write")
            else:
                print(f"  {chip}: TSF not readable after bring-up: {json.dumps(first)[:160]}")

            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not readable_seen:
        bad("no adapter returned a readable TSF — nothing was verified")
        failures.append("no readable TSF on the bench")
    if not adopted_seen:
        bad("no adapter accepted a TSF write — adoption was not verified")
        failures.append("no TSF adoption on the bench")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All TSF hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

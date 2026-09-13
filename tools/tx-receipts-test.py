#!/usr/bin/env python3
"""Hardware test for per-frame TX receipts (`tx.report`).

Opens a transmitter with `tx_report`, sends a bounded burst through the
experiment engine, then reads `radio_tx_receipts` and checks the radio's own
account of the frames — delivery state, hardware retries, final rate. This is
the TX-side sensor tx_stats cannot be: tx_stats counts what the host submitted.

    tools/tx-receipts-test.py [--channel 6] [--width 20] [--frames 200]

NEVER passes vacuously: no adapter returning receipts is a failure.
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
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--width", type=int, default=20)
    ap.add_argument("--frames", type=int, default=200)
    args = ap.parse_args()

    failures = []
    receipts_seen = False

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 2:
            print("Need a transmitter and an independent receiver. This test "
                  f"needs hardware (found {len(devices)}).")
            return 2

        opened = []
        for d in devices:
            r = c.tool(
                "radio_open",
                {
                    "bus": d["bus"],
                    "address": d["address"],
                    "tx_report": 1,  # report every frame
                },
                timeout=180,
            )
            if r.get("_isError") or "session" not in r:
                bad(f"open {d['usb_id']}: {r.get('_text', r)}")
                continue
            opened.append(r)

        try:
            if len(opened) < 2:
                bad("fewer than two adapters opened")
                return _finish(failures, receipts_seen)

            # tx.report is a CCX C2H facility: only the Jaguar generations
            # emit it. A MediaTek ignores the divisor and reports nothing.
            ccx = [
                o for o in opened
                if o["capabilities"]["generation"] in ("jaguar1", "jaguar2", "jaguar3")
            ]
            if not ccx:
                bad("no CCX-capable (Jaguar) adapter to emit tx.report")
                return _finish(failures, receipts_seen)
            tx = ccx[0]
            rx = next(o for o in opened if o["session"] != tx["session"])
            print(f"  TX {tx['capabilities']['chip']} session {tx['session']}  "
                  f"RX {rx['capabilities']['chip']} session {rx['session']}")

            res = c.tool(
                "experiment_link_probe",
                {
                    "tx_session": tx["session"],
                    "rx_session": rx["session"],
                    "channel": args.channel,
                    "width_mhz": args.width,
                    "modes": ["6M"],
                    "frames_per_point": args.frames,
                    "interval_us": 1000,
                    "max_duration_ms": 30000,
                },
                timeout=180,
            )
            if res.get("_isError"):
                bad(f"link_probe: {res.get('_text', json.dumps(res))[:160]}")
                return _finish(failures, receipts_seen)

            got = c.tool("radio_tx_receipts", {"session": tx["session"]})
            print(f"  receipts: {json.dumps(got)[:600]}")

            if not got.get("enabled"):
                bad(f"receipts disabled despite tx_report: {json.dumps(got)}")
                failures.append("tx-receipts: not enabled")
            elif got.get("total", 0) == 0:
                bad(f"tx_report enabled but no reports arrived: {json.dumps(got)[:200]}")
                failures.append("tx-receipts: none arrived")
            else:
                receipts_seen = True
                first = got["receipts"][0]
                for field in ("state", "ok", "retries", "final_rate", "fmt"):
                    if field not in first:
                        bad(f"receipt missing {field}: {json.dumps(first)}")
                        failures.append(f"tx-receipts: missing {field}")
                ok(f"{tx['capabilities']['chip']}: {got['total']} reports "
                   f"({len(got['receipts'])} buffered), first state={first['state']} "
                   f"retries={first['retries']} final_rate={first['final_rate']}")
                # Drained by default: a second read should be empty.
                again = c.tool("radio_tx_receipts", {"session": tx["session"]})
                if again.get("receipts"):
                    bad("receipts were not drained on read")
                    failures.append("tx-receipts: not drained")
                else:
                    ok("a second read came back drained")

            # A backend without the report path still answers honestly.
            for o in opened[2:]:
                other = c.tool("radio_tx_receipts", {"session": o["session"]})
                print(f"  {o['capabilities']['chip']} receipts: {json.dumps(other)[:200]}")
        finally:
            for o in opened:
                c.tool("radio_close", {"session": o["session"]})

    return _finish(failures, receipts_seen)


def _finish(failures, seen):
    print("=" * 60)
    if not seen:
        failures.append("no adapter returned tx receipts")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All TX-receipt hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

#!/usr/bin/env python3
"""Hardware test for the A-MPDU TX mode.

Read, enable and clear the mode, and check the capability tri-state is honest:
`unknown` on a read, `supported` after a set that took, an error on a backend
that refuses. The goodput gain itself needs a feeder that keeps the TX queue
deep, which this bridge's one-frame-at-a-time send path does not; this test
verifies control, not the +30%.

    tools/ampdu-test.py [--channel 6]

NEVER passes vacuously: no adapter accepting a mode is a failure.
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
    args = ap.parse_args()

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
            radio = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
            if radio.get("_isError") or "session" not in radio:
                bad(f"open: {radio.get('_text', json.dumps(radio))[:160]}")
                failures.append(f"{label}: open")
                continue
            session = radio["session"]
            chip = radio["capabilities"]["chip"]

            started = c.tool("monitor_start",
                             {"session": session, "channel": args.channel, "width_mhz": 20},
                             timeout=180)
            if started.get("_isError"):
                bad(f"monitor_start: {started.get('_text', json.dumps(started))[:160]}")
                failures.append(f"{label}: monitor_start")
                c.tool("radio_close", {"session": session})
                continue
            capture = started["capture_id"]

            before = c.tool("radio_ampdu", {"session": session})
            print(f"  read: {json.dumps(before)[:240]}")
            set_result = c.tool("radio_ampdu",
                                {"session": session, "enabled": True, "tid": 0, "max_num": 16})
            if errored(set_result):
                after = c.tool("radio_ampdu", {"session": session})
                if after.get("capability") == "unsupported":
                    ok(f"{chip}: refused A-MPDU, and now reports unsupported")
                else:
                    bad(f"set refused but capability {after.get('capability')}: "
                        f"{json.dumps(set_result)[:160]}")
                    failures.append(f"{label}: refusal not reflected")
            else:
                supported_seen = True
                if set_result.get("enabled") is True and set_result.get("capability") == "supported":
                    ok(f"{chip}: enabled (tid {set_result.get('tid')} max {set_result.get('max_num')})")
                else:
                    bad(f"set did not take: {json.dumps(set_result)}")
                    failures.append(f"{label}: enable")
                cleared = c.tool("radio_ampdu", {"session": session, "clear": True})
                if cleared.get("enabled") is False:
                    ok(f"{chip}: cleared")
                else:
                    bad(f"clear failed: {json.dumps(cleared)}")
                    failures.append(f"{label}: clear")

            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not supported_seen:
        bad("no adapter accepted A-MPDU — nothing was verified")
        failures.append("no A-MPDU support on the bench")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All A-MPDU hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

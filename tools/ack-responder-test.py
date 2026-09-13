#!/usr/bin/env python3
"""Hardware test for the hardware ACK responder.

Read, arm and clear the MAC's ACK responder, and check that arming is refused
without the experimental level and that a backend without the feature says so
rather than reporting "unarmed".

    tools/ack-responder-test.py [--channel 6]

NEVER passes vacuously: no adapter reporting the feature is a failure.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAC = "02:00:00:00:00:01"


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

            ack = c.tool("radio_ack_responder", {"session": session})
            print(f"  read: {json.dumps(ack)[:240]}")
            if ack.get("supported"):
                supported_seen = True
                refused = c.tool("radio_ack_responder", {"session": session, "mac": MAC})
                if errored(refused):
                    ok(f"{chip}: arming without experimental was refused")
                else:
                    bad(f"arming without the level was accepted: {json.dumps(refused)}")
                    failures.append(f"{label}: safety gate")

                armed = c.tool("radio_ack_responder",
                               {"session": session, "mac": MAC, "safety_level": "experimental"})
                if armed.get("armed") is True and armed.get("mac") == MAC:
                    ok(f"{chip}: armed for {MAC}")
                else:
                    bad(f"arm failed: {json.dumps(armed)}")
                    failures.append(f"{label}: arm")
                readback = c.tool("radio_ack_responder", {"session": session})
                if readback.get("armed") is True:
                    ok(f"{chip}: readback confirms armed")
                cleared = c.tool("radio_ack_responder", {"session": session, "clear": True})
                if cleared.get("armed") is False:
                    ok(f"{chip}: cleared")
                else:
                    bad(f"clear failed: {json.dumps(cleared)}")
                    failures.append(f"{label}: clear")
            elif ack.get("why"):
                ok(f"{chip}: no ACK responder, with a reason")
            else:
                bad(f"unsupported responder without a reason: {json.dumps(ack)}")
                failures.append(f"{label}: absence not honest")

            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not supported_seen:
        bad("no adapter reported the ACK-responder feature — nothing was verified")
        failures.append("no ACK responder on the bench")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All ACK-responder hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

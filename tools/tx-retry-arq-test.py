#!/usr/bin/env python3
"""Hardware test for the TX retry/ARQ bring-up knobs.

Opens a transmitter with `tx_retry_limit > 0` and `tx_report=1`, sends a bounded
unicast burst to a MAC, and reads the per-frame TX receipts. With no responder
the frames retry to the limit and report a retry-drop; with a SECOND adapter's
hardware ACK responder armed for that MAC, the retries collapse and the state
becomes delivered. That contrast is the knob's whole purpose, and the receipts
are what make it visible (a host-side counter cannot see hardware retries).

    tools/tx-retry-arq-test.py [--channel 6] [--frames 200]

NEVER passes vacuously: a transmitter whose caps do not report tx_retry_limit, or
a run that produces no receipts, is a failure.
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESPONDER_MAC = "02:00:00:00:00:22"


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def radiotap_ht():
    return bytes([0, 0, 13, 0, 0x00, 0x80, 0x08, 0x00, 0x08, 0x00, 0x3F, 0x00, 0x00])


def unicast(sa, da, n=200):
    f = bytearray(n)
    f[0], f[1] = 0x08, 0x00
    f[4:10] = da
    f[10:16] = sa
    f[16:22] = sa
    f[24:28] = b"DVAQ"
    return bytes(f)


def retry_summary(reply):
    receipts = reply.get("receipts", [])
    retries = [e["retries"] for e in receipts if e.get("retries") is not None]
    states = {}
    for e in receipts:
        states[e.get("state")] = states.get(e.get("state"), 0) + 1
    median = sorted(retries)[len(retries) // 2] if retries else None
    return reply.get("total", 0), median, (max(retries) if retries else None), states


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--frames", type=int, default=200)
    ap.add_argument("--retry-limit", type=int, default=8)
    args = ap.parse_args()

    failures = []
    verified = False

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 2:
            print("Need two adapters (a transmitter and a responder).")
            return 2

        # First a plain open of each to read capabilities (they are resolved
        # without bring-up), then close and re-open the chosen pair with the
        # knobs, because tx_retry_limit is read once at CreateRadio.
        opened = []
        for d in devices:
            r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]},
                       timeout=180)
            if not errored(r) and "session" in r:
                opened.append(r)
        tx0 = next((r for r in opened
                    if r["capabilities"].get("features", {}).get("tx_retry_limit")), None)
        if tx0 is None:
            bad("no adapter reports the tx_retry_limit feature; nothing to verify")
            for r in opened:
                c.tool("radio_close", {"session": r["session"]})
            return 1
        rx0 = next(r for r in opened if r["session"] != tx0["session"])
        tx_dev, rx_dev = tx0["device"], rx0["device"]
        for r in opened:
            c.tool("radio_close", {"session": r["session"]})

        tx = c.tool("radio_open", {"bus": tx_dev["bus"], "address": tx_dev["address"],
                                   "tx_report": 1, "tx_retry_limit": args.retry_limit},
                    timeout=180)
        responder = c.tool("radio_open",
                           {"bus": rx_dev["bus"], "address": rx_dev["address"]},
                           timeout=180)
        txs, rxs = tx["session"], responder["session"]
        rcap = c.tool("monitor_start",
                      {"session": rxs, "channel": args.channel, "width_mhz": 20},
                      timeout=180).get("capture_id")
        tcap = c.tool("monitor_start",
                      {"session": txs, "channel": args.channel, "width_mhz": 20},
                      timeout=180).get("capture_id")
        print(f"transmitter: {tx['capabilities']['chip']} session {txs}; "
              f"responder: {responder['capabilities']['chip']} session {rxs}")
        try:
            sa = bytes([0x02, 0x44, 0x52, 0x00, 0x00, 0x01])
            da = bytes([0x02, 0x00, 0x00, 0x00, 0x00, 0x22])
            frame = (radiotap_ht() + unicast(sa, da)).hex()

            c.tool("radio_tx_receipts", {"session": txs})  # drain
            c.tool("tx_send", {"session": txs, "frame_hex": frame, "count": args.frames,
                               "safety_level": "developer"}, timeout=60)
            time.sleep(0.8)
            _, med, mx, states = retry_summary(c.tool("radio_tx_receipts", {"session": txs}))
            print(f"  no responder: retries median/max={med}/{mx} states={states}")
            if med is not None and med >= args.retry_limit:
                ok("without a responder, every frame retried to the limit")
            else:
                bad(f"expected retries at the limit {args.retry_limit}, got median {med}")
                failures.append("no-responder retries")

            arm = c.tool("radio_ack_responder",
                         {"session": rxs, "mac": RESPONDER_MAC,
                          "safety_level": "experimental"})
            if arm.get("armed") is not True:
                bad(f"could not arm the responder: {json.dumps(arm)[:160]}")
                failures.append("arm responder")
            else:
                c.tool("radio_tx_receipts", {"session": txs})  # drain
                c.tool("tx_send", {"session": txs, "frame_hex": frame, "count": args.frames,
                                   "safety_level": "developer"}, timeout=60)
                time.sleep(0.8)
                total, med, mx, states = retry_summary(
                    c.tool("radio_tx_receipts", {"session": txs}))
                print(f"  armed responder: retries median/max={med}/{mx} states={states}")
                delivered = states.get(0, 0)
                if total > 0 and med is not None and med <= 1 and delivered > 0:
                    verified = True
                    ok(f"the hardware ACK collapsed retries to {med} "
                       f"({delivered}/{total} delivered)")
                else:
                    bad("ARQ did not take: " + json.dumps(states))
                    failures.append("arq")
        finally:
            c.tool("radio_ack_responder", {"session": rxs, "clear": True})
            if rcap:
                c.tool("monitor_stop", {"capture_id": rcap, "discard": True})
            if tcap:
                c.tool("monitor_stop", {"capture_id": tcap, "discard": True})
            c.tool("radio_close", {"session": txs})
            c.tool("radio_close", {"session": rxs})

    print("=" * 60)
    if not verified:
        bad("no adapter demonstrated hardware ARQ — nothing was verified")
        failures.append("no ARQ")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All retry/ARQ hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

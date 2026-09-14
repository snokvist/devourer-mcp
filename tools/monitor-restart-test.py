#!/usr/bin/env python3
"""Does a second monitor_start on a session still receive? HARDWARE test.

Found by the five MCP-usefulness trials: a radio that had monitored and was
then used as an `experiment_link_probe` witness reported 0 received frames
while an untouched radio heard ~195-200/200. It is not the experiment layer at
all — it reproduces with `monitor_start`/`monitor_stop` alone, and the bridge
log shows the second RX loop entering and exiting immediately with 0 reads.

Measured (2026-09-14, ch6, 300-frame txdemo bursts):
  RTL8822B (jaguar2)   first monitor 241-285 frames, second monitor 0
  RTL8812CU (jaguar3)  first 322, second 315
  MT7612U              first 300, second 300

So it is a jaguar2-specific restart defect in the vendor RX path
(`RtlJaguar2Device::StartRxLoop` after `StopRxLoop`). A `radio_close` +
`radio_open` restores reception; a fresh session is the workaround, and a
jaguar3/MT7612U witness avoids it entirely.

This is a characterization cell: it PASSES while the known pattern holds
(jaguar2 broken, the others fine) and FAILS if jaguar2 starts restarting,
so the finding cannot go stale silently.

    python3 tools/monitor-restart-test.py
"""

import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
from mcp_client import McpClient, McpError  # noqa: E402

DEMO_DIR = os.path.join(ROOT, "build", "native-bridge", "devourer")
INTERNAL = "0e8d:0616"


def demo_env(bus, usb_id, extra):
    vid, pid = usb_id.split(":")
    base = {k: os.environ[k] for k in
            ("PATH", "HOME", "LANG", "LC_ALL", "LD_LIBRARY_PATH") if k in os.environ}
    return {**base, "DEVOURER_USB_BUS": str(bus), "DEVOURER_EVENTS": "stdout",
            "DEVOURER_LOG_LEVEL": "error", "DEVOURER_VID": f"0x{vid}",
            "DEVOURER_PID": f"0x{pid}", **extra}


def run_tx(txdemo, usb_id, bus):
    env = demo_env(bus, usb_id, {
        "DEVOURER_CHANNEL": "6", "DEVOURER_TX_RATE": "6M",
        "DEVOURER_TX_FRAMES": "300", "DEVOURER_TX_GAP_US": "2000",
        "DEVOURER_TX_QOS_DATA": "1", "DEVOURER_TX_PAYLOAD_BYTES": "200",
    })
    p = subprocess.Popen([txdemo], env=env, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, text=True)
    try:
        p.communicate(timeout=60)
    except subprocess.TimeoutExpired:
        p.terminate()
        p.communicate(timeout=15)


def burst(c, txdemo, tx_usb, tx_bus, session):
    cap = c.tool("monitor_start", {"session": session, "channel": 6, "width_mhz": 20}, timeout=180)
    cid = cap["capture_id"]
    run_tx(txdemo, tx_usb, tx_bus)
    time.sleep(0.5)
    stored = c.tool("monitor_status", {"capture_id": cid})[0]["frames_stored"]
    c.tool("monitor_stop", {"capture_id": cid, "discard": True})
    return stored


def main():
    txdemo = os.path.join(DEMO_DIR, "txdemo")
    if not os.access(txdemo, os.X_OK):
        print(f"no txdemo under {DEMO_DIR}; build the native tree first")
        return 2

    with McpClient([os.path.join(ROOT, "tools", "host", "devourer-mcp")], cwd=ROOT) as c:
        c.initialize()
        devs = [d for d in c.tool("radio_list", {}).get("devices", []) if d["usb_id"] != INTERNAL]
        by = {d["usb_id"]: d for d in devs}

        cases = [
            ("0bda:b812", "0bda:c812", True),   # jaguar2: known broken restart
            ("0bda:c812", "0bda:b812", False),
            ("0e8d:7612", "0bda:c812", False),
        ]
        failures = []
        ran = 0
        for mon_id, tx_id, known_broken in cases:
            mon, tx = by.get(mon_id), by.get(tx_id)
            if mon is None or tx is None:
                print(f"  {mon_id:12s} monitor: device not attached, skipped")
                continue
            ran += 1
            session = c.tool(
                "radio_open", {"bus": mon["bus"], "address": mon["address"]}, timeout=180,
            )["session"]
            try:
                first = burst(c, txdemo, tx_id, tx["bus"], session)
                second = burst(c, txdemo, tx_id, tx["bus"], session)
            finally:
                c.tool("radio_close", {"session": session})
                time.sleep(1)
            restarts = second > 0
            verdict = "restarts" if restarts else "SECOND MONITOR RECEIVES NOTHING"
            if restarts == known_broken:
                # The measurement contradicts the recorded pattern.
                failures.append(mon_id)
                print(f"  {mon_id:12s} first={first} second={second}  {verdict}  "
                      f"UNEXPECTED (recorded as {'broken' if known_broken else 'working'})")
            else:
                note = "known jaguar2 defect" if known_broken else "ok"
                print(f"  {mon_id:12s} first={first} second={second}  {verdict}  ({note})")
        if ran < 2:
            print("fewer than two adapters exercised; nothing proven")
            return 2
        if failures:
            print(f"\nFAIL: monitor restart behaviour changed for {failures}; "
                  "update docs/hardware-evidence.md")
            return 1
        print(f"\nPASS: a second monitor on a jaguar2 session delivers nothing "
              f"(known), and the other {ran - 1} backend(s) restart cleanly")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

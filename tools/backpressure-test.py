#!/usr/bin/env python3
"""Hardware test: does a capture survive more frames than it can keep up with?

The frame flow used to end the stream the moment its channel filled —
`trySend(...).getOrThrow()` over roughly 64 slots, which at the 3300 frames/s
this bench has measured is about 20ms of slack. Any pause longer than that
killed the capture, and the caller saw a broken flow instead of a drop count.

The distinction this test draws is exactly that one: under a burst far larger
than the reader can absorb, does the frame count keep RISING to the end of the
burst, or does it freeze part way and never move again? A frozen count is the
old behaviour; a rising one that ends with bridge-side drops is the intended
policy — backpressure reaches the bridge, and the bridge drops whole records
and reports them.

    tools/backpressure-test.py [--seconds 12] [--channel 6]
"""

import argparse
import json
import os
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOCK = os.environ.get("DEVOURER_BRIDGE_SOCK") or os.path.join(
    os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "devourer-mcp", "bridge.sock"
)
GREEN, RED, OFF = "\033[32m", "\033[31m", "\033[0m"


def ok(m):
    print(f"  {GREEN}PASS{OFF} {m}")


def bad(m):
    print(f"  {RED}FAIL{OFF} {m}")


def note(m):
    print(f"       {m}")


def flood_frame(n):
    f = bytearray(n)
    f[0] = 0x08
    f[4:10] = b"\xff" * 6
    f[10:16] = bytes([0x02, 0x42, 0x50, 0x52, 0x53, 0x53])
    f[16:22] = bytes([0x02, 0x42, 0x50, 0x52, 0x53, 0x53])
    for i in range(24, n):
        f[i] = (0xA5 ^ (i & 0xFF)) & 0xFF
    return f.hex()


class Bridge:
    """Direct control connection, used only to drive the load generator."""

    def __init__(self):
        self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.s.connect(SOCK)
        self.f = self.s.makefile("rwb")
        self.id = 0

    def call(self, op, timeout=120.0, **p):
        self.id += 1
        req = {"id": self.id, "op": op}
        req.update(p)
        self.f.write((json.dumps(req) + "\n").encode())
        self.f.flush()
        self.s.settimeout(timeout)
        line = self.f.readline()
        if not line:
            raise IOError("bridge closed")
        r = json.loads(line)
        if not r.get("ok"):
            e = r.get("error", {})
            raise RuntimeError(f"{op} [{e.get('code')}]: {e.get('message')}")
        return r.get("result", {})

    def close(self):
        try:
            self.f.close()
        finally:
            self.s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--frames", type=int, default=60000)
    ap.add_argument("--bytes", type=int, default=1200)
    args = ap.parse_args()

    if not os.path.exists(SOCK):
        print(f"no bridge socket at {SOCK} — tools/host/bridge-ctl.sh start")
        return 2

    probe = Bridge()
    try:
        devices = probe.call("radio.list").get("devices", [])
    finally:
        probe.close()
    if len(devices) < 2:
        print("This test needs two adapters: one to receive, one to generate load.")
        return 2

    # Both ends MediaTek where possible. The MT7612U is the faster receiver
    # here, and the RTL8812AU's EDCCA defers ~90% of what it is asked to
    # transmit on this bench, which makes it a poor load generator.
    mediateks = [d for d in devices if d["usb_id"].startswith("0e8d")]
    if len(mediateks) >= 2:
        dut, peer = mediateks[0], mediateks[1]
    else:
        dut = mediateks[0] if mediateks else devices[0]
        peer = next(d for d in devices
                    if (d["bus"], d["address"]) != (dut["bus"], dut["address"]))
    print(f"receiver {dut['usb_id']} bus{dut['bus']}, "
          f"load from {peer['usb_id']} bus{peer['bus']}, ch{args.channel}\n")

    failures = []
    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        radio = c.tool("radio_open", {"bus": dut["bus"], "address": dut["address"]},
                       timeout=180)
        session = radio["session"]
        cap = c.tool("monitor_start", {
            "session": session, "channel": args.channel, "width_mhz": 20,
            # A small ring, so the store is evicting while the flow is being
            # pushed: the two pressures together, not one at a time.
            "capacity": 5000,
        })
        capture_id = cap.get("capture_id")
        if not capture_id:
            bad(f"monitor_start did not return a capture id: {json.dumps(cap)[:200]}")
            return 1
        ok(f"capture {capture_id} started on ch{args.channel}")

        load = Bridge()
        stop = threading.Event()
        try:
            r = load.call("radio.open", bus=peer["bus"], address=peer["address"],
                          reset=True)
            s = r["session"]
            load.call("radio.channel", session=s, channel=args.channel,
                      width_mhz=20, offset=0, band=0)

            def burst():
                try:
                    load.call("tx.send", timeout=120.0, session=s,
                              body_hex=flood_frame(args.bytes), mode="MCS7/20",
                              count=args.frames, interval_us=0, seq_offset=24)
                except Exception as e:  # noqa: BLE001
                    note(f"load generator stopped: {e}")
                finally:
                    stop.set()

            th = threading.Thread(target=burst, daemon=True)
            th.start()

            counts = []
            alive = True
            end = time.monotonic() + args.seconds
            while time.monotonic() < end:
                time.sleep(1.0)
                rows = c.tool("monitor_status", {"capture_id": capture_id},
                              timeout=30)
                row = rows[0] if isinstance(rows, list) and rows else None
                if row is None:
                    bad(f"monitor_status returned nothing: {json.dumps(rows)[:200]}")
                    failures.append("no status")
                    break
                counts.append(row["frames_admitted"])
                if not row["running"]:
                    alive = False
                note(f"admitted={row['frames_admitted']} "
                     f"stored={row['frames_stored']} "
                     f"evicted={row['evicted_from_ring']} "
                     f"bridge_dropped={row['bridge_dropped']} "
                     f"running={row['running']}")
                if not alive:
                    break

            if not alive:
                # This is the old failure, exactly: the collector's channel
                # filled, the flow threw, and the capture stopped taking
                # frames while the radio kept receiving them.
                bad("the capture's collector stopped running mid-burst")
                failures.append("collector died")

            th.join(timeout=60)
        finally:
            try:
                load.call("radio.close", session=s, timeout=10)
            except Exception:  # noqa: BLE001
                pass
            load.close()

        rows = c.tool("monitor_status", {"capture_id": capture_id}, timeout=30)
        final = rows[0] if isinstance(rows, list) and rows else {}
        c.tool("monitor_stop", {"session": session}, timeout=30)
        c.tool("radio_close", {"session": session}, timeout=30)

        admitted = final.get("frames_admitted", 0)
        if len(counts) < 3:
            bad("not enough samples to tell a rising count from a frozen one")
            failures.append("too few samples")
        else:
            # The whole point: a flow that died would freeze at whatever it
            # had when its channel filled, typically within the first second.
            froze_at = next((i for i in range(1, len(counts))
                             if counts[i] == counts[i - 1]), None)
            if froze_at is not None and froze_at < len(counts) - 1:
                bad(f"the frame count stopped moving at sample {froze_at} "
                    f"({counts[froze_at]}) and never recovered: {counts}")
                failures.append("flow died")
            elif counts[-1] <= counts[0]:
                bad(f"the capture took in nothing after the first second: {counts}")
                failures.append("no progress")
            else:
                ok(f"the capture kept taking frames for the whole burst "
                   f"({counts[0]} -> {counts[-1]} admitted)")

        if admitted == 0:
            bad("the capture admitted nothing at all")
            failures.append("nothing admitted")
        else:
            ok(f"{admitted} frames admitted, ring held "
               f"{final.get('frames_stored')} (evicted "
               f"{final.get('evicted_from_ring')}), bridge dropped "
               f"{final.get('bridge_dropped')}")
            note("Drops are the designed outcome: the reader backpressures, the "
                 "bridge's buffer fills, and it discards whole records and counts "
                 "them. What must never happen is the stream ending.")

    if failures:
        print(f"\n{RED}{len(failures)} check(s) failed{OFF}")
        return 1
    print(f"\n{GREEN}Frame flow survived sustained overload.{OFF}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

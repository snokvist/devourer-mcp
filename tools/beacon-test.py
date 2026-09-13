#!/usr/bin/env python3
"""Hardware test for the hardware beacon (`radio_beacon`).

Arms an 802.11 beacon on one adapter and has an INDEPENDENT adapter decode it
from the air: the beacon must air with no host involvement, at roughly the
programmed interval, and carry the transmitter's live hardware TSF in its
timestamp field (the sub-microsecond TX-egress stamp). Then it stops the
beacon and confirms the air goes quiet.

    tools/beacon-test.py [--channel 6] [--interval-tu 100] [--seconds 3]

NEVER passes vacuously: without a transmitter that arms AND an independent
witness that decodes the beacon, this is a failure.
"""

import argparse
import json
import os
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Locally administered, and constant so the witness filter is exact.
BSSID = bytes([0x02, 0x00, 0xbe, 0xac, 0x00, 0x01])
BSSID_STR = "02:00:be:ac:00:01"


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def beacon_mpdu(ssid, channel, interval_tu):
    """A minimal but well-formed 802.11 beacon MPDU, as hex.

    Timestamp is zero on the wire: the transmitting MAC overwrites bytes 24-31
    with its live TSF at the TX instant, which is exactly the stamp the witness
    reads back. addr2 = addr3 = BSSID.
    """
    m = bytearray()
    m += bytes([0x80, 0x00])            # frame control: mgmt / beacon
    m += b"\x00\x00"                     # duration
    m += b"\xff\xff\xff\xff\xff\xff"     # DA: broadcast
    m += BSSID                           # SA (addr2)
    m += BSSID                           # BSSID (addr3)
    m += b"\x00\x00"                     # sequence control
    m += b"\x00" * 8                     # timestamp (hardware overwrites)
    m += interval_tu.to_bytes(2, "little")
    m += (0x0001).to_bytes(2, "little")  # capability: ESS
    s = ssid.encode()
    m += bytes([0x00, len(s)]) + s       # SSID
    rates = bytes([0x82, 0x84, 0x8b, 0x96, 0x0c, 0x12, 0x18, 0x24])
    m += bytes([0x01, len(rates)]) + rates
    m += bytes([0x03, 0x01, channel])    # DS parameter set
    return m.hex()


def beacons(c, capture_id):
    return c.tool("capture_query",
                  {"capture_id": capture_id, "kind": "mgmt/beacon",
                   "transmitter": BSSID_STR, "limit": 500})


def ssid_from_hex(raw_hex):
    """Pull the SSID IE out of a beacon MPDU: 24 B header + 8 B timestamp +
    2 B interval + 2 B capability, then the IEs."""
    b = bytes.fromhex(raw_hex or "")
    if len(b) < 38:
        return None
    i = 36
    while i + 2 <= len(b):
        tag, ln = b[i], b[i + 1]
        if i + 2 + ln > len(b):
            break
        if tag == 0x00:
            return b[i + 2:i + 2 + ln].decode("utf-8", "replace")
        i += 2 + ln
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--interval-tu", type=int, default=100)
    ap.add_argument("--seconds", type=float, default=3.0)
    ap.add_argument("--tx-generation", default=None,
                    help="use the first adapter of this generation (e.g. "
                         "jaguar3) as the transmitter; any other adapter witnesses")
    args = ap.parse_args()

    failures = []
    armed_seen = False
    witnessed_seen = False
    expected_ms = args.interval_tu * 1.024

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 2:
            print("Need at least two Devourer-capable adapters "
                  f"(a transmitter and an independent witness); found {len(devices)}.")
            return 2

        opened = []
        for d in devices:
            r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]},
                       timeout=180)
            if not errored(r) and "session" in r:
                opened.append(r)
        if len(opened) < 2:
            print("could not open two adapters")
            return 2

        # The transmitter is the requested generation if given, else the first
        # adapter; the witness is the first independent one.
        if args.tx_generation:
            tx = next((r for r in opened
                       if r["capabilities"]["generation"] == args.tx_generation), None)
            if tx is None:
                print(f"no opened adapter of generation {args.tx_generation}")
                return 2
            witness = next(r for r in opened if r["session"] != tx["session"])
        else:
            tx, witness = opened[0], opened[1]
        label = f"{tx['capabilities']['chip']} session {tx['session']}"
        print(f"transmitter: {label}; witness: {witness['capabilities']['chip']} "
              f"session {witness['session']}")

        tx_capture = witness_capture = None
        try:
            started = c.tool("monitor_start",
                             {"session": tx["session"], "channel": args.channel,
                              "width_mhz": 20}, timeout=180)
            if errored(started):
                bad(f"tx monitor_start: {json.dumps(started)[:160]}")
                return 2
            tx_capture = started["capture_id"]

            wstarted = c.tool("monitor_start",
                              {"session": witness["session"], "channel": args.channel,
                               "width_mhz": 20}, timeout=180)
            if errored(wstarted):
                bad(f"witness monitor_start: {json.dumps(wstarted)[:160]}")
                return 2
            witness_capture = wstarted["capture_id"]

            cold = c.tool("radio_beacon", {"session": tx["session"]})
            if cold.get("supported") is True and cold.get("active") is False:
                ok(f"{label}: beacon reads inactive before arming")
            else:
                bad(f"cold beacon read not honest: {json.dumps(cold)[:200]}")
                failures.append(f"{label}: cold read")

            refused = c.tool("radio_beacon",
                             {"session": tx["session"], "action": "start",
                              "frame_hex": beacon_mpdu("devourer", args.channel,
                                                       args.interval_tu),
                              "interval_tu": args.interval_tu})
            if errored(refused):
                ok(f"{label}: starting without experimental was refused")
            else:
                bad(f"start without the level was accepted: {json.dumps(refused)[:200]}")
                failures.append(f"{label}: safety gate")

            frame = beacon_mpdu("devourer-beacon", args.channel, args.interval_tu)
            armed = c.tool("radio_beacon",
                           {"session": tx["session"], "action": "start",
                            "frame_hex": frame, "interval_tu": args.interval_tu,
                            "safety_level": "experimental"})
            if armed.get("active") is True and armed.get("started") is True:
                armed_seen = True
                ok(f"{label}: armed, interval {armed.get('interval_tu')} TU "
                   f"({expected_ms:.1f} ms), {armed.get('mpdu_bytes')} B MPDU")
            elif errored(armed):
                bad(f"arm failed: {json.dumps(armed)[:200]}")
                failures.append(f"{label}: arm")
                return _finish(failures, armed_seen, witnessed_seen)
            else:
                bad(f"arm reply not honest: {json.dumps(armed)[:200]}")
                failures.append(f"{label}: arm reply")

            time.sleep(args.seconds)

            rows = beacons(c, witness_capture)
            if not isinstance(rows, list):
                bad(f"witness query failed: {json.dumps(rows)[:200]}")
                failures.append("witness query")
                rows = []
            # capture_query returns newest-first; cadence wants chronological.
            rows = sorted(rows, key=lambda r: r["host_ns"])

            if len(rows) >= 3:
                witnessed_seen = True
                span_ms = (rows[-1]["host_ns"] - rows[0]["host_ns"]) / 1e6
                per_s = (len(rows) - 1) / (span_ms / 1000.0) if span_ms > 0 else 0.0
                gaps = [(rows[i + 1]["host_ns"] - rows[i]["host_ns"]) / 1e6
                        for i in range(len(rows) - 1)]
                ok(f"witness {witness['capabilities']['chip']}: decoded "
                   f"{len(rows)} beacons in {span_ms:.0f} ms (~{per_s:.1f}/s, "
                   f"median gap {statistics.median(gaps):.1f} ms)")

                # Delivery is a one-way broadcast measurement; the witness being
                # right frames the transmitter, but the cadence is a host-clock
                # view so only a loose sanity band is fair.
                if expected_ms * 0.5 <= statistics.median(gaps) <= expected_ms * 2.0:
                    ok(f"cadence is within 2x of the programmed {expected_ms:.1f} ms")
                else:
                    bad(f"median gap {statistics.median(gaps):.1f} ms is far from "
                        f"{expected_ms:.1f} ms")
                    failures.append("cadence")

                # The hardware TSF stamp in the beacon body.
                detail = c.tool("frame_inspect",
                                {"capture_id": witness_capture,
                                 "index": rows[0]["index"]})
                tsf = detail.get("tx_egress_tsf")
                if isinstance(tsf, int) and tsf > 0:
                    ok(f"beacon carries the transmitter's live TX-egress TSF "
                       f"(0x{tsf:x})")
                elif detail.get("chip_tsf") is not None:
                    print("  witness has no tx_egress_tsf; the beacon timestamp "
                          "field was not populated")
                    failures.append("tsf stamp")
                else:
                    bad(f"no TX TSF stamp on the beacon: {json.dumps(detail)[:200]}")
                    failures.append("tsf stamp")
            else:
                bad(f"witness decoded {len(rows)} beacons, expected >= 3 "
                    f"(armed={json.dumps(armed)[:120]})")
                failures.append("witness saw no beacon")

            # UpdateBeaconPayload: swap the airing content in place, interval
            # untouched, and confirm the witness decodes the NEW contents.
            updated_ssid = "devourer-updated"
            upd = c.tool("radio_beacon",
                         {"session": tx["session"], "action": "update",
                          "frame_hex": beacon_mpdu(updated_ssid, args.channel,
                                                   args.interval_tu)})
            if upd.get("updated") is True and upd.get("interval_tu") == args.interval_tu:
                ok(f"{label}: payload updated in place, interval kept at "
                   f"{upd.get('interval_tu')} TU")
                time.sleep(max(0.5, expected_ms / 1000.0 * 3))
                later = sorted(beacons(c, witness_capture) or [],
                               key=lambda r: r["host_ns"])
                if later:
                    detail2 = c.tool("frame_inspect",
                                     {"capture_id": witness_capture,
                                      "index": later[-1]["index"]})
                    ssid = ssid_from_hex(detail2.get("raw_hex"))
                    if ssid == updated_ssid:
                        ok(f"witness decoded the UPDATED content (SSID '{ssid}')")
                    else:
                        bad(f"witness still sees SSID {ssid!r}, expected "
                            f"{updated_ssid!r}")
                        failures.append("update content")
                else:
                    bad("no beacons after the update")
                    failures.append("update witnessed")
            else:
                bad(f"update reply not honest: {json.dumps(upd)[:200]}")
                failures.append("update")

            stopped = c.tool("radio_beacon",
                             {"session": tx["session"], "action": "stop"})
            if stopped.get("active") is False and stopped.get("stopped") is True:
                ok(f"{label}: stopped")
            else:
                bad(f"stop reply not honest: {json.dumps(stopped)[:200]}")
                failures.append(f"{label}: stop")

            # After stopping, the air must go quiet again.
            before = len(beacons(c, witness_capture) or [])
            time.sleep(max(0.5, expected_ms / 1000.0 * 4))
            after_rows = beacons(c, witness_capture) or []
            if len(after_rows) <= before:
                ok("no further beacons after the stop")
            else:
                bad(f"{len(after_rows) - before} beacons aired after the stop")
                failures.append("beacon did not stop")
        finally:
            # Restore state even on the failure path: a beacon left airing is
            # the exact thing this instrument must never walk away from.
            try:
                c.tool("radio_beacon", {"session": tx["session"], "action": "stop"})
            except Exception:  # noqa: BLE001
                pass
            if witness_capture:
                c.tool("monitor_stop", {"capture_id": witness_capture, "discard": True})
            if tx_capture:
                c.tool("monitor_stop", {"capture_id": tx_capture, "discard": True})

        for r in opened:
            c.tool("radio_close", {"session": r["session"]})

    return _finish(failures, armed_seen, witnessed_seen)


def _finish(failures, armed_seen, witnessed_seen):
    print("=" * 64)
    if not armed_seen:
        bad("no adapter armed a beacon — nothing was verified")
        failures.append("no beacon armed")
    if not witnessed_seen:
        bad("no independent witness decoded the beacon — nothing was verified")
        failures.append("no witness")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All beacon hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

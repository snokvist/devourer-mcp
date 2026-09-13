#!/usr/bin/env python3
"""Hardware test for the fused RX sensor and the chip thermal meter.

`radio_rx_quality` and `radio_thermal` through the real MCP server. Both are
honest-absence ops: a backend that does not wire them must report
`supported:false` with a reason, never an all-invalid window (which reads as a
real NO_SIGNAL) or a raw 0 (which reads as a cool chip).

    tools/rx-quality-thermal-test.py [--channel 6] [--width 20] [--dwell-ms 700]

NEVER passes vacuously: no adapter reporting a fused feed and no adapter
reporting a thermal reading is a failure.
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
    ap.add_argument("--dwell-ms", type=int, default=700)
    args = ap.parse_args()

    failures = []
    quality_seen = False
    thermal_seen = False

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
                {"session": session, "channel": args.channel, "width_mhz": args.width},
                timeout=180,
            )
            if started.get("_isError"):
                bad(f"monitor_start: {started.get('_text', json.dumps(started))[:160]}")
                failures.append(f"{label}: monitor_start")
                c.tool("radio_close", {"session": session})
                continue
            capture = started["capture_id"]

            # Let some ambient traffic accumulate for the quality window.
            import time

            time.sleep(max(args.dwell_ms, 400) / 1000.0)

            q = c.tool("radio_rx_quality", {"session": session, "dwell_ms": args.dwell_ms})
            print(f"  rx_quality: {json.dumps(q)[:400]}")
            # Supported replies are wrapped with the dwell; an unsupported one
            # is the RxQuality body directly.
            inner = q.get("quality", q)
            if inner.get("supported"):
                quality_seen = True
                if inner.get("valid"):
                    ok(f"{chip}: fused window over {inner.get('frames')} frames, "
                       f"verdict {inner.get('verdict')} label {inner.get('label')!r}")
                else:
                    print("       (feed supported but the window is empty — no traffic)")
                if inner.get("verdict") == "NO_SIGNAL" and (inner.get("frames") or 0) > 0:
                    bad("verdict says NO_SIGNAL with frames in the window")
                    failures.append(f"{label}: verdict/frames mismatch")
                # An out-of-range peak is the parser class this bench has seen;
                # the bridge must flag it rather than pass it as clean.
                if (inner.get("rssi_max_dbm") or 0) > 20 and not inner.get("note"):
                    bad(f"out-of-range peak RSSI passed without a note: "
                        f"{inner.get('rssi_max_dbm')} dBm")
                    failures.append(f"{label}: out-of-range RSSI not flagged")
                elif (inner.get("rssi_max_dbm") or 0) > 20:
                    ok("out-of-range peak RSSI from this window is flagged, not passed clean")
            else:
                if inner.get("why"):
                    ok(f"{chip}: no fused feed, with a reason")
                else:
                    bad(f"rx_quality absent without a reason: {json.dumps(q)}")
                    failures.append(f"{label}: rx_quality absence not honest")

            t = c.tool("radio_thermal", {"session": session})
            print(f"  thermal:    {json.dumps(t)}")
            if t.get("supported"):
                thermal_seen = True
                if t.get("bucket"):
                    ok(f"{chip}: thermal raw {t.get('raw')} baseline {t.get('baseline')} "
                       f"delta {t.get('delta')} bucket {t.get('bucket')}")
                else:
                    bad(f"thermal reading with no bucket: {json.dumps(t)}")
                    failures.append(f"{label}: thermal bucket")
            else:
                if t.get("why"):
                    ok(f"{chip}: no thermal meter, with a reason")
                else:
                    bad(f"thermal absent without a reason: {json.dumps(t)}")
                    failures.append(f"{label}: thermal absence not honest")

            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not quality_seen:
        bad("no adapter reported the fused RX feed — nothing was verified")
        failures.append("no fused RX feed on the bench")
    if not thermal_seen:
        bad("no adapter reported a thermal meter — nothing was verified")
        failures.append("no thermal meter on the bench")
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All RX-quality / thermal hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

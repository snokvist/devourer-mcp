#!/usr/bin/env python3
"""End-to-end hardware smoke test over real MCP.

Runs the whole vertical slice against whatever adapters are plugged in:
discover, open, capability-gate, monitor, summarize, query, inspect, export.

This is a HARDWARE test. With no adapters attached it reports that and exits
non-zero — it never passes vacuously, because a green run that touched no radio
would be worse than no run at all.

    tools/smoke-test.py [--seconds 8] [--channel 1]
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
    ap.add_argument("--seconds", type=float, default=8.0)
    ap.add_argument("--channel", type=int, default=1)
    ap.add_argument("--width", type=int, default=20)
    args = ap.parse_args()

    failures = []
    import time

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        info = c.initialize()
        print(f"server: {info['serverInfo']['name']} {info['serverInfo']['version']}")
        names = [t["name"] for t in c.tools()]
        print(f"tools:  {len(names)}\n")

        listing = c.tool("radio_list", {})
        devices = listing.get("devices", [])
        if not devices:
            print("No Devourer-capable adapters present. This test needs hardware.")
            return 2

        print(f"=== {len(devices)} adapter(s) ===")
        for d in devices:
            print(
                f"  {d['usb_id']} bus{d['bus']}/dev{d['address']} "
                f"id={d['identification']} backend={d.get('backend') or '(probe)'}"
            )
        print()

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
            caps = radio["capabilities"]
            ok(
                f"opened: {caps['chip']} {caps['generation']} "
                f"{caps['tx_chains']}T{caps['rx_chains']}R widths={caps['bandwidths_mhz']}"
            )

            # A width the adapter does not support must be refused, not silently
            # downgraded. Pick one outside its reported set.
            widths = {int(w) for w in caps["bandwidths_mhz"]}
            impossible = next((w for w in (160, 80, 40) if w not in widths), None)
            if impossible:
                r = c.tool(
                    "monitor_start",
                    {"session": session, "channel": args.channel, "width_mhz": impossible},
                )
                if r.get("_isError"):
                    ok(f"capability gate refused {impossible}MHz")
                else:
                    bad(f"capability gate did NOT refuse {impossible}MHz")
                    failures.append(f"{label}: capability gate")

            started = c.tool(
                "monitor_start",
                {"session": session, "channel": args.channel, "width_mhz": args.width},
                timeout=180,
            )
            if started.get("_isError") or "capture_id" not in started:
                bad(f"monitor_start: {started.get('_text', json.dumps(started))[:160]}")
                failures.append(f"{label}: monitor_start")
                c.tool("radio_close", {"session": session})
                continue
            capture = started["capture_id"]
            if started.get("capability_note"):
                print(f"       note: {started['capability_note'][:120]}")
            time.sleep(args.seconds)

            status = c.tool("monitor_status", {"capture_id": capture})[0]
            summary = c.tool("capture_summary", {"capture_id": capture})["summary"]
            frames = summary.get("frames", 0)
            if frames > 0:
                ok(
                    f"RX_VERIFIED: {frames} frames in {summary['windowSeconds']:.1f}s "
                    f"({summary.get('framesPerSecond', 0):.0f}/s), "
                    f"bridge dropped {status['bridge_dropped']}"
                )
            else:
                bad("no frames received — cannot claim RX_VERIFIED")
                failures.append(f"{label}: no frames")

            if status["bridge_dropped"] > 0:
                bad(f"bridge dropped {status['bridge_dropped']} frames (reader too slow)")
                failures.append(f"{label}: drops")

            for ch in summary.get("rssi", []):
                print(
                    f"       rssi {ch['chain']}: n={ch['count']} "
                    f"min={ch['min']} max={ch['max']} mean={ch['mean']:.1f}"
                )
            kinds = summary.get("byKind", {})
            if kinds:
                ok(f"decoded {len(kinds)} frame kinds: {list(kinds)[:5]}")
            else:
                bad("no frame kinds decoded")
                failures.append(f"{label}: no decode")

            # Chain reporting must never exceed the silicon's chain count.
            declared = caps["rx_chains"]
            for field in ("rssi", "snr"):
                got = len(summary.get(field, []))
                if got > declared:
                    bad(f"{field} reports {got} chains on a {declared}-chain radio")
                    failures.append(f"{label}: {field} chain count")
                elif got:
                    ok(f"{field} bounded to {got}/{declared} chains")

            rows = c.tool("capture_query", {"capture_id": capture, "limit": 3})
            if isinstance(rows, list) and rows:
                ok(f"query returned {len(rows)} frames with references")
                detail = c.tool(
                    "frame_inspect", {"capture_id": capture, "index": rows[0]["index"]}
                )
                if detail.get("raw_hex"):
                    ok(f"raw bytes reachable ({detail['raw_bytes_total']} bytes)")
                else:
                    bad("frame_inspect returned no raw bytes")
                    failures.append(f"{label}: raw bytes")

            exported = c.tool("capture_export_pcap", {"capture_id": capture, "limit": 2000})
            if exported.get("path") and os.path.exists(exported["path"]):
                ok(f"pcap: {exported['frames']} frames -> {exported['path']}")
            else:
                bad(f"pcap export: {json.dumps(exported)[:160]}")
                failures.append(f"{label}: pcap")

            antenna = c.tool("antenna_check", {"session": session})
            src = antenna.get("source", "?")
            bal = antenna.get("capture_derived_chain_balance")
            if bal:
                print(f"       antennas ({src}): {bal['verdict']}")
            else:
                print(f"       antennas: {src}")

            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All hardware checks passed.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

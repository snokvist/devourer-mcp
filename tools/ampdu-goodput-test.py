#!/usr/bin/env python3
"""Hardware test for A-MPDU goodput: the +30% claim, measured on an witness.

The control test (`tools/ampdu-test.py`) proves the mode can be armed. This one
proves it does something: the same burst, same PHY rate, same frame size and
same deep feeder, run three ways, with an independent receiver counting what
actually arrived.

  1. plain data frames, A-MPDU off       — the baseline
  2. QoS data frames, A-MPDU off         — isolates the frame shape
  3. QoS data frames, A-MPDU on          — the aggregation gain

The transmitter is opened with `usb_agg` and every run uses `batch:true` so the
MAC gets a deep queue to aggregate from; a per-frame feed starves the engine and
would make the comparison about the feeder, not the mode. Goodput is the
witness's count of delivered payload over the burst time — delivered bytes, not
occupancy: a burst that never reaches the air cannot score.

A run where the witness hears little enough that the comparison is meaningless
is a failure, not a marginal pass. So is an A-MPDU run that does not beat both
controls at the rate the gain is claimed for — the highest one, where preamble
amortization matters. A lower rate that shows no gain is reported and not
required: per-MPDU airtime dominates there, so aggregation has little to win.
If the bench disagrees with the vendor number at the high rate, record why
rather than asserting it.

    tools/ampdu-goodput-test.py [--channel 6] [--width 20] [--frames 2000]

It NEVER passes vacuously: no A-MPDU-capable transmitter or no independent
receiver means failure, and A-MPDU is cleared before it exits.
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


def run_probe(c, tx, rx, args, qos_tid):
    res = c.tool(
        "experiment_link_probe",
        {
            "tx_session": tx,
            "rx_session": rx,
            "channel": args.channel,
            "width_mhz": args.width,
            "modes": args.modes,
            "frame_bytes": args.frame_bytes,
            "frames_per_point": args.frames,
            "batch": True,
            "qos_tid": qos_tid,
            "max_duration_ms": args.max_duration_ms,
        },
        timeout=300,
    )
    if errored(res) or not res.get("points"):
        return None, res
    points = {}
    for p in res["points"]:
        mode = p["point"].split()[0]
        points[mode] = {
            "delivery": p.get("delivery_ratio"),
            "goodput": p.get("goodput_bytes_per_sec"),
            "received": p.get("frames_received"),
            "tx_accepted": p.get("tx_accepted"),
            "tx_elapsed_ms": p.get("tx_elapsed_ms"),
            "rssi": (p.get("witnesses", {}).get("RX_PEER", {}) or {}).get("rssi_mean"),
            "note": p.get("note"),
        }
    return points, res


def print_points(label, points):
    for mode, p in points.items():
        gp = p["goodput"]
        dr = p["delivery"]
        print(
            f"    {label:<10} {mode:<9} delivered {p['received']:>5}/{p['tx_accepted']} "
            f"({dr:.3f})  goodput {gp/1e6:>7.2f} MB/s  "
            f"burst {p['tx_elapsed_ms']:.0f} ms  rssi {p['rssi']}"
        )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--width", type=int, default=20)
    ap.add_argument("--frames", type=int, default=2000)
    ap.add_argument("--frame-bytes", type=int, default=1000)
    ap.add_argument("--usb-agg", type=int, default=16)
    ap.add_argument("--modes", nargs="+", default=["MCS0/20", "MCS7/20"])
    ap.add_argument("--gain-mode", default=None,
                    help="The mode whose A-MPDU gain is required. Default: the "
                         "last of --modes (the fastest). Other modes are "
                         "reported but not required to improve.")
    ap.add_argument("--max-duration-ms", type=int, default=60000)
    ap.add_argument("--min-delivery", type=float, default=0.50,
                    help="Below this the witness did not hear the link well "
                         "enough for a meaningful comparison.")
    args = ap.parse_args()

    failures = []
    opened = []
    tx = None
    rx = None

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 2:
            print(f"Need an A-MPDU transmitter and an independent receiver. Found "
                  f"{len(devices)} adapter(s). This test needs hardware.")
            return 2

        for d in devices:
            r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"],
                                      "usb_agg": args.usb_agg}, timeout=180)
            if errored(r) or "session" not in r:
                bad(f"open {d['usb_id']} bus{d['bus']}: {r.get('_text', r)}")
                failures.append(f"open {d['usb_id']}")
                continue
            opened.append(r["session"])
            chip = r["capabilities"]["chip"]
            if tx is None:
                # A-MPDU can only be armed on a radio that is already up, and
                # opening it is not enough: a monitor start is what runs Init.
                # Stop the monitor again before the experiment, which owns its
                # own receive side and would otherwise refuse the busy session.
                started = c.tool("monitor_start",
                                 {"session": r["session"], "channel": args.channel,
                                  "width_mhz": args.width}, timeout=180)
                if errored(started):
                    print(f"  {chip} could not be brought up: "
                          f"{started.get('_text', started)[:120]}")
                else:
                    c.tool("monitor_stop", {"capture_id": started["capture_id"],
                                            "discard": True})
                    set_result = c.tool(
                        "radio_ampdu",
                        {"session": r["session"], "enabled": True, "tid": 0,
                         "max_num": 16},
                    )
                    if errored(set_result):
                        print(f"  {chip} refuses A-MPDU; still a candidate witness")
                    elif set_result.get("enabled") is True:
                        tx = r["session"]
                        print(f"  transmitter: {chip} session {tx} "
                              f"(A-MPDU tid {set_result.get('tid')} "
                              f"max {set_result.get('max_num')}, usb_agg {args.usb_agg})")
            if rx is None and r["session"] != tx:
                rx = r["session"]
                print(f"  witness:     {chip} session {rx}")

        for session in opened:
            if session not in (tx, rx):
                c.tool("radio_close", {"session": session})

        if tx is None:
            bad("no adapter accepted A-MPDU — nothing could be measured")
            for session in opened:
                c.tool("radio_close", {"session": session})
            return 1
        if rx is None:
            bad("no independent receiver — nothing could be witnessed")
            c.tool("radio_ampdu", {"session": tx, "clear": True})
            for session in opened:
                c.tool("radio_close", {"session": session})
            return 1

        try:
            conditions = [
                ("plain", None, False),
                ("qos-off", 0, False),
                ("ampdu-on", 0, True),
            ]
            results = {}
            ampdu_state = {}
            run_caveats = {}
            run_truncated = {}
            for label, qos_tid, enable in conditions:
                if enable:
                    armed = c.tool("radio_ampdu",
                                   {"session": tx, "enabled": True, "tid": qos_tid,
                                    "max_num": 16})
                    if armed.get("enabled") is not True:
                        bad(f"could not arm A-MPDU for {label}: {json.dumps(armed)[:160]}")
                        failures.append(f"arm {label}")
                        continue
                else:
                    c.tool("radio_ampdu", {"session": tx, "clear": True})
                points, raw = run_probe(c, tx, rx, args, qos_tid)
                if points is None:
                    bad(f"{label} run failed: {json.dumps(raw)[:200]}")
                    failures.append(f"run {label}")
                    continue
                results[label] = points
                ampdu_state[label] = raw.get("ampdu") or {}
                run_caveats[label] = raw.get("caveats", [])
                run_truncated[label] = bool(raw.get("truncated"))
                shape = "plain data" if qos_tid is None else f"QoS tid {qos_tid}"
                aggregated = ampdu_state[label]
                print(f"\n  {label} ({shape}, A-MPDU {'on' if enable else 'off'}) "
                      f"[result says capability={aggregated.get('capability')} "
                      f"enabled={aggregated.get('enabled')} "
                      f"tid={aggregated.get('tid')}]")
                for caveat in run_caveats[label]:
                    if "NOT aggregated" in caveat:
                        print(f"    caveat: {caveat[:120]}...")
                print_points(label, points)

            # The result must describe itself, not just the run: an un-aggregated
            # QoS run that read as an A-MPDU one would be the exact silent lie
            # this whole measurement exists to avoid.
            if "ampdu-on" in results:
                on = ampdu_state.get("ampdu-on", {})
                if (on.get("capability") == "supported" and on.get("enabled")
                        and on.get("tid") == 0):
                    ok("the A-MPDU run reports itself as armed (supported, tid 0)")
                else:
                    bad(f"the A-MPDU run does not report an armed result: {on}")
                    failures.append("ampdu-on self-description")
                if any("NOT aggregated" in c for c in run_caveats.get("ampdu-on", [])):
                    bad("the armed run carries the not-aggregated caveat")
                    failures.append("ampdu-on caveat")
            if "qos-off" in results:
                if any("NOT aggregated" in c for c in run_caveats.get("qos-off", [])):
                    ok("the A-MPDU-off control is labelled single-MPDU, as it should be")
                else:
                    bad("the A-MPDU-off control was not labelled single-MPDU")
                    failures.append("qos-off caveat")

            for label, points in results.items():
                for mode, p in points.items():
                    if p["delivery"] is None or p["delivery"] < args.min_delivery:
                        bad(f"{label}/{mode}: witness delivery {p['delivery']} below "
                            f"{args.min_delivery} — comparison not meaningful")
                        failures.append(f"{label}/{mode} delivery")

            # A truncated run can be missing modes entirely: the gain-mode
            # comparison below must not silently vanish with them.
            for label, truncated in run_truncated.items():
                if truncated:
                    bad(f"{label}: the run hit --max-duration-ms and was truncated; "
                        "the comparison is incomplete")
                    failures.append(f"{label} truncated")

            gain_mode = args.gain_mode or args.modes[-1]
            plain_gain = (results.get("plain") or {}).get(gain_mode)
            on_gain = (results.get("ampdu-on") or {}).get(gain_mode)
            if plain_gain is None or on_gain is None:
                bad(f"{gain_mode}: the A-MPDU gain claim could not be evaluated "
                    f"(plain measured={plain_gain is not None}, "
                    f"ampdu-on measured={on_gain is not None})")
                failures.append(f"{gain_mode} gain not measured")
            else:
                qos_gain = (results.get("qos-off") or {}).get(gain_mode)
                best_control = max(plain_gain["goodput"],
                                   qos_gain["goodput"] if qos_gain else plain_gain["goodput"])
                if on_gain["goodput"] > best_control * 1.05:
                    gain = (on_gain["goodput"] / best_control - 1) * 100
                    ok(f"{gain_mode}: A-MPDU goodput {on_gain['goodput']/1e6:.2f} MB/s "
                       f"vs best control {best_control/1e6:.2f} MB/s (+{gain:.1f}%)")
                else:
                    bad(f"{gain_mode}: A-MPDU goodput {on_gain['goodput']/1e6:.2f} MB/s "
                        f"did not beat the A-MPDU-off control "
                        f"{best_control/1e6:.2f} MB/s")
                    failures.append(f"{gain_mode} gain")

            # The other rates are informational: per-MPDU airtime dominates at a
            # low rate, so aggregation has little to win and no gain is required.
            for mode in args.modes:
                if mode == gain_mode:
                    continue
                plain = results.get("plain", {}).get(mode)
                on = results.get("ampdu-on", {}).get(mode)
                if not (plain and on):
                    continue
                qos = results.get("qos-off", {}).get(mode)
                best_control = max(plain["goodput"],
                                   qos["goodput"] if qos else plain["goodput"])
                if on["goodput"] > best_control * 1.05:
                    gain = (on["goodput"] / best_control - 1) * 100
                    ok(f"{mode}: A-MPDU goodput {on['goodput']/1e6:.2f} MB/s "
                       f"vs best control {best_control/1e6:.2f} MB/s (+{gain:.1f}%)")
                else:
                    print(f"  \033[33mNOTE\033[0m {mode}: no gain "
                          f"({on['goodput']/1e6:.2f} vs {best_control/1e6:.2f} MB/s). "
                          "Per-MPDU airtime dominates at this rate, so preamble "
                          "amortization has little to win; not treated as a failure.")

            c.tool("radio_ampdu", {"session": tx, "clear": True})
        finally:
            for session in opened:
                c.tool("radio_close", {"session": session})

    print("=" * 60)
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("A-MPDU goodput measured, and the gain shown on an independent witness.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

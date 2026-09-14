#!/usr/bin/env python3
"""Hardware test that an STBC request actually airs, proven by an independent decode.

The mode grammar already carries `/STBC` (`vendor/devourer/src/RadiotapBuilder.cpp`,
`parse_tx_mode_str`). What was never verified is the whole path: a transmitter whose
`GetTxCaps().stbc_ok` is true airs an STBC-flagged PPDU when the mode asks for it, and
a receiver that is NOT the transmitter decodes `stbc=1` from the radiotap/PHY status.
A radio reporting its own TX descriptor is not evidence of what left the antenna, so
the verdict here comes from a third adapter's monitor capture, inspected with
`frame_inspect`.

Needs three adapters: a transmitter, the `experiment_link_probe` peer witness, and a
separate monitor whose capture is inspected. It NEVER passes vacuously - a monitor
that flags STBC on the plain control (an unreliable bit) or never flags it on the
STBC request both fail, and the failure text says which.

    tools/stbc-test.py [--channel 6] [--width 20] [--frames 400] [--mode MCS0/20]

Every radio is closed and every capture discarded before it exits.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DVRX_MAGIC = "44565258"  # 'DVRX', the ProbeFrame tag at the MPDU header


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def open_all(c):
    opened = []
    for d in c.tool("radio_list", {}).get("devices", []):
        r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
        if r.get("_isError") or "session" not in r:
            bad(f"open {d['usb_id']} bus{d['bus']}: {str(r.get('_text', r))[:120]}")
            continue
        caps = r.get("capabilities", {})
        o = {
            "session": r["session"],
            "chip": caps.get("chip", "?"),
            "generation": caps.get("generation", "?"),
            "usb": f"{d['usb_id']} bus{d['bus']}/dev{d['address']}",
            "stbc_ok": bool(caps.get("tx", {}).get("stbc_ok")),
            "streams": caps.get("tx", {}).get("spatial_streams", 0),
        }
        opened.append(o)
        print(
            f"  {o['chip']:>10} {o['generation']:<8} session={o['session']} "
            f"stbc_ok={o['stbc_ok']} streams={o['streams']}"
        )
    return opened


def run_probe(c, tx, peer, args, mode):
    return c.tool(
        "experiment_link_probe",
        {
            "tx_session": tx,
            "rx_session": peer,
            "channel": args.channel,
            "width_mhz": args.width,
            "modes": [mode],
            "frames_per_point": args.frames,
            "batch": True,
            "max_duration_ms": max(10000, min(60000, args.frames * 4)),
        },
        timeout=300,
    )


def inspect_new(c, capture_id, seen, limit):
    """frame_inspect every not-yet-seen frame; classify the DVRX probes by stbc.

    Returns (plain, stbc, probes): whether a probe frame decoded with stbc 0
    and with stbc 1, and how many probes were seen among the new frames.
    """
    reply = c.tool("capture_query", {"capture_id": capture_id, "limit": limit})
    rows = reply if isinstance(reply, list) else reply.get("frames", [])
    saw_plain = False
    saw_stbc = False
    probes = 0
    for row in rows:
        idx = row.get("index")
        if idx is None or idx in seen:
            continue
        seen.add(idx)
        if not str(row.get("kind", "")).startswith("data") or row.get("crc_error"):
            continue
        detail = c.tool("frame_inspect", {"capture_id": capture_id, "index": idx})
        if errored(detail):
            continue
        raw = (detail.get("raw_hex") or "").lower()
        if DVRX_MAGIC not in raw:
            continue
        probes += 1
        if detail.get("stbc") is True:
            saw_stbc = True
        else:
            saw_plain = True
    return saw_plain, saw_stbc, probes


def try_orientation(c, orientation, args):
    """Run control then STBC through one (tx, peer, monitor) role assignment.

    Returns (verdict, note): True = STBC decoded and control clean, False = a
    definite failure, None = inconclusive for this orientation (try another
    monitor, or report why nothing could be decided).
    """
    tx, peer, monitor = orientation
    print(
        f"\n  orientation: tx {tx['chip']} session {tx['session']} -> "
        f"peer {peer['chip']} session {peer['session']}, "
        f"monitor {monitor['chip']} session {monitor['session']}"
    )
    started = c.tool(
        "monitor_start",
        {"session": monitor["session"], "channel": args.channel, "width_mhz": args.width},
        timeout=180,
    )
    if errored(started):
        return None, f"monitor_start failed: {str(started.get('_text', started))[:120]}"
    capture = started["capture_id"]
    # Read the whole arm, not just the newest page: the counts are part of the
    # evidence, and a newer-first sample could miss frames.
    limit = max(400, args.frames * 2 + 100)
    inspect_new(c, capture, set(), limit)  # drain anything already in the ring

    try:
        seen = set()
        decoded = {}
        for label, mode in (("control", args.mode), ("stbc", args.mode + "/STBC")):
            res = run_probe(c, tx["session"], peer["session"], args, mode)
            if errored(res) or not res.get("points"):
                return None, f"{label} run failed: {str(res.get('_text', res))[:160]}"
            point = res["points"][0]
            received = point.get("witnesses", {}).get("RX_PEER", {})
            print(
                f"       {label:<7} {mode:<16} witness frames {received.get('frames_received')} "
                f"delivery {point.get('delivery_ratio')}"
            )
            plain, stbc, probes = inspect_new(c, capture, seen, limit)
            decoded[label] = {"received": received.get("frames_received") or 0,
                              "plain": plain, "stbc": stbc, "probes": probes}
            print(
                f"               monitor decoded {probes} DVRX frame(s): "
                f"stbc=1 x{int(stbc)}, stbc=0 x{int(plain)}"
            )

        ctl, arm = decoded["control"], decoded["stbc"]
        if ctl["received"] == 0 or arm["received"] == 0:
            return None, "the peer witness heard nothing; the link, not STBC, is in question"
        if ctl["probes"] == 0 or arm["probes"] == 0:
            return None, "the monitor captured none of the probe frames"
        if ctl["stbc"]:
            return False, (
                f"monitor {monitor['chip']} decoded stbc=1 on the plain control "
                f"({ctl['probes']} probes) - this receiver's STBC bit is not trustworthy"
            )
        if arm["plain"]:
            return False, (
                f"monitor {monitor['chip']} decoded a MIX of stbc=1 and stbc=0 on the "
                f"/STBC request ({arm['probes']} probes) - the mode is not applied to "
                "every frame, or the capture spans more than this arm"
            )
        if not arm["stbc"]:
            return None, (
                f"monitor {monitor['chip']} decoded no STBC on the /STBC request "
                f"({arm['probes']} probes, all stbc=0)"
            )
        ok(
            f"{tx['chip']} -> {peer['chip']} witness: /STBC decodes on {monitor['chip']} "
            f"(stbc=1 x{arm['probes']}) while the control stays stbc=0"
        )
        return True, "STBC decoded independently"
    finally:
        c.tool("monitor_stop", {"capture_id": capture, "discard": True})


def finish(failures):
    print("=" * 60)
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("STBC verified on an independent receiver.")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--width", type=int, default=20)
    ap.add_argument("--frames", type=int, default=400)
    ap.add_argument("--mode", default="MCS0/20", help="Base mode; /STBC is appended for the arm.")
    args = ap.parse_args()

    failures = []
    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if len(devices) < 3:
            print(
                f"Need three adapters (transmitter, peer witness, monitor); found "
                f"{len(devices)}. This test needs hardware."
            )
            return 2

        print("Opened radios:")
        opened = open_all(c)
        try:
            capable = [o for o in opened if o["stbc_ok"] and o["streams"] >= 2]
            if not capable:
                bad("no opened adapter reports stbc_ok with >=2 TX chains - nothing to verify")
                return finish(["no STBC-capable transmitter"])

            # Prefer a jaguar3 transmitter. A jaguar2 TX wedges after the first
            # experiment_link_probe in a session (hardware-evidence.md, "Open: a
            # jaguar2 transmitter wedges on the second experiment_link_probe"),
            # and this test runs two arms back to back.
            def tx_pref(o):
                generation = o["generation"]
                if "jaguar3" in generation:
                    return 0
                return 1 if "jaguar" in generation else 2

            capable.sort(key=lambda o: (tx_pref(o), o["session"]))
            tx = capable[0]
            others = [o for o in opened if o["session"] != tx["session"]]
            if len(others) < 2:
                bad("fewer than two other radios; cannot separate peer witness from monitor")
                return finish(["not enough adapters"])
            print(
                f"\n  transmitter: {tx['chip']} {tx['generation']} session {tx['session']} "
                f"(stbc_ok={tx['stbc_ok']}, {tx['streams']} chains)"
            )

            notes = []
            # Prefer a Realtek monitor: it decodes the STBC/PHY flags; the
            # MT7612U may not populate them at all.
            monitors = sorted(others, key=lambda o: (0 if "jaguar" in o["generation"] else 1,
                                                     o["session"]))
            for monitor in monitors:
                peer = next(o for o in others if o["session"] != monitor["session"])
                verdict, note = try_orientation(c, (tx, peer, monitor), args)
                notes.append(f"{monitor['chip']} as monitor: {note}")
                if verdict is False:
                    failures.append(note)
                if verdict is True:
                    # Print the earlier orientations' notes too: a monitor that was
                    # rejected is part of the evidence for why this one passed.
                    for n in notes[:-1]:
                        print(f"       note: {n}")
                    return finish([])

            for n in notes:
                print(f"       note: {n}")
            if not failures:
                failures.append("STBC was not decoded by any available monitor")
            bad("no monitor orientation produced a clean STBC decode")
        finally:
            for o in opened:
                c.tool("radio_close", {"session": o["session"]})
    return finish(failures)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

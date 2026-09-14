#!/usr/bin/env python3
"""Hardware test that 5/10 MHz narrowband works end to end, TX and RX, witnessed.

Narrowband re-clocks the baseband sample rate on the same LO, so a second card's
*reported* bandwidth cannot prove anything (vendor `docs/narrowband.md`). What
this test proves is the thing that matters: a narrowband transmitter's frames are
decoded by an independent narrowband receiver, with the delivery ratio as the
evidence, at both 10 and 5 MHz. It also checks the capability gate - a 20/40/80
only adapter (the MT7612U) is REFUSED as a 5 MHz witness rather than silently
capturing at 20 MHz.

The bench's narrowband pair is a jaguar3 (RTL8822C) and a jaguar2 (RTL8822B).
The required arms transmit from the jaguar3 (multi-run safe). The reverse arm
transmits from the jaguar2 and is attempted too, but a jaguar2 transmitter
delivers only its first experiment in a chip power cycle (the OPEN finding in
`docs/hardware-evidence.md`; a close/reopen does not reset it). A zero-delivery
first attempt of that one reverse arm is therefore reported as a CAVEAT and the
reverse direction is left unverified for the run - never silently counted as a
pass, and never excused on a retry. Pass `--jaguar2-width N` to pick which
width the reverse arm covers on a cold bench.

Narrowband RX on this bench is also intermittent, so a required arm is retried
once (`--attempts`, default 2) and the retry is printed. A retry only counts if
it delivers; an arm that fails every attempt is a failure. An unexercised width
gate is a failure: the test refuses to report a clean pass when it could not
check the refusal.

    tools/narrowband-test.py [--channel 6] [--frames 300] [--jaguar2-width 5]
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def caveat(msg):
    print(f"  \033[33mCAVEAT\033[0m {msg}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def tx_pref(gen):
    if "jaguar3" in gen:
        return 0
    if "jaguar" in gen:
        return 1
    return 2


def open_radio(c, d):
    r = c.tool("radio_open", {"bus": d["bus"], "address": d["address"]}, timeout=180)
    if r.get("_isError") or "session" not in r:
        return None
    caps = r.get("capabilities", {})
    widths = [int(x) for x in caps.get("bandwidths_mhz", [])]
    return {
        "session": r["session"],
        "chip": caps.get("chip", "?"),
        "generation": caps.get("generation", "?"),
        "widths": widths,
        "narrowband": bool(caps.get("features", {}).get("narrowband")) and 5 in widths,
    }


class Run:
    """Session bookkeeping, so every opened radio is closed even on an error."""

    def __init__(self, c):
        self.c = c
        self.live = set()
        self.opened = []

    def open(self, d):
        o = open_radio(self.c, d)
        if o is not None:
            self.live.add(o["session"])
            self.opened.append(o)
        return o

    def close_all(self):
        for s in sorted(self.live):
            self.c.tool("radio_close", {"session": s})
        self.live.clear()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--frames", type=int, default=300)
    ap.add_argument("--mode", default="6M")
    ap.add_argument("--jaguar2-width", type=int, default=5, choices=[5, 10],
                    help="Width for the reverse (jaguar2 TX) arm.")
    ap.add_argument("--attempts", type=int, default=2,
                    help="Attempts per required arm; narrowband RX on this bench is "
                         "intermittent, so one clean retry is allowed. A retry only "
                         "counts if it delivers; an arm that never delivers fails.")
    args = ap.parse_args()

    failures = []
    wedged = []
    forward_ok = {}

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()
        devices = c.tool("radio_list", {}).get("devices", [])
        if not devices:
            print("No Devourer-capable adapters present. This test needs hardware.")
            return 2

        run = Run(c)
        try:
            for d in devices:
                o = run.open(d)
                if o is None:
                    bad(f"open {d['usb_id']} bus{d['bus']}: refused")
                    failures.append(f"open {d['usb_id']}")
                    continue
                print(
                    f"  {o['chip']:>10} {o['generation']:<8} session={o['session']} "
                    f"widths={o['widths']} narrowband={o['narrowband']}"
                )

            def probe(tx, rx, width):
                return c.tool(
                    "experiment_link_probe",
                    {
                        "tx_session": tx["session"],
                        "rx_session": rx["session"],
                        "channel": args.channel,
                        "width_mhz": width,
                        "modes": [args.mode],
                        "frames_per_point": args.frames,
                        "interval_us": 1000,
                        "max_duration_ms": max(10000, min(60000, args.frames * 4)),
                    },
                    timeout=300,
                )

            def report(label, res, tx, rx, width, allow_wedge, attempt):
                """Grade one attempt. Returns 'ok', 'wedge' or 'fail'.

                A bodied reply with `verification: FAILED` is a measurement, not
                a skip: a reply with no body at all, or one with no points, is
                the transport-level error case.
                """
                if "_rpc_error" in res or "_text" in res or not res.get("points"):
                    bad(f"{label}: no measurement: {str(res)[:180]}")
                    if attempt >= args.attempts:
                        failures.append(label)
                    return "fail"
                point = res["points"][0]
                delivery = point.get("delivery_ratio")
                ver = res.get("verification")
                received = point.get("frames_received") or 0
                sent = point.get("frames_sent") or 0
                accepted = point.get("tx_accepted") or 0
                print(
                    f"       {label:<28} attempt {attempt}/{args.attempts} "
                    f"verification {str(ver):<11} delivered {received}/{sent} "
                    f"delivery {delivery}"
                )
                if ver == "TX_VERIFIED" and (delivery or 0) >= 0.5:
                    ok(f"{label}: {rx['chip']} decoded {tx['chip']} at {width} MHz "
                       f"(delivery {delivery:.3f})")
                    if attempt > 1:
                        print(f"       NOTE {label}: delivered on attempt {attempt} of "
                              f"{args.attempts} (the bench has shown intermittent "
                              f"narrowband RX)")
                    return "ok"
                # A zero-delivery *first* attempt of the one jaguar2 reverse arm is
                # the documented wedge. It is a caveat, not a pass, and a retry
                # never earns it.
                if (allow_wedge and attempt == 1 and forward_ok.get(width)
                        and received == 0 and accepted >= sent >= 1):
                    caveat(
                        f"{label}: the host accepted all {sent} frames and the witness "
                        f"heard none - the known jaguar2 second-experiment wedge (the "
                        f"OPEN finding in hardware-evidence.md), not a narrowband "
                        f"failure. The reverse direction at {width} MHz is UNVERIFIED "
                        f"this run and needs a jaguar2 power cycle."
                    )
                    return "wedge"
                if attempt < args.attempts:
                    print(f"       NOTE {label}: attempt {attempt} of {args.attempts} was "
                          f"not witnessed; retrying")
                else:
                    bad(f"{label} not witnessed (verification {ver}, delivery {delivery})")
                    failures.append(f"{label} delivery")
                return "fail"

            def arm(label, tx, rx, width, allow_wedge=False):
                for attempt in range(1, args.attempts + 1):
                    verdict = report(label, probe(tx, rx, width), tx, rx, width,
                                     allow_wedge, attempt)
                    if verdict == "ok":
                        return True
                    if verdict == "wedge":
                        wedged.append(f"{width} MHz reverse")
                        return True
                return False

            nb = [o for o in run.opened if o["narrowband"]]
            if len(nb) < 2:
                bad(f"need two adapters that report 5/10 MHz; found {len(nb)} "
                    f"(of {len(run.opened)} opened). This test needs narrowband hardware.")
                return _finish(failures)
            j3 = next((o for o in nb if "jaguar3" in o["generation"]), None)
            j2 = next((o for o in nb if "jaguar2" in o["generation"]), None)
            if j3 is None or j2 is None:
                print(
                    "Need one jaguar3 and one jaguar2 narrowband radio: the jaguar3 "
                    "carries the multi-run forward arms, the jaguar2 carries the "
                    f"single reverse arm. Found {[o['generation'] for o in nb]}. "
                    "This test needs hardware."
                )
                return 2
            print(f"\n  narrowband pair: {j3['chip']} session {j3['session']} "
                  f"<-> {j2['chip']} session {j2['session']}")

            # Forward arms from the jaguar3, at each width.
            forward_ok[10] = arm("10 MHz jaguar3->jaguar2", j3, j2, 10)
            forward_ok[5] = arm("5 MHz jaguar3->jaguar2", j3, j2, 5)

            # Capability gate: a 20/40/80-only adapter must be refused, and the
            # refusal must name the width. An accepted-then-heard-nothing result
            # is a FAILED experiment, which must not read as a gate pass. A gate
            # that cannot be exercised is a failure, not a silent pass.
            incapable = next((o for o in run.opened
                              if not o["narrowband"] and 20 in o["widths"]), None)
            if incapable is None:
                bad("no 20/40/80-only adapter opened, so the 5 MHz witness gate could "
                    "not be exercised")
                failures.append("narrowband gate not exercisable")
            else:
                refused = probe(j3, incapable, 5)
                text = str(refused)
                if not refused.get("points") and "cannot use 5MHz channels" in text:
                    ok(f"{incapable['chip']} refused as a 5 MHz witness, naming the width")
                else:
                    bad(f"{incapable['chip']} was not refused for the right reason: "
                        f"{text[:180]}")
                    failures.append("narrowband gate")

            # Reverse arm last: the jaguar2 gets its one TX experiment per chip
            # power cycle here, and nothing required runs after it.
            w = args.jaguar2_width
            arm(f"{w} MHz jaguar2->jaguar3", j2, j3, w, allow_wedge=True)
        finally:
            run.close_all()

    return _finish(failures, wedged)


def _finish(failures, wedged=()):
    print("=" * 60)
    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    if wedged:
        print("VERIFIED with a caveat: 5/10 MHz forward both widths, and the "
              f"jaguar2 reverse arm was excused as the known wedge ({', '.join(wedged)}); "
              "the reverse direction is not claimed.")
    else:
        print("Narrowband 5/10 MHz TX+RX verified on independent receivers.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

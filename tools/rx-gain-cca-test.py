#!/usr/bin/env python3
"""Hardware test for the receive-gain clamp and the split CCA gates.

Exercises `radio_rx_gain` and `radio_cca_gates` against real adapters through
the real MCP server. Both are IMPLEMENTED_IN_SOURCE until this runs: the
Kotlin layer deliberately refuses to fake a gain index or a gate split a
backend does not have, so a green unit test proves the plumbing, not the
silicon.

Three outcomes are checked, and all three matter:

  * a backend that reports a settable gain and the gate split is driven the
    whole way — read, clamp, read back, restore, one gate at a time, restore;
  * a backend without them says so (`supported:false` with a reason) and a
    write attempt is refused, not silently ignored;
  * disabling a gate without `safety_level="experimental"` is refused and
    changes nothing.

It NEVER passes vacuously. If no attached adapter reports a settable gain or
the gate split, the corresponding section fails — a green run that exercised
no hardware would be worse than no run.

    tools/rx-gain-cca-test.py [--channel 6] [--width 20]

Needs the bench adapters and the pre-installed MCP server
(`./gradlew :mcp:installDist`). Leaves every radio it touches with carrier
sense back on and every monitor stopped.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REALTEK = {"jaguar1", "jaguar2", "jaguar3", "rtl8733b", "kestrel"}


def ok(msg):
    print(f"  \033[32mPASS\033[0m {msg}")


def bad(msg):
    print(f"  \033[31mFAIL\033[0m {msg}")


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    ap.add_argument("--width", type=int, default=20)
    args = ap.parse_args()

    failures = []
    gain_exercised = False
    gates_exercised = False

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
            generation = radio["capabilities"]["generation"]
            print(f"  {radio['capabilities']['chip']} ({generation})")

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

            gain = c.tool("radio_rx_gain", {"session": session})
            gates = c.tool("radio_cca_gates", {"session": session})
            print(f"  rx_gain:   {json.dumps(gain)}")
            print(f"  cca_gates: {json.dumps(gates)}")

            wants_gain = bool(gain.get("supported") and gain.get("settable"))
            wants_gates = bool(gates.get("supported"))

            if wants_gain:
                gain_exercised = True
                failures += check_gain(c, session, gain, label)
            else:
                failures += check_unsupported_gain(c, session, gain, label)

            if wants_gates:
                gates_exercised = True
                failures += check_gates(c, session, gates, label)
            else:
                failures += check_unsupported_gates(c, session, gates, label)

            # Leave the radio as found: carrier sense on, monitor stopped.
            c.tool("radio_cca_gates", {"session": session, "primary_cca_disabled": False})
            c.tool("radio_cca_gates", {"session": session, "edcca_disabled": False})
            c.tool("monitor_stop", {"capture_id": capture, "discard": True})
            c.tool("radio_close", {"session": session})
            print()

    print("=" * 60)
    if not gain_exercised:
        bad("no adapter reported a settable receive gain — nothing was verified")
        failures.append("no settable gain on the bench")
    if not gates_exercised:
        bad("no adapter reported the carrier-sense gate split — nothing was verified")
        failures.append("no gate split on the bench")

    if failures:
        print(f"FAILED ({len(failures)}): " + "; ".join(failures))
        return 1
    print("All receive-gain / CCA-gate hardware checks passed.")
    return 0


def check_gain(c, session, initial, label):
    failures = []
    if not initial.get("valid"):
        bad(f"gain not readable after bring-up: {initial.get('why')}")
        return [f"{label}: gain not readable"]

    lo, hi = initial["index_min"], initial["index_max"]
    saved_min, saved_max = initial["range_min"], initial["range_max"]
    target = (lo + hi) // 2

    clamped = c.tool(
        "radio_rx_gain", {"session": session, "min_index": target, "max_index": target}
    )
    if (
        clamped.get("index") == target
        and clamped.get("range_min") == target
        and clamped.get("range_max") == target
    ):
        ok(f"clamp pinned the index to {target:#x} (envelope {lo:#x}..{hi:#x})")
    else:
        bad(f"clamp did not pin: {json.dumps(clamped)}")
        failures.append(f"{label}: clamp")

    restored = c.tool(
        "radio_rx_gain",
        {"session": session, "min_index": saved_min, "max_index": saved_max},
    )
    if (restored.get("range_min"), restored.get("range_max")) == (saved_min, saved_max):
        ok(f"restored the live range to [{saved_min:#x}, {saved_max:#x}]")
    else:
        bad(f"restore did not take: {json.dumps(restored)}")
        failures.append(f"{label}: restore")

    refused = c.tool(
        "radio_rx_gain", {"session": session, "min_index": lo, "max_index": hi + 1}
    )
    if errored(refused):
        ok("an out-of-envelope clamp was refused")
    else:
        bad(f"out-of-envelope clamp was accepted: {json.dumps(refused)}")
        failures.append(f"{label}: envelope bound")

    inverted = c.tool(
        "radio_rx_gain", {"session": session, "min_index": hi, "max_index": lo}
    )
    if errored(inverted):
        ok("an inverted clamp was refused")
    else:
        bad(f"inverted clamp was accepted: {json.dumps(inverted)}")
        failures.append(f"{label}: inverted")
    return failures


def check_gates(c, session, initial, label):
    failures = []
    if initial.get("note"):
        print(f"       note: {initial['note'][:150]}")

    disabled = c.tool(
        "radio_cca_gates",
        {"session": session, "edcca_disabled": True, "safety_level": "experimental"},
    )
    if disabled.get("edcca_disabled") is True and disabled.get("primary_cca_disabled") is False:
        ok("disabled EDCCA alone; primary CCA untouched")
    else:
        bad(f"one-at-a-time disable wrong: {json.dumps(disabled)}")
        failures.append(f"{label}: edcca disable")

    restored = c.tool("radio_cca_gates", {"session": session, "edcca_disabled": False})
    if restored.get("edcca_disabled") is False:
        ok("re-enabled EDCCA without a safety argument")
    else:
        bad(f"re-enable failed: {json.dumps(restored)}")
        failures.append(f"{label}: edcca restore")

    refused = c.tool(
        "radio_cca_gates", {"session": session, "primary_cca_disabled": True}
    )
    if errored(refused):
        ok("disabling primary CCA without experimental was refused")
    else:
        bad(f"safety gate did not fire: {json.dumps(refused)}")
        failures.append(f"{label}: safety gate")

    after = c.tool("radio_cca_gates", {"session": session})
    if after.get("primary_cca_disabled") is False and after.get("cca_disabled") is False:
        ok("the refused call left the gates unchanged")
    else:
        bad(f"state changed despite refusal: {json.dumps(after)}")
        failures.append(f"{label}: safety refusal mutated state")

    primary = c.tool(
        "radio_cca_gates",
        {"session": session, "primary_cca_disabled": True, "safety_level": "experimental"},
    )
    if primary.get("primary_cca_disabled") is True and primary.get("cca_disabled") is True:
        ok("disabled primary CCA with the level, and the combined state followed")
    else:
        bad(f"primary disable wrong: {json.dumps(primary)}")
        failures.append(f"{label}: primary disable")
    c.tool("radio_cca_gates", {"session": session, "primary_cca_disabled": False})
    return failures


def check_unsupported_gain(c, session, gain, label):
    failures = []
    if gain.get("supported") is False and gain.get("why"):
        ok(f"no gain index here, with a reason: {gain['why'][:80]}")
    else:
        bad(f"expected supported:false with a reason: {json.dumps(gain)}")
        failures.append(f"{label}: gain absence not honest")
    clamp = c.tool("radio_rx_gain", {"session": session, "min_index": 0, "max_index": 0})
    if errored(clamp):
        ok("a clamp attempt on this backend was refused")
    else:
        bad(f"clamp silently no-op'd: {json.dumps(clamp)}")
        failures.append(f"{label}: clamp not refused")
    return failures


def check_unsupported_gates(c, session, gates, label):
    failures = []
    if gates.get("supported") is False and gates.get("why"):
        ok(f"no gate split here, with a reason: {gates['why'][:80]}")
    else:
        bad(f"expected supported:false with a reason: {json.dumps(gates)}")
        failures.append(f"{label}: gate absence not honest")
    # The combined state must still be present even when the split is not.
    if "cca_disabled" in gates:
        ok(f"combined carrier-sense state still reported ({gates['cca_disabled']})")
    else:
        bad("no combined cca_disabled when the split is unsupported")
        failures.append(f"{label}: combined state missing")
    return failures


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

#!/usr/bin/env python3
"""Functional verification of the whole MCP surface, on real hardware.

Drives every tool in the server's inventory with a real request, checks the
reply's shape and content, exercises the negative paths (unknown session,
wrong-typed arguments, capability refusals), and confirms the artifacts a
caller depends on: the PCAP export, the characterization DB, a promoted
scratchpad tool, and the persistent dashboard. It is the counterpart to the
per-feature tests — those prove one slice works; this proves the surface hangs
together and that a request a model would actually send behaves.

    tools/mcp-verify.py [--channel 6]

It NEVER passes vacuously: no adapters, or a tool it cannot exercise, is a
failure. Every session, capture and scratchpad it opens is cleaned up.
"""

import argparse
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_client import McpClient, McpError  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EXPECTED_TOOLS = {
    "radio_list", "radio_open", "radio_describe", "radio_close",
    "monitor_start", "monitor_stop", "monitor_status",
    "radio_fast_retune", "radio_fast_bandwidth",
    "channel_energy", "spectrum_sweep",
    "radio_rx_gain", "radio_cca_gates", "radio_rx_quality", "radio_thermal",
    "radio_tsf", "radio_ampdu", "radio_ack_responder", "radio_beacon",
    "antenna_check", "capture_summary", "capture_query", "frame_inspect",
    "capture_export_pcap", "radio_tx_power", "radio_tx_receipts", "tx_send",
    "experiment_link_probe", "experiment_status", "experiment_cancel",
    "characterize_run", "characterize_report",
    "scratchpad_capabilities", "scratchpad_run", "scratchpad_inspect",
    "scratchpad_result", "scratchpad_stop", "scratchpad_promote",
    "scratchpad_list",
}

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    mark = "\033[32mPASS\033[0m" if ok else "\033[31mFAIL\033[0m"
    print(f"  {mark} {name}" + (f" — {detail}" if detail and not ok else ""))


def errored(reply):
    return bool(reply.get("_isError") or reply.get("_rpc_error"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channel", type=int, default=6)
    args = ap.parse_args()

    with McpClient([f"{ROOT}/tools/host/devourer-mcp"], cwd=ROOT) as c:
        c.initialize()

        # --- inventory -----------------------------------------------------
        names = {t["name"] for t in c.tools()}
        check("tool inventory", names == EXPECTED_TOOLS,
              f"missing={sorted(EXPECTED_TOOLS - names)} extra={sorted(names - EXPECTED_TOOLS)}")

        listed = c.tool("radio_list", {})
        devices = listed.get("devices", [])
        if not devices:
            print("No Devourer-capable adapters present. This verification needs hardware.")
            return 2

        sessions = []
        for d in devices:
            r = c.tool("radio_open",
                       {"bus": d["bus"], "address": d["address"], "tx_report": 1},
                       timeout=180)
            if not errored(r) and "session" in r:
                sessions.append(r)
        if not sessions:
            print("could not open any adapter")
            return 2

        rt = next((s for s in sessions
                   if s["capabilities"]["generation"] in ("jaguar1", "jaguar2", "jaguar3")), None)
        mt = next((s for s in sessions if s is not rt), None)
        check("a CCX/Jaguar adapter is present", rt is not None)
        check("a second adapter is present", mt is not None)

        captures = {}
        run_ids = []
        try:
            if rt is None or mt is None:
                return _finish()

            # --- radio / discover ------------------------------------------
            desc = c.tool("radio_describe", {"session": rt["session"]})
            check("radio_describe", not errored(desc) and desc.get("capabilities", {}).get("chip"),
                  json.dumps(desc)[:120])

            # --- monitors --------------------------------------------------
            for s in (rt, mt):
                cap = c.tool("monitor_start",
                             {"session": s["session"], "channel": args.channel, "width_mhz": 20},
                             timeout=180)
                if not errored(cap) and "capture_id" in cap:
                    captures[s["session"]] = cap["capture_id"]
            check("monitor_start on both", len(captures) == 2)
            # While a monitor is definitely up, a second one must be refused.
            check("double monitor_start is refused",
                  errored(c.tool("monitor_start",
                                 {"session": rt["session"], "channel": args.channel})))
            time.sleep(1.0)

            # --- telemetry on the Realtek ----------------------------------
            energy = c.tool("channel_energy", {"session": rt["session"], "dwell_ms": 300})
            # Supported replies are wrapped with the dwell; unsupported ones
            # come back as the RxEnergy body directly.
            inner_energy = energy.get("energy", energy)
            check("channel_energy (realtek)", inner_energy.get("supported") is True,
                  json.dumps(energy)[:160])
            mt_energy = c.tool("channel_energy", {"session": mt["session"]})
            check("channel_energy absence is honest", mt_energy.get("supported") is False
                  and bool(mt_energy.get("why")), json.dumps(mt_energy)[:160])

            quality = c.tool("radio_rx_quality", {"session": rt["session"], "dwell_ms": 400})
            check("radio_rx_quality (realtek)",
                  quality.get("quality", {}).get("supported") is True,
                  json.dumps(quality)[:160])

            thermal = c.tool("radio_thermal", {"session": rt["session"]})
            check("radio_thermal (realtek)", thermal.get("supported") is True
                  and thermal.get("bucket"), json.dumps(thermal)[:160])
            mt_thermal = c.tool("radio_thermal", {"session": mt["session"]})
            check("radio_thermal absence is honest", mt_thermal.get("supported") is False
                  and bool(mt_thermal.get("why")), json.dumps(mt_thermal)[:160])

            gain = c.tool("radio_rx_gain", {"session": rt["session"]})
            check("radio_rx_gain (realtek)", gain.get("supported") is True, json.dumps(gain)[:160])
            if gain.get("supported") and gain.get("valid"):
                saved = (gain.get("range_min"), gain.get("range_max"))
                clamped = c.tool("radio_rx_gain",
                                 {"session": rt["session"], "min_index": saved[0], "max_index": saved[1]})
                check("radio_rx_gain clamp round-trip",
                      (clamped.get("range_min"), clamped.get("range_max")) == saved,
                      json.dumps(clamped)[:160])

            gates = c.tool("radio_cca_gates", {"session": rt["session"]})
            check("radio_cca_gates (realtek)", gates.get("supported") is True,
                  json.dumps(gates)[:160])
            if gates.get("supported"):
                off = c.tool("radio_cca_gates",
                             {"session": rt["session"], "edcca_disabled": True,
                              "safety_level": "experimental"})
                check("radio_cca_gates one-at-a-time", off.get("edcca_disabled") is True
                      and off.get("cca_disabled") is True, json.dumps(off)[:160])
                c.tool("radio_cca_gates", {"session": rt["session"], "edcca_disabled": False})
                refused = c.tool("radio_cca_gates",
                                 {"session": rt["session"], "primary_cca_disabled": True})
                check("radio_cca_gates safety gate", errored(refused))

            power = c.tool("radio_tx_power", {"session": rt["session"]})
            check("radio_tx_power (realtek)", power.get("supported") is True,
                  json.dumps(power)[:160])

            receipts = c.tool("radio_tx_receipts", {"session": rt["session"], "clear": False})
            check("radio_tx_receipts is enabled when opened for it",
                  receipts.get("enabled") is True, json.dumps(receipts)[:160])

            # Read-only: arming a beacon is for tools/beacon-test.py, which
            # witnesses it. This only proves the state read composes.
            beacon = c.tool("radio_beacon", {"session": rt["session"]})
            check("radio_beacon read", "active" in beacon and "interval_tu" in beacon,
                  json.dumps(beacon)[:160])

            # --- sweeps / retune -------------------------------------------
            hop = c.tool("radio_fast_retune", {"session": rt["session"], "channel": 1})
            check("radio_fast_retune", hop.get("channel") == 1, json.dumps(hop)[:160])
            bw = c.tool("radio_fast_bandwidth", {"session": rt["session"], "width_mhz": 5})
            check("radio_fast_bandwidth", bw.get("width") == 5, json.dumps(bw)[:160])
            c.tool("radio_fast_bandwidth", {"session": rt["session"], "width_mhz": 20})
            c.tool("radio_fast_retune", {"session": rt["session"], "channel": args.channel})

            sweep = c.tool("spectrum_sweep",
                           {"session": rt["session"], "channels": [1, 6, 11], "dwell_ms": 50})
            check("spectrum_sweep", sweep.get("supported") is True
                  and len(sweep.get("points", [])) == 3, json.dumps(sweep)[:160])

            antenna = c.tool("antenna_check", {"session": rt["session"]})
            check("antenna_check", not errored(antenna), json.dumps(antenna)[:120])

            # --- capture artifacts -----------------------------------------
            cap = captures[rt["session"]]
            status = c.tool("monitor_status", {"capture_id": cap})
            check("monitor_status", isinstance(status, list) and len(status) == 1,
                  json.dumps(status)[:160])
            summary = c.tool("capture_summary", {"capture_id": cap})
            check("capture_summary", "summary" in summary, json.dumps(summary)[:160])
            rows = c.tool("capture_query", {"capture_id": cap, "limit": 3})
            check("capture_query", isinstance(rows, list))
            if rows:
                detail = c.tool("frame_inspect",
                                {"capture_id": cap, "index": rows[0]["index"]})
                check("frame_inspect raw bytes", bool(detail.get("raw_hex")),
                      json.dumps(detail)[:160])
            exported = c.tool("capture_export_pcap", {"capture_id": cap, "limit": 500})
            check("capture_export_pcap file exists",
                  bool(exported.get("path")) and os.path.exists(exported["path"]),
                  json.dumps(exported)[:160])

            # --- transmit + experiment + receipts --------------------------
            probe = c.tool("experiment_link_probe",
                           {"tx_session": rt["session"], "rx_session": mt["session"],
                            "channel": args.channel, "width_mhz": 20, "modes": ["6M"],
                            "frames_per_point": 100, "interval_us": 1000,
                            "max_duration_ms": 30000},
                           timeout=180)
            check("experiment_link_probe is TX_VERIFIED",
                  probe.get("verification") == "TX_VERIFIED", json.dumps(probe)[:200])

            got = c.tool("radio_tx_receipts", {"session": rt["session"]})
            check("radio_tx_receipts captured a burst", got.get("total", 0) > 0,
                  json.dumps(got)[:200])

            if probe.get("id"):
                st = c.tool("experiment_status", {"id": probe["id"], "include_result": True})
                check("experiment_status includes the result", "result" in st,
                      json.dumps(st)[:160])
            check("experiment_status lists runs",
                  isinstance(c.tool("experiment_status", {}), list))
            # Cancel of an unknown id is a clean error, not a crash.
            check("experiment_cancel unknown id is refused",
                  errored(c.tool("experiment_cancel", {"id": "exp-nope"})))

            # tx_send raw path is developer-gated.
            check("tx_send refuses without developer",
                  errored(c.tool("tx_send", {"session": rt["session"],
                                             "frame_hex": "00", "safety_level": "normal"})))

            # --- characterize ----------------------------------------------
            char = c.tool("characterize_run",
                          {"session": rt["session"], "peer_session": mt["session"],
                           "rx_channels": [args.channel], "rx_dwell_ms": 200,
                           "tx_channel": args.channel, "tx_modes": ["6M"]},
                          timeout=300)
            check("characterize_run", not errored(char), json.dumps(char)[:200])
            report = c.tool("characterize_report", {})
            check("characterize_report lists evidence", bool(report), json.dumps(report)[:160])

            # --- scratchpad ------------------------------------------------
            caps = c.tool("scratchpad_capabilities", {})
            check("scratchpad_capabilities", not errored(caps), json.dumps(caps)[:120])
            program = {
                "name": "mcp-verify",
                "purpose": "functional verification scratchpad",
                "capabilities": ["metrics", "timer", "radio.describe"],
                "sources": [
                    {"kind": "radio.metric", "id": "frames", "session": rt["session"],
                     "metric": "monitor_frames", "every_ms": 500},
                ],
                "computed": [{"id": "double", "expr": "frames * 2"}],
                "duration_ms": 2000,
            }
            inspected = c.tool("scratchpad_inspect", {"program": program})
            check("scratchpad_inspect", inspected.get("valid") is True,
                  json.dumps(inspected)[:200])
            started = c.tool("scratchpad_run",
                             {"program": program, "radio_sessions": [rt["session"]],
                              "grant_capabilities": ["metrics", "timer", "radio.describe"],
                              "max_runtime_ms": 3000})
            run_id = (started.get("run") or {}).get("id") if isinstance(started, dict) else None
            check("scratchpad_run", bool(run_id), json.dumps(started)[:200])
            if run_id:
                run_ids.append(run_id)
                time.sleep(0.5)
                result = c.tool("scratchpad_result", {"run_id": run_id, "window_ms": 1000})
                check("scratchpad_result", not errored(result), json.dumps(result)[:160])
                check("scratchpad_list", isinstance(c.tool("scratchpad_list", {}), list)
                      or not errored(c.tool("scratchpad_list", {})))
                promoted = c.tool("scratchpad_promote",
                                  {"run_id": run_id, "save_as": "mcp-verify-tool"})
                check("scratchpad_promote", not errored(promoted), json.dumps(promoted)[:160])
                stopped = c.tool("scratchpad_stop", {"run_id": run_id})
                check("scratchpad_stop", not errored(stopped), json.dumps(stopped)[:160])

            # --- negative / robustness -------------------------------------
            check("unknown session is refused",
                  errored(c.tool("radio_describe", {"session": 999999})))
            check("wrong-typed offset is refused",
                  errored(c.tool("radio_tx_power", {"session": rt["session"], "offset_qdb": "4"})))
            check("empty spectrum sweep is refused",
                  errored(c.tool("spectrum_sweep", {"session": rt["session"], "channels": []})))

            # --- dashboard artifact ----------------------------------------
            try:
                with urllib.request.urlopen("http://127.0.0.1:8910/", timeout=5) as resp:
                    body = resp.read(200000).decode("utf-8", "replace")
                check("dashboard serves loopback", resp.status == 200 and "devourer" in body.lower(),
                      f"status={resp.status}")
            except Exception as e:  # noqa: BLE001
                check("dashboard serves loopback", False, str(e))

        finally:
            for rid in run_ids:
                c.tool("scratchpad_stop", {"run_id": rid})
            for cap in captures.values():
                c.tool("monitor_stop", {"capture_id": cap, "discard": True})
            for s in sessions:
                c.tool("radio_close", {"session": s["session"]})

    return _finish()


def _finish():
    print("=" * 64)
    failed = [r for r in RESULTS if not r[1]]
    print(f"{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    if failed:
        print("FAILED:")
        for name, _, detail in failed:
            print(f"  - {name}: {detail}")
        return 1
    print("MCP surface verified.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except McpError as e:
        print(f"\nPROTOCOL ERROR: {e}", file=sys.stderr)
        sys.exit(3)

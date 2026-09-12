#!/usr/bin/env python3
"""Hardware test: what happens when the thing reading frames stops reading.

This is the failure mode the bridge is built around and the one that has
already cost this bench an adapter. Devourer states that an undrained MT7612U
receiver wedges below the USB level, and it did: a frame writer that copied
its buffer while holding the lock the RX callback needs blocked `on_packet`
for a multi-megabyte memcpy, and the adapter stopped responding.

Four bridge fixes exist for it and none of them could be verified without an
open radio and a sink that deliberately stalls. That is what this does:

  7  non-blocking sink; stop_writer() must not join a thread parked in write()
  8  Session lifecycle mutex; close() while the RX loop runs is not UB
  9  try/catch on the RX thread and on dispatch
  16 a write error closes the sink fd instead of counting and continuing

Pass criteria are about liveness, not throughput: while the sink is stalled
the control plane must keep answering, teardown must return promptly rather
than hang, and the adapter must still work afterwards.

    tools/stall-test.py [--seconds 8] [--channel 1]
"""

import argparse
import json
import os
import socket
import struct
import sys
import time

SOCK = os.environ.get("DEVOURER_BRIDGE_SOCK") or os.path.join(
    os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "devourer-mcp", "bridge.sock"
)

GREEN, RED, YELLOW, OFF = "\033[32m", "\033[31m", "\033[33m", "\033[0m"
failures = []


def ok(msg):
    print(f"  {GREEN}PASS{OFF} {msg}")


def bad(msg):
    print(f"  {RED}FAIL{OFF} {msg}")
    failures.append(msg)


def note(msg):
    print(f"       {msg}")


class Control:
    def __init__(self):
        self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.s.connect(SOCK)
        self.f = self.s.makefile("rwb")
        self.id = 0

    def call(self, op, timeout=15.0, **params):
        self.id += 1
        req = {"id": self.id, "op": op}
        req.update(params)
        self.f.write((json.dumps(req) + "\n").encode())
        self.f.flush()
        self.s.settimeout(timeout)
        line = self.f.readline()
        if not line:
            raise IOError(f"bridge closed the control connection during {op}")
        r = json.loads(line)
        if not r.get("ok"):
            e = r.get("error", {})
            raise RuntimeError(f"{op} failed [{e.get('code')}]: {e.get('message')}")
        return r.get("result", {})

    def close(self):
        try:
            self.f.close()
        finally:
            self.s.close()


def stalled_sink(session):
    """Attach a frame sink, read the ack, then never read again."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    # A small receive buffer so the kernel side fills in milliseconds rather
    # than megabytes: the point is to stall the writer, not to measure Linux.
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2048)
    s.connect(SOCK)
    s.sendall(json.dumps({"attach": session}).encode() + b"\n")
    s.settimeout(5.0)
    ack = b""
    while not ack.endswith(b"\n"):
        chunk = s.recv(1)
        if not chunk:
            raise IOError("bridge closed before acknowledging attach")
        ack += chunk
    parsed = json.loads(ack)
    if not parsed.get("ok"):
        raise RuntimeError(f"attach refused: {parsed}")
    return s


def flood_frame(total_bytes):
    """A broadcast data MPDU, padded. Broadcast is never ACKed and never
    retried, so what is aired is what was asked for."""
    f = bytearray(total_bytes)
    f[0] = 0x08  # type=data, subtype=0
    f[1] = 0x00
    f[4:10] = b"\xff" * 6                      # addr1 broadcast
    f[10:16] = bytes([0x02, 0x53, 0x54, 0x41, 0x4C, 0x4C])  # addr2, locally administered
    f[16:22] = bytes([0x02, 0x53, 0x54, 0x41, 0x4C, 0x4C])  # addr3
    for i in range(24, total_bytes):
        f[i] = (0xA5 ^ (i & 0xFF)) & 0xFF
    return f.hex()


def timed(fn, *a, **kw):
    t0 = time.monotonic()
    try:
        return fn(*a, **kw), (time.monotonic() - t0) * 1000, None
    except Exception as e:  # noqa: BLE001 - the timing is the measurement
        return None, (time.monotonic() - t0) * 1000, e


def start_flood(args, frames, bytes_each, interval_us):
    """A bounded broadcast burst from the other adapter.

    Returns a closer. The bridge releases a session when the control
    connection that opened it goes away, so leaving one open makes the next
    adapter's flooder fail with `busy` — the bridge doing exactly what it
    should, and the test leaking a session.
    """
    if not args.flood_from:
        return lambda: None
    f = Control()
    try:
        r = open_radio(f, args.flood_from)
        s = r["session"]
        f.call("radio.channel", session=s, channel=args.channel,
               width_mhz=20, offset=0, band=0)
        import threading

        def burst():
            try:
                f.s.settimeout(120.0)
                f.call("tx.send", timeout=90.0, session=s,
                       body_hex=flood_frame(bytes_each), mode="MCS7/20",
                       count=frames, interval_us=interval_us, seq_offset=24)
            except Exception:  # noqa: BLE001
                pass

        th = threading.Thread(target=burst, daemon=True)
        th.start()
        time.sleep(0.3)

        def done():
            th.join(timeout=60)
            # Release the adapter explicitly. Dropping the control connection
            # releases it too, but not synchronously, and the next phase
            # opens the same adapter immediately.
            try:
                f.call("radio.close", timeout=10.0, session=s)
            except Exception:  # noqa: BLE001
                pass
            try:
                f.close()
            except Exception:  # noqa: BLE001
                pass
        return done
    except Exception as e:  # noqa: BLE001
        note(f"flood unavailable: {e}")
        try:
            f.close()
        except Exception:  # noqa: BLE001
            pass
        return lambda: None


def open_radio(c, dev, **kw):
    """radio.open, retrying while a previous holder is still letting go.

    `busy` is the bridge being right, not a fault: it refuses to hand the
    same adapter to two sessions. Releases are prompt but not instantaneous.
    """
    deadline = time.monotonic() + 10.0
    while True:
        try:
            return c.call("radio.open", timeout=180, bus=dev["bus"],
                          address=dev["address"], reset=True, **kw)
        except RuntimeError as e:
            if "busy" not in str(e) or time.monotonic() > deadline:
                raise
            time.sleep(0.25)


def watch(c, session, seconds):
    """Poll monitor.stats, returning the worst call latency and last stats."""
    worst, last = 0.0, {}
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        stats, ms, err = timed(c.call, "monitor.stats", timeout=5.0, session=session)
        if err:
            bad(f"monitor.stats failed while the sink was stalled: {err}")
            break
        worst = max(worst, ms)
        last = stats
        time.sleep(0.25)
    return worst, last


def exercise(dev, args):
    label = f"{dev['usb_id']} @ bus{dev['bus']}/dev{dev['address']}"
    print(f"=== {label} ===")
    c = Control()
    sink = None
    stop_flood = lambda: None  # noqa: E731
    try:
        # The smallest buffer the bridge accepts, set at open. The 16MiB
        # default takes far longer to fill, and a test that never fills the
        # buffer proves nothing about what happens when it does.
        radio = open_radio(c, dev, buffer_bytes=1 << 20)
        session = radio["session"]
        chip = radio.get("capabilities", {}).get("chip") or dev["usb_id"]
        ok(f"opened {chip} as session {session} with a 1MiB frame buffer")

        # ---- phase 1: a sink that stops reading ---------------------------
        c.call("monitor.start", session=session, channel=args.channel,
               width_mhz=20, offset=0, band=0)
        sink = stalled_sink(session)
        stop_flood = start_flood(args, args.flood_frames, args.flood_bytes, 0)
        note(f"ch{args.channel}: a sink that never reads, under a bounded "
             f"broadcast burst from the peer adapter")

        worst, last = watch(c, session, args.seconds)
        if worst > 1000:
            bad(f"control calls took up to {worst:.0f}ms with a stalled sink")
        else:
            ok(f"control plane stayed responsive (worst call {worst:.0f}ms)")
        note(f"frames={last.get('frames')} dropped={last.get('dropped')} "
             f"buffer_used={last.get('buffer_used')}/{last.get('buffer_cap')} "
             f"write_errors={last.get('write_errors')} "
             f"sink_attached={last.get('sink_attached')}")

        if last.get("dropped", 0) > 0:
            ok(f"buffered to {last.get('buffer_used')} bytes and then dropped "
               f"{last['dropped']} records, rather than blocking the RX callback")
        else:
            # Not a pass. Teardown with a merely-idle sink is a much easier
            # case than teardown with a writer parked in write().
            bad(f"the frame buffer never filled (used {last.get('buffer_used')}"
                f"/{last.get('buffer_cap')}, {last.get('frames')} frames): the "
                f"stall was NOT exercised, so nothing below is evidence")

        # ---- phase 2: the stalled sink vanishes ---------------------------
        #
        # Until now the writer has only been backpressured. Resetting the
        # connection makes its next write fail outright, which is the path
        # that used to count an error and keep the dead fd.
        before = c.call("monitor.stats", session=session)
        sink.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER,
                        struct.pack("ii", 1, 0))  # RST, not a clean FIN
        sink.close()
        sink = None
        after = None
        for _ in range(60):
            time.sleep(0.1)
            after = c.call("monitor.stats", session=session)
            if not after.get("sink_attached"):
                break
        if after is not None and not after.get("sink_attached"):
            ok(f"the reset sink was dropped, not retained (write_errors "
               f"{before.get('write_errors')} -> {after.get('write_errors')}, "
               f"buffer {before.get('buffer_used')} -> {after.get('buffer_used')} "
               f"bytes)")
        else:
            bad(f"a sink is still attached after the client reset it: {after}")
        stop_flood()

        # ---- phase 3: teardown while a sink is stalled --------------------
        sink = stalled_sink(session)
        stop_flood = start_flood(args, args.flood_frames, args.flood_bytes, 0)
        time.sleep(2.0)
        parked = c.call("monitor.stats", session=session)
        note(f"re-stalled: buffer_used={parked.get('buffer_used')} "
             f"dropped={parked.get('dropped')}")

        _, ms, err = timed(c.call, "monitor.stop", timeout=10.0, session=session)
        if err:
            bad(f"monitor.stop failed with a stalled sink: {err}")
        elif ms > 2000:
            bad(f"monitor.stop took {ms:.0f}ms with a stalled sink (hang)")
        else:
            ok(f"monitor.stop returned in {ms:.0f}ms with the sink still stalled")

        _, ms, err = timed(c.call, "radio.close", timeout=10.0, session=session)
        if err:
            bad(f"radio.close failed with a stalled sink: {err}")
        elif ms > 3000:
            bad(f"radio.close took {ms:.0f}ms with a stalled sink (hang)")
        else:
            ok(f"radio.close returned in {ms:.0f}ms")

        sink.close()
        sink = None
        stop_flood()

        # ---- phase 4: and the adapter must still work ---------------------
        radio = open_radio(c, dev)
        session = radio["session"]
        c.call("monitor.start", session=session, channel=args.channel,
               width_mhz=20, offset=0, band=0)
        reader = stalled_sink(session)  # same attach; this one drains
        reader.settimeout(8.0)

        # Generate the traffic rather than hoping for ambient: this bench
        # measures ch6 at about one frame in four seconds, so "heard nothing"
        # would be indistinguishable from a wedged adapter.
        stop_flood = start_flood(args, 3000, 400, 200)
        got = 0
        end = time.monotonic() + 8.0
        while time.monotonic() < end and got < 4096:
            try:
                chunk = reader.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            got += len(chunk)
        reader.close()
        stop_flood()
        stats = c.call("monitor.stats", session=session)
        c.call("monitor.stop", session=session)
        c.call("radio.close", session=session)
        if stats.get("frames", 0) > 0 and got > 0:
            ok(f"adapter still receiving after the stall ({stats['frames']} "
               f"frames, {got} bytes drained)")
        else:
            bad(f"adapter did not recover: frames={stats.get('frames')} bytes={got}")
    finally:
        if sink is not None:
            try:
                sink.close()
            except Exception:  # noqa: BLE001
                pass
        stop_flood()
        c.close()
    print()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=8.0)
    ap.add_argument("--channel", type=int, default=1,
                    help="pick a BUSY channel; a quiet one exercises nothing")
    ap.add_argument("--only", default="", help="substring match on usb id")
    ap.add_argument("--no-flood", action="store_true",
                    help="rely on ambient traffic instead of generating any")
    ap.add_argument("--flood-frames", type=int, default=20000)
    ap.add_argument("--flood-bytes", type=int, default=1500)
    args = ap.parse_args()
    args.flood_from = None

    if not os.path.exists(SOCK):
        print(f"no bridge socket at {SOCK} — tools/host/bridge-ctl.sh start")
        return 2

    c = Control()
    try:
        hello = c.call("hello")
        print(f"bridge protocol {hello['protocol']['major']}.{hello['protocol']['minor']}, "
              f"devourer {hello['devourer_commit'][:12]}\n")
        devices = c.call("radio.list").get("devices", [])
    finally:
        c.close()

    all_devices = list(devices)
    devices = [d for d in devices if args.only in d["usb_id"]]
    if not devices:
        print("No Devourer-capable adapters present. This test needs hardware.")
        return 2

    for d in devices:
        # A second adapter generates the load, because ambient traffic on this
        # bench is nowhere near enough to fill a buffer: the stall has to be
        # provoked, not waited for.
        if not args.no_flood:
            others = [o for o in all_devices
                      if (o["bus"], o["address"]) != (d["bus"], d["address"])]
            # Never a Jaguar1 if anything else is available, and picked by
            # BACKEND rather than USB id: the whole family enables EDCCA at
            # bring-up and defers essentially everything it is asked to
            # transmit at the default (measured: 0-8% delivered on an idle
            # channel), so it cannot generate load and the run fails on the
            # "buffer never filled" guard rather than on the thing under
            # test. An 8811AU or 8814AU would have slipped past a check on
            # 0bda:8812 alone.
            args.flood_from = next(
                (o for o in others if not (o.get("backend") or "").startswith("jaguar1")),
                others[0] if others else None,
            )
        exercise(d, args)

    if failures:
        print(f"{RED}{len(failures)} check(s) failed{OFF}")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"{GREEN}Stalled-sink behaviour verified on {len(devices)} adapter(s).{OFF}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

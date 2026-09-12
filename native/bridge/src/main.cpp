/* devourer-bridge — the narrow native boundary.
 *
 * One process owning libusb and every open radio, speaking a small versioned
 * protocol over a Unix socket. The Kotlin runtime drives it; it never links
 * devourer, never shares an address space with the USB code, and therefore
 * survives anything the radio layer does to itself.
 *
 *   devourer-bridge --socket /run/user/1000/devourer-mcp/bridge.sock
 *
 * A client's FIRST LINE decides what kind of connection it is:
 *   {"attach":<session>}  -> frame sink; the bridge then writes binary records
 *   anything else         -> control connection; that line is the first request
 *
 * Everything here is deliberately dumb. Policy — which channel is legal, how
 * long an experiment may transmit, what counts as verified — lives in Kotlin.
 * The bridge's job is to be a faithful, crash-isolated pair of hands. */

#include <arpa/inet.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <ctime>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Devices.h"
#include "Json.h"
#include "Protocol.h"
#include "RadiotapBuilder.h"
#include "Session.h"
#include "TxMode.h"

namespace bridge {
namespace {

std::atomic<bool> g_stop{false};
std::mutex g_mu;
std::map<uint32_t, std::shared_ptr<Session>> g_sessions;
uint32_t g_next_session = 1;

/* Which control connection opened each session.
 *
 * A session outliving the client that created it strands the adapter: the chip
 * stays claimed and powered, and the next client gets BUSY from a process that
 * no longer exists. Crash isolation is the whole point of running the bridge
 * separately, so the bridge has to survive a client dying — but it must not
 * keep holding that client's hardware. Sessions are therefore owned by their
 * creating control connection and released when it goes away. */
std::map<uint32_t, uint64_t> g_session_owner;
std::atomic<uint64_t> g_next_conn{1};
thread_local uint64_t t_conn_id = 0;

std::string g_devourer_commit = "unknown";

/* Bounded so a local client cannot exhaust threads or fds by looping connect().
 * Each connection costs a thread (8 MiB of stack VA) and up to a 1 MiB line
 * buffer; at the thread limit the std::thread constructor throws. */
std::atomic<int> g_connections{0};
constexpr int kMaxConnections = 32;

void serve_connection_inner(int fd);
void wake_accept_loop();

/* --- small helpers ------------------------------------------------------- */

bool decode_hex(const std::string &s, std::vector<uint8_t> &out) {
  if (s.size() % 2 != 0)
    return false;
  out.clear();
  out.reserve(s.size() / 2);
  auto nib = [](char c) -> int {
    if (c >= '0' && c <= '9')
      return c - '0';
    if (c >= 'a' && c <= 'f')
      return c - 'a' + 10;
    if (c >= 'A' && c <= 'F')
      return c - 'A' + 10;
    return -1;
  };
  for (size_t i = 0; i < s.size(); i += 2) {
    const int hi = nib(s[i]), lo = nib(s[i + 1]);
    if (hi < 0 || lo < 0)
      return false;
    out.push_back(static_cast<uint8_t>((hi << 4) | lo));
  }
  return true;
}

bool decode_b64(const std::string &s, std::vector<uint8_t> &out) {
  auto val = [](char c) -> int {
    if (c >= 'A' && c <= 'Z')
      return c - 'A';
    if (c >= 'a' && c <= 'z')
      return c - 'a' + 26;
    if (c >= '0' && c <= '9')
      return c - '0' + 52;
    if (c == '+')
      return 62;
    if (c == '/')
      return 63;
    return -1;
  };
  out.clear();
  int acc = 0, bits = 0;
  for (char c : s) {
    if (c == '=' || c == '\n' || c == '\r' || c == ' ')
      continue;
    const int v = val(c);
    if (v < 0)
      return false;
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push_back(static_cast<uint8_t>((acc >> bits) & 0xff));
    }
  }
  return true;
}

/* MHz -> devourer's ChannelWidth_t. Taking MHz on the wire keeps the protocol
 * readable and insulates clients from an enum that is an implementation
 * detail; an unknown width is an error, never a silent fallback to 20. */
bool width_from_mhz(int mhz, ChannelWidth_t &out) {
  switch (mhz) {
  case 5:
    out = CHANNEL_WIDTH_5;
    return true;
  case 10:
    out = CHANNEL_WIDTH_10;
    return true;
  case 20:
    out = CHANNEL_WIDTH_20;
    return true;
  case 40:
    out = CHANNEL_WIDTH_40;
    return true;
  case 80:
    out = CHANNEL_WIDTH_80;
    return true;
  case 160:
    out = CHANNEL_WIDTH_160;
    return true;
  default:
    return false;
  }
}

std::shared_ptr<Session> find_session(const Json &req, std::string &err) {
  if (!req.at("session").is_number()) {
    err = "missing or non-numeric 'session'";
    return nullptr;
  }
  const auto id = static_cast<uint32_t>(req.at("session").integer());
  std::lock_guard<std::mutex> lk(g_mu);
  auto it = g_sessions.find(id);
  if (it == g_sessions.end()) {
    err = "no session " + std::to_string(id);
    return nullptr;
  }
  return it->second;
}

/* Hard wall-clock ceiling on one tx.send, enforced inside the loop. */
constexpr uint64_t kTxBudgetNs = 30ull * 1000000000ull;

uint64_t now_monotonic_ns() {
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
         static_cast<uint64_t>(ts.tv_nsec);
}

/* Sleep until an ABSOLUTE monotonic deadline.
 *
 * A relative nanosleep per frame is the obvious implementation and it is wrong
 * for pacing: each call sleeps *at least* the requested time, and the wakeup
 * latency is added fresh every iteration rather than absorbed. Measured on this
 * bench, a 200-frame burst asking for 1 ms spacing took 2226 ms instead of
 * 200 ms — an 11x overshoot that would have been silently attributed to the
 * radio.
 *
 * Scheduling against a fixed start time makes the error non-cumulative: a late
 * wakeup shortens the next sleep instead of pushing every later frame back. */
void sleep_until_ns(uint64_t deadline_ns) {
  timespec ts{static_cast<time_t>(deadline_ns / 1000000000ull),
              static_cast<long>(deadline_ns % 1000000000ull)};
  while (::clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, nullptr) == EINTR) {
  }
}

Json ok(Json result) { return Json().set("ok", true).set("result", result); }

Json fail(const std::string &code, const std::string &message) {
  return Json().set("ok", false).set(
      "error", Json().set("code", code).set("message", message));
}

/* --- ops ----------------------------------------------------------------- */

Json op_hello() {
  Json r;
  r.set("protocol", Json()
                        .set("major", kProtocolVersionMajor)
                        .set("minor", kProtocolVersionMinor));
  r.set("devourer_commit", g_devourer_commit);
  r.set("frame_record_bytes", static_cast<int>(sizeof(FrameRecord)));
  Json backends = Json::array();
  for (const auto &b : compiled_backends())
    backends.push(Json()
                      .set("name", b.name)
                      .set("chips", b.chips)
                      .set("compiled", b.compiled));
  r.set("backends", backends);
  return r;
}

Json device_json(const DeviceInfo &d) {
  char idbuf[16];
  std::snprintf(idbuf, sizeof idbuf, "%04x:%04x", d.vid, d.pid);
  const char *src = d.id_source == IdSource::UsbId      ? "usb_id"
                    : d.id_source == IdSource::Candidate ? "probe_required"
                                                         : "none";
  Json j;
  j.set("bus", d.bus)
      .set("address", d.address)
      .set("port_path", d.port_path)
      .set("usb_id", std::string{idbuf})
      .set("vid", d.vid)
      .set("pid", d.pid)
      .set("speed", speed_name(d.speed))
      .set("product", d.product)
      .set("serial", d.serial)
      .set("kernel_driver", d.kernel_driver)
      .set("identification", src)
      .set("backend", d.backend)
      .set("variant", d.variant)
      .set("backend_compiled_in", d.compiled_in);
  return j;
}

Json op_radio_list(const Json &req) {
  Json list = Json::array();
  for (const auto &d : enumerate_devices(req.at("all").boolean(false)))
    list.push(device_json(d));
  Json r;
  r.set("devices", list);
  /* Say out loud what enumeration can and cannot settle, so a caller never
   * mistakes "probe_required" for an identification. */
  r.set("note",
        "identification=usb_id came from a static VID:PID table in the "
        "vendored devourer source. identification=probe_required means the "
        "chip is dispatched on a SYS_CFG2 chip-id read, which needs "
        "radio.open — the backend is NOT known until then.");
  return r;
}

/* Range-check BEFORE narrowing. `bus: 257` silently became bus 1, so the bridge
 * opened, reset and claimed a different physical adapter than the caller named
 * — and reported success. On a bench with a ground station on the other radio
 * that is a live link torn down by a typo, with a self-consistent evidence
 * trail. Same for channel: 1036 became channel 12. */
bool ranged(const Json &req, const char *key, int64_t lo, int64_t hi,
            int64_t &out, std::string &err) {
  const int64_t v = req.at(key).integer(lo - 1);
  if (v < lo || v > hi) {
    err = std::string(key) + " must be " + std::to_string(lo) + ".." +
          std::to_string(hi);
    return false;
  }
  out = v;
  return true;
}

Json op_radio_open(const Json &req) {
  OpenOptions o;
  if (!req.at("bus").is_number() || !req.at("address").is_number())
    return fail("bad_request", "bus and address are required");
  std::string err;
  int64_t bus = 0, addr = 0;
  if (!ranged(req, "bus", 0, 255, bus, err) ||
      !ranged(req, "address", 0, 255, addr, err))
    return fail("bad_request", err);
  o.bus = static_cast<uint8_t>(bus);
  o.address = static_cast<uint8_t>(addr);
  o.reset = req.at("reset").boolean(true);
  o.noise_floor = req.at("noise_floor").boolean(false);
  o.adaptive_gain = req.at("adaptive_gain").boolean(false);
  if (req.at("buffer_bytes").is_number()) {
    int64_t v = 0;
    if (!ranged(req, "buffer_bytes", 1 << 20, 256LL << 20, v, err))
      return fail("bad_request", err);
    o.buffer_bytes = static_cast<size_t>(v);
  }
  if (req.at("max_frame_bytes").is_number()) {
    int64_t v = 0;
    if (!ranged(req, "max_frame_bytes", 64, 65535, v, err))
      return fail("bad_request", err);
    o.max_frame_bytes = static_cast<uint32_t>(v);
  }

  uint32_t id;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    id = g_next_session++;
  }
  std::string code, msg;
  auto s = Session::open(id, o, code, msg);
  if (!s)
    return fail(code, msg);
  Json desc = s->describe();
  {
    std::lock_guard<std::mutex> lk(g_mu);
    g_sessions[id] = std::move(s);
    g_session_owner[id] = t_conn_id;
  }
  return ok(desc);
}

Json op_radio_describe(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  return ok(s->describe());
}

Json op_radio_close(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  {
    std::lock_guard<std::mutex> lk(g_mu);
    g_sessions.erase(s->id());
    g_session_owner.erase(s->id());
  }
  s->close();
  return ok(Json().set("closed", s->id()));
}

Json op_sessions() {
  Json list = Json::array();
  std::lock_guard<std::mutex> lk(g_mu);
  for (auto &kv : g_sessions)
    list.push(Json()
                  .set("session", kv.first)
                  .set("usb_id", device_json(kv.second->device()).at("usb_id"))
                  .set("brought_up", kv.second->is_up())
                  .set("monitoring", kv.second->is_monitoring()));
  return ok(Json().set("sessions", list));
}

bool channel_from(const Json &req, SelectedChannel &ch, std::string &err) {
  if (!req.at("channel").is_number()) {
    err = "missing 'channel'";
    return false;
  }
  ChannelWidth_t w;
  const int mhz = static_cast<int>(req.at("width_mhz").integer(20));
  if (!width_from_mhz(mhz, w)) {
    err = "unsupported width_mhz " + std::to_string(mhz) +
          " (use 5, 10, 20, 40, 80 or 160)";
    return false;
  }
  const int64_t chan = req.at("channel").integer(-1);
  const int64_t off = req.at("offset").integer(0);
  const int64_t band = req.at("band").integer(0);
  if (chan < 0 || chan > 255) {
    err = "channel must be 0..255";
    return false;
  }
  if (off < 0 || off > 255 || band < 0 || band > 255) {
    err = "offset and band must be 0..255";
    return false;
  }
  ch.Channel = static_cast<uint8_t>(chan);
  ch.ChannelWidth = w;
  ch.ChannelOffset = static_cast<uint8_t>(off);
  ch.Band = static_cast<uint8_t>(band);
  return true;
}

Json op_monitor_start(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  SelectedChannel ch{};
  if (!channel_from(req, ch, err))
    return fail("bad_request", err);
  if (!s->bring_up(ch, err))
    return fail("bring_up_failed", err);
  if (!s->start_monitor(err))
    return fail("monitor_failed", err);
  return ok(Json()
                .set("session", s->id())
                .set("channel", ch.Channel)
                .set("width_mhz", req.at("width_mhz").integer(20))
                .set("monitoring", true));
}

Json op_monitor_stop(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  s->stop_monitor();
  return ok(Json().set("session", s->id()).set("monitoring", false));
}

Json op_radio_rx_paths(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  return ok(s->rx_paths_json());
}

Json op_radio_rx_gain(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  if (req.at("min_index").is_number() || req.at("max_index").is_number()) {
    int64_t lo = 0, hi = 0;
    if (!ranged(req, "min_index", 0, 127, lo, err) ||
        !ranged(req, "max_index", 0, 127, hi, err))
      return fail("bad_request", err);
    if (!s->set_rx_gain(static_cast<int>(lo), static_cast<int>(hi), err))
      return fail("unsupported", err);
  }
  return ok(s->rx_gain_json());
}

Json op_radio_rx_energy(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  /* Default false: the histogram arms a ~2 ms measurement window, which
   * dominates a call that is otherwise a handful of register reads. */
  return ok(s->rx_energy_json(req.at("with_nhm").boolean(false)));
}

Json op_radio_tx_stats(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  return ok(s->tx_stats_json());
}

Json op_radio_cca(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  if (!req.at("disabled").is_null() && req.at("disabled").type() != Json::Type::Bool)
    return fail("bad_request", "'disabled' must be a boolean");
  const bool disabled = req.at("disabled").boolean(false);
  if (!s->set_cca(disabled, err))
    return fail("cca_failed", err);
  Json r;
  r.set("session", s->id()).set("cca_disabled", disabled);
  if (disabled)
    r.set("warning",
          "Carrier sense is OFF: this radio will now transmit without listening "
          "first. Antisocial on any shared channel — re-enable it as soon as "
          "the measurement that needed it is done.");
  return ok(r);
}

Json op_monitor_stats(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  return ok(s->stats_json());
}

Json op_radio_channel(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  SelectedChannel ch{};
  if (!channel_from(req, ch, err))
    return fail("bad_request", err);
  if (!s->bring_up(ch, err)) /* retunes when already up */
    return fail("retune_failed", err);
  return ok(Json().set("session", s->id()).set("channel", ch.Channel));
}

/* Assemble the frame to air.
 *
 * Two paths, deliberately unequal in ceremony:
 *
 *   NORMAL      `mode` (a TxMode spec such as "MCS5/40/SGI") + `body_hex` (the
 *               802.11 MPDU). The bridge builds the radiotap header with
 *               devourer's own build_stream_radiotap, so the wire-format rules
 *               send_packet depends on — a 13-byte header keeps the legacy/HT
 *               path, 22 bytes selects VHT — live in one place instead of being
 *               re-derived by every caller.
 *
 *   PRIVILEGED  `frame_hex` / `frame_b64`: a complete radiotap header plus MPDU,
 *               byte for byte. The escape hatch for development and for shapes
 *               the builder cannot express. Never the normal interface.
 */
bool build_tx_frame(const Json &req, std::vector<uint8_t> &out,
                    std::string &mode_used, size_t &body_at, std::string &err) {
  const bool raw = req.at("frame_hex").is_string() || req.at("frame_b64").is_string();
  const bool structured = req.at("body_hex").is_string();
  if (raw && structured) {
    err = "give either frame_hex/frame_b64 (raw, radiotap included) or "
          "body_hex + mode (structured) — not both";
    return false;
  }
  if (raw) {
    mode_used = "raw";
    body_at = 0; /* the caller supplied radiotap itself, so offsets are absolute */
    if (req.at("frame_hex").is_string()) {
      if (!decode_hex(req.at("frame_hex").str(), out)) {
        err = "frame_hex is not valid hex";
        return false;
      }
    } else if (!decode_b64(req.at("frame_b64").str(), out)) {
      err = "frame_b64 is not valid base64";
      return false;
    }
    if (out.empty()) {
      err = "empty frame";
      return false;
    }
    return true;
  }
  if (!structured) {
    err = "one of body_hex (with optional mode) or frame_hex/frame_b64 is required";
    return false;
  }
  std::vector<uint8_t> body;
  if (!decode_hex(req.at("body_hex").str(), body)) {
    err = "body_hex is not valid hex";
    return false;
  }
  if (body.size() < 10) {
    err = "body_hex is shorter than an 802.11 header (give the MPDU, not the "
          "payload alone)";
    return false;
  }
  const std::string spec = req.at("mode").str("6M");
  const devourer::TxMode mode = devourer::parse_tx_mode_str(spec);
  mode_used = spec;
  out = devourer::build_stream_radiotap(mode);
  body_at = out.size();
  out.insert(out.end(), body.begin(), body.end());
  return true;
}

Json op_tx_send(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);

  std::vector<uint8_t> frame;
  std::string mode_used;
  size_t body_at = 0;
  if (!build_tx_frame(req, frame, mode_used, body_at, err))
    return fail("bad_request", err);

  const int64_t count = req.at("count").integer(1);
  if (count < 1 || count > 100000)
    return fail("bad_request", "count must be 1..100000");
  const int64_t interval_us = req.at("interval_us").integer(0);
  if (interval_us < 0 || interval_us > 1000000)
    return fail("bad_request", "interval_us must be 0..1000000");

  /* Bounded by construction: the bridge will not sit in an unbounded send loop.
   * count*interval is capped so a paced burst cannot silently become a
   * multi-minute transmission from a single request. */
  /* The budget is wall clock, not count x interval. The product is ZERO when
   * interval_us is 0, so `count: 100000, interval_us: 0` used to sail past this
   * guard and emit an unbounded burst that nothing could interrupt. */
  const int64_t budget_us = count * interval_us;
  if (budget_us > kTxBudgetNs / 1000)
    return fail("bad_request",
                "count x interval_us exceeds the 30 s per-request transmit "
                "budget; split it or run it as an experiment");

  /* A per-frame sequence stamp, so a receiver can tell our frames apart and
   * count losses. Written little-endian at `seq_offset`.
   *
   * The offset is relative to WHAT THE CALLER GAVE US: the MPDU on the
   * structured path (where we prepended the radiotap header ourselves), the
   * whole buffer on the raw path. Getting this wrong is silent and ruinous —
   * stamping at the wrong place leaves every frame carrying sequence 0, and a
   * burst that delivered perfectly reports one frame received and the rest as
   * duplicates. */
  const int64_t seq_offset = req.at("seq_offset").integer(-1);
  const size_t stamp_at = body_at + static_cast<size_t>(seq_offset < 0 ? 0 : seq_offset);
  const bool stamp = seq_offset >= 0 && stamp_at + 4 <= frame.size();
  if (seq_offset >= 0 && !stamp)
    return fail("bad_request",
                "seq_offset+4 is past the end of the frame (offsets are "
                "relative to body_hex on the structured path)");

  const uint64_t t0 = now_monotonic_ns();
  int64_t sent = 0;
  int64_t late = 0;          /* frames whose slot had already passed */
  uint64_t max_late_ns = 0;  /* worst single miss */
  uint64_t send_ns = 0;      /* time inside send_packet, summed */
  for (int64_t i = 0; i < count; ++i) {
    if (interval_us > 0 && i > 0) {
      const uint64_t due = t0 + static_cast<uint64_t>(i) *
                                    static_cast<uint64_t>(interval_us) * 1000ull;
      const uint64_t before = now_monotonic_ns();
      if (before >= due) {
        ++late;
        max_late_ns = std::max(max_late_ns, before - due);
      } else {
        sleep_until_ns(due);
      }
    }
    if (stamp) {
      const uint32_t v = static_cast<uint32_t>(i);
      std::memcpy(frame.data() + stamp_at, &v, 4);
    }
    if (g_stop) {
      err = "bridge is shutting down";
      break;
    }
    if (now_monotonic_ns() - t0 > kTxBudgetNs) {
      err = "30 s transmit budget reached";
      break;
    }
    const uint64_t s0 = now_monotonic_ns();
    const bool sent_ok = s->send_frame(frame.data(), frame.size(), err);
    send_ns += now_monotonic_ns() - s0;
    if (!sent_ok)
      break;
    ++sent;
  }
  const uint64_t elapsed_ns = now_monotonic_ns() - t0;

  Json r;
  r.set("session", s->id())
      .set("requested", count)
      .set("sent", sent)
      .set("mode", mode_used)
      .set("frame_bytes", static_cast<int64_t>(frame.size()))
      .set("elapsed_ns", elapsed_ns)
      .set("seq_stamped", stamp)
      .set("radiotap_bytes", static_cast<int64_t>(body_at))
      /* Frames the loop could not start on time. Nonzero means the requested
       * spacing was tighter than this host and adapter can sustain, so the
       * burst is not the cadence that was asked for — a caller measuring
       * against timing must know that rather than infer it from elapsed_ns. */
      .set("late_frames", late)
      .set("max_late_us", static_cast<int64_t>(max_late_ns / 1000))
      .set("send_ns_total", send_ns);
  if (sent > 0 && elapsed_ns > 0)
    r.set("frames_per_second",
          static_cast<double>(sent) * 1e9 / static_cast<double>(elapsed_ns));
  if (sent != count)
    r.set("stopped_because", err);
  /* Say it here, not only in the tool description: a caller reading this
   * result should not be able to mistake acceptance for arrival. */
  r.set("note",
        "`sent` counts frames the TX path accepted, which is NOT evidence they "
        "reached the air. Confirm on an independent receiver before treating "
        "this as TX_VERIFIED.");
  return ok(r);
}

/* --- dispatch ------------------------------------------------------------ */

Json dispatch(const Json &req) {
  const std::string op = req.at("op").str();
  if (op == "hello")
    return ok(op_hello());
  if (op == "radio.list")
    return ok(op_radio_list(req));
  if (op == "radio.open")
    return op_radio_open(req);
  if (op == "radio.describe")
    return op_radio_describe(req);
  if (op == "radio.close")
    return op_radio_close(req);
  if (op == "radio.channel")
    return op_radio_channel(req);
  if (op == "sessions")
    return op_sessions();
  if (op == "monitor.start")
    return op_monitor_start(req);
  if (op == "monitor.stop")
    return op_monitor_stop(req);
  if (op == "monitor.stats")
    return op_monitor_stats(req);
  if (op == "radio.rx_paths")
    return op_radio_rx_paths(req);
  if (op == "radio.tx_stats")
    return op_radio_tx_stats(req);
  if (op == "radio.rx_energy")
    return op_radio_rx_energy(req);
  if (op == "radio.rx_gain")
    return op_radio_rx_gain(req);
  if (op == "radio.cca")
    return op_radio_cca(req);
  if (op == "tx.send")
    return op_tx_send(req);
  if (op == "shutdown") {
    g_stop = true;
    wake_accept_loop();
    return ok(Json().set("stopping", true));
  }
  return fail("unknown_op", "no such op: " + op);
}

/* --- connections --------------------------------------------------------- */

bool read_line(int fd, std::string &line) {
  line.clear();
  char c;
  for (;;) {
    const ssize_t n = ::read(fd, &c, 1);
    if (n == 0)
      return !line.empty();
    if (n < 0) {
      if (errno == EINTR)
        continue;
      return false;
    }
    if (c == '\n')
      return true;
    line += c;
    if (line.size() > (1u << 20))
      return false; /* a control line this long is a client bug */
  }
}

bool write_all(int fd, const std::string &s) {
  size_t off = 0;
  while (off < s.size()) {
    const ssize_t w = ::write(fd, s.data() + off, s.size() - off);
    if (w > 0) {
      off += static_cast<size_t>(w);
      continue;
    }
    if (w < 0 && errno == EINTR)
      continue;
    return false;
  }
  return true;
}

/* Release every session this control connection still owns. */
void release_sessions_of(uint64_t conn) {
  std::vector<std::shared_ptr<Session>> doomed;
  {
    std::lock_guard<std::mutex> lk(g_mu);
    for (auto it = g_session_owner.begin(); it != g_session_owner.end();) {
      if (it->second != conn) {
        ++it;
        continue;
      }
      auto s = g_sessions.find(it->first);
      if (s != g_sessions.end()) {
        doomed.push_back(s->second);
        g_sessions.erase(s);
      }
      it = g_session_owner.erase(it);
    }
  }
  for (auto &s : doomed) {
    std::fprintf(stderr,
                 "control connection gone — releasing orphaned session %u\n",
                 s->id());
    s->close();
  }
}

void serve_control(int fd, std::string first_line) {
  t_conn_id = g_next_conn.fetch_add(1);
  std::string line = std::move(first_line);
  for (;;) {
    Json req;
    std::string perr;
    Json resp;
    if (!Json::parse(line, req, perr)) {
      resp = fail("bad_json", perr);
    } else {
      /* An op that throws must become an error reply, not a dead process. The
       * vendor HAL throws from several reachable paths (StartRxLoop, chip
       * bring-up), and an exception out of a detached thread is terminate —
       * which kills every OTHER open adapter mid-transfer. */
      try {
        resp = dispatch(req);
      } catch (const std::exception &e) {
        resp = fail("internal_error", e.what());
      } catch (...) {
        resp = fail("internal_error", "unknown exception");
      }
      if (req.at("id").is_number())
        resp.set("id", req.at("id").integer());
    }
    if (!write_all(fd, resp.dump() + "\n"))
      break;
    if (g_stop)
      break;
    if (!read_line(fd, line))
      break;
  }
  ::close(fd);
  release_sessions_of(t_conn_id);
}

void serve_connection(int fd) {
  struct ConnGuard {
    ~ConnGuard() { g_connections.fetch_sub(1); }
  } guard;
  try {
    serve_connection_inner(fd);
  } catch (const std::exception &e) {
    std::fprintf(stderr, "connection thread died: %s\n", e.what());
  } catch (...) {
    std::fprintf(stderr, "connection thread died: unknown exception\n");
  }
}

void serve_connection_inner(int fd) {
  std::string line;
  if (!read_line(fd, line)) {
    ::close(fd);
    return;
  }
  Json hello;
  std::string perr;
  if (Json::parse(line, hello, perr) && hello.at("attach").is_number()) {
    const auto id = static_cast<uint32_t>(hello.at("attach").integer());
    std::shared_ptr<Session> s;
    {
      std::lock_guard<std::mutex> lk(g_mu);
      auto it = g_sessions.find(id);
      if (it != g_sessions.end())
        s = it->second;
    }
    if (!s) {
      write_all(fd, fail("no_session", "no session " + std::to_string(id))
                            .dump() +
                        "\n");
      ::close(fd);
      return;
    }
    /* Acknowledge on the same socket BEFORE any binary follows, so the client
     * can tell "attached" from "refused" without a second channel. */
    write_all(fd, ok(Json().set("attached", id)).dump() + "\n");
    s->attach_sink(fd); /* session owns the fd from here */
    return;
  }
  serve_control(fd, std::move(line));
}

/* Written by the signal handler and by the `shutdown` op, read by the accept
 * loop's poll(). A self-pipe rather than relying on EINTR alone: it closes the
 * race where the signal lands between the g_stop check and poll(). */
int g_wake_fds[2] = {-1, -1};

void wake_accept_loop() {
  if (g_wake_fds[1] >= 0) {
    const char b = 1;
    ssize_t w = ::write(g_wake_fds[1], &b, 1); /* async-signal-safe */
    (void)w;
  }
}

void on_signal(int) {
  g_stop = true;
  wake_accept_loop();
}

} // namespace
} // namespace bridge

int main(int argc, char **argv) {
  using namespace bridge;

  std::string sock_path;
  for (int i = 1; i < argc; ++i) {
    const std::string a = argv[i];
    if ((a == "--socket" || a == "-s") && i + 1 < argc)
      sock_path = argv[++i];
    else if (a == "--devourer-commit" && i + 1 < argc)
      g_devourer_commit = argv[++i];
    else if (a == "--help" || a == "-h") {
      std::fprintf(stderr,
                   "devourer-bridge --socket <path> [--devourer-commit <sha>]\n"
                   "Protocol v%d.%d; frame record %zu bytes.\n",
                   kProtocolVersionMajor, kProtocolVersionMinor,
                   sizeof(FrameRecord));
      return 0;
    }
  }
  if (sock_path.empty()) {
    const char *xdg = std::getenv("XDG_RUNTIME_DIR");
    sock_path = (xdg ? std::string(xdg) : std::string("/tmp")) +
                "/devourer-mcp/bridge.sock";
  }
  if (sock_path.size() >= sizeof(sockaddr_un::sun_path)) {
    std::fprintf(stderr, "socket path too long (max %zu)\n",
                 sizeof(sockaddr_un::sun_path) - 1);
    return 1;
  }

  /* Create the parent directory, then unlink any stale socket. A leftover
   * socket file from a killed bridge otherwise makes bind() fail with EADDRINUSE
   * forever. */
  const size_t slash = sock_path.find_last_of('/');
  if (slash != std::string::npos)
    ::mkdir(sock_path.substr(0, slash).c_str(), 0700);
  ::unlink(sock_path.c_str());

  const int listener = ::socket(AF_UNIX, SOCK_STREAM, 0);
  if (listener < 0) {
    std::perror("socket");
    return 1;
  }
  sockaddr_un addr{};
  addr.sun_family = AF_UNIX;
  std::strncpy(addr.sun_path, sock_path.c_str(), sizeof(addr.sun_path) - 1);
  if (::bind(listener, reinterpret_cast<sockaddr *>(&addr), sizeof addr) != 0) {
    std::perror("bind");
    return 1;
  }
  ::chmod(sock_path.c_str(), 0600); /* the radio is the owner's, not the host's */
  if (::listen(listener, 16) != 0) {
    std::perror("listen");
    return 1;
  }

  /* sigaction with SA_RESTART CLEARED, plus the self-pipe above.
   *
   * std::signal on glibc installs BSD semantics, i.e. SA_RESTART, so a blocked
   * accept() is auto-restarted and never returns EINTR — the EINTR branch below
   * was dead code and SIGTERM could not stop an idle bridge. systemd then
   * SIGKILLed it, so the cleanup that calls IRadio::Stop() and releases the USB
   * claim never ran, leaving adapters exactly in the wedge state. */
  if (::pipe(g_wake_fds) != 0) {
    std::perror("pipe");
    return 1;
  }
  {
    struct sigaction sa {};
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0; /* deliberately NOT SA_RESTART */
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
  }
  /* A frame-sink client that disappears mid-write must surface as EPIPE on the
   * write, not as a process-killing signal. */
  std::signal(SIGPIPE, SIG_IGN);

  std::fprintf(stderr, "devourer-bridge listening on %s (protocol %d.%d)\n",
               sock_path.c_str(), kProtocolVersionMajor, kProtocolVersionMinor);

  while (!g_stop) {
    pollfd fds[2] = {{listener, POLLIN, 0}, {g_wake_fds[0], POLLIN, 0}};
    const int pr = ::poll(fds, 2, -1);
    if (pr < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    if (fds[1].revents & POLLIN)
      break; /* shutdown op or a signal */
    if (!(fds[0].revents & POLLIN))
      continue;

    const int fd = ::accept(listener, nullptr, nullptr);
    if (fd < 0) {
      if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)
        continue;
      break;
    }
    if (g_connections.load() >= kMaxConnections) {
      write_all(fd, fail("too_many_connections",
                         "the bridge is already serving " +
                             std::to_string(kMaxConnections) + " connections")
                        .dump() +
                    "\n");
      ::close(fd);
      continue;
    }
    /* A std::thread constructor throw out of this loop would be an uncaught
     * exception in main, i.e. terminate with every adapter still claimed. */
    try {
      g_connections.fetch_add(1);
      std::thread(serve_connection, fd).detach();
    } catch (const std::exception &e) {
      g_connections.fetch_sub(1);
      std::fprintf(stderr, "cannot spawn connection thread: %s\n", e.what());
      ::close(fd);
    }
  }

  {
    std::lock_guard<std::mutex> lk(g_mu);
    for (auto &kv : g_sessions)
      kv.second->close();
    g_sessions.clear();
    g_session_owner.clear();
  }
  ::close(listener);
  ::unlink(sock_path.c_str());
  std::fprintf(stderr, "devourer-bridge stopped\n");
  return 0;
}

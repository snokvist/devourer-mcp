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
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

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
#include "Session.h"

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

Json op_radio_open(const Json &req) {
  OpenOptions o;
  if (!req.at("bus").is_number() || !req.at("address").is_number())
    return fail("bad_request", "bus and address are required");
  o.bus = static_cast<uint8_t>(req.at("bus").integer());
  o.address = static_cast<uint8_t>(req.at("address").integer());
  o.reset = req.at("reset").boolean(true);
  if (req.at("buffer_bytes").is_number())
    o.buffer_bytes = static_cast<size_t>(req.at("buffer_bytes").integer());
  if (req.at("max_frame_bytes").is_number())
    o.max_frame_bytes =
        static_cast<uint32_t>(req.at("max_frame_bytes").integer());

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
  ch.Channel = static_cast<uint8_t>(req.at("channel").integer());
  ch.ChannelWidth = w;
  ch.ChannelOffset = static_cast<uint8_t>(req.at("offset").integer(0));
  ch.Band = static_cast<uint8_t>(req.at("band").integer(0));
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

Json op_tx_send(const Json &req) {
  std::string err;
  auto s = find_session(req, err);
  if (!s)
    return fail("no_session", err);
  std::vector<uint8_t> frame;
  if (req.at("frame_hex").is_string()) {
    if (!decode_hex(req.at("frame_hex").str(), frame))
      return fail("bad_request", "frame_hex is not valid hex");
  } else if (req.at("frame_b64").is_string()) {
    if (!decode_b64(req.at("frame_b64").str(), frame))
      return fail("bad_request", "frame_b64 is not valid base64");
  } else {
    return fail("bad_request", "one of frame_hex / frame_b64 is required");
  }
  if (frame.empty())
    return fail("bad_request", "empty frame");

  const int64_t count = req.at("count").integer(1);
  if (count < 1 || count > 100000)
    return fail("bad_request", "count must be 1..100000");

  /* Bounded by construction: the bridge will not sit in an unbounded send
   * loop. Longer or timed transmissions are the experiment engine's job, where
   * there is a cancellation path and a caller watching. */
  int64_t sent = 0;
  for (int64_t i = 0; i < count; ++i) {
    if (!s->send_frame(frame.data(), frame.size(), err))
      break;
    ++sent;
  }
  Json r;
  r.set("session", s->id()).set("requested", count).set("sent", sent);
  if (sent != count)
    r.set("stopped_because", err);
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
  if (op == "tx.send")
    return op_tx_send(req);
  if (op == "shutdown") {
    g_stop = true;
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
      resp = dispatch(req);
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

void on_signal(int) { g_stop = true; }

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

  std::signal(SIGINT, on_signal);
  std::signal(SIGTERM, on_signal);
  /* A frame-sink client that disappears mid-write must surface as EPIPE on the
   * write, not as a process-killing signal. */
  std::signal(SIGPIPE, SIG_IGN);

  std::fprintf(stderr, "devourer-bridge listening on %s (protocol %d.%d)\n",
               sock_path.c_str(), kProtocolVersionMajor, kProtocolVersionMinor);

  while (!g_stop) {
    const int fd = ::accept(listener, nullptr, nullptr);
    if (fd < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    std::thread(serve_connection, fd).detach();
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

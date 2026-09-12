#include "Session.h"

#include <libusb.h>

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/file.h>
#include <poll.h>
#include <sys/socket.h>
#include <ctime>
#include <unistd.h>

#include "AdapterCaps.h"
#include "TxStats.h"
#include "DeviceConfig.h"
#include "IRadio.h"
#include "IRtlRadio.h"
#include "Protocol.h"
#include "RxPacket.h"
#include "UsbOpen.h"
#include "WiFiDriver.h"
#include "common/DeviceSession.h"
#include "logger.h"

namespace bridge {
namespace {

uint64_t now_ns() {
  timespec ts{};
  clock_gettime(CLOCK_REALTIME, &ts);
  return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
         static_cast<uint64_t>(ts.tv_nsec);
}

/* Who holds the devourer USB lock for this adapter, as "name (pid N)".
 *
 * devourer's UsbDeviceLock writes the owner's pid into /tmp/devourer-usb-<bus>-<port>.lock
 * as a diagnostic stamp (the lock itself is the flock, not the contents). The
 * stamp can be stale — a crashed process leaves its pid behind while the flock
 * is long released — so it is only reported when that pid is still alive, and
 * never used to decide anything. */
/* Is devourer's lock actually held right now?
 *
 * The decisive question, and the pid stamp cannot answer it. Taking the flock
 * non-blocking and immediately dropping it says yes or no with no guesswork
 * and no privileges — which matters, because the interesting case is a
 * FOREIGN process holding the kernel interface while our own stale stamp sits
 * in the file. Observed on this bench: a waybeam-link run had claimed the
 * adapter, our stamp from a previous open was still there, and the refusal
 * named us instead of it. */
bool devourer_lock_held(uint8_t bus, const std::string &port_path) {
  if (port_path.empty())
    return false;
  const std::string path = "/tmp/devourer-usb-" + std::to_string(bus) + "-" +
                           port_path + ".lock";
  const int fd = ::open(path.c_str(), O_RDONLY);
  if (fd < 0)
    return false;
  const bool free_now = ::flock(fd, LOCK_EX | LOCK_NB) == 0;
  if (free_now)
    ::flock(fd, LOCK_UN);
  ::close(fd);
  return !free_now;
}

std::string lock_holder(uint8_t bus, const std::string &port_path) {
  if (port_path.empty())
    return {};
  /* A stamp with no lock behind it is a fossil from some earlier open —
   * possibly our own. Naming it would point the caller at the wrong process,
   * which is worse than admitting we do not know. */
  if (!devourer_lock_held(bus, port_path))
    return {};
  const std::string path = "/tmp/devourer-usb-" + std::to_string(bus) + "-" +
                           port_path + ".lock";
  FILE *f = std::fopen(path.c_str(), "r");
  if (f == nullptr)
    return {};
  long pid = 0;
  const bool got = std::fscanf(f, "%ld", &pid) == 1;
  std::fclose(f);
  if (!got || pid <= 0)
    return {};

  /* Naming ourselves back to the caller is useless — "devourer-bridge holds it"
   * when devourer-bridge is who you are asking tells nobody anything. What the
   * caller needs is that ANOTHER SESSION in this same bridge has it, which is a
   * different fix (close that session) from a foreign process (stop it). */
  if (pid == static_cast<long>(::getpid()))
    return "this bridge — another session already has it; close that session "
           "first (see the `sessions` op)";

  const std::string comm_path = "/proc/" + std::to_string(pid) + "/comm";
  FILE *cf = std::fopen(comm_path.c_str(), "r");
  if (cf == nullptr)
    return {}; /* pid is gone: a stale stamp, so say nothing rather than guess */
  char name[256] = {0};
  if (std::fgets(name, sizeof name, cf) == nullptr)
    name[0] = '\0';
  std::fclose(cf);
  std::string n{name};
  while (!n.empty() && (n.back() == '\n' || n.back() == '\r'))
    n.pop_back();
  if (n.empty())
    return "pid " + std::to_string(pid);
  return n + " (pid " + std::to_string(pid) + ")";
}

Logger_t make_logger() {
  /* One logger for every session. Devourer's Logger writes to stderr, which is
   * what we want: stdout belongs to whoever launched the bridge, and mixing
   * library chatter into a protocol stream is how a control plane becomes
   * unparseable. */
  static Logger_t shared = std::make_shared<Logger>();
  return shared;
}

Json band_range(const devourer::BandRange &r) {
  Json j;
  j.set("valid", r.valid);
  if (r.valid) {
    j.set("min_mhz", r.min_mhz);
    j.set("max_mhz", r.max_mhz);
  }
  return j;
}

/* ChannelWidth_t -> MHz. The protocol takes MHz on the way in, so reporting the
 * raw enum on the way out is an asymmetry that reads as a bug: width 0 means
 * 20 MHz, and looks like "unknown". */
int width_mhz_of(ChannelWidth_t w) {
  switch (w) {
  case CHANNEL_WIDTH_20:
    return 20;
  case CHANNEL_WIDTH_40:
    return 40;
  case CHANNEL_WIDTH_80:
    return 80;
  case CHANNEL_WIDTH_160:
    return 160;
  case CHANNEL_WIDTH_5:
    return 5;
  case CHANNEL_WIDTH_10:
    return 10;
  default:
    return 0;
  }
}

Json bw_list(uint8_t mask) {
  Json a = Json::array();
  if (mask & devourer::kBw5)
    a.push("5");
  if (mask & devourer::kBw10)
    a.push("10");
  if (mask & devourer::kBw20)
    a.push("20");
  if (mask & devourer::kBw40)
    a.push("40");
  if (mask & devourer::kBw80)
    a.push("80");
  if (mask & devourer::kBw160)
    a.push("160");
  return a;
}

} // namespace

Session::Session(uint32_t id, DeviceInfo info)
    : _id{id}, _info{std::move(info)} {}

Session::~Session() {
  /* close() calls into the vendor HAL, whose own Stop() wraps StopRxLoop in a
   * try/catch precisely because "the logging inside StopRxLoop is the
   * realistic thrower". A throw out of a destructor is implicitly terminate. */
  try {
    close();
  } catch (...) {
  }
}

void Session::_logger_error(const std::string &msg) {
  std::fprintf(stderr, "devourer-bridge session %u: %s\n", _id, msg.c_str());
}

std::unique_ptr<Session> Session::open(uint32_t id, const OpenOptions &opts,
                                       std::string &code, std::string &msg) {
  auto logger = make_logger();

  /* Find the requested device first so "no such adapter" is distinguishable
   * from "present but won't open" — they lead to different next steps. */
  DeviceInfo info;
  bool found = false;
  for (const auto &d : enumerate_devices(true)) {
    if (d.bus == opts.bus && d.address == opts.address) {
      info = d;
      found = true;
      break;
    }
  }
  if (!found) {
    code = "not_found";
    msg = "no USB device at bus " + std::to_string(opts.bus) + " address " +
          std::to_string(opts.address);
    return nullptr;
  }

  auto s = std::unique_ptr<Session>(new Session(id, info));
  s->_max_frame_bytes = opts.max_frame_bytes;
  s->_buf_cap = opts.buffer_bytes;

  auto dev_session = std::make_unique<devourer::DeviceSession>(logger);

  libusb_context *ctx = nullptr;
  if (libusb_init(&ctx) != 0) {
    code = "usb_init_failed";
    msg = "libusb_init failed";
    return nullptr;
  }
  dev_session->adopt_context(ctx);

  /* Re-resolve the device on OUR context: a libusb_device from another
   * context cannot be opened here. */
  libusb_device **list = nullptr;
  const ssize_t n = libusb_get_device_list(ctx, &list);
  libusb_device_handle *handle = nullptr;
  int rc = LIBUSB_ERROR_NO_DEVICE;
  for (ssize_t i = 0; i < n; ++i) {
    if (libusb_get_bus_number(list[i]) != opts.bus ||
        libusb_get_device_address(list[i]) != opts.address)
      continue;
    rc = libusb_open(list[i], &handle);
    break;
  }
  if (list != nullptr)
    libusb_free_device_list(list, 1);

  if (rc != 0 || handle == nullptr) {
    code = (rc == LIBUSB_ERROR_ACCESS) ? "permission" : "open_failed";
    msg = std::string("libusb_open: ") + libusb_error_name(rc);
    if (rc == LIBUSB_ERROR_ACCESS)
      msg += " — install tools/host/70-devourer-usb.rules and replug, or run "
             "as root";
    return nullptr;
  }

  /* Lock, detach the kernel driver, set config, claim, then reset — the
   * ordering UsbOpen.h documents. The reopen variant recovers when the reset
   * re-enumerates the device (a warm Kestrel drops to ROM). */
  std::shared_ptr<devourer::UsbDeviceLock> lock;
  rc = devourer::claim_interface_reset_reopen(ctx, handle, logger, opts.reset,
                                              lock);
  if (rc != 0) {
    dev_session->adopt_handle(handle); /* so the unwind closes it */
    code = (rc == LIBUSB_ERROR_BUSY) ? "busy" : "claim_failed";
    if (rc == LIBUSB_ERROR_BUSY) {
      /* "In use by another process" is a fact, not a diagnosis. Devourer stamps
       * the holder's pid into its lock file precisely so a refusal can be
       * traced, so name the process — on a bench with an FPV ground station or
       * a second tool running, that is the whole answer. */
      const auto holder = lock_holder(info.bus, info.port_path);
      if (holder.empty()) {
        /* Nobody holds devourer's lock, so whatever claimed the USB
         * interface is not using devourer's locking at all — another tool,
         * or the same tool run as root. The kernel knows who; we cannot see
         * it without privileges, so hand over the command that can. */
        msg = "the kernel refused the interface claim (EBUSY) and devourer's "
              "own lock is NOT held, so the holder is a process outside "
              "devourer. Find it with: sudo fuser -v /dev/bus/usb/" +
              std::string(info.bus < 100 ? (info.bus < 10 ? "00" : "0") : "") +
              std::to_string(info.bus) + "/...";
      } else if (holder.rfind("this bridge", 0) == 0) {
        msg = "adapter is already in use by " + holder;
      } else {
        msg = "adapter is already in use by " + holder +
              ". Stop that process, or use a different adapter.";
      }
    } else {
      msg = std::string("claim/reset: ") + libusb_error_name(rc);
    }
    return nullptr;
  }
  dev_session->adopt_handle(handle);
  dev_session->adopt_lock(lock);

  WiFiDriver driver(logger);
  devourer::DeviceConfig cfg;
  /* Set before CreateRadio because devourer measures the absolute idle floor
   * inside Init, before the RX loop starts — there is no later. */
  cfg.rx.abs_noise_floor = opts.noise_floor;
  cfg.tuning.phydm_watchdog = opts.adaptive_gain;
  s->_noise_floor_requested = opts.noise_floor;
  /* Say when a session is brought up on anything but the default tuning. A
   * run whose behaviour depends on an option nobody can see afterwards is
   * not reproducible, and both of these change what the radio does. */
  if (opts.noise_floor || opts.adaptive_gain) {
    logger->info("bridge: open with noise_floor={} adaptive_gain={}",
                 opts.noise_floor, opts.adaptive_gain);
  }
  auto radio = driver.CreateRadio(handle, ctx, lock, cfg);
  if (!radio) {
    code = "unsupported_chip";
    msg = "no devourer backend for this chip in this build (the factory "
          "logged the chip id it read)";
    return nullptr;
  }
  dev_session->adopt_device(std::move(radio));
  s->_radio = dev_session->device();
  s->_dev = std::move(dev_session);

  /* Now that the chip has been identified, fill in what only an open could
   * tell us — this is the step that turns a CANDIDATE into a backend. */
  const auto caps = s->_radio->GetAdapterCaps();
  if (caps.supported) {
    s->_info.backend = devourer::generation_name(caps.generation);
    s->_info.variant = caps.variant;
    s->_info.id_source = IdSource::UsbId;
    /* Cached here so the RX hot path never calls back into the HAL. */
    s->_rx_chains = caps.rx_chains;
  }
  return s;
}

Json Session::describe() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  Json dev;
  dev.set("bus", _info.bus)
      .set("address", _info.address)
      .set("port_path", _info.port_path)
      .set("vid", _info.vid)
      .set("pid", _info.pid)
      .set("usb_id", [&] {
        char buf[16];
        std::snprintf(buf, sizeof buf, "%04x:%04x", _info.vid, _info.pid);
        return std::string{buf};
      }())
      .set("speed", speed_name(_info.speed))
      .set("product", _info.product)
      .set("serial", _info.serial)
      .set("kernel_driver", _info.kernel_driver);
  j.set("device", dev);

  if (_radio == nullptr)
    return j;

  const auto caps = _radio->GetAdapterCaps();
  Json c;
  c.set("supported", caps.supported);
  if (caps.supported) {
    c.set("chip", caps.chip_name)
        .set("marketing_names", caps.marketing_names)
        .set("chip_id", caps.chip_id)
        .set("generation", devourer::generation_name(caps.generation))
        .set("variant", caps.variant)
        .set("transport", caps.transport)
        .set("tx_chains", caps.tx_chains)
        .set("rx_chains", caps.rx_chains)
        .set("bandwidths_mhz", bw_list(caps.bw_mask))
        .set("tune_2g4", band_range(caps.tune_2g4))
        .set("tune_5g", band_range(caps.tune_5g))
        .set("characterized_2g4", band_range(caps.characterized_2g4))
        .set("characterized_5g", band_range(caps.characterized_5g))
        .set("ldpc_rx_ht", caps.ldpc_rx_ht)
        .set("ldpc_rx_vht", caps.ldpc_rx_vht)
        .set("ldpc_rx_flag", caps.ldpc_rx_flag)
        .set("vht_2g4_ok", caps.vht_2g4_ok);

    Json tx;
    tx.set("supported", caps.tx.supported)
        .set("spatial_streams", caps.tx.n_ss)
        .set("stbc_ok", caps.tx.stbc_ok)
        .set("ldpc_ok", caps.tx.ldpc_ok)
        .set("sgi_ok", caps.tx.sgi_ok)
        .set("bw_max_mhz", caps.tx.bw_max_mhz);
    c.set("tx", tx);

    /* TX power is an index/offset model, not dBm: index_max and step_qdb
     * describe the knob, and step_measured says whether the on-air slope was
     * actually validated for this family. An experiment that treats an
     * unmeasured step as calibrated is producing numbers, not evidence. */
    Json p;
    p.set("supported", caps.txpwr.supported)
        .set("index_max", caps.txpwr.index_max)
        .set("step_qdb", caps.txpwr.step_qdb)
        .set("step_measured", caps.txpwr.step_measured)
        .set("offset_min_qdb", caps.txpwr.offset_min_qdb)
        .set("offset_max_qdb", caps.txpwr.offset_max_qdb)
        .set("rate_diffs", caps.txpwr.rate_diffs)
        .set("rate_diffs_hw_table", caps.txpwr.rate_diffs_hw_table)
        .set("rate_diffs_measured", caps.txpwr.rate_diffs_measured);
    c.set("tx_power", p);

    /* The per-feature gates the MCP layer checks before offering an operation.
     * Every one is a real "this adapter can / cannot", resolved by devourer
     * from the chip identity — which is exactly why the instrument needs no
     * chipset table of its own.
     *
     * Booleans and numbers are kept in separate objects. A gate ("can this
     * adapter do per-packet TX power") and a parameter ("in steps of how many
     * qdB") are different questions, and a consumer that has to type-sniff each
     * value to tell them apart will eventually get one wrong. */
    Json f;
    f.set("ack_responder", caps.ack_responder_ok)
        .set("tx_retry_limit", caps.tx_retry_limit_ok)
        .set("per_packet_txpower", caps.per_packet_txpower)
        .set("per_packet_txpower_measured", caps.per_pkt_txpwr_measured)
        .set("narrowband", caps.narrowband_ok)
        .set("fast_retune", caps.fastretune_ok)
        .set("he_er_su", caps.he_er_su_ok)
        .set("per_chain_rssi", caps.per_chain_rssi)
        .set("hw_rx_timestamp", caps.hw_rx_timestamp)
        .set("hw_beacon_txtsf", caps.hw_beacon_txtsf)
        .set("trigger_ul", caps.trigger_ul_ok)
        .set("twt", caps.twt_ok)
        .set("sounding", caps.sounding_ok);
    c.set("features", f);

    Json prm;
    prm.set("per_packet_txpower_steps", caps.per_pkt_txpwr_steps)
        .set("per_packet_txpower_step_qdb", caps.per_pkt_txpwr_step_qdb)
        .set("per_packet_txpower_min_qdb", caps.per_pkt_txpwr_min_qdb)
        .set("per_packet_txpower_max_qdb", caps.per_pkt_txpwr_max_qdb)
        .set("xtal_cap_max", caps.xtal_cap_max)
        .set("xtal_cap_default", caps.xtal_cap_default);
    c.set("parameters", prm);
  }
  j.set("capabilities", c);

  uint8_t mac[6] = {0};
  if (_radio->GetPermanentMacAddress(mac)) {
    char buf[20];
    std::snprintf(buf, sizeof buf, "%02x:%02x:%02x:%02x:%02x:%02x", mac[0],
                  mac[1], mac[2], mac[3], mac[4], mac[5]);
    j.set("permanent_mac", std::string{buf});
  }

  j.set("state", Json()
                     .set("brought_up", _up.load())
                     .set("monitoring", _rx_running.load())
                     .set("cca_disabled", _cca_disabled));
  if (_up) {
    j.set("channel", Json()
                         .set("channel", _channel.Channel)
                         .set("width", width_mhz_of(_channel.ChannelWidth))
                         .set("offset", _channel.ChannelOffset)
                         .set("band", _channel.Band));
  }
  return j;
}

bool Session::bring_up(SelectedChannel ch, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (_up) {
    /* Already powered: a channel change is a retune, not a second bring-up. */
    return set_channel(ch, err);
  }
  try {
    _radio->InitWrite(ch);
  } catch (const std::exception &e) {
    err = std::string("InitWrite threw: ") + e.what();
    return false;
  }
  _channel = ch;
  _up = true;
  return true;
}

bool Session::set_channel(SelectedChannel ch, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (!_up) {
    err = "radio is not brought up";
    return false;
  }
  try {
    _radio->SetMonitorChannel(ch);
  } catch (const std::exception &e) {
    err = std::string("SetMonitorChannel threw: ") + e.what();
    return false;
  }
  _channel = ch;
  return true;
}

bool Session::start_monitor(std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (!_up) {
    err = "radio is not brought up";
    return false;
  }
  bool expected = false;
  if (!_rx_running.compare_exchange_strong(expected, true)) {
    err = "already monitoring";
    return false;
  }
  /* The loop can exit on its own (unplug, USB error), leaving the thread
   * joinable with _rx_running false. Assigning over a joinable std::thread is
   * std::terminate, so reap it first. */
  if (_rx_thread.joinable())
    _rx_thread.join();
  _rx_thread = std::thread([this] {
    /* Nothing may escape this thread. Mt7612uRadio::StartRxLoop throws on
     * three conditions, and an exception out of a thread entry is
     * std::terminate — which would abort the process with every OTHER adapter
     * still claimed and undrained, i.e. inflict the USB wedge via the error
     * path. */
    try {
      _radio->StartRxLoop([this](const Packet &p) { on_packet(p); });
    } catch (const std::exception &e) {
      _logger_error(std::string("RX loop threw: ") + e.what());
    } catch (...) {
      _logger_error("RX loop threw a non-std exception");
    }
    _rx_running = false;
  });
  return true;
}

void Session::stop_monitor() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (!_rx_thread.joinable())
    return;
  _radio->StopRxLoop();
  _rx_thread.join();
  _rx_running = false;
}

void Session::on_packet(const Packet &pkt) {
  const auto &a = pkt.RxAtrib;
  const uint32_t avail = static_cast<uint32_t>(pkt.Data.size());
  const uint32_t take = avail > _max_frame_bytes ? _max_frame_bytes : avail;

  FrameRecord h{};
  h.magic = kFrameMagic;
  h.record_len = static_cast<uint32_t>(sizeof(FrameRecord) - 8 + take);
  h.seq = _seq.fetch_add(1, std::memory_order_relaxed);
  h.host_ns = now_ns();
  h.session = _id;
  h.frame_len = take;
  h.pkt_len = a.pkt_len;
  h.seq_num = a.seq_num;
  h.data_rate = a.data_rate;
  h.frag_num = a.frag_num;
  h.priority = a.priority;
  h.tsfl = a.tsfl;
  h.bw = a.bw;
  h.stbc = a.stbc;
  h.ldpc = a.ldpc;
  h.sgi = a.sgi;
  h.ppdu_type = a.ppdu_type;
  h.ppdu_cnt = a.ppdu_cnt;
  h.scrambler = a.scrambler;
  h.cfo_tail = a.cfo_tail;
  std::memcpy(h.rssi, a.rssi, 4);
  std::memcpy(h.snr, a.snr, 4);
  std::memcpy(h.evm, a.evm, 4);
  h.physt = a.physt ? 1 : 0;
  h._reserved_phy_fill = 0; /* see Protocol.h: never a measurement */
  h.crc_err = a.crc_err ? 1 : 0;
  h.icv_err = a.icv_err ? 1 : 0;
  h.bdecrypted = a.bdecrypted ? 1 : 0;
  h.encrypt = a.encrypt;
  h.qos = a.qos ? 1 : 0;
  h.mdata = a.mdata ? 1 : 0;
  h.mfrag = a.mfrag ? 1 : 0;
  h.paggr = a.paggr ? 1 : 0;
  h.fcs_present = a.fcs_present ? 1 : 0;
  h.pkt_rpt_type = static_cast<uint8_t>(a.pkt_rpt_type);
  h.truncated = (take < avail) ? 1 : 0;
  h.rx_chains = _rx_chains;
  if (auto tsf = pkt.TxEgressTsf()) {
    h.has_tx_egress_tsf = 1;
    h.tx_egress_tsf = *tsf;
  }

  const size_t need = sizeof(FrameRecord) + take;
  {
    std::lock_guard<std::mutex> lk(_buf_mu);
    if (_sink_fd < 0) {
      /* Nobody attached: count the frame, discard the bytes. Monitoring
       * without a reader is a legitimate state (stats-only observation). */
      std::lock_guard<std::mutex> sl(_stats_mu);
      _stats.frames++;
      _stats.bytes += take;
      return;
    }
    if (_buf.size() - _buf_head + need > _buf_cap) {
      std::lock_guard<std::mutex> sl(_stats_mu);
      _stats.dropped++;
      return; /* whole record dropped: never write a partial one */
    }
    /* Reserve in generous steps rather than letting vector growth realloc a
     * multi-megabyte buffer while the lock is held. */
    if (_buf.capacity() < _buf.size() + need)
      _buf.reserve(std::max(_buf.capacity() * 2, _buf.size() + need + (1u << 20)));
    const size_t at = _buf.size();
    _buf.resize(at + need);
    std::memcpy(_buf.data() + at, &h, sizeof h);
    if (take)
      std::memcpy(_buf.data() + at + sizeof h, pkt.Data.data(), take);
  }
  _buf_cv.notify_one();
  {
    std::lock_guard<std::mutex> sl(_stats_mu);
    _stats.frames++;
    _stats.bytes += take;
  }
}

void Session::attach_sink(int fd) {
  std::lock_guard<std::mutex> sink_lk(_sink_mu);
  detach_sink_locked();
  /* Non-blocking, so the writer can never park in write() where neither the
   * stop flag nor the condvar can reach it. A paused client used to hang
   * stop_writer()'s join forever, which hung session teardown, which at
   * shutdown hung the whole daemon under g_mu. */
  const int flags = ::fcntl(fd, F_GETFL, 0);
  if (flags >= 0)
    ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
  {
    std::lock_guard<std::mutex> lk(_buf_mu);
    _sink_fd = fd;
    _buf.clear();
    _buf_head = 0;
  }
  _writer_stop = false;
  _writer = std::thread([this] { writer_loop(); });
}

void Session::detach_sink() {
  std::lock_guard<std::mutex> sink_lk(_sink_mu);
  detach_sink_locked();
}

void Session::detach_sink_locked() {
  stop_writer();
  std::lock_guard<std::mutex> lk(_buf_mu);
  if (_sink_fd >= 0) {
    ::close(_sink_fd);
    _sink_fd = -1;
  }
  _buf.clear();
  _buf_head = 0;
}

void Session::stop_writer() {
  if (!_writer.joinable())
    return;
  _writer_stop = true;
  /* shutdown() before join(): it makes any in-flight or subsequent write on
   * the socket fail immediately, so a writer blocked on a full send buffer
   * returns instead of being joined forever. Do NOT close() here — the writer
   * still holds the fd. */
  {
    std::lock_guard<std::mutex> lk(_buf_mu);
    if (_sink_fd >= 0)
      ::shutdown(_sink_fd, SHUT_RDWR);
  }
  _buf_cv.notify_all();
  _writer.join();
}

void Session::writer_loop() {
  /* Double-buffered: the producer keeps filling one vector while this thread
   * writes the other, and handover is a swap.
   *
   * The obvious implementation — copy the pending bytes out under the lock —
   * is a correctness bug on this hot path, not just a slow one. The copy is
   * O(bytes pending), it runs while holding the same mutex on_packet needs, and
   * the RX callback therefore blocks for the length of a multi-megabyte memcpy
   * whenever the client falls behind. Devourer is explicit that an undrained
   * MT7612U receiver wedges below the USB level, and that is what happened
   * here: "rx.pool_exhaust=backpressure cannot be honoured", then MCU command
   * timeouts and a dead adapter. Swapping makes the locked region constant
   * time, so the producer is never held up by however much is queued. */
  std::vector<uint8_t> chunk;
  for (;;) {
    {
      std::unique_lock<std::mutex> lk(_buf_mu);
      _buf_cv.wait(lk, [this] {
        return _writer_stop.load() || _buf.size() > _buf_head;
      });
      if (_writer_stop && _buf.size() == _buf_head)
        return;
      chunk.clear();
      chunk.swap(_buf); /* O(1): three pointer assignments */
      _buf.reserve(chunk.capacity()); /* keep the producer allocation-free */
      _buf_head = 0;
    }
    size_t off = 0;
    while (off < chunk.size()) {
      const ssize_t w = ::write(_sink_fd, chunk.data() + off, chunk.size() - off);
      if (w > 0) {
        off += static_cast<size_t>(w);
        continue;
      }
      if (w < 0 && errno == EINTR)
        continue;
      if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        /* The socket is non-blocking now, so a full send buffer lands here
         * rather than parking the thread. Wait for room, but bounded, so
         * _writer_stop is re-checked at a known cadence. */
        if (_writer_stop)
          return;
        pollfd pfd{_sink_fd, POLLOUT, 0};
        ::poll(&pfd, 1, 200);
        continue;
      }
      /* The client went away mid-record. There is no honest recovery: the
       * stream is truncated at an arbitrary byte. Actually drop the sink —
       * this used to only bump a counter, so the fd leaked, monitor.stats kept
       * reporting sink_attached:true, and frames piled up to the cap forever. */
      {
        std::lock_guard<std::mutex> lk(_buf_mu);
        if (_sink_fd >= 0) {
          ::close(_sink_fd);
          _sink_fd = -1;
        }
        _buf.clear();
        _buf_head = 0;
      }
      {
        std::lock_guard<std::mutex> sl(_stats_mu);
        _stats.write_errors++;
      }
      return;
    }
  }
}

bool Session::send_frame(const uint8_t *data, size_t len, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (!_up) {
    err = "radio is not brought up";
    return false;
  }
  const bool ok = _radio->send_packet(data, len);
  std::lock_guard<std::mutex> sl(_stats_mu);
  if (ok)
    _stats.tx_sent++;
  else
    _stats.tx_failed++;
  if (!ok)
    err = "send_packet returned false (queue full or TX path down)";
  return ok;
}

Json Session::rx_paths_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("valid", false).set("why", "session has no radio");
    return j;
  }
  if (!_rx_running) {
    /* Returning a zeroed report here would look like "no antennas active",
     * which is a measurement claim we have no basis for. */
    j.set("valid", false)
        .set("why", "no RX loop running — start monitoring first; this estimate "
                    "needs sampled frames");
    return j;
  }
  const auto p = _radio->GetActiveRxPaths();
  /* GetActiveRxPaths is an optional IRadio member with a not-ported default, so
   * a backend that never implemented it returns a zeroed struct. Zero chains is
   * not a measurement of zero active antennas — reporting it as one would be a
   * fabricated result. Say "not implemented" and hand back the static chain
   * count so the caller can fall back to analysing the capture itself. */
  if (p.n_chains == 0) {
    const auto caps = _radio->GetAdapterCaps();
    j.set("valid", false)
        .set("supported", false)
        .set("why",
             "this backend does not implement the live RX-path estimator "
             "(IRadio::GetActiveRxPaths is optional and not ported here)")
        .set("static_rx_chains", caps.rx_chains)
        .set("fallback",
             "derive per-chain balance from the capture instead: compare the "
             "per-chain RSSI distributions in capture_summary");
    return j;
  }
  j.set("supported", true);
  j.set("valid", p.valid)
      .set("frames_sampled", p.frames)
      .set("chains", p.n_chains)
      .set("chains_active", p.n_active)
      .set("active_mask", p.active_mask);
  Json per = Json::array();
  for (int i = 0; i < p.n_chains && i < 4; ++i) {
    Json c;
    c.set("chain", std::string(1, static_cast<char>('A' + i)))
        .set("sampled", p.chain_sampled[i])
        .set("active", ((p.active_mask >> i) & 1) != 0);
    if (p.chain_sampled[i])
      c.set("rssi_mean_dbm", p.rssi_mean_dbm[i]);
    if (p.snr_sampled[i])
      c.set("snr_mean_db", p.snr_mean_db[i]);
    if (p.evm_sampled[i])
      c.set("evm_mean_db", p.evm_mean_db[i]);
    per.push(c);
  }
  j.set("per_chain", per);
  j.set("caveat",
        "Best-effort: a chain is called active when its window-mean RSSI is "
        "within a margin of the strongest chain. Strong near-field traffic can "
        "light a chain whose antenna is absent, via coupling. Treat one window "
        "as a hint; repeat across channels and signal levels for a verdict. "
        "This reports CHAINS, not antenna connectors — a 2-chain part behind 4 "
        "antennas with diversity switching still reports 2.");
  return j;
}

bool Session::set_cca(bool disabled, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "radio is not brought up — set a channel first";
    return false;
  }
  try {
    /* Pure virtual on IRadio: every generation either implements it or refuses
     * loudly. A silent no-op here would be the worst outcome — the caller would
     * believe carrier sense was off and misread every result that followed. */
    _radio->SetCcaMode(disabled);
  } catch (const std::exception &e) {
    err = std::string("SetCcaMode threw: ") + e.what();
    return false;
  }
  _cca_disabled = disabled;
  return true;
}

Json Session::cca_gates_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  auto *rtl = dynamic_cast<IRtlRadio *>(_radio);
  bool primary = false, edcca = false;
  if (rtl == nullptr || !rtl->GetCcaGates(primary, edcca)) {
    j.set("supported", false)
        .set("why",
             "splitting the carrier-sense gate is a Realtek 0x520 facility "
             "(IRtlRadio::GetCcaGates) and is not ported for this backend; "
             "radio.cca still turns both gates off together")
        .set("cca_disabled", _cca_disabled);
    return j;
  }
  j.set("supported", true)
      .set("primary_cca_disabled", primary)
      .set("edcca_disabled", edcca)
      .set("note",
           "primary CCA defers to a DECODABLE PREAMBLE; EDCCA defers to raw "
           "in-band ENERGY. Devourer's own on-air work (tests/"
           "dis_cca_tx_onair.sh, Jaguar3) found primary CCA costing an "
           "injector 41-45% against a co-channel flooder while the energy "
           "bit alone was null. Both disabled is the antisocial setting.");
  return j;
}

bool Session::set_cca_gates(bool primary_disabled, bool edcca_disabled,
                            std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  auto *rtl = dynamic_cast<IRtlRadio *>(_radio);
  if (rtl == nullptr || !rtl->SetCcaGates(primary_disabled, edcca_disabled)) {
    err = "this backend cannot address the two carrier-sense gates "
          "separately; use radio.cca";
    return false;
  }
  _cca_disabled = primary_disabled && edcca_disabled;
  return true;
}

Json Session::rx_gain_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const auto caps = _radio->GetRxGainCaps();
  if (!caps.supported) {
    j.set("supported", false)
        .set("why",
             "this backend does not report a receive-gain index "
             "(IRadio::GetRxGainCaps is optional and not ported here)");
    return j;
  }
  j.set("supported", true)
      .set("settable", caps.settable)
      .set("index_name", caps.index_name)
      .set("index_min", caps.index_min)
      .set("index_max", caps.index_max);
  if (caps.index_step_db > 0)
    j.set("index_step_db", caps.index_step_db);
  /* The field this whole op exists for: whether anything is adjusting the
   * gain, and what it keys on. "A periodic loop exists" and "a periodic loop
   * is doing something" are different facts. */
  j.set("automatic", caps.automatic).set("automatic_input", caps.automatic_input);

  const auto st = _radio->GetRxGainState();
  j.set("valid", st.valid);
  if (st.valid) {
    j.set("index", st.index)
        .set("range_min", st.range_min)
        .set("range_max", st.range_max);
    if (st.index == st.range_min && st.range_min != st.range_max)
      j.set("note",
            "the index is sitting at the bottom of its range, i.e. maximum "
            "gain. On Realtek the EDCCA threshold is derived from it, so "
            "carrier sense is at its most sensitive too.");
  } else {
    j.set("why",
          "the baseband is not up yet — bring the radio up (retune or start "
          "a monitor) before reading the gain");
  }
  return j;
}

bool Session::set_rx_gain(int min, int max, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  const auto caps = _radio->GetRxGainCaps();
  if (!caps.settable) {
    err = "this backend cannot clamp its receive gain";
    return false;
  }
  if (min > max) {
    err = "min must be <= max";
    return false;
  }
  if (min < caps.index_min || max > caps.index_max) {
    err = "range must lie inside [" + std::to_string(caps.index_min) + ", " +
          std::to_string(caps.index_max) + "]";
    return false;
  }
  if (!_radio->SetRxGainRange(static_cast<uint8_t>(min),
                              static_cast<uint8_t>(max))) {
    err = "the backend refused the clamp";
    return false;
  }
  return true;
}

Json Session::rx_energy_json(bool with_nhm) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  /* The documented way to reach a Realtek-family member: CreateRadio returns
   * an IRadio, a caller that needs one of these dynamic_casts, and nullptr
   * means "not a Realtek radio — skip the feature, never fake a reading". */
  auto *rtl = dynamic_cast<IRtlRadio *>(_radio);
  if (rtl == nullptr) {
    j.set("supported", false)
        .set("why",
             "frame-free energy is a Realtek phydm facility "
             "(IRtlRadio::GetRxEnergy); this backend is not a Realtek radio")
        .set("fallback",
             "count what a receiver can decode instead: monitor the channel "
             "and compare frame rates. That is a different quantity — it "
             "cannot see energy that never becomes a frame.");
    return j;
  }

  const auto e = rtl->GetRxEnergy(with_nhm);
  j.set("supported", true);
  j.set("channel", _channel.Channel);
  /* Every field behind its own valid flag: the facilities differ by chip
   * generation, and a zero from a generation that does not fill the field is
   * not a measurement of zero. */
  j.set("valid_counters", e.valid_fa);
  if (e.valid_fa) {
    j.set("fa_ofdm", e.fa_ofdm)
        .set("fa_cck", e.fa_cck)
        .set("cca_ofdm", e.cca_ofdm)
        .set("cca_cck", e.cca_cck);
  }
  j.set("valid_igi", e.valid_igi);
  if (e.valid_igi)
    j.set("igi", static_cast<int>(e.igi));
  j.set("valid_noise_floor", e.valid_noise_floor);
  if (e.valid_noise_floor) {
    j.set("abs_noise_floor_dbm", static_cast<int>(e.abs_noise_floor_dbm));
  } else {
    /* Three different absences, and a bare false cannot tell them apart. */
    j.set("noise_floor_why",
          !_noise_floor_requested
              ? "not requested: open the radio with noise_floor=true"
              : "requested, but this generation did not fill it. On Jaguar1 "
                "(8812A/8821A) the vendor CAL runs inside IRadio::Init and "
                "this bridge brings radios up with InitWrite + StartRxLoop, "
                "so it never runs; on Jaguar2 the live report is best-effort "
                "and often unpopulated in monitor mode. Use igi as the "
                "relative floor proxy instead.");
  }
  j.set("valid_nhm", e.valid_nhm);
  if (e.valid_nhm) {
    Json buckets = Json::array();
    for (unsigned char b : e.nhm)
      buckets.push(static_cast<int>(b));
    j.set("nhm", buckets);
    j.set("nhm_duration", static_cast<int>(e.nhm_duration));
  }
  j.set("note",
        "fa/cca are DELTAS since the previous read, which resets the hardware "
        "counters. To measure a window, read once and throw it away, wait, "
        "then read again. igi is the AGC's initial-gain index: the gain backs "
        "off as the in-band floor rises, so higher means a noisier channel. "
        "These are channel-wide scalars, not a spectrum.");
  return j;
}

Json Session::tx_stats_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const auto t = _radio->GetTxStats();
  j.set("supported", true)
      .set("submitted", t.submitted)
      .set("failed", t.failed)
      .set("last_error_rc", t.last_error_rc)
      .set("last_was_timeout", t.last_was_timeout);
  {
    std::lock_guard<std::mutex> sl(_stats_mu);
    j.set("bridge_tx_sent", _stats.tx_sent).set("bridge_tx_failed", _stats.tx_failed);
  }
  j.set("note",
        "`submitted` is frames handed to the USB stack, not frames aired. A "
        "large submitted with zero failed and nothing heard by an independent "
        "receiver means the host did its part and the radio did not.");
  return j;
}

SessionStats Session::stats() const {
  std::lock_guard<std::mutex> sl(_stats_mu);
  return _stats;
}

Json Session::stats_json() const {
  const auto s = stats();
  Json j;
  j.set("session", _id)
      .set("frames", s.frames)
      .set("bytes", s.bytes)
      .set("dropped", s.dropped)
      .set("write_errors", s.write_errors)
      .set("tx_sent", s.tx_sent)
      .set("tx_failed", s.tx_failed);
  {
    std::lock_guard<std::mutex> lk(_buf_mu);
    j.set("buffer_used", static_cast<uint64_t>(_buf.size() - _buf_head));
    j.set("buffer_cap", static_cast<uint64_t>(_buf_cap));
    j.set("sink_attached", _sink_fd >= 0);
  }
  return j;
}

void Session::close() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_closed)
    return; /* idempotent: two clients can close the same session concurrently */
  _closed = true;
  stop_monitor();
  detach_sink();
  if (_radio != nullptr && _up) {
    /* Clean chip de-init before the interface is dropped, so the adapter
     * re-enumerates instead of hanging its USB core. */
    _radio->Stop();
    _up = false;
  }
  _radio = nullptr;
  _dev.reset(); /* device -> interface -> handle -> context, in that order */
}

} // namespace bridge

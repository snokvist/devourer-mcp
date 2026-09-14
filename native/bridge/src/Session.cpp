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

/* How many parsed tx.report entries a session keeps before evicting the
 * oldest. Enough to see the last burst; the cumulative total and a dropped
 * count are reported beside it, so eviction is visible rather than silent. */
constexpr size_t kTxReceiptRing = 256;

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
  /* One logger per session, not shared. Diagnostics still go to stderr, so
   * this changes nothing a reader sees; the reason is the EventSink: it is a
   * single FILE*, and `tx.report` capture has to point each session's sink at
   * its own stream rather than have every session's events interleave into
   * one. */
  return std::make_shared<Logger>();
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

/* Does this adapter's capability report include a channel width? The width
 * changes are capability-gated everywhere else (monitor_start, radio.channel);
 * FastSetBandwidth must be too, or a family with no narrowband path reports a
 * width it silently kept its old one at. */
bool width_in_caps(const devourer::AdapterCaps &caps, ChannelWidth_t w) {
  switch (w) {
  case CHANNEL_WIDTH_5:
    return caps.bw_mask & devourer::kBw5;
  case CHANNEL_WIDTH_10:
    return caps.bw_mask & devourer::kBw10;
  case CHANNEL_WIDTH_20:
    return caps.bw_mask & devourer::kBw20;
  case CHANNEL_WIDTH_40:
    return caps.bw_mask & devourer::kBw40;
  case CHANNEL_WIDTH_80:
    return caps.bw_mask & devourer::kBw80;
  case CHANNEL_WIDTH_160:
    return caps.bw_mask & devourer::kBw160;
  default:
    return false;
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

void Session::handle_tx_event_line(const std::string &line) {
  if (line.empty() || line[0] != '{')
    return;
  Json ev;
  std::string err;
  if (!Json::parse(line, ev, err))
    return;
  if (ev.at("ev").str() != "tx.report")
    return;
  Json r;
  r.set("t_ms", ev.at("t").integer(0))
      .set("state", ev.at("state").integer(0))
      .set("ok", ev.at("ok").boolean(false))
      .set("retries", ev.at("retries").integer(0))
      .set("final_rate", ev.at("final_rate").integer(0))
      .set("queue_time_raw", ev.at("queue_time_raw").integer(0))
      .set("bmc", ev.at("bmc").boolean(false))
      .set("macid", ev.at("macid").integer(0))
      .set("fmt", ev.at("fmt").str());
  /* HalMAC-only fields; absent on the 8812 (Jaguar1) format. */
  if (ev.has("tag"))
    r.set("tag", ev.at("tag").integer(0));
  if (ev.has("rts_retries"))
    r.set("rts_retries", ev.at("rts_retries").integer(0));

  std::lock_guard<std::mutex> lk(_txr_mu);
  ++_txr_total;
  _txr.push_back(std::move(r));
  while (_txr.size() > kTxReceiptRing) {
    _txr.pop_front();
    ++_txr_dropped;
  }
}

void Session::drain_tx_receipts() {
  if (_txr_file == nullptr)
    return;
  /* EveryLine flush means each event is a complete line already in the file;
   * flush once more for any fixed-policy build and to order against writers. */
  std::fflush(_txr_file);
  const int fd = fileno(_txr_file);
  if (fd < 0)
    return;
  std::string chunk;
  char tmp[4096];
  for (;;) {
    const ssize_t n = pread(fd, tmp, sizeof(tmp), _txr_read_off);
    if (n <= 0)
      break;
    chunk.append(tmp, static_cast<size_t>(n));
    _txr_read_off += n;
    if (static_cast<size_t>(n) < sizeof(tmp))
      break;
  }
  if (chunk.empty() && _txr_tail.empty())
    return;
  std::string buf = _txr_tail;
  buf += chunk;
  size_t start = 0;
  for (;;) {
    const size_t nl = buf.find('\n', start);
    if (nl == std::string::npos)
      break;
    handle_tx_event_line(buf.substr(start, nl - start));
    start = nl + 1;
  }
  _txr_tail = buf.substr(start);
}

Json Session::tx_receipts_json(bool clear) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id).set("enabled", _txr_enabled).set("sampling", _txr_sampling);
  if (_txr_enabled)
    drain_tx_receipts();
  {
    std::lock_guard<std::mutex> lk(_txr_mu);
    Json arr = Json::array();
    for (const auto &r : _txr)
      arr.push(r);
    j.set("total", _txr_total)
        .set("dropped", _txr_dropped)
        .set("buffered", static_cast<uint64_t>(_txr.size()))
        .set("receipts", arr);
    if (clear)
      _txr.clear();
  }
  if (!_txr_enabled)
    j.set("why",
          "tx.report was not enabled at open. Reopen with tx_report=N (1 = "
          "every frame, N>1 = every Nth) and keep an RX loop running so the "
          "C2H reports are delivered.");
  else
    j.set("note",
          "one entry per REPORTED frame, not per frame sent: with sampling N "
          "only every Nth frame is reported, and the report stream itself can "
          "drop under load. tx_stats is the host-side submission count and "
          "cannot see retries or the final rate the way these do. On Jaguar3 "
          "the MISSED_RPT field is a constant, so gauge emission drops from "
          "tag gaps, not from it. Not every family emits these: the CCX report "
          "is a HalMAC/Jaguar facility, so an adapter without it (MT7612U, "
          "RTL8733B) accepts tx_report and then reports nothing.");
  return j;
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
  s->_logger = logger;

  /* tx.report capture: point this session's EventSink at a temp file before
   * any radio thread exists, so no event is written to stdout or to a stream
   * another session shares. */
  if (opts.tx_report > 0) {
    FILE *f = std::tmpfile();
    if (f == nullptr) {
      code = "tx_report_unavailable";
      msg = "could not open a temp file to capture tx.report events";
      return nullptr;
    }
    logger->events().configure(f, devourer::EventSink::FlushPolicy::EveryLine);
    s->_txr_file = f;
    s->_txr_enabled = true;
    s->_txr_sampling = opts.tx_report;
  }

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
  cfg.tx.report = opts.tx_report;
  cfg.tx.retry_limit = opts.tx_retry_limit;
  cfg.tx.ack_timeout_us = opts.tx_ack_timeout_us;
  if (opts.tx_retry_fallback_off)
    cfg.tx.retry_fallback = devourer::RetryFallback::Off;
  cfg.tx.usb_agg_max = opts.usb_agg_max;
  s->_noise_floor_requested = opts.noise_floor;
  /* Say when a session is brought up on anything but the default tuning. A
   * run whose behaviour depends on an option nobody can see afterwards is
   * not reproducible, and all of these change what the radio does. */
  if (opts.noise_floor || opts.adaptive_gain || opts.tx_report > 0 ||
      opts.tx_retry_limit != 0 || opts.tx_retry_fallback_off ||
      opts.usb_agg_max > 0 ||
      opts.tx_ack_timeout_us != 128) {
    logger->info("bridge: open with noise_floor={} adaptive_gain={} "
                 "tx_report={} retry_limit={} ack_timeout_us={} "
                 "retry_fallback_off={} usb_agg_max={}",
                 opts.noise_floor, opts.adaptive_gain, opts.tx_report,
                 opts.tx_retry_limit, opts.tx_ack_timeout_us,
                 opts.tx_retry_fallback_off, opts.usb_agg_max);
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

  Json st;
  st.set("brought_up", _up.load())
      .set("monitoring", _rx_running.load())
      /* Either gate off, not both: a radio with only the energy gate
       * disabled is still transmitting without fully listening, and this
       * field is what the dashboard's warning and the MCP reply key off. */
      .set("cca_disabled", cca_disabled());
  /* The two gate bits are a hardware reading, not a remembered request, and
   * a backend that cannot give one gets neither field. Reporting the
   * session's defaults here would present "we never asked" as "the gates are
   * on", which is the same fabricated-measurement failure the not-ported
   * default exists to avoid — and an RTL8733BU, which implements SetCcaMode
   * but not the split, is a real device that hits it. */
  {
    bool primary = false, edcca = false, is_rtl = false;
    if (read_cca_gates(primary, edcca, is_rtl))
      st.set("primary_cca_disabled", primary).set("edcca_disabled", edcca);
  }
  j.set("state", st);
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

bool Session::fast_retune(int channel, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "radio is not brought up — use radio.channel first; a fast retune "
          "assumes a live channel to move from";
    return false;
  }
  if (channel < 0 || channel > 255) {
    err = "channel must be 0..255";
    return false;
  }
  try {
    _radio->FastRetune(static_cast<uint8_t>(channel));
  } catch (const std::exception &e) {
    err = std::string("FastRetune threw: ") + e.what();
    return false;
  }
  _channel.Channel = static_cast<uint8_t>(channel);
  return true;
}

bool Session::fast_bandwidth(ChannelWidth_t width, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "radio is not brought up — use radio.channel first";
    return false;
  }
  if (!width_in_caps(_radio->GetAdapterCaps(), width)) {
    err = std::to_string(width_mhz_of(width)) +
          " MHz is not a width this adapter supports";
    return false;
  }
  try {
    _radio->FastSetBandwidth(width);
  } catch (const std::exception &e) {
    err = std::string("FastSetBandwidth threw: ") + e.what();
    return false;
  }
  _channel.ChannelWidth = width;
  return true;
}

Json Session::channel_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("channel", _channel.Channel)
      .set("width", width_mhz_of(_channel.ChannelWidth))
      .set("offset", _channel.ChannelOffset)
      .set("band", _channel.Band);
  bool fast = false;
  if (_radio != nullptr)
    fast = _radio->GetAdapterCaps().fastretune_ok;
  j.set("fast_retune", fast);
  return j;
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

size_t Session::send_frames(const std::vector<std::vector<uint8_t>> &frames,
                            std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (!_up) {
    err = "radio is not brought up";
    return 0;
  }
  if (frames.empty())
    return 0;
  /* Views borrow the caller's frame buffers; send_packets completes (or skips)
   * every one before returning, so the pointers stay valid for the call. */
  std::vector<TxPacketView> views;
  views.reserve(frames.size());
  for (const auto &f : frames)
    views.push_back(TxPacketView{f.data(), f.size()});
  const size_t sent = _radio->send_packets(views.data(), views.size());
  {
    std::lock_guard<std::mutex> sl(_stats_mu);
    _stats.tx_sent += sent;
    _stats.tx_failed += frames.size() - sent;
  }
  if (sent != frames.size())
    err = "send_packets submitted " + std::to_string(sent) + " of " +
          std::to_string(frames.size()) +
          " (queue full or TX path down)";
  return sent;
}

bool Session::supports_per_packet_txpower() const {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr)
    return false;
  return _radio->GetAdapterCaps().per_packet_txpower;
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
  /* SetCcaMode moves BOTH gates, so both per-gate flags follow it. Writing a
   * separate combined flag here is what let describe report
   * cca_disabled=false while primary_cca_disabled and edcca_disabled were
   * both true — one object, two answers, and the false one is the field the
   * dashboard warning keys off. */
  _cca_primary_disabled = disabled;
  _cca_edcca_disabled = disabled;
  return true;
}

bool Session::read_cca_gates(bool &primary, bool &edcca, bool &is_rtl) const {
  auto *rtl = dynamic_cast<IRtlRadio *>(_radio);
  is_rtl = rtl != nullptr;
  if (rtl == nullptr || !_up)
    return false;
  try {
    return rtl->GetCcaGates(primary, edcca);
  } catch (const std::exception &) {
    /* A dying or half-unplugged adapter is exactly when someone asks. Fold
     * the throw into "no reading" the way GetPermanentMacAddress does rather
     * than letting it escape as internal_error. */
    return false;
  }
}

/* Why the gate split is unavailable, distinguishing the three reasons that
 * all arrive here as "GetCcaGates returned false". Before the RTL8733BU
 * joined the bench every Realtek adapter here implemented the split, so
 * "is an IRtlRadio" and "supports the split" were the same question and
 * this collapsed into one message — which then told a brought-up 8733BU
 * that it was not brought up. */
const char *Session::cca_split_unavailable_reason(bool is_rtl) const {
  if (!is_rtl)
    return "splitting the carrier-sense gate is a Realtek 0x520 facility "
           "(IRtlRadio::GetCcaGates) and this is not a Realtek backend; "
           "radio.cca still turns both gates off together";
  if (!_up)
    return "the radio is not brought up — set a channel first; the gate "
           "register is meaningless before then";
  return "this Realtek backend does not implement the carrier-sense gate "
         "split (IRtlRadio::GetCcaGates is optional and not ported here); "
         "radio.cca is the portable all-or-nothing control";
}

Json Session::cca_gates_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  bool primary = false, edcca = false, is_rtl = false;
  if (!read_cca_gates(primary, edcca, is_rtl)) {
    j.set("supported", false)
        .set("why", cca_split_unavailable_reason(is_rtl))
        .set("cca_disabled", cca_disabled());
    return j;
  }
  j.set("supported", true)
      .set("primary_cca_disabled", primary)
      .set("edcca_disabled", edcca)
      /* The combined state has to be here too. The unsupported branch already
       * carries it, and describe's state does; omitting it from the supported
       * reply was a real inconsistency a radio with EDCCA off exposed: both
       * per-gate fields were true, and cca_disabled read false. */
      .set("cca_disabled", primary || edcca)
      .set("note",
           "primary CCA defers to a DECODABLE PREAMBLE; EDCCA defers to raw "
           "in-band ENERGY. Which one matters is FAMILY-SPECIFIC and the two "
           "measured families disagree: on Jaguar3 primary CCA costs an "
           "injector 41-45% against a co-channel flooder and the energy bit "
           "alone is null (devourer tests/dis_cca_tx_onair.sh); on Jaguar1 it "
           "inverts — turning EDCCA off alone recovers 94% on an idle "
           "channel while primary CCA off alone recovers almost nothing. "
           "Measure before assuming either.");
  if (primary || edcca)
    j.set("warning",
          "a carrier-sense gate is OFF: this radio transmits without fully "
          "listening first. Turning BOTH off is also worse for your own "
          "delivery on a busy channel — measured 0.3% against 78% with "
          "primary CCA left on, because the injector collides instead of "
          "waiting for a gap.");
  return j;
}

bool Session::get_cca_gates(bool &primary_disabled, bool &edcca_disabled,
                            std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  bool is_rtl = false;
  if (!read_cca_gates(primary_disabled, edcca_disabled, is_rtl)) {
    err = cca_split_unavailable_reason(is_rtl);
    return false;
  }
  return true;
}

bool Session::set_cca_gates(bool primary_disabled, bool edcca_disabled,
                            std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "radio is not brought up — set a channel first";
    return false;
  }
  auto *rtl = dynamic_cast<IRtlRadio *>(_radio);
  if (rtl == nullptr)
    return (err = cca_split_unavailable_reason(false)), false;
  try {
    if (!rtl->SetCcaGates(primary_disabled, edcca_disabled)) {
      err = cca_split_unavailable_reason(true);
      return false;
    }
  } catch (const std::exception &e) {
    /* Same shape as set_cca: a backend that refuses loudly must not reach
     * the caller as internal_error. */
    err = std::string("carrier-sense gates: ") + e.what();
    return false;
  }
  _cca_primary_disabled = primary_disabled;
  _cca_edcca_disabled = edcca_disabled;
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

Json Session::tx_power_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const auto caps = _radio->GetTxPowerCaps();
  j.set("supported", caps.supported);
  if (!caps.supported) {
    j.set("why",
          "this backend does not wire the runtime TX-power knobs "
          "(IRadio::GetTxPowerCaps is optional and not ported here)");
    return j;
  }
  /* The caps travel with the state: index_max is the clamp on the flat index,
   * the offset range is in qdB, and step_qdb/step_measured are what decide
   * whether a sweep here produces evidence or just indices. */
  j.set("index_max", caps.index_max)
      .set("step_qdb", caps.step_qdb)
      .set("step_measured", caps.step_measured)
      .set("offset_min_qdb", caps.offset_min_qdb)
      .set("offset_max_qdb", caps.offset_max_qdb)
      .set("rate_diffs", caps.rate_diffs)
      .set("rate_diffs_hw_table", caps.rate_diffs_hw_table)
      .set("rate_diffs_measured", caps.rate_diffs_measured);

  const auto st = _radio->GetTxPowerState();
  j.set("valid", st.valid);
  if (!st.valid) {
    /* The last offset the backend reported APPLYING, when we have one. This
     * is what carries an MT7612U sweep: its dBm-model GetTxPowerState is the
     * all-invalid default, so offset_qdb never appears above. */
    if (_last_applied_offset_qdb)
      j.set("applied_offset_qdb", *_last_applied_offset_qdb);
    j.set("why",
          "the backend reported no applied state. On a family that does not "
          "override GetTxPowerState (the MT7612U's dBm model) this is normal — "
          "the caps are valid and setting works, only the state readback is "
          "missing, so read applied_offset_qdb. Otherwise the chip is not "
          "brought up yet: retune or start a monitor first.");
    return j;
  }
  j.set("flat_index", st.flat_index)
      .set("offset_qdb", st.offset_qdb)
      .set("offset_steps", st.offset_steps)
      .set("saturated_low", st.saturated_low)
      .set("saturated_high", st.saturated_high)
      .set("cck_index", st.cck_index)
      .set("ofdm_index", st.ofdm_index)
      .set("mcs7_index", st.mcs7_index)
      .set("hw_readback", st.hw_readback)
      .set("rate_diffs_custom", st.rate_diffs_custom);
  if (!caps.step_measured)
    j.set("note",
          "step_measured is false: this family's dB-per-step slope has not "
          "been validated on air, so a power change here moves the index by a "
          "documented amount, not a measured one. Treat relative index "
          "comparisons as real and absolute dBm claims as extrapolated.");
  else if (!st.hw_readback)
    j.set("note",
          "hw_readback is false: the representative indices are the driver's "
          "software shadow, not a register readback (this family's TXAGC port "
          "is write-only).");
  return j;
}

bool Session::set_tx_power(std::optional<int> offset_qdb,
                           std::optional<int> index_override, bool set_rate_diffs,
                           const std::optional<devourer::TxRateDiffsQdb> &rate_diffs,
                           bool reapply, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  const auto caps = _radio->GetTxPowerCaps();
  if (!caps.supported) {
    err = "this backend does not wire the runtime TX-power knobs";
    return false;
  }
  /* Applied in the order the model composes: the per-rate shape first (it
   * REPLACES the chip's), then the flat index sets the baseline, then the
   * offset folds onto it, then a reapply forces the result at the current
   * channel. A refused reapply is the one hard failure — it is false when the
   * chip is not brought up. */
  if (set_rate_diffs) {
    if (!caps.rate_diffs) {
      err = "this backend does not honour per-rate TX-power diffs";
      return false;
    }
    if (!_radio->SetTxPowerRateDiffs(rate_diffs)) {
      err = "the backend refused the per-rate diff table";
      return false;
    }
  }
  if (index_override) {
    /* index_max == 0 is the dBm-model marker: no flat index exists, and the
     * backend's SetTxPowerIndexOverride silently logs-and-returns, so a
     * granted-looking request would be a no-op reported as success. */
    if (caps.index_max == 0) {
      err = "this backend has no flat TXAGC index (the dBm model); "
            "index_override is not a knob here";
      return false;
    }
    if (*index_override < -1 || *index_override > caps.index_max) {
      err = "index_override must be -1 (clear) or 0.." +
            std::to_string(caps.index_max);
      return false;
    }
    _radio->SetTxPowerIndexOverride(*index_override);
  }
  if (offset_qdb) {
    if (*offset_qdb < caps.offset_min_qdb || *offset_qdb > caps.offset_max_qdb) {
      err = "offset_qdb must be " + std::to_string(caps.offset_min_qdb) + ".." +
            std::to_string(caps.offset_max_qdb);
      return false;
    }
    /* The return is the APPLIED qdB after quantization/rail clamp — the only
     * applied value a family without a GetTxPowerState override (MT7612U) can
     * report. */
    _last_applied_offset_qdb = _radio->SetTxPowerOffsetQdb(*offset_qdb);
  }
  if (reapply && !_radio->ReApplyTxPower()) {
    err = "the backend refused to re-apply TX power — is the chip brought up?";
    return false;
  }
  return true;
}

namespace {
const char *link_verdict_name(devourer::LinkVerdict v) {
  switch (v) {
  case devourer::LinkVerdict::NoSignal: return "NO_SIGNAL";
  case devourer::LinkVerdict::Saturated: return "SATURATED";
  case devourer::LinkVerdict::Interference: return "INTERFERENCE";
  case devourer::LinkVerdict::Weak: return "WEAK";
  case devourer::LinkVerdict::Marginal: return "MARGINAL";
  case devourer::LinkVerdict::Healthy: return "HEALTHY";
  }
  return "UNKNOWN";
}
} // namespace

Json Session::rx_quality_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const auto q = _radio->GetRxQuality();
  /* The IRadio default is an all-invalid snapshot whose verdict text is empty
   * ("NO_SIGNAL" with no cause); a wired backend with no frames still fills
   * cause/fix from the classifier. So the presence of verdict text is the
   * capability signal — an IRtlRadio downcast is NOT one, because
   * Rtl8733bDevice derives from IRtlRadio and does not override this. */
  const bool wired = q.valid || (q.cause != nullptr && q.cause[0] != '\0');
  if (!wired) {
    j.set("supported", false)
        .set("why",
             "this backend returned the all-invalid GetRxQuality default (no "
             "window and no verdict text), so it does not wire the fused feed")
        .set("fallback",
             "capture_summary and antenna_check read the decoded frames "
             "instead — a different quantity, but honest about what this "
             "silicon can report");
    return j;
  }
  j.set("supported", true)
      .set("valid", q.valid)
      .set("frames", q.frames)
      .set("rssi_mean_dbm", q.rssi_mean_dbm)
      .set("rssi_max_dbm", q.rssi_max_dbm)
      .set("snr_mean_db", q.snr_mean_db)
      .set("snr_min_db", q.snr_min_db)
      .set("snr_valid", q.snr_valid)
      .set("evm_mean_db", q.evm_mean_db)
      .set("evm_valid", q.evm_valid)
      .set("noise_floor_dbm", q.noise_floor_dbm)
      .set("nf_valid", q.nf_valid)
      .set("abs_noise_floor_dbm", q.abs_noise_floor_dbm)
      .set("abs_nf_valid", q.abs_nf_valid)
      .set("energy_valid", q.energy_valid)
      .set("fa_ofdm", q.fa_ofdm)
      .set("cca_ofdm", q.cca_ofdm)
      .set("igi_valid", q.igi_valid)
      .set("igi", q.igi)
      .set("verdict", link_verdict_name(q.verdict))
      .set("label", q.label ? q.label : "")
      .set("cause", q.cause ? q.cause : "")
      .set("fix", q.fix ? q.fix : "")
      .set("igi_at_floor", q.igi_at_floor)
      .set("igi_at_ceiling", q.igi_at_ceiling);
  if (!q.valid)
    j.set("why",
          "no frames were decoded in this window; read again after a dwell. "
          "The window DRAINS on every read, so this is the interval since the "
          "previous one.");
  /* The per-frame PWDB is documented as raw 0..127 (dBm -110..17). A peak
   * above that means the parser folded a value outside the field's range into
   * the window — measured on the 8822C, where some frames report raw RSSI up
   * to 247 — and the fused verdict is then built on a bad sample. Say so
   * rather than let a plausible-looking SATURATED stand. */
  else if (q.rssi_max_dbm > 20 || q.rssi_mean_dbm > 20)
    j.set("note",
          "the window's RSSI is outside the documented PWDB range (raw 0..127 "
          "-> -110..17 dBm), so at least one frame reported a signal value the "
          "parser should not produce. The peak and any verdict derived from it "
          "are suspect; this is a known parser class on 8822C silicon, not a "
          "real 130 dBm signal and not necessarily front-end saturation.");
  return j;
}

Json Session::thermal_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const auto t = _radio->GetThermalStatus();
  /* A real meter reading is never raw 0 with no baseline: raw 0 is not a
   * plausible RF 0x42 value, so that pair means the backend did not wire
   * GetThermalStatus at all. A backend with a meter but no baseline reports
   * supported with valid=false, because raw is still meaningful there. */
  const bool supported = t.valid || t.raw != 0;
  j.set("supported", supported);
  if (!supported) {
    j.set("why",
          "this backend returned no thermal reading (raw 0, no baseline); it "
          "may not wire GetThermalStatus (only the Jaguar backends override "
          "it) — this is not a claim that the chip is cool");
    return j;
  }
  j.set("raw", t.raw)
      .set("baseline", t.baseline)
      .set("delta", t.delta)
      .set("valid", t.valid)
      .set("bucket", devourer::ThermalBucket(t));
  j.set("note",
        "raw is RF 0x42 thermal units (roughly 1.5-2 C each), NOT absolute "
        "degrees; delta is raw minus the baseline (on the 8822C, which wires "
        "no efuse baseline, that is since the first read). Telemetry, not a "
        "validated degradation predictor — read it beside a rate ceiling, "
        "never as the cause of one.");
  return j;
}

Json Session::ack_responder_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const bool supported = _radio->GetAdapterCaps().ack_responder_ok;
  j.set("supported", supported).set("armed", _ack_responder_armed);
  if (_ack_responder_armed)
    j.set("mac", _ack_responder_mac);
  if (!supported)
    j.set("why",
          "this adapter does not report a hardware ACK responder "
          "(AdapterCaps.ack_responder_ok is false)");
  else
    j.set("note",
          "arming makes the MAC hardware-ACK unicast frames addressed to `mac` "
          "with no host involvement, so a peer transmitting there retries in "
          "hardware until the ACK. Clearing is best effort and does not promise "
          "silence — see IRadio::SetAckResponder's contract.");
  return j;
}

bool Session::set_ack_responder(const std::string &mac, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_radio->GetAdapterCaps().ack_responder_ok) {
    err = "this adapter does not report a hardware ACK responder";
    return false;
  }
  if (!_up) {
    err = "bring the radio up before arming the ACK responder";
    return false;
  }
  const auto parsed = devourer::parse_mac(mac);
  if (!parsed) {
    err = "mac must be an address like aa:bb:cc:dd:ee:ff";
    return false;
  }
  if ((parsed->bytes[0] & 0x01) != 0) {
    err = "mac must be unicast (the group bit is set); a multicast address "
          "would make the responder answer frames not addressed to it";
    return false;
  }
  try {
    if (!_radio->SetAckResponder(*parsed)) {
      err = "the backend refused to arm the ACK responder";
      return false;
    }
  } catch (const std::exception &e) {
    err = std::string("SetAckResponder threw: ") + e.what();
    return false;
  }
  _ack_responder_armed = true;
  _ack_responder_mac = mac;
  return true;
}

bool Session::clear_ack_responder(std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_radio->GetAdapterCaps().ack_responder_ok) {
    err = "this adapter does not report a hardware ACK responder";
    return false;
  }
  try {
    _radio->ClearAckResponder();
  } catch (const std::exception &e) {
    err = std::string("ClearAckResponder threw: ") + e.what();
    return false;
  }
  _ack_responder_armed = false;
  _ack_responder_mac.clear();
  return true;
}

bool Session::write_tsf(uint64_t tsf_us, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "bring the radio up before writing the TSF";
    return false;
  }
  try {
    _radio->WriteTsf(tsf_us);
  } catch (const std::exception &e) {
    err = std::string("WriteTsf threw: ") + e.what();
    return false;
  }
  return true;
}

/* The MPDU length after an optional leading radiotap header, stripped exactly
 * as the backends strip it (it_len at bytes [2:3], LE; a malformed it_len is
 * treated as "no radiotap"). Reporting only — the backend does the strip. */
static size_t beacon_mpdu_bytes(const std::vector<uint8_t> &b) {
  size_t rt = b.size() >= 4 ? static_cast<size_t>(b[2] | (b[3] << 8)) : 0;
  if (rt > b.size())
    rt = 0;
  return b.size() - rt;
}

Json Session::beacon_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  j.set("supported", true)
      .set("active", _beacon_active)
      .set("interval_tu", _beacon_active ? _beacon_interval_tu : 0)
      .set("frame_bytes",
           _beacon_active ? static_cast<int64_t>(_beacon_frame_bytes) : 0)
      .set("mpdu_bytes",
           _beacon_active ? static_cast<int64_t>(_beacon_mpdu_bytes) : 0);
  if (_beacon_active)
    j.set("note",
          "active/interval_tu/frame_bytes are the bridge's record of what it "
          "asked the backend to do — IRadio has no beacon getter. The chip "
          "beacons AUTONOMOUSLY: the MAC airs it at every TBTT with the live "
          "TSF stamped, no host involvement, until it is stopped or the "
          "session is torn down.");
  else
    j.set("note",
          "no beacon is armed on this session. Starting one makes the MAC "
          "auto-transmit at every TBTT, hardware-timed and hardware-TSF-"
          "stamped, until stopped.");
  return j;
}

bool Session::start_beacon(const std::vector<uint8_t> &beacon, int interval_tu,
                           std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "bring the radio up before arming a beacon";
    return false;
  }
  try {
    if (!_radio->StartBeacon(beacon.data(), beacon.size(), interval_tu)) {
      err = "the backend refused to arm the beacon (no beacon engine on this "
            "backend, or it rejected the payload/interval)";
      return false;
    }
  } catch (const std::exception &e) {
    err = std::string("StartBeacon threw: ") + e.what();
    return false;
  }
  _beacon_active = true;
  _beacon_interval_tu = interval_tu;
  _beacon_frame_bytes = beacon.size();
  _beacon_mpdu_bytes = beacon_mpdu_bytes(beacon);
  return true;
}

bool Session::update_beacon_payload(const std::vector<uint8_t> &beacon,
                                    std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "bring the radio up before updating a beacon";
    return false;
  }
  if (!_beacon_active) {
    err = "no beacon is active on this session — start one first "
          "(IRadio::UpdateBeaconPayload requires an active beacon)";
    return false;
  }
  try {
    if (!_radio->UpdateBeaconPayload(beacon.data(), beacon.size())) {
      err = "the backend refused to update the active beacon";
      return false;
    }
  } catch (const std::exception &e) {
    err = std::string("UpdateBeaconPayload threw: ") + e.what();
    return false;
  }
  _beacon_frame_bytes = beacon.size();
  _beacon_mpdu_bytes = beacon_mpdu_bytes(beacon);
  return true;
}

bool Session::stop_beacon(bool &was_active, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  was_active = _beacon_active;
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_beacon_active) {
    /* IRadio::StopBeacon returns false when no beacon was active — the mirror
     * is what tells that from a failed stop, so this is not an error. */
    return true;
  }
  try {
    if (!_radio->StopBeacon()) {
      err = "the backend did not confirm the beacon stopped — the MAC may "
            "still be airing it. Retry, and power-cycle the adapter if it "
            "persists.";
      return false;
    }
  } catch (const std::exception &e) {
    err = std::string("StopBeacon threw: ") + e.what();
    return false;
  }
  _beacon_active = false;
  _beacon_interval_tu = 0;
  _beacon_frame_bytes = 0;
  _beacon_mpdu_bytes = 0;
  return true;
}

Json Session::ampdu_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  const auto m = _radio->GetAmpduMode();
  j.set("enabled", m.enabled)
      .set("tid", m.tid)
      .set("max_num", m.max_num)
      .set("density", m.density)
      .set("no_ack", m.no_ack)
      .set("max_time", m.max_time)
      .set("clear_burst_mode", m.clear_burst_mode);
  const bool unknown = !_ampdu_supported && !m.enabled;
  j.set("capability",
        _ampdu_supported ? (*_ampdu_supported ? "supported" : "unsupported")
                         : (m.enabled ? "supported" : "unknown"));
  if (unknown)
    j.set("note",
          "a read cannot tell a supported-but-off backend from one that does "
          "not wire SetAmpduMode — the cleared state is the same bytes. Set a "
          "mode to find out which.");
  else if (j.at("capability").str() == std::string("supported"))
    j.set("note",
          "A-MPDU needs the TX queue fed deep enough for the MAC to aggregate; "
          "a shallow feed produces single-MPDU aggregates and no goodput gain. "
          "The batch feed (experiment_link_probe batch:true with radio_open "
          "usb_agg) is the deep path that measures the ~+30% at high MCS; a "
          "single-frame send loop will not.");
  return j;
}

bool Session::set_ampdu(const devourer::AmpduMode &mode, std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "bring the radio up before enabling A-MPDU";
    return false;
  }
  if (mode.enabled) {
    if (mode.tid > 7) {
      err = "tid must be 0..7 (the QSEL the aggregatable frames ride)";
      return false;
    }
    if (mode.max_num < 1 || mode.max_num > 0x1f) {
      err = "max_num must be 1..31";
      return false;
    }
    if (mode.density > 7) {
      err = "density must be 0..7";
      return false;
    }
  }
  try {
    if (!_radio->SetAmpduMode(mode)) {
      _ampdu_supported = false;
      err = "the backend refused the A-MPDU mode (unsupported, or the chip is "
            "not brought up)";
      return false;
    }
  } catch (const std::exception &e) {
    _ampdu_supported = false;
    err = std::string("SetAmpduMode threw: ") + e.what();
    return false;
  }
  _ampdu_supported = true;
  return true;
}

bool Session::clear_ampdu(std::string &err) {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  if (_radio == nullptr) {
    err = "session has no radio";
    return false;
  }
  if (!_up) {
    err = "bring the radio up before changing A-MPDU";
    return false;
  }
  try {
    _radio->ClearAmpduMode();
  } catch (const std::exception &e) {
    err = std::string("ClearAmpduMode threw: ") + e.what();
    return false;
  }
  return true;
}

Json Session::tsf_json() {
  std::lock_guard<std::recursive_mutex> life(_life_mu);
  Json j;
  j.set("session", _id);
  if (_radio == nullptr) {
    j.set("supported", false).set("why", "session has no radio");
    return j;
  }
  if (!_up) {
    j.set("supported", true).set("readable", false).set("why",
          "the radio is not brought up — retune or start a monitor before "
          "reading the TSF");
    return j;
  }
  const uint64_t tsf = _radio->ReadTsf();
  const bool readable = tsf != 0;
  j.set("supported", true)
      .set("readable", readable)
      .set("tsf_us", static_cast<int64_t>(tsf));
  if (!readable)
    j.set("why",
          "ReadTsf returned 0 — the MAC clock is not running, or this backend "
          "does not wire it (the RTL8733B family reports 0)");
  else
    j.set("note",
          "microseconds since the MAC's epoch; each received frame carries a "
          "MAC-latched timestamp in its tsfl. NOT synchronized to any external "
          "clock on its own — adoption requires WriteTsf or a timing protocol.");
  return j;
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
    /* A beacon airs autonomously, so silence it before the chip is torn down:
     * Jaguar2 has no teardown power-down, so an armed beacon would keep airing
     * until the adapter is re-enumerated. Best-effort — Stop() below powers
     * down whatever this does not. */
    if (_beacon_active) {
      try {
        if (_radio->StopBeacon())
          _beacon_active = false;
      } catch (...) {
      }
    }
    /* Clean chip de-init before the interface is dropped, so the adapter
     * re-enumerates instead of hanging its USB core. */
    _radio->Stop();
    _up = false;
  }
  _radio = nullptr;
  _dev.reset(); /* device -> interface -> handle -> context, in that order */
  /* Stop the tx.report capture: disable the sink before closing the file, so
   * a late event write is skipped rather than touching a closed FILE. */
  if (_logger)
    _logger->events().disable();
  if (_txr_file != nullptr) {
    std::fclose(_txr_file);
    _txr_file = nullptr;
  }
  _logger.reset();
}

} // namespace bridge

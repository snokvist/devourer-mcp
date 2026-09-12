#include "Session.h"

#include <libusb.h>

#include <cerrno>
#include <cstring>
#include <ctime>
#include <unistd.h>

#include "AdapterCaps.h"
#include "DeviceConfig.h"
#include "IRadio.h"
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

Session::~Session() { close(); }

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
    msg = (rc == LIBUSB_ERROR_BUSY)
              ? "adapter is already in use by another process"
              : std::string("claim/reset: ") + libusb_error_name(rc);
    return nullptr;
  }
  dev_session->adopt_handle(handle);
  dev_session->adopt_lock(lock);

  WiFiDriver driver(logger);
  auto radio = driver.CreateRadio(handle, ctx, lock, devourer::DeviceConfig{});
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
                     .set("monitoring", _rx_running.load()));
  if (_up) {
    j.set("channel", Json()
                         .set("channel", _channel.Channel)
                         .set("width", static_cast<int>(_channel.ChannelWidth))
                         .set("offset", _channel.ChannelOffset)
                         .set("band", _channel.Band));
  }
  return j;
}

bool Session::bring_up(SelectedChannel ch, std::string &err) {
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
  if (!_up) {
    err = "radio is not brought up";
    return false;
  }
  if (_rx_running) {
    err = "already monitoring";
    return false;
  }
  _rx_running = true;
  _rx_thread = std::thread([this] {
    _radio->StartRxLoop([this](const Packet &p) { on_packet(p); });
    _rx_running = false;
  });
  return true;
}

void Session::stop_monitor() {
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
  h.phy_fill = 0; /* PhyStsFill is parser-local; not on rx_pkt_attrib */
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
  detach_sink();
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
  _buf_cv.notify_all();
  _writer.join();
}

void Session::writer_loop() {
  std::vector<uint8_t> chunk;
  for (;;) {
    {
      std::unique_lock<std::mutex> lk(_buf_mu);
      _buf_cv.wait(lk, [this] {
        return _writer_stop.load() || _buf.size() > _buf_head;
      });
      if (_writer_stop && _buf.size() == _buf_head)
        return;
      chunk.assign(_buf.begin() + static_cast<long>(_buf_head), _buf.end());
      _buf.clear();
      _buf_head = 0;
    }
    size_t off = 0;
    while (off < chunk.size()) {
      const ssize_t w = ::write(_sink_fd, chunk.data() + off, chunk.size() - off);
      if (w > 0) {
        off += static_cast<size_t>(w);
        continue;
      }
      if (w < 0 && (errno == EINTR))
        continue;
      /* The client went away mid-record. There is no honest recovery: the
       * stream is now truncated at an arbitrary byte, so drop the sink and let
       * the client reattach (which resets the buffer). */
      std::lock_guard<std::mutex> sl(_stats_mu);
      _stats.write_errors++;
      return;
    }
  }
}

bool Session::send_frame(const uint8_t *data, size_t len, std::string &err) {
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

#ifndef DEVOURER_BRIDGE_SESSION_H
#define DEVOURER_BRIDGE_SESSION_H

/* One opened radio: its libusb lifetime, its IRadio, its RX thread, and the
 * frame stream feeding the client.
 *
 * Bring-up follows the contract IRadio documents: InitWrite() powers the chip
 * and sets the channel, then StartRxLoop() runs the blocking receive loop on a
 * thread of ours. That pairing — rather than Init(), which is the RX-only
 * convenience wrapper that does both — is what lets TX and RX run concurrently
 * on one claimed handle, which every two-radio experiment needs.
 *
 * BACKPRESSURE IS A CORRECTNESS CONSTRAINT, NOT A TUNING KNOB. Devourer's
 * MT7612U notes put it plainly: a receiver left undrained wedges the part. So
 * the RX callback must never block on a slow client. Frames are appended to a
 * bounded in-process buffer under a short lock and drained by a separate
 * writer thread; when the buffer is full the RX callback drops a WHOLE record
 * and counts it. Dropping is visible (monitor.stats reports it) and never
 * corrupts the stream — a partially written record would desynchronize the
 * reader for every frame after it. */

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Devices.h"
#include "Json.h"
#include "SelectedChannel.h"

namespace devourer {
class DeviceSession;
}
class IRadio;
struct Packet;

namespace bridge {

struct OpenOptions {
  uint8_t bus = 0;
  uint8_t address = 0;
  bool reset = true;       /* libusb_reset_device during claim */
  size_t buffer_bytes = 16u << 20; /* frame buffer high-water */
  uint32_t max_frame_bytes = 4096; /* payload cap; excess marks truncated */
};

struct SessionStats {
  uint64_t frames = 0;      /* handed to the sink (or would have been) */
  uint64_t bytes = 0;
  uint64_t dropped = 0;     /* buffer full — client too slow */
  uint64_t write_errors = 0;
  uint64_t tx_sent = 0;
  uint64_t tx_failed = 0;
};

class Session {
public:
  ~Session();

  /* Opens and claims the adapter and constructs the IRadio. Does NOT power up
   * the chip — bring_up() does, because the channel is not known until the
   * caller asks to monitor. On failure `code` is a stable machine token
   * ("busy", "not_found", "unsupported_chip", "permission") and `msg` carries
   * the detail. */
  static std::unique_ptr<Session> open(uint32_t id, const OpenOptions &opts,
                                       std::string &code, std::string &msg);

  uint32_t id() const { return _id; }
  const DeviceInfo &device() const { return _info; }
  bool is_up() const { return _up; }
  bool is_monitoring() const { return _rx_running; }

  /* Static capability report: AdapterCaps + TxCaps + TxPowerCaps, plus the
   * permanent MAC. Safe before bring-up (the caps are resolved at
   * construction), which is what makes radio.describe cheap. */
  Json describe();

  bool bring_up(SelectedChannel ch, std::string &err);
  bool start_monitor(std::string &err);
  void stop_monitor();
  bool set_channel(SelectedChannel ch, std::string &err);

  /* Adopt the client's frame-stream socket. Takes ownership of the fd. */
  void attach_sink(int fd);
  void detach_sink();

  bool send_frame(const uint8_t *data, size_t len, std::string &err);

  /* Live estimate of which RF chains are actually carrying signal.
   *
   * The one antenna question that cannot be answered statically: a chain whose
   * antenna is missing or blocked still exists in AdapterCaps, but its RSSI
   * collapses toward the noise floor. Needs a running RX loop and ambient
   * traffic; devourer is explicit that a single window is a hint, not a
   * verdict, since a strong near-field frame can light a dead chain by
   * coupling. */
  Json rx_paths_json();

  /* Driver-side TX submission health (devourer's TxStats): frames handed to the
   * USB stack and how many the stack refused. The missing half of "did it
   * transmit" — send_packet returning true means queued, and this is where a
   * frame that never left the host shows up. */
  Json tx_stats_json();

  /* The MAC carrier-sense gate that defers TX while the channel looks busy.
   *
   * EXPERIMENTAL by nature: disabling it makes the radio transmit without
   * listening first, which is deliberately antisocial on a shared channel. It
   * exists because injection on a quiet bench channel can otherwise be starved
   * by an over-sensitive EDCCA threshold — frames are accepted, reported
   * submitted, and never aired. Bounded use on owned hardware only. */
  bool set_cca(bool disabled, std::string &err);
  bool cca_disabled() const { return _cca_disabled; }

  SessionStats stats() const;
  Json stats_json() const;
  SelectedChannel channel() const { return _channel; }

  void close();

private:
  Session(uint32_t id, DeviceInfo info);

  void on_packet(const Packet &pkt);
  void writer_loop();
  void stop_writer();

  uint32_t _id;
  DeviceInfo _info;
  std::unique_ptr<devourer::DeviceSession> _dev;
  IRadio *_radio = nullptr; /* owned by _dev */

  SelectedChannel _channel{};
  std::atomic<bool> _up{false};
  std::atomic<bool> _rx_running{false};
  std::thread _rx_thread;

  /* --- frame buffer --- */
  mutable std::mutex _buf_mu;
  std::condition_variable _buf_cv;
  std::vector<uint8_t> _buf; /* contiguous FIFO; compacted on drain */
  size_t _buf_head = 0;
  size_t _buf_cap = 0;
  int _sink_fd = -1;
  std::thread _writer;
  std::atomic<bool> _writer_stop{false};

  std::atomic<uint64_t> _seq{0};
  mutable std::mutex _stats_mu;
  SessionStats _stats;
  uint32_t _max_frame_bytes = 4096;
  /* From AdapterCaps at open; stamped into every frame record. */
  uint8_t _rx_chains = 0;
  bool _cca_disabled = false;
};

} // namespace bridge

#endif /* DEVOURER_BRIDGE_SESSION_H */

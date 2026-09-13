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
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "Devices.h"
#include "Json.h"
#include "SelectedChannel.h"
#include "TxPower.h"

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
  /* Ask for the ABSOLUTE frame-free noise floor in rx_energy (DeviceConfig
   * rx.abs_noise_floor). An open-time option rather than a call because
   * devourer reads the config once, at CreateRadio.
   *
   * What it actually reaches, per generation, is worth knowing before relying
   * on it. Jaguar2 measures live inside GetRxEnergy and is wedge-free, so the
   * flag works — best-effort, since the BB's idle-power report is often
   * unpopulated in monitor mode. Jaguar1 8812A/8821A measure it once, RX-idle,
   * inside `IRadio::Init` — and THIS BRIDGE NEVER CALLS Init: it brings a
   * radio up with InitWrite + StartRxLoop, so the CAL never runs and the field
   * stays invalid however this flag is set. Reaching it on a Realtek wave-1
   * part would mean moving bring-up onto Init, which is a change to the one
   * path that currently sustains 6000 frames/s.
   *
   * rx_energy says which of those cases applies rather than leaving the caller
   * to read a bare `valid_noise_floor: false`. */
  bool noise_floor = false;

  /* Run devourer's optional phydm watchdog (DeviceConfig tuning.phydm_watchdog).
   *
   * The thing that makes carrier sense adaptive. Off by default in devourer
   * and therefore here, and that default is load-bearing: without the
   * watchdog, DIG never runs, so the initial-gain index stays pinned at its
   * bring-up value and the EDCCA threshold stays at the static value
   * SetCcaMode programmed. Measured on this bench, igi read 28 (0x1C) on
   * three channels with very different traffic, which is what a pinned gain
   * looks like.
   *
   * With it on, a ~2s thread runs FA statistics and DIG, and SetCcaMode's
   * enable path re-derives the BB 0x8a4 L2H/H2L from the IGI DIG just wrote.
   * Jaguar1 only. It writes BB registers from a background thread, which is
   * why it is opt-in per session rather than always on. */
  bool adaptive_gain = false;
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

  /* Guards attach/detach against each other. Separate from _life_mu so a
   * client attaching cannot block a control op, and never nested inside
   * _buf_mu. */
  mutable std::mutex _sink_mu;

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

  /* The carrier-sense gate, one bit at a time.
   *
   * `radio.cca` is all-or-nothing and on Realtek that is two gates doing
   * different jobs: primary CCA defers to a decodable preamble, EDCCA to raw
   * in-band energy. Telling them apart is the difference between "the channel
   * has traffic on it" and "the channel has energy on it", which is the
   * distinction a deferral diagnosis turns on. */
  Json cca_gates_json();
  bool get_cca_gates(bool &primary_disabled, bool &edcca_disabled, std::string &err);
  bool set_cca_gates(bool primary_disabled, bool edcca_disabled, std::string &err);

  /* Receive gain: the index, its bounds, and whether anything is moving it.
   *
   * Vendor-neutral (IRadio), unlike rx_energy. The index also sets the EDCCA
   * threshold on Realtek, so on that family this is the carrier-sense
   * sensitivity as much as the receive sensitivity. */
  Json rx_gain_json();

  /* Clamp the receive-gain index. min == max pins it. Steers an adaptive loop
   * where one runs rather than overriding it behind its back. Caps describe
   * the supported envelope, not the initial window; callers that need to
   * restore state must remember the initial rx_gain_json() range. */
  bool set_rx_gain(int min, int max, std::string &err);

  /* Runtime TX power, as the index/offset model IRadio documents (TxPower.h),
   * never as dBm: the caps and state both carry `step_qdb`, and `step_measured`
   * is what decides whether a power sweep is evidence or just numbers.
   *
   * Vendor-neutral (IRadio), so this covers every backend that wired the API
   * and reports supported=false for those that did not. The mechanism differs
   * by family: a TXAGC index model (Jaguar/Kestrel) versus an absolute dBm
   * limit with no index (`index_max == 0` — MT7612U, RTL8733B), which is why
   * the caps carry `step_qdb` and `index_max` rather than a single scale. */
  Json tx_power_json();

  /* Apply the TX-power knobs. Each argument is optional; absent leaves that
   * knob alone. `index_override` >= 0 forces a flat index, < 0 clears back to
   * the per-rate table. `reapply` forces a re-apply at the current channel
   * without moving a knob (the register-level check). Returns false only for
   * a hard failure — an unsupported backend reports that through the returned
   * JSON, since a read must still work there.
   *
   * `set_rate_diffs` is a separate flag because an absent table and an
   * explicit clear (`rate_diffs = nullopt`) are different requests: the first
   * leaves the configured shape alone, the second restores the chip's own.
   * Only `rate_diffs`-capable backends accept it; the rest are refused. */
  bool set_tx_power(std::optional<int> offset_qdb, std::optional<int> index_override,
                    bool set_rate_diffs,
                    const std::optional<devourer::TxRateDiffsQdb> &rate_diffs,
                    bool reapply, std::string &err);

  /* The fused, windowed RX sensor (IRadio::GetRxQuality): per-frame
   * RSSI/SNR/EVM aggregate, a passive noise-floor estimate, the frame-free
   * FA/CCA/IGI energy, and the LinkHealth verdict, in one draining read.
   *
   * A backend that does not wire GetRxQuality reports `supported:false` rather
   * than the default's all-invalid snapshot, which would read as a real
   * NO_SIGNAL. The capability signal is the verdict text the classifier
   * fills, NOT an IRtlRadio downcast — Rtl8733bDevice is an IRtlRadio and does
   * not override this. DRAINS: read once to clear, dwell, read again for the
   * window. Do not poll this and rx_energy on the same cadence; on Realtek
   * they consume the same FA/CCA/IGI delta. */
  Json rx_quality_json();

  /* The chip's thermal meter (IRadio::GetThermalStatus): raw RF 0x42 thermal
   * units, the baseline, the delta, and a coarse bucket. Telemetry, not a
   * calibrated temperature, and not a validated degradation predictor.
   *
   * `supported:false` when the backend returned no reading at all (raw 0 with
   * no baseline); a backend that has a meter but no baseline still reports
   * supported with `valid:false`, because raw is meaningful there. */
  Json thermal_json();

  /* Frame-free RX energy: what the chip's own PHY thinks is on the channel,
   * without decoding anything.
   *
   * The measurement the carrier-sense question needs. A frame counter says how
   * much traffic a receiver could decode; EDCCA defers on ENERGY, including
   * energy that never resolves into a frame. These are different quantities,
   * and this is the second one: phydm false-alarm and CCA (channel-busy)
   * counters, the DIG initial-gain index as a noise-floor proxy, and
   * optionally the NHM in-band power histogram.
   *
   * Realtek only — it is IRtlRadio, not IRadio. A MediaTek radio reports
   * unsupported rather than zeros, because zero channel-busy counts and "this
   * chip has no such counter" are opposite claims.
   *
   * FA/CCA are DELTAS since the previous call, which resets them. To measure a
   * window: read once and discard, wait, read again.
   *
   * `with_nhm` is a cost decision. The histogram arms a ~2 ms measurement and
   * then polls for it; the scalars are a handful of register reads. */
  Json rx_energy_json(bool with_nhm);

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
  /* True when carrier sense is not fully on — EITHER gate disabled, not
   * both. It feeds the "this radio transmits without listening" warning, and
   * a radio with only the energy gate off still belongs in that warning. */
  bool cca_disabled() const { return _cca_primary_disabled || _cca_edcca_disabled; }
  /* Why the carrier-sense gate split is unavailable: not a Realtek radio,
   * not brought up, or a Realtek backend that has not ported it. */
  const char *cca_split_unavailable_reason(bool is_rtl) const;
  /* The one place that reads the gates off the hardware: does the cast, the
   * bring-up check and the try/catch, so no caller can forget one. `is_rtl`
   * comes back for cca_split_unavailable_reason. A register read throws on a
   * dying adapter, and that must read as "no answer", never as an exception
   * escaping an accessor. CALLER MUST HOLD _life_mu — this touches _radio
   * and _up and takes no lock of its own. */
  bool read_cca_gates(bool &primary, bool &edcca, bool &is_rtl) const;

  SessionStats stats() const;
  Json stats_json() const;
  SelectedChannel channel() const { return _channel; }

  void close();

private:
  Session(uint32_t id, DeviceInfo info);

  void on_packet(const Packet &pkt);
  void detach_sink_locked();
  void _logger_error(const std::string &msg);
  void writer_loop();
  void stop_writer();

  uint32_t _id;
  DeviceInfo _info;
  std::unique_ptr<devourer::DeviceSession> _dev;
  IRadio *_radio = nullptr; /* owned by _dev */

  SelectedChannel _channel{};
  bool _noise_floor_requested = false;
  std::atomic<bool> _up{false};
  std::atomic<bool> _rx_running{false};
  std::thread _rx_thread;

  /* --- frame buffer --- */
  /* Serialises everything that touches _radio/_dev: open, close, bring-up,
   * monitor start/stop, send, describe. NOT taken by on_packet — the RX hot
   * path must never wait on a control op.
   *
   * Without it, two concurrent radio.close (or one racing a control-connection
   * drop, which triggers release_sessions_of) both reached _rx_thread.join() —
   * concurrent join on one std::thread is UB — and both ran _dev.reset(),
   * destroying the IRadio twice. send_frame meanwhile dereferenced _radio with
   * no lock while the other thread nulled it. */
  mutable std::recursive_mutex _life_mu;
  bool _closed = false;

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
  bool _cca_primary_disabled = false;
  bool _cca_edcca_disabled = false;
  /* The qdB SetTxPowerOffsetQdb reported APPLYING, for backends whose
   * GetTxPowerState is not overridden (the MT7612U's dBm model), where the
   * state readback is otherwise empty. Guarded by _life_mu. */
  std::optional<int> _last_applied_offset_qdb;
};

} // namespace bridge

#endif /* DEVOURER_BRIDGE_SESSION_H */

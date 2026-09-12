#ifndef DEVOURER_BRIDGE_DEVICES_H
#define DEVOURER_BRIDGE_DEVICES_H

/* USB enumeration and backend classification.
 *
 * The honest shape of this problem, which the API reflects rather than hides:
 * devourer identifies its chips two different ways, and only one of them works
 * without touching the adapter.
 *
 *   - MT7612U, RTL8733B and Kestrel (11ax) carry static VID:PID tables in the
 *     vendored source (Mt7612uUsbIds.h, Rtl8733bUsbIds.h, KestrelUsbIds.h).
 *     Enumeration answers these exactly, and we CALL those tables rather than
 *     copying them, so a table that grows upstream grows here too.
 *
 *   - The Realtek 11ac families (Jaguar1/2/3) are dispatched on the SYS_CFG2
 *     chip-id byte read over USB control transfers — WiFiDriver::CreateRadio
 *     reads it at construction. There is no PID table upstream to consult;
 *     the per-example PID lists are "devices worth trying to open", not an
 *     identification. So enumeration can only say CANDIDATE, and the backend
 *     is not known until something opens the device.
 *
 * Reporting a probe-required device as though it were identified would be
 * exactly the "successful compilation means hardware verification" error in a
 * different costume, so Classification keeps the two apart. */

#include <cstdint>
#include <string>
#include <vector>

namespace bridge {

/* How a device's backend was determined. */
enum class IdSource {
  UsbId,     /* a static VID:PID table in the vendored source matched */
  Candidate, /* plausible Realtek 11ac; needs a chip-id probe to say more */
  None,      /* nothing in devourer claims this device */
};

struct DeviceInfo {
  uint8_t bus = 0;
  uint8_t address = 0;
  std::string port_path; /* dotted libusb port chain, as in `lsusb -t` */
  uint16_t vid = 0;
  uint16_t pid = 0;
  int speed = 0; /* libusb_speed enum */

  IdSource id_source = IdSource::None;
  std::string backend;    /* "mt7612u"|"rtl8733b"|"kestrel"|"" when unknown */
  std::string variant;    /* e.g. "C8852B" when the table distinguishes one */
  bool compiled_in = false; /* the matched backend is in THIS build */

  std::string kernel_driver; /* bound driver name, empty when none */
  std::string product;       /* USB string descriptor, when readable */
  std::string serial;
};

/* Every USB device that devourer could plausibly drive. `include_all` also
 * returns devices nothing claims, for "why isn't my adapter listed" triage. */
std::vector<DeviceInfo> enumerate_devices(bool include_all);

/* Which backends this binary was actually compiled with, derived from the
 * DEVOURER_HAVE_* macros the vendored CMake defines — never a hand-kept list.
 * Each entry is {name, chips it covers}. */
struct BackendInfo {
  std::string name;
  std::string chips;
  bool compiled = false;
};
std::vector<BackendInfo> compiled_backends();

const char *speed_name(int libusb_speed);

} // namespace bridge

#endif /* DEVOURER_BRIDGE_DEVICES_H */

#include "Devices.h"

#include <libusb.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#ifdef __linux__
#include <dirent.h>
#include <unistd.h>
#endif

#include "kestrel/KestrelUsbIds.h"
#include "mt7612u/Mt7612uUsbIds.h"
#include "rtl8733b/Rtl8733bUsbIds.h"

namespace bridge {
namespace {

/* Realtek 11ac CANDIDATES — the union of the PID lists devourer's own examples
 * try (examples/{doctor,chipstate,txpower,chanscout,chanmig,duplex,...}). This
 * is deliberately NOT presented as an identification: upstream has no Realtek
 * 11ac id table because CreateRadio reads the SYS_CFG2 chip-id instead. A PID
 * here means "worth probing", nothing more, and a Realtek adapter missing from
 * this list still opens fine when addressed by bus/address. */
constexpr uint16_t kRealtekCandidatePids[] = {
    0x0811, 0x0820, 0x0821, 0x8812, 0x8813, 0x8814, 0x881a, 0x881b,
    0x881c, 0x8822, 0xa811, 0xa81a, 0xa82a, 0xb811, 0xb812, 0xb82c,
    0xc811, 0xc812, 0xc820, 0xc82c, 0xc82e, 0xe822,
};

/* Realtek silicon ships under OEM vendor ids as well as 0x0bda. These are the
 * vendors seen across devourer's id tables and example selectors. */
constexpr uint16_t kRealtekVendorIds[] = {
    0x0bda, /* Realtek */
    0x2357, /* TP-Link */
    0x0b05, /* ASUS */
    0x7392, /* Edimax */
    0x2001, /* D-Link */
    0x0846, /* NetGear */
    0x0e66, /* Hawking */
    0x20f4, /* TRENDnet */
    0x056e, /* Elecom */
};

bool contains(const uint16_t *xs, size_t n, uint16_t v) {
  return std::find(xs, xs + n, v) != xs + n;
}

std::string port_path_of(libusb_device *dev) {
  uint8_t ports[8];
  const int n = libusb_get_port_numbers(dev, ports, sizeof ports);
  if (n <= 0)
    return {};
  std::string out;
  for (int i = 0; i < n; ++i) {
    if (i)
      out += '.';
    out += std::to_string(ports[i]);
  }
  return out;
}

/* Which kernel driver holds this device's interfaces, by name.
 *
 * libusb-1.0 can only answer "is a driver attached" (libusb_kernel_driver_active);
 * it has no portable call for the driver's NAME, and libusb_get_driver_np is a
 * libusb-0.1 compatibility relic that is not in the 1.0 API. On Linux sysfs has
 * the answer directly, so read it there and report nothing elsewhere rather
 * than pretend. The name matters for diagnosis — "mt76x2u holds it" and
 * "rtw_8812au holds it" lead to different next steps. */
std::string kernel_driver_of(uint8_t bus, uint8_t address) {
#ifndef __linux__
  (void)bus;
  (void)address;
  return {};
#else
  DIR *root = opendir("/sys/bus/usb/devices");
  if (root == nullptr)
    return {};
  std::string found;
  auto read_u = [](const std::string &path, int &out) {
    FILE *f = std::fopen(path.c_str(), "r");
    if (f == nullptr)
      return false;
    const bool ok = std::fscanf(f, "%d", &out) == 1;
    std::fclose(f);
    return ok;
  };
  while (const dirent *e = readdir(root)) {
    const std::string name = e->d_name;
    if (name[0] == '.' || name.find(':') != std::string::npos)
      continue; /* ":" entries are interfaces, not devices */
    const std::string dir = std::string("/sys/bus/usb/devices/") + name;
    int b = -1, d = -1;
    if (!read_u(dir + "/busnum", b) || !read_u(dir + "/devnum", d))
      continue;
    if (b != bus || d != address)
      continue;
    /* Interfaces of this device are siblings named "<dev>:<cfg>.<iface>". */
    DIR *r2 = opendir("/sys/bus/usb/devices");
    if (r2 != nullptr) {
      while (const dirent *e2 = readdir(r2)) {
        const std::string iname = e2->d_name;
        if (iname.rfind(name + ":", 0) != 0)
          continue;
        char link[256];
        const std::string lp =
            std::string("/sys/bus/usb/devices/") + iname + "/driver";
        const ssize_t n = readlink(lp.c_str(), link, sizeof link - 1);
        if (n <= 0)
          continue;
        link[n] = '\0';
        const char *slash = std::strrchr(link, '/');
        found = slash ? slash + 1 : link;
        if (found != "usbfs")
          break; /* prefer a real driver over the usbfs placeholder */
      }
      closedir(r2);
    }
    break;
  }
  closedir(root);
  return found;
#endif
}

/* Reading string descriptors requires opening the device. That is cheap and
 * non-disruptive (no claim, no reset, no kernel-driver detach), but it can
 * fail on permissions — in which case we simply report less, never refuse to
 * list. Listing must work even when nothing is accessible, because "you lack
 * permission on this device" is exactly what the caller needs to be told. */
void read_strings(libusb_device *dev, const libusb_device_descriptor &desc,
                  DeviceInfo &info) {
  info.kernel_driver = kernel_driver_of(info.bus, info.address);

  libusb_device_handle *h = nullptr;
  if (libusb_open(dev, &h) != 0 || h == nullptr)
    return;
  unsigned char buf[256];
  if (desc.iProduct &&
      libusb_get_string_descriptor_ascii(h, desc.iProduct, buf, sizeof buf) > 0)
    info.product = reinterpret_cast<char *>(buf);
  if (desc.iSerialNumber &&
      libusb_get_string_descriptor_ascii(h, desc.iSerialNumber, buf,
                                         sizeof buf) > 0)
    info.serial = reinterpret_cast<char *>(buf);
  libusb_close(h);
}

void classify(DeviceInfo &info) {
  if (mt7612u::is_usb_id(info.vid, info.pid)) {
    info.id_source = IdSource::UsbId;
    info.backend = "mt7612u";
#if defined(DEVOURER_HAVE_MT7612U)
    info.compiled_in = true;
#endif
    return;
  }
  if (rtl8733b::is_usb_id(info.vid, info.pid)) {
    info.id_source = IdSource::UsbId;
    info.backend = "rtl8733b";
#if defined(DEVOURER_HAVE_8733B)
    info.compiled_in = true;
#endif
    return;
  }
  if (auto v = kestrel::variant_for_usb_id(info.vid, info.pid)) {
    info.id_source = IdSource::UsbId;
    info.backend = "kestrel";
    info.variant = (*v == kestrel::ChipVariant::C8852B) ? "C8852B" : "C8852C";
#if defined(DEVOURER_HAVE_KESTREL)
    info.compiled_in = true;
#endif
    return;
  }
  const bool vendor_match =
      contains(kRealtekVendorIds,
               sizeof kRealtekVendorIds / sizeof *kRealtekVendorIds, info.vid);
  const bool pid_match = contains(
      kRealtekCandidatePids,
      sizeof kRealtekCandidatePids / sizeof *kRealtekCandidatePids, info.pid);
  if (vendor_match && pid_match) {
    info.id_source = IdSource::Candidate;
    /* Backend stays empty on purpose: only a chip-id probe can fill it. */
#if defined(DEVOURER_HAVE_JAGUAR1) || defined(DEVOURER_HAVE_JAGUAR2) ||        \
    defined(DEVOURER_HAVE_JAGUAR3)
    info.compiled_in = true;
#endif
    return;
  }
  info.id_source = IdSource::None;
}

} // namespace

const char *speed_name(int s) {
  switch (s) {
  case LIBUSB_SPEED_LOW:
    return "low";
  case LIBUSB_SPEED_FULL:
    return "full";
  case LIBUSB_SPEED_HIGH:
    return "high";
  case LIBUSB_SPEED_SUPER:
    return "super";
  case LIBUSB_SPEED_SUPER_PLUS:
    return "super+";
  default:
    return "unknown";
  }
}

std::vector<DeviceInfo> enumerate_devices(bool include_all) {
  std::vector<DeviceInfo> out;
  libusb_context *ctx = nullptr;
  if (libusb_init(&ctx) != 0)
    return out;

  libusb_device **list = nullptr;
  const ssize_t n = libusb_get_device_list(ctx, &list);
  for (ssize_t i = 0; i < n; ++i) {
    libusb_device_descriptor desc{};
    if (libusb_get_device_descriptor(list[i], &desc) != 0)
      continue;
    /* Root hubs are never adapters and only add noise. */
    if (desc.idVendor == 0x1d6b)
      continue;

    DeviceInfo info;
    info.bus = libusb_get_bus_number(list[i]);
    info.address = libusb_get_device_address(list[i]);
    info.port_path = port_path_of(list[i]);
    info.vid = desc.idVendor;
    info.pid = desc.idProduct;
    info.speed = libusb_get_device_speed(list[i]);
    classify(info);

    if (info.id_source == IdSource::None && !include_all)
      continue;
    read_strings(list[i], desc, info);
    out.push_back(std::move(info));
  }
  if (list != nullptr)
    libusb_free_device_list(list, 1);
  libusb_exit(ctx);
  return out;
}

std::vector<BackendInfo> compiled_backends() {
  /* The macro set is defined by the vendored CMakeLists from its DEVOURER_*
   * options, so this reports what was actually built, not what we hope was. */
  std::vector<BackendInfo> v = {
      {"jaguar1", "RTL8812AU / 8811AU / 8821AU", false},
      {"jaguar1-8814", "RTL8814AU (4T4R)", false},
      {"jaguar2-8822b", "RTL8822BU", false},
      {"jaguar2-8821c", "RTL8811CU / 8821CU", false},
      {"jaguar3-8822c", "RTL8812CU / 8822CU", false},
      {"jaguar3-8822e", "RTL8812EU / 8822EU", false},
      {"rtl8733b", "RTL8731BU / 8733BU", false},
      {"kestrel-8852b", "RTL8852BU / 8832BU (11ax)", false},
      {"kestrel-8852c", "RTL8852CU / 8832CU (11ax)", false},
      {"mt7612u", "MediaTek MT7612U / MT7662U", false},
      {"pcie", "RTL8821CE via vfio-pci", false},
  };
  auto mark = [&v](const char *name) {
    for (auto &b : v)
      if (b.name == name)
        b.compiled = true;
  };
#if defined(DEVOURER_HAVE_JAGUAR1)
  mark("jaguar1");
#endif
#if defined(DEVOURER_HAVE_8814)
  mark("jaguar1-8814");
#endif
#if defined(DEVOURER_HAVE_JAGUAR2_8822B)
  mark("jaguar2-8822b");
#endif
#if defined(DEVOURER_HAVE_JAGUAR2_8821C)
  mark("jaguar2-8821c");
#endif
#if defined(DEVOURER_HAVE_JAGUAR3_8822C)
  mark("jaguar3-8822c");
#endif
#if defined(DEVOURER_HAVE_JAGUAR3_8822E)
  mark("jaguar3-8822e");
#endif
#if defined(DEVOURER_HAVE_8733B)
  mark("rtl8733b");
#endif
#if defined(DEVOURER_HAVE_KESTREL_8852B)
  mark("kestrel-8852b");
#endif
#if defined(DEVOURER_HAVE_KESTREL_8852C)
  mark("kestrel-8852c");
#endif
#if defined(DEVOURER_HAVE_MT7612U)
  mark("mt7612u");
#endif
#if defined(DEVOURER_HAVE_PCIE)
  mark("pcie");
#endif
  return v;
}

} // namespace bridge

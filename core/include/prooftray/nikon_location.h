#ifndef PROOFTRAY_NIKON_LOCATION_H_
#define PROOFTRAY_NIKON_LOCATION_H_

#include <cstdint>
#include <optional>
#include <vector>

namespace prooftray {

struct LocationFix {
  double latitude = 0;
  double longitude = 0;
  std::optional<double> altitude_metres;
  int64_t utc_millis = 0;
  int satellites = 0;
};

// Nikon location payload: 41 bytes, WGS-84, UTC, coordinates in degrees and 1/10000 minutes.
std::vector<uint8_t> NikonLocationPacket(const std::optional<LocationFix>& fix);
// Pairing clock: UTC calendar fields followed by daylight and standard timezone offsets.
std::vector<uint8_t> NikonCurrentTimePacket(int64_t utc_millis, int standard_offset_minutes, int daylight_minutes);

}  // namespace prooftray
#endif  // PROOFTRAY_NIKON_LOCATION_H_

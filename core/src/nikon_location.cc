#include "prooftray/nikon_location.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>
#include <string_view>

namespace prooftray {

std::vector<uint8_t> NikonCurrentTimePacket(int64_t utc_millis, int standard_offset_minutes, int daylight_minutes) {
  if (utc_millis <= 0 || utc_millis > 253402300799999LL || standard_offset_minutes < -720 || standard_offset_minutes > 840 ||
      (daylight_minutes != 0 && daylight_minutes != 30 && daylight_minutes != 60 && daylight_minutes != 120)) {
    throw std::invalid_argument("Unsupported Nikon clock timestamp or timezone");
  }
  using namespace std::chrono;
  const sys_time<milliseconds> time{milliseconds{utc_millis}};
  const auto day = floor<days>(time);
  const year_month_day date{day};
  const hh_mm_ss clock{time - day};
  const auto year = static_cast<uint16_t>(static_cast<int>(date.year()));
  return {static_cast<uint8_t>(year), static_cast<uint8_t>(year >> 8), static_cast<uint8_t>(static_cast<unsigned>(date.month())),
          static_cast<uint8_t>(static_cast<unsigned>(date.day())), static_cast<uint8_t>(clock.hours().count()),
          static_cast<uint8_t>(clock.minutes().count()), static_cast<uint8_t>(clock.seconds().count()),
          static_cast<uint8_t>(daylight_minutes / 15), static_cast<uint8_t>(standard_offset_minutes / 60),
          static_cast<uint8_t>(std::abs(standard_offset_minutes % 60))};
}

std::vector<uint8_t> NikonLocationPacket(const std::optional<LocationFix>& fix) {
  std::vector<uint8_t> bytes(41, 0);
  bytes[0] = 1 << 5;  // GPS-valid field is present even when invalidating a previous fix.
  if (!fix) return bytes;
  const auto& value = *fix;
  if (!std::isfinite(value.latitude) || !std::isfinite(value.longitude) || std::abs(value.latitude) > 90 ||
      std::abs(value.longitude) > 180 || value.utc_millis <= 0 || value.utc_millis > 253402300799999LL) {
    throw std::invalid_argument("Invalid location fix");
  }
  auto word = [&](size_t offset, uint16_t number) {
    bytes[offset] = static_cast<uint8_t>(number);
    bytes[offset + 1] = static_cast<uint8_t>(number >> 8);
  };
  auto coordinate = [&](size_t offset, double degrees, char positive, char negative) {
    // Integral minute units keep carry at the degree boundary deterministic.
    const auto units = static_cast<uint32_t>(std::floor(std::abs(degrees) * 600000.0 + 0.000001));
    bytes[offset] = static_cast<uint8_t>(degrees < 0 ? negative : positive);
    bytes[offset + 1] = static_cast<uint8_t>(units / 600000);
    bytes[offset + 2] = static_cast<uint8_t>(units / 10000 % 60);
    bytes[offset + 3] = static_cast<uint8_t>(units / 100 % 100);
    bytes[offset + 4] = static_cast<uint8_t>(units % 100);
  };
  bytes[0] |= (1 << 0) | (1 << 1) | (1 << 4) | (1 << 6);
  coordinate(2, value.latitude, 'N', 'S');
  coordinate(7, value.longitude, 'E', 'W');
  if (value.satellites > 0) {
    bytes[0] |= 1 << 2;
    bytes[12] = static_cast<uint8_t>(std::min(value.satellites, 255));
  }
  if (value.altitude_metres) {
    const double altitude = *value.altitude_metres;
    if (!std::isfinite(altitude) || std::abs(altitude) > 32767) throw std::invalid_argument("Altitude is outside the supported range");
    bytes[0] |= 1 << 3;
    bytes[13] = altitude < 0 ? 'M' : 'P';
    word(14, static_cast<uint16_t>(std::abs(altitude)));
  }
  using namespace std::chrono;
  const sys_time<milliseconds> time{milliseconds{value.utc_millis}};
  const auto day = floor<days>(time);
  const year_month_day date{day};
  const hh_mm_ss clock{time - day};
  const int year_number = static_cast<int>(date.year());
  if (!date.ok() || year_number < 1970 || year_number > 9999) throw std::invalid_argument("Invalid location timestamp");
  word(16, static_cast<uint16_t>(year_number));
  bytes[18] = static_cast<uint8_t>(static_cast<unsigned>(date.month()));
  bytes[19] = static_cast<uint8_t>(static_cast<unsigned>(date.day()));
  bytes[20] = static_cast<uint8_t>(clock.hours().count());
  bytes[21] = static_cast<uint8_t>(clock.minutes().count());
  bytes[22] = static_cast<uint8_t>(clock.seconds().count());
  bytes[23] = static_cast<uint8_t>(clock.subseconds().count() / 10);
  bytes[24] = 1;
  constexpr std::string_view datum = "WGS-84";
  std::copy(datum.begin(), datum.end(), bytes.begin() + 25);
  return bytes;
}

}  // namespace prooftray

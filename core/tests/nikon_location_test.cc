#include "prooftray/nikon_location.h"
#include "prooftray/ptp.h"

#include <array>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

namespace {
void Check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
}

int main() {
  try {
    const prooftray::LocationFix fix{-12.345678, 98.765432, -123.9, 1709210096780LL, 12};
    const auto bytes = prooftray::NikonLocationPacket(fix);
    Check(bytes.size() == 41 && bytes[0] == 0x7f && bytes[1] == 0, "GPS packet size and presence mask");
    Check(bytes[2] == 'S' && bytes[3] == 12 && bytes[4] == 20 && bytes[5] == 74 && bytes[6] == 6,
          "Latitude must use fractional minutes, not seconds");
    Check(bytes[7] == 'E' && bytes[8] == 98 && bytes[9] == 45 && bytes[10] == 92 && bytes[11] == 59, "Longitude encoding");
    Check(bytes[12] == 12 && bytes[13] == 'M' && bytes[14] == 123 && bytes[15] == 0, "Satellite and altitude encoding");
    Check(bytes[16] == 0xe8 && bytes[17] == 7 && bytes[18] == 2 && bytes[19] == 29 && bytes[20] == 12 &&
          bytes[21] == 34 && bytes[22] == 56 && bytes[23] == 78 && bytes[24] == 1, "UTC timestamp encoding");
    Check(std::string(bytes.begin() + 25, bytes.begin() + 31) == "WGS-84" && bytes[40] == 0, "Datum and zero padding");
    const auto missing = prooftray::NikonLocationPacket(prooftray::LocationFix{0, 180, std::nullopt, 1709210096780LL, 0});
    Check((missing[0] & 0x0c) == 0 && missing[2] == 'N' && missing[8] == 180, "Missing optional fields and geographic boundaries");
    const auto disabled = prooftray::NikonLocationPacket(std::nullopt);
    Check(disabled.size() == 41 && disabled[0] == 0x20 && disabled[24] == 0, "Invalidation must explicitly clear GPS validity");
    bool rejected = false;
    try {
      prooftray::NikonLocationPacket(prooftray::LocationFix{std::numeric_limits<double>::quiet_NaN(), 0, std::nullopt, 1, 0});
    } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "NaN coordinates must not reach the camera");
    const std::vector<uint8_t> clock{0xe8, 7, 2, 29, 12, 34, 56, 0, 8, 0};
    Check(prooftray::NikonCurrentTimePacket(fix.utc_millis, 480, 0) == clock, "Pairing clock uses UTC and a separate timezone");
    const auto west = prooftray::NikonCurrentTimePacket(fix.utc_millis, -210, 0);
    Check(west[8] == 0xfd && west[9] == 30, "Negative fractional timezone offset");
    const auto summer = prooftray::NikonCurrentTimePacket(fix.utc_millis, 60, 60);
    Check(summer[7] == 4 && summer[8] == 1 && summer[4] == 12, "Daylight is encoded separately from UTC fields");
    rejected = false;
    try { prooftray::NikonCurrentTimePacket(fix.utc_millis, 480, 45); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Unsupported daylight must not silently change camera time");
    const auto outgoing = prooftray::ptp::Command(0x9523, 2, std::array<uint32_t, 0>{}, false, true);
    Check(outgoing[8] == 2 && outgoing[12] == 0x23 && outgoing[13] == 0x95, "PTP outgoing data direction");
    std::cout << "Location packet tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}

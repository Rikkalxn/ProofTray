#ifndef PROOFTRAY_PTP_H_
#define PROOFTRAY_PTP_H_

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace prooftray::ptp {

// Wire values follow PTP/IP v1.0. All multi-byte numeric fields are little-endian.
std::vector<uint8_t> Initialize(std::span<const uint8_t> guid, const std::u16string& client_name);
std::vector<uint8_t> Command(uint16_t operation, uint32_t transaction, std::span<const uint32_t> parameters,
                             bool receive_data, bool send_data = false);
std::vector<uint32_t> Handles(std::span<const uint8_t> data);
std::string DeviceInfoJson(std::span<const uint8_t> data);
std::string ObjectInfoJson(std::span<const uint8_t> data);

}  // namespace prooftray::ptp

#endif  // PROOFTRAY_PTP_H_

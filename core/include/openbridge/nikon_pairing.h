#ifndef OPENBRIDGE_NIKON_PAIRING_H_
#define OPENBRIDGE_NIKON_PAIRING_H_
#include <cstdint>
#include <span>
#include <vector>
namespace openbridge {
std::vector<uint8_t> NikonPairingReply(std::span<const uint8_t> stage1, std::span<const uint8_t> stage2);
std::vector<uint8_t> NikonPairingSessionKey(std::span<const uint8_t> stage1, std::span<const uint8_t> stage2,
                                         std::span<const uint8_t> stage4);
std::vector<uint8_t> NikonDecryptConnectionField(std::span<const uint8_t> key, std::span<const uint8_t> ciphertext);
}
#endif

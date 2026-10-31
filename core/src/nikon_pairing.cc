// Nikon smart-device hash adapted from gkoh/furble (MIT, Copyright 2020 Guo-Rong Koh).
// See third_party/furble/LICENSE. Official application code is not included.
#include "openbridge/nikon_pairing.h"

#include <array>
#include <stdexcept>

#include "Blowfish.h"

namespace openbridge {
namespace {
constexpr std::array<std::array<uint32_t, 2>, 8> kSalts{{
    {0x704066e4, 0x0433d552}, {0xed4b8fac, 0x15f7e47b}, {0x24471f11, 0x8b5ea1fc}, {0x05960c31, 0x2b8c7f41},
    {0xfda588c1, 0xeba8b1f3}, {0x99166056, 0x1bd3d550}, {0xcd32687f, 0xa9e28a30}, {0x2a8fe834, 0xdec7ebf4}}};

uint32_t BigEndian(std::span<const uint8_t> bytes, size_t offset) {
  return (uint32_t{bytes[offset]} << 24) | (uint32_t{bytes[offset + 1]} << 16) |
         (uint32_t{bytes[offset + 2]} << 8) | bytes[offset + 3];
}

class SmartHash : private Furble::Blowfish {
 public:
  SmartHash() : Blowfish({0xff, 0xff, 0xaa, 0x55, 0x11, 0x22, 0x33, 0x00}) {}
  std::array<uint32_t, 2> Hash(const std::array<uint32_t, 6>& input) const {
    uint32_t left = 0x01020304;
    uint32_t right = 0x05060708;
    for (size_t index = 0; index < input.size(); index += 2) {
      left ^= input[index];
      right ^= input[index + 1];
      uint32_t intermediate = 0;
      for (size_t round = 0; round < N; ++round) {
        intermediate = left ^ m_P[round];
        left = f(intermediate) ^ right;
        right = intermediate;
      }
      right = left ^ m_P[N];
      left = intermediate ^ m_P[N + 1];
    }
    return {left, right};
  }
};
}  // namespace

std::vector<uint8_t> NikonPairingReply(std::span<const uint8_t> start, std::span<const uint8_t> challenge) {
  if (start.size() != 17 || challenge.size() != 17 || start[0] != 1 || challenge[0] != 2) {
    throw std::invalid_argument("Invalid Nikon pairing stage");
  }
  const SmartHash hasher;
  for (const auto& salt : kSalts) {
    const auto check = hasher.Hash({salt[0], salt[1], BigEndian(challenge, 1), BigEndian(challenge, 5),
                                   BigEndian(start, 1), BigEndian(start, 5)});
    if (check[0] != BigEndian(challenge, 9) || check[1] != BigEndian(challenge, 13)) continue;
    const auto response = hasher.Hash({salt[0], salt[1], BigEndian(start, 1), BigEndian(start, 5),
                                      BigEndian(challenge, 1), BigEndian(challenge, 5)});
    std::vector<uint8_t> output(start.begin(), start.end());
    output[0] = 3;
    for (size_t word = 0; word < 2; ++word) {
      for (size_t byte = 0; byte < 4; ++byte) output[9 + word * 4 + byte] = static_cast<uint8_t>(response[word] >> (24 - byte * 8));
    }
    return output;
  }
  throw std::invalid_argument("Camera pairing challenge did not match the public protocol");
}

std::vector<uint8_t> NikonPairingSessionKey(std::span<const uint8_t> start, std::span<const uint8_t> challenge,
                                         std::span<const uint8_t> confirmation) {
  // Revalidate the transcript before deriving a key. Stage 4 supplies the camera ID, not a challenge-nonce echo.
  NikonPairingReply(start, challenge);
  if (confirmation.size() != 17 || confirmation[0] != 4) throw std::invalid_argument("Invalid Nikon pairing confirmation");
  const auto key = SmartHash().Hash({BigEndian(confirmation, 9), BigEndian(confirmation, 13), BigEndian(start, 9),
                                   BigEndian(start, 13), BigEndian(challenge, 1), BigEndian(start, 1)});
  std::vector<uint8_t> bytes(8);
  for (size_t word = 0; word < key.size(); ++word) {
    for (size_t byte = 0; byte < 4; ++byte) bytes[word * 4 + byte] = static_cast<uint8_t>(key[word] >> (24 - byte * 8));
  }
  return bytes;
}
}  // namespace openbridge

// Nikon smart-device hash adapted from gkoh/furble (MIT, Copyright 2020 Guo-Rong Koh).
// See third_party/furble/LICENSE. Official application code is not included.
#include "openbridge/nikon_pairing.h"

#include <array>
#include <algorithm>
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

class ConnectionCipher : private Furble::Blowfish {
 public:
  explicit ConnectionCipher(std::span<const uint8_t> key) : Blowfish(std::vector<uint8_t>(key.begin(), key.end())) {}

  std::array<uint32_t, 2> Decrypt(uint32_t left, uint32_t right) const {
    for (size_t round = N + 1; round > 1; --round) {
      left ^= m_P[round];
      right ^= f(left);
      std::swap(left, right);
    }
    std::swap(left, right);
    return {left ^ m_P[0], right ^ m_P[1]};
  }

  ~ConnectionCipher() {
    for (auto& value : m_P) *static_cast<volatile uint32_t*>(&value) = 0;
    for (auto& box : m_SBox) for (auto& value : box) *static_cast<volatile uint32_t*>(&value) = 0;
  }
};

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
size_t VerifiedVariant(const SmartHash& hasher, std::span<const uint8_t> start, std::span<const uint8_t> challenge) {
  if (start.size() != 17 || challenge.size() != 17 || start[0] != 1 || challenge[0] != 2) {
    throw std::invalid_argument("Invalid Nikon pairing stage");
  }
  for (size_t index = 0; index < kSalts.size(); ++index) {
    const auto& salt = kSalts[index];
    const auto check = hasher.Hash({salt[0], salt[1], BigEndian(challenge, 1), BigEndian(challenge, 5),
                                   BigEndian(start, 1), BigEndian(start, 5)});
    if (check[0] == BigEndian(challenge, 9) && check[1] == BigEndian(challenge, 13)) return index;
  }
  throw std::invalid_argument("Camera pairing challenge did not match the public protocol");
}
}  // namespace

std::vector<uint8_t> NikonPairingReply(std::span<const uint8_t> start, std::span<const uint8_t> challenge) {
  const SmartHash hasher;
  const auto& salt = kSalts[VerifiedVariant(hasher, start, challenge)];
  const auto response = hasher.Hash({salt[0], salt[1], BigEndian(start, 1), BigEndian(start, 5),
                                     BigEndian(challenge, 1), BigEndian(challenge, 5)});
  std::vector<uint8_t> output(start.begin(), start.end());
  output[0] = 3;
  for (size_t word = 0; word < response.size(); ++word) {
    for (size_t byte = 0; byte < 4; ++byte) output[9 + word * 4 + byte] = static_cast<uint8_t>(response[word] >> (24 - byte * 8));
  }
  return output;
}

std::vector<uint8_t> NikonPairingSessionKey(std::span<const uint8_t> start, std::span<const uint8_t> challenge,
                                         std::span<const uint8_t> confirmation) {
  if (confirmation.size() != 17 || confirmation[0] != 4) throw std::invalid_argument("Invalid Nikon pairing confirmation");
  const SmartHash hasher;
  const auto variant = static_cast<uint32_t>(VerifiedVariant(hasher, start, challenge));
  // After stage 3 verifies the proof, byte zero of the stored nonce prefix holds the matched variant.
  const uint32_t camera_prefix = (variant << 24) | (BigEndian(challenge, 1) & 0x00ffffffu);
  const auto key = hasher.Hash({BigEndian(confirmation, 9), BigEndian(confirmation, 13), BigEndian(start, 9),
                                BigEndian(start, 13), camera_prefix, BigEndian(start, 1)});
  std::vector<uint8_t> bytes(8);
  for (size_t word = 0; word < key.size(); ++word) {
    for (size_t byte = 0; byte < 4; ++byte) bytes[word * 4 + byte] = static_cast<uint8_t>(key[word] >> (24 - byte * 8));
  }
  return bytes;
}
std::vector<uint8_t> NikonDecryptConnectionField(std::span<const uint8_t> key, std::span<const uint8_t> ciphertext) {
  if (key.size() != 8 || ciphertext.empty() || ciphertext.size() > 4096 || ciphertext.size() % 8 != 0) {
    throw std::invalid_argument("Invalid encrypted camera connection field");
  }
  const ConnectionCipher cipher(key);
  std::array<uint32_t, 2> previous{};  // Each fixed-width field starts a separate CBC message with a zero IV.
  std::vector<uint8_t> plaintext(ciphertext.size());
  for (size_t offset = 0; offset < ciphertext.size(); offset += 8) {
    const std::array<uint32_t, 2> block{BigEndian(ciphertext, offset), BigEndian(ciphertext, offset + 4)};
    const auto decoded = cipher.Decrypt(block[0], block[1]);
    for (size_t word = 0; word < 2; ++word) {
      const uint32_t value = decoded[word] ^ previous[word];
      for (size_t byte = 0; byte < 4; ++byte) plaintext[offset + word * 4 + byte] = static_cast<uint8_t>(value >> (24 - byte * 8));
    }
    previous = block;
  }
  return plaintext;
}
}  // namespace openbridge

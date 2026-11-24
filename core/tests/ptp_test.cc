#include "prooftray/nikon_pairing.h"
#include "prooftray/ptp.h"

#include <algorithm>
#include <array>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {
void Check(bool result, const char* message) { if (!result) throw std::runtime_error(message); }
void Put(std::vector<uint8_t>& data, uint32_t value, size_t width) {
  for (size_t byte = 0; byte < width; ++byte) data.push_back(static_cast<uint8_t>(value >> (byte * 8)));
}
void String(std::vector<uint8_t>& data, const std::u16string& value) {
  Put(data, static_cast<uint32_t>(value.size() + 1), 1);
  for (auto character : value) Put(data, character, 2);
  Put(data, 0, 2);
}
}  // namespace

int main() {
  try {
    const std::array<uint32_t, 1> handle{0xdeadbeef};
    const auto command = prooftray::ptp::Command(0x1008, 0x78563412, handle, true);
    const std::vector<uint8_t> expected{22, 0, 0, 0, 6, 0, 0, 0, 1, 0, 0, 0, 8, 16, 18, 52, 86, 120, 239, 190, 173, 222};
    Check(command == expected, "PTP/IP data-in command must use DataOut=1 and little-endian fields");
    const std::array<uint8_t, 16> guid{};
    const auto initialize = prooftray::ptp::Initialize(guid, u"A");
    Check(initialize.size() == 32 && initialize[0] == 32 && initialize[24] == 65 && initialize[30] == 1, "Init wire format");
    std::vector<uint8_t> handles;
    Put(handles, 2, 4); Put(handles, 0xffffffff, 4); Put(handles, 0x80000000, 4);
    const auto parsed = prooftray::ptp::Handles(handles);
    Check(parsed.size() == 2 && parsed[0] == 0xffffffff && parsed[1] == 0x80000000, "Handles must retain unsigned range");
    handles[0] = 3;
    bool rejected = false;
    try { prooftray::ptp::Handles(handles); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Truncated handle arrays must be rejected");

    std::vector<uint8_t> object;
    Put(object, 0x10001, 4); Put(object, 0xb103, 2); Put(object, 0, 2); Put(object, 0xf0000000, 4);
    Put(object, 0x3801, 2);
    for (int index = 0; index < 3; ++index) Put(object, 0, 4);
    Put(object, 6000, 4); Put(object, 4000, 4); Put(object, 14, 4); Put(object, 1, 4);
    Put(object, 0, 2); Put(object, 0, 4); Put(object, 1, 4);
    String(object, u"DSC_\u4e2d.NEF"); String(object, u"20261028T120000"); String(object, u"");
    const auto info = prooftray::ptp::ObjectInfoJson(object);
    Check(info.find("4026531840") != std::string::npos && info.find("\\u4e2d") != std::string::npos, "Size and Unicode parsing");
    Check(info.find("\"width\":6000") != std::string::npos && info.find("\"height\":4000") != std::string::npos, "PTP image offsets");
    object.resize(20);
    rejected = false;
    try { prooftray::ptp::ObjectInfoJson(object); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Truncated object info must fail");
    std::array<uint8_t, 17> start{}, challenge{};
    start[0] = 1; challenge[0] = 2;
    rejected = false;
    try { prooftray::NikonPairingReply(start, challenge); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Unauthenticated BLE challenge must not produce a reply");

    // Synthetic protocol fixtures cross-checked with JDK Blowfish/CBC/NoPadding, not captured device traffic.
    start = {1, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 1, 0, 0, 0, 0x44, 0x55, 0x66, 0x77};
    challenge = {2, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0x88, 0x70, 0xc3, 0xe8, 0x76, 0x49, 0x6c, 0xda};
    std::array<uint8_t, 17> confirmation{4, 0, 0, 0, 0, 0, 0, 0, 0, '1', '2', '3', '4', '5', '6', '7', 0};
    const std::vector<uint8_t> stage3{3, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
                                    0xe8, 0xdf, 0x23, 0x32, 0x25, 0xd1, 0xb5, 0xea};
    const std::vector<uint8_t> key{0x7c, 0xb0, 0x48, 0x1b, 0xf3, 0x54, 0x6e, 0x55};
    Check(prooftray::NikonPairingReply(start, challenge) == stage3, "Stage 3 must match the independent CBC-MAC fixture");
    Check(prooftray::NikonPairingSessionKey(start, challenge, confirmation) == key, "Session key must use the verified variant in the nonce prefix");
    std::fill(confirmation.begin() + 1, confirmation.begin() + 9, 0xff);
    Check(prooftray::NikonPairingSessionKey(start, challenge, confirmation) == key, "Stage 4 nonce is not part of session key derivation");
    confirmation[9] ^= 1;
    Check(prooftray::NikonPairingSessionKey(start, challenge, confirmation) != key, "Camera ID must affect the session key");
    confirmation[9] ^= 1;
    start[9] ^= 1;
    Check(prooftray::NikonPairingSessionKey(start, challenge, confirmation) != key, "Client ID must affect the session key");
    start[9] ^= 1;
    challenge[16] ^= 1;
    rejected = false;
    try { prooftray::NikonPairingSessionKey(start, challenge, confirmation); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Session key must not be derived from an unverified camera challenge");
    challenge[16] ^= 1;
    confirmation[0] = 3;
    rejected = false;
    try { prooftray::NikonPairingSessionKey(start, challenge, confirmation); } catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Session key requires a stage 4 confirmation");
    // Published Blowfish zero-key vector; a repeated ciphertext block also checks CBC chaining, not just the block inverse.
    const std::array<uint8_t, 8> zero_key{};
    const std::vector<uint8_t> zero_block_cipher{0x4e, 0xf9, 0x97, 0x45, 0x61, 0x98, 0xdd, 0x78};
    Check(prooftray::NikonDecryptConnectionField(zero_key, zero_block_cipher) == std::vector<uint8_t>(8), "Connection field zero IV");
    auto chained = zero_block_cipher;
    chained.insert(chained.end(), zero_block_cipher.begin(), zero_block_cipher.end());
    std::vector<uint8_t> chained_plaintext(8);
    chained_plaintext.insert(chained_plaintext.end(), zero_block_cipher.begin(), zero_block_cipher.end());
    Check(prooftray::NikonDecryptConnectionField(zero_key, chained) == chained_plaintext, "Connection fields use CBC chaining");
    rejected = false;
    try { prooftray::NikonDecryptConnectionField(zero_key, std::array<uint8_t, 7>{}); }
    catch (const std::invalid_argument&) { rejected = true; }
    Check(rejected, "Partial encrypted blocks must be rejected");
    std::cout << "PTP codec tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}

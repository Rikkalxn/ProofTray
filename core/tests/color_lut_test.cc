#include "openbridge/color_lut.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

void Check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }

void Near(const openbridge::Rgb& actual, const openbridge::Rgb& expected, float tolerance = 0.00001f) {
  for (size_t channel = 0; channel < 3; ++channel) {
    Check(std::isfinite(actual[channel]) && std::abs(actual[channel] - expected[channel]) <= tolerance, "Unexpected interpolated color");
  }
}

template <typename Operation>
void Reject(Operation operation) {
  bool rejected = false;
  try { operation(); } catch (const std::invalid_argument&) { rejected = true; }
  Check(rejected, "Invalid color input was accepted");
}

constexpr auto kIdentity = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";

void CubeAndInterpolation() {
  const auto identity = openbridge::ParseCube(std::string("\xef\xbb\xbfTITLE \"Identity # test\" # comment\r\n") + kIdentity);
  Check(identity.title == "Identity # test", "Quoted titles must preserve hash characters");
  Near(openbridge::SampleLut(identity, {0, 0, 0}), {0, 0, 0});
  Near(openbridge::SampleLut(identity, {1, 1, 1}), {1, 1, 1});
  Near(openbridge::SampleLut(identity, {-0.2f, 0.5f, 1.2f}), {0, 0.5f, 1});
  auto swapped = identity;
  for (size_t index = 0; index < swapped.values.size(); index += 3) std::swap(swapped.values[index], swapped.values[index + 2]);
  auto nonlinear = identity;
  std::fill(nonlinear.values.begin(), nonlinear.values.end(), 0.0f);
  std::fill(nonlinear.values.end() - 3, nonlinear.values.end(), 1.0f);
  openbridge::Rgb point{0.2f, 0.4f, 0.7f};
  do {
    Near(openbridge::SampleLut(identity, point), point);
    Near(openbridge::SampleLut(swapped, point), {point[2], point[1], point[0]});
    // A cube with only its white corner set yields min(R,G,B) under tetrahedral interpolation.
    Near(openbridge::SampleLut(nonlinear, point), {0.2f, 0.2f, 0.2f});
  } while (std::next_permutation(point.begin(), point.end()));

  const auto curves = openbridge::ParseCube("LUT_1D_SIZE 3\nLUT_1D_INPUT_RANGE -1 1\n0 1 0\n0.25 0.5 0.75\n1 0 1\n");
  Near(openbridge::SampleLut(curves, {-0.5f, 0.0f, 0.5f}), {0.125f, 0.5f, 0.875f});
  const auto domains = openbridge::ParseCube(std::string("DOMAIN_MIN 0 -1 -2\nDOMAIN_MAX 1 1 2\n") + kIdentity);
  Near(openbridge::SampleLut(domains, {0.5f, 0, 0}), {0.5f, 0.5f, 0.5f});
  for (const auto* text : {"", "LUT_3D_SIZE 66\n", "LUT_3D_SIZE 2\n0 0 0\n", "LUT_1D_SIZE 2\nLUT_3D_SIZE 2\n",
                          "LUT_1D_SIZE 2\n0 0 0 extra\n1 1 1\n", "LUT_1D_SIZE 2\n0 0 0\nNaN 1 1\n"}) {
    Reject([&] { openbridge::ParseCube(text); });
  }
  Reject([] { openbridge::ParseCube(std::string(kIdentity) + "1 1 1\n"); });
  Reject([] { openbridge::ParseCube(std::string("DOMAIN_MIN 1 0 0\n") + kIdentity); });
  Reject([] { openbridge::ParseCube(std::string("LUT_1D_INPUT_RANGE 0 1\n") + kIdentity); });
  Reject([] { openbridge::ParseCube(std::string("LUT_3D_INPUT_RANGE 0 1\nDOMAIN_MIN 0 0 0\n") + kIdentity); });
  auto invalid = identity;
  invalid.values[0] = std::numeric_limits<float>::max();
  Reject([&] { openbridge::ValidateLut(invalid); });
  invalid = identity;
  invalid.domain_max[0] = std::numeric_limits<float>::denorm_min();
  Reject([&] { openbridge::ValidateLut(invalid); });
}

void BinaryAndHald() {
  const auto identity = openbridge::ParseCube(kIdentity);
  auto bytes = openbridge::SerializeLut(identity);
  Check(bytes.size() == 40 + 8 * 3 * 4 && bytes[8] == 3 && bytes[12] == 2, "Unexpected binary LUT header");
  const auto loaded = openbridge::ParseLutBinary(bytes);
  Check(loaded.values == identity.values && loaded.domain_min == identity.domain_min && loaded.domain_max == identity.domain_max,
        "Binary round trip changed the color table");
  auto damaged = bytes;
  damaged[0] = 'X';
  Reject([&] { openbridge::ParseLutBinary(damaged); });
  bytes.pop_back();
  Reject([&] { openbridge::ParseLutBinary(bytes); });
  damaged = openbridge::SerializeLut(identity);
  damaged[12] = 65;
  Reject([&] { openbridge::ParseLutBinary(damaged); });

  // Level 2 Hald: 8x8 pixels contain a 4x4x4 RGB cube, red fastest. Include row padding.
  std::vector<uint8_t> image(8 * 36, 42);
  for (size_t index = 0; index < 64; ++index) {
    const auto offset = index / 8 * 36 + index % 8 * 4;
    image[offset] = static_cast<uint8_t>((index % 4) * 85);
    image[offset + 1] = static_cast<uint8_t>((index / 4 % 4) * 85);
    image[offset + 2] = static_cast<uint8_t>((index / 16) * 85);
    image[offset + 3] = 255;
  }
  const auto hald = openbridge::FromHaldRgba(image, 8, 36);
  Near(openbridge::SampleLut(hald, {0.15f, 0.67f, 0.83f}), {0.15f, 0.67f, 0.83f});
  Reject([&] { openbridge::FromHaldRgba(image, 7, 36); });
  Reject([&] { openbridge::FromHaldRgba(image, 8, 31); });
  image[3] = 254;
  Reject([&] { openbridge::FromHaldRgba(image, 8, 36); });
}

void PixelAndLook() {
  const auto identity = openbridge::ParseCube(kIdentity);
  std::vector<uint8_t> image{51, 102, 179, 255, 32, 64, 96, 128, 99, 98, 97, 96};
  const auto original = image;
  openbridge::ApplyColorLookRgba(image, 2, 1, 12, true, {}, &identity);
  Check(image == original, "Identity LUT must preserve pixels, alpha and padding");
  openbridge::ColorLook disabled{0, 3, 1, 0, -1};
  openbridge::ApplyColorLookRgba(image, 2, 1, 12, true, disabled, &identity);
  Check(image == original, "Zero strength must preserve every byte");
  openbridge::ColorLook gray;
  gray.saturation = 0;
  openbridge::ApplyColorLookRgba(image, 2, 1, 12, true, gray, nullptr);
  for (size_t pixel : {0, 4}) Check(image[pixel] == image[pixel + 1] && image[pixel] == image[pixel + 2], "Grayscale channels differ");
  Check(image[3] == 255 && image[7] == 128 && image[8] == 99, "Alpha or row padding was overwritten");
  openbridge::ColorLook exposure;
  exposure.exposure_ev = 1;
  Near(openbridge::ApplyColorLook({0.5f, 0.5f, 0.5f}, exposure, nullptr), {0.685836f, 0.685836f, 0.685836f});
  openbridge::ColorLook invalid;
  invalid.strength = std::numeric_limits<float>::infinity();
  Reject([&] { openbridge::ValidateLook(invalid); });
  Reject([&] { openbridge::ApplyColorLookRgba(image, 4, 1, 12, true, {}, nullptr); });
  Reject([&] { openbridge::ApplyColorLookRgba(image, 2, 2, 12, true, {}, nullptr); });
}

}  // namespace

int main() {
  try {
    CubeAndInterpolation();
    BinaryAndHald();
    PixelAndLook();
    std::cout << "Color LUT tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}

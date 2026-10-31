#ifndef OPENBRIDGE_COLOR_LUT_H_
#define OPENBRIDGE_COLOR_LUT_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace openbridge {

using Rgb = std::array<float, 3>;

struct ColorLut {
  int dimension = 3;
  int size = 0;
  Rgb domain_min{0, 0, 0};
  Rgb domain_max{1, 1, 1};
  // Red changes fastest in 3D tables. 1D entries hold independent R/G/B curves.
  std::vector<float> values;
  std::string title;
};

struct ColorLook {
  float strength = 1;
  float exposure_ev = 0;
  float contrast = 0;
  float saturation = 1;
  float warmth = 0;
};

ColorLut ParseCube(std::string_view source);
ColorLut ParseLutBinary(std::span<const uint8_t> source);
std::vector<uint8_t> SerializeLut(const ColorLut& lut);
ColorLut FromHaldRgba(std::span<const uint8_t> pixels, size_t side, size_t stride);
void ValidateLut(const ColorLut& lut);
void ValidateLook(const ColorLook& look);
// Per-pixel helpers require validated LUTs/looks. SampleLut clamps finite input to the LUT domain.
Rgb SampleLut(const ColorLut& lut, const Rgb& input);
// The input to ApplyColorLook must be finite, normalized sRGB.
Rgb ApplyColorLook(const Rgb& srgb, const ColorLook& look, const ColorLut* lut);
// Mutable SDR sRGB RGBA8 pixels. Alpha is preserved; premultiplied buffers are unpremultiplied for grading.
void ApplyColorLookRgba(std::span<uint8_t> pixels, size_t width, size_t height, size_t stride, bool premultiplied,
                        const ColorLook& look, const ColorLut* lut);

}  // namespace openbridge
#endif  // OPENBRIDGE_COLOR_LUT_H_

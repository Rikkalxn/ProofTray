#ifndef OPENBRIDGE_RAW_RENDERING_H_
#define OPENBRIDGE_RAW_RENDERING_H_

#include <array>
#include <span>

#include "openbridge/color_lut.h"

namespace openbridge {

// Uniform sRGB-coordinate samples describe luminance only. Pixels remain linear until the final display encoding.
struct RawToneCurve {
  static constexpr size_t kSamples = 257;
  std::array<float, kSamples> values{};
  static RawToneCurve Identity();
  static RawToneCurve Generic();
  static RawToneCurve Parse(std::span<const float> values);
  float map(float linear_luminance) const;
};

struct RawDisplayGrade {
  RawToneCurve curve;
  Rgb gain;
  ColorLook display_look;
  float strength;
};

Rgb RenderRawDefault(const Rgb& linear_srgb, const RawToneCurve& curve);
RawDisplayGrade PrepareRawDisplayGrade(const RawToneCurve& curve, const ColorLook& look);
Rgb RenderRawDisplayPixel(const Rgb& linear_srgb, const ColorLut* lut, const RawDisplayGrade& grade);

}  // namespace openbridge
#endif  // OPENBRIDGE_RAW_RENDERING_H_

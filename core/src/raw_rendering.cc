#include "openbridge/raw_rendering.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace openbridge {
namespace {
float Encode(float value) {
  return value <= .0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - .055f;
}
float Linear(float value) {
  return value <= .04045f ? value / 12.92f : std::pow((value + .055f) / 1.055f, 2.4f);
}
}  // namespace

RawToneCurve RawToneCurve::Identity() {
  RawToneCurve curve;
  for (size_t i = 0; i < kSamples; ++i) curve.values[i] = float(i) / (kSamples - 1);
  return curve;
}

RawToneCurve RawToneCurve::Generic() {
  auto curve = Identity();
  // A bounded, monotone rational S curve with a toe and shoulder; a generic display fallback, not a camera profile.
  for (float& x : curve.values) x = 4.f * x * x / (4.f * x * x + (1.f - x) * (1.f - x));
  return curve;
}

RawToneCurve RawToneCurve::Parse(std::span<const float> samples) {
  if (samples.empty()) return Generic();
  if (samples.size() != kSamples || samples.front() != 0.f || samples.back() != 1.f) {
    throw std::invalid_argument("Invalid RAW tone curve layout");
  }
  RawToneCurve curve;
  float previous = 0;
  for (size_t i = 0; i < samples.size(); ++i) {
    const float value = samples[i];
    if (!std::isfinite(value) || value < previous || value > 1.f) throw std::invalid_argument("Invalid RAW tone curve sample");
    curve.values[i] = previous = value;
  }
  return curve;
}

float RawToneCurve::map(float luminance) const {
  if (luminance <= 0) return 0;
  if (luminance >= 1) return 1;
  const float position = std::clamp(Encode(luminance), 0.f, 1.f) * (kSamples - 1);
  const size_t index = std::min(size_t(position), kSamples - 2);
  return Linear(std::lerp(values[index], values[index + 1], position - index));
}

Rgb RenderRawDefault(const Rgb& input, const RawToneCurve& curve) {
  Rgb color = input;
  for (float& c : color) {
    if (!std::isfinite(c)) throw std::invalid_argument("Non-finite RAW pixel");
    c = std::max(c, 0.f);
  }
  const float luminance = .2126f * color[0] + .7152f * color[1] + .0722f * color[2];
  const float scale = luminance > 0 ? curve.map(luminance) / luminance : 0;
  for (float& c : color) c = Encode(std::clamp(c * scale, 0.f, 1.f));
  return color;
}

RawDisplayGrade PrepareRawDisplayGrade(const RawToneCurve& curve, const ColorLook& look) {
  ValidateLook(look);
  auto display = look;
  display.strength = 1;
  display.exposure_ev = display.warmth = 0;
  return {curve, {std::exp2(look.exposure_ev + look.warmth * .25f), std::exp2(look.exposure_ev),
                  std::exp2(look.exposure_ev - look.warmth * .25f)}, display, look.strength};
}

Rgb RenderRawDisplayPixel(const Rgb& linear, const ColorLut* lut, const RawDisplayGrade& grade) {
  if (grade.strength == 0) return RenderRawDefault(linear, grade.curve);
  auto adjusted = linear;
  for (size_t i = 0; i < adjusted.size(); ++i) adjusted[i] *= grade.gain[i];
  adjusted = ApplyColorLook(RenderRawDefault(adjusted, grade.curve), grade.display_look, lut);
  if (grade.strength == 1) return adjusted;
  auto base = RenderRawDefault(linear, grade.curve);
  for (size_t i = 0; i < base.size(); ++i) base[i] = std::lerp(base[i], adjusted[i], grade.strength);
  return base;
}

}  // namespace openbridge

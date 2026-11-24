#ifndef OPENBRIDGE_COLOR_ADJUSTMENTS_H_
#define OPENBRIDGE_COLOR_ADJUSTMENTS_H_

#include <array>
#include <span>
#include <vector>

namespace openbridge {

struct CurvePoint {
  float x, y;
  bool operator==(const CurvePoint&) const = default;
};
using ToneCurve = std::vector<CurvePoint>;

struct ColorAdjustments {
  float highlights = 0, shadows = 0, whites = 0, blacks = 0, vibrance = 0;
  float blending = .5f, balance = 0;
  std::array<std::array<float, 3>, 8> hsl{};  // Red, orange, yellow, green, aqua, blue, purple, magenta.
  std::array<std::array<float, 3>, 4> grading{};  // Shadows, midtones, highlights, global: hue degrees, saturation, luminance.
  std::array<ToneCurve, 4> curves{ToneCurve{{0, 0}, {1, 1}}, ToneCurve{{0, 0}, {1, 1}},
                                  ToneCurve{{0, 0}, {1, 1}}, ToneCurve{{0, 0}, {1, 1}}};
  bool IsIdentity() const;
  bool operator==(const ColorAdjustments&) const = default;
};

void ValidateColorAdjustments(const ColorAdjustments& edits);
ColorAdjustments ParseColorAdjustments(std::span<const float> values);
float EvaluateToneCurve(const ToneCurve& curve, float x);
// Display-referred luma in [0,1]. The three tonal masks form a partition of unity; global grading is independent.
std::array<float, 3> ColorGradingWeights(float display_luma, float blending, float balance);

}  // namespace openbridge
#endif  // OPENBRIDGE_COLOR_ADJUSTMENTS_H_

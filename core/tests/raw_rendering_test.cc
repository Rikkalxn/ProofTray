#include "openbridge/raw_exposure.h"
#include "openbridge/raw_rendering.h"

#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

namespace {
void Check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
void Near(const openbridge::Rgb& a, const openbridge::Rgb& b) {
  for (int i = 0; i < 3; ++i) Check(std::isfinite(a[i]) && std::abs(a[i] - b[i]) < 1e-5f, "RAW rendering mismatch");
}
template <typename F> void Reject(F function) {
  try { function(); } catch (const std::invalid_argument&) { return; }
  throw std::runtime_error("Invalid RAW curve was accepted");
}
}  // namespace

int main() {
  try {
    using namespace openbridge;
    const auto identity = RawToneCurve::Identity();
    const auto curve = RawToneCurve::Generic();
    Near(RenderRawDefault({.18f, .18f, .18f}, identity), {.461356f, .461356f, .461356f});
    Near(RenderRawDefault({0, 0, 0}, curve), {0, 0, 0});
    Near(RenderRawDefault({1, 1, 1}, curve), {1, 1, 1});
    float previous = 0;
    for (int i = 0; i <= 1000; ++i) {
      const float x = i / 1000.f;
      const auto rgb = RenderRawDefault({x, x, x}, curve);
      Check(std::isfinite(rgb[0]) && rgb[0] >= previous && rgb[0] <= 1, "Curve must be bounded and monotone");
      previous = rgb[0];
    }
    const auto gray = RenderRawDefault({.081f, .081f, .081f}, curve);
    Check(gray[0] > .4f && gray[0] < .55f, "Fallback midtones are not displayable");
    Check(RenderRawDefault({.7f, .7f, .7f}, curve)[0] < 1, "Reference gain must not clip below RAW white");
    const auto highlights = RenderRawDefault({2.f, 3.f, 4.f}, curve);
    for (float c : highlights) Check(std::isfinite(c) && c >= 0 && c <= 1, "Invalid display highlight");

    auto invalid = identity.values;
    invalid[80] = -1;
    Reject([&] { RawToneCurve::Parse(invalid); });
    invalid = identity.values;
    invalid[80] = std::numeric_limits<float>::quiet_NaN();
    Reject([&] { RawToneCurve::Parse(invalid); });
    Near(RenderRawDefault({.1f, .2f, .3f}, RawToneCurve::Parse(curve.values)), RenderRawDefault({.1f, .2f, .3f}, curve));

    ColorLook look;
    look.exposure_ev = 1;
    const Rgb raw{.04f, .06f, .08f};
    const auto base = RenderRawDefault(raw, curve);
    const auto exposed = RenderRawDefault({.08f, .12f, .16f}, curve);
    Near(RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade(curve, look)), exposed);
    look.strength = 0;
    Near(RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade(curve, look)), base);
    look.strength = .5f;
    Rgb mixed{};
    for (int i = 0; i < 3; ++i) mixed[i] = (base[i] + exposed[i]) / 2;
    Near(RenderRawDisplayPixel(raw, nullptr, PrepareRawDisplayGrade(curve, look)), mixed);

    const auto lut = ParseCube("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n");
    look.input_encoding = LutEncoding::NLog;
    look.output_encoding = LutEncoding::Rec709Gamma24;
    look.strength = 0;
    auto log = PrepareRawLogGrade(LogRange::Full10, 2.f, look, kGenericRawExposureReference, curve);
    Near(RenderLogPixel(raw, lut, log), base);
    look.strength = 1;
    const auto with_curve = PrepareRawLogGrade(LogRange::Full10, 2.f, look, kGenericRawExposureReference, curve);
    const auto without_curve = PrepareRawLogGrade(LogRange::Full10, 2.f, look, kGenericRawExposureReference, identity);
    Near(RenderLogPixel(raw, lut, with_curve), RenderLogPixel(raw, lut, without_curve));
    std::cout << "RAW default rendering tests passed\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}

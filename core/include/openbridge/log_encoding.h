#ifndef OPENBRIDGE_LOG_ENCODING_H_
#define OPENBRIDGE_LOG_ENCODING_H_

#include <optional>

#include "openbridge/color_lut.h"
#include "openbridge/raw_rendering.h"

namespace openbridge {

enum class LogRange { Full10, Video10 };

struct LogEncodingSpec {
  LutEncoding encoding;
  std::string_view name;
  std::array<Rgb, 3> from_rec709;
  double (*encode)(double);  // Scene reflectance to normalized full-range code, before the LUT domain.
  Rgb ToGamut(const Rgb& linear_srgb) const;
};

const LogEncodingSpec& GetLogEncoding(LutEncoding encoding);
double LogCoordinate(double full, LogRange range);

struct LogGrade {
  const LogEncodingSpec& spec;
  LogRange range;
  Rgb gain;
  float strength, contrast, saturation;
  std::optional<RawDisplayTransform> base_render = std::nullopt;
  LutEncoding output_encoding = LutEncoding::Rec709Gamma24;
};

LogGrade PrepareLogGrade(LogRange range, float k, const ColorLook& look);
Rgb RenderLogPixel(const Rgb& linear_srgb, const ColorLut& lut, const LogGrade& grade);

// Nikon reference probe uses the unquantized 10-bit code.
double NLogCode(double reflectance);
Rgb RenderNLogReference(const Rgb& linear_srgb, const ColorLut& lut, LogRange range, float k, float exposure_ev, float strength);

}  // namespace openbridge
#endif  // OPENBRIDGE_LOG_ENCODING_H_

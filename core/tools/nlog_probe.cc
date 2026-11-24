// Standalone calibration probe. Pixel coordinates and PPM output use LibRaw's unrotated developed image.
#include <libraw/libraw.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "prooftray/linear_raw.h"
#include "prooftray/log_encoding.h"
#include "prooftray/raw_exposure.h"

namespace {
using prooftray::ColorLut;
using prooftray::LogRange;
using prooftray::Rgb;

ColorLut ReadLut(const char* path) {
  if (std::filesystem::file_size(path) > 32 * 1024 * 1024) throw std::runtime_error("LUT exceeds size limit");
  std::ifstream file(path, std::ios::binary);
  if (!file) throw std::runtime_error("Cannot open LUT");
  return prooftray::ParseCube(std::string(std::istreambuf_iterator<char>(file), {}));
}

void Ramp(const ColorLut& lut, bool scan_black) {
  std::cout << "reflectance,code10,full_coordinate,full_r,full_g,full_b,video_coordinate,video_r,video_g,video_b\n";
  auto sample = [&](double y) {
    const double code = prooftray::NLogCode(y);
    std::cout << y << ',' << code;
    for (auto range : {LogRange::Full10, LogRange::Video10}) {
      const float x = static_cast<float>(prooftray::LogCoordinate(code / 1023., range));
      const auto rgb = prooftray::SampleLut(lut, {x, x, x});
      std::cout << ',' << x << ',' << rgb[0] << ',' << rgb[1] << ',' << rgb[2];
    }
    std::cout << '\n';
  };
  if (scan_black) {
    for (int i = 0; i <= 1000; ++i) sample(i / 100000.);
  } else {
    for (double y : {0., .01, .09, .18, .328, .36, .9, 1., 2., 4., 8.}) sample(y);
  }
}

class CameraImage : public prooftray::LinearRaster {
 public:
  explicit CameraImage(const char* path) : LinearRaster(path, 0, 100000000, prooftray::RawDecodeSize::Full, nullptr, nullptr, false) {
    std::cerr << data().idata.make << ' ' << data().idata.model << " ISO " << data().other.iso_speed << ", " << width << 'x' << height << '\n';
  }
  bool clipped(int x, int y) const {
    const auto& p = data().image[static_cast<size_t>(y) * width + x];
    return std::max({p[0], p[1], p[2]}) == 65535;
  }
};

void Calibrate(const CameraImage& image, int x, int y, int w, int h) {
  if (x < 0 || y < 0 || w <= 0 || h <= 0 || w > image.width - x || h > image.height - y) {
    throw std::invalid_argument("Gray-card ROI outside unrotated image");
  }
  double sum = 0;
  for (int row = y; row < y + h; ++row) for (int col = x; col < x + w; ++col) {
    if (image.clipped(col, row)) throw std::runtime_error("Clipped gray-card patch");
    const auto c = image.pixel(col, row);
    sum += .2126 * c[0] + .7152 * c[1] + .0722 * c[2];
  }
  const double mean = sum / (double(w) * h);
  if (!(mean > 0 && std::isfinite(mean))) throw std::runtime_error("Invalid gray-card luminance");
  const auto& reference = prooftray::kGenericRawExposureReference;
  const double absolute_scale = .18 / mean, user_k = absolute_scale / reference.scale;
  std::cout << "camera_linear_patch_Y=" << mean << " reference=" << reference.source << " reference_scale=" << reference.scale
            << " reference_calibrated=" << reference.calibrated << " referenced_patch_Y=" << mean * reference.scale
            << " absolute_scale_to_gray=" << absolute_scale << " user_k=" << user_k << " user_gain_ev=" << std::log2(user_k) << '\n';
}

void Render(const CameraImage& image, const ColorLut& lut, float k, float ev, float strength, LogRange range, const char* output) {
  if (!(std::isfinite(k) && k >= .125f && k <= 8.f && std::isfinite(ev) && std::abs(ev) <= 3 && strength >= 0 && strength <= 1)) {
    throw std::invalid_argument("Invalid k, EV or strength");
  }
  const auto& reference = prooftray::kGenericRawExposureReference;
  const prooftray::ColorLook look{strength, ev, 0.f, 1.f, 0.f, prooftray::LutEncoding::NLog, prooftray::LutEncoding::Rec709Gamma24};
  const auto grade = prooftray::PrepareRawLogGrade(range, std::log2(k), look, reference, {image.displayBaselineExposure()});
  std::cerr << "RAW reference=" << reference.source << " scale=" << reference.scale << " calibrated=" << reference.calibrated
            << ", user_k=" << k << '\n';
  if (std::filesystem::exists(output)) throw std::runtime_error("Choose a new output path");
  std::ofstream file(output, std::ios::binary);
  file.exceptions(std::ios::badbit | std::ios::failbit);
  file << "P6\n" << image.width << ' ' << image.height << "\n255\n";
  std::vector<char> row(static_cast<size_t>(image.width) * 3);
  for (int y = 0; y < image.height; ++y) {
    for (int x = 0; x < image.width; ++x) {
      auto srgb = prooftray::RenderLogPixel(image.pixel(x, y), lut, grade);
      for (int i = 0; i < 3; ++i) row[3 * x + i] = static_cast<char>(std::lround(std::clamp(srgb[i], 0.f, 1.f) * 255));
    }
    file.write(row.data(), row.size());
  }
  file.close();
}
}  // namespace

int main(int argc, char** argv) {
  try {
    std::cout << std::fixed << std::setprecision(10);
    const std::string mode = argc > 1 ? argv[1] : "";
    if (mode == "ramp" && (argc == 3 || (argc == 4 && std::string(argv[3]) == "--black"))) Ramp(ReadLut(argv[2]), argc == 4);
    else if (mode == "gray" && argc == 7) {
      Calibrate(CameraImage(argv[2]), std::stoi(argv[3]), std::stoi(argv[4]), std::stoi(argv[5]), std::stoi(argv[6]));
    }
    else if (mode == "render" && argc == 9) {
      const std::string range = argv[7];
      if (range != "full" && range != "video") throw std::invalid_argument("Specify full or video input normalization");
      Render(CameraImage(argv[2]), ReadLut(argv[3]), std::stof(argv[4]), std::stof(argv[5]), std::stof(argv[6]),
             range == "full" ? LogRange::Full10 : LogRange::Video10, argv[8]);
    } else throw std::invalid_argument("ramp LUT.cube [--black] | gray image.NEF x y w h | "
                                       "render image.NEF LUT.cube user_k EV strength full|video new.ppm");
    return 0;
  } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}

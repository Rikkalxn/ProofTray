#ifndef OPENBRIDGE_LINEAR_RAW_H_
#define OPENBRIDGE_LINEAR_RAW_H_

#include <libraw/libraw.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

#include "openbridge/color_lut.h"

namespace openbridge {
enum class RawDecodeSize { Full, Preview };

// Resample the WB'd camera channels in scene-linear space. Coordinates follow LibRaw's eight flip orientations.
class LinearRaster {
 public:
  LinearRaster(const std::string& path, int max_edge, int64_t budget, RawDecodeSize mode,
               progress_callback progress = nullptr, void* context = nullptr, bool orient = true, int64_t memory_budget = 0) {
    Initialize([&] { return decoder_->open_file(path.c_str()); }, max_edge, budget, mode, progress, context, orient, memory_budget);
  }

  LinearRaster(std::unique_ptr<LibRaw_abstract_datastream> input, int max_edge, int64_t budget, RawDecodeSize mode,
               progress_callback progress = nullptr, void* context = nullptr, bool orient = true, int64_t memory_budget = 0)
      : input_(std::move(input)) {
    if (!input_) throw std::invalid_argument("Missing RAW input stream");
    Initialize([&] { return decoder_->open_datastream(input_.get()); }, max_edge, budget, mode, progress, context, orient, memory_budget);
  }

  // White-balanced camera channels, before the default matrix. A future input profile can replace that matrix, not apply it twice.
  Rgb cameraPixel(int x, int y) const {
    const auto& data = decoder_->imgdata;
    const auto& s = data.sizes;
    const int left = x * int64_t{oriented_width_} / width, right = (x + 1) * int64_t{oriented_width_} / width;
    const int top = y * int64_t{oriented_height_} / height, bottom = (y + 1) * int64_t{oriented_height_} / height;
    Rgb camera{};
    for (int j = top; j < bottom; ++j) {
      for (int i = left; i < right; ++i) {
        int row = j, col = i;
        if (flip_ & 4) std::swap(row, col);
        if (flip_ & 2) row = s.height - 1 - row;
        if (flip_ & 1) col = s.width - 1 - col;
        const auto& sample = data.image[size_t(row) * s.width + col];
        for (int c = 0; c < 3; ++c) camera[c] += sample[c];
      }
    }
    const float scale = 1.f / (65535.f * (right - left) * (bottom - top));
    for (float& c : camera) c *= scale;
    return camera;
  }

  // Camera-normalized linear sRGB, without a display curve or the technical LUT's scene reference.
  Rgb pixel(int x, int y) const {
    const auto camera = cameraPixel(x, y);
    Rgb linear{};
    for (int i = 0; i < 3; ++i) {
      for (int c = 0; c < 3; ++c) linear[i] += decoder_->imgdata.color.rgb_cam[i][c] * camera[c];
    }
    return linear;
  }

  const libraw_data_t& data() const { return decoder_->imgdata; }
  float displayBaselineExposure() const {
    const auto& data = decoder_->imgdata;
    if (!data.idata.dng_version) return 0.f;
    // LibRaw promotes the IFD exposure value without its parsedfields bit; missing tags retain the -999 sentinel.
    const float exposure = data.color.dng_levels.baseline_exposure;
    if (exposure == -999.f || !std::isfinite(exposure) || std::abs(exposure) > 16.f) return 0.f;
    return exposure;
  }
  int width = 0, height = 0;
  int source_width = 0, source_height = 0;
 private:
  template <typename Open>
  void Initialize(Open open, int max_edge, int64_t budget, RawDecodeSize mode, progress_callback progress,
                  void* context, bool orient, int64_t memory_budget) {
    if (max_edge < 0 || budget <= 0 || budget > 100000000 || memory_budget < 0) throw std::invalid_argument("Invalid RAW dimensions");
    if (progress) decoder_->set_progress_handler(progress, context);
    auto& data = decoder_->imgdata;
    auto& p = data.params;
    p.use_camera_wb = p.use_camera_matrix = p.no_auto_bright = 1;
    p.output_color = 0;
    p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1;
    p.highlight = 0;
    p.adjust_maximum_thr = 0;
    p.exp_correc = 0;  // Display BaselineExposure is applied once by the shared reference renderer.
    p.user_qual = 3;
    data.rawparams.max_raw_memory_mb = 512;
    Check(open(), "Open linear RAW");
    const auto& s = data.sizes;
    if (!s.width || !s.height || uint64_t{s.raw_width} * s.raw_height > 100000000) throw std::runtime_error("RAW exceeds processing limit");
    CheckMemoryBudget(max_edge, memory_budget);
    Check(decoder_->unpack(), "Unpack linear RAW");
    // Apply the file's standard crop after unpack() has calculated black levels, before either preview or full development.
    // The return value is a crop index plus one (0 means no valid crop), not a LibRaw error code.
    decoder_->adjust_to_raw_inset_crop(1);
    source_width = s.flip & 4 ? s.height : s.width;
    source_height = s.flip & 4 ? s.width : s.height;
    if (!max_edge && int64_t{s.width} * s.height > budget) throw std::runtime_error("RAW output exceeds memory budget");
    p.half_size = mode == RawDecodeSize::Preview && max_edge > 0 && std::max(s.width, s.height) >= int64_t{max_edge} * 2;
    Check(decoder_->dcraw_process(), "Develop camera RGB");
    if (!data.image || data.idata.colors != 3 || !s.width || !s.height) throw std::runtime_error("Expected three camera channels");
    // After dcraw_process these are the developed image dimensions, also used as the stride by copy_mem_image.
    // For half-size Bayer, pre_interpolate has already assigned width=iwidth and height=iheight.
    flip_ = orient ? s.flip : 0;
    oriented_width_ = flip_ & 4 ? s.height : s.width;
    oriented_height_ = flip_ & 4 ? s.width : s.height;
    const double scale = max_edge ? std::min(1., double(max_edge) / std::max(oriented_width_, oriented_height_)) : 1.;
    width = std::max(1, int(std::lround(oriented_width_ * scale)));
    height = std::max(1, int(std::lround(oriented_height_ * scale)));
    if (int64_t{width} * height > budget) throw std::runtime_error("Linear RAW output exceeds memory budget");
    CheckMemoryBudget(max_edge, memory_budget);
  }

  void CheckMemoryBudget(int max_edge, int64_t memory_budget) const {
    if (!memory_budget) return;
    const auto& s = decoder_->imgdata.sizes;
    const int64_t raw_pixels = int64_t{s.raw_width} * s.raw_height;
    const int64_t image_pixels = int64_t{s.width} * s.height;
    const double scale = max_edge ? std::min(1., double(max_edge) / std::max(s.width, s.height)) : 1.;
    const int64_t output_pixels = std::max(1L, std::lround(s.width * scale)) * int64_t{std::max(1L, std::lround(s.height * scale))};
    // Budget raw storage (up to four 16-bit channels), the developed raster and one RGBA_8888 output together.
    // Even resized exports demosaic the full default crop; reserve scratch space for LibRaw, LUTs and encoding.
    const int64_t required = raw_pixels * 8 + std::max(raw_pixels, image_pixels) * 8 + output_pixels * 4 + 64 * 1024 * 1024;
    if (required > memory_budget) throw std::runtime_error("可用内存不足，无法以当前尺寸显影 RAW，请关闭其他应用后重试");
  }

  static void Check(int result, const char* phase) {
    if (result != LIBRAW_SUCCESS) throw std::runtime_error(std::string(phase) + ": " + libraw_strerror(result));
  }
  // LibRaw does not own open_datastream inputs. Keep the stream alive until after its decoder is destroyed.
  std::unique_ptr<LibRaw_abstract_datastream> input_;
  std::unique_ptr<LibRaw> decoder_ = std::make_unique<LibRaw>();
  int flip_ = 0;
  int oriented_width_ = 0, oriented_height_ = 0;
};
}  // namespace openbridge
#endif  // OPENBRIDGE_LINEAR_RAW_H_

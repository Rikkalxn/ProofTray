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
               progress_callback progress = nullptr, void* context = nullptr, bool orient = true) {
    Initialize([&] { return decoder_->open_file(path.c_str()); }, max_edge, budget, mode, progress, context, orient);
  }

  LinearRaster(std::unique_ptr<LibRaw_abstract_datastream> input, int max_edge, int64_t budget, RawDecodeSize mode,
               progress_callback progress = nullptr, void* context = nullptr, bool orient = true) : input_(std::move(input)) {
    if (!input_) throw std::invalid_argument("Missing RAW input stream");
    Initialize([&] { return decoder_->open_datastream(input_.get()); }, max_edge, budget, mode, progress, context, orient);
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
  int width = 0, height = 0;
  int source_width = 0, source_height = 0;
 private:
  template <typename Open>
  void Initialize(Open open, int max_edge, int64_t budget, RawDecodeSize mode, progress_callback progress, void* context, bool orient) {
    if (max_edge < 0 || budget <= 0 || budget > 100000000) throw std::invalid_argument("Invalid RAW dimensions");
    if (progress) decoder_->set_progress_handler(progress, context);
    auto& data = decoder_->imgdata;
    auto& p = data.params;
    p.use_camera_wb = p.use_camera_matrix = p.no_auto_bright = 1;
    p.output_color = 0;
    p.output_bps = 16;
    p.gamm[0] = p.gamm[1] = 1;
    p.highlight = 0;
    p.adjust_maximum_thr = 0;
    p.user_qual = 3;
    data.rawparams.max_raw_memory_mb = 512;
    Check(open(), "Open linear RAW");
    const auto& s = data.sizes;
    if (!s.width || !s.height || uint64_t{s.raw_width} * s.raw_height > 100000000) throw std::runtime_error("RAW exceeds processing limit");
    source_width = s.flip & 4 ? s.height : s.width;
    source_height = s.flip & 4 ? s.width : s.height;
    if (!max_edge && int64_t{s.width} * s.height > budget) throw std::runtime_error("RAW output exceeds memory budget");
    p.half_size = mode == RawDecodeSize::Preview && max_edge > 0 && std::max(s.width, s.height) >= int64_t{max_edge} * 2;
    Check(decoder_->unpack(), "Unpack linear RAW");
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

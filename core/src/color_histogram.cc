#include "prooftray/color_histogram.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace prooftray {
namespace {
constexpr uint32_t kSampleBudget = 256 * 256;

uint32_t SampleCoordinate(uint32_t cell, uint32_t cells, uint32_t extent, uint32_t fraction) {
  return static_cast<uint32_t>(((uint64_t{cell} << 16) + fraction) * extent / (uint64_t{cells} << 16));
}

struct FilterTap {
  uint32_t low, high;
  float fraction;
};

struct TextureAxis {
  uint32_t count;
  double first_texel, step;
  uint32_t last_texel;

  FilterTap Tap(uint32_t pixel) const {
    const double texel = std::clamp(first_texel + pixel * step, 0., static_cast<double>(last_texel));
    const auto low = static_cast<uint32_t>(texel);
    return {low, std::min(low + 1, last_texel), static_cast<float>(texel - low)};
  }
};

TextureAxis FitAxis(uint32_t source, int viewport, double scale) {
  if (!viewport) return {source, 0., 1., source - 1};
  const double offset = (viewport - source * scale) * .5;
  // Rasterize the centered photo at display pixel centers. Texture coordinates refer to texel centers at half integers.
  const double begin = std::clamp(std::ceil(offset - .5), 0., static_cast<double>(viewport));
  const double end = std::clamp(std::ceil(offset + source * scale - .5), begin, static_cast<double>(viewport));
  return {static_cast<uint32_t>(end - begin), (begin + .5 - offset) / scale - .5, 1. / scale, source - 1};
}

std::array<float, 4> FilteredRgba(std::span<const uint8_t> pixels, size_t stride, FilterTap x, FilterTap y) {
  const auto* top = pixels.data() + y.low * stride;
  const auto* bottom = pixels.data() + y.high * stride;
  const size_t left = size_t{x.low} * 4, right = size_t{x.high} * 4;
  std::array<float, 4> rgba{};
  for (int c = 0; c < 4; ++c) {
    const float a = std::lerp(float(top[left + c]), float(top[right + c]), x.fraction);
    const float b = std::lerp(float(bottom[left + c]), float(bottom[right + c]), x.fraction);
    rgba[c] = std::lerp(a, b, y.fraction);
  }
  return rgba;
}
}  // namespace

HistogramSampleGrid::HistogramSampleGrid(uint32_t width, uint32_t height, bool subsample)
    : width_(width), height_(height), columns_(width), rows_(height) {
  const uint64_t area = uint64_t{width} * height;
  if (!subsample || area <= kSampleBudget) return;
  const double scale = std::sqrt(static_cast<double>(kSampleBudget) / area);
  columns_ = std::clamp(static_cast<uint32_t>(width * scale), 1u, kSampleBudget);
  rows_ = std::min(height, kSampleBudget / columns_);
}

std::array<uint32_t, 2> HistogramSampleGrid::Pixel(uint32_t column, uint32_t row) const {
  if (columns_ == width_ && rows_ == height_) return {column, row};
  // Vary both offsets within each equal-area cell so repeated textures cannot fall between every sample column or row.
  uint32_t bits = row * columns_ + column;
  bits ^= bits >> 16;
  bits *= 0x85ebca6bu;
  bits ^= bits >> 13;
  bits *= 0xc2b2ae35u;
  bits ^= bits >> 16;
  return {SampleCoordinate(column, columns_, width_, bits & 0xffffu), SampleCoordinate(row, rows_, height_, bits >> 16)};
}

RgbHistogram PreviewHistogramRgba(std::span<const uint8_t> pixels, uint32_t width, uint32_t height, size_t stride,
                                  bool premultiplied, const ColorLook* look, const ColorLut* lut, HistogramViewport viewport) {
  if (!width || !height || uint64_t{width} * 4 > stride || stride > pixels.size() / height) {
    throw std::invalid_argument("Invalid histogram bitmap dimensions");
  }
  if (viewport.width < 0 || viewport.height < 0 || (viewport.width == 0) != (viewport.height == 0)) {
    throw std::invalid_argument("Invalid histogram viewport");
  }
  const double scale = viewport.width ? std::min(double(viewport.width) / width, double(viewport.height) / height) : 1.;
  const auto x_axis = FitAxis(width, viewport.width, scale), y_axis = FitAxis(height, viewport.height, scale);
  if (!x_axis.count || !y_axis.count) return {};
  const bool filter = scale != 1. || x_axis.first_texel != 0. || y_axis.first_texel != 0.;
  const bool subsample = look || lut || uint64_t{x_axis.count} * y_axis.count > std::numeric_limits<int32_t>::max() / 255;
  RgbHistogram bins{};
  if (!filter && !subsample) {
    for (uint32_t y = 0; y < height; ++y) {
      const auto* pixel = pixels.data() + y * stride;
      for (uint32_t x = 0; x < width; ++x, pixel += 4) {
        const int alpha = pixel[3];
        if (alpha == 0) continue;
        for (int c = 0; c < 3; ++c) {
          const int code = premultiplied && alpha < 255 ? std::min(255, (pixel[c] * 255 + alpha / 2) / alpha) : pixel[c];
          bins[c * 256 + code] += alpha;
        }
      }
    }
    return bins;
  }

  const HistogramSampleGrid grid(x_axis.count, y_axis.count, subsample);
  for (uint32_t row = 0; row < grid.rows(); ++row) {
    for (uint32_t col = 0; col < grid.columns(); ++col) {
      const auto [x, y] = grid.Pixel(col, row);
      std::array<float, 4> rgba;
      if (filter) rgba = FilteredRgba(pixels, stride, x_axis.Tap(x), y_axis.Tap(y));
      else {
        const auto* pixel = pixels.data() + y * stride + size_t{x} * 4;
        std::copy_n(pixel, 4, rgba.begin());
      }
      const int opacity = static_cast<int>(std::lround(rgba[3]));
      if (!opacity) continue;
      const float normalize = 1.f / (premultiplied ? rgba[3] : 255.f);
      Rgb rgb{};
      for (int c = 0; c < 3; ++c) rgb[c] = std::min(1.f, rgba[c] * normalize);
      if (look) rgb = ApplyColorLook(rgb, *look, lut);
      else if (lut) rgb = SampleLut(*lut, rgb);
      for (int c = 0; c < 3; ++c) bins[c * 256 + std::lround(std::clamp(rgb[c], 0.f, 1.f) * 255)] += opacity;
    }
  }
  return bins;
}

}  // namespace prooftray

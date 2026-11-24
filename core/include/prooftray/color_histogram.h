#ifndef PROOFTRAY_COLOR_HISTOGRAM_H_
#define PROOFTRAY_COLOR_HISTOGRAM_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "prooftray/color_lut.h"

namespace prooftray {

// RAW and bitmap analysis use the same positions across edits. Small previews are always counted in full.
class HistogramSampleGrid {
 public:
  HistogramSampleGrid(uint32_t width, uint32_t height, bool subsample);
  uint32_t columns() const { return columns_; }
  uint32_t rows() const { return rows_; }
  std::array<uint32_t, 2> Pixel(uint32_t column, uint32_t row) const;

 private:
  uint32_t width_, height_, columns_, rows_;
};

struct HistogramViewport {
  int width = 0, height = 0;
};

// Three 256-bin display channels, weighted by opacity. A viewport samples the fitted texture before applying the validated look or LUT.
using RgbHistogram = std::array<int32_t, 3 * 256>;
RgbHistogram PreviewHistogramRgba(std::span<const uint8_t> pixels, uint32_t width, uint32_t height, size_t stride,
                                  bool premultiplied, const ColorLook* look, const ColorLut* lut, HistogramViewport viewport = {});

}  // namespace prooftray
#endif  // PROOFTRAY_COLOR_HISTOGRAM_H_

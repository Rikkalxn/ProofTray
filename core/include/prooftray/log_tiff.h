#ifndef PROOFTRAY_LOG_TIFF_H_
#define PROOFTRAY_LOG_TIFF_H_

#include <functional>

#include "prooftray/log_encoding.h"

namespace prooftray {

struct LogExportSettings {
  LutEncoding encoding = LutEncoding::SLog3Cine;
  float exposure_ev = 0.f;
  float warmth = 0.f;
  float gain_ev = 0.f;
  float tint = 0.f;
};

// Borrows a new, empty, seekable read/write descriptor. The caller retains ownership of the descriptor and file.
// Working storage is one RGB16 row plus libtiff's bounded strip buffer, not a full-size output bitmap.
void WriteLogTiff(int fd, int width, int height, const LogExportSettings& settings,
                  const std::function<Rgb(int, int)>& read, const std::function<bool()>& cancelled);

}  // namespace prooftray
#endif  // PROOFTRAY_LOG_TIFF_H_

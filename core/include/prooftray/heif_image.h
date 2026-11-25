#ifndef PROOFTRAY_HEIF_IMAGE_H_
#define PROOFTRAY_HEIF_IMAGE_H_

#include <cstddef>
#include <cstdint>
#include <span>

namespace prooftray {

struct HeifImageSize {
  int width;
  int height;
};

// Reads the HEIF primary image's displayed dimensions, including container rotation and cropping.
HeifImageSize ProbeHeifImage(std::span<const uint8_t> bytes);

// Writes premultiplied sRGB RGBA8 to caller-owned rows. The target must fit the original and preserve its aspect ratio.
// A sufficiently large embedded thumbnail can be used for browsing. Disable this when rendering edits or exports.
// Input and output must remain valid for the duration of the call. Invalid/unsupported files and limits throw exceptions.
void DecodeHeifRgba(std::span<const uint8_t> bytes, HeifImageSize target, std::span<uint8_t> rgba, size_t stride, bool allow_thumbnail);

}  // namespace prooftray
#endif  // PROOFTRAY_HEIF_IMAGE_H_

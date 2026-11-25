#include "prooftray/heif_image.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string_view>
#include <utility>
#include <vector>

#include <libheif/heif.h>
#include <libheif/heif_items.h>
#include <libheif/heif_properties.h>

namespace prooftray {
namespace {

constexpr uint64_t kMaxPixels = 64'000'000;  // Includes 61 MP camera originals.
constexpr size_t kMiB = 1024 * 1024;

template <typename T, auto Release>
struct HeifDeleter {
  void operator()(T* object) const { Release(object); }
};
template <typename T, auto Release>
using HeifPtr = std::unique_ptr<T, HeifDeleter<T, Release>>;
using Handle = HeifPtr<heif_image_handle, heif_image_handle_release>;
using Image = HeifPtr<heif_image, heif_image_release>;

void Check(heif_error error) {
  if (error.code == heif_error_Ok) return;
  if (error.code == heif_error_Memory_allocation_error && error.subcode != heif_suberror_Security_limit_exceeded) throw std::bad_alloc();
  throw std::runtime_error(error.message ? error.message : "HEIF decoding failed");
}

uint64_t Pixels(HeifImageSize size) {
  return size.width > 0 && size.height > 0 ? static_cast<uint64_t>(size.width) * size.height : 0;
}

bool SupportedSize(HeifImageSize size) {
  return size.width <= 32768 && size.height <= 32768 && Pixels(size) > 0 && Pixels(size) <= kMaxPixels;
}

HeifImageSize Size(const heif_image_handle* handle) {
  return {heif_image_handle_get_width(handle), heif_image_handle_get_height(handle)};
}

Handle OpenPrimary(std::span<const uint8_t> bytes) {
  if (bytes.size() < 12 || bytes.size() > 512 * kMiB) throw std::invalid_argument("Invalid HEIF input size");
  const std::string_view mime = heif_get_file_mime_type(bytes.data(), static_cast<int>(bytes.size()));
  if (std::memcmp(bytes.data() + 4, "ftyp", 4) != 0 || (!mime.empty() && !mime.starts_with("image/hei"))) {
    throw std::invalid_argument("Unsupported HEIF file type");
  }
  // Finish process-wide initialization before concurrent preview contexts access the built-in decoder registry.
  static const heif_error initialization = heif_init(nullptr);
  Check(initialization);
  HeifPtr<heif_context, heif_context_free> context(heif_context_alloc());
  if (!context) throw std::bad_alloc();
  auto* limits = heif_context_get_security_limits(context.get());
  limits->max_image_size_pixels = kMaxPixels;
  limits->max_memory_block_size = 256 * kMiB;
  limits->max_total_memory = 512 * kMiB;
  heif_context_set_max_decoding_threads(context.get(), 0);
  Check(heif_context_read_from_memory_without_copy(context.get(), bytes.data(), bytes.size(), nullptr));
  heif_image_handle* raw = nullptr;
  Check(heif_context_get_primary_image_handle(context.get(), &raw));
  Handle primary(raw);
  if (!SupportedSize(Size(primary.get()))) throw std::invalid_argument("Invalid HEIF primary image size");
  auto item = heif_image_handle_get_item_id(primary.get());
  if (heif_item_get_item_type(context.get(), item) == heif_item_type_grid) {
    Check(heif_image_handle_get_grid_image_tile_id(primary.get(), 0, 0, 0, &item));
  }
  const auto type = heif_item_get_item_type(context.get(), item);
  if (type != heif_item_type_hvc1 && type != heif_fourcc('i', 'd', 'e', 'n') && type != heif_fourcc('i', 'o', 'v', 'l')) {
    throw std::invalid_argument("Unsupported HEIF primary codec");
  }
  return primary;  // The handle retains the context; the caller retains the input span.
}

bool SameAspect(HeifImageSize original, HeifImageSize scaled) {
  const int64_t error = static_cast<int64_t>(original.width) * scaled.height - static_cast<int64_t>(original.height) * scaled.width;
  return std::abs(error) <= std::max(original.width, original.height);  // Allow rounding of the short edge.
}

// Maps source axes to displayed axes in top-left pixel coordinates. Apply HEIF properties in their declared order.
struct Orientation {
  int xx = 1, xy = 0, yx = 0, yy = 1;
  bool operator==(const Orientation&) const = default;
  HeifImageSize SourceSize(HeifImageSize displayed) const { return xx == 0 ? HeifImageSize{displayed.height, displayed.width} : displayed; }
};

struct Geometry {
  Orientation orientation;
  bool cropped = false;
};

std::optional<Geometry> ReadGeometry(const heif_context* context, heif_item_id item) {
  std::array<heif_property_id, 16> properties{};
  const int count = heif_item_get_transformation_properties(context, item, nullptr, 0);
  if (count > static_cast<int>(properties.size())) return std::nullopt;
  heif_item_get_transformation_properties(context, item, properties.data(), count);
  Geometry geometry;
  auto& m = geometry.orientation;
  for (int i = 0; i < count; ++i) {
    const auto id = properties[i];
    const auto type = heif_item_get_property_type(context, item, id);
    if (type == heif_item_property_type_transform_rotation) {
      const int angle = heif_item_get_property_transform_rotation_ccw(context, item, id);
      if (angle < 0 || angle > 270 || angle % 90 != 0) return std::nullopt;
      for (int step = 0; step < angle; step += 90) m = {m.yx, m.yy, -m.xx, -m.xy};
    } else if (type == heif_item_property_type_transform_mirror) {
      const auto direction = heif_item_get_property_transform_mirror(context, item, id);
      if (direction == heif_transform_mirror_direction_horizontal) m = {-m.xx, -m.xy, m.yx, m.yy};
      else if (direction == heif_transform_mirror_direction_vertical) m = {m.xx, m.xy, -m.yx, -m.yy};
      else return std::nullopt;
    } else if (type == heif_item_property_type_transform_crop) {
      geometry.cropped = true;
    }
  }
  return geometry;
}

Orientation OutputOrientation(const heif_image_handle* handle) {
  HeifPtr<heif_context, heif_context_free> context(heif_image_handle_get_context(handle));
  if (!context) throw std::bad_alloc();
  const auto item = heif_image_handle_get_item_id(handle);
  const auto geometry = ReadGeometry(context.get(), item);
  if (!geometry || geometry->cropped || geometry->orientation == Orientation{} || heif_image_handle_has_alpha_channel(handle)) return {};
  const auto type = heif_item_get_item_type(context.get(), item);
  if (type == heif_item_type_hvc1) return geometry->orientation;
  if (type != heif_item_type_grid) return {};
  // ignore_transformations also affects grid children. Only bypass libheif's transform stage on a plain grid of coded tiles.
  heif_image_tiling tiling{};
  if (heif_image_handle_get_image_tiling(handle, 0, &tiling).code != heif_error_Ok ||
      static_cast<uint64_t>(tiling.num_columns) * tiling.num_rows > 4096) return {};
  for (uint32_t y = 0; y < tiling.num_rows; ++y) {
    for (uint32_t x = 0; x < tiling.num_columns; ++x) {
      heif_item_id tile;
      if (heif_image_handle_get_grid_image_tile_id(handle, 0, x, y, &tile).code != heif_error_Ok ||
          heif_item_get_item_type(context.get(), tile) != heif_item_type_hvc1 ||
          heif_item_get_transformation_properties(context.get(), tile, nullptr, 0) != 0) return {};
    }
  }
  return geometry->orientation;
}

Handle FindThumbnail(const heif_image_handle* primary, HeifImageSize target) {
  Handle best;
  std::array<heif_item_id, 32> ids{};
  const int count = heif_image_handle_get_list_of_thumbnail_IDs(primary, ids.data(), static_cast<int>(ids.size()));
  if (count == 0) return {};
  HeifPtr<heif_context, heif_context_free> context(heif_image_handle_get_context(primary));
  if (!context) throw std::bad_alloc();
  const auto geometry = ReadGeometry(context.get(), heif_image_handle_get_item_id(primary));
  if (!geometry) return {};
  uint64_t best_pixels = Pixels(Size(primary));
  for (int i = 0; i < count; ++i) {
    heif_image_handle* raw = nullptr;
    if (heif_image_handle_get_thumbnail(primary, ids[i], &raw).code != heif_error_Ok) continue;
    Handle candidate(raw);
    const auto size = Size(candidate.get());
    if (!SupportedSize(size) || size.width < target.width || size.height < target.height ||
        !SameAspect(Size(primary), size) || Pixels(size) >= best_pixels) continue;
    const auto thumbnail_geometry = ReadGeometry(context.get(), ids[i]);
    if (!thumbnail_geometry || thumbnail_geometry->orientation != geometry->orientation) continue;
    best_pixels = Pixels(size);
    best = std::move(candidate);
  }
  return best;
}

struct Sample {
  int first, second;
  float fraction;
};

Sample AtPosition(double coordinate, int source) {
  const double position = std::clamp(coordinate, 0.0, static_cast<double>(source - 1));
  const int first = static_cast<int>(position);
  return {first, std::min(first + 1, source - 1), static_cast<float>(position - first)};
}

double SampleCenter(int index, int source, int target) { return (index + 0.5) * source / target - 0.5; }

// Read the decoder's native planes at output resolution. A 10-bit preview never needs a full-sized RGB16 intermediate.
class Plane {
 public:
  Plane(const heif_image* image, heif_channel channel, HeifImageSize target, bool full_range, bool chroma, int cosited_luma_width = 0)
      : height_(heif_image_get_height(image, channel)), target_height_(target.height) {
    const int width = heif_image_get_width(image, channel);
    const int bits = heif_image_get_bits_per_pixel_range(image, channel);
    bytes_ = heif_image_get_bits_per_pixel(image, channel) / 8;
    data_ = heif_image_get_plane_readonly2(image, channel, &stride_);
    if (!data_ || !SupportedSize({width, height_}) || bits < 8 || bits > 16 ||
        bytes_ != (bits + 7) / 8 || stride_ < static_cast<size_t>(width) * bytes_) throw std::runtime_error("Invalid HEIF sample plane");
    const int unit = 1 << (bits - 8);
    offset_ = static_cast<float>((chroma ? 128 : full_range ? 0 : 16) * unit);
    scale_ = 1.0f / static_cast<float>(full_range ? (1 << bits) - 1 : (chroma ? 224 : 219) * unit);
    columns_.reserve(target.width);
    for (int x = 0; x < target.width; ++x) {
      // In 4:2:2 the chroma samples coincide horizontally with even luma samples, including when the coded width is odd.
      const double coordinate = cosited_luma_width ? SampleCenter(x, cosited_luma_width, target.width) / 2 : SampleCenter(x, width, target.width);
      columns_.push_back(AtPosition(coordinate, width));
    }
  }

  void SetRow(int y) { row_ = AtPosition(SampleCenter(y, height_, target_height_), height_); }
  float At(int x) const {
    const auto& column = columns_[x];
    auto read = [&](int row) {
      const uint8_t* start = data_ + static_cast<size_t>(row) * stride_;
      const float first = Read(start, column.first);
      return column.fraction == 0 ? first : std::lerp(first, Read(start, column.second), column.fraction);
    };
    const float first = read(row_.first);
    const float value = row_.fraction == 0 ? first : std::lerp(first, read(row_.second), row_.fraction);
    return (value - offset_) * scale_;
  }

 private:
  float Read(const uint8_t* row, int x) const {
    if (bytes_ == 1) return row[x];
    uint16_t value;  // libheif planar samples are native-endian and right-aligned.
    std::memcpy(&value, row + static_cast<size_t>(x) * 2, sizeof(value));
    return value;
  }
  int height_, target_height_, bytes_;
  const uint8_t* data_;
  size_t stride_ = 0;
  float offset_, scale_;
  std::vector<Sample> columns_;
  Sample row_{};
};

struct Profile {
  int primaries = 1, transfer = 13, matrix = 6;  // Untagged SDR defaults to BT.709 primaries, sRGB, BT.601 matrix.
  bool full_range = true;
};

Profile ReadProfile(const heif_image* image, const heif_image_handle* handle) {
  heif_color_profile_nclx* raw = nullptr;
  const heif_error error = heif_image_get_nclx_color_profile(image, &raw);  // Includes decoder VUI and container overrides.
  HeifPtr<heif_color_profile_nclx, heif_nclx_color_profile_free> nclx(raw);
  if (error.code != heif_error_Color_profile_does_not_exist) Check(error);
  if ((!nclx || nclx->color_primaries == 2 || nclx->transfer_characteristics == 2) && heif_image_handle_get_raw_color_profile_size(handle)) {
    throw std::runtime_error("HEIF with an ICC-only color profile requires a color-managed decoder");
  }
  Profile profile;
  if (nclx) {
    if (nclx->color_primaries != 2) profile.primaries = nclx->color_primaries;
    if (nclx->transfer_characteristics != 2) profile.transfer = nclx->transfer_characteristics;
    if (nclx->matrix_coefficients != 2) profile.matrix = nclx->matrix_coefficients;
    profile.full_range = nclx->full_range_flag != 0;
  }
  return profile;
}

using RgbTriplet = std::array<float, 3>;
using ColorMatrix = std::array<RgbTriplet, 3>;

ColorMatrix ToSrgbMatrix(int primaries) {
  switch (primaries) {
    case 1: return {{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}};
    case 9: return {{{1.660491f, -.587641f, -.072850f}, {-.124551f, 1.132900f, -.008349f}, {-.018151f, -.100579f, 1.118730f}}};
    case 12: return {{{1.224940f, -.224940f, 0}, {-.042057f, 1.042057f, 0}, {-.019638f, -.078636f, 1.098274f}}};
    default: throw std::runtime_error("Unsupported HEIF color primaries");
  }
}

float LinearSample(float code, int transfer) {
  switch (transfer) {
    case 1: case 6: case 14: case 15: return code < .081f ? code / 4.5f : std::pow((code + .099f) / 1.099f, 1 / .45f);
    case 4: return std::pow(code, 2.2f);
    case 5: return std::pow(code, 2.8f);
    case 8: return code;
    case 13: return code <= .04045f ? code / 12.92f : std::pow((code + .055f) / 1.055f, 2.4f);
    case 16: {  // ST 2084 absolute luminance, expressed relative to 203 nit HDR reference white.
      const float p = std::pow(code, 32.f / 2523);
      return (10000.f / 203) * std::pow(std::max(p - 3424.f / 4096, 0.f) / (2413.f / 128 - (2392.f / 128) * p), 16384.f / 2610);
    }
    case 18: return code <= .5f ? code * code / 3 : (std::exp((code - .55991073f) / .17883277f) + .28466892f) / 12;
    default: throw std::runtime_error("Unsupported HEIF transfer function");
  }
}

constexpr int kTableScale = 4096;
using TransferTable = std::array<float, kTableScale + 1>;

float Lookup(const TransferTable& table, float value) {
  const float index = std::clamp(value, 0.f, 1.f) * kTableScale;
  const int low = static_cast<int>(index);
  return std::lerp(table[low], table[std::min(low + 1, kTableScale)], index - low);
}

class DisplayTransform {
 public:
  explicit DisplayTransform(Profile profile) : matrix_(ToSrgbMatrix(profile.primaries)), transfer_(profile.transfer),
                                               identity_(profile.primaries == 1 && profile.transfer == 13) {
    if (!identity_) for (int i = 0; i <= kTableScale; ++i) linear_[i] = LinearSample(static_cast<float>(i) / kTableScale, transfer_);
  }

  RgbTriplet Apply(RgbTriplet rgb) const {
    if (identity_) return rgb;
    for (float& c : rgb) c = Lookup(linear_, c);
    RgbTriplet srgb;
    for (size_t i = 0; i < 3; ++i) srgb[i] = matrix_[i][0] * rgb[0] + matrix_[i][1] * rgb[1] + matrix_[i][2] * rgb[2];
    if (transfer_ == 16 || transfer_ == 18) {
      const float luma = std::max(0.f, .2126f * srgb[0] + .7152f * srgb[1] + .0722f * srgb[2]);
      // BT.2100 HLG OOTF for a nominal 1000 nit display. PQ already supplies absolute luminance.
      float gain = transfer_ == 18 ? (1000.f / 203) * std::pow(luma, .2f) : 1.f;
      const float luminance = luma * gain;
      // Preserve shadow contrast, then smoothly compress specular highlights into the SDR output.
      constexpr float knee = .6f;
      if (luminance > knee) gain *= (knee + (1 - knee) * (luminance - knee) / (1 - knee + luminance - knee)) / luminance;
      for (float& c : srgb) c *= gain;
    }
    static const TransferTable encode = [] {
      TransferTable table;
      for (int i = 0; i <= kTableScale; ++i) {
        const float x = static_cast<float>(i) / kTableScale;
        table[i] = x <= .0031308f ? 12.92f * x : 1.055f * std::pow(x, 1.f / 2.4f) - .055f;
      }
      return table;
    }();
    for (float& c : srgb) c = Lookup(encode, c);
    return srgb;
  }

 private:
  ColorMatrix matrix_;
  int transfer_;
  bool identity_;
  TransferTable linear_{};
};

std::array<float, 4> YuvCoefficients(int matrix) {
  float kr, kb;
  switch (matrix) {
    case 1: kr = .2126f; kb = .0722f; break;
    case 5: case 6: kr = .299f; kb = .114f; break;
    case 9: kr = .2627f; kb = .0593f; break;
    default: throw std::runtime_error("Unsupported HEIF YCbCr matrix");
  }
  return {2 * (1 - kr), -2 * kb * (1 - kb) / (1 - kr - kb), -2 * kr * (1 - kr) / (1 - kr - kb), 2 * (1 - kb)};
}

void Render(const heif_image_handle* handle, HeifImageSize target, std::span<uint8_t> rgba, size_t stride) {
  HeifPtr<heif_decoding_options, heif_decoding_options_free> options(heif_decoding_options_alloc());
  if (!options) throw std::bad_alloc();
  options->output_image_nclx_profile_passthrough = true;
  options->autocorrect_broken_input = true;  // Sony HIF files can label full-range HEVC samples as limited in colr.
  options->num_codec_threads = 2;
  // In particular, libheif would expand a portrait 4:2:2 original to full-sized 4:4:4 before allocating a rotated copy.
  const Orientation orientation = OutputOrientation(handle);
  options->ignore_transformations = orientation != Orientation{};
  heif_image* raw = nullptr;
  Check(heif_decode_image(handle, &raw, heif_colorspace_undefined, heif_chroma_undefined, options.get()));
  Image image(raw);
  const auto source = orientation.SourceSize(Size(handle));
  if (heif_image_get_primary_width(raw) != source.width || heif_image_get_primary_height(raw) != source.height) {
    throw std::runtime_error("HEIF decoded size does not match the image header");
  }
  const auto space = heif_image_get_colorspace(raw);
  if (space != heif_colorspace_YCbCr && space != heif_colorspace_RGB && space != heif_colorspace_monochrome) {
    throw std::runtime_error("Unsupported HEIF pixel layout");
  }
  const Profile profile = ReadProfile(raw, handle);
  DisplayTransform transform(profile);
  const bool yuv = space == heif_colorspace_YCbCr;
  const bool identity_matrix = yuv && profile.matrix == 0;  // GBR planes.
  std::array<float, 4> coeff{};
  if (yuv && !identity_matrix) coeff = YuvCoefficients(profile.matrix);
  const auto sampled = orientation.SourceSize(target);
  Plane first(raw, space == heif_colorspace_RGB ? heif_channel_R : heif_channel_Y, sampled, profile.full_range, false);
  std::optional<Plane> second, third, alpha;
  if (space != heif_colorspace_monochrome) {
    const int cosited_width = yuv && heif_image_get_chroma_format(raw) == heif_chroma_422 ? source.width : 0;
    second.emplace(raw, yuv ? heif_channel_Cb : heif_channel_G, sampled, profile.full_range, yuv && !identity_matrix, cosited_width);
    third.emplace(raw, yuv ? heif_channel_Cr : heif_channel_B, sampled, profile.full_range, yuv && !identity_matrix, cosited_width);
  }
  if (heif_image_has_channel(raw, heif_channel_Alpha)) alpha.emplace(raw, heif_channel_Alpha, sampled, true, false);
  const bool premultiplied = heif_image_is_premultiplied_alpha(raw);
  auto quantize = [](float value) { return static_cast<uint8_t>(std::clamp(value, 0.f, 1.f) * 255 + .5f); };
  const auto row_stride = static_cast<std::ptrdiff_t>(stride);
  const auto step_x = orientation.xx * 4 + orientation.yx * row_stride;
  const auto step_y = orientation.xy * 4 + orientation.yy * row_stride;
  const auto origin = (orientation.xx < 0 || orientation.xy < 0 ? (target.width - 1) * 4 : 0) +
                      (orientation.yx < 0 || orientation.yy < 0 ? (target.height - 1) * row_stride : 0);
  for (int y = 0; y < sampled.height; ++y) {
    first.SetRow(y);
    if (second) { second->SetRow(y); third->SetRow(y); }
    if (alpha) alpha->SetRow(y);
    const auto row = origin + y * step_y;
    for (int x = 0; x < sampled.width; ++x) {
      uint8_t* pixel = rgba.data() + (row + x * step_x);
      const float a = alpha ? std::clamp(alpha->At(x), 0.f, 1.f) : 1;
      const float value = first.At(x);
      RgbTriplet rgb{value, value, value};
      if (second) {
        const float b = second->At(x), c = third->At(x);
        if (!yuv) rgb = {value, b, c};
        else if (identity_matrix) rgb = {c, value, b};
        else rgb = {value + coeff[0] * c, value + coeff[1] * b + coeff[2] * c, value + coeff[3] * b};
      }
      if (premultiplied && a > 0 && a < 1) for (float& c : rgb) c /= a;
      rgb = transform.Apply(rgb);
      for (size_t i = 0; i < 3; ++i) pixel[i] = quantize(std::clamp(rgb[i], 0.f, 1.f) * a);
      pixel[3] = quantize(a);
    }
  }
}

}  // namespace

HeifImageSize ProbeHeifImage(std::span<const uint8_t> bytes) { return Size(OpenPrimary(bytes).get()); }

void DecodeHeifRgba(std::span<const uint8_t> bytes, HeifImageSize target, std::span<uint8_t> rgba, size_t stride, bool allow_thumbnail) {
  auto primary = OpenPrimary(bytes);
  const auto original = Size(primary.get());
  if (!Pixels(target) || target.width > original.width || target.height > original.height || !SameAspect(original, target) ||
      stride < static_cast<size_t>(target.width) * 4 || static_cast<size_t>(target.height) > rgba.size() / stride) {
    throw std::invalid_argument("Invalid HEIF output size or row stride");
  }
  if (allow_thumbnail) {
    if (auto thumbnail = FindThumbnail(primary.get(), target)) {
      try {
        Render(thumbnail.get(), target, rgba, stride);
        return;
      } catch (const std::runtime_error&) { /* A thumbnail can use an unavailable codec even when the primary is decodable. */ }
    }
  }
  Render(primary.get(), target, rgba, stride);
}

}  // namespace prooftray

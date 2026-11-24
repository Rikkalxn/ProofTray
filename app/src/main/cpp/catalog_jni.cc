#include <jni.h>

#include "jni_support.h"

#include <cmath>
#include <cstdint>
#include <limits>
#include <new>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "prooftray/catalog.h"

namespace {

constexpr jsize kTextStride = 7;
constexpr jsize kIntegerStride = 12;
constexpr jsize kDecimalStride = 3;
constexpr jsize kFilterIntegerCount = 15;
constexpr jsize kFilterDecimalCount = 6;
constexpr jsize kMaxAssets = 500000;
constexpr jsize kMaxTags = 2000000;
constexpr int64_t kMissingInteger = std::numeric_limits<int64_t>::min();

using prooftray::jni::ThrowJava;
using prooftray::jni::CheckJni;

void Require(bool condition, const char* message) {
  if (!condition) throw std::invalid_argument(message);
}


void AppendUtf8(std::string& output, uint32_t code_point) {
  if (code_point < 0x80) {
    output.push_back(static_cast<char>(code_point));
  } else if (code_point < 0x800) {
    output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
    output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  } else if (code_point < 0x10000) {
    output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
    output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  } else {
    output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
    output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  }
}

std::string ReadString(JNIEnv* environment, jobjectArray array, jsize index) {
  auto value = static_cast<jstring>(environment->GetObjectArrayElement(array, index));
  CheckJni(environment);
  Require(value != nullptr, "Metadata strings must not be null");
  const jsize length = environment->GetStringLength(value);
  if (length > 65536) {
    environment->DeleteLocalRef(value);
    throw std::invalid_argument("Metadata string is too long");
  }
  const jchar* characters = environment->GetStringChars(value, nullptr);
  if (characters == nullptr) {
    environment->DeleteLocalRef(value);
    CheckJni(environment);
    throw std::bad_alloc();
  }
  std::string result;
  try {
    result.reserve(static_cast<size_t>(length));
    for (jsize offset = 0; offset < length; ++offset) {
      uint32_t code_point = characters[offset];
      if (code_point >= 0xd800 && code_point <= 0xdbff && offset + 1 < length &&
          characters[offset + 1] >= 0xdc00 && characters[offset + 1] <= 0xdfff) {
        code_point = 0x10000 + ((code_point - 0xd800) << 10) + (characters[++offset] - 0xdc00);
      } else if (code_point >= 0xd800 && code_point <= 0xdfff) {
        code_point = 0xfffd;
      }
      AppendUtf8(result, code_point);
    }
  } catch (...) {
    environment->ReleaseStringChars(value, characters);
    environment->DeleteLocalRef(value);
    throw;
  }
  environment->ReleaseStringChars(value, characters);
  environment->DeleteLocalRef(value);
  return result;
}

std::vector<jlong> ReadIntegers(JNIEnv* environment, jlongArray array) {
  std::vector<jlong> values(static_cast<size_t>(environment->GetArrayLength(array)));
  if (!values.empty()) environment->GetLongArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
  CheckJni(environment);
  return values;
}

std::vector<jdouble> ReadDecimals(JNIEnv* environment, jdoubleArray array) {
  std::vector<jdouble> values(static_cast<size_t>(environment->GetArrayLength(array)));
  if (!values.empty()) environment->GetDoubleArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
  CheckJni(environment);
  return values;
}

std::optional<int64_t> OptionalInteger(jlong value) {
  return value == kMissingInteger ? std::nullopt : std::optional<int64_t>(value);
}

std::optional<double> OptionalDecimal(jdouble value) {
  if (std::isnan(value)) return std::nullopt;
  Require(std::isfinite(value), "Decimal metadata must be finite or NaN for unknown");
  return value;
}

jintArray Query(JNIEnv* environment, jobjectArray text, jlongArray integers, jdoubleArray decimals, jobjectArray tags,
                jobjectArray filter_text, jlongArray filter_integers, jdoubleArray filter_decimals, jobjectArray filter_tags,
                jboolean collapse_stacks) {
  Require(text && integers && decimals && tags && filter_text && filter_integers && filter_decimals && filter_tags, "Null catalog arrays");
  const jsize text_count = environment->GetArrayLength(text);
  Require(text_count % kTextStride == 0, "Invalid catalog text stride");
  const jsize count = text_count / kTextStride;
  Require(count <= kMaxAssets, "Catalog exceeds the supported batch size");
  Require(environment->GetArrayLength(integers) == count * kIntegerStride, "Invalid catalog integer stride");
  Require(environment->GetArrayLength(decimals) == count * kDecimalStride, "Invalid catalog decimal stride");
  Require(environment->GetArrayLength(filter_text) == 3, "Invalid filter text count");
  Require(environment->GetArrayLength(filter_integers) == kFilterIntegerCount, "Invalid filter integer count");
  Require(environment->GetArrayLength(filter_decimals) == kFilterDecimalCount, "Invalid filter decimal count");
  const jsize tag_count = environment->GetArrayLength(tags);
  Require(tag_count <= kMaxTags && environment->GetArrayLength(filter_tags) <= kMaxTags, "Too many catalog tags");
  const auto integer_values = ReadIntegers(environment, integers);
  const auto decimal_values = ReadDecimals(environment, decimals);
  std::vector<std::string> tag_values;
  tag_values.reserve(tag_count);
  for (jsize index = 0; index < tag_count; ++index) tag_values.push_back(ReadString(environment, tags, index));

  std::vector<prooftray::PhotoAsset> assets;
  assets.reserve(count);
  for (jsize index = 0; index < count; ++index) {
    const auto* number = integer_values.data() + index * kIntegerStride;
    const auto* decimal = decimal_values.data() + index * kDecimalStride;
    Require(number[0] >= 0 && number[0] <= 5 && number[6] >= 0 && number[6] <= 5, "Invalid asset format or rating");
    Require(number[7] >= 0 && number[7] <= 2 && number[8] >= 0 && number[8] <= 2, "Invalid asset state");
    Require(number[9] >= 0 && number[10] >= 0 && number[9] <= tag_count && number[10] <= tag_count - number[9], "Invalid tag range");
    Require(number[11] == 0 || number[11] == 1, "Invalid metadata completion flag");
    prooftray::PhotoAsset asset;
    asset.id = ReadString(environment, text, index * kTextStride);
    asset.name = ReadString(environment, text, index * kTextStride + 1);
    asset.camera = ReadString(environment, text, index * kTextStride + 2);
    asset.lens = ReadString(environment, text, index * kTextStride + 3);
    asset.source_id = ReadString(environment, text, index * kTextStride + 4);
    asset.folder = ReadString(environment, text, index * kTextStride + 5);
    asset.stack_id = ReadString(environment, text, index * kTextStride + 6);
    asset.format = static_cast<prooftray::MediaFormat>(number[0]);
    asset.size_bytes = OptionalInteger(number[1]);
    asset.captured_at_ms = OptionalInteger(number[2]);
    asset.width = OptionalInteger(number[3]);
    asset.height = OptionalInteger(number[4]);
    asset.iso = OptionalInteger(number[5]);
    asset.rating = static_cast<int>(number[6]);
    asset.pick_state = static_cast<prooftray::PickState>(number[7]);
    asset.download_state = static_cast<prooftray::DownloadState>(number[8]);
    asset.tags.assign(tag_values.begin() + number[9], tag_values.begin() + number[9] + number[10]);
    asset.metadata_complete = number[11] != 0;
    asset.aperture = OptionalDecimal(decimal[0]);
    asset.exposure_seconds = OptionalDecimal(decimal[1]);
    asset.focal_length_mm = OptionalDecimal(decimal[2]);
    assets.push_back(std::move(asset));
  }

  const auto fields = ReadIntegers(environment, filter_integers);
  const auto fractions = ReadDecimals(environment, filter_decimals);
  Require(fields[0] >= 0 && fields[0] < 64 && fields[3] >= 0 && fields[3] < 8 && fields[4] >= 0 && fields[4] < 8, "Invalid filter mask");
  Require(fields[1] >= 0 && fields[2] <= 5, "Rating bounds must be between 0 and 5");
  Require(fields[11] >= 0 && fields[11] <= 3 && fields[14] >= 0 && fields[14] <= 4, "Invalid filter enumeration");
  Require((fields[12] == 0 || fields[12] == 1) && (fields[13] == 0 || fields[13] == 1), "Invalid filter flag");
  prooftray::CatalogFilter filter;
  filter.text = ReadString(environment, filter_text, 0);
  filter.camera = ReadString(environment, filter_text, 1);
  filter.lens = ReadString(environment, filter_text, 2);
  filter.format_mask = static_cast<uint32_t>(fields[0]);
  filter.rating = {fields[1], fields[2]};
  filter.pick_mask = static_cast<uint32_t>(fields[3]);
  filter.download_mask = static_cast<uint32_t>(fields[4]);
  filter.captured_at_ms = {OptionalInteger(fields[5]), OptionalInteger(fields[6])};
  filter.size_bytes = {OptionalInteger(fields[7]), OptionalInteger(fields[8])};
  filter.iso = {OptionalInteger(fields[9]), OptionalInteger(fields[10])};
  filter.orientation = static_cast<prooftray::Orientation>(fields[11]);
  filter.paired_raw_jpeg_only = fields[12] != 0;
  filter.include_unknown_metadata = fields[13] != 0;
  filter.sort_order = static_cast<prooftray::SortOrder>(fields[14]);
  filter.aperture = {OptionalDecimal(fractions[0]), OptionalDecimal(fractions[1])};
  filter.exposure_seconds = {OptionalDecimal(fractions[2]), OptionalDecimal(fractions[3])};
  filter.focal_length_mm = {OptionalDecimal(fractions[4]), OptionalDecimal(fractions[5])};
  for (jsize index = 0; index < environment->GetArrayLength(filter_tags); ++index) {
    filter.required_tags.push_back(ReadString(environment, filter_tags, index));
  }

  const auto result = prooftray::QueryCatalog(assets, filter, collapse_stacks == JNI_TRUE);
  std::vector<jint> encoded{1, static_cast<jint>(result.matched_count), static_cast<jint>(result.pending_metadata_count),
                            static_cast<jint>(result.groups.size())};
  encoded.reserve(4 + result.groups.size() * 3 + assets.size());
  for (const auto& group : result.groups) {
    encoded.push_back(static_cast<jint>(group.anchor_index));
    encoded.push_back(group.inferred_burst ? 1 : 0);
    encoded.push_back(static_cast<jint>(group.members.size()));
    for (size_t member : group.members) encoded.push_back(static_cast<jint>(member));
  }
  jintArray output = environment->NewIntArray(static_cast<jsize>(encoded.size()));
  if (output == nullptr) return nullptr;
  environment->SetIntArrayRegion(output, 0, static_cast<jsize>(encoded.size()), encoded.data());
  return environment->ExceptionCheck() ? nullptr : output;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL Java_org_prooftray_app_domain_CatalogEngine_queryNative(
    JNIEnv* environment, jobject, jobjectArray text, jlongArray integers, jdoubleArray decimals, jobjectArray tags,
    jobjectArray filter_text, jlongArray filter_integers, jdoubleArray filter_decimals, jobjectArray filter_tags, jboolean collapse_stacks) {
  try {
    return Query(environment, text, integers, decimals, tags, filter_text, filter_integers, filter_decimals, filter_tags, collapse_stacks);
  } catch (const std::bad_alloc&) {
    ThrowJava(environment, "java/lang/OutOfMemoryError", "Not enough memory for the catalog query");
  } catch (const std::invalid_argument& error) {
    ThrowJava(environment, "java/lang/IllegalArgumentException", error.what());
  } catch (const std::exception& error) {
    ThrowJava(environment, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    ThrowJava(environment, "java/lang/IllegalStateException", "Unexpected native catalog failure");
  }
  return nullptr;
}

#include <android/bitmap.h>
#include <jni.h>

#include "jni_support.h"

#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <unordered_map>

#include "openbridge/color_lut.h"

namespace {
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<const openbridge::ColorLut>> registry;
std::atomic<jlong> next_id{1};

using openbridge::jni::ThrowArgument;

std::shared_ptr<const openbridge::ColorLut> Get(jlong id) {
  if (id == 0) return {};
  std::lock_guard lock(registry_mutex);
  const auto found = registry.find(id);
  if (found == registry.end()) throw std::invalid_argument("LUT handle is no longer available");
  return found->second;
}

jlong Save(openbridge::ColorLut lut) {
  auto value = std::make_shared<const openbridge::ColorLut>(std::move(lut));
  const jlong id = next_id.fetch_add(1);
  std::lock_guard lock(registry_mutex);
  registry.emplace(id, std::move(value));
  return id;
}

class BitmapPixels {
 public:
  BitmapPixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) throw std::invalid_argument("Expected software RGBA8 bitmap");
    if (AndroidBitmap_lockPixels(env, bitmap, &data_) != ANDROID_BITMAP_RESULT_SUCCESS) throw std::invalid_argument("Cannot lock image pixels");
  }
  ~BitmapPixels() { AndroidBitmap_unlockPixels(env_, bitmap_); }
  std::span<uint8_t> bytes() { return {static_cast<uint8_t*>(data_), static_cast<size_t>(info.stride) * info.height}; }
  AndroidBitmapInfo info{};
 private:
  JNIEnv* env_;
  jobject bitmap_;
  void* data_ = nullptr;
};

std::vector<uint8_t> Bytes(JNIEnv* env, jbyteArray data) {
  return openbridge::jni::ReadBytes(env, data, 32 * 1024 * 1024, "LUT file exceeds the size limit");
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_org_openbridge_app_color_NativeColor_load(JNIEnv* env, jobject, jbyteArray data, jboolean cube) {
  try {
    const auto bytes = Bytes(env, data);
    return Save(cube ? openbridge::ParseCube(std::string_view(reinterpret_cast<const char*>(bytes.data()), bytes.size()))
                     : openbridge::ParseLutBinary(bytes));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_openbridge_app_color_NativeColor_hald(JNIEnv* env, jobject, jobject bitmap) {
  try {
    BitmapPixels pixels(env, bitmap);
    if (pixels.info.width != pixels.info.height) throw std::invalid_argument("Hald LUT must be a square image");
    return Save(openbridge::FromHaldRgba(pixels.bytes(), pixels.info.width, pixels.info.stride));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_org_openbridge_app_color_NativeColor_values(JNIEnv* env, jobject, jlong id, jboolean metadata) {
  try {
    const auto lut = Get(id);
    if (!lut) throw std::invalid_argument("No LUT selected");
    std::vector<float> result;
    if (metadata) {
      result = {static_cast<float>(lut->dimension), static_cast<float>(lut->size)};
      result.insert(result.end(), lut->domain_min.begin(), lut->domain_min.end());
      result.insert(result.end(), lut->domain_max.begin(), lut->domain_max.end());
      result.push_back(lut->domain_declared ? 1.f : 0.f);
    } else result = lut->values;
    auto output = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (output) env->SetFloatArrayRegion(output, 0, static_cast<jsize>(result.size()), result.data());
    return output;
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_openbridge_app_color_NativeColor_pack(JNIEnv* env, jobject, jlong id) {
  try {
    const auto lut = Get(id);
    if (!lut) throw std::invalid_argument("No LUT selected");
    const auto bytes = openbridge::SerializeLut(*lut);
    return openbridge::jni::ByteArray(env, bytes);
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_org_openbridge_app_color_NativeColor_release(JNIEnv*, jobject, jlong id) {
  std::lock_guard lock(registry_mutex);
  registry.erase(id);
}

extern "C" JNIEXPORT void JNICALL Java_org_openbridge_app_color_NativeColor_apply(
    JNIEnv* env, jobject, jlong id, jobject bitmap, jfloatArray settings, jboolean premultiplied, jint input, jint output) {
  try {
    if (!settings || env->GetArrayLength(settings) != 5) throw std::invalid_argument("Invalid look parameters");
    std::array<float, 5> values{};
    env->GetFloatArrayRegion(settings, 0, 5, values.data());
    openbridge::ColorLook look{values[0], values[1], values[2], values[3], values[4]};
    look.input_encoding = static_cast<openbridge::LutEncoding>(input);
    look.output_encoding = static_cast<openbridge::LutEncoding>(output);
    const auto lut = Get(id);
    BitmapPixels pixels(env, bitmap);
    openbridge::ApplyColorLookRgba(pixels.bytes(), pixels.info.width, pixels.info.height, pixels.info.stride, premultiplied, look, lut.get());
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); }
}

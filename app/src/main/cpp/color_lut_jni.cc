#include <android/bitmap.h>
#include <jni.h>

#include "jni_support.h"
#include "color_look_jni.h"

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <unordered_map>

#include "prooftray/color_histogram.h"
#include "prooftray/color_lut.h"

namespace {
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<const prooftray::ColorLut>> registry;
std::atomic<jlong> next_id{1};

using prooftray::jni::ThrowArgument;

std::shared_ptr<const prooftray::ColorLut> Get(jlong id) {
  if (id == 0) return {};
  std::lock_guard lock(registry_mutex);
  const auto found = registry.find(id);
  if (found == registry.end()) throw std::invalid_argument("LUT handle is no longer available");
  return found->second;
}

jlong Save(prooftray::ColorLut lut) {
  auto value = std::make_shared<const prooftray::ColorLut>(std::move(lut));
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
  return prooftray::jni::ReadBytes(env, data, 32 * 1024 * 1024, "LUT file exceeds the size limit");
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_org_prooftray_app_color_NativeColor_load(JNIEnv* env, jobject, jbyteArray data, jboolean cube) {
  try {
    const auto bytes = Bytes(env, data);
    return Save(cube ? prooftray::ParseCube(std::string_view(reinterpret_cast<const char*>(bytes.data()), bytes.size()))
                     : prooftray::ParseLutBinary(bytes));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_prooftray_app_color_NativeColor_hald(JNIEnv* env, jobject, jobject bitmap) {
  try {
    BitmapPixels pixels(env, bitmap);
    if (pixels.info.width != pixels.info.height) throw std::invalid_argument("Hald LUT must be a square image");
    return Save(prooftray::FromHaldRgba(pixels.bytes(), pixels.info.width, pixels.info.stride));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_org_prooftray_app_color_NativeColor_values(JNIEnv* env, jobject, jlong id, jboolean metadata) {
  try {
    const auto lut = Get(id);
    if (!lut) throw std::invalid_argument("No LUT selected");
    const std::array<float, 9> info{static_cast<float>(lut->dimension), static_cast<float>(lut->size),
                                    lut->domain_min[0], lut->domain_min[1], lut->domain_min[2],
                                    lut->domain_max[0], lut->domain_max[1], lut->domain_max[2], lut->domain_declared ? 1.f : 0.f};
    const auto result = metadata ? std::span<const float>(info) : std::span<const float>(lut->values);
    auto output = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (output) env->SetFloatArrayRegion(output, 0, static_cast<jsize>(result.size()), result.data());
    return output;
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_color_NativeColor_pack(JNIEnv* env, jobject, jlong id) {
  try {
    const auto lut = Get(id);
    if (!lut) throw std::invalid_argument("No LUT selected");
    const auto bytes = prooftray::SerializeLut(*lut);
    return prooftray::jni::ByteArray(env, bytes);
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_org_prooftray_app_color_NativeColor_release(JNIEnv*, jobject, jlong id) {
  std::lock_guard lock(registry_mutex);
  registry.erase(id);
}

extern "C" JNIEXPORT void JNICALL Java_org_prooftray_app_color_NativeColor_apply(
    JNIEnv* env, jobject, jlong id, jobject bitmap, jfloatArray settings, jfloatArray edits, jboolean premultiplied, jint input, jint output) {
  try {
    const auto look = prooftray::jni::ReadColorLook(env, settings, edits, input, output);
    const auto lut = Get(id);
    BitmapPixels pixels(env, bitmap);
    prooftray::ApplyColorLookRgba(pixels.bytes(), pixels.info.width, pixels.info.height, pixels.info.stride, premultiplied, look, lut.get());
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); }
}

extern "C" JNIEXPORT jlong JNICALL Java_org_prooftray_app_color_NativeColor_bake(
    JNIEnv* env, jobject, jlong id, jfloatArray settings, jfloatArray edits, jint input, jint output) {
  try {
    const auto look = prooftray::jni::ReadColorLook(env, settings, edits, input, output);
    const auto source = Get(id);
    return Save(prooftray::BakeColorLook(look, source.get()));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL Java_org_prooftray_app_color_NativeColor_histogram(
    JNIEnv* env, jobject, jlong id, jobject bitmap, jboolean premultiplied, jfloatArray settings, jfloatArray edits, jint input, jint output,
    jint viewport_width, jint viewport_height) {
  try {
    std::optional<prooftray::ColorLook> look;
    if (settings) {
      look = prooftray::jni::ReadColorLook(env, settings, edits, input, output);
      prooftray::ValidateLook(*look);
    }
    const auto lut = Get(id);
    BitmapPixels pixels(env, bitmap);
    const auto& info = pixels.info;
    const auto bins = prooftray::PreviewHistogramRgba(pixels.bytes(), info.width, info.height, info.stride, premultiplied,
                                                       look ? &*look : nullptr, lut.get(), {viewport_width, viewport_height});
    auto result = env->NewIntArray(static_cast<jsize>(bins.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(bins.size()), bins.data());
    return result;
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

#include <android/bitmap.h>
#include <jni.h>

#include "jni_support.h"
#include <libraw/libraw.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

#include "linear_raw.h"
#include "raw_file_stream.h"
#include "openbridge/log_encoding.h"
#include "openbridge/raw_exposure.h"

namespace {

struct Cancellation {
  JNIEnv* environment;
  jobject signal;
  jmethodID is_cancelled;

  bool Requested() const { return environment->CallBooleanMethod(signal, is_cancelled) == JNI_TRUE || environment->ExceptionCheck(); }
};

int Progress(void* context, LibRaw_progress, int, int) { return static_cast<Cancellation*>(context)->Requested() ? 1 : 0; }

void Check(int status, const char* phase) {
  if (status != LIBRAW_SUCCESS) throw std::runtime_error(std::string(phase) + ": " + libraw_strerror(status));
}

jobject NewBitmap(JNIEnv* environment, int width, int height) {
  const auto bitmap_class = environment->FindClass("android/graphics/Bitmap");
  const auto config_class = environment->FindClass("android/graphics/Bitmap$Config");
  if (!bitmap_class || !config_class) return nullptr;
  const auto config_field = environment->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
  const auto create = environment->GetStaticMethodID(bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  if (!config_field || !create) return nullptr;
  return environment->CallStaticObjectMethod(bitmap_class, create, width, height, environment->GetStaticObjectField(config_class, config_field));
}

}  // namespace

namespace {
using openbridge::Rgb;

std::unique_ptr<openbridge::LinearRaster> ReadLinear(int fd, int edge, int64_t budget, openbridge::RawDecodeSize mode, Cancellation& cancel) {
  return std::make_unique<openbridge::LinearRaster>(std::make_unique<openbridge::RawFileStream>(fd), edge, budget, mode, Progress, &cancel);
}

// Only called for a bitmap that has not been handed back to Java. Preserve the original JNI failure during cleanup.
void RecycleUnpublished(JNIEnv* env, jobject bitmap) {
  auto pending = env->ExceptionOccurred();
  if (pending) env->ExceptionClear();
  auto type = env->GetObjectClass(bitmap);
  if (type) {
    auto recycle = env->GetMethodID(type, "recycle", "()V");
    if (recycle) env->CallVoidMethod(bitmap, recycle);
  }
  if (pending) {
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->Throw(pending);
  }
}

template <typename Work>
jobject WithSignal(JNIEnv* env, jobject signal, Work work) {
  Cancellation cancel{env, signal, nullptr};
  try {
    cancel.is_cancelled = env->GetMethodID(env->GetObjectClass(signal), "isCanceled", "()Z");
    if (!cancel.is_cancelled || cancel.Requested()) return nullptr;
    return work(cancel);
  } catch (const std::exception& e) {
    if (!env->ExceptionCheck()) {
      const bool stopped = cancel.is_cancelled && cancel.Requested();
      env->ThrowNew(env->FindClass(stopped ? "java/util/concurrent/CancellationException" : "java/lang/IllegalStateException"), e.what());
    }
    return nullptr;
  }
}

template <typename ReadPixel>
jobject RenderLinear(JNIEnv* env, int width, int height, jfloatArray curve_samples, jbyteArray packed, jfloatArray settings,
                     jfloat gain_ev, jint encoding, jint output_encoding, jboolean video, Cancellation& cancel, ReadPixel read) {
  if (!curve_samples || env->GetArrayLength(curve_samples) > openbridge::RawToneCurve::kSamples ||
      !settings || env->GetArrayLength(settings) != 5 || !std::isfinite(gain_ev) || std::abs(gain_ev) > 3) {
    throw std::invalid_argument("Invalid RAW rendering parameters");
  }
  std::vector<float> samples(env->GetArrayLength(curve_samples));
  if (!samples.empty()) env->GetFloatArrayRegion(curve_samples, 0, samples.size(), samples.data());
  const auto curve = openbridge::RawToneCurve::Parse(samples);
  std::array<float, 5> v{};
  env->GetFloatArrayRegion(settings, 0, 5, v.data());
  if (env->ExceptionCheck()) return nullptr;
  std::optional<openbridge::ColorLut> lut;
  if (packed) lut.emplace(openbridge::ParseLutBinary(openbridge::jni::ReadBytes(env, packed, 16 * 1024 * 1024, "Invalid RAW LUT payload")));
  const auto range = video ? openbridge::LogRange::Video10 : openbridge::LogRange::Full10;
  const openbridge::ColorLook look{v[0], v[1], v[2], v[3], v[4], static_cast<openbridge::LutEncoding>(encoding),
                                   static_cast<openbridge::LutEncoding>(output_encoding)};
  const bool scene = look.input_encoding != openbridge::LutEncoding::Direct && look.input_encoding != openbridge::LutEncoding::Rec709Gamma24;
  std::optional<openbridge::LogGrade> log_grade;
  std::optional<openbridge::RawDisplayGrade> display_grade;
  if (scene) {
    if (!lut) throw std::invalid_argument("Scene RAW rendering requires a LUT");
    log_grade.emplace(openbridge::PrepareRawLogGrade(range, gain_ev, look, openbridge::kGenericRawExposureReference, curve));
  } else display_grade.emplace(openbridge::PrepareRawDisplayGrade(curve, look));
  jobject bitmap = NewBitmap(env, width, height);
  if (!bitmap || env->ExceptionCheck()) return nullptr;
  AndroidBitmapInfo info{};
  void* pixels = nullptr;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    RecycleUnpublished(env, bitmap);
    throw std::runtime_error("Cannot lock RAW output");
  }
  try {
    for (int y = 0; y < height; ++y) {
      if (cancel.Requested()) throw std::runtime_error("RAW render cancelled");
      auto* dst = static_cast<uint8_t*>(pixels) + size_t(y) * info.stride;
      for (int x = 0; x < width; ++x, dst += 4) {
        const auto rgb = scene ? openbridge::RenderLogPixel(read(x, y), *lut, *log_grade)
                               : openbridge::RenderRawDisplayPixel(read(x, y), lut ? &*lut : nullptr, *display_grade);
        for (int c = 0; c < 3; ++c) dst[c] = static_cast<uint8_t>(std::lround(std::clamp(rgb[c], 0.f, 1.f) * 255));
        dst[3] = 255;
      }
    }
  } catch (...) {
    AndroidBitmap_unlockPixels(env, bitmap);
    RecycleUnpublished(env, bitmap);
    throw;
  }
  AndroidBitmap_unlockPixels(env, bitmap);
  return bitmap;
}
}  // namespace

extern "C" JNIEXPORT jobject JNICALL Java_org_openbridge_app_data_RawPhotoDecoder_readLinearNative(
    JNIEnv* env, jobject, jint fd, jint edge, jobject signal) {
  return WithSignal(env, signal, [&](Cancellation& cancel) -> jobject {
    if (edge <= 0 || edge > 1600) throw std::invalid_argument("Invalid linear preview size");
    auto raw = ReadLinear(fd, edge, int64_t{edge} * edge, openbridge::RawDecodeSize::Preview, cancel);
    auto type = env->FindClass("org/openbridge/app/data/LinearRawPreview");
    if (!type) return nullptr;
    auto init = env->GetMethodID(type, "<init>", "(II)V");
    if (!init) return nullptr;
    auto preview = env->NewObject(type, init, raw->width, raw->height);
    if (!preview || env->ExceptionCheck()) return nullptr;
    const auto source_width = env->GetFieldID(type, "sourceWidth", "I");
    const auto source_height = env->GetFieldID(type, "sourceHeight", "I");
    const auto color_space = env->GetFieldID(type, "cameraColorSpace", "I");
    if (!source_width || !source_height || !color_space) return nullptr;
    env->SetIntField(preview, source_width, raw->source_width);
    env->SetIntField(preview, source_height, raw->source_height);
    env->SetIntField(preview, color_space, raw->data().makernotes.common.ColorSpace);
    auto field = env->GetFieldID(type, "pixels", "Ljava/nio/ByteBuffer;");
    if (!field) return nullptr;
    auto buffer = env->GetObjectField(preview, field);
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!dst || env->GetDirectBufferCapacity(buffer) != jlong(raw->width) * raw->height * jlong(sizeof(Rgb))) {
      throw std::runtime_error("Invalid preview buffer");
    }
    for (int y = 0; y < raw->height; ++y) {
      if (cancel.Requested()) throw std::runtime_error("RAW decode cancelled");
      for (int x = 0; x < raw->width; ++x, dst += sizeof(Rgb)) {
        const auto rgb = raw->pixel(x, y);
        std::memcpy(dst, rgb.data(), sizeof(Rgb));
      }
    }
    return preview;
  });
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_openbridge_app_data_RawPreviewExtractor_extractJpegNative(
    JNIEnv* env, jobject, jint descriptor, jintArray color_space, jobject signal) {
  return static_cast<jbyteArray>(WithSignal(env, signal, [&](Cancellation& cancel) -> jobject {
    if (descriptor < 0 || !color_space || env->GetArrayLength(color_space) != 1) throw std::invalid_argument("Invalid RAW preview descriptor");
    constexpr unsigned kMaxBytes = 16 * 1024 * 1024;
    openbridge::RawFileStream input(descriptor);
    auto raw = std::make_unique<LibRaw>();
    raw->set_progress_handler(Progress, &cancel);
    raw->imgdata.rawparams.max_raw_memory_mb = 64;
    Check(raw->open_datastream(&input), "Open RAW preview");
    const jint space = raw->imgdata.makernotes.common.ColorSpace;
    env->SetIntArrayRegion(color_space, 0, 1, &space);
    if (env->ExceptionCheck()) return nullptr;

    // LibRaw identifies RAW subimages, including NEF/ARW full-size JPEGs that ExifInterface does not expose.
    const auto& list = raw->imgdata.thumbs_list;
    std::vector<int> candidates;
    for (int i = 0; i < std::min(list.thumbcount, LIBRAW_THUMBNAIL_MAXCOUNT); ++i) {
      const auto& thumb = list.thumblist[i];
      if (thumb.tformat == LIBRAW_INTERNAL_THUMBNAIL_JPEG && thumb.tlength > 0 && thumb.tlength <= kMaxBytes) candidates.push_back(i);
    }
    std::stable_sort(candidates.begin(), candidates.end(), [&](int left, int right) {
      const auto& a = list.thumblist[left];
      const auto& b = list.thumblist[right];
      const auto a_pixels = uint64_t{a.twidth} * a.theight;
      const auto b_pixels = uint64_t{b.twidth} * b.theight;
      return a_pixels != b_pixels ? a_pixels > b_pixels : a.tlength > b.tlength;
    });
    for (int index : candidates) {
      if (cancel.Requested()) throw std::runtime_error("RAW preview cancelled");
      const int status = raw->unpack_thumb_ex(index);  // Never unpack() or dcraw_process(): no sensor decoding.
      if (LIBRAW_FATAL_ERROR(status)) Check(status, "Read RAW preview");
      const auto& thumb = raw->imgdata.thumbnail;
      if (status != LIBRAW_SUCCESS || thumb.tformat != LIBRAW_THUMBNAIL_JPEG || thumb.tlength > kMaxBytes) continue;
      int error = LIBRAW_SUCCESS;
      std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
          raw->dcraw_make_mem_thumb(&error), LibRaw::dcraw_clear_mem);
      if (error != LIBRAW_SUCCESS || !image || image->type != LIBRAW_IMAGE_JPEG || image->data_size > kMaxBytes) continue;
      if (cancel.Requested()) throw std::runtime_error("RAW preview cancelled");
      return openbridge::jni::ByteArray(env, {image->data, image->data_size});
    }
    return nullptr;
  }));
}

extern "C" JNIEXPORT jobject JNICALL Java_org_openbridge_app_data_RawPhotoDecoder_renderPreviewNative(
    JNIEnv* env, jobject, jobject buffer, jint w, jint h, jfloatArray curve, jbyteArray lut, jfloatArray params, jfloat gain_ev,
    jint encoding, jint output_encoding, jboolean video, jobject signal) {
  return WithSignal(env, signal, [&](Cancellation& cancel) {
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (w <= 0 || h <= 0 || w > 1600 || h > 1600 || !src || env->GetDirectBufferCapacity(buffer) != int64_t{w} * h * sizeof(Rgb)) {
      throw std::invalid_argument("Invalid linear preview buffer");
    }
    return RenderLinear(env, w, h, curve, lut, params, gain_ev, encoding, output_encoding, video, cancel, [&](int x, int y) {
      Rgb rgb;
      std::memcpy(rgb.data(), src + (size_t(y) * w + x) * sizeof(Rgb), sizeof(Rgb));
      return rgb;
    });
  });
}

extern "C" JNIEXPORT jobject JNICALL Java_org_openbridge_app_data_RawPhotoDecoder_decodeNative(
    JNIEnv* env, jobject, jint fd, jint edge, jlong budget, jfloatArray curve, jbyteArray lut, jfloatArray params, jfloat gain_ev,
    jint encoding, jint output_encoding, jboolean video, jobject signal) {
  return WithSignal(env, signal, [&](Cancellation& cancel) {
    auto raw = ReadLinear(fd, edge, budget, openbridge::RawDecodeSize::Full, cancel);
    return RenderLinear(env, raw->width, raw->height, curve, lut, params, gain_ev, encoding, output_encoding, video, cancel,
                        [&](int x, int y) { return raw->pixel(x, y); });
  });
}

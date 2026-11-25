#include <android/bitmap.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>

#include <cerrno>
#include <cstdint>
#include <limits>
#include <new>
#include <span>
#include <stdexcept>
#include <system_error>

#include "jni_support.h"
#include "prooftray/heif_image.h"

namespace {

constexpr off_t kMaxFileBytes = 512LL * 1024 * 1024;
constexpr jsize kMaxPreviewBytes = 16 * 1024 * 1024;

class MappedImage {
 public:
  explicit MappedImage(int fd) {
    struct stat info {};
    if (fstat(fd, &info) != 0) throw std::system_error(errno, std::generic_category(), "Inspect HEIF source");
    if (!S_ISREG(info.st_mode) || info.st_size <= 0 || info.st_size > kMaxFileBytes) throw std::invalid_argument("Invalid HEIF file size");
    size_ = static_cast<size_t>(info.st_size);
    data_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
    if (data_ == MAP_FAILED) {
      if (errno == ENOMEM) throw std::bad_alloc();
      throw std::system_error(errno, std::generic_category(), "Map HEIF source");
    }
  }
  ~MappedImage() { munmap(data_, size_); }
  MappedImage(const MappedImage&) = delete;
  MappedImage& operator=(const MappedImage&) = delete;

  std::span<const uint8_t> bytes() const { return {static_cast<const uint8_t*>(data_), size_}; }

 private:
  void* data_ = MAP_FAILED;
  size_t size_ = 0;
};

void CheckBitmapResult(int result) {
  if (result == ANDROID_BITMAP_RESULT_ALLOCATION_FAILED) throw std::bad_alloc();
  if (result != ANDROID_BITMAP_RESULT_SUCCESS) throw std::invalid_argument("Cannot access HEIF output bitmap");
}

class OutputPixels {
 public:
  OutputPixels(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
    if (!bitmap) throw std::invalid_argument("Missing HEIF output bitmap");
    CheckBitmapResult(AndroidBitmap_getInfo(env, bitmap, &info_));
    if (info_.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info_.width == 0 || info_.height == 0 ||
        info_.width > info_.stride / 4 || info_.height > std::numeric_limits<int>::max() ||
        info_.stride > std::numeric_limits<size_t>::max() / info_.height) {
      throw std::invalid_argument("Expected a software RGBA8 bitmap");
    }
    CheckBitmapResult(AndroidBitmap_lockPixels(env, bitmap, &pixels_));
  }
  ~OutputPixels() { AndroidBitmap_unlockPixels(env_, bitmap_); }
  OutputPixels(const OutputPixels&) = delete;
  OutputPixels& operator=(const OutputPixels&) = delete;

  prooftray::HeifImageSize size() const { return {static_cast<int>(info_.width), static_cast<int>(info_.height)}; }
  size_t stride() const { return info_.stride; }
  std::span<uint8_t> bytes() const { return {static_cast<uint8_t*>(pixels_), stride() * info_.height}; }

 private:
  JNIEnv* env_;
  jobject bitmap_;
  AndroidBitmapInfo info_{};
  void* pixels_ = nullptr;
};

template <typename ReadSize>
jintArray Probe(JNIEnv* env, ReadSize read_size) {
  try {
    const auto size = read_size();
    if (size.width <= 0 || size.height <= 0) return nullptr;
    const jint dimensions[] = {size.width, size.height};
    const auto result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, dimensions);
    return env->ExceptionCheck() ? nullptr : result;
  } catch (const std::bad_alloc&) {
    prooftray::jni::ThrowJava(env, "java/lang/OutOfMemoryError", "Not enough memory to inspect HEIF image");
  } catch (const std::exception&) {
    // The probe also receives unsupported previews. Any pending JVM exception still propagates to the caller.
    return nullptr;
  } catch (...) {
    prooftray::jni::ThrowJava(env, "java/lang/IllegalStateException", "Unexpected HEIF probe failure");
  }
  return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL Java_org_prooftray_app_data_NativeHeif_probeBytes(JNIEnv* env, jobject, jbyteArray bytes) {
  return Probe(env, [&] {
    const auto input = prooftray::jni::ReadBytes(env, bytes, kMaxPreviewBytes, "Invalid HEIF preview size");
    return prooftray::ProbeHeifImage(input);
  });
}

extern "C" JNIEXPORT jintArray JNICALL Java_org_prooftray_app_data_NativeHeif_probeFile(JNIEnv* env, jobject, jint fd) {
  return Probe(env, [&] {
    const MappedImage input(fd);
    return prooftray::ProbeHeifImage(input.bytes());
  });
}

extern "C" JNIEXPORT void JNICALL Java_org_prooftray_app_data_NativeHeif_decodeFile(JNIEnv* env, jobject, jint fd, jobject bitmap,
                                                                                   jboolean allow_thumbnail) {
  try {
    const MappedImage input(fd);
    OutputPixels output(env, bitmap);
    prooftray::DecodeHeifRgba(input.bytes(), output.size(), output.bytes(), output.stride(), allow_thumbnail == JNI_TRUE);
  } catch (const std::bad_alloc&) {
    prooftray::jni::ThrowJava(env, "java/lang/OutOfMemoryError", "Not enough memory to decode HEIF image");
  } catch (const std::exception& error) {
    prooftray::jni::ThrowArgument(env, error.what());
  } catch (...) {
    prooftray::jni::ThrowJava(env, "java/lang/IllegalStateException", "Unexpected HEIF decode failure");
  }
}

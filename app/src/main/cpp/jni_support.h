#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>
#include <stdexcept>
#include <vector>

namespace prooftray::jni {

// Preserve a pending JVM exception, especially allocation failures raised while crossing JNI.
inline void ThrowJava(JNIEnv* env, const char* type, const char* message) {
  if (env->ExceptionCheck()) return;
  const auto exception = env->FindClass(type);
  if (exception) {
    env->ThrowNew(exception, message);
    env->DeleteLocalRef(exception);
  }
}

inline void ThrowArgument(JNIEnv* env, const char* message) { ThrowJava(env, "java/lang/IllegalArgumentException", message); }

inline void CheckJni(JNIEnv* env) {
  if (env->ExceptionCheck()) throw std::runtime_error("JNI access failed");
}

inline std::vector<uint8_t> ReadBytes(JNIEnv* env, jbyteArray array, jsize limit, const char* message) {
  if (!array) throw std::invalid_argument(message);
  const auto size = env->GetArrayLength(array);
  CheckJni(env);
  if (size > limit) throw std::invalid_argument(message);
  std::vector<uint8_t> result(size);
  if (!result.empty()) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(result.data()));
  CheckJni(env);
  return result;
}

inline jbyteArray ByteArray(JNIEnv* env, std::span<const uint8_t> bytes) {
  if (bytes.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) throw std::invalid_argument("JNI byte array is too large");
  const auto size = static_cast<jsize>(bytes.size());
  auto result = env->NewByteArray(size);
  if (result && size != 0) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(bytes.data()));
  return result;
}

}  // namespace prooftray::jni

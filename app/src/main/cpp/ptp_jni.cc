#include <jni.h>

#include "jni_support.h"

#include <limits>
#include <cmath>
#include <stdexcept>
#include <vector>

#include "prooftray/ptp.h"
#include "prooftray/nikon_pairing.h"
#include "prooftray/nikon_location.h"

namespace {

using prooftray::jni::ByteArray;
using prooftray::jni::ThrowArgument;

std::vector<uint8_t> Bytes(JNIEnv* env, jbyteArray array) {
  return prooftray::jni::ReadBytes(env, array, 16 * 1024 * 1024, "Invalid PTP metadata payload");
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_pairReply(
    JNIEnv* env, jobject, jbyteArray start, jbyteArray challenge) {
  try { return ByteArray(env, prooftray::NikonPairingReply(Bytes(env, start), Bytes(env, challenge))); }
  catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_pairingKey(
    JNIEnv* env, jobject, jbyteArray start, jbyteArray challenge, jbyteArray confirmation) {
  try { return ByteArray(env, prooftray::NikonPairingSessionKey(Bytes(env, start), Bytes(env, challenge), Bytes(env, confirmation))); }
  catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_decryptConnectionField(
    JNIEnv* env, jobject, jbyteArray key, jbyteArray ciphertext) {
  try { return ByteArray(env, prooftray::NikonDecryptConnectionField(Bytes(env, key), Bytes(env, ciphertext))); }
  catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_initialize(JNIEnv* env, jobject, jbyteArray guid, jstring name) {
  try {
    if (!name || env->GetStringLength(name) > 127) throw std::invalid_argument("Invalid client name");
    std::u16string text(env->GetStringLength(name), 0);
    env->GetStringRegion(name, 0, static_cast<jsize>(text.size()), reinterpret_cast<jchar*>(text.data()));
    return ByteArray(env, prooftray::ptp::Initialize(Bytes(env, guid), text));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_command(
    JNIEnv* env, jobject, jint operation, jlong transaction, jlongArray parameters, jboolean receive_data, jboolean send_data) {
  try {
    if (operation < 0 || operation > 65535 || transaction < 0 || transaction > UINT32_MAX || !parameters ||
        env->GetArrayLength(parameters) > 5) throw std::invalid_argument("Invalid PTP command");
    std::vector<jlong> input(env->GetArrayLength(parameters));
    if (!input.empty()) env->GetLongArrayRegion(parameters, 0, static_cast<jsize>(input.size()), input.data());
    std::vector<uint32_t> params;
    for (auto value : input) {
      if (value < 0 || value > UINT32_MAX) throw std::invalid_argument("PTP parameter exceeds unsigned 32-bit range");
      params.push_back(static_cast<uint32_t>(value));
    }
    const auto bytes = prooftray::ptp::Command(static_cast<uint16_t>(operation), static_cast<uint32_t>(transaction),
                                               params, receive_data, send_data);
    return ByteArray(env, bytes);
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_location(
    JNIEnv* env, jobject, jdouble latitude, jdouble longitude, jdouble altitude, jlong utc_millis, jint satellites, jboolean enabled) {
  try {
    if (!enabled) return ByteArray(env, prooftray::NikonLocationPacket(std::nullopt));
    prooftray::LocationFix fix{latitude, longitude, std::nullopt, utc_millis, satellites};
    if (!std::isnan(altitude)) fix.altitude_metres = altitude;
    return ByteArray(env, prooftray::NikonLocationPacket(fix));
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_org_prooftray_app_camera_NativePtp_currentTime(
    JNIEnv* env, jobject, jlong utc_millis, jint standard_offset_minutes, jint daylight_minutes) {
  try { return ByteArray(env, prooftray::NikonCurrentTimePacket(utc_millis, standard_offset_minutes, daylight_minutes)); }
  catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jstring JNICALL Java_org_prooftray_app_camera_NativePtp_info(JNIEnv* env, jobject, jbyteArray data, jboolean device) {
  try {
    auto bytes = Bytes(env, data);
    const auto json = device ? prooftray::ptp::DeviceInfoJson(bytes) : prooftray::ptp::ObjectInfoJson(bytes);
    return env->NewStringUTF(json.c_str());
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_org_prooftray_app_camera_NativePtp_handles(JNIEnv* env, jobject, jbyteArray data) {
  try {
    const auto values = prooftray::ptp::Handles(Bytes(env, data));
    std::vector<jlong> longs(values.begin(), values.end());
    auto result = env->NewLongArray(static_cast<jsize>(longs.size()));
    if (result && !longs.empty()) env->SetLongArrayRegion(result, 0, static_cast<jsize>(longs.size()), longs.data());
    return result;
  } catch (const std::exception& error) { ThrowArgument(env, error.what()); return nullptr; }
}

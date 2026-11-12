#include <jni.h>
#include "fingerprint.h"
#include <fstream>
#include <vector>

extern "C" JNIEXPORT jlongArray JNICALL Java_com_echomute_NativeFingerprint_generateFingerprints(JNIEnv* env, jobject, jstring path) {
    const char* name = env->GetStringUTFChars(path, nullptr);
    std::ifstream file(name, std::ios::binary | std::ios::ate);
    if (!file) {
        env->ReleaseStringUTFChars(path, name);
        env->ThrowNew(env->FindClass("java/io/IOException"), "Cannot open PCM file");
        return nullptr;
    }
    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);
    std::vector<char> bytes(size);
    if (size > 0) file.read(bytes.data(), size);
    env->ReleaseStringUTFChars(path, name);

    size_t sampleCount = bytes.size() / 2;
    std::vector<int16_t> samples(sampleCount);
    for (size_t i = 0; i < sampleCount; ++i) {
        samples[i] = static_cast<int16_t>(static_cast<uint8_t>(bytes[i * 2]) | (static_cast<uint8_t>(bytes[i * 2 + 1]) << 8));
    }
    auto points = fingerprint(samples.data(), samples.size());
    std::vector<jlong> packed;
    packed.reserve(points.size());
    for (auto p : points) packed.push_back((static_cast<jlong>(p.hash) << 32) | p.time_ms);
    jlongArray result = env->NewLongArray(packed.size());
    if (result) env->SetLongArrayRegion(result, 0, packed.size(), packed.data());
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_echomute_NativeFingerprint_generateFingerprintsFromBytes(JNIEnv* env, jobject, jbyteArray bytesArray, jint length) {
    if (!bytesArray || length <= 0) return env->NewLongArray(0);
    jbyte* data = env->GetByteArrayElements(bytesArray, nullptr);
    if (!data) return env->NewLongArray(0);

    size_t sampleCount = static_cast<size_t>(length) / 2;
    std::vector<int16_t> samples(sampleCount);
    for (size_t i = 0; i < sampleCount; ++i) {
        samples[i] = static_cast<int16_t>(static_cast<uint8_t>(data[i * 2]) | (static_cast<uint8_t>(data[i * 2 + 1]) << 8));
    }
    env->ReleaseByteArrayElements(bytesArray, data, JNI_ABORT);

    auto points = fingerprint(samples.data(), samples.size());
    std::vector<jlong> packed;
    packed.reserve(points.size());
    for (auto p : points) packed.push_back((static_cast<jlong>(p.hash) << 32) | p.time_ms);
    jlongArray result = env->NewLongArray(packed.size());
    if (result) env->SetLongArrayRegion(result, 0, packed.size(), packed.data());
    return result;
}

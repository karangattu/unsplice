#include <jni.h>
#include "fingerprint.h"
#include <fstream>
#include <iterator>
#include <vector>
extern "C" JNIEXPORT jlongArray JNICALL Java_com_echomute_NativeFingerprint_generateFingerprints(JNIEnv* env, jobject, jstring path) {
    const char* name = env->GetStringUTFChars(path, nullptr);
    std::ifstream file(name, std::ios::binary);
    env->ReleaseStringUTFChars(path, name);
    if (!file) { env->ThrowNew(env->FindClass("java/io/IOException"), "Cannot open PCM file"); return nullptr; }
    std::vector<char> bytes((std::istreambuf_iterator<char>(file)), {});
    std::vector<int16_t> samples(bytes.size() / 2);
    for (size_t i = 0; i < samples.size(); ++i) samples[i] = static_cast<int16_t>(static_cast<uint8_t>(bytes[i * 2]) | (static_cast<uint8_t>(bytes[i * 2 + 1]) << 8));
    auto points = fingerprint(samples);
    std::vector<jlong> packed; packed.reserve(points.size());
    for (auto p : points) packed.push_back((static_cast<jlong>(p.hash) << 32) | p.time_ms);
    jlongArray result = env->NewLongArray(packed.size());
    if (result) env->SetLongArrayRegion(result, 0, packed.size(), packed.data());
    return result;
}

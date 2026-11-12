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
    jsize arrayLen = env->GetArrayLength(bytesArray);
    if (length > arrayLen) return env->NewLongArray(0);
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

extern "C" JNIEXPORT jlong JNICALL Java_com_echomute_NativeFingerprint_createAnalyzer(
    JNIEnv*, jobject, jint sampleRate, jint channels, jint targetRate, jint envelopeRate, jboolean enableFingerprint
) {
    auto* analyzer = new StreamingAudioAnalyzer(sampleRate, channels, targetRate, envelopeRate, enableFingerprint == JNI_TRUE);
    return reinterpret_cast<jlong>(analyzer);
}

extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeFingerprint_configureAnalyzer(
    JNIEnv*, jobject, jlong handle, jint sampleRate, jint channels
) {
    if (!handle) return;
    reinterpret_cast<StreamingAudioAnalyzer*>(handle)->configure(sampleRate, channels);
}

extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeFingerprint_feedPcmBuffer(
    JNIEnv* env, jobject, jlong handle, jobject buffer, jint offset, jint size
) {
    if (!handle || !buffer || size <= 0 || offset < 0) return;
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base) return;
    jlong cap = env->GetDirectBufferCapacity(buffer);
    if (cap <= 0 || static_cast<jlong>(offset) + size > cap) return;
    reinterpret_cast<StreamingAudioAnalyzer*>(handle)->feedPcmBytes(base + offset, static_cast<size_t>(size));
}

extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeFingerprint_feedPcmBytes(
    JNIEnv* env, jobject, jlong handle, jbyteArray bytesArray, jint offset, jint size
) {
    if (!handle || !bytesArray || size <= 0 || offset < 0) return;
    jsize arrayLen = env->GetArrayLength(bytesArray);
    if (static_cast<jlong>(offset) + size > arrayLen) return;
    jbyte* data = env->GetByteArrayElements(bytesArray, nullptr);
    if (!data) return;
    reinterpret_cast<StreamingAudioAnalyzer*>(handle)->feedPcmBytes(
        reinterpret_cast<const uint8_t*>(data) + offset,
        static_cast<size_t>(size)
    );
    env->ReleaseByteArrayElements(bytesArray, data, JNI_ABORT);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_echomute_NativeFingerprint_finishAnalyzer(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return env->NewLongArray(0);
    auto* analyzer = reinterpret_cast<StreamingAudioAnalyzer*>(handle);
    analyzer->finish();
    const auto& fp = analyzer->fingerprints();
    jlongArray result = env->NewLongArray(static_cast<jsize>(fp.size()));
    if (result && !fp.empty()) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(fp.size()), reinterpret_cast<const jlong*>(fp.data()));
    }
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL Java_com_echomute_NativeFingerprint_analyzerEnvelope(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return env->NewIntArray(0);
    auto* analyzer = reinterpret_cast<StreamingAudioAnalyzer*>(handle);
    const auto& ev = analyzer->envelope();
    jintArray result = env->NewIntArray(static_cast<jsize>(ev.size()));
    if (result && !ev.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(ev.size()), reinterpret_cast<const jint*>(ev.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeFingerprint_destroyAnalyzer(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<StreamingAudioAnalyzer*>(handle);
}

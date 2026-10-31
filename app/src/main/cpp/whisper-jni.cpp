#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

static void silent_log(ggml_log_level, const char*, void*) {}

extern "C" JNIEXPORT jlong JNICALL Java_com_echomute_NativeWhisper_load(JNIEnv* env, jobject, jstring path) {
    whisper_log_set(silent_log, nullptr);
    const char* name = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(name, params);
    env->ReleaseStringUTFChars(path, name);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeWhisper_release(JNIEnv*, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context*>(handle));
}

// Transcribes 16 kHz mono float samples. Returns UTF-8 lines of "startMs\tendMs\ttext", one per word, so that
// token boundaries that are not valid UTF-8 are decoded leniently on the Kotlin side instead of aborting the JVM.
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_echomute_NativeWhisper_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint threads) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) { env->ThrowNew(env->FindClass("java/io/IOException"), "Model not loaded"); return nullptr; }
    jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(count);
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.no_context = true; // the previous window's text as a prompt makes repetition loops more likely
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    if (whisper_full(ctx, params, pcm.data(), count) != 0) {
        env->ThrowNew(env->FindClass("java/io/IOException"), "Transcription failed");
        return nullptr;
    }
    std::string out;
    int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (!text || !*text) continue;
        out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10);
        out += '\t';
        out += std::to_string(whisper_full_get_segment_t1(ctx, i) * 10);
        out += '\t';
        for (const char* p = text; *p; ++p) out += (*p == '\t' || *p == '\n') ? ' ' : *p;
        out += '\n';
    }
    jbyteArray result = env->NewByteArray(out.size());
    if (result) env->SetByteArrayRegion(result, 0, out.size(), reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

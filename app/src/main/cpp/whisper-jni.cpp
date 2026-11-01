#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <dlfcn.h>
#include <link.h>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>
#include "ggml-backend.h"
#include "whisper.h"

namespace {

struct Session {
    whisper_context* ctx = nullptr;
    std::string vadModel;
    std::atomic<bool> abort{false};
    std::atomic<int> progress{0};
};

void silent_log(ggml_log_level, const char*, void*) {}

std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throw_io(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }

// Loads the ggml CPU backend once: with several variants packaged, ggml picks the best one the CPU supports.
std::once_flag backends_once;
void load_backends(const std::string& dir) {
    std::call_once(backends_once, [&] {
        whisper_log_set(silent_log, nullptr);
        ggml_log_set(silent_log, nullptr);
        ggml_backend_load_all_from_path(dir.c_str());
        __android_log_print(ANDROID_LOG_INFO, "Transcript", "ggml backends loaded from %s: %zu registries, %zu devices", dir.c_str(), ggml_backend_reg_count(), ggml_backend_dev_count());
    });
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_echomute_NativeWhisper_load(JNIEnv* env, jobject, jstring modelPath, jstring vadModelPath, jstring backendDir) {
    load_backends(utf8(env, backendDir));
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(utf8(env, modelPath).c_str(), params);
    if (!ctx) return 0;
    auto* session = new Session();
    session->ctx = ctx;
    session->vadModel = utf8(env, vadModelPath);
    return reinterpret_cast<jlong>(session);
}

/** File name of the CPU backend library ggml loaded, such as "libggml-cpu-android_armv8.2_1.so"; "none" if none. */
extern "C" JNIEXPORT jstring JNICALL Java_com_echomute_NativeWhisper_backendName(JNIEnv* env, jobject) {
    std::string found = "none";
    dl_iterate_phdr([](dl_phdr_info* info, size_t, void* data) {
        std::string name = info->dlpi_name ? info->dlpi_name : "";
        size_t slash = name.find_last_of('/');
        std::string base = slash == std::string::npos ? name : name.substr(slash + 1);
        if (base.rfind("libggml-cpu", 0) == 0) *static_cast<std::string*>(data) = base;
        return 0;
    }, &found);
    return env->NewStringUTF(found.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeWhisper_release(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return;
    whisper_free(session->ctx);
    delete session;
}

/** Makes the running or next transcribe call on this session stop as soon as possible. */
extern "C" JNIEXPORT void JNICALL Java_com_echomute_NativeWhisper_cancel(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session) session->abort.store(true);
}

extern "C" JNIEXPORT jint JNICALL Java_com_echomute_NativeWhisper_progress(JNIEnv*, jobject, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    return session ? session->progress.load() : 0;
}

// Transcribes [count] 16 kHz mono 16-bit samples starting [offset] samples into the PCM file. Returns UTF-8
// lines of "startMs\tendMs\ttext", one per word, relative to the window; token boundaries that are not valid
// UTF-8 are decoded leniently on the Kotlin side instead of aborting the JVM.
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_echomute_NativeWhisper_transcribe(JNIEnv* env, jobject, jlong handle, jstring pcmPath, jlong offset, jint count, jint threads, jint audioCtx) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) { throw_io(env, "Model not loaded"); return nullptr; }
    if (session->abort.load()) { throw_io(env, "Transcription cancelled"); return nullptr; }
    session->progress.store(0);

    std::vector<float> pcm(count);
    {
        FILE* file = fopen(utf8(env, pcmPath).c_str(), "rb");
        if (!file) { throw_io(env, "Cannot open PCM file"); return nullptr; }
        fseek(file, offset * 2, SEEK_SET);
        std::vector<int16_t> samples(count);
        size_t read = fread(samples.data(), 2, count, file);
        fclose(file);
        pcm.resize(read);
        for (size_t i = 0; i < read; ++i) pcm[i] = samples[i] / 32768.0f;
    }

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
    params.audio_ctx = audioCtx;
    params.temperature_inc = 0.0f; // no re-decoding of hard sections at higher temperatures
    params.abort_callback = [](void* data) { return static_cast<Session*>(data)->abort.load(); };
    params.abort_callback_user_data = session;
    params.progress_callback = [](whisper_context*, whisper_state*, int progress, void* data) {
        static_cast<Session*>(data)->progress.store(progress);
    };
    params.progress_callback_user_data = session;
    if (!session->vadModel.empty()) {
        params.vad = true;
        params.vad_model_path = session->vadModel.c_str();
        params.vad_params = whisper_vad_default_params();
    }
    if (whisper_full(session->ctx, params, pcm.data(), (int) pcm.size()) != 0) {
        throw_io(env, session->abort.load() ? "Transcription cancelled" : "Transcription failed");
        return nullptr;
    }
    std::string out;
    int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) {
        const char* text = whisper_full_get_segment_text(session->ctx, i);
        if (!text || !*text) continue;
        out += std::to_string(whisper_full_get_segment_t0(session->ctx, i) * 10);
        out += '\t';
        out += std::to_string(whisper_full_get_segment_t1(session->ctx, i) * 10);
        out += '\t';
        for (const char* p = text; *p; ++p) out += (*p == '\t' || *p == '\n') ? ' ' : *p;
        out += '\n';
    }
    jbyteArray result = env->NewByteArray(out.size());
    if (result) env->SetByteArrayRegion(result, 0, out.size(), reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

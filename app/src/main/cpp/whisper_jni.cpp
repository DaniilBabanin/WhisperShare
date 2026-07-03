#include <jni.h>
#include <string>
#include <vector>
#include <exception>
#include <atomic>
#include <mutex>
#include <stdlib.h>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Last native error stash — Kotlin polls this after any failure.
// Most useful case: Vulkan vk::DeviceLostError (Mali drivers).
// Written by nativeInitContext/nativeTranscribe, read by nativeLastError
// from other threads — guard all access with a mutex.
std::mutex g_last_error_mutex;
std::string g_last_error;

void set_last_error(const std::string &msg) {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    g_last_error = msg;
}

std::string get_last_error() {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    return g_last_error;
}

// Abort flag for nativeTranscribe. Engine is a singleton with one context,
// so one process-global flag suffices. Set by nativeRequestAbort (any
// thread), polled by whisper_full via params.abort_callback, reset at the
// start of each nativeTranscribe.
std::atomic<bool> g_abort_requested{false};

// Language id detected by the last *completed* transcription, -1 when
// unavailable. Reset at the start of each nativeTranscribe (like the abort
// flag), written after a successful whisper_full, read by
// nativeDetectedLanguage — possibly from another thread, hence atomic.
std::atomic<int> g_detected_lang_id{-1};

struct CallbackCtx {
    JNIEnv  *env;
    jobject  callback;
    jmethodID seg_method;
    jmethodID prog_method;
};

void cb_new_segment(struct whisper_context *ctx, struct whisper_state * /*state*/,
                    int n_new, void *user_data) {
    auto *cbctx = static_cast<CallbackCtx *>(user_data);
    if (cbctx == nullptr || cbctx->callback == nullptr) return;
    int n_segments = whisper_full_n_segments(ctx);
    int start = n_segments - n_new;
    if (start < 0) start = 0;
    for (int i = start; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;
        jstring jtext = cbctx->env->NewStringUTF(text);
        cbctx->env->CallVoidMethod(cbctx->callback, cbctx->seg_method, jtext);
        if (cbctx->env->ExceptionCheck()) {
            cbctx->env->ExceptionDescribe();
            cbctx->env->ExceptionClear();
        }
        cbctx->env->DeleteLocalRef(jtext);
    }
}

void cb_progress(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/,
                 int progress, void *user_data) {
    auto *cbctx = static_cast<CallbackCtx *>(user_data);
    if (cbctx == nullptr || cbctx->callback == nullptr) return;
    cbctx->env->CallVoidMethod(cbctx->callback, cbctx->prog_method, static_cast<jint>(progress));
    if (cbctx->env->ExceptionCheck()) {
        cbctx->env->ExceptionDescribe();
        cbctx->env->ExceptionClear();
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_whispershare_WhisperEngine_nativeInitContext(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath, jboolean useGpu) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        // OOM: GetStringUTFChars returned null and may have thrown.
        if (env->ExceptionCheck()) env->ExceptionClear();
        set_last_error("init failed: GetStringUTFChars returned null (out of memory)");
        LOGE("GetStringUTFChars returned null");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
#ifdef WHISPERSHARE_VULKAN
    cparams.use_gpu = useGpu;
    if (useGpu) {
        // Mali-G715 / Pixel 9 hits ggml_abort at ggml-vulkan.cpp:6452
        // (descriptor_set_idx exceeds descriptor_sets.size()) when the fused
        // add/multi_add code paths are active. Disable them so pre-flight and
        // runtime descriptor-set counts agree. Pass-through if user explicitly
        // sets these env vars themselves (overwrite=0).
        setenv("GGML_VK_DISABLE_FUSION",     "1", 0);
        setenv("GGML_VK_DISABLE_MULTI_ADD",  "1", 0);
    }
#else
    cparams.use_gpu = false;
#endif
    cparams.flash_attn = false;

    LOGI("Loading model: %s (gpu=%d)", path, cparams.use_gpu);
    whisper_context *ctx = nullptr;
    try {
        ctx = whisper_init_from_file_with_params(path, cparams);
    } catch (const std::exception &e) {
        std::string err = std::string("init failed: ") + e.what();
        set_last_error(err);
        LOGE("%s", err.c_str());
    } catch (...) {
        set_last_error("init failed: unknown native exception");
        LOGE("init failed: unknown native exception");
    }
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    set_last_error("");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_io_whispershare_WhisperEngine_nativeFreeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) return;
    try {
        whisper_free(ctx);
    } catch (const std::exception &e) {
        LOGW("whisper_free threw: %s", e.what());
    } catch (...) {
        LOGW("whisper_free threw unknown exception");
    }
}

JNIEXPORT jstring JNICALL
Java_io_whispershare_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/,
        jlong ctxPtr,
        jfloatArray pcm,
        jstring language,
        jboolean translate,
        jint nThreads,
        jboolean useBeam,
        jobject callback) {

    // New transcription run: clear any stale abort request and the
    // detected language from the previous run.
    g_abort_requested.store(false, std::memory_order_relaxed);
    g_detected_lang_id.store(-1, std::memory_order_relaxed);

    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        // OOM: GetFloatArrayElements returned null and may have thrown.
        if (env->ExceptionCheck()) env->ExceptionClear();
        set_last_error("GetFloatArrayElements returned null (out of memory)");
        LOGE("GetFloatArrayElements returned null");
        return env->NewStringUTF("");
    }

    whisper_full_params params = whisper_full_default_params(
            useBeam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    params.print_progress  = false;
    params.print_special   = false;
    params.print_realtime  = false;
    params.print_timestamps = false;
    params.translate       = translate;
    params.n_threads       = nThreads;
    params.suppress_blank  = true;
    params.no_context      = true;
    params.single_segment  = false;
    if (useBeam) {
        params.beam_search.beam_size = 5;
    }

    // Poll the abort flag so Kotlin can cancel a running transcription.
    params.abort_callback = [](void * /*user_data*/) -> bool {
        return g_abort_requested.load(std::memory_order_relaxed);
    };
    params.abort_callback_user_data = nullptr;

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        if (lang == nullptr && env->ExceptionCheck()) env->ExceptionClear();
        if (lang && lang[0] != '\0') params.language = lang;
    }

    CallbackCtx cbctx{env, callback, nullptr, nullptr};
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        cbctx.seg_method  = env->GetMethodID(cls, "jniSegment",  "(Ljava/lang/String;)V");
        // A failed lookup leaves NoSuchMethodError pending; clear it before
        // making further JNI calls (running with a pending exception is UB).
        if (cbctx.seg_method == nullptr && env->ExceptionCheck()) env->ExceptionClear();
        cbctx.prog_method = env->GetMethodID(cls, "jniProgress", "(I)V");
        if (cbctx.prog_method == nullptr && env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
        if (cbctx.seg_method != nullptr) {
            params.new_segment_callback           = cb_new_segment;
            params.new_segment_callback_user_data = &cbctx;
        }
        if (cbctx.prog_method != nullptr) {
            params.progress_callback           = cb_progress;
            params.progress_callback_user_data = &cbctx;
        }
    }

    int rc = -1;
    bool native_threw = false;
    try {
        rc = whisper_full(ctx, params, samples, n);
    } catch (const std::exception &e) {
        native_threw = true;
        std::string err = std::string("native exception: ") + e.what();
        set_last_error(err);
        LOGE("%s", err.c_str());
    } catch (...) {
        native_threw = true;
        set_last_error("native exception: unknown");
        LOGE("native exception: unknown");
    }

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (lang) env->ReleaseStringUTFChars(language, lang);

    if (native_threw) {
        return env->NewStringUTF("");
    }
    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        set_last_error("whisper_full returned " + std::to_string(rc));
        return env->NewStringUTF("");
    }
    set_last_error("");
    g_detected_lang_id.store(whisper_full_lang_id(ctx), std::memory_order_relaxed);

    std::string out;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) out += text;
    }
    size_t start = out.find_first_not_of(" \t\n");
    if (start != std::string::npos) out = out.substr(start);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_whispershare_WhisperEngine_nativeBackendInfo(
        JNIEnv *env, jobject /*thiz*/) {
    std::string info;
#ifdef WHISPERSHARE_VULKAN
    info = "vulkan";
#else
    info = "cpu";
#endif
    return env->NewStringUTF(info.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_whispershare_WhisperEngine_nativeLastError(
        JNIEnv *env, jobject /*thiz*/) {
    std::string err = get_last_error();
    return env->NewStringUTF(err.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_whispershare_WhisperEngine_nativeDetectedLanguage(
        JNIEnv *env, jobject /*thiz*/, jlong /*ctxPtr*/) {
    // ISO-639-1 code ("de") of the language detected by the last completed
    // transcription, "" when unavailable. whisper_lang_str is a static table
    // lookup, so no context access is needed here.
    int id = g_detected_lang_id.load(std::memory_order_relaxed);
    if (id < 0) return env->NewStringUTF("");
    const char *code = whisper_lang_str(id);
    return env->NewStringUTF(code ? code : "");
}

JNIEXPORT void JNICALL
Java_io_whispershare_WhisperEngine_nativeRequestAbort(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong /*ctxPtr*/) {
    // Engine holds one context; a single process-global flag is enough.
    // Picked up by the abort_callback wired in nativeTranscribe.
    g_abort_requested.store(true, std::memory_order_relaxed);
}

} // extern "C"

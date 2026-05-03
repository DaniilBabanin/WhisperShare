#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct CallbackCtx {
    JNIEnv  *env;
    jobject  callback;        // io.whispershare.TranscribeCallback (local ref, valid for the call)
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

    whisper_context_params cparams = whisper_context_default_params();
#ifdef WHISPERSHARE_VULKAN
    cparams.use_gpu = useGpu;
#else
    cparams.use_gpu = false;
#endif
    cparams.flash_attn = false;

    LOGI("Loading model: %s (gpu=%d)", path, cparams.use_gpu);
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_io_whispershare_WhisperEngine_nativeFreeContext(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx) whisper_free(ctx);
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

    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);

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

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        if (lang && lang[0] != '\0') params.language = lang;
    }

    CallbackCtx cbctx{env, callback, nullptr, nullptr};
    if (callback != nullptr) {
        jclass cls = env->GetObjectClass(callback);
        cbctx.seg_method  = env->GetMethodID(cls, "jniSegment",  "(Ljava/lang/String;)V");
        cbctx.prog_method = env->GetMethodID(cls, "jniProgress", "(I)V");
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

    int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (lang) env->ReleaseStringUTFChars(language, lang);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) out += text;
    }

    // Trim leading whitespace whisper sometimes emits
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

} // extern "C"

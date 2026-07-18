package io.whispershare

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("whispershare", Context.MODE_PRIVATE)

    /**
     * Stable id of the selected model: "builtin:BASE_Q5" or "custom:<filename>".
     * Migrates legacy values that stored the bare enum name (e.g. "BASE_Q5").
     */
    var selectedModelId: String
        get() {
            val raw = prefs.getString(KEY_MODEL, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
            return when {
                // A selected built-in that no longer exists (e.g. the removed
                // large-v3-turbo) falls back to the default instead of erroring.
                raw.startsWith("builtin:") ->
                    if (runCatching { ModelManager.BuiltInModel.valueOf(raw.removePrefix("builtin:")) }.isSuccess) raw
                    else DEFAULT_MODEL_ID
                raw.startsWith("custom:") -> raw
                runCatching { ModelManager.BuiltInModel.valueOf(raw) }.isSuccess -> "builtin:$raw"
                else -> DEFAULT_MODEL_ID
            }
        }
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    /** Empty = auto-detect. Otherwise ISO-639-1 code like "en", "de". */
    var language: String
        get() = prefs.getString(KEY_LANG, "")!!
        set(value) = prefs.edit { putString(KEY_LANG, value) }

    var translateToEnglish: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE, false)
        set(value) = prefs.edit { putBoolean(KEY_TRANSLATE, value) }

    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_GPU, DEFAULT_USE_GPU)
        set(value) = prefs.edit { putBoolean(KEY_GPU, value) }

    /** 0 = "auto" (cores/2, min 2). Otherwise honoured verbatim. */
    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 0)
        set(value) = prefs.edit { putInt(KEY_THREADS, value.coerceIn(0, 16)) }

    /** Beam search instead of greedy: slower but more accurate on noisy/accented audio. */
    var highQuality: Boolean
        get() = prefs.getBoolean(KEY_HIGH_QUALITY, false)
        set(value) = prefs.edit { putBoolean(KEY_HIGH_QUALITY, value) }

    /** Silero VAD preprocessing: transcribe only detected speech segments. Needs the VAD model download. */
    var vadEnabled: Boolean
        get() = prefs.getBoolean(KEY_VAD_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_VAD_ENABLED, value) }

    /**
     * Stream text in while transcribing by splitting 16-90 s clips at silence
     * boundaries. Off by default: each extra chunk pays whisper's fixed
     * per-window encoder cost, so it trades a little total speed for liveness.
     */
    var liveText: Boolean
        get() = prefs.getBoolean(KEY_LIVE_TEXT, false)
        set(value) = prefs.edit { putBoolean(KEY_LIVE_TEXT, value) }

    /** Reveals advanced controls (thread slider, benchmark). Toggled by tapping the home tip. */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEV_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DEV_MODE, value) }

    /** Developer-only escape hatch: bypass SHA-256 check on model downloads. */
    var skipModelVerification: Boolean
        get() = prefs.getBoolean(KEY_SKIP_VERIFY, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_VERIFY, value) }

    /** One-shot flag: set when a previous GPU run crashed; cleared once observed. */
    var gpuCrashedNotice: Boolean
        get() = prefs.getBoolean(KEY_GPU_CRASHED, false)
        set(value) = prefs.edit { putBoolean(KEY_GPU_CRASHED, value) }

    /** Called on app start when the GPU crumb is found. Force CPU and arm the notice. */
    fun onGpuCrashed() {
        // Use commit() so it's durable before the user retries anything.
        prefs.edit().putBoolean(KEY_GPU, false).putBoolean(KEY_GPU_CRASHED, true).commit()
    }

    fun resolvedThreads(): Int =
        if (threads > 0) threads
        else (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "language"
        private const val KEY_TRANSLATE = "translate"
        private const val KEY_GPU = "use_gpu"
        private const val KEY_THREADS = "threads"
        private const val KEY_HIGH_QUALITY = "high_quality"
        private const val KEY_VAD_ENABLED = "vad_enabled"
        private const val KEY_LIVE_TEXT = "live_text"
        private const val KEY_DEV_MODE = "developer_mode"
        private const val KEY_SKIP_VERIFY = "skip_model_verification"
        private const val KEY_GPU_CRASHED = "gpu_crashed_notice"

        private const val DEFAULT_MODEL_ID = "builtin:BASE_Q5"

        // Off by default — Vulkan support varies by GPU (Mali on Pixel 9 has been
        // known to abort mid-inference). Power users can enable it from settings.
        private const val DEFAULT_USE_GPU = false
    }
}

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
                raw.startsWith("builtin:") || raw.startsWith("custom:") -> raw
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

    /** Reveals advanced controls (thread slider, benchmark). Toggled by tapping the home tip. */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEV_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_DEV_MODE, value) }

    /** Developer-only escape hatch: bypass SHA-256 check on model downloads. */
    var skipModelVerification: Boolean
        get() = prefs.getBoolean(KEY_SKIP_VERIFY, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_VERIFY, value) }

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
        private const val KEY_DEV_MODE = "developer_mode"
        private const val KEY_SKIP_VERIFY = "skip_model_verification"

        private const val DEFAULT_MODEL_ID = "builtin:BASE_Q5"

        // Default to true. If the build doesn't include Vulkan, the JNI side
        // ignores the flag and runs on CPU regardless — see whisper_jni.cpp.
        private const val DEFAULT_USE_GPU = true
    }
}

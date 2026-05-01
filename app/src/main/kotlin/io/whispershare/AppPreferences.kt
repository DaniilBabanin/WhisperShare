package io.whispershare

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("whispershare", Context.MODE_PRIVATE)

    var selectedModel: ModelManager.Model
        get() {
            val name = prefs.getString(KEY_MODEL, ModelManager.Model.BASE_Q5.name)
            return runCatching { ModelManager.Model.valueOf(name!!) }
                .getOrDefault(ModelManager.Model.BASE_Q5)
        }
        set(value) = prefs.edit { putString(KEY_MODEL, value.name) }

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

    companion object {
        private const val KEY_MODEL = "model"
        private const val KEY_LANG = "language"
        private const val KEY_TRANSLATE = "translate"
        private const val KEY_GPU = "use_gpu"

        // Default to true. If the build doesn't include Vulkan, the JNI side
        // ignores the flag and runs on CPU regardless — see whisper_jni.cpp.
        private const val DEFAULT_USE_GPU = true
    }
}

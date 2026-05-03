package io.whispershare

import android.app.Application
import android.util.Log
import java.io.File

class WhisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Eagerly load native lib so the share path is fast.
        runCatching { System.loadLibrary("whispershare") }

        // Crash-crumb: if a previous GPU run aborted the process (e.g.
        // ggml_abort or vk::DeviceLostError on Mali), force CPU on next launch
        // so the user doesn't loop into the same crash.
        val crumb = File(filesDir, GPU_CRUMB_FILE)
        if (crumb.exists()) {
            Log.w("WhisperApp", "GPU crash-crumb found from previous run — forcing CPU")
            AppPreferences(this).onGpuCrashed()
            crumb.delete()
        }
    }

    companion object {
        const val GPU_CRUMB_FILE = ".gpu_in_flight"
    }
}

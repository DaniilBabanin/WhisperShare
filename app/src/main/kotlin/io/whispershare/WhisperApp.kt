package io.whispershare

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.util.Log
import java.io.File

class WhisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Eagerly load native lib so the share path is fast.
        runCatching { System.loadLibrary("whispershare") }

        // Crash-crumb: if a previous GPU run aborted the process (e.g.
        // ggml_abort or vk::DeviceLostError on Mali), force CPU on next launch
        // so the user doesn't loop into the same crash. With the pipeline in a
        // foreground service the process can also die for benign reasons
        // (LMK, force-stop, task swipe) while the crumb exists — check the
        // recorded exit reason so those don't false-positive as GPU crashes.
        val crumb = File(filesDir, GPU_CRUMB_FILE)
        if (crumb.exists()) {
            crumb.delete()
            if (lastExitWasNativeCrash()) {
                Log.w("WhisperApp", "GPU crash-crumb found from previous run — forcing CPU")
                AppPreferences(this).onGpuCrashed()
            } else {
                Log.i("WhisperApp", "GPU crumb found but last exit was not a native crash (system kill?) — ignoring")
            }
        }
    }

    private fun lastExitWasNativeCrash(): Boolean = runCatching {
        val am = getSystemService(ActivityManager::class.java)
        val exits = am.getHistoricalProcessExitReasons(packageName, 0, 1)
        exits.firstOrNull()?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
    }.getOrDefault(true) // can't tell → keep the old conservative behavior

    companion object {
        const val GPU_CRUMB_FILE = ".gpu_in_flight"
    }
}

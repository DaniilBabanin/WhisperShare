package io.whispershare

import android.app.Application

class WhisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Eagerly load native lib so the share path is fast.
        runCatching { System.loadLibrary("whispershare") }
    }
}

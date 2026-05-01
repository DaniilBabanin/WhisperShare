package io.whispershare

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin Kotlin wrapper over the whisper.cpp JNI bridge.
 *
 * Lifecycle: load() once, then transcribe() many times, then release() at process death.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"

    private val ctxPtr = AtomicLong(0L)
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedWithGpu: Boolean = false

    val isLoaded: Boolean get() = ctxPtr.get() != 0L
    val activeBackend: String get() = nativeBackendInfo()
    val activeModelPath: String? get() = loadedPath

    suspend fun load(modelFile: File, useGpu: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Model not found: ${modelFile.absolutePath}"))
        }
        // Already loaded with same parameters? No-op.
        if (isLoaded && loadedPath == modelFile.absolutePath && loadedWithGpu == useGpu) {
            return@withContext Result.success(Unit)
        }
        // Different model or backend toggle — release first.
        release()
        val ptr = nativeInitContext(modelFile.absolutePath, useGpu)
        if (ptr == 0L) {
            return@withContext Result.failure(IllegalStateException("whisper_init_from_file returned null"))
        }
        ctxPtr.set(ptr)
        loadedPath = modelFile.absolutePath
        loadedWithGpu = useGpu
        Log.i(TAG, "Loaded ${modelFile.name} (gpu=$useGpu, backend=${nativeBackendInfo()})")
        Result.success(Unit)
    }

    fun release() {
        val ptr = ctxPtr.getAndSet(0L)
        if (ptr != 0L) nativeFreeContext(ptr)
        loadedPath = null
    }

    /**
     * @param pcm16k mono float PCM at 16 kHz, range [-1.0, 1.0]
     * @param language ISO-639-1 like "en", "de"; null = auto-detect
     * @param translate true = translate non-English to English
     * @param threads CPU threads to use (ignored on GPU path)
     */
    suspend fun transcribe(
        pcm16k: FloatArray,
        language: String? = null,
        translate: Boolean = false,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    ): String = withContext(Dispatchers.Default) {
        val ptr = ctxPtr.get()
        check(ptr != 0L) { "WhisperEngine not loaded — call load() first" }
        nativeTranscribe(ptr, pcm16k, language ?: "", translate, threads)
    }

    // ---------- native ----------
    private external fun nativeInitContext(modelPath: String, useGpu: Boolean): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeTranscribe(
        ctxPtr: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        nThreads: Int
    ): String
    private external fun nativeBackendInfo(): String
}

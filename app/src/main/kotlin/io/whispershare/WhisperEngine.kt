package io.whispershare

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * Serializes load/transcribe/release. Without it, release() (or load()'s
     * internal release) can free the native context while whisper_full is still
     * running on it — a use-after-free that also bites across activities
     * (benchmark and transcribe share this singleton).
     */
    private val mutex = Mutex()

    val isLoaded: Boolean get() = ctxPtr.get() != 0L
    /** "vulkan" if the binary was compiled with Vulkan support, else "cpu". */
    val compiledBackend: String get() = nativeBackendInfo()
    /** Reflects what actually ran the last load: "gpu" only if Vulkan compiled AND load(useGpu=true). */
    val activeBackend: String get() =
        if (compiledBackend == "vulkan" && loadedWithGpu) "gpu" else "cpu"
    val activeModelPath: String? get() = loadedPath
    /** Last native error message, "" if none. Populated by failed init/transcribe. */
    fun lastError(): String = nativeLastError()

    suspend fun load(modelFile: File, useGpu: Boolean): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!modelFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Model not found: ${modelFile.absolutePath}"))
            }
            // Already loaded with same parameters? No-op.
            if (isLoaded && loadedPath == modelFile.absolutePath && loadedWithGpu == useGpu) {
                return@withContext Result.success(Unit)
            }
            // Different model or backend toggle — release first.
            releaseLocked()
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
    }

    suspend fun release() = mutex.withLock { releaseLocked() }

    /** Must only be called while [mutex] is held. */
    private fun releaseLocked() {
        val ptr = ctxPtr.getAndSet(0L)
        if (ptr != 0L) nativeFreeContext(ptr)
        loadedPath = null
        loadedWithGpu = false
    }

    /**
     * @param pcm16k mono float PCM at 16 kHz, range [-1.0, 1.0]
     * @param language ISO-639-1 like "en", "de"; null = auto-detect
     * @param translate true = translate non-English to English (English-only output)
     * @param threads CPU threads to use (ignored on GPU path)
     * @param highQuality switch sampling strategy from greedy → beam search (slower, more accurate)
     * @param onSegment fires per committed segment for streaming UI updates
     * @param onProgress fires periodically with 0..1 progress
     */
    suspend fun transcribe(
        pcm16k: FloatArray,
        language: String? = null,
        translate: Boolean = false,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        highQuality: Boolean = false,
        onSegment: ((String) -> Unit)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): String = mutex.withLock {
        val ptr = ctxPtr.get()
        check(ptr != 0L) { "WhisperEngine not loaded — call load() first" }
        val cb = if (onSegment != null || onProgress != null) {
            TranscribeCallback().apply {
                this.onSegment = onSegment
                this.onProgress = onProgress
            }
        } else null
        coroutineScope {
            val finished = AtomicBoolean(false)
            // Watcher: as soon as the caller is cancelled, set the native abort
            // flag so whisper_full returns early instead of running to the end.
            // coroutineScope waits for this child, so the abort can never fire
            // after the mutex is released (ptr stays valid for its lifetime).
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) nativeRequestAbort(ptr)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    nativeTranscribe(ptr, pcm16k, language ?: "", translate, threads, highQuality, cb)
                }
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    }

    /**
     * Convenience: full load → transcribe → keep loaded. Used by [Benchmark] which
     * iterates many (model, gpu) combinations.
     */
    suspend fun runOnce(
        modelFile: File,
        useGpu: Boolean,
        pcm16k: FloatArray,
        language: String? = null,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    ): Result<String> = try {
        load(modelFile, useGpu).getOrThrow()
        Result.success(transcribe(pcm16k = pcm16k, language = language, translate = false, threads = threads))
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    // ---------- native ----------
    private external fun nativeInitContext(modelPath: String, useGpu: Boolean): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeTranscribe(
        ctxPtr: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        nThreads: Int,
        useBeam: Boolean,
        callback: TranscribeCallback?
    ): String
    /**
     * Sets an atomic abort flag polled by nativeTranscribe's abort_callback;
     * the flag resets at the start of each transcribe.
     */
    private external fun nativeRequestAbort(ctxPtr: Long)
    private external fun nativeBackendInfo(): String
    private external fun nativeLastError(): String
}

/**
 * JNI calls these methods directly via reflection-free MethodIDs — keep public + don't rename.
 */
class TranscribeCallback {
    @JvmField var onSegment: ((String) -> Unit)? = null
    @JvmField var onProgress: ((Float) -> Unit)? = null

    @Suppress("unused")
    fun jniSegment(text: String) {
        onSegment?.invoke(text)
    }

    @Suppress("unused")
    fun jniProgress(pct: Int) {
        onProgress?.invoke(pct / 100f)
    }
}

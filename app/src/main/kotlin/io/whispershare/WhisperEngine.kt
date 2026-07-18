package io.whispershare

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Thin Kotlin wrapper over the whisper.cpp JNI bridge.
 *
 * Lifecycle: load() once, then transcribe() many times, then release() at process death.
 */
object WhisperEngine {

    private const val TAG = "WhisperEngine"

    // Chunked-streaming knobs — see planChunks() for the rationale.
    private const val CHUNK_STREAM_MAX_SEC = 90
    private const val CHUNK_MIN_SEC = 8
    private const val CHUNK_MAX_SEC = 16
    private const val SILENCE_FRAME_SAMPLES = 320 // 20 ms at 16 kHz

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

    /**
     * Absolute path of the Silero VAD model to apply to every transcription,
     * null = VAD off. Seeded at process start (WhisperApp) from the vadEnabled
     * preference and re-pushed when the user toggles the setting. Re-validated
     * per transcribe: a missing/invalid file logs and runs without VAD instead
     * of failing the run.
     */
    @Volatile var vadModelPath: String? = null

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
     * @param pcmFile mono int16 LE PCM at 16 kHz on disk ([AudioDecoder.DecodedPcm.file]).
     *   Read block-wise in native code, so a long clip never materializes as a Kotlin
     *   FloatArray plus a second JNI pin/copy.
     * @param language ISO-639-1 like "en", "de"; null = auto-detect
     * @param translate true = translate non-English to English (English-only output)
     * @param threads CPU threads to use (ignored on GPU path)
     * @param highQuality switch sampling strategy from greedy → beam search (slower, more accurate)
     * @param onSegment fires per committed segment for streaming UI updates
     * @param onProgress fires periodically with 0..1 progress
     */
    suspend fun transcribe(
        pcmFile: File,
        language: String? = null,
        translate: Boolean = false,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        highQuality: Boolean = false,
        onSegment: ((String) -> Unit)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): String = mutex.withLock {
        val ptr = ctxPtr.get()
        check(ptr != 0L) { "WhisperEngine not loaded — call load() first" }
        val totalSamples = pcmFile.length() / 2
        // Per-chunk window the progress callback maps native 0..1 into, so the
        // caller sees one monotonic overall progress across all chunks.
        var progressBase = 0f
        var progressSpan = 1f
        val cb = if (onSegment != null || onProgress != null) {
            TranscribeCallback().apply {
                this.onSegment = onSegment
                if (onProgress != null) {
                    this.onProgress = { p -> onProgress((progressBase + p * progressSpan).coerceIn(0f, 1f)) }
                }
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
                    nativeSetVadModelPath(resolveVadModelPath())
                    val bounds = planChunks(pcmFile, totalSamples)
                    val parts = ArrayList<String>(bounds.size - 1)
                    var lang = language ?: ""
                    for (i in 0 until bounds.size - 1) {
                        // The abort flag resets on every native call, so a
                        // cancellation that lands between chunks must be
                        // caught here instead of relying on the flag.
                        ensureActive()
                        val start = bounds[i]
                        val end = bounds[i + 1]
                        progressBase = if (totalSamples > 0) start.toFloat() / totalSamples else 0f
                        progressSpan = if (totalSamples > 0) (end - start).toFloat() / totalSamples else 1f
                        parts += nativeTranscribeFile(
                            ptr, pcmFile.absolutePath, start, end,
                            lang, translate, threads, highQuality, cb
                        )
                        // Pin the auto-detected language after the first chunk so
                        // a multi-chunk run can't flip languages mid-transcript.
                        if (i == 0 && lang.isEmpty() && bounds.size > 2) {
                            lang = nativeDetectedLanguage(ptr)
                        }
                    }
                    parts.filter { it.isNotBlank() }.joinToString(" ")
                }
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    }

    /**
     * Streaming granularity: whisper_full fires its segment/progress callbacks
     * only once per internal ~30 s window, so a typical voice note (< 30 s =
     * one window) would display nothing until the very end. Clips up to
     * [CHUNK_STREAM_MAX_SEC] are therefore split at silence-aligned boundaries
     * into [CHUNK_MIN_SEC]..[CHUNK_MAX_SEC] second pieces, each its own
     * whisper_full call — text streams in per piece. Every extra call pays
     * whisper's fixed per-window encoder cost (~2.8 s CPU floor measured on a
     * Pixel 9 in soundscript's 2026-06 spike), so longer clips — which already
     * stream once per native window — stay a single call.
     *
     * Returns chunk boundaries as samples offsets: [0, c1, …, totalSamples].
     */
    @VisibleForTesting
    internal fun planChunks(pcmFile: File, totalSamples: Long): LongArray {
        val rate = AudioDecoder.TARGET_RATE.toLong()
        if (totalSamples <= CHUNK_MAX_SEC * rate || totalSamples > CHUNK_STREAM_MAX_SEC * rate) {
            return longArrayOf(0, totalSamples)
        }

        // Mean |amplitude| per 20 ms frame — enough resolution to find pauses.
        val frameCount = (totalSamples / SILENCE_FRAME_SAMPLES).toInt()
        val energy = FloatArray(frameCount)
        pcmFile.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(SILENCE_FRAME_SAMPLES * 2)
            frames@ for (f in 0 until frameCount) {
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break@frames
                    read += n
                }
                var sum = 0L
                var i = 0
                while (i < buf.size) {
                    val v = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                    sum += abs(v)
                    i += 2
                }
                energy[f] = sum.toFloat() / SILENCE_FRAME_SAMPLES
            }
        }

        val bounds = ArrayList<Long>()
        bounds.add(0L)
        var pos = 0L
        while (totalSamples - pos > CHUNK_MAX_SEC * rate) {
            // Cut at the quietest 20 ms frame in the allowed window — a real
            // pause when there is one, the least-bad spot otherwise.
            val lo = ((pos + CHUNK_MIN_SEC * rate) / SILENCE_FRAME_SAMPLES).toInt()
            val hi = ((pos + CHUNK_MAX_SEC * rate) / SILENCE_FRAME_SAMPLES).toInt()
                .coerceAtMost(energy.size - 1)
            var best = lo
            for (f in lo..hi) {
                if (energy[f] < energy[best]) best = f
            }
            pos = best.toLong() * SILENCE_FRAME_SAMPLES
            bounds.add(pos)
        }
        bounds.add(totalSamples)
        return bounds.toLongArray()
    }

    /**
     * Validated VAD model path for this run, "" = VAD off. Called on
     * Dispatchers.IO (reads 4 bytes off disk). whisper_full fails the whole
     * run when its VAD init fails, so an unusable file falls back to a plain
     * transcription here instead.
     */
    private fun resolveVadModelPath(): String {
        val path = vadModelPath ?: return ""
        if (ModelManager.isUsableVadModel(File(path))) return path
        Log.w(TAG, "VAD model missing or invalid at $path — transcribing without VAD")
        return ""
    }

    /**
     * ISO-639-1 code (e.g. "de") of the language whisper detected during the
     * last completed [transcribe]; "" when unavailable (not loaded, no run
     * yet, or the last run failed). Only meaningful right after a transcribe —
     * call it before starting the next one. Serialized on the same mutex so
     * it can't race a running transcription.
     */
    suspend fun detectedLanguage(): String = mutex.withLock {
        val ptr = ctxPtr.get()
        if (ptr == 0L) "" else nativeDetectedLanguage(ptr)
    }

    /**
     * Convenience: full load → transcribe → keep loaded. Used by [Benchmark] which
     * iterates many (model, gpu) combinations.
     */
    suspend fun runOnce(
        modelFile: File,
        useGpu: Boolean,
        pcmFile: File,
        language: String? = null,
        threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    ): Result<String> = try {
        load(modelFile, useGpu).getOrThrow()
        Result.success(transcribe(pcmFile = pcmFile, language = language, translate = false, threads = threads))
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }

    // ---------- native ----------
    private external fun nativeInitContext(modelPath: String, useGpu: Boolean): Long
    private external fun nativeFreeContext(ctxPtr: Long)
    private external fun nativeTranscribeFile(
        ctxPtr: Long,
        pcmPath: String,
        startSample: Long,
        endSample: Long,
        language: String,
        translate: Boolean,
        nThreads: Int,
        useBeam: Boolean,
        callback: TranscribeCallback?
    ): String
    /**
     * Sets an atomic abort flag polled by nativeTranscribeFile's abort_callback;
     * the flag resets at the start of each transcribe.
     */
    private external fun nativeRequestAbort(ctxPtr: Long)
    /**
     * Sets the mutex-guarded native VAD model path; "" disables VAD. Applied
     * by the next nativeTranscribeFile (called before every run, same mutex).
     */
    private external fun nativeSetVadModelPath(path: String)
    /**
     * Language code detected by the last completed transcribe, "" if none;
     * the stored id resets at the start of each transcribe.
     */
    private external fun nativeDetectedLanguage(ctxPtr: Long): String
    private external fun nativeBackendInfo(): String
    private external fun nativeLastError(): String
}

/**
 * Strip Whisper non-speech annotations so silence/music/noise never becomes transcript text:
 * bracketed cues ([BLANK_AUDIO], [MUSIC PLAYING], [INAUDIBLE]…), starred cues (*laughs*), and a
 * whitelist of parenthesized non-speech cues. Returns the remaining real speech, or "" when
 * nothing's left (callers map "" → "(no speech detected)"). Real speech almost never contains
 * [...] so bracket-stripping is safe; parentheses are only stripped for known non-speech words,
 * to keep genuine asides like "call me (later)".
 */
fun stripNonSpeech(raw: String): String {
    val cleaned = raw
        .replace(Regex("\\[[^\\]]*]"), " ")
        .replace(Regex("\\*[^*]*\\*"), " ")
        .replace(
            Regex(
                "(?i)\\(\\s*(?:music|applause|laughter|laughs?|chuckles?|inaudible|silence|no audio|" +
                    "blank[_ ]?audio|background noise|noise|sighs?|coughs?|breathing|static|beep|wind|" +
                    "foreign language|speaking (?:in )?(?:a )?foreign language)\\s*\\)"
            ),
            " ",
        )
        .replace(Regex("\\s+"), " ")
        .trim()
    // Whatever survives is real only if it has a letter or digit — drop lone punctuation like "[…]."→".".
    return if (cleaned.any { it.isLetterOrDigit() }) cleaned else ""
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

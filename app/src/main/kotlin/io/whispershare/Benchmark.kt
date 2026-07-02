package io.whispershare

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs every installed model on a fixed PCM buffer, on CPU and (if compiled with
 * Vulkan) GPU, capturing wall-clock elapsed time per (model × backend) pair.
 *
 * The result list lets the UI show a "what's actually fastest on this device"
 * table without making the user manually swap settings between transcriptions.
 */
data class BenchmarkResult(
    val modelId: String,
    val modelName: String,
    val backend: String,     // "cpu" or "gpu"
    val durationSec: Double, // input audio length
    val elapsedMs: Long,
    val realtimeFactor: Double,
    val sampleText: String,
    val error: String? = null
)

object Benchmark {

    /** True if the binary was built with Vulkan support. GPU is meaningless otherwise. */
    fun gpuSupported(): Boolean = WhisperEngine.compiledBackend == "vulkan"

    suspend fun run(
        context: Context,
        pcm: FloatArray,
        models: List<ModelManager.ModelEntry>,
        includeGpu: Boolean,
        threads: Int,
        onProgress: (current: Int, total: Int, label: String) -> Unit
    ): List<BenchmarkResult> {
        val durationSec = pcm.size / 16_000.0
        val gpuAvailable = includeGpu && gpuSupported()
        val backends = if (gpuAvailable) listOf(false, true) else listOf(false)
        val total = models.size * backends.size

        val out = mutableListOf<BenchmarkResult>()
        var i = 0
        try {
            for (model in models) {
                for (gpu in backends) {
                    val backendLabel = if (gpu) "gpu" else "cpu"
                    onProgress(i, total, "${model.displayName} · $backendLabel")
                    val file = ModelManager.fileFor(context, model)
                    val started = System.currentTimeMillis()
                    val res = WhisperEngine.runOnce(
                        modelFile = file,
                        useGpu = gpu,
                        pcm16k = pcm,
                        threads = threads
                    )
                    val elapsed = System.currentTimeMillis() - started
                    out += res.fold(
                        onSuccess = { text ->
                            BenchmarkResult(
                                modelId = model.id,
                                modelName = model.displayName,
                                backend = backendLabel,
                                durationSec = durationSec,
                                elapsedMs = elapsed,
                                realtimeFactor = durationSec / (elapsed / 1000.0).coerceAtLeast(0.001),
                                sampleText = text.take(120)
                            )
                        },
                        onFailure = { t ->
                            BenchmarkResult(
                                modelId = model.id,
                                modelName = model.displayName,
                                backend = backendLabel,
                                durationSec = durationSec,
                                elapsedMs = elapsed,
                                realtimeFactor = 0.0,
                                sampleText = "",
                                error = t.message
                            )
                        }
                    )
                    i++
                }
            }
        } finally {
            // Don't keep the last-loaded model pinned in RAM after the run —
            // even when it fails or is cancelled. NonCancellable so the release
            // still happens (and doesn't throw) from a cancelled coroutine.
            withContext(NonCancellable) { WhisperEngine.release() }
        }
        onProgress(total, total, "")
        return out
    }
}

package io.whispershare

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight model registry + downloader. Models live under filesDir/models/.
 *
 * Defaults to ggml-base-q5_1.bin (multilingual, 60 MB) which strikes the best balance
 * between speed and accuracy for messenger voice notes on Tensor G4. Swap if needed.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    enum class Model(
        val displayName: String,
        val filename: String,
        val urlPath: String,
        val approxSizeMb: Int,
        val multilingual: Boolean
    ) {
        TINY_Q5("Tiny (multilingual, fastest)",       "ggml-tiny-q5_1.bin",   "ggml-tiny-q5_1.bin",   31, true),
        BASE_Q5("Base (multilingual, recommended)",   "ggml-base-q5_1.bin",   "ggml-base-q5_1.bin",   60, true),
        SMALL_Q5("Small (multilingual, accurate)",    "ggml-small-q5_1.bin",  "ggml-small-q5_1.bin", 190, true),
        BASE_EN_Q5("Base English-only (faster)",      "ggml-base.en-q5_1.bin","ggml-base.en-q5_1.bin", 60, false);

        fun downloadUrl(): String = "$HF_BASE/$urlPath"
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, model: Model): File =
        File(modelsDir(context), model.filename)

    fun isDownloaded(context: Context, model: Model): Boolean =
        fileFor(context, model).let { it.exists() && it.length() > 1_000_000 }

    fun listInstalled(context: Context): List<Model> =
        Model.entries.filter { isDownloaded(context, it) }

    /** Emits 0..100 then -1 to signal completion. */
    fun download(context: Context, model: Model): Flow<Int> = flow {
        val target = fileFor(context, model)
        val tmp = File(target.absolutePath + ".part")
        val url = URL(model.downloadUrl())
        Log.i(TAG, "Downloading $url -> $target")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            conn.connect()
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            var lastEmitted = -1

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastEmitted) {
                                lastEmitted = pct
                                emit(pct)
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            emit(100)
            emit(-1)
        } finally {
            conn.disconnect()
            if (tmp.exists()) tmp.delete()
        }
    }.flowOn(Dispatchers.IO)

    fun delete(context: Context, model: Model) {
        fileFor(context, model).delete()
    }
}

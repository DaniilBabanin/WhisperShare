package io.whispershare

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Model registry + downloader. Models live under filesDir/models/.
 *
 * Two flavours:
 *  - [BuiltInModel] — curated list of ggml quantizations served from huggingface.co.
 *    SHA-256 verified after download (size column matches HF v1.7.4 manifest).
 *  - [CustomModel] — user-imported .bin files, persisted in custom_models.json so
 *    they survive process restarts. Useful when HF is unreachable / rate-limited.
 *
 * Both implement [ModelEntry], the common interface used by the rest of the app.
 */
object ModelManager {

    private const val TAG = "ModelManager"
    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val MANIFEST_FILE = "custom_models.json"
    private const val CUSTOM_PREFIX = "custom_"

    // ---------- public types ----------

    sealed interface ModelEntry {
        val id: String
        val displayName: String
        val filename: String
        val multilingual: Boolean
        val approxSizeMb: Int
    }

    enum class BuiltInModel(
        override val displayName: String,
        override val filename: String,
        val urlPath: String,
        override val approxSizeMb: Int,
        override val multilingual: Boolean,
        val sha256: String
    ) : ModelEntry {
        TINY_Q5(
            "Tiny (multilingual, fastest)",
            "ggml-tiny-q5_1.bin",
            "ggml-tiny-q5_1.bin",
            31, true,
            "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
        ),
        BASE_Q5(
            "Base (multilingual, recommended)",
            "ggml-base-q5_1.bin",
            "ggml-base-q5_1.bin",
            60, true,
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        ),
        SMALL_Q5(
            "Small (multilingual, accurate)",
            "ggml-small-q5_1.bin",
            "ggml-small-q5_1.bin",
            190, true,
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        ),
        BASE_EN_Q5(
            "Base English-only (faster)",
            "ggml-base.en-q5_1.bin",
            "ggml-base.en-q5_1.bin",
            60, false,
            "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
        );

        override val id: String get() = "builtin:$name"
        fun downloadUrl(): String = "$HF_BASE/$urlPath"
    }

    data class CustomModel(
        override val displayName: String,
        override val filename: String,
        override val multilingual: Boolean,
        override val approxSizeMb: Int
    ) : ModelEntry {
        override val id: String get() = "custom:$filename"
    }

    /** For UI: keep BuiltIn maintained in source order, then customs. */
    fun listAll(context: Context): List<ModelEntry> =
        BuiltInModel.entries.toList<ModelEntry>() + readCustomManifest(context)

    fun listInstalled(context: Context): List<ModelEntry> =
        listAll(context).filter { isDownloaded(context, it) }

    fun entryById(context: Context, id: String): ModelEntry? =
        listAll(context).firstOrNull { it.id == id }

    // ---------- file paths ----------

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(context: Context, entry: ModelEntry): File =
        File(modelsDir(context), entry.filename)

    fun isDownloaded(context: Context, entry: ModelEntry): Boolean =
        fileFor(context, entry).let { it.exists() && it.length() > 1_000_000 }

    // ---------- download (built-ins only) ----------

    /**
     * Emits 0..100 then -1 to signal completion. SHA-256 verified on success
     * unless [verify] is false (developer escape hatch).
     */
    fun download(context: Context, model: BuiltInModel, verify: Boolean = true): Flow<Int> = flow {
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

            // Verify before promoting .part -> final.
            if (verify) {
                val actual = sha256(tmp)
                if (!actual.equals(model.sha256, ignoreCase = true)) {
                    tmp.delete()
                    throw IllegalStateException(
                        "Checksum mismatch for ${model.filename}: expected ${model.sha256.take(12)}…, got ${actual.take(12)}…"
                    )
                }
            } else {
                Log.w(TAG, "SHA-256 verification skipped for ${model.filename} (developer override)")
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

    fun delete(context: Context, entry: ModelEntry) {
        fileFor(context, entry).delete()
        if (entry is CustomModel) removeFromManifest(context, entry)
    }

    // ---------- custom import ----------

    /**
     * Copy a user-picked .bin into the app's models directory and register it in
     * the custom manifest. Falls back gracefully if the URI can't be opened.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        displayName: String,
        multilingual: Boolean
    ): Result<CustomModel> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val sanitised = sanitiseFilename(displayName)
            val filename = "$CUSTOM_PREFIX$sanitised.bin"
            val target = File(modelsDir(context), filename)
            val tmp = File(target.absolutePath + ".part")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open $uri")

            if (tmp.length() < 1_000_000) {
                tmp.delete()
                return@withContext Result.failure(
                    IllegalStateException("File is too small to be a whisper model (<1 MB)")
                )
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            val entry = CustomModel(
                displayName = displayName.take(60),
                filename = filename,
                multilingual = multilingual,
                approxSizeMb = (target.length() / 1_000_000).toInt().coerceAtLeast(1)
            )
            addToManifest(context, entry)
            Log.i(TAG, "Imported custom model: ${entry.filename} (${entry.approxSizeMb} MB)")
            Result.success(entry)
        } catch (t: Throwable) {
            Log.w(TAG, "Custom import failed", t)
            Result.failure(t)
        }
    }

    // ---------- manifest helpers ----------

    private fun manifestFile(context: Context): File =
        File(modelsDir(context), MANIFEST_FILE)

    private fun readCustomManifest(context: Context): List<CustomModel> {
        val file = manifestFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val filename = o.getString("filename")
                    // Drop entries whose underlying file went missing.
                    if (!File(modelsDir(context), filename).exists()) continue
                    add(
                        CustomModel(
                            displayName = o.optString("displayName", filename),
                            filename = filename,
                            multilingual = o.optBoolean("multilingual", true),
                            approxSizeMb = o.optInt("approxSizeMb", 0)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read custom manifest", t)
            emptyList()
        }
    }

    private fun writeCustomManifest(context: Context, entries: List<CustomModel>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("filename", e.filename)
                    put("displayName", e.displayName)
                    put("multilingual", e.multilingual)
                    put("approxSizeMb", e.approxSizeMb)
                }
            )
        }
        manifestFile(context).writeText(arr.toString())
    }

    private fun addToManifest(context: Context, entry: CustomModel) {
        val current = readCustomManifest(context).filterNot { it.filename == entry.filename }
        writeCustomManifest(context, current + entry)
    }

    private fun removeFromManifest(context: Context, entry: CustomModel) {
        val current = readCustomManifest(context).filterNot { it.filename == entry.filename }
        writeCustomManifest(context, current)
    }

    private fun sanitiseFilename(name: String): String {
        val cleaned = name.lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .take(40)
        return cleaned.ifBlank { "model_${System.currentTimeMillis()}" }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

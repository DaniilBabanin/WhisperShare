package io.whispershare

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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

    /** RFC 9110 §15.5.17 — java.net.HttpURLConnection has no constant for it. */
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    /**
     * GGML_FILE_MAGIC from whisper.cpp v1.8.4 (ggml/include/ggml.h): 0x67676d6c, "ggml".
     * whisper_model_load reads it as a little-endian uint32, so the file starts with
     * the bytes 6c 6d 67 67 on disk.
     */
    private const val GGML_FILE_MAGIC = 0x67676d6c

    // ---------- Silero VAD model (W3-G) ----------
    // Special non-transcription download: never appears in listAll()/the model
    // picker. whisper.cpp v1.8.4 hosts its converted VAD models in the
    // ggml-org/whisper-vad HF repo (see models/download-vad-model.sh upstream),
    // NOT in ggerganov/whisper.cpp. The file carries the same GGML magic as
    // whisper models (checked by whisper_vad_init_with_params).

    const val VAD_MODEL_FILENAME = "ggml-silero-v5.1.2.bin"

    internal const val VAD_MODEL_URL =
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_MODEL_FILENAME"

    /**
     * SHA-256 of ggml-silero-v5.1.2.bin, cross-checked between the HF
     * paths-info API and the raw git-LFS pointer (both report this value,
     * size 885098 bytes).
     */
    internal const val VAD_MODEL_SHA256 =
        "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"

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
        LARGE_V3_TURBO_Q5(
            "Large v3 Turbo (multilingual, most accurate, high RAM use)",
            "ggml-large-v3-turbo-q5_0.bin",
            "ggml-large-v3-turbo-q5_0.bin",
            574, true,
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"
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

    /** Progress of a model download. */
    sealed interface DownloadProgress {
        /** Total size known: 0..100. */
        data class Percent(val percent: Int) : DownloadProgress

        /** Total size unknown (chunked response): megabytes received so far. */
        data class DownloadedMb(val mb: Int) : DownloadProgress
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

    fun vadModelFile(context: Context): File =
        File(modelsDir(context), VAD_MODEL_FILENAME)

    /**
     * The silero file is only ~0.9 MB, so the 1 MB whisper-model heuristic
     * from [isDownloaded] would never pass — use a lower floor.
     */
    fun isVadModelDownloaded(context: Context): Boolean =
        vadModelFile(context).let { it.exists() && it.length() > 500_000 }

    /**
     * Cheap pre-flight used by [WhisperEngine] right before a transcription:
     * the file exists and starts with the GGML magic (the VAD ggml format
     * shares it with whisper models). Any I/O error counts as unusable.
     */
    fun isUsableVadModel(file: File): Boolean =
        runCatching { file.exists() && hasGgmlMagic(file) }.getOrDefault(false)

    // ---------- download (built-ins only) ----------

    /**
     * Emits [DownloadProgress] until the flow completes. Percent when the total
     * size is known, downloaded megabytes otherwise (chunked responses).
     * SHA-256 verified on success unless [verify] is false (developer escape hatch).
     *
     * Resume: a leftover .part from a previously *failed* transfer is continued
     * with an HTTP `Range: bytes=N-` request (206 appends, 200 restarts from
     * scratch, 416 either means "already complete" or forces a clean restart).
     * Cancelling the collector aborts the transfer and removes the .part file;
     * a transfer that fails on its own keeps it so the next attempt can resume.
     */
    fun download(context: Context, model: BuiltInModel, verify: Boolean = true): Flow<DownloadProgress> =
        downloadFile(fileFor(context, model), URL(model.downloadUrl()), model.sha256, verify)

    /** Same plumbing as [download] (Range-resume, SHA-256) for the Silero VAD model. */
    fun downloadVadModel(context: Context, verify: Boolean = true): Flow<DownloadProgress> =
        downloadFile(vadModelFile(context), URL(VAD_MODEL_URL), VAD_MODEL_SHA256, verify)

    private fun downloadFile(
        target: File,
        url: URL,
        expectedSha256: String,
        verify: Boolean
    ): Flow<DownloadProgress> = flow {
        val tmp = File(target.absolutePath + ".part")

        var conn: HttpURLConnection? = null
        try {
            var startOffset = tmp.length() // 0 when the .part doesn't exist
            Log.i(TAG, "Downloading $url -> $target (resume offset $startOffset)")
            var c = openConnection(url, startOffset)
            conn = c
            c.connect()

            var skipTransfer = false
            var total: Long
            if (startOffset > 0) {
                val action = resumeActionFor(
                    responseCode = c.responseCode,
                    resumeOffset = startOffset,
                    contentRangeHeader = c.getHeaderField("Content-Range"),
                    contentLength = c.contentLengthLong
                )
                when (action) {
                    is ResumeAction.Append -> {
                        Log.i(TAG, "Resuming ${target.name} at $startOffset bytes")
                        total = action.total
                    }
                    ResumeAction.FullBody -> {
                        // Server ignored the Range header: stream the full body from scratch.
                        startOffset = 0
                        total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
                    }
                    ResumeAction.DiscardAndRestart -> {
                        c.disconnect()
                        tmp.delete()
                        startOffset = 0
                        c = openConnection(url, 0)
                        conn = c
                        c.connect()
                        total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
                    }
                    ResumeAction.AlreadyComplete -> {
                        // .part already holds every byte; fall through to verification.
                        skipTransfer = true
                        total = startOffset
                    }
                }
            } else {
                total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
            }

            if (!skipTransfer) {
                var downloaded = startOffset
                var lastEmitted = -1

                c.inputStream.use { input ->
                    FileOutputStream(tmp, /* append = */ startOffset > 0).use { output ->
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
                                    emit(DownloadProgress.Percent(pct))
                                }
                            } else {
                                val mb = (downloaded / 1_000_000).toInt()
                                if (mb != lastEmitted) {
                                    lastEmitted = mb
                                    emit(DownloadProgress.DownloadedMb(mb))
                                }
                            }
                        }
                    }
                }
            }

            // Verify before promoting .part -> final. Hashes the complete file,
            // so a corrupt resume (appended onto a stale .part) is caught here.
            if (verify) {
                val actual = sha256(tmp)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tmp.delete() // never offer a corrupt file for another resume
                    throw IllegalStateException(
                        "Checksum mismatch for ${target.name}: expected ${expectedSha256.take(12)}…, got ${actual.take(12)}…"
                    )
                }
            } else {
                Log.w(TAG, "SHA-256 verification skipped for ${target.name} (developer override)")
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            emit(DownloadProgress.Percent(100))
        } catch (e: CancellationException) {
            // Explicit cancel: the user discarded the download, so discard the .part.
            tmp.delete()
            throw e
        } finally {
            conn?.disconnect()
            // Any other failure (network drop, server error) keeps the .part so the
            // next attempt can resume with an HTTP Range request.
        }
    }.flowOn(Dispatchers.IO)

    private fun openConnection(url: URL, rangeOffset: Long): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            if (rangeOffset > 0) setRequestProperty("Range", "bytes=$rangeOffset-")
        }

    /** How to treat the server's response to a `Range: bytes=N-` resume request. */
    @VisibleForTesting
    internal sealed interface ResumeAction {
        /** 206 starting exactly at N: append to the .part. [total] is the full size, -1 if unknown. */
        data class Append(val total: Long) : ResumeAction

        /** 200: server ignored the Range header; truncate the .part and write the full body. */
        data object FullBody : ResumeAction

        /** Range response unusable: delete the .part and re-request without Range. */
        data object DiscardAndRestart : ResumeAction

        /** 416 and the .part already holds the complete file: skip straight to verification. */
        data object AlreadyComplete : ResumeAction
    }

    /**
     * Pure decision for resuming at [resumeOffset] (> 0) given the server's response.
     * Throws [IOException] for status codes a ranged GET should never produce.
     */
    @VisibleForTesting
    internal fun resumeActionFor(
        responseCode: Int,
        resumeOffset: Long,
        contentRangeHeader: String?,
        contentLength: Long
    ): ResumeAction = when (responseCode) {
        HttpURLConnection.HTTP_PARTIAL -> {
            val range = parseContentRange(contentRangeHeader)
            if (range?.first != resumeOffset) {
                // Missing/garbled Content-Range or an offset we didn't ask for.
                ResumeAction.DiscardAndRestart
            } else {
                val total = range.total
                    ?: contentLength.takeIf { it > 0 }?.let { resumeOffset + it }
                    ?: -1L
                ResumeAction.Append(total)
            }
        }
        HttpURLConnection.HTTP_OK -> ResumeAction.FullBody
        HTTP_RANGE_NOT_SATISFIABLE -> {
            // The 416 SHOULD carry "Content-Range: bytes */<total>"; if the .part is
            // already exactly <total> bytes, everything arrived before the failure.
            val total = parseContentRange(contentRangeHeader)?.total
            if (total != null && total == resumeOffset) ResumeAction.AlreadyComplete
            else ResumeAction.DiscardAndRestart
        }
        else -> throw IOException("Unexpected HTTP $responseCode for ranged download request")
    }

    /** Parsed `Content-Range` header. [total] is null when the server sends `*`. */
    @VisibleForTesting
    internal data class ContentRange(val first: Long?, val last: Long?, val total: Long?)

    private val CONTENT_RANGE_REGEX =
        Regex("""bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    /** Parses `bytes 100-999/1000`, `bytes 100-999/*` and `bytes */1000`; null if malformed. */
    @VisibleForTesting
    internal fun parseContentRange(header: String?): ContentRange? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header?.trim() ?: return null) ?: return null
        val (first, last, total) = match.destructured
        return ContentRange(
            first = first.toLongOrNull(),
            last = last.toLongOrNull(),
            total = total.toLongOrNull() // "*" -> null
        )
    }

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

            // whisper.cpp's model parser is not hardened — refuse anything that
            // doesn't carry the GGML magic before it can ever reach native code.
            if (!hasGgmlMagic(tmp)) {
                tmp.delete()
                return@withContext Result.failure(
                    IllegalStateException(context.getString(R.string.import_error_not_ggml))
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Custom import failed", t)
            Result.failure(t)
        }
    }

    /** True if the file starts with GGML_FILE_MAGIC ("ggml", little-endian on disk). */
    @VisibleForTesting
    internal fun hasGgmlMagic(file: File): Boolean {
        val header = ByteArray(4)
        FileInputStream(file).use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) return false
                read += n
            }
        }
        val magic = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 24)
        return magic == GGML_FILE_MAGIC
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

    @VisibleForTesting
    internal fun sanitiseFilename(name: String): String {
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

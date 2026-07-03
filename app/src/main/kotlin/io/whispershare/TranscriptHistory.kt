package io.whispershare

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Opt-in, local-only transcript history.
 *
 * Storage is a flat JSON array in `filesDir/transcript_history.json`, oldest
 * entry first. Growth is capped at [MAX_ENTRIES]: appending beyond the cap
 * silently drops the oldest entries. A corrupt or unreadable file is treated
 * as an empty history — never a crash — and the next append rewrites it
 * cleanly. All I/O runs on [Dispatchers.IO].
 *
 * The opt-in flag (default OFF — the app is privacy-focused, nothing is ever
 * written unless the user enables it) lives in its own SharedPreferences file
 * ([PREFS_NAME]) instead of [AppPreferences]. This is a deliberate ownership
 * workaround: AppPreferences is being reworked in a parallel change, so the
 * flag gets isolated storage here; a follow-up can consolidate it there.
 *
 * The constructor takes the backing [File] directly so pure-JVM tests can
 * point it at a temp directory; production code uses [forApp].
 */
class TranscriptHistory(private val file: File) {

    /**
     * One saved transcription run. Batch runs produce a single entry whose
     * [text] is the combined transcript and whose [sourceName] is "N files".
     */
    data class Entry(
        val timestamp: Long,
        val sourceName: String,
        val text: String,
        val durationSec: Double,
        val detectedLanguage: String? = null
    )

    /** All saved entries, oldest first. Missing or corrupt file → empty list. */
    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) { readEntries() }

    /** Appends [entry], dropping the oldest entries beyond [MAX_ENTRIES]. */
    suspend fun append(entry: Entry) {
        withContext(Dispatchers.IO) {
            val entries = (readEntries() + entry).takeLast(MAX_ENTRIES)
            file.writeText(toJson(entries))
        }
    }

    /** Removes the whole history (deletes the backing file). */
    suspend fun clear() {
        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun readEntries(): List<Entry> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    timestamp = o.optLong(KEY_TIMESTAMP),
                    sourceName = o.optString(KEY_SOURCE_NAME),
                    text = o.optString(KEY_TEXT),
                    durationSec = o.optDouble(KEY_DURATION_SEC, 0.0),
                    detectedLanguage = o.optString(KEY_DETECTED_LANGUAGE)
                        .takeIf { it.isNotBlank() }
                )
            }
        }
    } catch (t: Throwable) {
        // Corrupt file (bad JSON, I/O error): behave as empty. The next
        // append overwrites it with valid content.
        emptyList()
    }

    private fun toJson(entries: List<Entry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put(KEY_TIMESTAMP, e.timestamp)
                    put(KEY_SOURCE_NAME, e.sourceName)
                    put(KEY_TEXT, e.text)
                    put(KEY_DURATION_SEC, e.durationSec)
                    if (e.detectedLanguage != null) put(KEY_DETECTED_LANGUAGE, e.detectedLanguage)
                }
            )
        }
        return arr.toString()
    }

    companion object {
        /** Newest entries kept when the history grows past this. */
        const val MAX_ENTRIES = 50

        private const val FILE_NAME = "transcript_history.json"
        private const val PREFS_NAME = "history_prefs"
        private const val KEY_ENABLED = "history_enabled"

        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SOURCE_NAME = "sourceName"
        private const val KEY_TEXT = "text"
        private const val KEY_DURATION_SEC = "durationSec"
        private const val KEY_DETECTED_LANGUAGE = "detectedLanguage"

        /** History backed by the app's standard location in [Context.getFilesDir]. */
        fun forApp(context: Context): TranscriptHistory =
            TranscriptHistory(File(context.filesDir, FILE_NAME))

        /** Whether saving to history is enabled. Default false (opt-in). */
        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

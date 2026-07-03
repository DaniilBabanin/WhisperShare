package io.whispershare

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for [TranscriptHistory]: JSON round-trip fidelity, the
 * [TranscriptHistory.MAX_ENTRIES] cap, corrupt-file recovery, and clear-all.
 * The backing file is injected, so no Android framework is involved.
 */
class TranscriptHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var history: TranscriptHistory

    private fun setup() {
        file = File(tmp.root, "transcript_history.json")
        history = TranscriptHistory(file)
    }

    private fun entry(
        n: Int,
        text: String = "text $n",
        language: String? = null
    ) = TranscriptHistory.Entry(
        timestamp = 1_700_000_000_000L + n,
        sourceName = "voice_$n.ogg",
        text = text,
        durationSec = n + 0.5,
        detectedLanguage = language
    )

    // ---------- load ----------

    @Test
    fun `missing file loads as empty`() = runTest {
        setup()
        assertEquals(emptyList<TranscriptHistory.Entry>(), history.load())
    }

    // ---------- round trip ----------

    @Test
    fun `entries round-trip through JSON unchanged`() = runTest {
        setup()
        val first = entry(1, language = "de")
        val second = entry(
            2,
            text = "line one\nline two \"quoted\" — ümlauts, emoji 🎉, backslash \\ and <tags>"
        )
        history.append(first)
        history.append(second)

        val loaded = TranscriptHistory(file).load()
        assertEquals(listOf(first, second), loaded)
    }

    @Test
    fun `null detected language survives the round trip`() = runTest {
        setup()
        history.append(entry(1, language = null))
        assertNull(TranscriptHistory(file).load().single().detectedLanguage)
    }

    @Test
    fun `file on disk is a JSON array`() = runTest {
        setup()
        history.append(entry(1))
        val raw = file.readText()
        assertTrue("expected JSON array, got: $raw", raw.startsWith("[") && raw.endsWith("]"))
    }

    // ---------- cap ----------

    @Test
    fun `append caps history at MAX_ENTRIES keeping the newest`() = runTest {
        setup()
        val total = TranscriptHistory.MAX_ENTRIES + 5
        for (n in 1..total) history.append(entry(n))

        val loaded = history.load()
        assertEquals(TranscriptHistory.MAX_ENTRIES, loaded.size)
        // Oldest 5 dropped; order preserved oldest-first.
        assertEquals(entry(6), loaded.first())
        assertEquals(entry(total), loaded.last())
    }

    // ---------- corrupt file ----------

    @Test
    fun `corrupt file loads as empty`() = runTest {
        setup()
        file.writeText("{this is [not json!!")
        assertEquals(emptyList<TranscriptHistory.Entry>(), history.load())
    }

    @Test
    fun `empty file loads as empty`() = runTest {
        setup()
        file.writeText("")
        assertEquals(emptyList<TranscriptHistory.Entry>(), history.load())
    }

    @Test
    fun `non-object array elements are skipped`() = runTest {
        setup()
        file.writeText("""[42, "junk", null]""")
        assertEquals(emptyList<TranscriptHistory.Entry>(), history.load())
    }

    @Test
    fun `append after corruption rewrites a valid file`() = runTest {
        setup()
        file.writeText("garbage")
        val e = entry(1)
        history.append(e)
        assertEquals(listOf(e), TranscriptHistory(file).load())
    }

    // ---------- clear ----------

    @Test
    fun `clear wipes all entries and deletes the file`() = runTest {
        setup()
        history.append(entry(1))
        history.append(entry(2))
        history.clear()
        assertFalse(file.exists())
        assertEquals(emptyList<TranscriptHistory.Entry>(), history.load())
    }
}

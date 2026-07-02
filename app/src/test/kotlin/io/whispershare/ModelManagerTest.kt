package io.whispershare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Pure-JVM tests for [ModelManager]'s framework-free logic: filename sanitisation,
 * GGML magic validation, model id / download-URL construction, and HTTP Range
 * resume decisions.
 */
class ModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- sanitiseFilename ----------

    @Test
    fun `sanitise strips unix path traversal`() {
        assertEquals("etc_passwd", ModelManager.sanitiseFilename("../../../etc/passwd"))
    }

    @Test
    fun `sanitise strips windows path traversal`() {
        assertEquals(
            "windows_system32",
            ModelManager.sanitiseFilename("..\\..\\Windows\\System32")
        )
    }

    @Test
    fun `sanitise replaces weird characters and lowercases`() {
        assertEquals("my_v_ice_m_del", ModelManager.sanitiseFilename("My Vöice Mödel!!"))
    }

    @Test
    fun `sanitise never emits path separators or leading dots`() {
        val nasty = listOf(
            "../../../etc/passwd",
            "..\\..\\evil.bin",
            "/absolute/path/model",
            "C:\\Users\\x\\model",
            "....//....//model",
            "model/../../x",
            "\u0000null\u0000byte",
            "..",
            "föö/bär",
        )
        for (input in nasty) {
            val out = ModelManager.sanitiseFilename(input)
            assertFalse("'$input' -> '$out' contains /", out.contains('/'))
            assertFalse("'$input' -> '$out' contains \\", out.contains('\\'))
            assertTrue("'$input' -> '$out' is blank", out.isNotBlank())
            assertFalse("'$input' -> '$out' starts with a dot", out.startsWith("."))
        }
    }

    @Test
    fun `sanitise falls back to timestamped name when nothing survives`() {
        assertTrue(ModelManager.sanitiseFilename("").startsWith("model_"))
        assertTrue(ModelManager.sanitiseFilename("!!!???").startsWith("model_"))
    }

    @Test
    fun `sanitise truncates to 40 characters`() {
        assertEquals("a".repeat(40), ModelManager.sanitiseFilename("a".repeat(60)))
    }

    // ---------- hasGgmlMagic ----------

    private fun fileWith(bytes: ByteArray): File =
        tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun `accepts file starting with ggml magic in little-endian byte order`() {
        // GGML_FILE_MAGIC 0x67676d6c stored little-endian: 6c 6d 67 67 on disk.
        val file = fileWith(byteArrayOf(0x6c, 0x6d, 0x67, 0x67) + ByteArray(64))
        assertTrue(ModelManager.hasGgmlMagic(file))
    }

    @Test
    fun `rejects big-endian byte order`() {
        val file = fileWith(byteArrayOf(0x67, 0x67, 0x6d, 0x6c) + ByteArray(64))
        assertFalse(ModelManager.hasGgmlMagic(file))
    }

    @Test
    fun `rejects jpeg header`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertFalse(ModelManager.hasGgmlMagic(fileWith(jpeg + ByteArray(64))))
    }

    @Test
    fun `rejects all-zero file`() {
        assertFalse(ModelManager.hasGgmlMagic(fileWith(ByteArray(16))))
    }

    @Test
    fun `rejects file shorter than four bytes`() {
        assertFalse(ModelManager.hasGgmlMagic(fileWith(byteArrayOf(0x6c, 0x6d))))
    }

    @Test
    fun `rejects empty file`() {
        assertFalse(ModelManager.hasGgmlMagic(fileWith(ByteArray(0))))
    }

    // ---------- model id / download URL ----------

    @Test
    fun `built-in ids are stable builtin-prefixed enum names`() {
        for (m in ModelManager.BuiltInModel.entries) {
            assertEquals("builtin:${m.name}", m.id)
        }
        assertEquals("builtin:BASE_Q5", ModelManager.BuiltInModel.BASE_Q5.id)
    }

    @Test
    fun `custom model id is custom-prefixed filename`() {
        val custom = ModelManager.CustomModel(
            displayName = "My Model",
            filename = "custom_my_model.bin",
            multilingual = true,
            approxSizeMb = 60
        )
        assertEquals("custom:custom_my_model.bin", custom.id)
    }

    @Test
    fun `download urls point at huggingface whisper repo`() {
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            ModelManager.BuiltInModel.BASE_Q5.downloadUrl()
        )
        for (m in ModelManager.BuiltInModel.entries) {
            val url = m.downloadUrl()
            assertTrue(
                "$url should be an HF resolve URL",
                url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/")
            )
            assertTrue("$url should end with the model filename", url.endsWith(m.urlPath))
        }
    }

    @Test
    fun `built-in entries have unique filenames and well-formed sha256`() {
        val models = ModelManager.BuiltInModel.entries
        assertEquals(models.size, models.map { it.filename }.toSet().size)
        for (m in models) {
            assertTrue(
                "${m.name} sha256 must be 64 hex chars",
                m.sha256.matches(Regex("[0-9a-f]{64}"))
            )
        }
    }

    // ---------- parseContentRange ----------

    @Test
    fun `parses standard content-range`() {
        assertEquals(
            ModelManager.ContentRange(first = 100, last = 999, total = 1000),
            ModelManager.parseContentRange("bytes 100-999/1000")
        )
    }

    @Test
    fun `parses content-range with unknown total`() {
        assertEquals(
            ModelManager.ContentRange(first = 100, last = 999, total = null),
            ModelManager.parseContentRange("bytes 100-999/*")
        )
    }

    @Test
    fun `parses unsatisfied-range form from a 416`() {
        assertEquals(
            ModelManager.ContentRange(first = null, last = null, total = 1000),
            ModelManager.parseContentRange("bytes */1000")
        )
    }

    @Test
    fun `content-range parsing ignores case and surrounding whitespace`() {
        assertEquals(
            ModelManager.ContentRange(first = 0, last = 9, total = 10),
            ModelManager.parseContentRange("  BYTES 0-9/10  ")
        )
    }

    @Test
    fun `rejects malformed content-range headers`() {
        assertNull(ModelManager.parseContentRange(null))
        assertNull(ModelManager.parseContentRange(""))
        assertNull(ModelManager.parseContentRange("garbage"))
        assertNull(ModelManager.parseContentRange("items 0-9/10"))
        assertNull(ModelManager.parseContentRange("bytes -5-9/10"))
        assertNull(ModelManager.parseContentRange("bytes 0-9"))
    }

    // ---------- resumeActionFor ----------

    @Test
    fun `206 starting at the resume offset appends with total from content-range`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 206,
            resumeOffset = 400,
            contentRangeHeader = "bytes 400-999/1000",
            contentLength = 600
        )
        assertEquals(ModelManager.ResumeAction.Append(total = 1000), action)
    }

    @Test
    fun `206 with unknown total derives it from offset plus content-length`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 206,
            resumeOffset = 400,
            contentRangeHeader = "bytes 400-999/*",
            contentLength = 600
        )
        assertEquals(ModelManager.ResumeAction.Append(total = 1000), action)
    }

    @Test
    fun `206 with no total and no content-length appends with unknown total`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 206,
            resumeOffset = 400,
            contentRangeHeader = "bytes 400-999/*",
            contentLength = -1
        )
        assertEquals(ModelManager.ResumeAction.Append(total = -1), action)
    }

    @Test
    fun `206 starting at the wrong offset restarts from scratch`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 206,
            resumeOffset = 400,
            contentRangeHeader = "bytes 0-999/1000",
            contentLength = 1000
        )
        assertEquals(ModelManager.ResumeAction.DiscardAndRestart, action)
    }

    @Test
    fun `206 without a usable content-range restarts from scratch`() {
        for (header in listOf(null, "", "garbage")) {
            assertEquals(
                "header '$header' must force a restart",
                ModelManager.ResumeAction.DiscardAndRestart,
                ModelManager.resumeActionFor(206, 400, header, 600)
            )
        }
    }

    @Test
    fun `200 means the server ignored the range and sends the full body`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 200,
            resumeOffset = 400,
            contentRangeHeader = null,
            contentLength = 1000
        )
        assertEquals(ModelManager.ResumeAction.FullBody, action)
    }

    @Test
    fun `416 with the part file already complete skips straight to verification`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 416,
            resumeOffset = 1000,
            contentRangeHeader = "bytes */1000",
            contentLength = -1
        )
        assertEquals(ModelManager.ResumeAction.AlreadyComplete, action)
    }

    @Test
    fun `416 with a different total restarts from scratch`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 416,
            resumeOffset = 1200,
            contentRangeHeader = "bytes */1000",
            contentLength = -1
        )
        assertEquals(ModelManager.ResumeAction.DiscardAndRestart, action)
    }

    @Test
    fun `416 without a content-range restarts from scratch`() {
        val action = ModelManager.resumeActionFor(
            responseCode = 416,
            resumeOffset = 400,
            contentRangeHeader = null,
            contentLength = -1
        )
        assertEquals(ModelManager.ResumeAction.DiscardAndRestart, action)
    }

    @Test
    fun `unexpected status codes fail the download`() {
        for (code in listOf(403, 500, 503)) {
            assertThrows(IOException::class.java) {
                ModelManager.resumeActionFor(code, 400, null, -1)
            }
        }
    }
}

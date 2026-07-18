package io.whispershare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for [WhisperEngine.planChunks]: chunk planning reads a plain
 * int16 PCM file and returns sample offsets, no native code involved.
 */
class WhisperEngineTest {

    private val rate = 16_000

    private fun pcmFile(samples: ShortArray): File {
        val f = File.createTempFile("plan_", ".pcm")
        f.deleteOnExit()
        f.outputStream().buffered().use { out ->
            for (s in samples) {
                out.write(s.toInt() and 0xFF)
                out.write((s.toInt() shr 8) and 0xFF)
            }
        }
        return f
    }

    /** Loud alternating signal with silent stretches at the given second ranges. */
    private fun signal(seconds: Double, silences: List<Pair<Double, Double>>): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            when {
                silences.any { t >= it.first && t < it.second } -> 0
                i % 2 == 0 -> 8_000
                else -> -8_000
            }
        }
    }

    @Test
    fun `short clip is a single chunk`() {
        val samples = signal(12.0, emptyList())
        val bounds = WhisperEngine.planChunks(pcmFile(samples), samples.size.toLong())
        assertEquals(2, bounds.size)
        assertEquals(0L, bounds[0])
        assertEquals(samples.size.toLong(), bounds[1])
    }

    @Test
    fun `long clip stays a single whisper call`() {
        // > 90 s: whisper's own ~30 s windows already stream; no file scan happens.
        val total = 100L * rate
        val bounds = WhisperEngine.planChunks(pcmFile(ShortArray(0)), total)
        assertEquals(2, bounds.size)
        assertEquals(total, bounds[1])
    }

    @Test
    fun `cuts land in silence and chunks stay bounded`() {
        val samples = signal(30.0, listOf(10.0 to 11.0, 23.0 to 24.0))
        val total = samples.size.toLong()
        val bounds = WhisperEngine.planChunks(pcmFile(samples), total)

        assertEquals(4, bounds.size)
        assertEquals(0L, bounds[0])
        assertEquals(total, bounds.last())
        for (i in 1 until bounds.size) {
            assertTrue("bounds must increase", bounds[i] > bounds[i - 1])
            assertTrue("chunk ${i - 1} too long", bounds[i] - bounds[i - 1] <= 16L * rate)
        }
        // Interior cuts sit inside the silent stretches, on 20 ms frame boundaries.
        assertTrue("cut 1 at ${bounds[1]}", bounds[1] in (10L * rate)..(11L * rate))
        assertTrue("cut 2 at ${bounds[2]}", bounds[2] in (23L * rate)..(24L * rate))
        assertEquals(0L, bounds[1] % 320)
        assertEquals(0L, bounds[2] % 320)
    }
}

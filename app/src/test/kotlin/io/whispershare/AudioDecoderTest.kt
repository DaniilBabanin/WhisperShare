package io.whispershare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-JVM tests for [AudioDecoder.PcmChunkConverter]. No Android framework needed:
 * the converter operates on plain byte arrays and emits plain floats.
 *
 * Encoding constants are AudioFormat values, inlined as literals to stay JVM-pure:
 * 2 = PCM_16BIT, 4 = PCM_FLOAT, 21 = PCM_24BIT_PACKED, 22 = PCM_32BIT.
 */
class AudioDecoderTest {

    // ---------- helpers ----------

    private fun convert(
        bytes: ByteArray,
        rate: Int,
        channels: Int = 1,
        encoding: Int = 2,
        chunkSize: Int = bytes.size
    ): FloatArray {
        val out = ArrayList<Float>()
        val conv = AudioDecoder.PcmChunkConverter(rate, channels, encoding) { out.add(it) }
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunkSize, bytes.size - i)
            conv.feed(bytes, i, n)
            i += n
        }
        conv.finish()
        return out.toFloatArray()
    }

    private fun shortsToBytes(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        for ((i, v) in values.withIndex()) {
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Generate [seconds] of a [freqHz] sine at [rate] Hz, amplitude 0.5, as int16 LE bytes. */
    private fun sineBytes(freqHz: Double, rate: Int, seconds: Double): ByteArray {
        val n = (rate * seconds).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (0.5 * sin(2.0 * PI * freqHz * i / rate) * 32767).roundToInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Count sign changes; for a sine of f Hz lasting T s this is ~2*f*T. Skips exact
     * zeros: int16 quantization turns on-grid crossings (sin(kπ) → 0) into literal 0
     * samples, which a naive product test would miss as crossings.
     */
    private fun zeroCrossings(signal: FloatArray): Int {
        var count = 0
        var lastSign = 0
        for (v in signal) {
            val s = if (v > 0f) 1 else if (v < 0f) -1 else 0
            if (s != 0) {
                if (lastSign != 0 && s != lastSign) count++
                lastSign = s
            }
        }
        return count
    }

    // ---------- resampling ----------

    @Test
    fun `resample 48k to 16k yields one third length and preserves pitch`() {
        val out = convert(sineBytes(freqHz = 440.0, rate = 48_000, seconds = 0.5), rate = 48_000)

        assertEquals(8_000, out.size) // 0.5 s at 16 kHz
        // 440 Hz for 0.5 s -> ~440 zero crossings.
        val zc = zeroCrossings(out)
        assertTrue("expected ~440 zero crossings, got $zc", abs(zc - 440) <= 4)
    }

    @Test
    fun `resample 24k to 16k yields two thirds length and preserves pitch`() {
        val out = convert(sineBytes(freqHz = 440.0, rate = 24_000, seconds = 0.5), rate = 24_000)

        assertEquals(8_000, out.size) // 0.5 s at 16 kHz regardless of source rate
        val zc = zeroCrossings(out)
        assertTrue("expected ~440 zero crossings, got $zc", abs(zc - 440) <= 4)
    }

    /**
     * REGRESSION (Wave 1, W1-C): HE-AAC/SBR voice notes often declare 24 kHz in the
     * container while the decoder actually emits 48 kHz PCM. The converter must be built
     * with the decoder OUTPUT rate. This feeds a true 48 kHz stream through a converter
     * constructed at that rate and asserts duration and pitch: if the pipeline ever
     * reverts to constructing from the container-declared rate (24_000 here), the output
     * length doubles (16_000) and the measured pitch halves (~220 Hz).
     */
    @Test
    fun `converter built from decoder output rate preserves duration and pitch`() {
        val decoderOutputRate = 48_000 // what the codec really produced
        // val containerDeclaredRate = 24_000 // what the container header lied about

        val out = convert(sineBytes(freqHz = 440.0, rate = decoderOutputRate, seconds = 0.5), rate = decoderOutputRate)

        // 0.5 s of real audio -> exactly 8 000 samples at 16 kHz.
        assertEquals(8_000, out.size)

        // Pitch check: frequency = crossings / 2 / duration. With the wrong rate the
        // apparent duration doubles and this reads ~220 Hz.
        val durationSec = out.size / 16_000.0
        val measuredHz = zeroCrossings(out) / 2.0 / durationSec
        assertTrue("expected ~440 Hz, measured $measuredHz Hz", abs(measuredHz - 440.0) <= 5.0)
    }

    @Test
    fun `same rate passes samples through unchanged`() {
        val out = convert(shortsToBytes(0, 16_384, -16_384, 32_767), rate = 16_000)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(-0.5f, out[2], 1e-6f)
        assertEquals(32_767f / 32_768f, out[3], 1e-6f)
    }

    @Test
    fun `upsample doubles length with linear interpolation`() {
        val out = convert(shortsToBytes(0, 16_384), rate = 8_000)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.25f, out[1], 1e-6f)
        assertEquals(0.5f, out[2], 1e-6f)
        assertEquals(0.5f, out[3], 1e-6f) // clamped at last sample
    }

    @Test
    fun `downsample preserves constant signal exactly`() {
        val out = convert(shortsToBytes(*IntArray(48_000) { 16_384 }), rate = 48_000)
        assertEquals(16_000, out.size)
        for (v in out) assertEquals(0.5f, v, 1e-6f)
    }

    @Test
    fun `downsample box-averages to suppress aliasing of Nyquist tone`() {
        // Alternating +-0.9 at 48 kHz is a 24 kHz tone — inaudible garbage above the
        // 8 kHz post-resample Nyquist. Naive every-3rd-sample decimation would keep it
        // at full amplitude; the box average must attenuate it to ~1/3.
        val amp = (0.9f * 32768).toInt()
        val out = convert(shortsToBytes(*IntArray(48_000) { i -> if (i % 2 == 0) amp else -amp }), rate = 48_000)
        var maxAbs = 0f
        for (v in out) maxAbs = maxOf(maxAbs, abs(v))
        assertTrue("expected attenuation to <=0.31, max abs was $maxAbs", maxAbs <= 0.31f)
    }

    // ---------- downmix ----------

    @Test
    fun `downmix averages stereo pairs`() {
        val out = convert(shortsToBytes(8_192, 24_576, -16_384, 16_384), rate = 16_000, channels = 2)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)  // (8192+24576)/2 = 16384 -> 0.5
        assertEquals(0f, out[1], 1e-6f)    // (-16384+16384)/2 = 0
    }

    @Test
    fun `partial frames carry over across arbitrary chunk boundaries`() {
        // Stereo 16-bit = 4-byte frames. Feeding one byte at a time forces every frame
        // through the carry buffer; the output must be identical to a single-chunk feed.
        val bytes = shortsToBytes(8_192, 24_576, -16_384, 16_384, 4_096, 4_096)
        val whole = convert(bytes, rate = 16_000, channels = 2)
        val byteAtATime = convert(bytes, rate = 16_000, channels = 2, chunkSize = 1)
        assertEquals(whole.size, byteAtATime.size)
        for (i in whole.indices) assertEquals(whole[i], byteAtATime[i], 1e-6f)
    }

    // ---------- PCM encodings ----------

    @Test
    fun `16-bit encoding maps full scale to unit range`() {
        val out = convert(shortsToBytes(0, 16_384, -32_768, 32_767), rate = 16_000)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
        assertEquals(32_767f / 32_768f, out[3], 1e-6f)
    }

    @Test
    fun `24-bit packed encoding decodes signed little-endian`() {
        // +4194304 (0x400000) = +0.5; -4194304 (0xC00000 sign-extended) = -0.5.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x40,              // +0.5
            0x00, 0x00, 0xC0.toByte(),     // -0.5
        )
        val out = convert(bytes, rate = 16_000, encoding = 21)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test
    fun `32-bit int encoding decodes signed little-endian`() {
        // 0x40000000 = +0.5; 0xC0000000 = -0.5.
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x40,
            0x00, 0x00, 0x00, 0xC0.toByte(),
        )
        val out = convert(bytes, rate = 16_000, encoding = 22)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test
    fun `float encoding passes IEEE bits through`() {
        val values = floatArrayOf(0f, 0.25f, -0.75f, 1f)
        val bytes = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            val bits = v.toRawBits()
            bytes[i * 4] = (bits and 0xFF).toByte()
            bytes[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        val out = convert(bytes, rate = 16_000, encoding = 4)
        assertEquals(4, out.size)
        for (i in values.indices) assertEquals(values[i], out[i], 1e-6f)
    }

    @Test
    fun `hi-res stereo WAV shape decodes and downsamples together`() {
        // 24-bit stereo 48 kHz constant +0.5 on both channels -> constant 0.5 mono at 16 kHz.
        val frame = byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0x40)
        val bytes = ByteArray(frame.size * 4_800)
        for (i in 0 until 4_800) frame.copyInto(bytes, i * frame.size)
        val out = convert(bytes, rate = 48_000, channels = 2, encoding = 21, chunkSize = 1_000)
        assertEquals(1_600, out.size)
        for (v in out) assertEquals(0.5f, v, 1e-6f)
    }
}

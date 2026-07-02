package io.whispershare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure-JVM tests for the DSP helpers in [AudioDecoder]. No Android framework needed:
 * resampleLinear / downmix / shortsToFloats operate on plain arrays.
 */
class AudioDecoderTest {

    // ---------- helpers ----------

    /** Generate [seconds] of a [freqHz] sine at [rate] Hz, amplitude 0.5. */
    private fun sine(freqHz: Double, rate: Int, seconds: Double): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i -> (0.5 * sin(2.0 * PI * freqHz * i / rate)).toFloat() }
    }

    /** Count sign changes; for a sine of f Hz lasting T s this is ~2*f*T. */
    private fun zeroCrossings(signal: FloatArray): Int {
        var count = 0
        for (i in 1 until signal.size) {
            if (signal[i - 1] * signal[i] < 0f) count++
        }
        return count
    }

    // ---------- resampleLinear ----------

    @Test
    fun `resample 48k to 16k yields one third length and preserves pitch`() {
        val src = sine(freqHz = 440.0, rate = 48_000, seconds = 0.5)
        val out = AudioDecoder.resampleLinear(src, 48_000, 16_000)

        assertEquals(8_000, out.size) // 0.5 s at 16 kHz
        // 440 Hz for 0.5 s -> ~440 zero crossings.
        val zc = zeroCrossings(out)
        assertTrue("expected ~440 zero crossings, got $zc", abs(zc - 440) <= 4)
    }

    @Test
    fun `resample 24k to 16k yields two thirds length and preserves pitch`() {
        val src = sine(freqHz = 440.0, rate = 24_000, seconds = 0.5)
        val out = AudioDecoder.resampleLinear(src, 24_000, 16_000)

        assertEquals(8_000, out.size) // 0.5 s at 16 kHz regardless of source rate
        val zc = zeroCrossings(out)
        assertTrue("expected ~440 zero crossings, got $zc", abs(zc - 440) <= 4)
    }

    /**
     * REGRESSION (Wave 1, W1-C): HE-AAC/SBR voice notes often declare 24 kHz in the
     * container while the decoder actually emits 48 kHz PCM. AudioDecoder must resample
     * from the decoder OUTPUT rate. This test feeds a true 48 kHz stream and asserts
     * duration and pitch of the correct-rate result: if the pipeline ever reverts to
     * the container-declared rate (24_000 here), the output length doubles (16_000)
     * and the measured pitch halves (~220 Hz), so both assertions below fail.
     */
    @Test
    fun `resample uses decoder output rate not container declared rate`() {
        val decoderOutputRate = 48_000 // what the codec really produced
        // val containerDeclaredRate = 24_000 // what the container header lied about

        val src = sine(freqHz = 440.0, rate = decoderOutputRate, seconds = 0.5)
        val out = AudioDecoder.resampleLinear(src, decoderOutputRate, 16_000)

        // 0.5 s of real audio -> exactly 8 000 samples at 16 kHz.
        // With the wrong (container) rate the length would be 16 000.
        assertEquals(8_000, out.size)

        // Pitch check: frequency = crossings / 2 / duration. With the wrong rate the
        // apparent duration doubles and this reads ~220 Hz.
        val durationSec = out.size / 16_000.0
        val measuredHz = zeroCrossings(out) / 2.0 / durationSec
        assertTrue("expected ~440 Hz, measured $measuredHz Hz", abs(measuredHz - 440.0) <= 5.0)
    }

    @Test
    fun `resample same rate returns input unchanged`() {
        val src = sine(freqHz = 200.0, rate = 16_000, seconds = 0.1)
        val out = AudioDecoder.resampleLinear(src, 16_000, 16_000)
        assertSame(src, out)
    }

    @Test
    fun `upsample doubles length with linear interpolation`() {
        val src = floatArrayOf(0f, 1f)
        val out = AudioDecoder.resampleLinear(src, 8_000, 16_000)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(1f, out[2], 1e-6f)
        assertEquals(1f, out[3], 1e-6f) // clamped at last sample
    }

    @Test
    fun `downsample preserves constant signal exactly`() {
        val src = FloatArray(48_000) { 0.5f }
        val out = AudioDecoder.resampleLinear(src, 48_000, 16_000)
        assertEquals(16_000, out.size)
        for (v in out) assertEquals(0.5f, v, 1e-6f)
    }

    @Test
    fun `downsample box-averages to suppress aliasing of Nyquist tone`() {
        // Alternating +-0.9 at 48 kHz is a 24 kHz tone — inaudible garbage above the
        // 8 kHz post-resample Nyquist. Naive every-3rd-sample decimation would keep it
        // at full amplitude; the box average must attenuate it to ~1/3.
        val src = FloatArray(48_000) { i -> if (i % 2 == 0) 0.9f else -0.9f }
        val out = AudioDecoder.resampleLinear(src, 48_000, 16_000)
        var maxAbs = 0f
        for (v in out) maxAbs = maxOf(maxAbs, abs(v))
        assertTrue("expected attenuation to <=0.31, max abs was $maxAbs", maxAbs <= 0.31f)
    }

    // ---------- downmix ----------

    @Test
    fun `downmix averages stereo pairs`() {
        val src = shortArrayOf(8_192, 24_576, -16_384, 16_384)
        val out = AudioDecoder.downmix(src, src.size, 2)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)  // (8192+24576)/2 = 16384 -> 0.5
        assertEquals(0f, out[1], 1e-6f)    // (-16384+16384)/2 = 0
    }

    @Test
    fun `downmix pads trailing partial frame by averaging available samples`() {
        // 3 samples of stereo = 1 full frame + 1 half frame. The lone trailing sample
        // must become its own frame (averaged over 1), not be dropped or crash.
        val src = shortArrayOf(8_192, 24_576, 16_384)
        val out = AudioDecoder.downmix(src, src.size, 2)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f) // 16384/1 -> 0.5
    }

    @Test
    fun `downmix honours logical size not backing array length`() {
        // PcmBuffer over-allocates; only the first `size` shorts are valid.
        val src = shortArrayOf(8_192, 8_192, 0, 0, 32_000, 32_000)
        val out = AudioDecoder.downmix(src, 4, 2)
        assertEquals(2, out.size)
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
    }

    // ---------- shortsToFloats ----------

    @Test
    fun `shortsToFloats maps full scale to unit range`() {
        val src = shortArrayOf(0, 16_384, Short.MIN_VALUE, Short.MAX_VALUE)
        val out = AudioDecoder.shortsToFloats(src, src.size)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
        assertEquals(32_767f / 32_768f, out[3], 1e-6f)
    }

    @Test
    fun `shortsToFloats honours logical size not backing array length`() {
        val src = shortArrayOf(16_384, -16_384, 9_999, 9_999)
        val out = AudioDecoder.shortsToFloats(src, 2)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
    }
}

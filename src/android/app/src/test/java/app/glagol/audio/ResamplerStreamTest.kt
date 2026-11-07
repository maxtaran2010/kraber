package app.glagol.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Потоковый ресемплер обязан давать те же отсчёты, что ресемплинг всего сигнала целиком. */
class ResamplerStreamTest {

    private fun signal(n: Int, rate: Int): FloatArray {
        val rnd = Random(1)
        return FloatArray(n) { i -> (0.5 * sin(2 * Math.PI * 440 * i / rate) + 0.1 * (rnd.nextFloat() - 0.5)).toFloat() }
    }

    private fun streamed(x: FloatArray, inRate: Int, outRate: Int, seed: Int): FloatArray {
        val out = ArrayList<Float>()
        val s = Resampler.Stream(inRate, outRate) { src, n -> for (i in 0 until n) out += src[i] }
        val rnd = Random(seed)
        var pos = 0
        while (pos < x.size) {
            val n = minOf(x.size - pos, 1 + rnd.nextInt(5000))
            s.push(x.copyOfRange(pos, pos + n), n)
            pos += n
        }
        s.finish()
        return out.toFloatArray()
    }

    private fun check(inRate: Int, outRate: Int) {
        val x = signal(inRate * 3 + 123, inRate)
        val ref = Resampler.resample(x, inRate, outRate)
        for (seed in 1..3) {
            val got = streamed(x, inRate, outRate, seed)
            assertEquals("length $inRate→$outRate", ref.size.toDouble(), got.size.toDouble(), 1.0)
            val n = minOf(ref.size, got.size)
            var maxErr = 0f
            for (i in 0 until n) maxErr = maxOf(maxErr, abs(ref[i] - got[i]))
            assertEquals("max error $inRate→$outRate", 0.0, maxErr.toDouble(), 1e-5)
        }
    }

    @Test fun from48k() = check(48000, 16000)
    @Test fun from44k() = check(44100, 16000)
    @Test fun from22k() = check(22050, 16000)
    @Test fun from8kUp() = check(8000, 16000)
    @Test fun oddRateGenericPath() = check(44111, 16000)
    @Test fun passthrough() = check(16000, 16000)
}

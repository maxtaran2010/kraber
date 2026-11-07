package app.glagol.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ресемплинг windowed-sinc (окно Блэкмана), ФНЧ на 0.95 от новой частоты Найквиста — без алиасинга.
 * Для «обычных» частот (44.1/48/22.05/8 кГц…) — полифазный фильтр: на каждую фазу заранее посчитаны
 * коэффициенты, во внутреннем цикле только умножение-сложение float.
 */
object Resampler {

    private const val ZERO_CROSSINGS = 12
    private const val MAX_PHASES = 2048

    fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || input.isEmpty()) return input
        val g = gcd(inRate, outRate)
        val up = outRate / g   // L — число фаз
        val down = inRate / g  // M — шаг по входу
        return if (up <= MAX_PHASES) polyphase(input, inRate, outRate, up, down) else generic(input, inRate, outRate)
    }

    private fun polyphase(input: FloatArray, inRate: Int, outRate: Int, up: Int, down: Int): FloatArray {
        val ratio = outRate.toDouble() / inRate
        val cutoff = min(1.0, ratio) * 0.95
        val half = ceil(ZERO_CROSSINGS / cutoff).toInt()
        val taps = 2 * half
        // Фаза p: выход в момент t = base + p/up (в отсчётах входа); коэффициенты для отсчётов base-half+1 .. base+half
        val bank = Array(up) { p ->
            val frac = p.toDouble() / up
            FloatArray(taps) { j -> kernel(abs(frac - (j - half + 1)), cutoff, half.toDouble()).toFloat() }
        }
        val outLen = floor(input.size * ratio).toInt()
        val out = FloatArray(outLen)
        val last = input.size - 1
        for (n in 0 until outLen) {
            val pos = n.toLong() * down
            val base = (pos / up).toInt()
            val h = bank[(pos % up).toInt()]
            val start = base - half + 1
            var acc = 0f
            if (start >= 0 && start + taps - 1 <= last) {
                for (j in 0 until taps) acc += input[start + j] * h[j]
            } else {
                for (j in 0 until taps) {
                    val k = start + j
                    if (k in 0..last) acc += input[k] * h[j]
                }
            }
            out[n] = acc
        }
        return out
    }

    /** Запасной путь для «странных» пар частот: табличное ядро с интерполяцией. */
    private fun generic(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val tableRes = 512
        val ratio = outRate.toDouble() / inRate
        val cutoff = min(1.0, ratio) * 0.95
        val halfWidth = ZERO_CROSSINGS / cutoff
        val tableSize = (halfWidth * tableRes).toInt() + 2
        val table = FloatArray(tableSize) { i -> kernel(i.toDouble() / tableRes, cutoff, halfWidth).toFloat() }
        val outLen = floor(input.size * ratio).toInt()
        val out = FloatArray(outLen)
        val last = input.size - 1
        for (n in 0 until outLen) {
            val t = n / ratio
            val lo = max(0, ceil(t - halfWidth).toInt())
            val hi = min(last, floor(t + halfWidth).toInt())
            var acc = 0f
            for (k in lo..hi) {
                val d = abs(t - k) * tableRes
                val i = d.toInt()
                if (i + 1 < tableSize) {
                    val frac = (d - i).toFloat()
                    acc += input[k] * (table[i] + (table[i + 1] - table[i]) * frac)
                }
            }
            out[n] = acc
        }
        return out
    }

    private fun kernel(x: Double, cutoff: Double, halfWidth: Double): Double {
        if (x >= halfWidth) return 0.0
        val sinc = if (x < 1e-9) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
        val w = x / halfWidth // 0..1
        val blackman = 0.42 + 0.5 * cos(PI * w) + 0.08 * cos(2 * PI * w)
        return cutoff * sinc * blackman
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /**
     * Потоковый вариант [resample]: вход подаётся кусками, на выходе — те же отсчёты, что дал бы
     * resample() на всём сигнале. Хранит только «хвост» входа длиной в фильтр — длинные файлы
     * (час и больше) не держатся в памяти на исходной частоте.
     */
    class Stream(inRate: Int, private val outRate: Int, private val sink: (FloatArray, Int) -> Unit) {
        private val passthrough = inRate == outRate
        private val up: Int
        private val down: Int
        private val ratio = outRate.toDouble() / inRate
        private val cutoff = min(1.0, ratio) * 0.95
        // полифазный путь
        private val half: Int
        private val bank: Array<FloatArray>?
        // запасной путь
        private val halfWidth = ZERO_CROSSINGS / cutoff
        private val tableRes = 512
        private val table: FloatArray?

        private var buf = FloatArray(1 shl 16)
        private var bufStart = 0L  // глобальный индекс buf[0]
        private var bufLen = 0
        private var next = 0L      // следующий выходной отсчёт
        private val out = FloatArray(4096)
        private var outN = 0

        init {
            val g = gcd(inRate, outRate)
            up = outRate / g
            down = inRate / g
            half = ceil(ZERO_CROSSINGS / cutoff).toInt()
            bank = if (!passthrough && up <= MAX_PHASES) Array(up) { p ->
                val frac = p.toDouble() / up
                FloatArray(2 * half) { j -> kernel(abs(frac - (j - half + 1)), cutoff, half.toDouble()).toFloat() }
            } else null
            table = if (!passthrough && bank == null) {
                FloatArray((halfWidth * tableRes).toInt() + 2) { i -> kernel(i.toDouble() / tableRes, cutoff, halfWidth).toFloat() }
            } else null
        }

        fun push(src: FloatArray, n: Int) {
            if (n <= 0) return
            if (passthrough) { sink(src, n); return }
            if (bufLen + n > buf.size) compact(n)
            System.arraycopy(src, 0, buf, bufLen, n)
            bufLen += n
            produce(final = false)
        }

        /** Конец входа: досчитывает последние отсчёты (за краем сигнала — нули, как в resample()). */
        fun finish() {
            if (passthrough) return
            produce(final = true)
            flushOut()
        }

        private fun produce(final: Boolean) {
            val total = bufStart + bufLen
            val outLen = if (final) {
                if (bank != null) total * up / down else floor(total * ratio).toLong()
            } else Long.MAX_VALUE
            while (next < outLen) {
                if (bank != null) {
                    val pos = next * down
                    val base = pos / up
                    if (!final && base + half >= total) break
                    val h = bank[(pos % up).toInt()]
                    val start = base - half + 1
                    var acc = 0f
                    val off = (start - bufStart).toInt()
                    if (off >= 0 && off + h.size <= bufLen) {
                        for (j in h.indices) acc += buf[off + j] * h[j]
                    } else {
                        for (j in h.indices) {
                            val k = off + j
                            if (k in 0 until bufLen) acc += buf[k] * h[j]
                        }
                    }
                    emit(acc)
                } else {
                    val t = next / ratio
                    val hiNeed = floor(t + halfWidth).toLong()
                    if (!final && hiNeed >= total) break
                    val lo = max(0L, ceil(t - halfWidth).toLong())
                    val hi = min(total - 1, hiNeed)
                    var acc = 0f
                    val tb = table!!
                    for (k in lo..hi) {
                        val d = abs(t - k) * tableRes
                        val i = d.toInt()
                        if (i + 1 < tb.size) {
                            val frac = (d - i).toFloat()
                            acc += buf[(k - bufStart).toInt()] * (tb[i] + (tb[i + 1] - tb[i]) * frac)
                        }
                    }
                    emit(acc)
                }
                next++
            }
        }

        /** Сдвигает буфер: оставляет только вход, нужный для ещё не посчитанных выходов. */
        private fun compact(incoming: Int) {
            val keepFrom = if (bank != null) next * down / up - half + 1 else ceil(next / ratio - halfWidth).toLong()
            val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
            if (drop > 0) {
                System.arraycopy(buf, drop, buf, 0, bufLen - drop)
                bufLen -= drop
                bufStart += drop
            }
            if (bufLen + incoming > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + incoming))
        }

        private fun emit(v: Float) {
            out[outN++] = v
            if (outN == out.size) flushOut()
        }

        private fun flushOut() {
            if (outN > 0) sink(out, outN)
            outN = 0
        }
    }
}

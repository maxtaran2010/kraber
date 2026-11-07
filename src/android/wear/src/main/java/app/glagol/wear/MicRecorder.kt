package app.glagol.wear

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Запись с микрофона часов в WAV 16 кГц mono PCM16 (урезанная копия телефонного AudioRecorder).
 * Каждые 50 мс: кусок звука в [onChunk], уровень в [levels], время в [elapsedMs].
 */
class MicRecorder {

    // SharedFlow, а не StateFlow: уровень нужен на каждый кусок, даже если он не изменился
    private val _levels = MutableSharedFlow<Float>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Уровень 0..1 каждые 50 мс (для анимации волны). */
    val levels: SharedFlow<Float> = _levels.asSharedFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** Сколько сэмплов записано; после [stop] — итог (столько же ушло в [onChunk]). */
    @Volatile var totalSamples = 0L
        private set

    /**
     * Каждый записанный кусок: PCM16LE, первые size байт. Вызывается в потоке записи, буфер переиспользуется —
     * копировать! Не должен блокировать.
     */
    @Volatile var onChunk: ((ByteArray, Int) -> Unit)? = null

    private var job: Job? = null

    /**
     * Начинает запись в [output]. Микрофон открывается сразу: недоступен — исключение.
     * Возвращённый Job завершается и сам — если микрофон отказал насовсем или кончился отладочный файл.
     */
    fun start(output: File, scope: CoroutineScope): Job {
        check(job?.isActive != true) { "запись уже идёт" }
        output.parentFile?.mkdirs()
        totalSamples = 0
        _elapsedMs.value = 0
        val debug = debugSource?.let { src -> debugSource = null; checkDebugSource(src) }
        val mic = if (debug == null) openMic() else null
        val j = scope.launch(Dispatchers.IO) {
            try {
                if (mic != null) recordMic(mic, output) else playWav(debug!!, output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "запись прервалась", e)
            }
        }
        job = j
        return j
    }

    /** Останавливает запись; после возврата WAV закрыт, заголовок исправлен, [onChunk] больше не зовётся. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    @SuppressLint("MissingPermission") // разрешение проверяет MainActivity до старта
    private fun openMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE), // ~0,5 с буфер
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Микрофон недоступен")
        }
        return rec
    }

    private suspend fun recordMic(first: AudioRecord, output: File) {
        val ctx = currentCoroutineContext()
        var rec: AudioRecord? = first
        try {
            Sink(output).use { sink ->
                val chunk = ShortArray(CHUNK)
                var zeroRun = 0L
                var reopens = 0
                var lastReopenAt = -10L * RATE
                first.startRecording()
                while (ctx.isActive) {
                    val r = rec ?: break
                    val n = r.read(chunk, 0, chunk.size)
                    if (n > 0) {
                        val peak = sink.emit(chunk, n)
                        zeroRun = if (peak <= 1) zeroRun + n else 0
                    }
                    // Поток «умер»: ошибка чтения или секунда точных нулей (живой микрофон так не звучит —
                    // даже тишина шумит). В эмуляторе HAL ловит I/O error и не оживает, пока поток не переоткрыть.
                    val stuck = zeroRun > RATE && sink.total - lastReopenAt > 3L * RATE && reopens < MAX_REOPENS
                    if (n < 0 || stuck) {
                        Log.w(TAG, "микрофон: ${if (n < 0) "ошибка $n" else "цифровая тишина"} на ${sink.total * 1000 / RATE} мс")
                        if (reopens >= MAX_REOPENS) break
                        val t0 = SystemClock.elapsedRealtime()
                        runCatching { r.stop() }
                        r.release()
                        rec = null
                        val fresh = runCatching { openMic().apply { startRecording() } }
                            .onFailure { Log.w(TAG, "микрофон не открылся заново", it) }
                            .getOrNull() ?: break
                        rec = fresh
                        reopens++
                        // Пропущенное время — тишиной, чтобы таймкоды не съехали
                        var gap = (SystemClock.elapsedRealtime() - t0) * RATE / 1000
                        chunk.fill(0)
                        while (gap > 0) {
                            val k = minOf(gap, CHUNK.toLong()).toInt()
                            sink.emit(chunk, k)
                            gap -= k
                        }
                        zeroRun = 0
                        lastReopenAt = sink.total
                    }
                }
            }
        } finally {
            rec?.let { runCatching { it.stop() }; it.release() }
        }
    }

    private class DebugWav(val file: File, val info: WavFile.Info)

    private fun checkDebugSource(src: File): DebugWav? {
        val info = runCatching { WavFile.info(src) }
            .onFailure { Log.w(TAG, "отладочный звук ${src.path} не читается — пишем с микрофона", it) }
            .getOrNull() ?: return null
        if (!info.isOurFormat) {
            Log.w(TAG, "отладочный звук ${src.path}: ${info.sampleRate} Гц, ${info.channels} кан., ${info.bitsPerSample} бит — нужен 16 кГц mono PCM16; пишем с микрофона")
            return null
        }
        return DebugWav(src, info)
    }

    /** Отладка: вместо микрофона «играем» WAV в реальном темпе. */
    private suspend fun playWav(src: DebugWav, output: File) {
        Log.i(TAG, "отладка: звук из ${src.file.path}, ${src.info.samples / RATE} с")
        val chunk = ShortArray(CHUNK)
        val raw = ByteArray(CHUNK * 2)
        FileInputStream(src.file).use { fis ->
            fis.channel.position(src.info.dataOffset)
            val inp = DataInputStream(BufferedInputStream(fis, 1 shl 16))
            Sink(output).use { sink ->
                val t0 = SystemClock.elapsedRealtime()
                var left = src.info.samples
                while (currentCoroutineContext().isActive && left > 0) {
                    val n = minOf(left, CHUNK.toLong()).toInt()
                    inp.readFully(raw, 0, n * 2)
                    for (i in 0 until n) {
                        chunk[i] = ((raw[2 * i].toInt() and 0xFF) or (raw[2 * i + 1].toInt() shl 8)).toShort()
                    }
                    sink.emit(chunk, n)
                    left -= n
                    val wait = t0 + sink.total * 1000 / RATE - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                }
            }
        }
    }

    /** WAV на диске (заголовок исправляется в [close]) + уровень, время и [onChunk] на каждый кусок. */
    private inner class Sink(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(WavFile.header(0))
        }
        private val bytes = ByteArray(CHUNK * 2)
        var total = 0L
            private set

        /** Пишет первые [n] ≤ CHUNK сэмплов из [pcm]; возвращает пик |s|. */
        fun emit(pcm: ShortArray, n: Int): Int {
            var sq = 0.0
            var peak = 0
            for (i in 0 until n) {
                val s = pcm[i].toInt()
                bytes[2 * i] = s.toByte()
                bytes[2 * i + 1] = (s shr 8).toByte()
                sq += s.toDouble() * s
                peak = maxOf(peak, abs(s))
            }
            raf.write(bytes, 0, n * 2)
            onChunk?.invoke(bytes, n * 2)
            total += n
            totalSamples = total
            // Мягкая логарифмическая шкала, чтобы тихая речь тоже была видна
            val rms = sqrt(sq / n) / 32768.0
            _levels.tryEmit((ln(1.0 + rms * 60.0) / ln(61.0)).toFloat().coerceIn(0f, 1f))
            _elapsedMs.value = total * 1000 / RATE
            return peak
        }

        override fun close() {
            try {
                raf.seek(0)
                raf.write(WavFile.header(total * 2))
            } finally {
                raf.close()
            }
        }
    }

    companion object {
        private const val TAG = "WatchMic"
        private const val RATE = WatchProtocol.SAMPLE_RATE
        /** 50 мс. */
        private const val CHUNK = RATE / 20
        /** Сколько раз за запись переоткрываем «замолчавший» микрофон. */
        private const val MAX_REOPENS = 50

        /** Отладка (`am start … --es live_wav <путь>`): следующий старт записи берёт звук из этого WAV. */
        @Volatile var debugSource: File? = null
    }
}

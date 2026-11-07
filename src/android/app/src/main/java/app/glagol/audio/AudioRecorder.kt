package app.glagol.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Запись с микрофона сразу в WAV 16 кГц mono PCM16 — без лишнего ресемплинга. */
class AudioRecorder {

    // SharedFlow, а не StateFlow: уровень нужен на каждый кусок, даже если он не изменился (тишина = 0, 0, 0…),
    // иначе волна на экране «замирает»
    private val _levels = MutableSharedFlow<Float>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Уровень 0..1 каждые 50 мс (для анимации волны). */
    val levels: SharedFlow<Float> = _levels.asSharedFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _noSignal = MutableStateFlow(false)
    /** Микрофон отдаёт цифровой ноль дольше 2 с: занят другим приложением, выключен или сломан мост в эмуляторе. */
    val noSignal: StateFlow<Boolean> = _noSignal.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Запись прервалась (микрофон отключился); то, что успели записать, сохранено. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var record: AudioRecord? = null
    private var file: File? = null

    /** Каждый записанный кусок (50 мс PCM16) — для живой расшифровки. Вызывается в потоке записи. */
    @Volatile var pcmSink: ((ShortArray, Int) -> Unit)? = null

    val isRecording: Boolean get() = job?.isActive == true

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord {
        val rate = WavIO.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, rate), // ~0.5 c буфер
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Микрофон недоступен")
        }
        return rec
    }

    fun start(output: File, scope: CoroutineScope) {
        if (isRecording) return
        debugSource?.let { src -> debugSource = null; startFromWav(src, output, scope); return }
        val rate = WavIO.SAMPLE_RATE
        record = openMic()
        file = output
        output.parentFile?.mkdirs()
        _elapsedMs.value = 0
        _noSignal.value = false
        _error.value = null
        job = scope.launch(Dispatchers.IO) {
            RandomAccessFile(output, "rw").use { raf ->
                raf.setLength(0)
                raf.write(WavIO.header(0))
                val chunk = ShortArray(rate / 20) // 50 мс
                val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                var total = 0L
                var zeroRun = 0L
                var reopens = 0
                var rec = record!!

                /** Пишет [n] сэмплов из [chunk] в файл, отдаёт живой расшифровке, считает уровень. */
                fun emit(n: Int) {
                    bytes.clear()
                    var sq = 0.0
                    var peak = 0
                    for (i in 0 until n) {
                        val s = chunk[i]
                        bytes.putShort(s)
                        val v = s / 32768.0
                        sq += v * v
                        peak = maxOf(peak, abs(s.toInt()))
                    }
                    raf.write(bytes.array(), 0, n * 2)
                    pcmSink?.invoke(chunk, n)
                    total += n
                    zeroRun = if (peak <= 1) zeroRun + n else 0
                    val rms = sqrt(sq / n).toFloat()
                    // Мягкая логарифмическая шкала, чтобы тихая речь тоже была видна
                    _levels.tryEmit((kotlin.math.ln(1f + rms * 60f) / kotlin.math.ln(61f)).coerceIn(0f, 1f))
                    _elapsedMs.value = total * 1000 / rate
                }

                /**
                 * Поток с микрофона «умер» (цифровые нули или ошибка чтения) — открываем микрофон заново.
                 * Так бывает в эмуляторе: HAL ловит I/O error и больше не восстанавливается, пока поток не переоткрыть.
                 * Пропущенное время заполняем тишиной, чтобы таймкоды не съехали.
                 */
                fun reopen(): Boolean {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    runCatching { rec.stop() }
                    rec.release()
                    val fresh = runCatching { openMic() }.getOrNull() ?: return false
                    rec = fresh
                    record = fresh
                    fresh.startRecording()
                    reopens++
                    var gap = (android.os.SystemClock.elapsedRealtime() - t0) * rate / 1000
                    java.util.Arrays.fill(chunk, 0)
                    while (gap > 0) {
                        val k = minOf(gap, chunk.size.toLong()).toInt()
                        emit(k)
                        gap -= k
                    }
                    zeroRun = 0
                    Log.i(TAG, "mic reopened (#$reopens) at ${total * 1000 / rate} ms")
                    return true
                }

                rec.startRecording()
                var lastReopenAt = -rate * 10L
                try {
                    while (isActive) {
                        val n = rec.read(chunk, 0, chunk.size)
                        if (n < 0) {
                            // ERROR_DEAD_OBJECT и т.п.: пробуем переоткрыть микрофон, иначе останавливаемся
                            Log.w(TAG, "AudioRecord.read error $n after ${total * 1000 / rate} ms")
                            if (reopens < MAX_REOPENS && reopen()) { lastReopenAt = total; continue }
                            _error.value = "Микрофон отключился — запись остановлена"
                            break
                        }
                        if (n == 0) continue
                        emit(n)
                        // Секунда точных нулей — живой микрофон так не звучит (даже тишина даёт шум): переоткрываем
                        if (zeroRun > rate && total - lastReopenAt > rate * 3 && reopens < MAX_REOPENS) {
                            if (reopen()) lastReopenAt = total
                        }
                        // Предупреждение — только если переоткрытие не помогло
                        val silent = zeroRun > rate * 2 || (zeroRun > rate && reopens >= MAX_REOPENS)
                        if (silent != _noSignal.value) {
                            Log.i(TAG, "no signal=$silent at ${total * 1000 / rate} ms")
                            _noSignal.value = silent
                        }
                    }
                } finally {
                    runCatching { rec.stop() }
                    val dataBytes = (total * 2).toInt()
                    raf.seek(0)
                    raf.write(WavIO.header(dataBytes))
                }
            }
        }
    }

    /** Останавливает запись и возвращает готовый WAV. */
    suspend fun stop(): File? {
        val j = job ?: return null
        j.cancel()
        j.join()
        withContext(Dispatchers.IO) { record?.release() }
        record = null
        job = null
        _noSignal.value = false
        return file
    }

    /** Отладка: вместо микрофона «играем» WAV в реальном темпе (проверка живого режима без звука в эмуляторе). */
    private fun startFromWav(src: File, output: File, scope: CoroutineScope) {
        val (raw, srcRate) = WavIO.read(src)
        val samples = if (srcRate == WavIO.SAMPLE_RATE) raw else Resampler.resample(raw, srcRate, WavIO.SAMPLE_RATE)
        file = output
        output.parentFile?.mkdirs()
        _elapsedMs.value = 0
        _noSignal.value = false
        _error.value = null
        Log.i(TAG, "debug source ${src.name}: ${samples.size / WavIO.SAMPLE_RATE} s")
        job = scope.launch(Dispatchers.IO) {
            RandomAccessFile(output, "rw").use { raf ->
                raf.setLength(0)
                raf.write(WavIO.header(0))
                val chunk = ShortArray(WavIO.SAMPLE_RATE / 20)
                val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 0
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    while (isActive && pos < samples.size) {
                        val n = minOf(chunk.size, samples.size - pos)
                        bytes.clear()
                        var sq = 0.0
                        for (i in 0 until n) {
                            val v = samples[pos + i].coerceIn(-1f, 1f)
                            chunk[i] = (v * 32767).toInt().toShort()
                            bytes.putShort(chunk[i])
                            sq += v * v
                        }
                        raf.write(bytes.array(), 0, n * 2)
                        pcmSink?.invoke(chunk, n)
                        pos += n
                        val rms = sqrt(sq / n).toFloat()
                        _levels.tryEmit((kotlin.math.ln(1f + rms * 60f) / kotlin.math.ln(61f)).coerceIn(0f, 1f))
                        _elapsedMs.value = pos * 1000L / WavIO.SAMPLE_RATE
                        val due = t0 + _elapsedMs.value
                        val wait = due - android.os.SystemClock.elapsedRealtime()
                        if (wait > 0) kotlinx.coroutines.delay(wait)
                    }
                } finally {
                    raf.seek(0)
                    raf.write(WavIO.header(pos * 2))
                }
            }
        }
    }

    companion object {
        private const val TAG = "Recorder"
        /** Сколько раз за запись переоткрываем «замолчавший» микрофон. */
        private const val MAX_REOPENS = 50

        /** Отладка (`am start … --es live_wav <путь>`): следующий старт записи берёт звук из этого файла. */
        @Volatile var debugSource: File? = null
    }
}

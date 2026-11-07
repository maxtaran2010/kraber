package app.glagol.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import app.glagol.MainActivity
import app.glagol.R
import app.glagol.audio.WavIO
import app.glagol.ml.AsrEngine
import app.glagol.ml.LiveTranscriber
import app.glagol.service.ProcessingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Приём записей с часов (протокол — [WatchProtocol]).
 *
 * LIVE: часы шлют звук по ходу записи — пишем WAV и сразу гоним его через [LiveTranscriber], живой экран
 * показывает текст. FILE: часы досылают целую запись (связь рвалась или телефона не было рядом).
 * После END и сверки числа сэмплов — обычная полная расшифровка ([ProcessingManager.startFromRecording]) и ACK часам.
 * Часы хранят файл до ACK, поэтому при любом обрыве частичный файл здесь просто удаляется — придёт целиком.
 */
class WatchHub(
    private val context: Context,
    private val processing: ProcessingManager,
    private val asr: AsrEngine,
) {
    /** Идущая сейчас живая запись с часов. [broken] — связь оборвалась (запись придёт файлом после «Стоп»). */
    data class Live(val sessionId: String, val startedAt: Long, val elapsedMs: Long = 0, val broken: Boolean = false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Громкость 0..1 — для волны на живом экране. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _transcript = MutableStateFlow(LiveTranscriber.State())
    /** Живой текст последней сессии (остаётся на экране, пока не открыта расшифровка). */
    val transcript: StateFlow<LiveTranscriber.State> = _transcript.asStateFlow()

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** id записи, поставленной в расшифровку после живой сессии. */
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)

    /** Новое соединение от часов (канал Data Layer или отладочный сокет). [close] вызывается по окончании. */
    fun accept(input: InputStream, output: OutputStream, close: () -> Unit) {
        scope.launch {
            try {
                session(
                    DataInputStream(BufferedInputStream(input, 1 shl 16)),
                    DataOutputStream(BufferedOutputStream(output)),
                )
            } catch (e: Exception) {
                Log.w(TAG, "session failed: $e")
            } finally {
                runCatching(close)
            }
        }
    }

    /** Живой экран закрыт после обрыва — сбрасываем состояние. */
    fun dismissBroken() {
        if (_live.value?.broken == true) _live.value = null
    }

    private suspend fun session(inp: DataInputStream, out: DataOutputStream) {
        val h = WatchProtocol.readHeader(inp)
        val sid = h.sessionId
        Log.i(TAG, "session $sid mode=${h.mode} rate=${h.sampleRate}")
        if (h.sampleRate != WatchProtocol.SAMPLE_RATE) throw java.io.IOException("частота ${h.sampleRate}")
        val buf = ByteArray(WatchProtocol.MAX_FRAME_BYTES)

        // Уже приняли (ACK потерялся по дороге) — дочитываем и подтверждаем без второй записи
        if (isDone(sid)) {
            while (WatchProtocol.readFrame(inp, buf) !is WatchProtocol.Frame.End) Unit
            WatchProtocol.writeReply(out, true, sid)
            Log.i(TAG, "session $sid already received — ACK")
            return
        }

        val live = h.mode == WatchProtocol.MODE_LIVE
        val dir = File(context.cacheDir, "watch").apply { mkdirs() }
        val wav = File(dir, "$sid.wav")
        var lt: LiveTranscriber? = null
        var ltJob: Job? = null
        var total = 0L
        var completed = false
        try {
            if (live) {
                lt = LiveTranscriber(context.assets, asr, scope).also { it.start() }
                _transcript.value = LiveTranscriber.State()
                ltJob = scope.launch { lt.state.collect { _transcript.value = it } }
                _live.value = Live(sid, h.startedAt)
                notifyLive(true)
            }
            var declared = -1L
            val shorts = ShortArray(WatchProtocol.MAX_FRAME_BYTES / 2)
            RandomAccessFile(wav, "rw").use { raf ->
                raf.setLength(0)
                raf.write(WavIO.header(0))
                while (true) {
                    when (val f = WatchProtocol.readFrame(inp, buf)) {
                        is WatchProtocol.Frame.Audio -> {
                            raf.write(f.pcm, 0, f.size)
                            val n = f.size / 2
                            total += n
                            if (lt != null) {
                                ByteBuffer.wrap(f.pcm, 0, f.size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, n)
                                lt.feed(shorts, n)
                                _level.value = level(shorts, n)
                                _live.value = _live.value?.copy(elapsedMs = total * 1000 / WatchProtocol.SAMPLE_RATE)
                            }
                        }
                        is WatchProtocol.Frame.End -> { declared = f.totalSamples; break }
                    }
                }
                raf.seek(0)
                raf.write(WavIO.header((total * 2).toInt()))
            }
            if (declared != total) {
                Log.w(TAG, "session $sid: got $total samples, watch says $declared — NACK")
                wav.delete()
                WatchProtocol.writeReply(out, false, sid)
                return
            }
            lt?.finish()
            val title = "С часов · " + TITLE_TIME.format(Instant.ofEpochMilli(h.startedAt).atZone(ZoneId.systemDefault()))
            val recId = processing.startFromRecording(wav, title, 0)
            markDone(sid)
            completed = true
            Log.i(TAG, "session $sid → $recId (${total / WatchProtocol.SAMPLE_RATE} s)")
            if (live) _finished.tryEmit(recId)
            runCatching { WatchProtocol.writeReply(out, true, sid) }
                .onFailure { Log.w(TAG, "ACK не дошёл ($sid) — часы пришлют файл ещё раз, ответим без дубля") }
        } finally {
            ltJob?.cancel()
            lt?.cancel()
            _level.value = 0f
            if (!completed) wav.delete()
            if (live) {
                notifyLive(false)
                val cur = _live.value
                if (cur?.sessionId == sid) _live.value = if (completed) null else cur.copy(broken = true)
            }
        }
    }

    private fun level(s: ShortArray, n: Int): Float {
        if (n == 0) return 0f
        var sq = 0.0
        for (i in 0 until n) { val v = s[i] / 32768.0; sq += v * v }
        val rms = sqrt(sq / n).toFloat()
        return (ln(1f + rms * 60f) / ln(61f)).coerceIn(0f, 1f)
    }

    // ---------------- Уже принятые сессии ----------------

    @Synchronized private fun isDone(sid: String) = sid in doneList()

    @Synchronized private fun markDone(sid: String) {
        val list = (doneList() + sid).takeLast(50)
        prefs.edit().putString(KEY_DONE, list.joinToString(",")).apply()
    }

    private fun doneList(): List<String> = prefs.getString(KEY_DONE, "").orEmpty().split(',').filter { it.isNotEmpty() }

    // ---------------- Уведомление ----------------

    private fun notifyLive(on: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!on) { nm.cancel(NOTIFICATION_ID); return }
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Запись с часов", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_WATCH_LIVE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Часы записывают")
            .setContentText("Нажмите — текст появляется вживую, на телефоне")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    companion object {
        private const val TAG = "WatchHub"
        private const val KEY_DONE = "done"
        private const val CHANNEL_ID = "watch"
        private const val NOTIFICATION_ID = 43
        private val TITLE_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))
    }
}

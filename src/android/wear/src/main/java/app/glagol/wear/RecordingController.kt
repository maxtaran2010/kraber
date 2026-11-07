package app.glagol.wear

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Что показывает экран часов. */
sealed interface UiState {
    /** Ждём тапа. [phoneNearby]: null — ещё не проверяли. [pending] — записей ждут отправки на телефон. */
    data class Idle(val phoneNearby: Boolean?, val pending: Int) : UiState

    data class Recording(val elapsedMs: Long, val level: Float, val link: LinkMode) : UiState

    /** [progress] 0..1; null — неизвестен (ждём ответа телефона). */
    data class Sending(val progress: Float?) : UiState

    /** Телефон подтвердил: запись у него и расшифровывается. */
    data object Sent : UiState

    /** Телефона нет — запись осталась на часах и уйдёт позже сама. */
    data object SavedOnWatch : UiState

    /** Короткое сообщение (нет микрофона, слишком короткая запись). */
    data class Message(val text: String) : UiState
}

/** Куда идёт звук во время записи. */
enum class LinkMode {
    /** Ищем телефон (первые секунды). */
    Connecting,
    /** Потоком на телефон — он расшифровывает на ходу. */
    Live,
    /** Только в файл на часах — отправим целиком после остановки. */
    Local,
}

/**
 * Запись на часах и доставка на телефон. Один на процесс: переживает пересоздание активити,
 * запись продолжается под [RecordService].
 *
 * Живой путь: каждый 50-мс кусок с первого сэмпла → очередь → канал ([WatchProtocol.MODE_LIVE]).
 * Любой сбой (переполнение очереди, ошибка записи в канал, NACK, тайм-аут) — живой путь мёртв,
 * и после остановки WAV уходит целиком ([WatchProtocol.MODE_FILE]). Не ушёл — лежит в `files/pending/`
 * и досылается при каждом открытии приложения и раз в 30 с, пока оно на экране.
 */
@SuppressLint("StaticFieldLeak") // хранится только applicationContext
object RecordingController {
    private const val TAG = "WatchRec"
    /** 2 минуты по 50 мс: столько звука переживёт живой путь, пока канал тормозит. */
    private const val QUEUE_CHUNKS = 2400
    private const val CHUNK_MS = 50L
    /** Короче 0,5 с — не запись, а случайный двойной тап. */
    private const val MIN_SAMPLES = WatchProtocol.SAMPLE_RATE / 2L
    private const val RESULT_SHOW_MS = 2_500L
    private const val SYNC_PERIOD_MS = 30_000L
    /** Живой путь после остановки: хвост очереди + END + ACK — не дольше 10 с + время на хвост. */
    private const val LIVE_REPLY_TIMEOUT_MS = 10_000L
    /** Отправка файла: столько без продвижения (запись кадров или ожидание ACK) — и обрываем. */
    private const val FILE_STALL_TIMEOUT_MS = 20_000L

    private lateinit var app: Context
    private lateinit var phone: PhoneLink
    private lateinit var haptics: Haptics

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = MicRecorder()

    private val _state = MutableStateFlow<UiState>(UiState.Idle(null, 0))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Текущая запись (от старта до конца отправки). Только main-поток. */
    private var session: Session? = null
    /** sid записей, которые фоновая досылка не трогает: идёт запись или её отправка. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var lastNearby: Boolean? = null
    @Volatile private var visible = false
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var syncRun: Job? = null

    private val pendingDir: File get() = File(app.filesDir, "pending")

    /** Вызывать из onCreate активити; повторные вызовы ничего не делают. */
    fun attach(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        phone = PhoneLink(app)
        haptics = Haptics(app)
        _state.value = idle()
        main.launch {
            recorder.levels.collect { lv ->
                _state.update { if (it is UiState.Recording) it.copy(level = lv, elapsedMs = recorder.elapsedMs.value) else it }
            }
        }
        main.launch { syncLoop() }
    }

    /** Активити на экране (onResume) / ушла (onPause). На экране — сразу пробуем дослать накопившееся. */
    fun setVisible(v: Boolean) {
        visible = v
        if (v) kick.trySend(Unit)
    }

    /** Короткое сообщение поверх ожидания (например, не дали доступ к микрофону). */
    fun showMessage(text: String) {
        if (_state.value is UiState.Idle) show(UiState.Message(text))
    }

    /** Старт записи. Разрешение на микрофон уже должно быть. */
    fun start() {
        if (session != null || _state.value !is UiState.Idle) return
        syncRun?.cancel() // фоновая досылка не должна делить канал с живым звуком
        val startedAt = System.currentTimeMillis()
        val sid = SessionId.new(startedAt)
        val wav = File(pendingDir, "$sid.wav")
        busy.add(sid)
        val live = LiveStream(sid, startedAt) { mode ->
            _state.update { if (it is UiState.Recording) it.copy(link = mode) else it }
        }
        // До старта микрофона: в живой поток должны попасть все сэмплы с самого первого
        recorder.onChunk = live::offer
        val job = try {
            recorder.start(wav, io)
        } catch (e: Exception) {
            Log.e(TAG, "микрофон не открылся", e)
            recorder.onChunk = null
            busy.remove(sid)
            wav.delete()
            show(UiState.Message("Микрофон недоступен"))
            return
        }
        RecordService.start(app)
        val s = Session(sid, wav, live)
        session = s
        _state.value = UiState.Recording(0, 0f, LinkMode.Connecting)
        haptics.start()
        live.startSending(io, phone)
        Log.i(TAG, "запись $sid начата")
        // Микрофон отказал насовсем или кончился отладочный файл — останавливаемся сами
        main.launch {
            job.join()
            if (session === s) stop()
        }
    }

    /** Стоп: WAV закрывается, затем доставка на телефон (живой путь или файлом). */
    fun stop() {
        val s = session ?: return
        if (s.stopping) return
        s.stopping = true
        main.launch {
            recorder.stop()
            recorder.onChunk = null
            RecordService.stop(app)
            haptics.stop()
            val total = recorder.totalSamples
            Log.i(TAG, "запись ${s.sid} остановлена: ${total * 1000 / WatchProtocol.SAMPLE_RATE} мс")
            val result = if (total < MIN_SAMPLES) {
                s.live.kill("слишком короткая запись")
                s.wav.delete()
                UiState.Message("Слишком короткая запись")
            } else {
                _state.value = UiState.Sending(null)
                val progress: (Float) -> Unit = { p ->
                    _state.update { if (it is UiState.Sending) UiState.Sending(p) else it }
                }
                var ok = s.live.finish(total, progress)
                if (!ok) {
                    _state.value = UiState.Sending(0f)
                    ok = sendFile(s.wav, progress) == Transfer.OK
                }
                if (ok) {
                    s.wav.delete()
                    haptics.success()
                    UiState.Sent
                } else {
                    UiState.SavedOnWatch
                }
            }
            busy.remove(s.sid)
            session = null
            show(result)
        }
    }

    /** Показать итог и через [RESULT_SHOW_MS] вернуться к ожиданию (если пользователь не начал новую запись). */
    private fun show(st: UiState) {
        _state.value = st
        main.launch {
            delay(RESULT_SHOW_MS)
            if (_state.value == st) {
                _state.value = idle()
                kick.trySend(Unit)
            }
        }
    }

    private fun idle() = UiState.Idle(lastNearby, pendingFiles().size)

    private fun publishIdle() = _state.update { if (it is UiState.Idle) idle() else it }

    private fun pendingFiles(): List<File> =
        pendingDir.listFiles { f -> f.isFile && f.name.endsWith(".wav") && f.nameWithoutExtension !in busy }
            ?.sortedBy { it.name }
            .orEmpty()

    // ---------- Досылка накопившегося ----------

    private suspend fun syncLoop() {
        while (true) {
            withTimeoutOrNull(SYNC_PERIOD_MS) { kick.receive() }
            if (!visible || _state.value !is UiState.Idle) continue
            val run = main.launch { syncPending() }
            syncRun = run
            run.join()
            syncRun = null
        }
    }

    private suspend fun syncPending() {
        val near = phone.phoneNearby()
        lastNearby = near
        publishIdle()
        if (!near) return
        for (f in pendingFiles()) {
            if (_state.value !is UiState.Idle) return
            val sid = f.nameWithoutExtension
            if (!busy.add(sid)) continue
            try {
                when (sendFile(f, progress = null)) {
                    Transfer.OK -> f.delete()
                    Transfer.BAD_FILE -> {
                        Log.w(TAG, "${f.name}: пустой или битый файл — удаляем")
                        f.delete()
                    }
                    Transfer.REJECTED -> {} // телефон ответил NACK — следующий файл, этот попробуем позже
                    Transfer.FAILED -> return // телефон пропал — до следующего раза
                }
            } finally {
                busy.remove(sid)
            }
            publishIdle()
        }
    }

    // ---------- Отправка файлом ----------

    private enum class Transfer { OK, REJECTED, FAILED, BAD_FILE }

    /** Отправляет WAV целиком (MODE_FILE). [progress] 0..1 — для экрана отправки (null — фоновая досылка). */
    private suspend fun sendFile(wav: File, progress: ((Float) -> Unit)?): Transfer {
        val sid = wav.nameWithoutExtension
        val info = withContext(Dispatchers.IO) { runCatching { WavFile.info(wav) }.getOrNull() }
        if (info == null || !info.isOurFormat || info.samples < MIN_SAMPLES) return Transfer.BAD_FILE
        val startedAt = SessionId.startedAt(sid)
            ?: (wav.lastModified() - info.samples * 1000 / WatchProtocol.SAMPLE_RATE)
        val header = WatchProtocol.Header(sid, WatchProtocol.SAMPLE_RATE, startedAt, WatchProtocol.MODE_FILE)
        val link = phone.open() ?: return Transfer.FAILED
        var shownPct = -1
        val acked = guarded(link, FILE_STALL_TIMEOUT_MS) { touch ->
            val out = DataOutputStream(BufferedOutputStream(link.output, 64 * 1024))
            FileFraming.writeFileStream(wav, out, header) { sent, total ->
                touch()
                if (progress != null) {
                    val pct = (sent * 100 / total).toInt()
                    if (pct != shownPct) {
                        shownPct = pct
                        progress(sent.toFloat() / total)
                    }
                }
            }
            touch() // END ушёл — ждём ответ
            WatchProtocol.readReply(DataInputStream(link.input), sid)
        }
        Log.i(TAG, "файл $sid (${info.samples / WatchProtocol.SAMPLE_RATE} с): ${when (acked) { true -> "ACK"; false -> "NACK"; null -> "сбой" }}")
        return when (acked) {
            true -> Transfer.OK
            false -> Transfer.REJECTED
            null -> Transfer.FAILED
        }
    }

    /**
     * Блокирующий обмен [block] с телефоном в IO-потоке. Если [stallMs] нет продвижения (block зовёт touch),
     * закрываем линк — это будит зависшие чтение/запись — и выходим, не дожидаясь потока.
     * null — сбой или тайм-аут. Линк закрывается в любом случае (и при отмене).
     */
    private suspend fun <T : Any> guarded(link: PhoneLink.Link, stallMs: Long, block: (touch: () -> Unit) -> T): T? {
        val last = AtomicLong(SystemClock.elapsedRealtime())
        val task = io.async { runCatching { block { last.set(SystemClock.elapsedRealtime()) } } }
        try {
            while (true) {
                val r = withTimeoutOrNull(250) { task.await() }
                if (r != null) return r.onFailure { Log.w(TAG, "обмен с телефоном: $it") }.getOrNull()
                if (SystemClock.elapsedRealtime() - last.get() > stallMs) {
                    Log.w(TAG, "телефон молчит дольше $stallMs мс — обрываем")
                    return null
                }
            }
        } finally {
            link.close()
            task.cancel()
        }
    }

    // ---------- Живой путь ----------

    private class Session(val sid: String, val wav: File, val live: LiveStream) {
        var stopping = false
    }

    /**
     * Живая передача одной записи: куски с первого сэмпла → очередь → канал к телефону.
     * Поток валиден, только если в нём все сэмплы без пропусков (телефон их считает и иначе ответит NACK),
     * поэтому любой сбой убивает живой путь насовсем ([kill]).
     */
    private class LiveStream(
        private val sid: String,
        private val startedAt: Long,
        private val onMode: (LinkMode) -> Unit,
    ) {
        private val queue = Channel<ByteArray>(QUEUE_CHUNKS)
        private val dead = AtomicBoolean(false)
        @Volatile private var link: PhoneLink.Link? = null
        private val result = CompletableDeferred<Boolean>()
        private val queued = AtomicLong()
        private val sent = AtomicLong()
        /** Сколько сэмплов записано — выставляется в [finish] перед закрытием очереди. */
        @Volatile private var expected = -1L

        /** Кусок из потока записи. Никогда не блокирует. */
        fun offer(pcm: ByteArray, size: Int) {
            if (dead.get()) return
            queued.incrementAndGet()
            if (!queue.trySend(pcm.copyOf(size)).isSuccess) kill("очередь переполнена — канал не успевает")
        }

        fun kill(why: String) {
            if (!dead.compareAndSet(false, true)) return
            Log.w(TAG, "живая передача $sid отключена: $why")
            queue.cancel()
            link?.close?.invoke()
            result.complete(false)
            onMode(LinkMode.Local)
        }

        /** Отправитель: ищет телефон (до ~5 с, куски копятся в очереди), затем гонит очередь в канал. */
        fun startSending(scope: CoroutineScope, phone: PhoneLink) = scope.launch sender@{
            val l = phone.open()
            if (l == null) {
                kill("телефон не найден")
                return@sender
            }
            link = l
            if (dead.get()) {
                l.close()
                return@sender
            }
            onMode(LinkMode.Live)
            try {
                val out = DataOutputStream(BufferedOutputStream(l.output, 16 * 1024))
                WatchProtocol.writeHeader(out, WatchProtocol.Header(sid, WatchProtocol.SAMPLE_RATE, startedAt, WatchProtocol.MODE_LIVE))
                out.flush()
                var samples = 0L
                var frames = 0
                while (true) {
                    // Догнали микрофон — выталкиваем буфер, чтобы телефон получал звук без задержки
                    val c = queue.tryReceive().getOrNull()
                        ?: run { out.flush(); queue.receiveCatching().getOrNull() }
                        ?: break
                    WatchProtocol.writeAudio(out, c)
                    samples += c.size / 2
                    sent.incrementAndGet()
                    if (++frames % 16 == 0) out.flush()
                }
                // Очередь закрыта: либо запись остановлена ([finish]), либо живой путь убит ([kill])
                if (dead.get()) return@sender
                if (samples != expected) throw IOException("в потоке $samples сэмплов, а записано $expected")
                WatchProtocol.writeEnd(out, samples)
                out.flush()
                val ok = WatchProtocol.readReply(DataInputStream(l.input), sid)
                Log.i(TAG, "живая передача $sid: ${if (ok) "ACK" else "NACK"}, $samples сэмплов")
                result.complete(ok)
            } catch (e: Exception) {
                kill("ошибка канала: $e")
            } finally {
                l.close()
            }
        }

        /**
         * Запись остановлена, [totalSamples] — итог. Досылаем хвост очереди, END, ждём ACK
         * (не дольше 10 с + время на хвост). true — телефон получил всё.
         */
        suspend fun finish(totalSamples: Long, onProgress: (Float) -> Unit): Boolean {
            if (dead.get()) return false
            expected = totalSamples
            queue.close()
            val sentAtStop = sent.get()
            val backlog = (queued.get() - sentAtStop).coerceAtLeast(0)
            val deadline = SystemClock.elapsedRealtime() + LIVE_REPLY_TIMEOUT_MS + backlog * CHUNK_MS
            while (true) {
                val r = withTimeoutOrNull(200) { result.await() }
                if (r != null) return r
                if (backlog > 0) onProgress(((sent.get() - sentAtStop).toFloat() / backlog).coerceIn(0f, 1f))
                if (SystemClock.elapsedRealtime() > deadline) {
                    kill("телефон не ответил вовремя")
                    return false
                }
            }
        }
    }
}

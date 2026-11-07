package app.glagol.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Проигрывание записи (audio.wav) с плавной позицией для подсветки слов.
 * MediaPlayer отдаёт позицию рывками — между обновлениями экстраполируем по часам.
 * Все методы — с главного потока.
 */
class Player(private val scope: CoroutineScope, private val file: () -> File) {

    private var mp: MediaPlayer? = null
    private var ticker: Job? = null
    private var stopAt: Long? = null

    private val _position = MutableStateFlow(0L)
    /** Текущая позиция, мс (обновляется ~60 раз в секунду во время проигрывания). */
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _started = MutableStateFlow(false)
    /** Проигрывание хоть раз запускали — показываем подсветку и позицию. */
    val started: StateFlow<Boolean> = _started.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    // Экстраполяция позиции между «рывками» MediaPlayer
    private var lastRaw = -1L
    private var lastRawAt = 0L
    /** Идёт перемотка: старой позиции плеера не верим, пока не придёт onSeekComplete. */
    private var seeking = false

    private fun ensure(): MediaPlayer? = mp ?: runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file().absolutePath)
            prepare()
            setOnSeekCompleteListener { seeking = false; resetClock() }
            setOnCompletionListener {
                _position.value = _duration.value
                setPlaying(false)
            }
            _duration.value = duration.toLong().coerceAtLeast(0)
        }
    }.onFailure { Log.w(TAG, "player init failed: $it") }.getOrNull()?.also { mp = it }

    /** Играть с [fromMs]; [stopAtMs] — остановиться в этой точке (режим «только фраза»). */
    fun play(fromMs: Long, stopAtMs: Long? = null) {
        val p = ensure() ?: return
        stopAt = stopAtMs
        val target = fromMs.coerceIn(0, (_duration.value - 1).coerceAtLeast(0))
        seeking = true
        p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        _position.value = target
        resetClock()
        p.start()
        _started.value = true
        setPlaying(true)
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        setPlaying(false)
    }

    fun resume() {
        val p = ensure() ?: return
        if (_position.value >= _duration.value - 30) { play(0); return }
        stopAt?.let { if (_position.value >= it - 30) stopAt = null }
        resetClock()
        p.start()
        _started.value = true
        setPlaying(true)
    }

    fun toggle() = if (_playing.value) pause() else resume()

    /** Перемотка без смены состояния «играет/пауза». */
    fun seek(ms: Long) {
        val p = ensure() ?: return
        val target = ms.coerceIn(0, (_duration.value - 1).coerceAtLeast(0))
        stopAt = null
        seeking = true
        p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        _position.value = target
        _started.value = true
        resetClock()
    }

    /** Отключить остановку в конце фразы (переключили режим на «дальше»). */
    fun clearStopAt() { stopAt = null }

    fun release() {
        ticker?.cancel()
        runCatching { mp?.release() }
        mp = null
        _playing.value = false
        _started.value = false
        _position.value = 0
    }

    private fun resetClock() {
        lastRaw = -1
        lastRawAt = SystemClock.elapsedRealtime()
    }

    private fun setPlaying(on: Boolean) {
        _playing.value = on
        ticker?.cancel()
        if (!on) return
        ticker = scope.launch {
            while (isActive) {
                val p = mp ?: break
                val now = SystemClock.elapsedRealtime()
                if (!seeking) {
                    val raw = runCatching { p.currentPosition.toLong() }.getOrDefault(_position.value)
                    if (raw != lastRaw) { lastRaw = raw; lastRawAt = now }
                    // Экстраполяция не дальше 250 мс от последнего значения плеера и только вперёд
                    val est = raw + (now - lastRawAt).coerceAtMost(250)
                    if (est > _position.value || _position.value - est > 400) _position.value = est
                }
                val stop = stopAt
                if (stop != null && _position.value >= stop) {
                    stopAt = null
                    pause()
                    break
                }
                delay(16)
            }
        }
    }

    private companion object { const val TAG = "Player" }
}

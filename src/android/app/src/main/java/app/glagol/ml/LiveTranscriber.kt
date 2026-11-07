package app.glagol.ml

import android.content.res.AssetManager
import android.util.Log
import app.glagol.data.Utterance
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Живая расшифровка во время записи — предпросмотр, всё на устройстве:
 *  - потоковый VAD режет речь на куски по паузам;
 *  - каждый кусок сразу распознаётся ASR (тот же GigaAM), тире модели — смена реплики;
 *  - спикер — онлайн-кластеризация голосовых эмбеддингов (CAM++): ближайший центроид или новый голос;
 *  - имена — [SpeakerNamer] по уже распознанному тексту.
 * Итоговая расшифровка после остановки всё равно делается полным пайплайном (точнее, с глобальной кластеризацией).
 */
class LiveTranscriber(
    private val assets: AssetManager,
    private val asr: AsrEngine,
    private val scope: CoroutineScope,
) {
    data class State(
        val utterances: List<Utterance> = emptyList(),
        /** Сейчас звучит речь, текст ещё распознаётся. */
        val speaking: Boolean = false,
        val names: Map<String, String> = emptyMap(),
        /** Модели ещё загружаются (первые секунды). */
        val warmingUp: Boolean = true,
        /** Черновик текущей фразы — распознаётся по ходу речи, заменяется итоговым текстом после паузы. */
        val partial: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val input = Channel<FloatArray>(Channel.UNLIMITED)
    private var job: Job? = null
    /** Сколько сэмплов пришло, но ещё не обработано (отставание от реального времени). */
    private val backlog = java.util.concurrent.atomic.AtomicLong(0)

    // Последние 30 с звука — для черновика текущей фразы
    private val ring = FloatArray(SR * 30)
    private var written = 0L
    private var speechStart = -1L
    private var lastPartialAt = 0L

    // Онлайн-кластеры голосов: сумма нормированных эмбеддингов с весом по длительности
    private val sums = ArrayList<FloatArray>()
    private val cents = ArrayList<FloatArray>()
    private var lastSpeaker = -1

    private class LiveUtt(val spk: Int, val start: Float, var end: Float, val text: StringBuilder)
    private val utts = ArrayList<LiveUtt>()

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) { loop() }
    }

    /** Кусок с микрофона (PCM16). Неблокирующий — вызывается из потока записи. */
    fun feed(pcm: ShortArray, n: Int) {
        backlog.addAndGet(n.toLong())
        input.trySend(FloatArray(n) { pcm[it] / 32768f })
    }

    /** Запись остановлена: дораспознать то, что уже пришло. */
    fun finish() {
        input.close()
    }

    fun cancel() {
        input.close()
        job?.cancel()
    }

    private suspend fun loop() {
        val t0 = System.currentTimeMillis()
        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ModelPaths.VAD,
                    threshold = 0.45f,
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.2f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 8f,
                ),
                sampleRate = SR,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        val extractor = SpeakerEmbeddingExtractor(
            assets,
            SpeakerEmbeddingExtractorConfig(model = ModelPaths.EMBEDDING, numThreads = 1, debug = false, provider = "cpu"),
        )
        try {
            asr.ensureLoaded()
            _state.update { it.copy(warmingUp = false) }
            Log.i(TAG, "live ready in ${System.currentTimeMillis() - t0} ms")
            val buf = FloatArray(WINDOW)
            var fill = 0
            fun drain() {
                while (!vad.empty()) {
                    val seg = vad.front()
                    vad.pop()
                    runCatching { onSegment(seg.start.toLong(), seg.samples, extractor) }
                        .onFailure { Log.w(TAG, "segment failed: $it") }
                }
            }
            for (chunk in input) {
                backlog.addAndGet(-chunk.size.toLong())
                var i = 0
                while (i < chunk.size) {
                    val k = min(WINDOW - fill, chunk.size - i)
                    System.arraycopy(chunk, i, buf, fill, k)
                    fill += k
                    i += k
                    if (fill == WINDOW) {
                        vad.acceptWaveform(buf.copyOf())
                        for (k2 in 0 until WINDOW) ring[((written + k2) % ring.size).toInt()] = buf[k2]
                        written += WINDOW
                        fill = 0
                        val speaking = vad.isSpeechDetected()
                        if (speaking != _state.value.speaking) {
                            if (speaking) { speechStart = (written - WINDOW - PRE_ROLL).coerceAtLeast(0); lastPartialAt = written }
                            _state.update { it.copy(speaking = speaking) }
                        }
                        drain()
                        if (speaking) maybePartial()
                    }
                }
            }
            vad.flush()
            drain()
            _state.update { it.copy(speaking = false) }
        } finally {
            vad.release()
            extractor.release()
        }
    }

    /**
     * Пока человек говорит, раз в ~2 с распознаём текущую фразу (последние ≤ 10 с) — текст появляется по ходу речи.
     * Только если не отстаём от реального времени: иначе важнее догнать итоговые куски.
     */
    private fun maybePartial() {
        if (speechStart < 0 || written - lastPartialAt < PARTIAL_EVERY || written - speechStart < SR) return
        if (backlog.get() > SR / 2) return
        lastPartialAt = written
        val from = maxOf(speechStart, written - PARTIAL_MAX, written - ring.size)
        val n = (written - from).toInt()
        val audio = FloatArray(n) { ring[((from + it) % ring.size).toInt()] }
        val text = runCatching { asr.transcribe(audio).text }.getOrDefault("")
            .replace(Regex("^[\\s—–-]+"), "").replace(Regex("\\s+[—–]\\s+"), " ").trim()
        if (text.isNotEmpty()) _state.update { it.copy(partial = text) }
    }

    private fun onSegment(startSample: Long, samples: FloatArray, extractor: SpeakerEmbeddingExtractor) {
        if (samples.size < SR / 5) return
        val segStart = startSample.toFloat() / SR
        val segEnd = segStart + samples.size.toFloat() / SR
        val res = asr.transcribe(samples)
        val words = WordAligner.fromAsr(res, segStart, segEnd)
        if (words.isEmpty()) return

        // Тире GigaAM внутри куска — смена реплики: каждую часть относим к спикеру отдельно
        val parts = ArrayList<List<Word>>()
        var from = 0
        for (i in 1 until words.size) if (words[i].turnStart) { parts += words.subList(from, i); from = i }
        parts += words.subList(from, words.size)

        for (part in parts) {
            val ps = max(segStart, part.first().start - 0.05f)
            val pe = min(segEnd, part.last().end + 0.1f)
            val spk = speakerFor(samples, segStart, ps, pe, extractor)
            lastSpeaker = spk
            val text = part.joinToString(" ") { it.text }
            val last = utts.lastOrNull()
            if (last != null && last.spk == spk && ps - last.end < JOIN_GAP) {
                last.text.append(' ').append(text)
                last.end = max(last.end, pe)
            } else {
                utts += LiveUtt(spk, ps, pe, StringBuilder(text))
            }
        }
        val list = utts.map {
            Utterance("speaker_${it.spk + 1}", (it.start * 1000).roundToLong(), (it.end * 1000).roundToLong(), it.text.toString())
        }
        val names = SpeakerNamer.detect(list)
        _state.update { it.copy(utterances = list, names = names, partial = "") }
    }

    /** Онлайн-кластеризация: ближайший голос, новый голос или (для коротких кусков) предыдущий говорящий. */
    private fun speakerFor(samples: FloatArray, segStart: Float, s: Float, e: Float, extractor: SpeakerEmbeddingExtractor): Int {
        val dur = e - s
        if (dur < MIN_EMB) return max(lastSpeaker, 0)
        val a = ((s - segStart) * SR).toInt().coerceIn(0, samples.size)
        val b = ((e - segStart) * SR).toInt().coerceIn(a, samples.size)
        val emb = run {
            val stream = extractor.createStream()
            try {
                stream.acceptWaveform(samples.copyOfRange(a, b), SR)
                stream.inputFinished()
                normalize(extractor.compute(stream))
            } finally {
                stream.release()
            }
        }
        var best = -1
        var bestSim = -1f
        cents.forEachIndexed { i, c -> val d = dot(emb, c); if (d > bestSim) { bestSim = d; best = i } }
        val spk = when {
            best >= 0 && bestSim >= SAME -> best
            (best < 0 || dur >= NEW_MIN_SEC) && cents.size < MAX_SPEAKERS -> {
                sums += FloatArray(emb.size); cents += emb; cents.size - 1
            }
            best >= 0 && bestSim >= WEAK -> best
            else -> max(lastSpeaker, 0)
        }
        // Уточняем центроид голоса
        val sum = sums[spk]
        for (d in sum.indices) sum[d] += emb[d] * dur
        cents[spk] = normalize(sum)
        Log.d(TAG, "part ${"%.1f".format(s)}–${"%.1f".format(e)} s → $spk (sim ${"%.2f".format(bestSim)})")
        return spk
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var x = 0f
        for (i in a.indices) x += a[i] * b[i]
        return x
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val inv = 1f / (sqrt(n) + 1e-9f)
        return FloatArray(v.size) { v[it] * inv }
    }

    private companion object {
        const val TAG = "Live"
        const val SR = 16_000
        const val WINDOW = 512
        /** Запас перед началом речи для черновика, сэмплов. */
        const val PRE_ROLL = SR * 3 / 10L
        /** Черновик обновляется не чаще, сэмплов (2 с), и берёт не больше последних 10 с. */
        const val PARTIAL_EVERY = SR * 2L
        const val PARTIAL_MAX = SR * 10L
        /** Тот же голос, если близость к центроиду не ниже. */
        const val SAME = 0.45f
        /** Слабое сходство: если новый голос заводить нельзя — всё же к ближайшему. */
        const val WEAK = 0.25f
        /** Новый голос заводим только по куску не короче, с. */
        const val NEW_MIN_SEC = 1.0f
        /** Кусок короче — эмбеддинг ненадёжен, берём предыдущего говорящего. */
        const val MIN_EMB = 0.5f
        const val MAX_SPEAKERS = 6
        /** Пауза, после которой у того же спикера начинается новая реплика, с. */
        const val JOIN_GAP = 1.5f
    }
}

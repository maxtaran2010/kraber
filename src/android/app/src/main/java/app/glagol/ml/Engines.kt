package app.glagol.ml

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Пути к моделям внутри APK (assets/). Скачиваются scripts/download_models.sh. */
object ModelPaths {
    const val ASR_ENCODER = "models/asr/encoder.onnx"
    const val ASR_DECODER = "models/asr/decoder.onnx"
    const val ASR_JOINER = "models/asr/joiner.onnx"
    const val ASR_TOKENS = "models/asr/tokens.txt"
    const val EMBEDDING = "models/diarization/embedding.onnx"
    const val VAD = "models/vad/silero_vad.onnx"

    const val VAD_NAME = "Silero VAD v5"
    const val ASR_NAME = "GigaAM v3 RNNT (пунктуация)"
    const val DIAR_NAME = "CAM++ + кластеризация"
    /** Подпись для старых конспектов (до выбора модели все были от 1.7B). */
    const val LLM_NAME = "Qwen3 1.7B"
}

internal val numThreads: Int by lazy {
    Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
}

data class AsrResult(val text: String, val tokens: Array<String>, val timestamps: FloatArray)

/** Распознавание речи: GigaAM v3 (RNN-T, int8) через sherpa-onnx. */
class AsrEngine(private val assets: AssetManager) {

    @Volatile private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun ensureLoaded(): OfflineRecognizer = recognizer ?: run {
        val t0 = System.currentTimeMillis()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 64, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = ModelPaths.ASR_ENCODER,
                    decoder = ModelPaths.ASR_DECODER,
                    joiner = ModelPaths.ASR_JOINER,
                ),
                tokens = ModelPaths.ASR_TOKENS,
                modelType = "nemo_transducer",
                numThreads = numThreads,
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        OfflineRecognizer(assets, config).also {
            recognizer = it
            Log.i(TAG, "ASR loaded in ${System.currentTimeMillis() - t0} ms, threads=$numThreads")
        }
    }

    fun transcribe(samples: FloatArray): AsrResult {
        val r = ensureLoaded()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            r.decode(stream)
            val res = r.getResult(stream)
            return AsrResult(res.text.trim(), res.tokens, res.timestamps)
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release(); recognizer = null
    }

    private companion object { const val TAG = "AsrEngine" }
}

/** Поиск речи: Silero VAD v5. Возвращает куски речи (секунды). */
class VadEngine(private val assets: AssetManager) {

    data class Params(
        val threshold: Float = 0.4f,
        val minSilence: Float = 0.25f,
        val minSpeech: Float = 0.15f,
        val maxSpeech: Float = 20f,
    )

    @Synchronized
    fun segments(samples: FloatArray, sampleRate: Int, p: Params = Params(), onProgress: (Float) -> Unit = {}): List<SpeakerClustering.Chunk> {
        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = ModelPaths.VAD,
                    threshold = p.threshold,
                    minSilenceDuration = p.minSilence,
                    minSpeechDuration = p.minSpeech,
                    windowSize = WINDOW,
                    maxSpeechDuration = p.maxSpeech,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        val out = ArrayList<SpeakerClustering.Chunk>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                val s = seg.start.toFloat() / sampleRate
                out += SpeakerClustering.Chunk(s, s + seg.samples.size.toFloat() / sampleRate)
                vad.pop()
            }
        }
        try {
            // Кормим строго по одному окну (см. FEED)
            var i = 0
            while (i < samples.size) {
                val end = minOf(samples.size, i + FEED)
                vad.acceptWaveform(samples.copyOfRange(i, end))
                drain()
                i = end
                if ((i / WINDOW) % 64 == 0) onProgress(i.toFloat() / samples.size)
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return out
    }

    private companion object {
        const val WINDOW = 512
        /** Ровно одно окно: при подаче больших кусков VAD в sherpa-onnx перестаёт резать речь. */
        const val FEED = WINDOW
    }
}

/**
 * Голосовые эмбеддинги (CAM++) для диаризации.
 * Окна короткие, поэтому многопоточность внутри одного вызова почти не помогает —
 * держим несколько однопоточных экстракторов и считаем окна параллельно.
 */
class SpeakerEmbedder(private val assets: AssetManager) {

    private var pool: List<SpeakerEmbeddingExtractor>? = null

    @Synchronized
    private fun extractors(): List<SpeakerEmbeddingExtractor> = pool ?: List(numThreads) {
        SpeakerEmbeddingExtractor(
            assets,
            SpeakerEmbeddingExtractorConfig(model = ModelPaths.EMBEDDING, numThreads = 1, debug = false, provider = "cpu"),
        )
    }.also { pool = it }

    /** Эмбеддинги для отрезков (секунды); порядок результатов совпадает с порядком отрезков. */
    fun embedAll(
        samples: FloatArray,
        sampleRate: Int,
        ranges: List<Pair<Float, Float>>,
        onProgress: (Float) -> Unit = {},
    ): List<FloatArray> {
        if (ranges.isEmpty()) return emptyList()
        val ex = extractors()
        val out = arrayOfNulls<FloatArray>(ranges.size)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val workers = ex.map { e ->
            Thread {
                try {
                    while (!stop.get()) {
                        val i = next.getAndIncrement()
                        if (i >= ranges.size) break
                        val (s, t) = ranges[i]
                        val from = (s * sampleRate).toInt().coerceIn(0, samples.size)
                        val to = (t * sampleRate).toInt().coerceIn(from, samples.size)
                        val stream = e.createStream()
                        try {
                            stream.acceptWaveform(samples.copyOfRange(from, to), sampleRate)
                            stream.inputFinished()
                            out[i] = e.compute(stream)
                        } finally {
                            stream.release()
                        }
                        val d = done.incrementAndGet()
                        if (d % 8 == 0 || d == ranges.size) onProgress(d.toFloat() / ranges.size)
                    }
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                    stop.set(true)
                }
            }.apply { name = "embed"; start() }
        }
        try {
            workers.forEach { it.join() }
        } catch (e: InterruptedException) {
            stop.set(true)
            workers.forEach { it.join() }
            throw e
        }
        error.get()?.let { throw it }
        return out.map { it!! }
    }

    @Synchronized
    fun release() {
        pool?.forEach { it.release() }
        pool = null
    }
}

data class SpeakerTurn(val start: Float, val end: Float, val speaker: Int) {
    val duration: Float get() = end - start
}


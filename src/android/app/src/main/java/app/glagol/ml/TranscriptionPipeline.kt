package app.glagol.ml

import android.os.SystemClock
import android.util.Log
import app.glagol.audio.AudioDecoder
import app.glagol.audio.WavIO
import app.glagol.data.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class Stage(val title: String, val weight: Float) {
    Decoding("Подготовка аудио", 0.06f),
    Recognition("Распознавание речи", 0.62f),
    Diarization("Определение говорящих", 0.32f);

    /** Общий прогресс 0..1 по прогрессу текущего этапа. */
    fun overall(stageProgress: Float): Float {
        val before = entries.takeWhile { it != this }.sumOf { it.weight.toDouble() }.toFloat()
        return (before + weight * stageProgress.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

class UserFacingException(message: String) : Exception(message)

data class PipelineResult(
    val utterances: List<Utterance>,
    val durationMs: Long,
    val speakerCount: Int,
    val speakerShare: List<Float>,
    val stageMs: Map<String, Long>,
    /** Таймкоды слов каждой реплики, см. [TranscriptAssembler.Result.wordTimes]. */
    val wordTimes: List<LongArray> = emptyList(),
)

/**
 * Вся обработка — локально:
 *  1. декодирование и нормализация;
 *  2. VAD находит всю речь → ASR распознаёт каждый кусок целиком (слова с таймкодами);
 *  3. диаризация: смена спикера по эмбеддингам скользящего окна и тире в тексте, кластеризация кусков речи;
 *  4. каждому слову — спикер, сглаживание, сборка реплик.
 * Текст не зависит от ошибок диаризации: ни одно слово не теряется и не режется пополам.
 */
class TranscriptionPipeline(
    private val vad: VadEngine,
    private val asr: AsrEngine,
    private val embedder: SpeakerEmbedder,
) {

    suspend fun run(
        input: File,
        outWav: File,
        numSpeakers: Int,
        onProgress: (Stage, Float) -> Unit,
    ): PipelineResult = withContext(Dispatchers.Default) {
        val timings = linkedMapOf<String, Long>()
        val sr = WavIO.SAMPLE_RATE
        val ctx = currentCoroutineContext()

        // 1. Декодирование
        onProgress(Stage.Decoding, 0f)
        var t0 = SystemClock.elapsedRealtime()
        val samples = AudioDecoder.normalize(
            AudioDecoder.decodeFile(input) { onProgress(Stage.Decoding, it * 0.9f) }
        )
        if (samples.size < sr / 2) throw UserFacingException("Запись слишком короткая")
        if (input.absolutePath != outWav.absolutePath) WavIO.write(outWav, samples)
        val durationMs = samples.size * 1000L / sr
        timings["decode"] = SystemClock.elapsedRealtime() - t0
        onProgress(Stage.Decoding, 1f)

        // 2. Речь → текст
        onProgress(Stage.Recognition, 0f)
        t0 = SystemClock.elapsedRealtime()
        val speech = vad.segments(samples, sr) { onProgress(Stage.Recognition, it * 0.05f) }
        timings["vad"] = SystemClock.elapsedRealtime() - t0
        ctx.ensureActive()
        t0 = SystemClock.elapsedRealtime()
        asr.ensureLoaded()
        val totalSpeech = speech.sumOf { it.duration.toDouble() }.toFloat().coerceAtLeast(1e-3f)
        var done = 0f
        val pad = (PAD_SEC * sr).toInt()
        val words = ArrayList<Word>()
        for (seg in speech) {
            ctx.ensureActive()
            val from = max(0, (seg.start * sr).toInt() - pad)
            val to = min(samples.size, (seg.end * sr).toInt() + pad)
            if (to - from >= sr / 10) {
                val res = asr.transcribe(samples.copyOfRange(from, to))
                words += WordAligner.fromAsr(res, from.toFloat() / sr, to.toFloat() / sr)
            }
            done += seg.duration
            onProgress(Stage.Recognition, 0.05f + 0.95f * done / totalSpeech)
        }
        timings["asr"] = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "vad: ${speech.size} segments, ${"%.1f".format(totalSpeech)} s speech; asr: ${words.size} words")
        onProgress(Stage.Recognition, 1f)

        if (words.isEmpty()) {
            return@withContext PipelineResult(emptyList(), durationMs, 0, emptyList(), timings)
        }

        // 3. Кто говорит: смена спикера (эмбеддинги скользящим окном + тире в тексте) → кластеризация кусков
        onProgress(Stage.Diarization, 0f)
        t0 = SystemClock.elapsedRealtime()
        val dashCuts = words.filter { it.turnStart }.map { it.start - 0.02f }
        val turns = runInterruptible {
            SpeakerClustering(embedder).run(samples, sr, speech, dashCuts, emptyList(), numSpeakers) {
                onProgress(Stage.Diarization, it)
            }
        }
        ctx.ensureActive()
        timings["diarization"] = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "diarization: ${turns.size} chunks, speakers=${turns.map { it.speaker }.toSet()}")

        // 4. Реплики
        val result = TranscriptAssembler.assemble(words, turns)
        onProgress(Stage.Diarization, 1f)
        PipelineResult(
            utterances = result.utterances,
            durationMs = durationMs,
            speakerCount = result.speakerCount,
            speakerShare = result.speakerShare,
            stageMs = timings,
            wordTimes = result.wordTimes,
        )
    }

    private companion object {
        const val TAG = "Pipeline"
        /** Небольшой запас по краям куска речи, чтобы ASR не терял первые/последние звуки. */
        const val PAD_SEC = 0.1f
    }
}

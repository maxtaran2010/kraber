package app.glagol.service

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.glagol.data.QaItem
import app.glagol.data.RecordingStatus
import app.glagol.data.RecordingsRepository
import app.glagol.data.SummaryStatus
import app.glagol.ml.LlmClient
import app.glagol.ml.LlmSummarizer
import app.glagol.ml.LlmUnsupportedException
import app.glagol.ml.SpeakerNamer
import app.glagol.ml.SummaryLogic
import app.glagol.ml.Stage
import app.glagol.ml.TranscriptionPipeline
import app.glagol.ml.UserFacingException
import app.glagol.audio.UnsupportedAudioException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class ProcessingState(
    val recordingId: String,
    val stage: Stage,
    val stageProgress: Float,
    val startedAt: Long,
) {
    val overall: Float get() = stage.overall(stageProgress)
}

/**
 * Очередь обработки. Живёт в Application, переживает пересоздание экранов;
 * пока есть работа — держит запущенным [ProcessingService] (foreground).
 */
class ProcessingManager(
    private val context: Context,
    private val repo: RecordingsRepository,
    private val pipeline: TranscriptionPipeline,
    private val llm: LlmClient,
) {
    /** Необработанная ошибка в фоновой задаче (например, запись meta.json на полном диске) не роняет приложение. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "background job failed", e) }
    )
    private val pipelineMutex = Mutex()

    private val _state = MutableStateFlow<ProcessingState?>(null)
    /** Текущая активная обработка (одна за раз). */
    val state: StateFlow<ProcessingState?> = _state.asStateFlow()

    /**
     * Потоковый текст конспекта, пока LLM его генерирует: id -> текст.
     * Для длинных записей впереди статус («Читаю часть 3 из 17…») — см. [SummaryLogic.decodeLive].
     */
    private val _liveSummary = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveSummary: StateFlow<Map<String, String>> = _liveSummary.asStateFlow()

    /** Потоковый ответ на вопрос, пока LLM его пишет: id -> текст ("" — ещё думает). */
    private val _liveAnswer = MutableStateFlow<Map<String, String>>(emptyMap())
    val liveAnswer: StateFlow<Map<String, String>> = _liveAnswer.asStateFlow()

    /** Записи, по которым сейчас идёт вопрос к LLM. */
    private val _asking = MutableStateFlow<Set<String>>(emptySet())
    val asking: StateFlow<Set<String>> = _asking.asStateFlow()

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val _activeJobs = MutableStateFlow(0)
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    /** Импорт файла: копируем во внутреннюю память (URI может «протухнуть») и ставим в очередь. */
    fun startFromUri(uri: Uri, displayName: String, numSpeakers: Int): String {
        val title = displayName.substringBeforeLast('.').ifBlank { "Аудио" }
        val rec = repo.create(title, displayName, numSpeakers, fromMic = false)
        val ext = displayName.substringAfterLast('.', "bin").take(5)
        val src = File(repo.dir(rec.id), "source.$ext")
        enqueue(rec.id, prepare = {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    src.outputStream().use { input.copyTo(it, 1 shl 16) }
                } ?: throw UserFacingException("Не удалось прочитать файл")
            }
            src
        })
        return rec.id
    }

    /** Запись с микрофона: файл уже WAV 16 кГц. */
    fun startFromRecording(wav: File, title: String, numSpeakers: Int): String {
        val rec = repo.create(title, "${title}.wav", numSpeakers, fromMic = true)
        val dst = repo.audioFile(rec.id)
        enqueue(rec.id, prepare = {
            withContext(Dispatchers.IO) {
                if (!wav.renameTo(dst)) { wav.copyTo(dst, overwrite = true); wav.delete() }
            }
            dst
        })
        return rec.id
    }

    /** Повторная обработка (например, с другим числом спикеров). */
    fun reprocess(id: String, numSpeakers: Int) {
        val audio = repo.audioFile(id)
        if (!audio.exists() || jobs[id]?.isActive == true) return
        repo.update(id) {
            // Нумерация спикеров изменится — старые имена (и найденные, и заданные вручную) уже не про тех людей
            it.copy(status = RecordingStatus.Processing, requestedSpeakers = numSpeakers, error = null,
                summary = null, digest = null, summaryStatus = SummaryStatus.None, speakerNames = null, speakerNamesUser = emptyMap())
        }
        enqueue(id, prepare = { audio })
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    fun retrySummary(id: String) {
        // Отладка (tools/eval/llm_compare.sh): `--es resummarize "ask:<id>:<вопрос>"` — вопрос без UI
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && id.startsWith("ask:")) {
            val rest = id.removePrefix("ask:")
            ask(rest.substringBefore(':'), rest.substringAfter(':', ""))
            return
        }
        queueSummary(id, front = true)
    }

    private fun enqueue(id: String, prepare: suspend () -> File) {
        ProcessingService.start(context)
        _activeJobs.update { it + 1 }
        pipelineJobs.incrementAndGet()
        pauseSummaries()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val input = prepare()
                pipelineMutex.withLock { process(id, input) }
            } catch (e: CancellationException) {
                Log.i(TAG, "cancelled $id")
                repo.delete(id)
            } catch (e: Throwable) {
                Log.e(TAG, "prepare failed $id", e)
                repo.update(id) { it.copy(status = RecordingStatus.Failed, error = e.userMessage()) }
            } finally {
                jobs.remove(id)
                _activeJobs.update { it - 1 }
                if (pipelineJobs.decrementAndGet() == 0) maybeStartSummaries()
            }
        }
        jobs[id] = job
        job.start()
    }

    private suspend fun process(id: String, input: File) {
        val rec = repo.get(id) ?: return
        val started = SystemClock.elapsedRealtime()
        _state.value = ProcessingState(id, Stage.Decoding, 0f, System.currentTimeMillis())
        try {
            val result = pipeline.run(input, repo.audioFile(id), rec.requestedSpeakers) { stage, p ->
                _state.value = _state.value?.copy(stage = stage, stageProgress = p)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            // Имена по тексту разговора — миллисекунды, до конспекта: LLM уже увидит «Оля:» вместо «Спикер 2:»
            val names = SpeakerNamer.analyze(result.utterances)
            if (names.evidence.isNotEmpty()) Log.i(TAG, "names $id: ${names.names}\n" + names.evidence.joinToString("\n"))
            repo.saveTranscript(id, result.utterances)
            repo.saveWords(id, result.wordTimes)
            repo.update(id) {
                it.copy(
                    status = RecordingStatus.Done,
                    durationMs = result.durationMs,
                    speakerCount = result.speakerCount,
                    speakerShare = result.speakerShare,
                    processingMs = elapsed,
                    stageMs = result.stageMs,
                    error = null,
                    summaryStatus = if (result.utterances.isEmpty()) SummaryStatus.None else SummaryStatus.Pending,
                    speakerNames = names.names,
                )
            }
            Log.i(TAG, "done $id: ${result.utterances.size} utt, ${result.durationMs} ms audio in $elapsed ms")
            if (input.name.startsWith("source.")) input.delete()
            // Свежая запись — первой: длинный конспект (map-reduce, минуты) не должен её задерживать
            if (result.utterances.isNotEmpty()) queueSummary(id, front = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "pipeline failed $id", e)
            repo.update(id) { it.copy(status = RecordingStatus.Failed, error = e.userMessage()) }
        } finally {
            _state.value = null
        }
    }

    // ---------------------------------------------------------------- конспекты
    // LLM запускается только когда очередь расшифровок пуста: иначе она отнимает CPU и портит скорость (RTF).

    private val summaryLock = Any()
    private val summaryQueue = ArrayDeque<String>()
    private var summaryJob: Job? = null
    private val pipelineJobs = AtomicInteger(0)

    private fun queueSummary(id: String, front: Boolean = false) {
        repo.update(id) { it.copy(summaryStatus = SummaryStatus.Pending, summaryError = null) }
        synchronized(summaryLock) {
            summaryQueue.remove(id)
            if (front) summaryQueue.addFirst(id) else summaryQueue.addLast(id)
        }
        maybeStartSummaries()
    }

    private fun maybeStartSummaries() = synchronized(summaryLock) {
        if (pipelineJobs.get() > 0 || summaryJob?.isActive == true || summaryQueue.isEmpty()) return@synchronized
        _activeJobs.update { it + 1 }
        cancelRelease()
        summaryJob = scope.launch {
            var finished = false
            try {
                while (true) {
                    val id = synchronized(summaryLock) { summaryQueue.removeFirstOrNull() } ?: break
                    summarizeOne(id)
                }
                finished = true
            } finally {
                // Пауза ради расшифровки — гасим процесс :llm сразу (он ещё генерирует и ест CPU).
                // Очередь кончилась — модель остаётся тёплой на минуту для вопросов.
                if (finished) scheduleRelease() else withContext(NonCancellable) { llm.release() }
                _activeJobs.update { it - 1 }
            }
        }
    }

    /** Новая расшифровка важнее конспекта: останавливаем LLM, конспект продолжится после. */
    private fun pauseSummaries() {
        synchronized(summaryLock) { summaryJob }?.cancel()
        // Модель, оставленная тёплой для вопросов, держит 1–2 ГБ: на 4 ГБ ОЗУ это тормозит расшифровку — отдаём память
        val warmIdle = synchronized(summaryLock) { releaseJob?.isActive == true }
        if (warmIdle && _asking.value.isEmpty()) {
            cancelRelease()
            scope.launch { llm.release() }
        }
    }

    /** Запись в meta.json, которая не должна ронять конспект/вопрос (полный диск и т. п.). */
    private fun safeUpdate(id: String, transform: (app.glagol.data.Recording) -> app.glagol.data.Recording) {
        runCatching { repo.update(id, transform) }.onFailure { Log.e(TAG, "meta update failed $id", it) }
    }

    private suspend fun summarizeOne(id: String) {
        val utterances = repo.loadTranscript(id).orEmpty()
        if (utterances.isEmpty()) {
            safeUpdate(id) { it.copy(summaryStatus = SummaryStatus.None) }
            return
        }
        try {
            repo.update(id) { it.copy(summaryStatus = SummaryStatus.Generating, summaryError = null) }
            val rec = repo.get(id) ?: return
            // Подписи как в интерфейсе: имя (найденное или заданное) → «Спикер N»
            val lines = SummaryLogic.promptLines(rec, utterances)
            // Совсем короткая запись — только название, карточки «Кратко» не будет
            val titleOnly = SummaryLogic.wordCount(lines) < SummaryLogic.TITLE_ONLY_WORDS
            val res = llm.summarize(lines) { partial ->
                _liveSummary.update { it + (id to partial) }
            }
            val digest = res.digest?.takeUnless { it.isEmpty }
            repo.update(id) {
                it.copy(
                    digest = digest,
                    summary = res.text.ifBlank { null },
                    summaryStatus = if (digest == null && !titleOnly) SummaryStatus.Failed else SummaryStatus.Done,
                    summaryModel = res.model,
                    title = if (!it.titleEdited && !res.title.isNullOrBlank()) res.title else it.title,
                )
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "summary paused $id")
            synchronized(summaryLock) { summaryQueue.addFirst(id) }
            safeUpdate(id) { it.copy(summaryStatus = SummaryStatus.Pending) }
            throw e
        } catch (e: LlmUnsupportedException) {
            Log.w(TAG, "summary unavailable on this device")
            safeUpdate(id) {
                it.copy(summaryStatus = SummaryStatus.Failed, summaryError = "Локальная LLM не поддерживается процессором этого устройства")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "summary failed $id", e)
            // Понятная причина — только для нехватки места; остальное — «Не удалось составить конспект»
            val reason = e.message?.takeIf { it.startsWith(LlmSummarizer.NO_SPACE) }
            safeUpdate(id) { it.copy(summaryStatus = SummaryStatus.Failed, summaryError = reason) }
        } finally {
            _liveSummary.update { it - id }
        }
    }

    // ---------------------------------------------------------------- вопросы к записи
    // Вопрос можно задать в любой момент: если LLM занята конспектом, он подождёт на mutex клиента.

    /** Задать вопрос по записи [id]: ответ стримится в [liveAnswer], по готовности — в `meta.json` (`qa`). */
    fun ask(id: String, question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        synchronized(summaryLock) {
            if (id in _asking.value) return
            _asking.update { it + id }
        }
        cancelRelease()
        _liveAnswer.update { it + (id to "") }
        scope.launch {
            try {
                val rec = repo.get(id) ?: return@launch
                val utterances = withContext(Dispatchers.IO) { repo.loadTranscript(id) }.orEmpty()
                if (utterances.isEmpty()) {
                    addAnswer(id, q, SummaryLogic.NOT_FOUND)
                    return@launch
                }
                val lines = SummaryLogic.promptLines(rec, utterances)
                val history = rec.qa.filterNot { it.a.startsWith(SummaryLogic.ERROR_PREFIX) }.takeLast(2)
                val answer = llm.ask(lines, rec.digest, history, q) { partial ->
                    _liveAnswer.update { it + (id to partial) }
                }
                addAnswer(id, q, answer.ifBlank { SummaryLogic.NOT_FOUND })
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmUnsupportedException) {
                addAnswer(id, q, SummaryLogic.ERROR_PREFIX + "Локальная LLM не поддерживается процессором этого устройства")
            } catch (e: Throwable) {
                Log.e(TAG, "ask failed $id", e)
                val reason = e.message?.takeIf { it.startsWith(LlmSummarizer.NO_SPACE) } ?: "Не получилось ответить. Попробуйте ещё раз"
                addAnswer(id, q, SummaryLogic.ERROR_PREFIX + reason)
            } finally {
                _liveAnswer.update { it - id }
                _asking.update { it - id }
                scheduleRelease()
            }
        }
    }

    private fun addAnswer(id: String, q: String, a: String) {
        safeUpdate(id) { it.copy(qa = it.qa + QaItem(q = q, a = a, at = System.currentTimeMillis())) }
    }

    // ---------------------------------------------------------------- тёплая модель
    // Загрузка модели — секунды: после конспекта/ответа держим процесс :llm ещё минуту,
    // чтобы следующий вопрос шёл сразу. Новый запрос отменяет отложенное освобождение.

    private var releaseJob: Job? = null

    private fun cancelRelease() = synchronized(summaryLock) {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun scheduleRelease() = synchronized(summaryLock) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(LLM_IDLE_MS)
            val busy = synchronized(summaryLock) { summaryJob?.isActive == true } || _asking.value.isNotEmpty()
            if (!busy) llm.release()
        }
    }

    private fun Throwable.userMessage(): String = when (this) {
        is UserFacingException, is UnsupportedAudioException -> message ?: "Ошибка"
        is OutOfMemoryError -> "Недостаточно памяти для обработки"
        else -> "Ошибка обработки: ${message ?: javaClass.simpleName}"
    }

    private companion object {
        const val TAG = "Processing"
        /** Сколько держать модель загруженной после последнего запроса. */
        const val LLM_IDLE_MS = 60_000L
    }
}

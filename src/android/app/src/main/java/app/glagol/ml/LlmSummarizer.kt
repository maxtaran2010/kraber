package app.glagol.ml

import android.content.Context
import android.util.Log
import app.glagol.data.Chapter
import app.glagol.data.QaItem
import app.glagol.data.Summary
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Результат конспекта: название (может не быть) и структура; [digest] = null — конспекта нет (короткая запись). */
data class SummaryResult(val title: String?, val digest: Summary?) {
    /** Текстовая версия для старого поля `summary`. */
    val text: String get() = digest?.let { SummaryLogic.renderPlain(it) }.orEmpty()
}

enum class LlmBackend { CPU, GPU }

/**
 * Локальная LLM (LiteRT-LM, Qwen3 1.7B int4) — конспект, название разговора и ответы на вопросы.
 * Текст реплик LLM НЕ изменяет: на точность расшифровки (WER) она не влияет.
 *
 * Конспект:
 *  - меньше [SummaryLogic.TITLE_ONLY_WORDS] слов — только название;
 *  - до [SummaryLogic.SINGLE_PASS_CHARS] символов — один проход;
 *  - длиннее — map-reduce: заметка по каждой части (это и «Хронология»), затем итог по заметкам.
 * Каждый вызов модели — новый диалог: контекст 2048 токенов, промпт + ответ должны в него влезть.
 *
 * Работает только внутри [LlmService] — в отдельном процессе `:llm`.
 */
class LlmSummarizer(private val context: Context) {

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engineBackend: LlmBackend? = null

    /** LiteRT-LM нужен путь к файлу — один раз копируем модель из APK во внутреннюю память. */
    private fun modelFile(): File {
        // Отладка: подмена модели без пересборки (adb push в files/models/override.litertlm)
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) File(context.filesDir, "models/override.litertlm").takeIf { it.exists() }?.let { return it }
        val dst = File(context.filesDir, "models/model.litertlm")
        val expected = runCatching { context.assets.openFd(ModelPaths.LLM).use { it.length } }.getOrDefault(-1L)
        if (dst.exists() && (expected < 0 || dst.length() == expected)) return dst
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "model.litertlm.part")
        tmp.delete()
        dst.delete()
        // Копия модели ~1 ГБ: без места не начинаем, чтобы не забить диск обрывком
        val free = dst.parentFile?.usableSpace ?: 0L
        if (expected > 0 && free < expected + (64L shl 20)) {
            throw java.io.IOException("$NO_SPACE: нужно ~${(expected shr 20) + 64} МБ, свободно ${free shr 20} МБ")
        }
        try {
            context.assets.open(ModelPaths.LLM).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            }
            if (!tmp.renameTo(dst)) throw java.io.IOException("rename failed")
        } catch (e: Throwable) {
            tmp.delete()
            throw if (e is java.io.IOException && free < expected * 2) java.io.IOException("$NO_SPACE для модели", e) else e
        }
        return dst
    }

    private fun ensureEngine(backend: LlmBackend): Engine {
        engine?.let { if (engineBackend == backend) return it }
        closeEngine()
        val t0 = System.currentTimeMillis()
        val model = modelFile()
        val cacheDir = cacheDirFor()
        val e = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = if (backend == LlmBackend.GPU) Backend.GPU() else Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                cacheDir = cacheDir,
            )
        )
        e.initialize()
        Log.i(TAG, "LLM ready on $backend in ${System.currentTimeMillis() - t0} ms (cache: $cacheDir)")
        engine = e
        engineBackend = backend
        return e
    }

    /**
     * Кэш весов XNNPack (~0.9 ГБ) пишется в cacheDir при первом запуске. Если места мало, запись обрывается
     * и движок падает (SIGABRT «cannot append buffer to cache file») — это выглядело бы как «LLM не поддерживается».
     * Поэтому при нехватке места и без готового кэша работаем без него (`:nocache`): старт медленнее, но работает.
     */
    private fun cacheDirFor(): String {
        val dir = File(context.cacheDir, "llm").apply { mkdirs() }
        val hasCache = dir.listFiles()?.any { it.name.endsWith(".xnnpack_cache") && it.length() > 0 } == true
        val free = dir.usableSpace
        return if (hasCache || free >= MIN_FREE_FOR_CACHE) {
            Log.i(TAG, "XNNPack cache in ${dir.absolutePath} (exists=$hasCache, free=${free shr 20} MB)")
            dir.absolutePath
        } else {
            Log.w(TAG, "low storage (${free shr 20} MB free) and no XNNPack cache — running with :nocache")
            NO_CACHE
        }
    }

    /**
     * Один вызов модели в новом диалоге. [onRaw] получает накопленный сырой текст.
     * Модель держим заряженной между вызовами — процесс `:llm` освобождает клиент.
     */
    private suspend fun generate(
        backend: LlmBackend,
        system: String,
        prompt: String,
        maxOut: Int,
        temperature: Double,
        tag: String,
        onRaw: (String) -> Unit = {},
    ): String {
        val engine = ensureEngine(backend)
        // Промпт + ответ должны влезть в контекст: при длинном промпте укорачиваем ответ, а не падаем
        val promptTokens = ((system.length + prompt.length) / SummaryLogic.CHARS_PER_TOKEN).toInt() + 40
        val out = maxOut.coerceAtMost(MAX_TOKENS - promptTokens).coerceAtLeast(64)
        if (out < maxOut) Log.w(TAG, "$tag: long prompt (~$promptTokens tokens), output limited to $out")
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.8, temperature = temperature, seed = 42),
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = out,
            )
        )
        val raw = StringBuilder()
        val t0 = System.currentTimeMillis()
        try {
            conversation.sendMessageAsync(prompt).collect { msg ->
                val chunk = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                // Поддерживаем и дельты, и накопительный текст
                if (chunk.startsWith(raw)) { raw.setLength(0); raw.append(chunk) } else raw.append(chunk)
                onRaw(raw.toString())
            }
            Log.i(TAG, "$tag: prompt ${prompt.length} chars, ${raw.length} chars out, ${System.currentTimeMillis() - t0} ms")
            Log.d(TAG, "$tag raw: ${raw.toString().take(800).replace('\n', '⏎')}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Запрос отменён (конспект на паузе) — останавливаем генерацию в движке
            runCatching { conversation.cancelProcess() }
            throw e
        } finally {
            conversation.close()
        }
        return raw.toString()
    }

    suspend fun summarize(lines: List<PromptLine>, backend: LlmBackend, onPartial: (String) -> Unit): SummaryResult =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                val t0 = System.currentTimeMillis()
                val facts = SummaryLogic.facts(lines)
                val transcript = SummaryLogic.render(lines)
                val res = when {
                    facts.words < SummaryLogic.TITLE_ONLY_WORDS -> {
                        // Приветствие или пара фраз: конспект тут только выдумает «суть» — просим одно название
                        val raw = generate(backend, SummaryLogic.SYSTEM_SUMMARY, SummaryLogic.titlePrompt(transcript), 24, 0.3, "title")
                        SummaryResult(SummaryLogic.parseTitle(raw), null)
                    }
                    transcript.length <= SummaryLogic.SINGLE_PASS_CHARS -> {
                        val raw = generate(
                            backend, SummaryLogic.SYSTEM_SUMMARY, SummaryLogic.summaryPrompt(transcript, facts), SUMMARY_TOKENS, 0.3, "summary",
                        ) { r -> onPartial(SummaryLogic.renderPlain(SummaryLogic.parseSummary(r, facts, final = false).summary)) }
                        val parsed = SummaryLogic.parseSummary(raw, facts)
                        SummaryResult(parsed.title, parsed.summary.takeUnless { it.isEmpty })
                    }
                    else -> mapReduce(lines, facts, backend, onPartial)
                }
                Log.i(TAG, "summary: ${lines.size} lines, ${transcript.length} chars, words=${facts.words}, " +
                    "commitments=${facts.commitments} → ${res.digest?.chapters?.size ?: 0} chapters in ${System.currentTimeMillis() - t0} ms")
                res
            }
        }

    /** Длинная запись: заметка по каждой части (map), затем итог по заметкам (reduce). */
    private suspend fun mapReduce(
        lines: List<PromptLine>,
        facts: Facts,
        backend: LlmBackend,
        onPartial: (String) -> Unit,
    ): SummaryResult {
        val chunks = SummaryLogic.chunk(lines)
        val chapters = ArrayList<Chapter>()
        fun progress(i: Int) = "Читаю часть ${i + 1} из ${chunks.size}…"
        fun soFar() = SummaryLogic.renderPlain(Summary(chapters = chapters), withChapters = true)
        for ((i, c) in chunks.withIndex()) {
            onPartial(SummaryLogic.encodeLive(progress(i), soFar()))
            val raw = generate(backend, SummaryLogic.SYSTEM_SUMMARY, SummaryLogic.mapPrompt(c, i, chunks.size), MAP_TOKENS, 0.3, "map ${i + 1}/${chunks.size}")
            var text = SummaryLogic.cleanChapter(raw, facts)
            if (text.isBlank()) {
                // Модель повторила реплики вместо пересказа — ещё раз, со строгим запретом
                val again = generate(
                    backend, SummaryLogic.SYSTEM_SUMMARY, SummaryLogic.mapPrompt(c, i, chunks.size, strict = true),
                    MAP_TOKENS, 0.5, "map ${i + 1}/${chunks.size} retry",
                )
                text = SummaryLogic.cleanChapter(again, facts)
            }
            if (text.isNotBlank()) chapters += Chapter(c.startMs, c.endMs, text)
        }
        onPartial(SummaryLogic.encodeLive("Собираю итог…", soFar()))
        if (chapters.isEmpty()) return SummaryResult(null, null)
        val raw = generate(
            backend, SummaryLogic.SYSTEM_SUMMARY, SummaryLogic.reducePrompt(chapters, facts), SUMMARY_TOKENS, 0.3, "reduce",
        ) { r ->
            val p = SummaryLogic.parseSummary(r, facts, final = false).summary
            onPartial(SummaryLogic.encodeLive("Собираю итог…", SummaryLogic.renderPlain(p)))
        }
        val parsed = SummaryLogic.parseSummary(raw, facts)
        return SummaryResult(parsed.title, parsed.summary.copy(chapters = chapters))
    }

    /** Ответ на вопрос по расшифровке (стримится в [onPartial]). */
    suspend fun answer(
        lines: List<PromptLine>,
        digest: Summary?,
        history: List<QaItem>,
        question: String,
        backend: LlmBackend,
        onPartial: (String) -> Unit,
    ): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (SummaryLogic.clearlyNotDiscussed(lines, question)) {
                Log.i(TAG, "ask: «$question» — слов вопроса нет в расшифровке, отвечаем без модели")
                return@withContext SummaryLogic.NOT_FOUND
            }
            val prompt = SummaryLogic.askPrompt(lines, digest, history, question)
            val raw = generate(backend, SummaryLogic.SYSTEM_ASK, prompt, ASK_TOKENS, 0.2, "ask: $question") { r ->
                onPartial(SummaryLogic.cleanAnswer(r, lines, final = false))
            }
            SummaryLogic.cleanAnswer(raw, lines).ifBlank { SummaryLogic.NOT_FOUND }
        }
    }

    /** Освобождаем ~0.5 ГБ, когда LLM не нужна. */
    suspend fun release() = mutex.withLock { closeEngine() }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineBackend = null
    }

    companion object {
        private const val TAG = "LLM"
        /** Начало текста ошибки «нет места» — показывается пользователю как причина. */
        const val NO_SPACE = "Недостаточно места на устройстве"
        /** KV-кэш ~0.23 МБ на токен (fp32): 2048 токенов — ~0.5 ГБ. */
        private const val MAX_TOKENS = 2048
        private const val SUMMARY_TOKENS = 300
        private const val MAP_TOKENS = 90
        private const val ASK_TOKENS = 200
        /** Меньше свободного места — не пишем кэш весов XNNPack (~0.9 ГБ). */
        private const val MIN_FREE_FOR_CACHE = 1300L shl 20
        /** LiteRT-LM: специальное значение cacheDir — без кэша на диске. */
        private const val NO_CACHE = ":nocache"
    }
}

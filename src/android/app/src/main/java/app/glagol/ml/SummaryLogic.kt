package app.glagol.ml

import app.glagol.data.Chapter
import app.glagol.data.QaItem
import app.glagol.data.Recording
import app.glagol.data.Summary
import app.glagol.data.Utterance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Строка расшифровки для LLM: кто (имя или «Спикер N») и что сказал.
 * Короткие имена полей — меньше байт в binder (длинный созвон — сотни строк).
 */
@Serializable
data class PromptLine(
    @SerialName("s") val startMs: Long,
    @SerialName("e") val endMs: Long,
    @SerialName("w") val who: String,
    @SerialName("t") val text: String,
) {
    fun render(): String = "$who: $text"
}

/** Кусок разговора для map-reduce: подряд идущие реплики и их время. */
data class Chunk(val startMs: Long, val endMs: Long, val lines: List<PromptLine>) {
    val text: String get() = SummaryLogic.render(lines)
}

/** Что известно о расшифровке без LLM — для промптов и фильтра выдумок. */
data class Facts(
    /** Как участники подписаны в промпте: имена и «Спикер N». */
    val who: Set<String>,
    /** Номера «Спикер N», которые реально есть в промпте. */
    val speakerNums: Set<Int>,
    val words: Int,
    /** Были ли обещания/поручения/сроки — без них раздел «Договорились» не просим и не показываем. */
    val commitments: Boolean,
    /** Весь текст в нижнем регистре (ё → е) — проверка, что имена в ответе модели взяты из разговора. */
    val corpus: String,
    /**
     * Кто мог что-то пообещать (подписи в нижнем регистре): сам говорил «сделаю/проверю/пришлю…»
     * или к нему обращались в реплике с поручением/сроком. «✓ Спикер 2 — …» про других — путаница модели.
     */
    val committers: Set<String> = emptySet(),
)

/** Разобранный ответ модели. */
data class ParsedSummary(val title: String?, val summary: Summary)

/**
 * Чистая логика конспекта и вопросов (без Android и LiteRT — покрыта unit-тестами):
 * строки для промпта, нарезка на части, промпты, разбор ответа модели, поиск фрагментов под вопрос.
 */
object SummaryLogic {

    /** Меньше слов — только название, без конспекта (иначе модель выдумывает «суть» приветствия). */
    const val TITLE_ONLY_WORDS = 25
    // Бюджеты. Контекст модели — 2048 токенов на промпт + ответ. Токенизатор Qwen3 на русской расшифровке
    // даёт ≈ 2.3–2.5 символа на токен (замерено на dialog1 / piper_3spk / «Оперконтуре»), поэтому
    // промпт держим в пределах ~4000 символов (~1650 токенов) при ответе до 300 токенов.

    /** До стольких символов — конспект за один проход; длиннее — map-reduce по частям. */
    const val SINGLE_PASS_CHARS = 2800
    /** Размер части для map (~1150 токенов + ответ 90). */
    const val CHUNK_CHARS = 2800
    /** Сколько символов заметок по частям отдаём в reduce. */
    const val REDUCE_CHARS = 2400
    /** Вопрос: вся расшифровка целиком, если не длиннее. */
    const val ASK_FULL_CHARS = 2800
    /** Вопрос: сколько символов найденных фрагментов. */
    const val ASK_RETRIEVE_CHARS = 2400
    /** Вопрос: общий бюджет на обзор + фрагменты + историю. */
    const val ASK_CONTEXT_CHARS = 3400
    /** Вопрос: обзор (суть + главы) не длиннее. */
    const val ASK_OVERVIEW_CHARS = 800
    /** Оценка символов на токен для защиты от переполнения контекста (с запасом). */
    const val CHARS_PER_TOKEN = 2.2
    const val MAX_POINTS = 5
    const val MAX_AGREED = 4

    /** Ответ, когда в расшифровке ничего по вопросу нет. */
    const val NOT_FOUND = "В записи об этом не говорили."
    /** Ответы-ошибки сохраняются с этим префиксом и не попадают в историю для модели. */
    const val ERROR_PREFIX = "⚠ "

    /** Разделитель «статус ⟂ текст» в потоковом тексте конспекта (см. [encodeLive]). */
    private const val LIVE_SEP = '\u001E'

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------ строки и части

    /**
     * Строки для промпта: подпись спикера как в интерфейсе (имя → «Спикер N»).
     * Соседние короткие реплики одного человека склеиваем — меньше токенов на подписи.
     */
    fun promptLines(rec: Recording, utterances: List<Utterance>): List<PromptLine> =
        promptLines(utterances) { rec.speakerLabel(it) }

    fun promptLines(utterances: List<Utterance>, label: (String) -> String): List<PromptLine> {
        val out = ArrayList<PromptLine>()
        for (u in utterances) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            val who = label(u.speakerId)
            val last = out.lastOrNull()
            if (last != null && last.who == who && last.text.length + text.length < MERGE_CHARS && u.startMs - last.endMs < 2000) {
                out[out.size - 1] = last.copy(endMs = max(last.endMs, u.endMs), text = last.text + " " + text)
            } else {
                out += PromptLine(u.startMs, u.endMs, who, text)
            }
        }
        return out
    }

    fun render(lines: List<PromptLine>): String = lines.joinToString("\n") { it.render() }

    fun encodeLines(lines: List<PromptLine>): String = json.encodeToString(ListSerializer(PromptLine.serializer()), lines)
    fun decodeLines(s: String?): List<PromptLine> =
        if (s.isNullOrBlank()) emptyList() else json.decodeFromString(ListSerializer(PromptLine.serializer()), s)

    fun encodeSummary(s: Summary?): String? = s?.let { json.encodeToString(Summary.serializer(), it) }
    fun decodeSummary(s: String?): Summary? =
        if (s.isNullOrBlank()) null else runCatching { json.decodeFromString(Summary.serializer(), s) }.getOrNull()

    fun encodeQa(list: List<QaItem>): String = json.encodeToString(ListSerializer(QaItem.serializer()), list)
    fun decodeQa(s: String?): List<QaItem> =
        if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(ListSerializer(QaItem.serializer()), s) }.getOrDefault(emptyList())

    fun wordCount(lines: List<PromptLine>): Int = lines.sumOf { WORD.findAll(it.text).count() }

    fun facts(lines: List<PromptLine>): Facts {
        val who = lines.mapTo(LinkedHashSet()) { it.who }
        val nums = who.mapNotNull { SPEAKER_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        val corpus = norm(lines.joinToString("\n") { it.who + ": " + it.text })
        val committers = HashSet<String>()
        for (l in lines) {
            val t = norm(l.text)
            if (!COMMITMENT.containsMatchIn(t) && !t.contains("пожалуйста")) continue
            // Сам что-то обещает («проверю», «сделаем», «договорились»), а не поручает другому
            if (OWN_COMMITMENT.containsMatchIn(t)) committers += norm(l.who)
            // Обращение по имени в поручении: «Барсик, проверь склад до пятницы»
            for (w in who) if (!SPEAKER_NAME.matches(w) && t.contains(norm(w).take(4))) committers += norm(w)
        }
        return Facts(who, nums, wordCount(lines), COMMITMENT.containsMatchIn(corpus), corpus, committers)
    }

    /**
     * Длинная реплика (монолог на минуты) — режем по предложениям на куски не длиннее [maxChars];
     * время куска — пропорционально позиции в тексте.
     */
    fun splitLong(line: PromptLine, maxChars: Int): List<PromptLine> {
        if (line.text.length <= maxChars) return listOf(line)
        val sentences = line.text.split(SENTENCE_END).filter { it.isNotBlank() }
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { parts += cur.toString().trim(); cur.setLength(0) } }
        for (s0 in sentences) {
            var s = s0.trim()
            // Предложение само длиннее лимита — режем по словам
            while (s.length > maxChars) {
                flush()
                val cut = s.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
                parts += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (cur.isNotEmpty() && cur.length + 1 + s.length > maxChars) flush()
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        flush()
        val total = parts.sumOf { it.length }.coerceAtLeast(1)
        val dur = (line.endMs - line.startMs).coerceAtLeast(0)
        var acc = 0
        return parts.map { p ->
            val s = line.startMs + dur * acc / total
            acc += p.length
            val e = line.startMs + dur * acc / total
            PromptLine(s, e, line.who, p)
        }
    }

    /**
     * Нарезка на части для map: по границам реплик, части примерно равной длины, каждая ≤ [maxChars].
     * Время части — от начала первой реплики до конца последней.
     */
    fun chunk(lines: List<PromptLine>, maxChars: Int = CHUNK_CHARS): List<Chunk> {
        val pieces = lines.flatMap { splitLong(it, (maxChars - it.who.length - 3).coerceAtLeast(200)) }
        if (pieces.isEmpty()) return emptyList()
        val lens = pieces.map { it.render().length + 1 }
        val total = lens.sum()
        // 5 % запаса: реплики не режем, поэтому части не могут быть ровно по maxChars
        val n = kotlin.math.ceil(total / (maxChars * 0.95)).toInt().coerceAtLeast(1)
        val out = ArrayList<Chunk>()
        var cur = ArrayList<PromptLine>()
        var len = 0
        var left = total
        // Доля каждой части пересчитывается по остатку — последняя часть не получается огрызком
        var target = total.toDouble() / n
        for ((i, p) in pieces.withIndex()) {
            val l = lens[i]
            // Режем, если не влезает, или если уже набрали «свою долю» и середина реплики за её пределами
            if (cur.isNotEmpty() && (len + l > maxChars || len + l / 2 > target)) {
                out += Chunk(cur.first().startMs, cur.last().endMs, cur)
                left -= len
                target = left.toDouble() / (n - out.size).coerceAtLeast(1)
                cur = ArrayList(); len = 0
            }
            cur += p; len += l
        }
        if (cur.isNotEmpty()) out += Chunk(cur.first().startMs, cur.last().endMs, cur)
        return out
    }

    // ------------------------------------------------------------------ промпты конспекта

    const val SYSTEM_SUMMARY =
        "Ты помогаешь составлять краткие конспекты разговоров. Пишешь по-русски, коротко, своими словами " +
            "и только о том, что есть в расшифровке."

    private fun pointsHint(words: Int): String = when {
        words < 80 -> "1–2"
        words < 250 -> "2–4"
        else -> "3–5"
    }

    private fun formatBlock(facts: Facts): String {
        // Формат описан словами, а не шаблоном: шаблон с «<…>» или «первый момент» модель 1.7B копирует дословно
        val agreed = if (facts.commitments) {
            "\nЕсли о чём-то договорились или кто-то что-то пообещал — в конце добавь строку «Договорились:» " +
                "и под ней строки «✓ кто — что сделает» (со сроком, если он назван); общее решение — «✓ Все — что решили». " +
                "Если договорённостей не было — этот раздел не пиши."
        } else ""
        return """
            |Ответь строго в таком формате:
            |Название: тема разговора в 2–5 словах
            |Суть: 1–2 предложения — о чём разговор и чем он закончился
            |• ключевой момент
            |Ключевых моментов ${pointsHint(facts.words)}, каждый с новой строки, начиная с «• », каждый — одно предложение с конкретикой: цифры, сроки, имена, решения.$agreed
            |Пиши только по-русски, своими словами. Не придумывай того, чего нет в расшифровке.
        """.trimMargin()
    }

    /** Совсем короткая запись: только название. */
    fun titlePrompt(transcript: String): String = """
        |Расшифровка короткой записи:
        |$transcript
        |
        |Придумай название для этой записи — 2–4 слова по её содержанию. Ответь одной строкой: «Название: …»
    """.trimMargin()

    /** Конспект за один проход. */
    fun summaryPrompt(transcript: String, facts: Facts): String = """
        |Расшифровка разговора (участники — по именам или «Спикер 1», «Спикер 2» и т. д.):
        |$transcript
        |
        |${formatBlock(facts)}
    """.trimMargin()

    /** map: короткая заметка по одной части длинного разговора. */
    // Задача — и до, и после текста, а сам текст — между «---»: иначе модель продолжает диалог вместо пересказа
    fun mapPrompt(chunk: Chunk, index: Int, count: Int, strict: Boolean = false): String = """
        |Ниже фрагмент расшифровки длинного разговора (часть ${index + 1} из $count). Перескажи его кратко.
        |---
        |${chunk.text}
        |---
        |Напиши 1–2 предложения своими словами: что именно обсуждают в этом фрагменте и к чему пришли. Называй конкретные вещи из текста (что за задача, какие числа и даты), а не общие слова вроде «обсуждают задачи и сроки». Без вступлений${if (strict) ", не повторяй реплики и не пиши «Спикер N:» в начале строки" else ""}.
    """.trimMargin()

    /** reduce: итог по заметкам частей. */
    fun reducePrompt(chapters: List<Chapter>, facts: Facts): String = """
        |Заметки по частям длинного разговора (время — от начала записи):
        |${chapterNotes(chapters, REDUCE_CHARS)}
        |
        |По этим заметкам составь итог всего разговора.
        |${formatBlock(facts)}
    """.trimMargin()

    /** «[0:00–5:12] текст» построчно, не длиннее [budget] символов (длинные заметки укорачиваем равномерно). */
    fun chapterNotes(chapters: List<Chapter>, budget: Int): String {
        if (chapters.isEmpty()) return ""
        val full = chapters.joinToString("\n") { "[${clock(it.startMs)}–${clock(it.endMs)}] ${it.text}" }
        if (full.length <= budget) return full
        // Не влезают даже по ~60 символов — берём главы равномерно по всей записи (первую и последнюю — всегда)
        val fit = (budget / (60 + 16)).coerceAtLeast(2)
        val picked = if (chapters.size <= fit) chapters
        else (0 until fit).map { chapters[(it * (chapters.size - 1).toDouble() / (fit - 1)).roundToInt()] }.distinct()
        val per = (budget / picked.size - 16).coerceAtLeast(50)
        return picked.joinToString("\n") { "[${clock(it.startMs)}–${clock(it.endMs)}] ${shorten(it.text, per)}" }
            .let { if (it.length <= budget) it else it.take(budget).substringBeforeLast('\n') }
    }

    // ------------------------------------------------------------------ разбор ответа

    fun stripThink(raw: String): String =
        raw.replace(THINK, "").replace("**", "").replace("__", "").trim()

    /** Разбирает «Название / Суть / • пункты / Договорились: ✓ …» и отбрасывает выдумки. */
    fun parseSummary(raw: String, facts: Facts?, final: Boolean = true): ParsedSummary {
        var title: String? = null
        var gist = ""
        val points = ArrayList<String>()
        val agreed = ArrayList<String>()
        var inAgreed = false
        var expectGist = false
        for (line0 in stripThink(raw).lines()) {
            var line = line0.trim().trim('#', ' ').trim()
            if (line.isEmpty()) continue
            // «• Договорились: ✓ …» — заголовок, ошибочно оформленный пунктом
            BULLET.find(line)?.let { b -> if (HEADER.containsMatchIn(line.substring(b.range.last + 1))) line = line.substring(b.range.last + 1).trim() }
            // Эхо инструкции: «Ключевых моментов 1–2: …»
            line = line.replace(INSTRUCTION_ECHO, "").trim()
            if (line.isEmpty()) continue
            val header = HEADER.find(line)
            if (header != null) {
                val key = header.groupValues[1].lowercase()
                val rest = line.substring(header.range.last + 1).trim()
                when {
                    key.startsWith("назван") -> {
                        if (title == null) title = cleanTitle(rest)
                        continue
                    }
                    key.startsWith("суть") || key.startsWith("кратко") || key.startsWith("главное") -> {
                        inAgreed = false
                        if (rest.isNotEmpty()) gist = joinGist(gist, rest) else expectGist = true
                        continue
                    }
                    key.startsWith("договор") || key.startsWith("задачи") || key.startsWith("решени") -> {
                        inAgreed = true; expectGist = false
                        if (rest.isNotEmpty()) addItem(agreed, rest, facts, agreedList = true)
                        continue
                    }
                    else -> { // «Ключевые моменты:», «Итоги:» — просто подзаголовок
                        inAgreed = false; expectGist = false
                        if (rest.isNotEmpty()) addItem(points, rest, facts, agreedList = false)
                        continue
                    }
                }
            }
            val check = CHECK_MARK.find(line)
            val bullet = BULLET.find(line)
            when {
                check != null -> {
                    expectGist = false
                    addItem(agreed, line.substring(check.range.last + 1), facts, agreedList = true)
                }
                bullet != null -> {
                    expectGist = false
                    addItem(if (inAgreed) agreed else points, line.substring(bullet.range.last + 1), facts, agreedList = inAgreed)
                }
                inAgreed -> addItem(agreed, line, facts, agreedList = true)
                expectGist || gist.isEmpty() && points.isEmpty() -> { gist = joinGist(gist, line); expectGist = false }
                else -> addItem(points, line, facts, agreedList = false)
            }
        }
        // Суть с выдуманным спикером/именем — лучше без неё
        if (gist.isNotEmpty() && facts != null && hallucinated(gist, facts)) gist = ""
        gist = firstSentences(gist.replace(LABELS, "").trim().trim('<', '>'), 2).replaceFirstChar { it.uppercaseChar() }
        if (final && gist.isNotEmpty() && gist.last().isLetterOrDigit()) gist += "."
        val gistKey = key(gist)
        // «✓ Васька предложил…» без «кто — что» — это не договорённость, а ход разговора
        val (dashed0, narrative) = agreed.partition { DASH_SPLIT.containsMatchIn(it) }
        // «Денис — разработат»: ответ оборвался на лимите токенов — что сделает, не понять
        val dashed = dashed0.filter { it.split(DASH_SPLIT, limit = 2).last().trim().split(' ').size >= 2 }
        narrative.forEach { if (points.size < MAX_POINTS && points.none { p -> key(p) == key(it) }) points += it }
        val finalAgreed = if (facts != null && !facts.commitments) emptyList() else dashed.take(MAX_AGREED)
        val finalPoints = points.filter { key(it) != gistKey }.take(MAX_POINTS)
        return ParsedSummary(title, Summary(gist = gist, points = finalPoints, agreed = finalAgreed))
    }

    /** Только «Название:» (короткая запись); если модель ответила без ярлыка — берём первую строку. */
    fun parseTitle(raw: String): String? {
        val lines = stripThink(raw).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val labeled = lines.firstOrNull { it.startsWith("Название", ignoreCase = true) }
        return cleanTitle(labeled?.substringAfter(':', "") ?: lines.firstOrNull().orEmpty())
    }

    /** Заметка map: одна-две фразы одним абзацем, без обрывка на конце. */
    fun cleanChapter(raw: String, facts: Facts?): String {
        val text = stripThink(raw).lines()
            .map { it.trim().replace(BULLET_PREFIX, "").replace(LABELS, "").replace(CHAPTER_LABEL, "").trim() }
            .filter { it.isNotEmpty() && it != "---" && !it.startsWith("Название", ignoreCase = true) }
            // «Спикер 1: …» — модель продолжила диалог вместо пересказа
            .filterNot { l -> facts != null && facts.who.any { l.startsWith("$it:") } || TRANSCRIPT_LINE.containsMatchIn(l) }
            .joinToString(" ")
        if (text.isEmpty()) return ""
        var s = firstSentences(text.replace(TIME_ECHO, "").replace(CHAPTER_OPENER, "").trim(), 2)
            .replaceFirstChar { it.uppercaseChar() }
        // Ответ упёрся в лимит токенов — отрезаем недописанное предложение
        if (!s.endsWith('.') && !s.endsWith('!') && !s.endsWith('?') && !s.endsWith('…')) {
            val cut = s.indexOfLast { it == '.' || it == '!' || it == '?' }
            s = if (cut > 20) s.substring(0, cut + 1) else "$s…"
        }
        if (facts != null && SPEAKER_REF.findAll(s).any { it.groupValues[1].toInt() !in facts.speakerNums }) {
            // Не выбрасываем всю главу — только убираем неверный номер
            s = SPEAKER_REF.replace(s) { m -> if (m.groupValues[1].toInt() in facts.speakerNums) m.value else "кто-то" }
        }
        return s
    }

    /** Текстовая версия конспекта для старого поля `summary` и потокового показа. */
    fun renderPlain(s: Summary, withChapters: Boolean = false): String = buildString {
        if (s.gist.isNotBlank()) appendLine(s.gist)
        if (s.points.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            s.points.forEach { appendLine("• $it") }
        }
        if (s.agreed.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("Договорились:")
            s.agreed.forEach { appendLine("✓ $it") }
        }
        if (withChapters && s.chapters.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("Хронология:")
            s.chapters.forEach { appendLine("${clock(it.startMs)}–${clock(it.endMs)}  ${it.text}") }
        }
    }.trim()

    /** Потоковое состояние для UI: [status] (например, «Читаю часть 3 из 17…») и уже готовый текст. */
    fun encodeLive(status: String?, text: String): String =
        if (status.isNullOrBlank()) text else "$status$LIVE_SEP$text"

    /** Обратно: статус (или null) и текст. */
    fun decodeLive(live: String?): Pair<String?, String> {
        if (live == null) return null to ""
        val i = live.indexOf(LIVE_SEP)
        return if (i < 0) null to live else live.substring(0, i) to live.substring(i + 1)
    }

    private fun addItem(list: MutableList<String>, raw: String, facts: Facts?, agreedList: Boolean) {
        var text = raw.trim().replace(BULLET_PREFIX, "").replace(INSTRUCTION_ECHO, "").replace(BULLET_PREFIX, "")
            .replace(LABELS, "").trim().trimEnd(';', ',').trim()
        text = text.replace(ORDINAL_POINT, "").trim()
        if (GENERIC_PERSON.containsMatchIn(text)) return
        // «кто – Вячеслав – что сделает – подготовить …» — модель повторила шаблон
        text = text.replace(WHO_ECHO, "").replace(WHAT_ECHO, " — ").trim()
        if (text.length < 4 || text.endsWith(":")) return
        if (text.matches(PARTICIPANTS)) return
        if (agreedList && text.matches(NOTHING_AGREED)) return
        // Цитата вместо договорённости: «Спикер 1: "Дописываю…"»
        if (agreedList && QUOTE_ITEM.containsMatchIn(text)) return
        // Шаблон из инструкции, скопированный моделью
        if (text.startsWith("кто — что", ignoreCase = true) || TEMPLATE_ECHO.matches(text) || '<' in text || '>' in text) return
        if (facts != null && hallucinated(text, facts)) return
        // «✓ Иван — пришлёт отчёт»: имя в начале тоже проверяем по разговору
        if (agreedList && facts != null && (unknownWho(text, facts) || wrongCommitter(text, facts))) return
        text = text.replaceFirstChar { it.uppercaseChar() }
        val k = key(text)
        if (list.none { key(it) == k }) list += text
    }

    /**
     * Выдумка: «Спикер N», которого нет в промпте, или имя собственное, которого нет в разговоре.
     * Имя — слово с заглавной не в начале предложения; сравниваем по первым 4 буквам (падежи).
     */
    fun hallucinated(text: String, facts: Facts): Boolean {
        if (SPEAKER_REF.findAll(text).any { it.groupValues[1].toInt() !in facts.speakerNums }) return true
        for (m in PROPER.findAll(text)) {
            val start = m.range.first
            val before = text.substring(0, start).trimEnd()
            if (before.isEmpty() || before.last() in ".!?:—–-«\"(•✓") continue
            val w = norm(m.value)
            if (w.length < 3 || w in COMMON_CAPS) continue
            if (!facts.corpus.contains(w.take(4))) return true
        }
        return false
    }

    /** «кто — что»: кто-то, кого в разговоре не было (и не «все»/«команда»). */
    fun unknownWho(text: String, facts: Facts): Boolean {
        val who = text.split(" — ", " - ", " – ", limit = 2).takeIf { it.size == 2 }?.first()?.trim() ?: return false
        if (who.split(' ').size > 3) return false
        return tokens(who).any { w ->
            w.length >= 3 && w !in GENERIC_WHO && stem(w) !in GENERIC_WHO && !facts.corpus.contains(w.take(4)) &&
                PROPER.containsMatchIn(who)
        }
    }

    /** «✓ Спикер 2 — пришлёт отчёт», а Спикер 2 ничего не обещал и к нему не обращались с поручением. */
    fun wrongCommitter(text: String, facts: Facts): Boolean {
        if (facts.committers.isEmpty()) return false
        val who = norm(text.split(" — ", " - ", " – ", limit = 2).takeIf { it.size == 2 }?.first()?.trim() ?: return false)
        val label = facts.who.map { norm(it) }.firstOrNull { it == who } ?: return false
        return label !in facts.committers
    }

    private val GENERIC_WHO = setOf(
        "все", "всем", "каждый", "команда", "команде", "мы", "они", "оба", "обе", "участники", "коллеги", "спикер",
        "стороны", "вместе", "группа",
    )

    private fun cleanTitle(s: String): String? =
        s.trim().trim('«', '»', '"', '\'', ' ', '.', ':', '<', '>').replace(Regex("\\s+"), " ")
            .takeIf { it.isNotBlank() && it.length <= 80 && !it.contains("спикер", ignoreCase = true) }

    private fun joinGist(a: String, b: String) = if (a.isEmpty()) b.trim() else "$a ${b.trim()}"

    fun firstSentences(s: String, n: Int): String {
        var count = 0
        for ((i, c) in s.withIndex()) {
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                val next = s.getOrNull(i + 1)
                if (next == null || next == ' ') {
                    count++
                    if (count == n) return s.substring(0, i + 1).trim()
                }
            }
        }
        return s.trim()
    }

    private fun shorten(s: String, max: Int): String {
        val first = firstSentences(s, 1)
        if (first.length <= max) return first
        val cut = first.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return first.substring(0, cut).trimEnd(',', ' ', ';') + "…"
    }

    private fun key(s: String) = s.lowercase().trimEnd('.', '!', ' ')

    // ------------------------------------------------------------------ вопросы

    const val SYSTEM_ASK =
        "Ты отвечаешь на вопросы по расшифровке разговора. Отвечаешь по-русски, коротко и только по тексту расшифровки."

    /** Окно для поиска: строки [from, to) и их слова. */
    private class Window(val from: Int, val to: Int, val words: List<String>)

    /**
     * Основы слов вопроса: до 5 первых букв, без стоп-слов; короткие слова — только если это имя участника.
     * У глаголов с приставкой («пообещал») добавляем и основу без неё («обеща»).
     */
    fun questionStems(question: String, names: Collection<String> = emptyList()): List<String> {
        val nameWords = names.flatMap { tokens(it) }.toSet()
        return tokens(question)
            .filter { w ->
                w in nameWords || (w.length >= 4 && !isStop(w)) || (w.length >= 2 && w.all { it.isDigit() })
            }
            .flatMap { w ->
                val p = VERB_PREFIXES.firstOrNull { w.startsWith(it) && w.length - it.length >= 5 }
                    ?.takeIf { VERB_END.containsMatchIn(w) }
                if (w in nameWords || p == null) listOf(stem(w)) else listOf(stem(w), stem(w.substring(p.length)))
            }
            .distinct()
    }

    /**
     * Фрагменты расшифровки под вопрос: окна ~500 символов со сдвигом в половину окна,
     * оценка — совпадения основ слов вопроса с весом idf. Лучшие окна до [budget] символов,
     * в хронологическом порядке, между несмежными кусками — «…».
     */
    fun retrieve(lines: List<PromptLine>, question: String, budget: Int = ASK_RETRIEVE_CHARS, window: Int = 500): String {
        val pieces = lines.flatMap { splitLong(it, 400) }
        if (pieces.isEmpty()) return ""
        val lens = pieces.map { it.render().length + 1 }
        if (lens.sum() <= budget) return renderRuns(pieces, pieces.indices.toList())

        val windows = ArrayList<Window>()
        var i = 0
        while (i < pieces.size) {
            var j = i
            var len = 0
            while (j < pieces.size && (j == i || len < window)) { len += lens[j]; j++ }
            windows += Window(i, j, tokens(pieces.subList(i, j).joinToString(" ") { it.render() }).distinct())
            if (j >= pieces.size) break
            var k = i
            var adv = 0
            while (k < j - 1 && adv < len / 2) { adv += lens[k]; k++ }
            i = max(k, i + 1)
        }

        val stems = questionStems(question, pieces.map { it.who }.distinct())
        val df = stems.associateWith { s -> windows.count { w -> w.words.any { it.startsWith(s) } } }
        val n = windows.size.toDouble()
        val scores = windows.map { w ->
            stems.sumOf { s ->
                val tf = w.words.count { it.startsWith(s) }
                if (tf == 0) 0.0 else ln(1.0 + n / df.getValue(s)) * (1.0 + 0.25 * min(tf - 1, 3))
            }
        }

        val picked = BooleanArray(pieces.size)
        var used = 0
        fun take(w: Window): Boolean {
            val add = (w.from until w.to).filter { !picked[it] }.sumOf { lens[it] }
            if (add == 0) return true
            if (used + add > budget && used > 0) return false
            for (x in w.from until w.to) {
                if (used >= budget) break
                if (!picked[x]) { picked[x] = true; used += lens[x] }
            }
            return true
        }
        val order = windows.indices.filter { scores[it] > 0 }.sortedByDescending { scores[it] }
        for (wi in order) {
            take(windows[wi])
            if (used >= budget * 0.9) break
        }
        if (used == 0) {
            // Ни одного совпадения (общий вопрос) — равномерно по всей записи
            val m = (budget / window).coerceAtLeast(1)
            for (q in 0 until m) take(windows[(q * windows.size / m).coerceAtMost(windows.size - 1)])
        }
        return renderRuns(pieces, pieces.indices.filter { picked[it] })
    }

    /** Подряд идущие строки — одним куском с временем, между кусками «…». */
    private fun renderRuns(pieces: List<PromptLine>, idx: List<Int>): String {
        if (idx.isEmpty()) return ""
        val runs = ArrayList<MutableList<Int>>()
        for (i in idx) {
            if (runs.isEmpty() || runs.last().last() != i - 1) runs += mutableListOf(i) else runs.last() += i
        }
        return runs.joinToString("\n…\n") { run ->
            val a = pieces[run.first()]
            val b = pieces[run.last()]
            "[${clock(a.startMs)}–${clock(b.endMs)}]\n" + run.joinToString("\n") { pieces[it].render() }
        }
    }

    /** Промпт для вопроса: обзор (суть + главы), расшифровка или найденные фрагменты, последние 2 вопроса. */
    fun askPrompt(lines: List<PromptLine>, digest: Summary?, history: List<QaItem>, question: String): String {
        val overview = buildString {
            if (digest != null && digest.gist.isNotBlank()) appendLine("Кратко о разговоре: ${digest.gist}")
            if (digest != null && digest.chapters.isNotEmpty()) {
                appendLine("Части разговора:")
                appendLine(chapterNotes(digest.chapters, ASK_OVERVIEW_CHARS - length))
            }
        }.trim()
        // История — только для уточняющих вопросов («а когда?», «почему он…»): иначе модель повторяет прошлый ответ
        val hist = if (!isFollowUp(question)) "" else history.filter { !it.a.startsWith(ERROR_PREFIX) }.takeLast(2).joinToString("\n") {
            "Вопрос: ${it.q.take(150)}\nОтвет: ${splitAnswer(it.a).first.take(250)}"
        }
        val full = render(lines)
        val room = ASK_CONTEXT_CHARS - overview.length - hist.length
        val transcript: String
        val header: String
        if (full.length <= min(ASK_FULL_CHARS, room)) {
            transcript = full
            header = "Расшифровка разговора (участники — по именам или «Спикер N»):"
        } else {
            transcript = retrieve(lines, question + " " + history.takeLast(1).joinToString(" ") { it.q }, min(ASK_RETRIEVE_CHARS, room.coerceAtLeast(1000)))
            header = "Фрагменты расшифровки, подходящие к вопросу (время — от начала записи; участники — по именам или «Спикер N»):"
        }
        return buildString {
            if (overview.isNotEmpty()) { appendLine(overview); appendLine() }
            appendLine(header)
            appendLine(transcript)
            appendLine()
            if (hist.isNotEmpty()) { appendLine("Предыдущие вопросы:"); appendLine(hist); appendLine() }
            appendLine("Вопрос: ${question.trim()}")
            appendLine()
            // Сначала цитата, потом ответ: маленькая модель сперва находит нужную реплику — и реже путает, кто что сказал
            appendLine("Ответь двумя строками:")
            appendLine("Цитата: «…»")
            appendLine("Ответ: …")
            appendLine("В строке «Цитата» — дословно та реплика из расшифровки, где есть ответ на вопрос. В строке «Ответ» — короткий ответ своими словами (1–2 предложения), только по расшифровке.")
            append("Если в расшифровке ответа нет, напиши только: «Ответ: $NOT_FOUND»")
        }
    }

    /**
     * Вопрос заведомо не о записи: ≥ 2 значимых слов вопроса и ни одно не встречается в расшифровке
     * («Сколько стоит ремонт крыши?»). Тогда отвечаем «не говорили» без модели — она бы что-нибудь придумала.
     */
    fun clearlyNotDiscussed(lines: List<PromptLine>, question: String): Boolean {
        // «Кто что пообещал?», «Что решили?» — вопросы о сути разговора: слова могут и не совпасть.
        // Считаем слова вопроса (не основы: «пообещал» даёт две основы), без стоп-слов и «смысловых» слов.
        val names = lines.mapTo(HashSet()) { it.who }.flatMap { tokens(it) }.toSet()
        val qWords = tokens(question).distinct().filter { w ->
            (w.length >= 4 && !isStop(w) || w in names || w.length >= 2 && w.all { it.isDigit() }) &&
                META_STEMS.none { m -> w.startsWith(m) || questionStems(w).any { it.startsWith(m) } }
        }
        if (qWords.size < 2) return false
        val words = lines.flatMapTo(HashSet()) { tokens(it.who + " " + it.text) }
        return qWords.none { w ->
            val forms = questionStems(w, names)
            forms.any { s -> words.any { it.startsWith(s) } }
        }
    }

    /**
     * Ответ модели «Цитата: … / Ответ: …» → «ответ\n\n«цитата» — кто».
     * Цитату оставляем, только если она действительно есть в расшифровке; «кто» берём из подписи этой реплики,
     * а не со слов модели. [final] = false — промежуточный (потоковый) текст.
     */
    fun cleanAnswer(raw: String, lines: List<PromptLine> = emptyList(), final: Boolean = true): String {
        // «… » Ответ: …» в одной строке — разносим по строкам
        var text = stripThink(raw)
        // Поток: недописанный тег («<thi») не показываем
        if (!final) text = text.replace(PARTIAL_TAG, "")
        var s = text.replace(INLINE_ANSWER, "\nОтвет:").lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Поток: последняя строка — начало метки («Цит», «Ответ») — ждём, пока станет ясно, что это;
        // иначе метка на миг видна как ответ и пропадает, когда допишется двоеточие
        if (!final && s.isNotEmpty() && isLabelPrefix(s.last())) s = s.dropLast(1)
        var quote: String? = null
        var inQuote = false
        val answer = StringBuilder()
        for (l in s) {
            val q = QUOTE_LABEL.find(l)
            if (q != null) {
                if (quote == null) {
                    quote = l.substring(q.range.last + 1).trim()
                    // Цитата на следующей строке («Цитата:\n«…»») или на несколько строк — до закрывающей «»»
                    inQuote = quote.isEmpty() || quote.startsWith('«') && !quote.contains('»')
                }
                continue
            }
            if (inQuote && !ANSWER_LABEL.containsMatchIn(l)) {
                val first = quote.isNullOrEmpty()
                quote = if (first) l else "$quote $l"
                inQuote = if (first) l.startsWith('«') && !l.contains('»') else !l.contains('»')
                continue
            }
            inQuote = false
            if (answer.isNotEmpty()) answer.append(' ')
            answer.append(l.replace(ANSWER_LABEL, "").trim())
        }
        var body = answer.toString().trim()
        // Хвост цитаты без начала («…до пятницы.» Барсик обещает…») — отрезаем до закрывающей кавычки
        val close = body.indexOf('»')
        if (close >= 0 && body.lastIndexOf('«', close) < 0) body = body.substring(close + 1).trim()
        var a = firstSentences(body.trim('«', '»', '"').trim(), 3)
        // Эхо инструкции («1–2 предложения по-русски…», «…») — не ответ
        if (ANSWER_ECHO.containsMatchIn(a) || a.trim('.', '…', ' ').isEmpty()) a = ""
        if (a.isEmpty() && final) {
            // Ответа нет, но модель нашла реплику — покажем её как ответ
            val src = quote?.let { findQuote(it, lines) } ?: return ""
            return "Об этом в записи:\n\n«${src.second}» — ${src.first}"
        }
        if (a.isEmpty()) return "" // пока пишется цитата — в интерфейсе «Думаю…»
        if (a.startsWith(NOT_FOUND.dropLast(1), ignoreCase = true)) return NOT_FOUND
        if (!final) return a
        val source = quote?.let { findQuote(it, lines) }
        if (source != null) a += "\n\n«${source.second}» — ${source.first}"
        return a
    }

    /** Ответ и цитата-источник (если есть) для показа. */
    fun splitAnswer(a: String): Pair<String, String?> {
        val i = a.indexOf("\n\n«")
        return if (i < 0) a to null else a.substring(0, i) to a.substring(i + 2)
    }

    /**
     * Ищет цитату модели в расшифровке: реплика, где есть ≥ 60 % слов цитаты.
     * Возвращает (кто, текст цитаты) — текст обрезаем до 160 символов.
     */
    fun findQuote(quote: String, lines: List<PromptLine>): Pair<String, String>? {
        val q = quote.substringBefore(" — ").substringBefore("» -").trim().trim('«', '»', '"', ' ')
        val words = tokens(q).filter { it.length >= 3 }.distinct()
        if (words.size < 2 || lines.isEmpty()) return null
        var best: PromptLine? = null
        var bestScore = 0.0
        for (l in lines) {
            val t = norm(l.text)
            val score = words.count { t.contains(it) }.toDouble() / words.size
            if (score > bestScore) { bestScore = score; best = l }
        }
        if (best == null || bestScore < 0.6) return null
        val text = q.removePrefix(best.who + ":").trim()
        return best.who to (if (text.length <= 160) text else text.take(157).substringBeforeLast(' ') + "…")
    }

    // ------------------------------------------------------------------ утилиты

    fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е')
    private fun tokens(s: String): List<String> = TOKEN.findAll(norm(s)).map { it.value }.toList()
    /** Основа: 5 букв; у коротких слов — без последней буквы («сроки» → «срок»), 4 буквы и меньше — целиком. */
    fun stem(w: String): String {
        val n = norm(w)
        return when {
            n.length <= 4 -> n
            n.length == 5 -> n.take(4)
            else -> n.take(5)
        }
    }

    private val VERB_PREFIXES = listOf("пере", "про", "при", "под", "по", "за", "на", "вы", "до", "об", "от", "раз", "рас")

    private const val MERGE_CHARS = 600
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
    private val THINK = Regex("(?s)<think>.*?(</think>|$)")
    private val HEADER = Regex(
        "^(Название|Суть|Кратко|Главное|Договорились|Договорённости|Договоренности|Задачи|Решения|" +
            "Ключевые моменты|Основные моменты|Моменты|Итоги|Пункты)\\s*[:：]",
        RegexOption.IGNORE_CASE,
    )
    private val BULLET = Regex("^([•*·▪●◦\\-–—]|\\d+[.)])\\s*")
    private val BULLET_PREFIX = Regex("^([•*·▪●◦\\-–—✓✔☑✅]|\\d+[.)])\\s*")
    private val CHECK_MARK = Regex("^[✓✔☑✅]\\s*")
    private val CHAPTER_LABEL = Regex("^(В этом фрагменте|В этой части|Во фрагменте|Фрагмент|Часть \\d+)\\s*[:,.—–-]?\\s*", RegexOption.IGNORE_CASE)
    private val ANSWER_ECHO = Regex("(?iu)(\\d\\s*[–-]\\s*\\d\\s*предложени|только по расшифровке|своими словами|дословно)")
    private val FOLLOW_UP = Regex("(?iu)^(а|и|но|тогда|почему|зачем|а\\s+\\S+)(?![\\p{L}])|(?<![\\p{L}])(он|она|они|его|ее|её|их|ему|ей|им|там|тогда|туда)(?![\\p{L}])")
    /** Уточняющий вопрос («А когда?», «Почему он…») — тогда нужна история. «Что решили?» — самостоятельный. */
    fun isFollowUp(q: String): Boolean = FOLLOW_UP.containsMatchIn(q.trim())
    /** Основы «вопросов о сути»: на них лексическое совпадение не обязательно. */
    private val META_STEMS = listOf(
        "обещ", "пообе", "реш", "догов", "срок", "спор", "план", "задач", "итог", "обсуж", "предл", "сказ", "говор",
        "дела", "сдела", "поруч", "отвеч", "ответс", "дедл", "когд", "глав", "основ", "тем", "вопр", "проб", "иде", "мнен",
        "согл", "возр", "довол", "недов", "цел", "резул", "вывод", "следу", "дальш", "нужн", "долж", "кто", "кому",
        "постав", "поруч", "обяза", "дежур", "встреч", "созв", "разгов", "запис",
    )
    private val INLINE_ANSWER = Regex("(?<=\\S)\\s*Ответ\\s*[:：]")
    private val QUOTE_LABEL = Regex("(?iu)^цитата\\s*[:：]\\s*")
    private val PARTIAL_TAG = Regex("<[^>\\n]*$")
    private val LABEL_WORDS = listOf("цитата", "ответ")

    /** «Ц», «Цит», «Ответ», «Ответ :» — строка, которая ещё может оказаться меткой. */
    private fun isLabelPrefix(l: String): Boolean {
        val t = l.lowercase().trimEnd(':', '：', ' ')
        return t.isNotEmpty() && LABEL_WORDS.any { it.startsWith(t) }
    }
    private val ANSWER_LABEL = Regex("^Ответ\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    /** «Третий момент: …», «Другой момент: …» */
    private val ORDINAL_POINT = Regex("(?iu)^(перв\\S*|втор\\S*|трет\\S*|четв\\S*|пят\\S*|друг\\S*|ещ[её] один|следующ\\S*|ключев\\S*)\\s+момент\\s*[:.—–-]\\s*")
    /** «Пользователь 1 отвечает за …» — безымянные «участники N», которых нет в расшифровке. */
    private val GENERIC_PERSON = Regex("(?iu)(пользовател|участник|человек|сотрудник)\\p{L}*\\s*\\d+")
    private val WHO_ECHO = Regex("(?iu)^кто\\s*[—–-]\\s*")
    private val WHAT_ECHO = Regex("(?iu)\\s*[—–-]\\s*что (сделает|сделать|решили)\\s*[—–:-]?\\s*")
    /** «Сроки: 9:37–12:18.» — модель повторила время части из заголовка. */
    private val TIME_ECHO = Regex("(?iu)\\s*(сроки|время( разговора| фрагмента)?|таймкод)\\s*:\\s*\\d{1,2}:\\d{2}(:\\d{2})?\\s*[—–-]\\s*\\d{1,2}:\\d{2}(:\\d{2})?\\.?")
    /** «Спикеры обсуждают …» → «Обсуждают …». */
    private val CHAPTER_OPENER = Regex("(?iu)^(спикеры|участники|коллеги|собеседники)\\s+(?=обсужда|говор|решают|решили|предлага|планиру)")
    private val TRANSCRIPT_LINE = Regex("^Спикер \\d+\\s*:")
    private val INSTRUCTION_ECHO = Regex("(?iu)^(ключевы[хе] момент(ов|ы)\\s*[\\d–\\-]*|формат ответа|конспект)\\s*:\\s*")
    private val TEMPLATE_ECHO = Regex("(?iu)^(первый|второй|третий|четв[её]ртый|пятый)?\\s*ключевой момент\\s*\\d*[.:]?$")
    private val DASH_SPLIT = Regex("\\s[—–-]\\s")
    private val QUOTE_ITEM = Regex("^[^—:]{1,40}:\\s*[\"«„]")
    /** «Спикеры: Спикер 1, Спикер 2» — перечень, а не пункт (но «Спикеры обсуждали …» — пункт). */
    private val PARTICIPANTS = Regex("(?iu)^(спикеры|участники)\\s*:.*")
    private val NOTHING_AGREED = Regex(
        "(?iu)^(нет|ничего|не (было|указано|договорились)|договор[её]нност(ей|и) (нет|не было)|отсутству\\S*|—|-)(?![\\p{L}\\p{N}]).*"
    )
    /** Ярлыки из инструкции, которые маленькие модели любят копировать в ответ. */
    private val LABELS = Regex(
        "^(Главная мысль|Основная идея|Основные идеи|Ключевой момент|Ещё итог|Еще итог|Итог|Тема|Суть|" +
            "Кто\\s*[—-]\\s*что сделает|Кто\\s*[—-]\\s*что сделал)\\s*[:.]?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val SPEAKER_NAME = Regex("Спикер (\\d+)")
    private val SPEAKER_REF = Regex("(?iu)спикер\\p{L}*\\s*(\\d+)")
    private val PROPER = Regex("(?<![\\p{L}\\p{N}])\\p{Lu}[\\p{Ll}]+")
    /** Слова с заглавной, которые не имена: их модель пишет сама. */
    private val COMMON_CAPS = setOf("спикер", "спикеры", "участники", "договорились", "суть", "название", "итог")

    /** Обещание от первого лица или общее решение: говорящий сам за что-то отвечает. */
    private val OWN_COMMITMENT = Regex(
        "(?<![а-яё])(" +
            "сделаю|сделаем|проверю|проверим|пришлю|пришлем|отправлю|отправим|напишу|напишем|подготовлю|подготовим|" +
            "позвоню|займусь|займемся|возьму|возьмем|принесу|куплю|купим|закажу|посмотрю|посмотрим|разберусь|допишу|" +
            "доделаю|исправлю|организую|обещаю|обещаем|договорились|договоримся|решили|принято|беру на себя|" +
            "давайте|давай|начинаем|начнем|я могу|могу|я буду|буду|будем|я должен|я должна|мы должны|успею|успеем|" +
            "дежурю|дежурим|сдам|сдадим|скину|скинем|запущу|запустим|добавлю|добавим|поправлю|поправим|обновлю|обновим" +
            ")(?![а-яё])"
    )

    /** Обещания, поручения, договорённости и сроки — без них раздел «Договорились» не просим. */
    private val COMMITMENT = Regex(
        "(?<![а-яё])(" +
            "сделаю|сделаем|проверю|проверим|пришлю|пришлём|пришлем|отправлю|отправим|напишу|подготовлю|подготовим|" +
            "позвоню|займусь|займёмся|займемся|возьму|возьмём|возьмем|принесу|куплю|купим|закажу|посмотрю|разберусь|допишу|" +
            "доделаю|исправлю|организую|обещаю|обещаем|договорились|договоримся|решили|принято|поручаю|" +
            "поручим|беру на себя|с тебя|с вас|за тобой|за вами|ответственн[а-яё]*|дедлайн[а-яё]*|срок[а-яё]*|" +
            "нужно|надо|должен|должна|должны|давайте|давай|начинаем|начнём|начнем|" +
            "до (понедельника|вторника|среды|четверга|пятницы|субботы|воскресенья|завтра|конца)|" +
            "к (понедельнику|вторнику|среде|четвергу|пятнице|субботе|воскресенью|завтрашнему)" +
            ")(?![а-яё])"
    )

    /** Стоп-слова вопроса: вопросительные, служебные и «мета»-слова о разговоре (сравниваем по [stem]). */
    private val STOP_WORDS = setOf(
        "какой", "какая", "какое", "какие", "каких", "каким", "какую", "когда", "почему", "зачем", "сколько", "который",
        "которая", "которые", "этот", "этого", "этому", "этом", "эта", "эти", "этих", "этой", "было", "была", "были",
        "будет", "будут", "есть", "очень", "просто", "вообще", "тоже", "также", "чтобы", "после", "перед", "между",
        "через", "потом", "сейчас", "здесь", "можно", "нужно", "надо", "свой", "своих", "своем", "своего", "всего",
        "всех", "того", "тому", "там", "тут", "разговор", "разговоре", "запись", "записи", "говорил", "говорили",
        "сказал", "сказала", "сказали", "обсуждали", "обсудили", "упоминали", "рассказал", "расшифровка", "спикер",
        "участники", "ответ", "вопрос", "мнение", "пожалуйста", "напомни", "назови", "назвал", "назвала", "назвали",
        "перечисли", "опиши", "объясни", "итоги", "вообще", "кого", "кому", "чего", "чему", "него", "неё", "нему",
    )
    private val STOP_STEMS = STOP_WORDS.map { stem(it) }.toSet()
    private fun isStop(w: String) = w in STOP_WORDS || stem(w) in STOP_STEMS
    /** Окончания глаголов: только у них отделяем приставку. */
    private val VERB_END = Regex("(л|ла|ли|ло|ть|ешь|ет|ем|ете|ют|ут|ят|ит|им|ите|ю|у)$")
}

package app.glagol.ml

import app.glagol.data.Utterance
import kotlin.math.max

/**
 * Имена спикеров по тексту разговора — без LLM и без интернета.
 *
 * - Обращение: «Оля, ты с нами?» → голос «Оля» тому, кто заговорил следующим (другой спикер, в пределах 5 с).
 * - Самопредставление: «меня зовут Ольга», «Это Даня.» → текущему спикеру.
 * - Сам себя по имени не зовут: кто обратился к X, тот точно не X.
 *
 * Имя присваивается только при согласованных голосах: лучше оставить «Спикер 2», чем подписать чужим именем.
 * Кандидат в имена — слово с заглавной в позиции обращения, которое есть в словаре имён или явно имя собственное
 * (встречается с заглавной посреди фразы, или после него «ты/пожалуйста/!») — так находятся и кошачьи клички.
 */
object SpeakerNamer {

    data class Result(val names: Map<String, String>, val evidence: List<String>)

    fun detect(utterances: List<Utterance>): Map<String, String> = analyze(utterances).names

    fun analyze(utts: List<Utterance>): Result {
        val evidence = ArrayList<String>()
        val parsed = utts.map { u -> sentences(u.text) }
        val allWords = HashSet<String>()
        val proper = HashSet<String>() // слова с заглавной НЕ в начале предложения
        val lower = HashSet<String>()  // слова, которые хоть раз написаны со строчной: точно не имена собственные
        for (ss in parsed) for (s in ss) for (t in s.tokens) {
            val lw = t.word.lowercase()
            allWords += lw
            if (t.word[0].isLowerCase()) lower += lw
            else if (t.index > 0 && isCap(t.word)) proper += lw
        }
        val speakers = utts.map { it.speakerId }.toSet()

        val votes = HashMap<String, HashMap<String, Double>>()       // группа имени → спикер → голоса
        val forbid = HashMap<String, HashSet<String>>()               // группа → спикеры, которые сами к ней обращались
        val forms = HashMap<String, HashMap<String, Int>>()           // группа → формы для показа
        fun vote(g: String, spk: String, w: Double) {
            votes.getOrPut(g) { HashMap() }.merge(spk, w, Double::plus)
        }

        // 1. Обращения и самопредставления
        val vocativeAt = HashSet<Pair<Int, Int>>() // (реплика, позиция слова) — чтобы не считать их упоминаниями
        for ((i, u) in utts.withIndex()) {
            val ss = parsed[i]
            for ((si, s) in ss.withIndex()) {
                selfIntro(s)?.let { w ->
                    val name = resolve(w.lowercase(), allWords) ?: return@let
                    if (!acceptable(w, s, null, proper, lower)) return@let
                    vote(name.group, u.speakerId, 3.0)
                    forms.getOrPut(name.group) { HashMap() }.merge(name.display, 2, Int::plus)
                    evidence += "${u.speakerId} представился «${name.display}» (${clock(u.startMs)})"
                }
                for (tok in vocatives(s)) {
                    if (!acceptable(tok.word, s, tok, proper, lower)) continue
                    val name = resolve(tok.word.lowercase(), allWords) ?: continue
                    vocativeAt += i to tok.globalIndex
                    forbid.getOrPut(name.group) { HashSet() } += u.speakerId
                    forms.getOrPut(name.group) { HashMap() }.merge(name.display, 1, Int::plus)
                    val where = "${u.speakerId} → «${name.display}» (${clock(u.startMs)})"
                    if (speakers.size == 2) {
                        // Двое: обращаются всегда ко второму
                        val other = speakers.first { it != u.speakerId }
                        vote(name.group, other, 1.0)
                        evidence += "$where: собеседник $other"
                        continue
                    }
                    // Вопрос/просьба → ответит следующий; «спасибо, согласен» → тому, кто говорил до этого
                    val next = addressee(utts, i)
                    val prev = previousSpeaker(utts, i)
                    val (wNext, wPrev) = when (addressKind(s, tok)) {
                        // обращение в начале длинной реплики — ответить могут не сразу
                        Kind.Ask -> (if (si == ss.lastIndex || u.text.trimEnd().endsWith("?") || isGreeting(s)) 1.0 else 0.6) to 0.0
                        Kind.Reply -> 0.0 to 1.0
                        Kind.Unknown -> 0.6 to 0.4
                    }
                    if (next != null && wNext > 0) vote(name.group, next, wNext)
                    if (prev != null && wPrev > 0) vote(name.group, prev, wPrev)
                    evidence += "$where: следующий ${next ?: "—"} ×$wNext, предыдущий ${prev ?: "—"} ×$wPrev"
                }
            }
        }
        if (votes.isEmpty()) return Result(emptyMap(), evidence)

        // 2. Упоминания в 3-м лице («Оля вышла»): говорящий, скорее всего, не Оля — слабый минус
        for ((i, u) in utts.withIndex()) for (s in parsed[i]) for (t in s.tokens) {
            if (!isCap(t.word) || (i to t.globalIndex) in vocativeAt) continue
            val g = resolve(t.word.lowercase(), allWords)?.group ?: continue
            if (g in votes) vote(g, u.speakerId, -0.3)
        }

        // 3. Решение: согласованные голоса, каждое имя и каждый спикер — не больше одного раза
        data class Cand(val group: String, val spk: String, val score: Double)
        val cands = ArrayList<Cand>()
        for ((g, bySpk) in votes) {
            val total = bySpk.values.sumOf { max(0.0, it) }
            for ((spk, v) in bySpk) {
                if (spk in forbid[g].orEmpty()) continue
                if (v < MIN_SCORE || v / total < MIN_SHARE) continue
                val spkTotal = votes.values.sumOf { max(0.0, it[spk] ?: 0.0) }
                if (v / spkTotal < MIN_SPEAKER_SHARE) continue
                cands += Cand(g, spk, v)
            }
        }
        val names = LinkedHashMap<String, String>()
        val usedGroups = HashSet<String>()
        for (c in cands.sortedByDescending { it.score }) {
            if (c.spk in names || c.group in usedGroups) continue
            val display = forms[c.group]?.maxByOrNull { it.value }?.key ?: c.group
            names[c.spk] = display.replaceFirstChar { it.uppercaseChar() }
            usedGroups += c.group
            evidence += "ИТОГ: ${c.spk} = ${names[c.spk]} (голосов ${"%.1f".format(c.score)})"
        }
        return Result(names, evidence)
    }

    // ------------------------------------------------------------------ разбор текста

    private class Token(val word: String, val punct: Char?, val index: Int, val globalIndex: Int)
    private class Sentence(val tokens: List<Token>)

    private val WORD = Regex("[А-ЯЁа-яё]+(?:-[А-ЯЁа-яё]+)*")
    private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

    private fun sentences(text: String): List<Sentence> {
        var g = 0
        return text.split(SENTENCE_END).map { st ->
            val toks = WORD.findAll(st).mapIndexed { i, m ->
                val after = st.substring(m.range.last + 1).trimStart().firstOrNull()
                Token(m.value, after?.takeIf { it in ",.!?…:;" }, i, g++)
            }.toList()
            Sentence(toks)
        }.filter { it.tokens.isNotEmpty() }
    }

    /** «Оля» — да; «ИИ», «оля», «О'кей» — нет. */
    private fun isCap(w: String) = w.length >= 2 && w[0].isUpperCase() && w.drop(1).all { !it.isUpperCase() }

    /** Слова в позиции обращения: «Оля, …», «Так, Даня, …», «…, Оля?», «Никита?», «А Никита, …». */
    private fun vocatives(s: Sentence): List<Token> {
        val t = s.tokens
        val res = ArrayList<Token>()
        for ((i, tok) in t.withIndex()) {
            if (tok.word.length < 3 || !isCap(tok.word)) continue
            val p = tok.punct
            val last = i == t.lastIndex
            val ok = if (i == 0) {
                p == ',' || p == '!' || p == '?' || (last && t.size == 1)
            } else {
                val opened = t[i - 1].punct == ',' || t.subList(0, i).all { it.word.lowercase() in FILLERS }
                opened && (p == ',' || p == '!' || p == '?' || last)
            }
            if (ok) res += tok
        }
        return res
    }

    private val INTRO = Regex("(?i)меня зовут\\s+([А-ЯЁ][а-яё]+)")
    private val INTRO_SHORT = Regex("^(?:Алло,?\\s*)?(?:Это|Я)\\s+([А-ЯЁ][а-яё]+)[.!]?$")

    private fun selfIntro(s: Sentence): String? {
        val text = s.tokens.joinToString(" ") { it.word } // без пунктуации
        INTRO.find(text)?.let { return it.groupValues[1] }
        if (s.tokens.size <= 3) INTRO_SHORT.find(text)?.let { return it.groupValues[1] }
        return null
    }

    /** Похоже ли слово на имя (а не на «Короче», «Ребята», «Давай»). */
    private fun acceptable(word: String, s: Sentence, tok: Token?, proper: Set<String>, lower: Set<String>): Boolean {
        val lw = word.lowercase()
        if (lw in STOP) return false
        if (lookup(lw) != null) return true
        // Не из словаря (кошачьи клички и редкие имена): никогда не пишется со строчной («задача», «например» — пишутся),
        // окончание как у имени и явный признак имени собственного
        if (lw in lower) return false
        if (lw.last() in "оеёуыэюйиь") return false
        if (lw in proper) return true
        if (tok == null) return false
        if (tok.punct == '!' || tok.punct == '?') return true
        val next = s.tokens.getOrNull(tok.index + 1)?.word?.lowercase()
        if (next != null && (next in CUE || looksImperative(next))) return true
        return s.tokens.any { it.word.equals("пожалуйста", ignoreCase = true) }
    }

    private fun looksImperative(w: String) =
        w.length >= 4 && w !in NOT_IMPERATIVE &&
            (w.endsWith("ь") || w.endsWith("ьте") || w.endsWith("й") || w.endsWith("йте") || w.endsWith("и") || w.endsWith("ите"))

    private enum class Kind { Ask, Reply, Unknown }

    /** «Оля, ты с нами?», «Барсик, проверь…» — Ask; «Спасибо, Дань», «Лер, я согласен» — Reply. */
    private fun addressKind(s: Sentence, tok: Token): Kind {
        val words = s.tokens.map { it.word.lowercase() }
        if (words.any { it in REPLY }) return Kind.Reply
        if (s.tokens.last().punct == '?') return Kind.Ask
        val next = s.tokens.getOrNull(tok.index + 1)?.word?.lowercase()
        if (next != null && (next in CUE || looksImperative(next))) return Kind.Ask
        if (isGreeting(s) || words.contains("пожалуйста")) return Kind.Ask
        return Kind.Unknown
    }

    private fun isGreeting(s: Sentence) = s.tokens.any { it.word.lowercase() in GREETING }

    /** Кто говорил прямо перед обращением (другой спикер, в пределах окна). */
    private fun previousSpeaker(utts: List<Utterance>, i: Int): String? {
        val a = utts[i].speakerId
        var firstStart = utts[i].startMs
        var j = i - 1
        while (j >= 0 && i - j <= 4) {
            val u = utts[j]
            if (u.endMs < firstStart - WINDOW_MS) return null
            if (u.speakerId != a) return u.speakerId
            firstStart = minOf(firstStart, u.startMs)
            j--
        }
        return null
    }

    /** Тот, кто ответил на обращение: первый другой спикер в пределах окна; продолжения фраз (со строчной) пропускаем. */
    private fun addressee(utts: List<Utterance>, i: Int): String? {
        val a = utts[i].speakerId
        var lastEnd = utts[i].endMs
        var looked = 0
        var j = i + 1
        while (j < utts.size && looked < 4) {
            val u = utts[j]
            if (u.startMs > lastEnd + WINDOW_MS) return null
            if (u.speakerId == a) {
                lastEnd = max(lastEnd, u.endMs)
            } else {
                looked++
                val first = u.text.firstOrNull { it.isLetter() }
                if (first != null && first.isUpperCase()) return u.speakerId
            }
            j++
        }
        return null
    }

    // ------------------------------------------------------------------ имена

    private class Name(val group: String, val display: String)

    /** Форма из текста → группа (один человек) и форма для показа: «дань» → (даниил, даня). */
    private fun resolve(lw: String, allWords: Set<String>): Name? {
        lookup(lw)?.let { return it }
        // Усечённое обращение к кличке: «Мурк» → «Мурка», если полная форма есть в тексте
        val full = listOf(lw + "а", lw + "я").firstOrNull { it in allWords }
        return Name(full ?: lw, full ?: lw)
    }

    private fun lookup(lw: String): Name? = DICT[lw]

    private val DICT: Map<String, Name> by lazy {
        val m = HashMap<String, Name>()
        for (line in NAMES) {
            val forms = line.split(' ').filter { it.isNotBlank() }
            val group = forms.first()
            for (f in forms) {
                m.putIfAbsent(f, Name(group, f))
                // Звательная форма: «Даня» → «Дань», «Саша» → «Саш», «Никита» → «Никит»
                val stem = f.dropLast(1)
                val voc = when {
                    f.endsWith("я") && stem.length >= 2 && stem.last() !in "аеёиоуыэюяь" -> stem + "ь"
                    f.endsWith("а") && stem.length >= 3 -> stem
                    else -> null
                }
                if (voc != null) m.putIfAbsent(voc, Name(group, f))
            }
        }
        m
    }

    private fun clock(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private const val WINDOW_MS = 5000L
    private const val MIN_SCORE = 1.0
    /** Доля голосов за имя, доставшаяся этому спикеру. */
    private const val MIN_SHARE = 0.6
    /** Доля голосов спикера, доставшаяся этому имени (если за спикера «голосуют» разные имена — диаризация спутала людей). */
    private const val MIN_SPEAKER_SHARE = 0.5

    private val REPLY = setOf(
        "спасибо", "благодарю", "согласен", "согласна", "согласны", "прав", "права", "правы", "молодец", "молодцы",
    )

    private val GREETING = setOf("привет", "здравствуй", "здравствуйте", "добрый", "доброе", "приветствую")

    private val FILLERS = setOf("а", "ну", "и", "так", "о", "ой", "эй", "вот", "да", "слушай", "ага")

    private val CUE = setOf(
        "ты", "тебя", "тебе", "тобой", "твой", "твоя", "вы", "вас", "вам", "пожалуйста",
        "давай", "давайте", "скажи", "скажите", "расскажи", "расскажите", "иди", "идите", "смотри", "слушай",
    )

    private val NOT_IMPERATIVE = setOf(
        "они", "мои", "твои", "свои", "эти", "все", "свой", "мой", "твой", "такой", "какой", "этой", "той",
        "моей", "ней", "кстати", "очень", "опять", "здесь", "почти", "тоже", "уже", "если", "чтобы",
    )

    private val STOP = setOf(
        "да", "нет", "ну", "так", "вот", "вон", "короче", "слушай", "слушайте", "смотри", "смотрите", "ребята", "ребят",
        "коллеги", "коллега", "друзья", "друг", "народ", "алло", "спасибо", "привет", "здравствуй", "здравствуйте",
        "давай", "давайте", "ладно", "хорошо", "окей", "значит", "кстати", "конечно", "наверное", "может", "итак",
        "блин", "боже", "господи", "мама", "мам", "папа", "пап", "отлично", "понятно", "ясно", "супер", "класс",
        "ага", "угу", "ого", "извини", "извините", "простите", "прости", "секунду", "подожди", "подождите", "стоп",
        "внимание", "честно", "правда", "точно", "верно", "господа", "товарищи", "мужики", "девочки", "девчонки",
        "тихо", "здорово", "пока", "погоди", "сейчас", "вообще", "добрый", "доброе", "спокойной", "итак",
        "кошки", "котики", "котик", "кот", "кошка", "малыш", "дорогой", "дорогая", "милый", "милая", "уважаемые",
        "хозяин", "хозяйка", "бабушка", "дедушка", "сынок", "дочка", "сестра", "брат", "мальчики", "товарищ",
        "например", "допустим", "получается", "правильно", "собственно", "видишь", "знаешь", "понимаешь",
        "представь", "кажется", "похоже", "наоборот", "однако", "итого", "всё", "все", "смотрите", "давно",
        "сири", "салют", // голосовые ассистенты
    )

    /** Частые имена: первое — группа, дальше уменьшительные и разговорные формы того же человека. */
    private val NAMES = listOf(
        "александр саша саня шура санёк", "алексей лёша алёша леша", "анатолий толя", "андрей андрюша",
        "антон антоша", "аркадий аркаша", "арсений арсюша сеня", "артём артем тёма", "артур", "богдан", "борис боря",
        "вадим вадик", "валентин", "валерий валера", "василий вася васька", "виктор витя", "виталий виталик",
        "владимир вова володя", "владислав влад владик", "вячеслав слава", "геннадий гена", "георгий гоша жора",
        "глеб", "григорий гриша", "давид", "даниил данил данила даня данька данечка", "денис дениска",
        "дмитрий дима митя", "евгений женя", "егор егорка", "иван ваня", "игорь", "илья илюша", "кирилл кирюша",
        "константин костя", "лев лёва", "леонид лёня", "максим макс", "марк", "матвей", "михаил миша",
        "никита", "николай коля", "олег", "павел паша", "пётр петя", "роман рома", "руслан", "семён сёма",
        "сергей серёжа сережа", "станислав стас", "степан стёпа", "тимофей тима", "тимур", "фёдор федя",
        "филипп", "эдуард эдик", "юрий юра", "ярослав ярик", "яков", "эмиль", "рустам", "айдар", "арсен",
        "александра", "алина", "алиса", "алла", "алёна", "анастасия настя", "ангелина", "анна аня анечка",
        "антонина тоня", "арина", "валентина валя", "валерия лера", "варвара варя", "вера", "вероника ника",
        "виктория вика", "галина галя", "дарья даша", "диана", "ева", "евгения", "екатерина катя", "елена лена",
        "елизавета лиза", "жанна", "зоя", "инна", "ирина ира иришка", "карина", "кира", "кристина", "ксения ксюша",
        "лариса", "лидия лида", "любовь люба", "людмила люда", "маргарита рита", "марина", "мария маша",
        "милана", "надежда надя", "наталья наталия наташа", "нина", "оксана", "ольга оля", "полина",
        "светлана света", "софия софья соня", "тамара", "татьяна таня", "ульяна", "юлия юля", "яна", "эльвира",
    )
}

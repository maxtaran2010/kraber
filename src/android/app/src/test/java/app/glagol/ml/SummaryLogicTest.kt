package app.glagol.ml

import app.glagol.data.Chapter
import app.glagol.data.QaItem
import app.glagol.data.Recording
import app.glagol.data.Summary
import app.glagol.data.Utterance
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryLogicTest {

    private fun line(i: Int, who: String, text: String) = PromptLine(i * 10_000L, i * 10_000L + 8_000, who, text)

    private val catLines = listOf(
        line(0, "Спикер 1", "Добрый день, коллеги. Начинаем заседание котов."),
        line(1, "Мурка", "У меня отчёт: за неделю мы съели 12 пакетов корма."),
        line(2, "Барсик", "Пиццу трогать было нельзя, это запасы на праздник."),
        line(3, "Мурка", "Хорошо, я проверю запасы до пятницы и пришлю список."),
    )
    private val catFacts = SummaryLogic.facts(catLines)

    // ------------------------------------------------------------ разбор ответа

    @Test fun dropsPointsThatRepeatEachOtherOrAgreements() {
        // Реальный ответ Qwen3 0.6B: одно и то же разными словами
        val lines = listOf(
            line(0, "Спикер 1", "Барсик, проверь, пожалуйста, склад до пятницы."),
            line(1, "Барсик", "Принято, проверю до пятницы и пришлю отчёт."),
            line(2, "Спикер 2", "За неделю мы съели двенадцать пакетов корма."),
        )
        val raw = """
            Название: Заседание котов
            Суть: Обсуждаются запасы корма.
            • Участники проверяют склад до пятницы и пришьют отчёт.
            • Спикер 1 проверяет склад и пришлёт отчёт.
            • Барсик проверяет склад и пришлёт отчёт.
            • За неделю съели двенадцать пакетов корма.
            Договорились:
            ✓ Барсик — проверить склад до пятницы и прислать отчёт.
            ✓ Барсик — проверить склад и прислать отчёт.
        """.trimIndent()
        val s = SummaryLogic.parseSummary(raw, SummaryLogic.facts(lines)).summary
        assertEquals(listOf("Барсик — проверить склад до пятницы и прислать отчёт."), s.agreed)
        assertEquals(listOf("За неделю съели двенадцать пакетов корма."), s.points)
    }

    @Test fun keepsDifferentPointsAboutSimilarTopics() {
        val items = listOf(
            "Спикер 1 рассказал про бюджет на рекламу",
            "Спикер 2 рассказал про бюджет на дизайн",
            "Решили перенести встречу на пятницу",
        )
        assertEquals(items, SummaryLogic.dropNearDuplicates(items))
    }


    @Test fun parsesStrictFormat() {
        val raw = """
            Название: Отчёт о запасах корма
            Суть: Коты обсудили расход корма и пиццу. Решили проверить запасы.
            • За неделю съели 12 пакетов корма.
            • Барсик считает пиццу запасом на праздник.
            Договорились:
            ✓ Мурка — проверит запасы до пятницы и пришлёт список
        """.trimIndent()
        val p = SummaryLogic.parseSummary(raw, catFacts)
        assertEquals("Отчёт о запасах корма", p.title)
        assertEquals("Коты обсудили расход корма и пиццу. Решили проверить запасы.", p.summary.gist)
        assertEquals(listOf("За неделю съели 12 пакетов корма.", "Барсик считает пиццу запасом на праздник."), p.summary.points)
        assertEquals(listOf("Мурка — проверит запасы до пятницы и пришлёт список"), p.summary.agreed)
    }

    @Test fun gistOnNextLineAndMarkdown() {
        val raw = "<think></think>**Название:** Заседание котов\n**Суть:**\nОбсуждали корм.\n- Съели 12 пакетов.\n1) Пицца — запас."
        val p = SummaryLogic.parseSummary(raw, catFacts)
        assertEquals("Заседание котов", p.title)
        assertEquals("Обсуждали корм.", p.summary.gist)
        assertEquals(listOf("Съели 12 пакетов.", "Пицца — запас."), p.summary.points)
    }

    @Test fun headerAsBulletAndInstructionEcho() {
        val raw = "Название: Заседание котов\nСуть: Обсуждают корм.\n• Ключевых моментов 1–2: Съели 12 пакетов.\n• Договорились: ✓ Барсик — проверит склад до пятницы"
        val lines = listOf(line(0, "Спикер 1", "Барсик, проверь склад до пятницы."), line(1, "Барсик", "Проверю до пятницы. Съели 12 пакетов."))
        val p = SummaryLogic.parseSummary(raw, SummaryLogic.facts(lines))
        assertEquals(listOf("Съели 12 пакетов."), p.summary.points)
        assertEquals(listOf("Барсик — проверит склад до пятницы"), p.summary.agreed)
    }

    @Test fun dropsHallucinatedSpeakersAndNames() {
        val raw = """
            Название: Заседание
            Суть: Обсуждали корм.
            • Спикер 3 предлагает купить рыбу.
            • Мурка отчиталась о 12 пакетах.
            • Затем Иван предложил купить рыбу.
            Договорились:
            ✓ Иван — купит рыбу
            ✓ Мурка — проверит запасы
            ✓ Нет договорённостей
        """.trimIndent()
        val p = SummaryLogic.parseSummary(raw, catFacts)
        assertEquals(listOf("Мурка отчиталась о 12 пакетах."), p.summary.points)
        assertEquals(listOf("Мурка — проверит запасы"), p.summary.agreed)
    }

    @Test fun agreedOnlyFromThoseWhoCommitted() {
        // dialog1: обещал Барсик (и к нему обращались с поручением), Спикер 2 ничего не обещал
        val lines = listOf(
            line(0, "Спикер 2", "Здравствуйте. У меня короткий отчёт про запасы корма."),
            line(1, "Спикер 2", "За неделю мы съели 12 пакетов корма и половину пиццы."),
            line(2, "Спикер 1", "Хорошо, Барсик, проверь, пожалуйста, склад до пятницы."),
            line(3, "Барсик", "Принято. Проверю до пятницы и пришлю отчёт."),
        )
        val facts = SummaryLogic.facts(lines)
        val raw = "Суть: Отчёт о корме.\nДоговорились:\n✓ Спикер 2 — предоставит отчёт до пятницы\n✓ Барсик — проверит склад до пятницы\n✓ Все — следующее заседание в среду"
        assertEquals(
            listOf("Барсик — проверит склад до пятницы", "Все — следующее заседание в среду"),
            SummaryLogic.parseSummary(raw, facts).summary.agreed,
        )
    }

    @Test fun ordinalPrefixesAndAnonymousPeople() {
        val raw = "Суть: Обсуждали корм.\n• Другой момент: Съели 12 пакетов корма.\n• Пользователь 1 отвечает за корм."
        assertEquals(listOf("Съели 12 пакетов корма."), SummaryLogic.parseSummary(raw, catFacts).summary.points)
    }

    @Test fun noAgreedWithoutCommitments() {
        val lines = listOf(line(0, "Спикер 1", "Какая сегодня хорошая погода."), line(1, "Спикер 2", "Да, солнечно и тепло."))
        val facts = SummaryLogic.facts(lines)
        assertFalse(facts.commitments)
        val p = SummaryLogic.parseSummary("Суть: Говорят о погоде.\nДоговорились:\n✓ Спикер 1 — пойдёт гулять", facts)
        assertTrue(p.summary.agreed.isEmpty())
        assertEquals("Говорят о погоде.", p.summary.gist)
    }

    @Test fun titleOnly() {
        assertEquals("Приветствие", SummaryLogic.parseTitle("Название: «Приветствие»."))
        assertEquals("Встреча друзей", SummaryLogic.parseTitle("Встреча друзей"))
        assertNull(SummaryLogic.parseTitle("Название: Спикер 1 и Спикер 2"))
    }

    @Test fun chapterCutsUnfinishedSentence() {
        val c = SummaryLogic.cleanChapter("В этом фрагменте: Обсуждают бюджет на рекламу. Решили, что Мурка пришлёт отчёт к пят", catFacts)
        assertEquals("Обсуждают бюджет на рекламу.", c)
    }

    @Test fun reduceEchoes() {
        val raw = "Суть: Обсуждали корм.\n• Спикеры обсуждали 12 пакетов корма.\nДоговорились:\n✓ кто – Вячеслав – что сделает – купить корм\n✓ кто – Мурка – что сделает – проверить склад"
        val p = SummaryLogic.parseSummary(raw, catFacts)
        assertEquals(listOf("Спикеры обсуждали 12 пакетов корма."), p.summary.points)
        assertEquals(listOf("Мурка — проверить склад"), p.summary.agreed)
    }

    @Test fun chapterEchoes() {
        assertEquals(
            "Обсуждают запасы корма на 12 пакетов.",
            SummaryLogic.cleanChapter("Спикеры обсуждают запасы корма на 12 пакетов. Сроки: 9:37–12:18.", catFacts),
        )
    }

    @Test fun chapterDropsContinuedDialogue() {
        val raw = "Спикер 1: Тебе нужна какая то помощь по этим задачам?\nМурка: Мне пока что нет."
        assertEquals("", SummaryLogic.cleanChapter(raw, catFacts))
    }

    @Test fun plainRendering() {
        val s = Summary("Суть.", listOf("Раз", "Два"), listOf("Мурка — отчёт"), listOf(Chapter(0, 65_000, "Начало.")))
        assertEquals("Суть.\n\n• Раз\n• Два\n\nДоговорились:\n✓ Мурка — отчёт", SummaryLogic.renderPlain(s))
        assertTrue(SummaryLogic.renderPlain(s, withChapters = true).endsWith("0:00–1:05  Начало."))
    }

    @Test fun liveRoundTrip() {
        val enc = SummaryLogic.encodeLive("Читаю часть 3 из 17…", "• готово")
        assertEquals("Читаю часть 3 из 17…" to "• готово", SummaryLogic.decodeLive(enc))
        assertEquals(null to "просто текст", SummaryLogic.decodeLive("просто текст"))
    }

    // ------------------------------------------------------------ строки и нарезка

    @Test fun promptLinesUseNamesAndMergeSameSpeaker() {
        val rec = Recording(id = "r", title = "t", sourceName = "a.wav", createdAt = 0, speakerNames = mapOf("speaker_2" to "Оля"))
        val utts = listOf(
            Utterance("speaker_1", 0, 1000, "Привет."),
            Utterance("speaker_2", 1500, 2500, "Привет!"),
            Utterance("speaker_2", 2700, 4000, "Как дела?"),
        )
        val lines = SummaryLogic.promptLines(rec, utts)
        assertEquals(listOf("Спикер 1: Привет.", "Оля: Привет! Как дела?"), lines.map { it.render() })
        assertEquals(1500L to 4000L, lines[1].startMs to lines[1].endMs)
    }

    private fun longDialog(n: Int): List<PromptLine> = (0 until n).map { i ->
        line(i, "Спикер ${i % 3 + 1}", "Реплика номер $i про задачу ${i * 7} и ещё немного слов, чтобы было длиннее обычного.")
    }

    @Test fun chunksFollowUtteranceBoundaries() {
        val lines = longDialog(120)
        val chunks = SummaryLogic.chunk(lines, 2500)
        assertTrue(chunks.size > 1)
        // Все строки на месте и по порядку, без разрезов внутри реплик
        assertEquals(lines, chunks.flatMap { it.lines })
        for (c in chunks) {
            assertTrue(c.text.length <= 2500)
            assertEquals(c.lines.first().startMs, c.startMs)
            assertEquals(c.lines.last().endMs, c.endMs)
        }
        // Части примерно равны: последняя не огрызок
        val sizes = chunks.map { it.text.length }
        assertTrue("sizes=$sizes", sizes.min() > sizes.max() / 2)
        // Время частей идёт подряд
        chunks.zipWithNext { a, b -> assertTrue(a.endMs <= b.startMs) }
    }

    @Test fun longMonologueIsSplitBySentences() {
        val text = (1..60).joinToString(" ") { "Предложение номер $it о важном деле." }
        val l = PromptLine(0, 600_000, "Оля", text)
        val chunks = SummaryLogic.chunk(listOf(l), 800)
        assertTrue(chunks.size >= 3)
        for (c in chunks) assertTrue(c.text.length <= 800)
        assertEquals(0L, chunks.first().startMs)
        assertEquals(600_000L, chunks.last().endMs)
        assertEquals(text, chunks.flatMap { it.lines }.joinToString(" ") { it.text })
        chunks.zipWithNext { a, b -> assertTrue(a.endMs <= b.startMs && a.startMs < b.startMs) }
    }

    @Test fun reduceNotesFitBudget() {
        val chapters = (0 until 20).map { Chapter(it * 180_000L, (it + 1) * 180_000L, "Обсуждают пункт $it. " + "Подробности. ".repeat(20)) }
        val notes = SummaryLogic.chapterNotes(chapters, SummaryLogic.REDUCE_CHARS)
        assertTrue(notes.length <= SummaryLogic.REDUCE_CHARS)
        assertTrue(notes.startsWith("[0:00–3:00] Обсуждают пункт 0."))
        assertTrue(notes.contains("[57:00–1:00:00]"))
    }

    // ------------------------------------------------------------ вопросы

    @Test fun questionStems() {
        val stems = SummaryLogic.questionStems("Какие сроки назвала Оля и что она пообещала?", listOf("Оля", "Спикер 2"))
        assertTrue(stems.toString(), "срок" in stems)
        assertTrue(stems.toString(), "оля" in stems)
        assertTrue(stems.toString(), "пообе" in stems && "обеща" in stems)
        assertFalse(stems.toString(), "каки" in stems || "какие" in stems)
    }

    @Test fun retrievalFindsTheAnswerWindow() {
        val filler = longDialog(200)
        val answer = PromptLine(1_000_000, 1_008_000, "Мурка", "Бюджет на рекламу утвердили: триста тысяч рублей до конца квартала.")
        val lines = filler.subList(0, 100) + answer + filler.subList(100, 200)
        val ctx = SummaryLogic.retrieve(lines, "Какой бюджет на рекламу утвердили?", budget = 1200)
        assertTrue(ctx, ctx.contains("триста тысяч"))
        assertTrue(ctx.length <= 1200 + 200)
        assertTrue(ctx.startsWith("["))
    }

    @Test fun retrievalKeepsChronologicalOrder() {
        val filler = longDialog(200)
        val a = PromptLine(100_000, 108_000, "Оля", "Дедлайн по отчёту — среда.")
        val b = PromptLine(1_800_000, 1_808_000, "Оля", "Повторяю: дедлайн по отчёту перенесли на пятницу.")
        val lines = filler.subList(0, 10) + a + filler.subList(10, 180) + b + filler.subList(180, 200)
        val ctx = SummaryLogic.retrieve(lines, "Какой дедлайн по отчёту?", budget = 1500)
        val ia = ctx.indexOf("среда")
        val ib = ctx.indexOf("пятницу")
        assertTrue(ctx, ia in 0 until ib)
        assertTrue(ctx.contains("…"))
    }

    @Test fun askPromptShortTranscriptIsWhole() {
        val p = SummaryLogic.askPrompt(
            catLines, Summary(gist = "Коты обсуждают корм."),
            listOf(QaItem("Сколько съели?", "12 пакетов.", 0), QaItem("Ошибка?", SummaryLogic.ERROR_PREFIX + "сбой", 1)),
            "А кто это пообещал?",
        )
        assertTrue(p.contains("Кратко о разговоре: Коты обсуждают корм."))
        catLines.forEach { assertTrue(p.contains(it.render())) }
        assertTrue(p.contains("Вопрос: Сколько съели?\nОтвет: 12 пакетов."))
        assertFalse(p.contains("сбой"))
        assertTrue(p.contains(SummaryLogic.NOT_FOUND))
        assertTrue(p.trimEnd().endsWith("»"))
    }

    @Test fun askPromptLongTranscriptFitsBudget() {
        val lines = longDialog(600)
        val chapters = (0 until 17).map { Chapter(it * 180_000L, (it + 1) * 180_000L, "Обсуждают пункт $it и решают, кто что сделает. Ещё детали.") }
        val p = SummaryLogic.askPrompt(lines, Summary(gist = "Длинный созвон.", chapters = chapters), emptyList(), "Что про задачу 2100?")
        assertTrue(p.length.toString(), p.length < 4000)
        assertTrue(p.contains("задачу 2100"))
        assertTrue(p.contains("Части разговора:"))
    }

    /** Промпт + ответ должны влезть в 2048 токенов: ≈ 2.3 символа на токен, ответ до 300 токенов. */
    @Test fun promptsFitContext() {
        val lines = longDialog(400)
        val facts = SummaryLogic.facts(lines)
        val single = SummaryLogic.summaryPrompt(SummaryLogic.render(lines).take(SummaryLogic.SINGLE_PASS_CHARS), facts)
        assertTrue("single ${single.length}", single.length < 4000)
        val chunks = SummaryLogic.chunk(lines)
        chunks.forEachIndexed { i, c ->
            val m = SummaryLogic.mapPrompt(c, i, chunks.size)
            assertTrue("map ${m.length}", m.length < 3300)
        }
        val chapters = (0 until 25).map { Chapter(it * 120_000L, (it + 1) * 120_000L, "Заметка по части $it. " + "Детали обсуждения. ".repeat(8)) }
        val reduce = SummaryLogic.reducePrompt(chapters, facts)
        assertTrue("reduce ${reduce.length}", reduce.length < 3800)
    }

    /** Ответ приходит по кусочкам: на экране не должны мелькать метки «Цит…» / «Отв…», текст не должен пропадать. */
    @Test fun streamingAnswerDoesNotFlicker() {
        val raw = "<think>\n\n</think>\n\nЦитата: «Принято. Проверю до пятницы и пришлю отчёт.»\nОтвет: Барсик обещал проверить склад до пятницы."
        var shown = ""
        for (n in 1..raw.length) {
            val a = SummaryLogic.cleanAnswer(raw.substring(0, n), final = false)
            if (shown.isNotEmpty()) assertTrue("пропал текст на «${raw.substring(0, n)}»", a.isNotEmpty())
            assertFalse("мелькнула метка: «$a»", a.startsWith("Ц", true) || a.startsWith("Отв", true) || a.startsWith("<"))
            shown = a
        }
        assertEquals("Барсик обещал проверить склад до пятницы.", shown)
    }

    @Test fun cleanAnswer() {
        assertEquals("Мурка проверит запасы до пятницы.", SummaryLogic.cleanAnswer("<think>\n</think>\nОтвет: Мурка проверит запасы до пятницы."))
        assertEquals(SummaryLogic.NOT_FOUND, SummaryLogic.cleanAnswer("Цитата: —\nОтвет: В записи об этом не говорили."))
    }

    @Test fun followUpDetection() {
        assertTrue(SummaryLogic.isFollowUp("А когда?"))
        assertTrue(SummaryLogic.isFollowUp("Почему он отказался от графика дежурств?"))
        assertFalse(SummaryLogic.isFollowUp("Сколько стоит ремонт крыши в подвале?"))
        assertFalse(SummaryLogic.isFollowUp("Что решили про диван и лежанку?"))
        assertFalse(SummaryLogic.isFollowUp("Кто что пообещал?"))
        assertFalse(SummaryLogic.isFollowUp("О чём этот созвон?"))
        // Не уточняющий вопрос — без истории, чтобы модель не повторяла прошлый ответ
        val p = SummaryLogic.askPrompt(catLines, null, listOf(QaItem("Сколько съели?", "12 пакетов.", 0)), "Сколько стоит ремонт крыши в подвале?")
        assertFalse(p.contains("Предыдущие вопросы"))
    }

    @Test fun quoteOnNextLineAndDanglingQuoteTail() {
        // «Цитата:» пустая, сама цитата строкой ниже — не должна попасть в ответ
        val raw = "Цитата:\n«Хорошо, я проверю запасы до пятницы и пришлю список.»\nОтвет: Мурка проверит запасы до пятницы."
        assertEquals(
            "Мурка проверит запасы до пятницы.\n\n«Хорошо, я проверю запасы до пятницы и пришлю список.» — Мурка",
            SummaryLogic.cleanAnswer(raw, catLines),
        )
        // Хвост цитаты без открывающей кавычки в начале ответа — отрезается
        assertEquals(
            "Мурка обещает проверить запасы до пятницы.",
            SummaryLogic.cleanAnswer("Ответ: проверю запасы до пятницы.» Мурка обещает проверить запасы до пятницы."),
        )
    }

    @Test fun multiLineQuoteAndInlineAnswer() {
        val raw = "Цитата: «Хорошо, я проверю запасы\nдо пятницы и пришлю список.» Ответ: Мурка проверит запасы до пятницы."
        assertEquals(
            "Мурка проверит запасы до пятницы.\n\n«Хорошо, я проверю запасы до пятницы и пришлю список.» — Мурка",
            SummaryLogic.cleanAnswer(raw, catLines),
        )
    }

    @Test fun notDiscussedWithoutModel() {
        assertTrue(SummaryLogic.clearlyNotDiscussed(catLines, "Сколько стоит ремонт крыши?"))
        assertFalse(SummaryLogic.clearlyNotDiscussed(catLines, "Какие сроки?"))
        assertFalse(SummaryLogic.clearlyNotDiscussed(catLines, "Сколько пакетов корма съели?"))
        // Вопросы о сути — всегда к модели, даже если слова не совпали
        assertFalse(SummaryLogic.clearlyNotDiscussed(catLines, "Кто что пообещал?"))
        assertFalse(SummaryLogic.clearlyNotDiscussed(catLines, "О чём спорили участники?"))
        // Подсказки-чипы и типовые вопросы о сути — никогда не отвечаем без модели
        for (q in listOf("Что решили?", "Кто что пообещал?", "Какие сроки?", "О чём спорили?",
                "Кто за что отвечает?", "Какие задачи поставили?", "О чём этот созвон?", "Какие выводы сделали в итоге?")) {
            assertFalse(q, SummaryLogic.clearlyNotDiscussed(catLines, q))
        }
    }

    @Test fun answerEchoFallsBackToQuote() {
        val raw = "Цитата: «Хорошо, я проверю запасы до пятницы и пришлю список»\nОтвет: 1–2 предложения по-русски, только по расшифровке."
        assertEquals("Об этом в записи:\n\n«Хорошо, я проверю запасы до пятницы и пришлю список» — Мурка", SummaryLogic.cleanAnswer(raw, catLines))
    }

    @Test fun answerQuoteIsVerifiedAndAttributedByTranscript() {
        // Модель перепутала, кто сказал, — подпись берём из расшифровки
        val raw = "Цитата: «Хорошо, я проверю запасы до пятницы и пришлю список» — Барсик\nОтвет: Мурка проверит запасы до пятницы."
        val a = SummaryLogic.cleanAnswer(raw, catLines)
        assertEquals("Мурка проверит запасы до пятницы.\n\n«Хорошо, я проверю запасы до пятницы и пришлю список» — Мурка", a)
        assertEquals("Мурка проверит запасы до пятницы." to "«Хорошо, я проверю запасы до пятницы и пришлю список» — Мурка", SummaryLogic.splitAnswer(a))
        // Выдуманная цитата — без источника
        val b = SummaryLogic.cleanAnswer("Цитата: «Купим новый диван в субботу»\nОтвет: Купят диван.", catLines)
        assertEquals("Купят диван.", b)
        // Пока пишется цитата — ответа ещё нет
        assertEquals("", SummaryLogic.cleanAnswer("Цитата: «Хорошо, я про", catLines, final = false))
    }

    @Test fun oldMetaStillLoads() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"id":"r1","title":"Т","sourceName":"a.wav","createdAt":1,"summary":"• старый","summaryStatus":"Done"}"""
        val rec = json.decodeFromString(Recording.serializer(), old)
        assertNull(rec.digest)
        assertTrue(rec.qa.isEmpty())
        assertEquals("• старый", rec.summary)
        val withNew = rec.copy(digest = Summary(gist = "Суть."), qa = listOf(QaItem("q", "a", 2)))
        assertEquals(withNew, json.decodeFromString(Recording.serializer(), json.encodeToString(Recording.serializer(), withNew)))
    }

    @Test fun linesSurviveIpcJson() {
        val enc = SummaryLogic.encodeLines(catLines)
        assertTrue(enc.contains("\"w\":\"Мурка\""))
        assertEquals(catLines, SummaryLogic.decodeLines(enc))
    }
}

package app.glagol.ml

import app.glagol.data.TranscriptFile
import app.glagol.data.Utterance
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeakerNamerTest {

    private var t = 0L
    private fun u(spk: Int, text: String): Utterance {
        val start = t
        t += 2500
        return Utterance("speaker_$spk", start, start + 2000, text)
    }

    private fun names(vararg utts: Utterance) = SpeakerNamer.detect(utts.toList())

    @Test fun vocativeAtStart() = assertEquals(
        mapOf("speaker_2" to "Оля"),
        names(u(1, "Оля, ты с нами?"), u(2, "Да, я тут."), u(3, "Я тоже.")),
    )

    @Test fun twoSpeakersAddressTheOther() = assertEquals(
        mapOf("speaker_2" to "Мурка"),
        names(u(1, "Добрый вечер, Мурка! Давай быстро обсудим запасы корма."), u(2, "Давай.")),
    )

    @Test fun thanksGoToPreviousSpeaker() = assertEquals(
        // «Спасибо, Дань» — благодарят того, кто только что говорил; «Дань» → «Даня»
        mapOf("speaker_2" to "Даня"),
        names(u(1, "Начинаем."), u(2, "Вот такой у меня план."), u(1, "Спасибо, Дань."), u(3, "Можно мне?")),
    )

    @Test fun bareQuestion() = assertEquals(
        mapOf("speaker_2" to "Никита"),
        names(u(1, "Так. Никита?"), u(2, "Да, у меня всё готово."), u(3, "Хорошо.")),
    )

    @Test fun vocativeAfterIntroWord() = assertEquals(
        mapOf("speaker_3" to "Мурка"),
        names(u(1, "Мы начинаем."), u(2, "Хорошо."), u(1, "Конечно, Мурка, пожалуйста."), u(3, "Спасибо, я быстро.")),
    )

    @Test fun lowercaseContinuationIsSkipped() = assertEquals(
        mapOf("speaker_3" to "Антон"),
        names(u(1, "Антон, ты разобрался"), u(2, "с задачей?"), u(3, "Да, всё закончил.")),
    )

    @Test fun nobodyCallsThemselvesByName() {
        // speaker_2 сам обращается к Вере — значит, он не Вера, даже если ему «отвечали» как Вере
        val n = names(u(1, "Вера, посмотри."), u(2, "Хорошо."), u(2, "Вера, а ты что думаешь?"), u(3, "Согласна."))
        assertTrue(n["speaker_2"] != "Вера")
    }

    @Test fun thirdPersonNamesAreNotAssigned() = assertEquals(
        emptyMap<String, String>(),
        names(u(1, "Вчера Барсик опять спал на диване."), u(2, "Да, а Пушок ловил мышей всю ночь.")),
    )

    @Test fun discourseWordsAreNotNames() = assertEquals(
        emptyMap<String, String>(),
        names(u(1, "Короче, ты понял?"), u(2, "Ребята, давайте начнём."), u(1, "Слушай, да."), u(2, "Честно, не знаю.")),
    )

    @Test fun selfIntroduction() = assertEquals(
        mapOf("speaker_1" to "Ольга"),
        names(u(1, "Здравствуйте, меня зовут Ольга."), u(2, "Очень приятно.")),
    )

    @Test fun catNameNotInDictionary() = assertEquals(
        mapOf("speaker_2" to "Мурка"),
        names(u(1, "Добрый вечер, Мурка! Как дела на кухне?"), u(2, "Всё хорошо, корма хватает."), u(3, "Мяу.")),
    )

    @Test fun commonWordsWrittenLowercaseAreNotNames() = assertEquals(
        // ASR иногда пишет обычные слова с заглавной посреди фразы — но где-то они есть и со строчной
        emptyMap<String, String>(),
        names(u(1, "Это фактически Задача на завтра."), u(2, "Задача, которая переносится?"), u(3, "Да, такая задача есть.")),
    )

    @Test fun conflictingVotesAbstain() = assertEquals(
        emptyMap<String, String>(),
        names(u(1, "Даня, ты как?"), u(2, "Нормально."), u(1, "Даня, а ты?"), u(3, "Тоже нормально.")),
    )

    /**
     * Реальный вывод приложения (ASR + диаризация) на тестовом наборе: `tools/eval/device_eval.sh` кладёт его в
     * .cache/test/<name>.dev.json. Если файлов нет (свежий клон) — проверка пропускается.
     */
    @Test fun deviceOutputsOfTestSet() {
        val dir = File("../../.cache/test")
        val json = Json { ignoreUnknownKeys = true }
        fun load(name: String) = File(dir, "$name.dev.json").takeIf { it.exists() }
            ?.let { json.decodeFromString(TranscriptFile.serializer(), it.readText()).transcript }

        // эталон: кто есть кто в сценариях tools/eval/make_piper_dialogs.py и make_test_dialog.py
        val expected = mapOf(
            "piper_2spk" to mapOf("speaker_2" to setOf("Мурка", "Морка")),
            "piper_3spk" to mapOf("speaker_2" to setOf("Мурка", "Морка"), "speaker_3" to setOf("Васька")),
            "piper_noisy" to mapOf("speaker_2" to setOf("Мурка", "Морка"), "speaker_3" to setOf("Васька")),
            "piper_4spk" to mapOf("speaker_2" to setOf("Мурка"), "speaker_3" to setOf("Рыжик"), "speaker_4" to setOf("Соня")),
            "piper_fast" to emptyMap(),
            "dialog1" to mapOf("speaker_3" to setOf("Барсик")),
        )
        var found = 0
        var total = 0
        for ((file, exp) in expected) {
            val utts = load(file) ?: continue
            val res = SpeakerNamer.analyze(utts)
            println("== $file: ${res.names}")
            res.evidence.forEach { println("   $it") }
            for ((spk, name) in res.names) {
                assertTrue("$file: $spk = $name — неверное имя", exp[spk]?.contains(name) == true)
            }
            found += res.names.size
            total += exp.size
        }
        println("Найдено имён: $found из $total, ошибок нет")

        load("operkontur1")?.let { utts ->
            val res = SpeakerNamer.analyze(utts)
            println("== operkontur1: ${res.names}")
            res.evidence.forEach { println("   $it") }
        }
    }
}

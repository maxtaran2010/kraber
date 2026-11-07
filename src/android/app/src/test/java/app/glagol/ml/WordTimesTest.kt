package app.glagol.ml

import app.glagol.data.Utterance
import app.glagol.ui.detail.wordRanges
import app.glagol.ui.detail.wordTimesFor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Таймкоды слов для подсветки при проигрывании: words.json и запасной вариант для старых записей. */
class WordTimesTest {

    private fun w(text: String, s: Float, e: Float, turn: Boolean = false) = Word(text, s, e, turnStart = turn)

    @Test fun wordTimesMatchUtteranceWords() {
        val words = listOf(
            w("Добрый", 0.5f, 0.9f, turn = true), w("день.", 0.9f, 1.3f),
            w("Привет!", 1.6f, 2.0f, turn = true),
            w("Начинаем.", 4.0f, 4.6f, turn = true),
        )
        val turns = listOf(SpeakerTurn(0f, 1.4f, 0), SpeakerTurn(1.5f, 2.2f, 1), SpeakerTurn(3.9f, 5f, 0))
        val r = TranscriptAssembler.assemble(words, turns)
        assertEquals(r.utterances.size, r.wordTimes.size)
        r.utterances.forEachIndexed { i, u ->
            assertEquals("слов в «${u.text}»", u.text.split(' ').size * 2, r.wordTimes[i].size)
        }
        assertArrayEquals(longArrayOf(500, 900, 900, 1300), r.wordTimes[0])
        // таймкоды слов не раньше начала реплики (с учётом небольшого запаса) и по возрастанию
        r.utterances.forEachIndexed { i, u ->
            val t = r.wordTimes[i]
            assertTrue(t[0] >= u.startMs)
            for (k in 2 until t.size step 2) assertTrue(t[k] >= t[k - 2])
        }
    }

    @Test fun rangesSplitBySpaces() {
        val text = "За неделю — 12 пакетов."
        val r = wordRanges(text)
        assertEquals(listOf("За", "неделю", "—", "12", "пакетов."), r.map { text.substring(it.first, it.last + 1) })
    }

    @Test fun storedTimesAreUsedWhenTheyFit() {
        val u = Utterance("speaker_1", 1000, 3000, "раз два")
        val stored = longArrayOf(1000, 1500, 2000, 2900)
        assertArrayEquals(stored, wordTimesFor(u, wordRanges(u.text), stored))
    }

    @Test fun fallbackSpreadsWordsByLength() {
        val u = Utterance("speaker_1", 1000, 3000, "аа бббббб")
        val t = wordTimesFor(u, wordRanges(u.text), null)
        assertEquals(4, t.size)
        assertEquals(1000, t[0])
        assertEquals(3000, t[3])
        // короткое слово занимает меньше времени, чем длинное
        assertTrue(t[1] - t[0] < t[3] - t[2])
        // несовпадающие сохранённые таймкоды игнорируются
        assertArrayEquals(t, wordTimesFor(u, wordRanges(u.text), longArrayOf(1, 2)))
    }
}

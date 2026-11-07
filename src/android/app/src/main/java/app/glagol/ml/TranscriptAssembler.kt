package app.glagol.ml

import app.glagol.data.Utterance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Слова + спикерские отрезки → реплики:
 * спикер каждого слова по перекрытию, сглаживание «дребезга», сдвиг смены спикера к концу предложения,
 * склейка слов одного спикера в реплики, нумерация speaker_N по первому появлению.
 */
object TranscriptAssembler {

    data class Params(
        /** Короткая вставка другого спикера внутри непрерывной речи — ошибка диаризации. */
        val jitterWords: Int = 2,
        val jitterSec: Float = 0.6f,
        val jitterGap: Float = 0.3f,
        /** Смену спикера двигаем к концу предложения, если он не дальше N слов. */
        val snapWords: Int = 2,
        /** Пауза, после которой начинается новая реплика того же спикера, с. */
        val splitGap: Float = 1.0f,
        /** Длинную реплику одного спикера делим на абзацы по концу предложения после N с (монолог, режим «1 спикер»). */
        val maxUttSec: Float = 20f,
        /** Небольшой запас перед первым словом реплики, с. */
        val lead: Float = 0.05f,
        val tail: Float = 0.0f,
    )

    /**
     * [wordTimes] — для каждой реплики таймкоды её слов `[s0, e0, s1, e1, …]` в мс, в порядке `text.split(' ')`
     * (для подсветки слов при проигрывании; в JSON-экспорт не идут).
     */
    data class Result(
        val utterances: List<Utterance>,
        val speakerCount: Int,
        val speakerShare: List<Float>,
        val wordTimes: List<LongArray> = emptyList(),
    )

    private val SENTENCE_END = charArrayOf('.', '?', '!', '…')

    fun assemble(words: List<Word>, turns: List<SpeakerTurn>, p: Params = Params()): Result {
        if (words.isEmpty()) return Result(emptyList(), 0, emptyList(), emptyList())
        assign(words, turns)
        smooth(words, p)
        if (p.snapWords > 0) snap(words, p.snapWords)
        return build(words, p)
    }

    private fun assign(words: List<Word>, turns: List<SpeakerTurn>) {
        if (turns.isEmpty()) { words.forEach { it.speaker = 0 }; return }
        val sorted = turns.sortedBy { it.start }
        for (w in words) {
            var best = -1
            var bestOv = 0f
            for (t in sorted) {
                if (t.start > w.end) break
                val ov = minOf(t.end, w.end) - maxOf(t.start, w.start)
                if (ov > bestOv) { bestOv = ov; best = t.speaker }
            }
            if (best < 0) {
                val mid = (w.start + w.end) / 2
                best = sorted.minBy { t -> if (mid in t.start..t.end) 0f else minOf(abs(t.start - mid), abs(t.end - mid)) }.speaker
            }
            w.speaker = best
        }
    }

    private fun runs(words: List<Word>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < words.size) {
            var j = i
            while (j + 1 < words.size && words[j + 1].speaker == words[i].speaker) j++
            out += i..j
            i = j + 1
        }
        return out
    }

    private fun smooth(words: List<Word>, p: Params) {
        var changed = true
        while (changed) {
            changed = false
            val rs = runs(words)
            for (k in 1 until rs.size - 1) {
                val r = rs[k]
                val prev = rs[k - 1]
                val next = rs[k + 1]
                if (words[prev.first].speaker != words[next.first].speaker) continue
                val dur = words[r.last].end - words[r.first].start
                val gapBefore = words[r.first].start - words[prev.last].end
                val gapAfter = words[next.first].start - words[r.last].end
                // Законченная короткая фраза («Да.», «Что?») — настоящая реплика, не трогаем
                val whole = (words[r.first].turnStart || words[prev.last].text.endsWithAny()) && words[r.last].text.endsWithAny()
                if (whole) continue
                val short = (r.last - r.first + 1) <= p.jitterWords || dur < p.jitterSec
                if (short && gapBefore < p.jitterGap && gapAfter < p.jitterGap) {
                    for (t in r) words[t].speaker = words[prev.first].speaker
                    changed = true
                    break
                }
            }
        }
    }

    private fun snap(words: List<Word>, maxShift: Int) {
        for (b in 1 until words.size) {
            if (words[b].speaker == words[b - 1].speaker) continue
            if (words[b - 1].text.endsWithAny() || words[b].turnStart) continue
            var bestC = -1
            var bestD = Float.MAX_VALUE
            for (c in max(1, b - maxShift)..minOf(words.size - 1, b + maxShift)) {
                // Тире GigaAM — более сильный признак смены реплики, чем точка
                val d = when {
                    words[c].turnStart -> abs(c - b) - 0.5f
                    words[c - 1].text.endsWithAny() -> abs(c - b).toFloat()
                    else -> continue
                }
                if (d < bestD) { bestD = d; bestC = c }
            }
            if (bestC < 0) continue
            if (bestC < b) for (t in bestC until b) words[t].speaker = words[b].speaker
            else for (t in b until bestC) words[t].speaker = words[b - 1].speaker
        }
    }

    private fun String.endsWithAny() = isNotEmpty() && last() in SENTENCE_END

    private fun build(words: List<Word>, p: Params): Result {
        data class U(val spk: Int, val start: Float, var end: Float, val text: StringBuilder, val times: ArrayList<Long> = ArrayList())
        val us = ArrayList<U>()
        for (w in words) {
            val last = us.lastOrNull()
            // Длинная реплика одного спикера (монолог, «1 спикер») — новый абзац на ближайшем конце предложения
            val paragraph = last != null && last.text.isNotEmpty() && last.text.last() in SENTENCE_END && w.end - last.start > p.maxUttSec
            val u = if (last != null && last.spk == w.speaker && w.start - last.end < p.splitGap && !paragraph) {
                last.text.append(' ').append(w.text)
                last.end = max(last.end, w.end)
                last
            } else {
                U(w.speaker, w.start, w.end, StringBuilder(w.text)).also { us += it }
            }
            u.times += (w.start * 1000).roundToLong()
            u.times += (w.end * 1000).roundToLong()
        }
        val order = LinkedHashMap<Int, Int>()
        us.forEach { order.getOrPut(it.spk) { order.size } }
        val share = FloatArray(order.size)
        us.forEach { share[order.getValue(it.spk)] += it.end - it.start }
        val total = share.sum().coerceAtLeast(1e-3f)
        val utterances = us.map { u ->
            Utterance(
                speakerId = "speaker_${order.getValue(u.spk) + 1}",
                startMs = (max(0f, u.start - p.lead) * 1000).roundToLong(),
                endMs = ((u.end + p.tail) * 1000).roundToLong(),
                text = u.text.toString(),
            )
        }
        return Result(utterances, order.size, share.map { it / total }, us.map { it.times.toLongArray() })
    }
}

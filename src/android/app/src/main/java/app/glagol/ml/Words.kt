package app.glagol.ml

import kotlin.math.max
import kotlin.math.min

/**
 * Слово с таймкодами в секундах от начала записи.
 * [turnStart] — перед словом GigaAM поставил тире (так модель размечает смену реплики в диалоге).
 */
data class Word(
    val text: String,
    val start: Float,
    val end: Float,
    val turnStart: Boolean = false,
    var speaker: Int = 0,
)

/** Слова из токенов ASR: токен с ведущим пробелом (или «▁») начинает новое слово. */
object WordAligner {

    private val PUNCT = Regex("^[.,?!…:;]+$")
    private val DASH = Regex("^[-–—]+$")

    fun fromAsr(res: AsrResult, segStart: Float, segEnd: Float): List<Word> {
        if (res.text.isBlank()) return emptyList()
        if (res.tokens.isEmpty() || res.timestamps.size != res.tokens.size) return evenly(res.text, segStart, segEnd)

        class W(val text: StringBuilder, val start: Float, var last: Float, val turn: Boolean)
        val ws = ArrayList<W>()
        var newWord = true
        var dash = false
        for (i in res.tokens.indices) {
            var tok = res.tokens[i]
            val t = segStart + res.timestamps[i]
            if (tok.startsWith(" ") || tok.startsWith("▁")) {
                newWord = true
                tok = tok.substring(1)
                if (tok.isEmpty()) continue
            }
            if (DASH.matches(tok.trim())) {
                dash = true
                newWord = true
                continue
            }
            if (ws.isNotEmpty() && PUNCT.matches(tok)) {
                ws.last().text.append(tok)
                continue
            }
            if (newWord || ws.isEmpty()) {
                ws += W(StringBuilder(tok), t, t, dash)
                newWord = false
                dash = false
            } else {
                ws.last().text.append(tok)
                ws.last().last = t
            }
        }
        return ws.mapIndexed { i, w ->
            val end = if (i + 1 < ws.size) min(ws[i + 1].start, w.last + WORD_TAIL) else min(segEnd, w.last + LAST_WORD_TAIL)
            Word(w.text.toString(), w.start, max(end, w.start + MIN_WORD), turnStart = w.turn || i == 0)
        }
    }

    /** Таймстемпов нет — распределяем слова по куску пропорционально длине. */
    private fun evenly(text: String, s: Float, e: Float): List<Word> {
        val parts = text.trim().split(Regex("\\s+"))
        val total = parts.sumOf { it.length + 1 }.toFloat()
        var t = s
        return parts.map { p ->
            val d = (e - s) * (p.length + 1) / total
            Word(p, t, t + d).also { t += d }
        }
    }

    private const val WORD_TAIL = 0.35f
    private const val LAST_WORD_TAIL = 0.5f
    private const val MIN_WORD = 0.08f
}

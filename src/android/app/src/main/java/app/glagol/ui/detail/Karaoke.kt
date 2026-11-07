package app.glagol.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import app.glagol.data.Utterance
import kotlin.math.abs

/** Диапазоны символов слов в тексте реплики (слова разделены пробелом — так их склеивает сборщик реплик). */
fun wordRanges(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var i = 0
    while (i < text.length) {
        while (i < text.length && text[i] == ' ') i++
        if (i >= text.length) break
        val s = i
        while (i < text.length && text[i] != ' ') i++
        out += s until i
    }
    return out
}

/**
 * Таймкоды слов `[s0, e0, s1, e1, …]`: из words.json, если они есть и сходятся с текстом,
 * иначе — равномерно по буквам внутри реплики (старые записи).
 */
fun wordTimesFor(u: Utterance, ranges: List<IntRange>, stored: LongArray?): LongArray {
    if (stored != null && stored.size == ranges.size * 2) return stored
    val total = ranges.sumOf { it.last - it.first + 2 }.coerceAtLeast(1)
    val span = (u.endMs - u.startMs).coerceAtLeast(1)
    val out = LongArray(ranges.size * 2)
    var acc = 0
    ranges.forEachIndexed { k, r ->
        out[2 * k] = u.startMs + span * acc / total
        acc += r.last - r.first + 2
        out[2 * k + 1] = u.startMs + span * acc / total
    }
    return out
}

/** Индекс последнего слова, которое уже началось к моменту [pos]; −1 — ещё ни одно. */
private fun currentWord(times: LongArray, pos: Long): Int {
    var lo = 0
    var hi = times.size / 2 - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (times[2 * mid] <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/**
 * Текст реплики с караоке-подсветкой: сказанные слова — обычным цветом, будущие — приглушённо,
 * под текущим словом плавно «едет» плашка цвета спикера.
 */
@Composable
fun KaraokeText(
    text: String,
    ranges: List<IntRange>,
    times: LongArray,
    position: State<Long>,
    accent: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val idx by remember(times) { derivedStateOf { currentWord(times, position.value) } }
    val future = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val annotated = remember(text, idx, accent, future) {
        buildAnnotatedString {
            append(text)
            val nextStart = ranges.getOrNull(idx + 1)?.first
            if (nextStart != null) addStyle(SpanStyle(color = future), nextStart, text.length)
            ranges.getOrNull(idx)?.let { addStyle(SpanStyle(color = accent), it.first, it.last + 1) }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val target: Rect? = remember(layout, idx) {
        val l = layout ?: return@remember null
        val r = ranges.getOrNull(idx) ?: return@remember null
        if (r.last >= l.layoutInput.text.length) return@remember null
        val a = l.getBoundingBox(r.first)
        val b = l.getBoundingBox(r.last)
        if (abs(a.top - b.top) < 1f) Rect(a.left, a.top, b.right, a.bottom)
        else Rect(a.left, a.top, l.getLineRight(l.getLineForOffset(r.first)), a.bottom)
    }
    val anim = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        // Новая строка — переносим плашку сразу, без «полёта» по диагонали
        if (anim.value == Rect.Zero || abs(anim.value.top - t.top) > 1f) anim.snapTo(t)
        else anim.animateTo(t, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    }
    val density = LocalDensity.current
    val padX = with(density) { 4.dp.toPx() }
    val padY = with(density) { 1.dp.toPx() }
    val radius = with(density) { 8.dp.toPx() }
    Text(
        annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            if (target != null && anim.value != Rect.Zero) {
                val r = anim.value
                drawRoundRect(
                    color = accent.copy(alpha = 0.24f),
                    topLeft = Offset(r.left - padX, r.top + padY),
                    size = Size(r.width + 2 * padX, r.height - 2 * padY),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
        },
    )
}

/** Маленький «эквалайзер» — признак того, что реплика сейчас звучит. */
@Composable
fun PlayingBars(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bars")
    val h = listOf(520, 380, 640).mapIndexed { i, d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d), RepeatMode.Reverse), label = "bar$i")
    }
    Canvas(modifier.size(14.dp, 12.dp)) {
        val w = size.width / 5
        h.forEachIndexed { i, a ->
            val k = if (playing) a.value else 0.3f
            val bh = size.height * k
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * w, size.height - bh),
                size = Size(w, bh),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

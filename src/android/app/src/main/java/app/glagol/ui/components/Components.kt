package app.glagol.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.glagol.ui.theme.LocalSpeakerPalette

/** Круглый аватар спикера: первая буква имени, если оно известно, иначе номер. */
@Composable
fun SpeakerAvatar(index: Int, modifier: Modifier = Modifier, size: Dp = 32.dp, name: String? = null) {
    val c = LocalSpeakerPalette.current[index]
    Box(
        modifier.size(size).clip(CircleShape).background(c.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "${index + 1}",
            color = c.onContainer,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = (size.value * 0.42f).sp),
        )
    }
}

/** Стопка мягких кружков-спикеров для карточки записи (число — в подписи рядом). */
@Composable
fun SpeakerDots(count: Int, modifier: Modifier = Modifier, ring: Color = MaterialTheme.colorScheme.surfaceContainerLow) {
    if (count <= 0) return
    val shown = count.coerceAtMost(5)
    val dot = 20.dp
    val step = 13.dp
    Box(modifier.width(dot + step * (shown - 1)).height(dot)) {
        for (i in shown - 1 downTo 0) {
            val c = LocalSpeakerPalette.current[i]
            Box(
                Modifier
                    .offset(x = step * i)
                    .size(dot)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(c.container),
            )
        }
    }
}

/** Небольшая «таблетка» с иконкой и значением. */
@Composable
fun MetaPill(icon: ImageVector, text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, null, Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Плейсхолдер-скелетон для текста, который ещё генерируется. */
@Composable
fun ShimmerLines(lines: Int = 3, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val alpha by t.animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f * alpha)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(lines) { i ->
            Box(
                Modifier
                    .fillMaxWidth(if (i == lines - 1) 0.6f else 1f - i * 0.08f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(color)
            )
        }
    }
}

/** Живая «волна» из столбиков: последние уровни громкости, новые справа. */
@Composable
fun Waveform(levels: List<Float>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val bar = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val count = ((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
        val data = if (levels.size >= count) levels.takeLast(count) else List(count - levels.size) { 0f } + levels
        val midY = size.height / 2
        data.forEachIndexed { i, lv ->
            val h = (size.height * (0.06f + 0.94f * lv)).coerceAtLeast(bar)
            val x = i * (bar + gap)
            val a = 0.35f + 0.65f * (i.toFloat() / count)
            drawRoundRect(
                color = color.copy(alpha = a),
                topLeft = Offset(x, midY - h / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2, bar / 2),
            )
        }
    }
}

/**
 * Живая «жидкая» волна: три слоя синусоид, сужающихся к краям, с градиентом.
 * Амплитуда плавно идёт за громкостью (быстрая атака, медленный спад), фаза всё время течёт.
 * [active] = false — волна затихает до тонкой линии.
 */
@Composable
fun LiveWave(level: Float, active: Boolean, colors: List<Color>, modifier: Modifier = Modifier) {
    val target by rememberUpdatedState(if (active) level.coerceIn(0f, 1f) else 0f)
    val running by rememberUpdatedState(active)
    var phase by remember { mutableFloatStateOf(0f) }
    var amp by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0f, 0.05f)
                last = t
                phase += dt * (if (running) 2.2f + 5f * amp else 0.8f)
                amp += (target - amp) * (if (target > amp) 0.28f else 0.07f)
            }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val mid = size.height / 2
        val n = 72
        // (масштаб амплитуды, частота, сдвиг фазы, прозрачность)
        val layers = listOf(
            floatArrayOf(1.00f, 1.15f, 0.0f, 0.30f),
            floatArrayOf(0.78f, 1.85f, 2.1f, 0.45f),
            floatArrayOf(0.55f, 2.70f, 4.2f, 0.85f),
        )
        for (l in layers) {
            val a = (0.035f + 0.92f * amp) * l[0] * mid
            val path = Path()
            val top = FloatArray(n + 1)
            for (i in 0..n) {
                val u = i.toFloat() / n
                val env = kotlin.math.sin(Math.PI * u).toFloat().let { it * it }
                val wave = 0.55f + 0.45f * kotlin.math.sin(2 * Math.PI * l[1] * u + phase * (1f + l[2] * 0.15f) + l[2]).toFloat()
                top[i] = a * env * wave
            }
            path.moveTo(0f, mid)
            for (i in 0..n) path.lineTo(w * i / n, mid - top[i])
            for (i in n downTo 0) path.lineTo(w * i / n, mid + top[i])
            path.close()
            drawPath(path, Brush.horizontalGradient(colors.map { it.copy(alpha = it.alpha * l[3]) }))
        }
    }
}

/**
 * Выбор числа спикеров: Авто · 1 · 2 · 3 · 4 · 5 · 6.
 * Своя «таблетка» вместо SegmentedButton — подписи не переносятся даже в узком диалоге.
 */
@Composable
fun SpeakerCountSelector(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(0, 1, 2, 3, 4, 5, 6)
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            "Сколько человек говорит",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Surface(shape = CircleShape, color = cs.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(4.dp).height(44.dp)) {
                options.forEach { n ->
                    val selected = value == n
                    val bg by animateColorAsState(if (selected) cs.primary else Color.Transparent, label = "segBg")
                    val fg by animateColorAsState(if (selected) cs.onPrimary else cs.onSurfaceVariant, label = "segFg")
                    Box(
                        Modifier
                            .weight(if (n == 0) 1.35f else 1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(bg)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onChange(n) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (n == 0) "Авто" else "$n",
                            color = fg,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

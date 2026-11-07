package app.glagol.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Слои: (масштаб амплитуды, число лепестков k, скорость фазы, сдвиг фазы, прозрачность).
// k — целые, чтобы контур замыкался без шва; соседние слои крутятся в разные стороны.
private val Layers = arrayOf(
    floatArrayOf(1.00f, 3f, 1.00f, 0.0f, 0.30f),
    floatArrayOf(0.78f, 5f, -1.25f, 2.1f, 0.45f),
    floatArrayOf(0.55f, 7f, 1.60f, 4.2f, 0.85f),
)

private const val POINTS = 144

/**
 * Круговая «жидкая» волна — полярный вариант телефонного LiveWave:
 * r(θ) = R0 + a·env·(0.55 + 0.45·sin(kθ + phase·speed + shift)), три слоя со sweep-градиентом.
 * Амплитуда идёт за [level] (быстрая атака, медленный спад), фаза течёт быстрее на громком звуке;
 * в тишине — спокойное «дыхание».
 */
@Composable
fun RadialWave(level: Float, colors: List<Color>, modifier: Modifier = Modifier) {
    val target by rememberUpdatedState(level.coerceIn(0f, 1f))
    var time by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var amp by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0f, 0.05f)
                last = t
                time += dt
                phase += dt * (1.2f + 5f * amp)
                amp += (target - amp) * (if (target > amp) 0.28f else 0.07f)
            }
        }
    }
    val paths = remember { Array(Layers.size) { Path() } }
    Canvas(modifier) {
        val c = center
        val half = size.minDimension / 2
        // Базовый радиус чуть дышит; волна доходит почти до края экрана
        val r0 = half * 0.50f * (1f + 0.02f * sin(time * 1.3f))
        val maxA = half * 0.42f
        val breath = 0.07f + 0.03f * sin(time * 1.1f)
        val env = breath + (1f - breath) * amp
        // Градиент медленно вращается — даже в тишине экран живой
        rotate(degrees = time * 14f, pivot = c) {
            Layers.forEachIndexed { li, l ->
                val a = maxA * l[0] * env
                val p = paths[li]
                p.reset()
                for (i in 0..POINTS) {
                    val th = (2 * PI * i / POINTS).toFloat()
                    val r = r0 + a * (0.55f + 0.45f * sin(l[1] * th + phase * l[2] + l[3]))
                    val x = c.x + r * cos(th)
                    val y = c.y + r * sin(th)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                drawPath(p, Brush.sweepGradient(colors.map { it.copy(alpha = it.alpha * l[4]) }, center = c))
            }
        }
    }
}

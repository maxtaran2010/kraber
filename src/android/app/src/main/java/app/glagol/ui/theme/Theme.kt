package app.glagol.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.glagol.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ---------- Цвета: seed «глубокий индиго» ----------

private val Light = lightColorScheme(
    primary = Color(0xFF4355B9), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F1), onTertiaryContainer = Color(0xFF2D1228),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680), outlineVariant = Color(0xFFC7C5D0),
    inverseSurface = Color(0xFF303036), inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFBAC3FF),
    surfaceTint = Color(0xFF4355B9),
    surfaceBright = Color(0xFFFBF8FF), surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4), surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC3C5DD), onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434659), onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7), onTertiary = Color(0xFF44263D),
    tertiaryContainer = Color(0xFF5D3C55), onTertiaryContainer = Color(0xFFFFD7F1),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF46464F),
    inverseSurface = Color(0xFFE4E1E9), inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF4355B9),
    surfaceTint = Color(0xFFBAC3FF),
    surfaceBright = Color(0xFF38393F), surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF29292F),
    surfaceContainerHighest = Color(0xFF34343A),
)

// ---------- Цвета спикеров: из системной темы, приглушённые ----------

@Immutable
data class SpeakerColor(val accent: Color, val container: Color, val onContainer: Color)

@Immutable
class SpeakerPalette(private val colors: List<SpeakerColor>) {
    operator fun get(index: Int): SpeakerColor = colors[((index % colors.size) + colors.size) % colors.size]
}

val LocalSpeakerPalette = staticCompositionLocalOf { speakerPalette(Light) }

/**
 * Цвета спикеров строятся из текущей схемы (на Android 12+ — из обоев, Material You):
 * 1-й — primary, 2-й — tertiary системы, остальные — те же светлота и (приглушённая) насыщенность,
 * но другой оттенок. В OKLCH, чтобы все цвета были одинаково «тихими» и не спорили с темой.
 */
fun speakerPalette(cs: ColorScheme): SpeakerPalette {
    val p = cs.primary.toOklch()
    val pc = cs.primaryContainer.toOklch()
    val opc = cs.onPrimaryContainer.toOklch()
    val t = cs.tertiary.toOklch()
    // Монохромная системная тема (серые обои): спикеров всё равно различаем мягкими оттенками
    val mono = p.c < 0.035f && t.c < 0.035f
    val accentC = if (mono) 0.075f else ((p.c + t.c) / 2f).coerceIn(0.06f, 0.11f)
    val contC = if (mono) 0.04f else pc.c.coerceIn(0.03f, 0.06f)
    val onC = if (mono) 0.04f else opc.c.coerceIn(0.02f, 0.06f)
    val dark = cs.surface.toOklch().l < 0.5f
    // В монохроме primary почти белый/чёрный — светлоту акцента берём «обычную»
    val accentL = if (mono) (if (dark) 0.82f else 0.50f) else p.l
    fun at(h: Float) = SpeakerColor(oklch(accentL, accentC, h), oklch(pc.l, contC, h), oklch(opc.l, onC, h))
    val baseHue = if (mono) 4.6f else p.h // ~265° — спокойный индиго
    val colors = if (mono) mutableListOf(at(baseHue)) else mutableListOf(
        SpeakerColor(cs.primary, cs.primaryContainer, cs.onPrimaryContainer),
        SpeakerColor(cs.tertiary, cs.tertiaryContainer, cs.onTertiaryContainer),
    )
    // Остальные оттенки — жадно: каждый следующий максимально далеко от уже выбранных
    val used = if (mono) mutableListOf(baseHue) else mutableListOf(p.h, t.h)
    val candidates = (0 until 36).map { baseHue + it * (2 * PI.toFloat() / 36) }
    while (colors.size < 8) {
        val h = candidates.maxBy { c -> used.minOf { hueDist(it, c) } }
        used += h
        colors += at(h)
    }
    return SpeakerPalette(colors)
}

private fun hueDist(a: Float, b: Float): Float {
    val d = abs(a - b) % (2 * PI.toFloat())
    return min(d, 2 * PI.toFloat() - d)
}

private class Lch(val l: Float, val c: Float, val h: Float)

private fun toLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
private fun toSrgb(c: Float) = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f

private fun Color.toOklch(): Lch {
    val r = toLinear(red)
    val g = toLinear(green)
    val b = toLinear(blue)
    val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
    val lL = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
    val aA = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    val bB = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    return Lch(lL, hypot(aA, bB), atan2(bB, aA))
}

/** OKLCH → sRGB; если цвет вне охвата sRGB — уменьшаем насыщенность. */
private fun oklch(lL: Float, c0: Float, h: Float): Color {
    var c = c0
    repeat(24) {
        val a = c * cos(h)
        val b = c * sin(h)
        val l1 = lL + 0.3963377774f * a + 0.2158037573f * b
        val m1 = lL - 0.1055613458f * a - 0.0638541728f * b
        val s1 = lL - 0.0894841775f * a - 1.2914855480f * b
        val l = l1 * l1 * l1
        val m = m1 * m1 * m1
        val s = s1 * s1 * s1
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bb = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        val eps = 0.0005f
        if (r in -eps..1 + eps && g in -eps..1 + eps && bb in -eps..1 + eps) {
            return Color(toSrgb(r.coerceIn(0f, 1f)), toSrgb(g.coerceIn(0f, 1f)), toSrgb(bb.coerceIn(0f, 1f)))
        }
        c *= 0.85f
    }
    val v = toSrgb((lL * lL * lL).coerceIn(0f, 1f))
    return Color(v, v, v)
}

// ---------- Типографика: Manrope (вшит в APK) ----------

private fun manrope(weight: FontWeight) = Font(
    R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Manrope = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold),
)

private fun TextStyle.m(weight: FontWeight, letterSpacing: Float? = null) = copy(
    fontFamily = Manrope,
    fontWeight = weight,
    letterSpacing = letterSpacing?.sp ?: this.letterSpacing,
)

private val base = Typography()

val AppTypography = Typography(
    displayLarge = base.displayLarge.m(FontWeight.SemiBold, -1f),
    displayMedium = base.displayMedium.m(FontWeight.SemiBold, -0.5f),
    displaySmall = base.displaySmall.m(FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.m(FontWeight.Bold, -0.5f),
    headlineMedium = base.headlineMedium.m(FontWeight.Bold, -0.25f),
    headlineSmall = base.headlineSmall.m(FontWeight.Bold),
    titleLarge = base.titleLarge.m(FontWeight.Bold),
    titleMedium = base.titleMedium.m(FontWeight.SemiBold, 0f),
    titleSmall = base.titleSmall.m(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Normal, lineHeight = 25.sp, letterSpacing = 0.1.sp),
    bodyMedium = base.bodyMedium.m(FontWeight.Normal),
    bodySmall = base.bodySmall.m(FontWeight.Medium),
    labelLarge = base.labelLarge.m(FontWeight.SemiBold),
    labelMedium = base.labelMedium.m(FontWeight.SemiBold),
    labelSmall = base.labelSmall.m(FontWeight.SemiBold),
)

/** Табличные цифры: таймкоды и проценты не «прыгают». */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun GlagolTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Android 12+: цвета из системы (обои / Material You); раньше — своя спокойная схема
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> Dark
        else -> Light
    }
    val palette = remember(scheme) { speakerPalette(scheme) }
    CompositionLocalProvider(LocalSpeakerPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

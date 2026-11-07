package app.glagol.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Цвета сверх схемы Material: кнопка записи и статус связи с телефоном. */
object KraberColors {
    /** Кнопка записи / точка таймера. */
    val Record = Color(0xFFEF5350)
    /** Звук идёт на телефон. */
    val Live = Color(0xFF8FD9A8)
    val OnLive = Color(0xFF00391C)
    /** Звук только на часах. */
    val Local = Color(0xFFFFC56E)
}

// Тёмная схема телефона (seed «глубокий индиго»), фон — чистый чёрный для OLED
private val KraberScheme = ColorScheme(
    primary = Color(0xFFBAC3FF),
    primaryDim = Color(0xFF9DA8F2),
    primaryContainer = Color(0xFF293CA0),
    onPrimary = Color(0xFF08218A),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC3C5DD),
    secondaryDim = Color(0xFFA8AAC1),
    secondaryContainer = Color(0xFF434659),
    onSecondary = Color(0xFF2D2F42),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7),
    tertiaryDim = Color(0xFFC99FBB),
    tertiaryContainer = Color(0xFF5D3C55),
    onTertiary = Color(0xFF44263D),
    onTertiaryContainer = Color(0xFFFFD7F1),
    surfaceContainerLow = Color(0xFF16171C),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    onSurface = Color(0xFFE4E1E9),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    background = Color.Black,
    onBackground = Color(0xFFE4E1E9),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFE6978E),
    errorContainer = Color(0xFF93000A),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun KraberTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KraberScheme, content = content)
}

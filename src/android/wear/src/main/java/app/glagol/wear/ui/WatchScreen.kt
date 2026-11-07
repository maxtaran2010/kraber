package app.glagol.wear.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.glagol.wear.LinkMode
import app.glagol.wear.RecordingController
import app.glagol.wear.UiState
import kotlin.math.roundToInt

/** Весь UI часов: один экран, содержимое меняется по состоянию [RecordingController]. */
@Composable
fun WatchApp(onRecord: () -> Unit, onStop: () -> Unit) {
    val state by RecordingController.state.collectAsStateWithLifecycle()
    KraberTheme {
        // Во время записи вместо часов сверху — таймер записи
        val timeAlpha by animateFloatAsState(if (state is UiState.Recording) 0f else 1f, tween(300), label = "timeText")
        AppScaffold(
            timeText = { if (timeAlpha > 0f) TimeText(Modifier.graphicsLayer { alpha = timeAlpha }) },
        ) {
            ScreenScaffold { _ ->
                AnimatedContent(
                    targetState = state,
                    // Recording обновляется 20 раз в секунду — анимируем только смену вида экрана
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.92f)) togetherWith fadeOut(tween(200))
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        is UiState.Idle -> IdleScreen(s, onRecord)
                        is UiState.Recording -> RecordingScreen(s, onStop)
                        is UiState.Sending -> SendingScreen(s)
                        UiState.Sent -> ResultScreen(
                            icon = Icons.Rounded.Check,
                            tint = KraberColors.OnLive,
                            background = KraberColors.Live,
                            title = "На телефоне",
                            subtitle = "расшифровывается",
                        )
                        UiState.SavedOnWatch -> ResultScreen(
                            icon = Icons.Rounded.Watch,
                            tint = Color.Black,
                            background = KraberColors.Local,
                            title = "Телефона нет рядом",
                            subtitle = "Запись на часах — отправлю сама",
                        )
                        is UiState.Message -> ResultScreen(
                            icon = Icons.Rounded.ErrorOutline,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            background = MaterialTheme.colorScheme.errorContainer,
                            title = s.text,
                            subtitle = null,
                        )
                    }
                }
            }
        }
    }
}

/** Круглый экран: кладём содержимое с отступами в долях диаметра, чтобы оно не обрезалось краем. */
@Composable
private fun RoundBox(content: @Composable (d: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        content(minOf(maxWidth, maxHeight))
    }
}

// ---------- Ожидание ----------

@Composable
private fun IdleScreen(s: UiState.Idle, onRecord: () -> Unit) = RoundBox { d ->
    val cs = MaterialTheme.colorScheme
    val button = (d * 0.40f).coerceIn(72.dp, 92.dp)
    Box(Modifier.fillMaxSize()) {
        Text(
            "Крабер",
            Modifier.align(Alignment.TopCenter).padding(top = d * 0.15f),
            style = MaterialTheme.typography.labelMedium,
            color = cs.primary,
        )
        BreathingRing(button, Modifier.align(Alignment.Center).size(button * 1.5f))
        FilledIconButton(
            onClick = onRecord,
            modifier = Modifier.align(Alignment.Center).size(button),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = KraberColors.Record, contentColor = Color.White),
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = "Начать запись", modifier = Modifier.size(button * 0.45f))
        }
        IdleStatus(
            s,
            Modifier.align(Alignment.BottomCenter).padding(bottom = d * 0.10f).widthIn(max = d * 0.64f),
        )
    }
}

/** Медленно «дышащее» кольцо вокруг кнопки записи (градиент бренда, как волна во время записи). */
@Composable
private fun BreathingRing(button: Dp, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val k by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "k",
    )
    Canvas(modifier) {
        val base = button.toPx() / 2 + 5.dp.toPx()
        val r = base * (1f + 0.12f * k)
        val sweep = Brush.sweepGradient(listOf(cs.primary, cs.tertiary, cs.primary), center)
        // мягкое свечение под кнопкой
        drawCircle(
            Brush.radialGradient(
                listOf(KraberColors.Record.copy(alpha = 0.28f - 0.12f * k), Color.Transparent),
                center = center,
                radius = r * 1.25f,
            ),
            radius = r * 1.25f,
        )
        drawCircle(sweep, radius = r, alpha = 0.75f - 0.45f * k, style = Stroke(width = 2.5.dp.toPx()))
    }
}

@Composable
private fun IdleStatus(s: UiState.Idle, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val (icon, text, color) = when {
        s.pending > 0 -> Triple(Icons.Rounded.Upload, "Не отправлено: ${s.pending}", KraberColors.Local)
        s.phoneNearby == true -> Triple(Icons.Rounded.PhoneAndroid, "Телефон рядом", KraberColors.Live)
        s.phoneNearby == false -> Triple(Icons.Rounded.MobileOff, "Нет телефона — сохраню на часах", cs.onSurfaceVariant)
        else -> Triple(Icons.Rounded.Sync, "Ищу телефон…", cs.onSurfaceVariant)
    }
    AnimatedContent(
        targetState = Triple(icon, text, color),
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        modifier = modifier,
        label = "status",
    ) { (ic, t, c) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(ic, contentDescription = null, tint = c, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(t, style = MaterialTheme.typography.labelSmall, color = c, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

// ---------- Запись ----------

@Composable
private fun RecordingScreen(s: UiState.Recording, onStop: () -> Unit) = RoundBox { d ->
    val cs = MaterialTheme.colorScheme
    val stop = (d * 0.29f).coerceIn(52.dp, 64.dp)
    Box(Modifier.fillMaxSize()) {
        RadialWave(
            level = s.level,
            colors = listOf(cs.primary, cs.tertiary, cs.primary),
            modifier = Modifier.fillMaxSize(),
        )
        RecTimer(s.elapsedMs, Modifier.align(Alignment.TopCenter).padding(top = d * 0.11f))
        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.align(Alignment.Center).size(stop),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black, contentColor = KraberColors.Record),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = "Остановить запись", modifier = Modifier.size(stop * 0.55f))
        }
        LinkChip(s.link, Modifier.align(Alignment.BottomCenter).padding(bottom = d * 0.11f))
    }
}

@Composable
private fun RecTimer(elapsedMs: Long, modifier: Modifier) {
    val dot by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val sec = elapsedMs / 1000
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = dot }
                .background(KraberColors.Record, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "%d:%02d".format(sec / 60, sec % 60),
            style = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(Color.Black, blurRadius = 10f),
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun LinkChip(mode: LinkMode, modifier: Modifier) {
    AnimatedContent(
        targetState = mode,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        modifier = modifier,
        label = "link",
    ) { m ->
        val (icon, text, color) = when (m) {
            LinkMode.Live -> Triple(Icons.Rounded.PhoneAndroid, "→ телефон", KraberColors.Live)
            LinkMode.Local -> Triple(Icons.Rounded.Watch, "на часах", KraberColors.Local)
            LinkMode.Connecting -> Triple(Icons.Rounded.Sync, "ищу телефон…", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Chip(icon, text, color)
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, color: Color) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.65f))
            .border(1.dp, color.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

// ---------- Отправка ----------

@Composable
private fun SendingScreen(s: UiState.Sending) = RoundBox { d ->
    val cs = MaterialTheme.colorScheme
    val colors = ProgressIndicatorDefaults.colors(indicatorColor = cs.primary, trackColor = cs.primary.copy(alpha = 0.18f))
    Box(Modifier.fillMaxSize()) {
        val target = s.progress
        if (target != null) {
            val p by animateFloatAsState(target, tween(250), label = "progress")
            CircularProgressIndicator(
                progress = { p },
                modifier = Modifier.fillMaxSize().padding(2.dp),
                colors = colors,
                strokeWidth = 6.dp,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.fillMaxSize().padding(2.dp), colors = colors, strokeWidth = 6.dp)
        }
        Column(
            Modifier.align(Alignment.Center).widthIn(max = d * 0.66f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = cs.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Отправляю на телефон…",
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = cs.onBackground,
            )
            if (target != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${(target * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Итог ----------

@Composable
private fun ResultScreen(icon: ImageVector, tint: Color, background: Color, title: String, subtitle: String?) = RoundBox { d ->
    val badge = (d * 0.30f).coerceIn(48.dp, 68.dp)
    // Значок «выпрыгивает» с пружиной
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Column(
        Modifier.widthIn(max = d * 0.72f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visibleState = shown,
            enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(150)),
        ) {
            Box(Modifier.size(badge).background(background, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(badge * 0.6f))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

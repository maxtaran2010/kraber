package app.glagol.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.glagol.data.Chapter
import app.glagol.data.Recording
import app.glagol.data.Summary
import app.glagol.data.SummaryStatus
import app.glagol.export.formatClock
import app.glagol.ml.ModelPaths
import app.glagol.ml.SummaryLogic
import app.glagol.ui.components.ShimmerLines
import app.glagol.ui.theme.LocalSpeakerPalette
import app.glagol.ui.theme.TabularNums

/** Совсем короткие записи (приветствие) получают только название — карточку «Кратко» не показываем даже на время. */
private const val SHORT_RECORDING_MS = 8_000L

/** Есть ли что показать в карточке «Кратко» (иначе она скрыта целиком). */
fun Recording.hasSummaryCard(live: String?): Boolean = when (summaryStatus) {
    SummaryStatus.None -> false
    SummaryStatus.Pending, SummaryStatus.Generating -> !live.isNullOrBlank() || durationMs !in 1 until SHORT_RECORDING_MS
    SummaryStatus.Done -> digest?.isEmpty == false || !summary.isNullOrBlank()
    SummaryStatus.Failed -> true
}

/**
 * Карточка «Кратко»: суть крупно, ключевые моменты с цветными точками, «Договорились» с галочками
 * и сворачиваемая «Хронология» (тап по части — проигрывание с её начала).
 * Пока LLM пишет — потоковый текст и прогресс («Читаю часть 3 из 17…»).
 * Если показывать нечего — карточки нет (включая отступ под ней).
 */
@Composable
fun SummarySection(
    rec: Recording,
    live: String?,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = rec.hasSummaryCard(live),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Column {
            SummaryCardBody(rec, live, onRetry, onSeek)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SummaryCardBody(rec: Recording, live: String?, onRetry: () -> Unit, onSeek: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = cs.primary)
                Spacer(Modifier.width(8.dp))
                Text("Кратко", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${rec.summaryModel ?: ModelPaths.LLM_NAME} · на устройстве",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            when (rec.summaryStatus) {
                SummaryStatus.Pending, SummaryStatus.Generating -> LiveBody(live)
                SummaryStatus.Done -> {
                    val d = rec.digest
                    if (d != null && !d.isEmpty) DigestBody(d, onSeek)
                    else SelectionContainer { Text(rec.summary.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
                }
                SummaryStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rec.summaryError ?: "Не удалось составить конспект",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Повторить") }
                }
                SummaryStatus.None -> {}
            }
        }
    }
}

/** Пока LLM пишет: статус длинной записи и потоковый текст с курсором. */
@Composable
private fun LiveBody(live: String?) {
    val cs = MaterialTheme.colorScheme
    val (status, text) = SummaryLogic.decodeLive(live)
    if (status != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(status, style = MaterialTheme.typography.labelLarge.merge(TabularNums), color = cs.primary)
        }
        Spacer(Modifier.height(12.dp))
    }
    when {
        text.isNotBlank() -> Text(
            text.trimEnd() + " ▍",
            style = MaterialTheme.typography.bodyMedium,
            color = if (status != null) cs.onSurfaceVariant else cs.onSurface,
        )
        else -> ShimmerLines(3)
    }
}

@Composable
private fun DigestBody(d: Summary, onSeek: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val palette = LocalSpeakerPalette.current
    SelectionContainer {
        Column {
            if (d.gist.isNotBlank()) {
                Text(
                    d.gist,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = cs.onSurface,
                )
            }
            if (d.points.isNotEmpty()) {
                if (d.gist.isNotBlank()) Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.points.forEachIndexed { i, p ->
                        Row {
                            Box(
                                Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(palette[i].accent)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(p, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (d.agreed.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Договорились", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.agreed.forEach { a ->
                        Row {
                            Icon(
                                Icons.Rounded.CheckCircle, null,
                                Modifier.padding(top = 1.dp).size(18.dp),
                                tint = cs.tertiary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(a, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (d.chapters.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Chapters(d.chapters, onSeek)
    }
}

/** Сворачиваемая хронология длинной записи: время части + о чём говорили; тап — играть с начала части. */
@Composable
private fun Chapters(chapters: List<Chapter>, onSeek: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(vertical = 10.dp),
    ) {
        Text("Хронология", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            "· ${chapters.size} ${partsWord(chapters.size)}",
            style = MaterialTheme.typography.labelLarge.merge(TabularNums),
            color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ExpandMore, if (open) "Свернуть" else "Развернуть", Modifier.rotate(angle), tint = cs.onSurfaceVariant)
    }
    AnimatedVisibility(visible = open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            chapters.forEach { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSeek(c.startMs) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                ) {
                    Surface(shape = CircleShape, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                        Row(
                            Modifier.padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp).widthIn(min = 44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(formatClock(c.startMs), style = MaterialTheme.typography.labelMedium.merge(TabularNums))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(c.text, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 1 часть, 2 части, 5 частей. */
private fun partsWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "часть"
        m10 in 2..4 && m100 !in 12..14 -> "части"
        else -> "частей"
    }
}

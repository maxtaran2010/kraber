package app.glagol.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.glagol.data.Recording
import app.glagol.data.RecordingStatus
import app.glagol.data.SummaryStatus
import app.glagol.ml.SummaryLogic

private val SUGGESTIONS = listOf("Что решили?", "Кто что пообещал?", "Какие сроки?", "О чём спорили?")

/** Можно ли спрашивать: запись готова, в ней есть речь и LLM на устройстве работает. */
fun Recording.canAsk(hasTranscript: Boolean = true): Boolean =
    status == RecordingStatus.Done && hasTranscript && summaryStatus != SummaryStatus.None &&
        summaryError?.contains("не поддерживается") != true

/**
 * Карточка «Спросить о разговоре»: подсказки, лента вопросов и ответов (ответ печатается потоково),
 * поле ввода. Отвечает локальная LLM только по расшифровке этой записи.
 * Скрыта, если LLM на устройстве не поддерживается или речи в записи нет.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskSection(
    rec: Recording,
    liveAnswer: String?,
    busy: Boolean,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
    hasTranscript: Boolean = true,
) {
    if (!rec.canAsk(hasTranscript) && rec.qa.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf("") }
    /** Заданный, но ещё не сохранённый вопрос (в `rec.qa` он попадёт вместе с ответом). */
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAt by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(busy) { if (!busy) pending = null }
    // Ответ уже сохранён, а флаг «занято» ещё не снят — не показываем вопрос дважды
    val saved = pending != null && rec.qa.any { it.q == pending && it.at >= pendingAt }

    fun submit(q: String) {
        val text = q.trim()
        if (text.isEmpty() || busy) return
        pending = text
        pendingAt = System.currentTimeMillis()
        input = ""
        focus.clearFocus()
        onAsk(text)
    }

    Column(modifier) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp).animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Forum, null, Modifier.size(18.dp), tint = cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Спросить о разговоре", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("без интернета", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }

                AnimatedVisibility(
                    visible = rec.qa.isEmpty() && pending == null && !busy,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        SUGGESTIONS.forEach { s ->
                            SuggestionChip(
                                onClick = { submit(s) },
                                label = { Text(s) },
                                shape = CircleShape,
                                colors = SuggestionChipDefaults.suggestionChipColors(containerColor = cs.surfaceContainerHigh),
                                border = null,
                            )
                        }
                    }
                }

                if (rec.qa.isNotEmpty() || pending != null || busy) {
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rec.qa.forEach { item ->
                            QuestionBubble(item.q)
                            AnswerBubble(item.a, error = item.a.startsWith(SummaryLogic.ERROR_PREFIX))
                        }
                        if (busy && !saved) {
                            pending?.let { QuestionBubble(it) }
                            if (!liveAnswer.isNullOrBlank()) AnswerBubble(liveAnswer, streaming = true)
                            else Thinking(if (rec.summaryStatus == SummaryStatus.Generating) "Допишу конспект и отвечу…" else "Думаю…")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Например: какие сроки назвали?") },
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 4,
                    enabled = rec.canAsk(hasTranscript),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(input) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = cs.outlineVariant,
                        focusedContainerColor = cs.surfaceContainerLowest,
                        unfocusedContainerColor = cs.surfaceContainerLowest,
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = { submit(input) },
                            enabled = input.isNotBlank() && !busy,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = cs.primary),
                        ) { Icon(Icons.AutoMirrored.Rounded.Send, "Спросить") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun QuestionBubble(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            color = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}

@Composable
private fun AnswerBubble(text: String, streaming: Boolean = false, error: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color = cs.surfaceContainerHigh,
            contentColor = if (error) cs.onSurfaceVariant else cs.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            // Ответ и (если модель нашла) реплика-источник: «цитата» — кто сказал
            val (answer, quote) = SummaryLogic.splitAnswer(text)
            SelectionContainer {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(
                        if (streaming) answer.trimEnd() + " ▍" else answer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (quote != null) {
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Box(Modifier.padding(top = 2.dp).width(2.dp).height(16.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.5f)))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                quote,
                                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** «Думаю…» с тремя мигающими точками. */
@Composable
private fun Thinking(label: String) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "thinking")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
        repeat(3) { i ->
            val a = if (phase.toInt() == i) 1f else 0.3f
            Box(Modifier.padding(end = 4.dp).size(6.dp).alpha(a).clip(CircleShape).background(cs.primary))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

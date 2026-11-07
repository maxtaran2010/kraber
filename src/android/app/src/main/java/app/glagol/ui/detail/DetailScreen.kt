package app.glagol.ui.detail

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glagol.data.Recording
import app.glagol.data.RecordingStatus
import app.glagol.data.SummaryStatus
import app.glagol.data.Utterance
import app.glagol.data.speakerDisplayName
import app.glagol.data.speakerIndex
import app.glagol.export.formatClock
import app.glagol.ml.ModelPaths
import app.glagol.ml.Stage
import app.glagol.service.ProcessingState
import app.glagol.ui.components.MetaPill
import app.glagol.ui.components.ShimmerLines
import app.glagol.ui.components.SpeakerAvatar
import app.glagol.ui.components.SpeakerCountSelector
import app.glagol.ui.home.RenameDialog
import app.glagol.ui.humanDuration
import app.glagol.ui.speakersLabel
import app.glagol.ui.theme.LocalSpeakerPalette
import app.glagol.ui.theme.TabularNums
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit) {
    val rec by vm.recording.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val live by vm.liveSummary.collectAsStateWithLifecycle()
    val wordTimes by vm.wordTimes.collectAsStateWithLifecycle()
    // Позицию плеера читают только активная реплика и панель плеера — список не перерисовывается 60 раз в секунду
    val position = vm.player.position.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val started by vm.player.started.collectAsStateWithLifecycle()
    val duration by vm.player.duration.collectAsStateWithLifecycle()
    val continuous by vm.continuous.collectAsStateWithLifecycle()
    val liveAnswer by vm.liveAnswer.collectAsStateWithLifecycle()
    val asking by vm.asking.collectAsStateWithLifecycle()
    val imeVisible = WindowInsets.isImeVisible
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.player.pause() }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exportMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renamingSpeaker by remember { mutableStateOf<String?>(null) }
    var reprocessing by remember { mutableStateOf(false) }

    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = vm.writeJsonTo(uri)
            snackbar.showSnackbar(if (ok) "JSON сохранён" else "Не удалось сохранить файл")
        }
    }

    // Запись удалили (или отменили обработку) — уходим назад
    LaunchedEffect(rec == null) { if (rec == null) onBack() }
    val r = rec ?: return

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = r.status == RecordingStatus.Done && !transcript.isNullOrEmpty() && !imeVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                PlayerBar(
                    position = position,
                    duration = duration.takeIf { it > 0 } ?: r.durationMs,
                    playing = playing,
                    continuous = continuous,
                    onToggle = vm.player::toggle,
                    onSeek = vm::seekTo,
                    onContinuous = vm::setContinuous,
                )
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
                actions = {
                    if (r.status == RecordingStatus.Done) {
                        Box {
                            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Rounded.IosShare, "Экспорт") }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Сохранить JSON в файл…") },
                                    leadingIcon = { Icon(Icons.Rounded.DataObject, null) },
                                    onClick = { exportMenu = false; saveJson.launch(vm.suggestedJsonName()) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Поделиться JSON") },
                                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                    onClick = { exportMenu = false; vm.shareJsonIntent()?.let(context::startActivity) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Поделиться текстом") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null) },
                                    onClick = { exportMenu = false; vm.shareTextIntent()?.let(context::startActivity) },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Копировать текст") },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                    onClick = {
                                        exportMenu = false
                                        vm.plainText()?.let { txt ->
                                            scope.launch {
                                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("transcript", txt)))
                                                snackbar.showSnackbar("Текст скопирован")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { moreMenu = false; renaming = true },
                            )
                            if (r.status != RecordingStatus.Processing) {
                                DropdownMenuItem(
                                    text = { Text("Пересчитать спикеров") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = { moreMenu = false; reprocessing = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Удалить") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                onClick = { moreMenu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = r.status,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "status",
            modifier = Modifier.padding(padding),
        ) { status ->
            when (status) {
                RecordingStatus.Processing -> ProcessingContent(processing, onCancel = vm::cancel)
                RecordingStatus.Failed -> FailedContent(r, onRetry = { reprocessing = true })
                RecordingStatus.Done -> TranscriptContent(
                    r, transcript, live, onRetrySummary = vm::retrySummary, onSpeakerClick = { renamingSpeaker = it },
                    playback = Playback(position, playing, started, wordTimes),
                    onUtteranceClick = vm::playUtterance,
                    onSeek = vm::seekTo,
                    ask = AskState(liveAnswer, asking, vm::ask),
                )
            }
        }
    }

    if (renaming) RenameDialog(r.title, onDismiss = { renaming = false }) { vm.rename(it); renaming = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Удалить запись?") },
            text = { Text("Аудио, расшифровка и конспект удалятся с телефона. Отменить это нельзя.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; vm.delete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
    renamingSpeaker?.let { spk ->
        val auto = r.speakerNames?.get(spk)
        RenameDialog(
            current = r.speakerNamesUser[spk] ?: auto.orEmpty(),
            onDismiss = { renamingSpeaker = null },
            title = speakerDisplayName(spk),
            hint = if (auto != null) "Найдено по разговору. Пустое поле — вернуть «$auto»"
            else "Как зовут этого человека? Пустое поле — «${speakerDisplayName(spk)}»",
        ) { vm.renameSpeaker(spk, it); renamingSpeaker = null }
    }
    if (reprocessing) ReprocessDialog(r.requestedSpeakers, onDismiss = { reprocessing = false }) {
        reprocessing = false; vm.reprocess(it)
    }
}

// ---------------- Обработка ----------------

@Composable
private fun ProcessingContent(state: ProcessingState?, onCancel: () -> Unit) {
    val overall = state?.overall ?: 0f
    val animated by animateFloatAsState(overall, tween(400), label = "progress")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.5f))
        if (state == null) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Spacer(Modifier.height(20.dp))
            Text("В очереди…", style = MaterialTheme.typography.titleLarge)
        } else {
            Text(
                "${(animated * 100).roundToInt()}%",
                style = MaterialTheme.typography.displayLarge.merge(TabularNums),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(state.stage.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                gapSize = 4.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(36.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Stage.entries.forEach { s -> StageRow(s, state) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (state != null) {
            Text(
                "Прошло ${humanDuration(now - state.startedAt)} · без интернета",
                style = MaterialTheme.typography.bodySmall.merge(TabularNums),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(bottom = 24.dp)) { Text("Отменить") }
    }
}

@Composable
private fun StageRow(stage: Stage, state: ProcessingState) {
    val cs = MaterialTheme.colorScheme
    val done = stage.ordinal < state.stage.ordinal
    val active = stage == state.stage
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary)
                active -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = cs.outlineVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            stage.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done || active) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            Text(
                "${(state.stageProgress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                color = cs.primary,
            )
        }
    }
}

// ---------------- Ошибка ----------------

@Composable
private fun FailedContent(rec: Recording, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onErrorContainer) }
        Spacer(Modifier.height(20.dp))
        Text("Не получилось", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(rec.error ?: "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Попробовать ещё раз") }
    }
}

// ---------------- Расшифровка ----------------

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TranscriptContent(
    rec: Recording,
    transcript: List<Utterance>?,
    live: String?,
    onRetrySummary: () -> Unit,
    onSpeakerClick: (String) -> Unit,
    playback: Playback,
    onUtteranceClick: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    ask: AskState,
) {
    if (transcript == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val listState = rememberLazyListState()
    val position = playback.position
    // Какая реплика сейчас звучит: последняя начавшаяся к текущей позиции
    val activeIndex by remember(transcript, playback.started) {
        derivedStateOf { if (!playback.started) -1 else utteranceAt(transcript, position.value) }
    }
    val showSummary = rec.summaryStatus != SummaryStatus.None
    val showLegend = rec.speakerShare.size > 1
    val showAsk = transcript.isNotEmpty() && (rec.canAsk() || rec.qa.isNotEmpty())
    val tabs = buildList<DetailTab> {
        add(DetailTab.Text)
        if (showSummary) add(DetailTab.Summary)
        if (showAsk) add(DetailTab.Ask)
    }
    var tabState by rememberSaveable { mutableStateOf(DetailTab.Text) }
    val tab = if (tabState in tabs) tabState else DetailTab.Text
    val showTabs = tabs.size > 1
    // Позиция в тексте сохраняется, пока смотрите конспект или вопросы
    var textScroll by remember { mutableStateOf(0 to 0) }
    fun select(t: DetailTab) {
        if (t == tab) return
        if (tab == DetailTab.Text) textScroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        tabState = t
    }
    LaunchedEffect(tab) {
        val pinned = if (showTabs) 1 else 0
        if (tab == DetailTab.Text && textScroll.first > pinned) listState.scrollToItem(textScroll.first, textScroll.second)
        else if (listState.firstVisibleItemIndex > pinned) listState.scrollToItem(pinned)
    }
    val headerCount = 1 + (if (showTabs) 1 else 0) + (if (showLegend) 1 else 0) + (if (transcript.isEmpty()) 1 else 0)
    FollowPlayback(listState, if (tab == DetailTab.Text) activeIndex else -1, playback.playing, headerCount)
    LazyColumn(
        state = listState,
        modifier = Modifier.imePadding(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 32.dp),
    ) {
        item(key = "meta") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                MetaPill(Icons.Rounded.Schedule, formatClock(rec.durationMs))
                if (rec.speakerCount > 0) MetaPill(Icons.Rounded.Groups, speakersLabel(rec.speakerCount))
                MetaPill(
                    Icons.Rounded.Bolt,
                    "за ${humanDuration(rec.processingMs)} · RTF ${String.format(Locale.US, "%.2f", rec.rtf)}",
                    emphasized = true,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        if (showTabs) {
            stickyHeader(key = "tabs") {
                DetailTabs(tabs, tab, rec.qa.size, onSelect = ::select)
            }
        }
        when (tab) {
            DetailTab.Summary -> item(key = "summary") {
                SummarySection(
                    rec, live, onRetrySummary,
                    onSeek = { ms -> onSeek(ms); select(DetailTab.Text) },
                    modifier = Modifier.padding(horizontal = 8.dp).padding(top = 8.dp),
                )
                if (!rec.hasSummaryCard(live)) EmptyTab("Конспекта для этой записи нет — разговор слишком короткий.")
            }
            DetailTab.Ask -> item(key = "ask") {
                AskSection(rec, ask.liveAnswer, ask.busy, ask.onAsk, Modifier.padding(horizontal = 8.dp).padding(top = 8.dp))
            }
            DetailTab.Text -> {
                if (showLegend) {
                    item(key = "legend") {
                        Box(Modifier.padding(horizontal = 8.dp).padding(top = if (showTabs) 8.dp else 0.dp)) { SpeakersLegend(rec, onSpeakerClick) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (transcript.isEmpty()) {
                    item(key = "empty") { NoSpeech() }
                }
            }
        }
        if (tab == DetailTab.Text) itemsIndexed(transcript, key = { i, _ -> i }) { i, u ->
            UtteranceItem(
                u, rec.speakerLabel(u.speakerId), rec.knownSpeakerNames[u.speakerId],
                showHeader = i == 0 || transcript[i - 1].speakerId != u.speakerId,
                active = i == activeIndex,
                playing = playback.playing,
                position = position,
                storedTimes = playback.wordTimes?.getOrNull(i),
                onSpeakerClick = onSpeakerClick,
                onClick = { onUtteranceClick(i) },
            )
        }
    }
}

private enum class DetailTab(val title: String) { Text("Текст"), Summary("Кратко"), Ask("Вопросы") }

/** Закреплённые вкладки «Текст · Кратко · Вопросы» с плавающей подложкой. */
@Composable
private fun DetailTabs(tabs: List<DetailTab>, selected: DetailTab, questions: Int, onSelect: (DetailTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(cs.surfaceContainerHigh).padding(4.dp),
        ) {
            val w = maxWidth / tabs.size
            val x by animateDpAsState(w * tabs.indexOf(selected).coerceAtLeast(0), label = "tab")
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(cs.secondaryContainer))
            Row(Modifier.fillMaxSize()) {
                tabs.forEach { t ->
                    val on = t == selected
                    val color by animateColorAsState(if (on) cs.onSecondaryContainer else cs.onSurfaceVariant, label = "tabText")
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                            .selectable(on, role = Role.Tab, onClick = { onSelect(t) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (t == DetailTab.Ask && questions > 0) "${t.title} · $questions" else t.title,
                            style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

@Composable
private fun NoSpeech() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MicOff, null, Modifier.size(32.dp), tint = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        Text("Речь не найдена", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "В записи только тишина или шум. Проверьте, что микрофон не выключен, и попробуйте ещё раз.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakersLegend(rec: Recording, onSpeakerClick: (String) -> Unit) {
    val share = rec.speakerShare
    val palette = LocalSpeakerPalette.current
    Column(Modifier.padding(horizontal = 4.dp)) {
        // Тонкая полоса «кто сколько говорил»
        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)) {
            share.forEachIndexed { i, s ->
                if (s > 0f) Box(Modifier.weight(s).fillMaxSize().background(palette[i].accent))
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            share.forEachIndexed { i, s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(CircleShape).clickable { onSpeakerClick("speaker_${i + 1}") }
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(palette[i].accent))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${rec.speakerLabel("speaker_${i + 1}")} · ${(s * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium.merge(TabularNums),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UtteranceItem(
    u: Utterance,
    label: String,
    name: String?,
    showHeader: Boolean,
    active: Boolean,
    playing: Boolean,
    position: State<Long>,
    storedTimes: LongArray?,
    onSpeakerClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    val idx = speakerIndex(u.speakerId)
    val color = LocalSpeakerPalette.current[idx]
    val bg by animateColorAsState(
        if (active) color.accent.copy(alpha = 0.13f) else Color.Transparent,
        tween(350),
        label = "activeBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (showHeader) 10.dp else 0.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(Modifier.width(32.dp)) {
            if (showHeader) SpeakerAvatar(idx, Modifier.clickable { onSpeakerClick(u.speakerId) }, name = name)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showHeader) {
                    // Тап по имени — переименовать спикера (во всей записи)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = color.accent,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onSpeakerClick(u.speakerId) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    formatClock(u.startMs),
                    style = MaterialTheme.typography.labelMedium.merge(TabularNums),
                    color = if (active) color.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    PlayingBars(color.accent, playing)
                }
            }
            Spacer(Modifier.height(2.dp))
            if (active) {
                val ranges = remember(u.text) { wordRanges(u.text) }
                val times = remember(u, storedTimes) { wordTimesFor(u, ranges, storedTimes) }
                KaraokeText(u.text, ranges, times, position, color.accent, MaterialTheme.typography.bodyLarge)
            } else {
                Text(u.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Вопросы к LLM: печатающийся ответ, «думает», отправка вопроса. */
private data class AskState(val liveAnswer: String?, val busy: Boolean, val onAsk: (String) -> Unit)

/** Состояние проигрывания для списка реплик. */
private data class Playback(
    val position: State<Long>,
    val playing: Boolean,
    val started: Boolean,
    val wordTimes: List<LongArray>?,
)

/** Индекс последней реплики, начавшейся к [pos] (−1 — до первой). */
private fun utteranceAt(list: List<Utterance>, pos: Long): Int {
    var lo = 0
    var hi = list.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (list[mid].startMs <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** Пока играет — держим звучащую реплику на экране, если пользователь сам не листает (последние 4 с). */
@Composable
private fun FollowPlayback(state: LazyListState, active: Int, playing: Boolean, headerCount: Int) {
    var lastDrag by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) lastDrag = System.currentTimeMillis() }
    }
    val margin = with(LocalDensity.current) { 96.dp.roundToPx() }
    LaunchedEffect(active, playing) {
        if (!playing || active < 0) return@LaunchedEffect
        if (System.currentTimeMillis() - lastDrag < 4000) return@LaunchedEffect
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == active }
        val fits = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
        if (!fits) state.animateScrollToItem(headerCount + active, -margin)
    }
}

/** Нижняя панель плеера: play/pause, перемотка, время, режим «дальше / только фраза». */
@Composable
private fun PlayerBar(
    position: State<Long>,
    duration: Long,
    playing: Boolean,
    continuous: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onContinuous: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(52.dp), shape = CircleShape) {
                AnimatedContent(playing, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "pp") { p ->
                    Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p) "Пауза" else "Слушать", Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val total = duration.coerceAtLeast(1)
                val frac = dragging ?: (position.value.toFloat() / total).coerceIn(0f, 1f)
                Slider(
                    value = frac,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { onSeek((it * total).toLong()) }; dragging = null },
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                Row {
                    Text(
                        formatClock((frac * total).toLong()),
                        style = MaterialTheme.typography.labelMedium.merge(TabularNums),
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatClock(duration),
                        style = MaterialTheme.typography.labelMedium.merge(TabularNums),
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // Режим: после фразы играть дальше или остановиться
            Surface(
                onClick = { onContinuous(!continuous) },
                shape = CircleShape,
                color = if (continuous) cs.secondaryContainer else cs.tertiaryContainer,
                contentColor = if (continuous) cs.onSecondaryContainer else cs.onTertiaryContainer,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (continuous) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.AutoMirrored.Rounded.ShortText,
                        null,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (continuous) "Дальше" else "Фраза", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ReprocessDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var n by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Обработать заново") },
        text = { SpeakerCountSelector(n, { n = it }) },
        confirmButton = { TextButton(onClick = { onConfirm(n) }) { Text("Запустить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

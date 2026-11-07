package app.glagol.ui.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import app.glagol.export.ZipOptions
import app.glagol.ui.plural
import app.glagol.GlagolApp
import app.glagol.station.StationBluetooth
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.glagol.data.Recording
import app.glagol.data.RecordingStatus
import app.glagol.export.formatClock
import app.glagol.service.ProcessingState
import app.glagol.ui.components.SpeakerCountSelector
import app.glagol.ui.components.SpeakerDots
import app.glagol.ui.dayHeader
import app.glagol.ui.speakersLabel
import app.glagol.ui.theme.TabularNums
import app.glagol.ui.timeOfDay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    pendingImport: android.net.Uri?,
    onPendingImportConsumed: () -> Unit,
    onOpen: (String) -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
) {
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val processing by vm.processing.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val exportProgress by vm.exportProgress.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Recording?>(null) }
    val selectionMode = selected.isNotEmpty()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareImport(uri)
    }
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) {
            vm.prepareImport(pendingImport)
            onPendingImportConsumed()
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.shareIntents.collect { context.startActivity(it) } }

    // Внешний микрофон — колонка по Bluetooth. Живой экран откроется сам, когда пойдёт поток.
    val btScope = rememberCoroutineScope()
    val container = remember(context) { (context.applicationContext as GlagolApp).container }
    fun say(msg: String) { btScope.launch { snackbar.showSnackbar(msg) } }
    fun connectStation() {
        val stations = StationBluetooth.pairedStations(context)
        if (stations.isEmpty()) { say("Сначала спарьте колонку в настройках Bluetooth"); return }
        say("Подключаюсь к колонке…")
        StationBluetooth.connect(context, container.watch, stations.first()) { st ->
            when (st) {
                StationBluetooth.State.CONNECTED -> say("Колонка подключена")
                StationBluetooth.State.FAILED -> say("Не удалось подключиться к колонке")
                else -> {}
            }
        }
    }
    val btPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connectStation() else say("Нужно разрешение на Bluetooth")
    }
    fun onConnectStation() {
        if (StationBluetooth.hasPermission(context)) connectStation()
        else btPermLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
    }
    BackHandler(enabled = selectionMode) { vm.clearSelection() }
    BackHandler(enabled = !selectionMode && query.isNotEmpty()) { vm.setQuery(""); focus.clearFocus() }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val barColor by animateColorAsState(
        if (selectionMode) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        label = "bar",
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = {
                    AnimatedContent(selectionMode, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "title") { sel ->
                        if (sel) Text("Выбрано: ${selected.size}") else Text("Крабер")
                    }
                },
                navigationIcon = {
                    AnimatedVisibility(selectionMode, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "Снять выделение") }
                    }
                },
                actions = {
                    if (selectionMode) {
                        val all = recordings.size == selected.size
                        IconButton(onClick = { if (all) vm.clearSelection() else vm.selectAll(recordings.map { it.id }) }) {
                            Icon(if (all) Icons.Rounded.Deselect else Icons.Rounded.SelectAll, if (all) "Снять всё" else "Выбрать все")
                        }
                        if (selected.size == 1) {
                            IconButton(onClick = { renaming = recordings.firstOrNull { it.id in selected } }) {
                                Icon(Icons.Rounded.Edit, "Переименовать")
                            }
                        }
                        IconButton(onClick = { showExport = true }) { Icon(Icons.Rounded.IosShare, "Экспорт в ZIP") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                    } else {
                        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Настройки") }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barColor,
                    scrolledContainerColor = if (selectionMode) barColor else MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            AnimatedVisibility(!selectionMode, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SmallFloatingActionButton(
                        onClick = { onConnectStation() },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) { Icon(Icons.Rounded.Bluetooth, "Подключить колонку") }
                    SmallFloatingActionButton(
                        onClick = { picker.launch(arrayOf("audio/*", "application/ogg", "video/mp4")) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) { Icon(Icons.Rounded.AudioFile, "Импорт аудио") }
                    ExtendedFloatingActionButton(
                        onClick = onRecord,
                        expanded = !listState.canScrollBackward,
                        icon = { Icon(Icons.Rounded.Mic, null) },
                        text = { Text("Записать") },
                    )
                }
            }
        },
    ) { padding ->
        if (recordings.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            val grouped = remember(recordings) { recordings.groupBy { dayHeader(it.createdAt) } }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 160.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "search") {
                    SearchField(query, vm::setQuery, enabled = !selectionMode, modifier = Modifier.padding(bottom = 4.dp))
                }
                val hits = results
                if (hits != null) {
                    item(key = "found") {
                        Text(
                            if (hits.isEmpty()) "Ничего не нашлось" else "Найдено: ${hits.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(hits, key = { it.rec.id }) { hit ->
                        RecordingCard(
                            rec = hit.rec,
                            processing = processing?.takeIf { it.recordingId == hit.rec.id },
                            selected = hit.rec.id in selected,
                            selectionMode = selectionMode,
                            preview = hit.snippet,
                            highlight = query.trim(),
                            onClick = { if (selectionMode) vm.toggle(hit.rec.id) else onOpen(hit.rec.id) },
                            onLongClick = { vm.toggle(hit.rec.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                } else {
                    grouped.forEach { (day, items) ->
                        item(key = "h_$day") {
                            Text(
                                day,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp).animateItem(),
                            )
                        }
                        items(items, key = { it.id }) { rec ->
                            RecordingCard(
                                rec = rec,
                                processing = processing?.takeIf { it.recordingId == rec.id },
                                selected = rec.id in selected,
                                selectionMode = selectionMode,
                                preview = rec.previewText(),
                                onClick = { if (selectionMode) vm.toggle(rec.id) else onOpen(rec.id) },
                                onLongClick = { vm.toggle(rec.id) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }

    draft?.let { d ->
        ImportSheet(
            draft = d,
            onDismiss = vm::dismissDraft,
            onConfirm = { speakers -> onOpen(vm.startImport(d, speakers)) },
        )
    }
    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Удалить ${plural(n, "запись", "записи", "записей")}?") },
            text = { Text("Аудио, расшифровки и конспекты удалятся с телефона. Отменить это нельзя.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; vm.deleteSelected() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
    if (showExport) {
        ExportSheet(
            count = selected.size,
            audioBytes = remember(selected) { vm.audioBytesOfSelection() },
            fileName = remember(selected) { vm.zipName() },
            progress = exportProgress,
            onShare = vm::shareZip,
            onSave = vm::saveZip,
            onDismiss = { showExport = false },
        )
        // Экспорт удался — выделение снято, лист закрываем
        LaunchedEffect(selectionMode) { if (!selectionMode) showExport = false }
    }
    renaming?.let { r ->
        RenameDialog(r.title, onDismiss = { renaming = null }) {
            vm.rename(r.id, it)
            renaming = null
            vm.clearSelection()
        }
    }
}

/** Строка-превью под названием: суть конспекта или начало первой мысли. */
private fun Recording.previewText(): String? =
    digest?.gist?.takeIf { it.isNotBlank() }
        ?: summary?.lineSequence()?.map { it.trim().trimStart('•', '-', ' ') }?.firstOrNull { it.isNotBlank() && !it.startsWith("Название") }

/** Поле поиска по названиям, конспектам, именам и тексту всех расшифровок. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Surface(
        shape = CircleShape,
        color = cs.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Поиск по записям и тексту", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AnimatedVisibility(query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                IconButton(onClick = { onQuery(""); focus.clearFocus() }) { Icon(Icons.Rounded.Close, "Очистить") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingCard(
    rec: Recording,
    processing: ProcessingState?,
    selected: Boolean,
    selectionMode: Boolean,
    preview: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: String = "",
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(if (selected) cs.secondaryContainer else cs.surfaceContainerLow, label = "card")
    val corner by animateDpAsState(if (selected) 32.dp else 24.dp, label = "corner")
    val shape = RoundedCornerShape(corner)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
            )
            .animateContentSize(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                label = "tile",
            ) { sel ->
                if (sel) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(cs.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Check, "Выбрано", tint = cs.onPrimary) }
                } else {
                    LeadingTile(rec, processing, dimmed = selectionMode)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val (sub, color) = when (rec.status) {
                    RecordingStatus.Processing -> (processing?.let { "${it.stage.title} · ${(it.overall * 100).roundToInt()}%" }
                        ?: "В очереди…") to cs.primary
                    RecordingStatus.Failed -> (rec.error ?: "Ошибка") to cs.error
                    RecordingStatus.Done -> listOf(
                        timeOfDay(rec.createdAt),
                        formatClock(rec.durationMs),
                        if (rec.speakerCount > 0) speakersLabel(rec.speakerCount) else "речь не найдена",
                    ).joinToString(" · ") to cs.onSurfaceVariant
                }
                Text(
                    sub, style = MaterialTheme.typography.bodyMedium.merge(TabularNums), color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!preview.isNullOrBlank() && rec.status == RecordingStatus.Done) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        highlighted(preview, highlight, cs.primary),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (rec.status == RecordingStatus.Done) {
                Spacer(Modifier.width(12.dp))
                SpeakerDots(rec.speakerCount, ring = container)
            }
        }
        if (rec.status == RecordingStatus.Processing) {
            val p = processing?.overall
            if (p != null) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }
        }
    }
}

/** Подсветка найденного слова в превью. */
private fun highlighted(text: String, needle: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (needle.isBlank()) return@buildAnnotatedString
    var from = 0
    val lower = text.lowercase()
    val n = needle.lowercase()
    while (true) {
        val i = lower.indexOf(n, from)
        if (i < 0) break
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), i, i + n.length)
        from = i + n.length
    }
}

@Composable
private fun LeadingTile(rec: Recording, processing: ProcessingState?, dimmed: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (rec.status) {
        RecordingStatus.Failed -> cs.errorContainer to cs.onErrorContainer
        else -> if (rec.fromMic) cs.secondaryContainer to cs.onSecondaryContainer else cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(bg).alpha(if (dimmed) 0.6f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        when (rec.status) {
            RecordingStatus.Processing -> {
                val p = processing?.overall
                if (p != null) CircularProgressIndicator(progress = { p }, Modifier.size(26.dp), color = fg, strokeWidth = 3.dp, trackColor = fg.copy(alpha = 0.2f))
                else CircularProgressIndicator(Modifier.size(26.dp), color = fg, strokeWidth = 3.dp)
            }
            RecordingStatus.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = fg)
            RecordingStatus.Done -> Icon(if (rec.fromMic) Icons.Rounded.Mic else Icons.Rounded.GraphicEq, null, tint = fg)
        }
    }
}

/** Лист «Экспорт в ZIP»: что положить в архив, «Поделиться» или «Сохранить в файлы». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    count: Int,
    audioBytes: Long,
    fileName: String,
    progress: Float?,
    onShare: (ZipOptions) -> Unit,
    onSave: (Uri, ZipOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var json by rememberSaveable { mutableStateOf(true) }
    var text by rememberSaveable { mutableStateOf(true) }
    var audio by rememberSaveable { mutableStateOf(false) }
    val opts = ZipOptions(json, text, audio)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onSave(uri, opts)
    }
    val busy = progress != null
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.FolderZip, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Экспорт в ZIP", style = MaterialTheme.typography.titleLarge)
                    Text(
                        plural(count, "запись", "записи", "записей") + " · по папке на каждую",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            ExportOption("Расшифровка JSON", "Формат из ТЗ: спикер, начало, конец, текст", json, !busy) { json = it }
            ExportOption("Текст TXT", "Имена, конспект и реплики с таймкодами", text, !busy) { text = it }
            ExportOption("Аудио WAV", "+ ${formatBytes(audioBytes)}", audio, !busy) { audio = it }
            Spacer(Modifier.height(20.dp))
            AnimatedVisibility(busy) {
                LinearProgressIndicator(
                    progress = { progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }
            val any = json || text || audio
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { saver.launch(fileName) },
                    enabled = any && !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.SaveAlt, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить")
                }
                Button(
                    onClick = { onShare(opts) },
                    enabled = any && !busy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Поделиться")
                }
            }
        }
    }
}

@Composable
private fun ExportOption(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f ГБ".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b shr 20} МБ"
    else -> "${(b shr 10).coerceAtLeast(1)} КБ"
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.7f))
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(28.dp))
        Text("Пока нет записей", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Импортируйте аудиофайл или запишите разговор — расшифровка с разделением по спикерам и таймкодами появится здесь.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(draft: ImportDraft, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var speakers by rememberSaveable { mutableIntStateOf(0) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.AudioFile, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(draft.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (draft.durationMs > 0) "Длительность ${formatClock(draft.durationMs)}" else "Аудиофайл",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNums),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            SpeakerCountSelector(speakers, { speakers = it })
            Spacer(Modifier.height(8.dp))
            Text(
                "«Авто» определит число участников сам. Если знаете точно — укажите, так точнее.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onConfirm(speakers) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Notes, null)
                Spacer(Modifier.width(10.dp))
                Text("Расшифровать", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    title: String = "Название",
    /** Подсказка под полем; пустое поле разрешено (например, «сбросить имя»). */
    hint: String? = null,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = hint?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = hint != null || text.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

package app.glagol.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.glagol.AppContainer
import app.glagol.audio.AudioRecorder
import app.glagol.data.Utterance
import app.glagol.data.speakerDisplayName
import app.glagol.data.speakerIndex
import app.glagol.export.formatClock
import app.glagol.ml.LiveTranscriber
import app.glagol.ui.components.LiveWave
import app.glagol.ui.components.SpeakerCountSelector
import app.glagol.ui.theme.LocalSpeakerPalette
import app.glagol.ui.theme.TabularNums
import app.glagol.watch.WatchHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RecPhase { Idle, Recording, Stopped }

class RecordViewModel(
    private val cacheDir: File,
    private val container: AppContainer,
    private val assets: AssetManager,
) : ViewModel() {
    val recorder = AudioRecorder()
    private val _phase = MutableStateFlow(RecPhase.Idle)
    val phase = _phase.asStateFlow()
    private var file: File? = null

    /** Текущая громкость 0..1 — для волны. */
    private val _level = MutableStateFlow(0f)
    val level = _level.asStateFlow()

    private var live: LiveTranscriber? = null
    private var liveJob: Job? = null
    private val _live = MutableStateFlow(LiveTranscriber.State())
    /** Живая расшифровка: реплики, «сейчас говорят», найденные имена. */
    val liveState = _live.asStateFlow()

    fun start() {
        if (_phase.value != RecPhase.Idle) return
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        val lt = LiveTranscriber(assets, container.asr, viewModelScope)
        recorder.pcmSink = lt::feed
        runCatching { recorder.start(f, viewModelScope) }.onSuccess {
            file = f
            live = lt
            lt.start()
            liveJob = viewModelScope.launch { lt.state.collect { _live.value = it } }
            _phase.value = RecPhase.Recording
            viewModelScope.launch { recorder.levels.collect { _level.value = it } }
            // Микрофон отвалился посреди записи — сохраняем то, что успели, и предлагаем расшифровать
            viewModelScope.launch {
                recorder.error.collect { if (it != null && _phase.value == RecPhase.Recording) stop() }
            }
        }.onFailure { recorder.pcmSink = null }
    }

    fun stop() {
        viewModelScope.launch {
            recorder.stop()
            recorder.pcmSink = null
            _level.value = 0f
            live?.finish() // дораспознать хвост — текст остаётся на экране
            _phase.value = RecPhase.Stopped
        }
    }

    fun discard() {
        live?.cancel()
        viewModelScope.launch { recorder.stop(); file?.delete(); file = null }
    }

    fun transcribe(speakers: Int): String? {
        val f = file ?: return null
        file = null
        live?.cancel() // освобождаем CPU для полного пайплайна
        val title = "Запись " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru")))
        return container.processing.startFromRecording(f, title, speakers)
    }

    override fun onCleared() {
        live?.cancel()
        recorder.pcmSink = null
        if (recorder.isRecording) kotlinx.coroutines.runBlocking { recorder.stop() }
        file?.delete()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(vm: RecordViewModel, onClose: () -> Unit, onTranscribe: (String) -> Unit) {
    val context = LocalContext.current
    val phase by vm.phase.collectAsStateWithLifecycle()
    val elapsed by vm.recorder.elapsedMs.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val live by vm.liveState.collectAsStateWithLifecycle()
    var denied by remember { mutableStateOf(false) }
    var speakers by rememberSaveable { mutableIntStateOf(0) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.start() else denied = true
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val close = { vm.discard(); onClose() }
    BackHandler(onBack = close)

    // Новое имя у спикера — всплывающий чип «Спикер 2 — это Оля»
    var revealed by remember { mutableStateOf<Pair<String, String>?>(null) }
    var knownNames by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(live.names) {
        val fresh = live.names.entries.firstOrNull { knownNames[it.key] != it.value }
        knownNames = live.names
        if (fresh != null) {
            revealed = fresh.key to fresh.value
            delay(2600)
            revealed = null
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { RecordingClock(phase, elapsed) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Закрыть") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        if (denied) {
            PermissionDenied(Modifier.padding(padding)) { denied = false; permission.launch(Manifest.permission.RECORD_AUDIO) }
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                LiveTranscript(live, phase, Modifier.weight(1f))
                BottomPanel(
                    phase = phase,
                    level = level,
                    live = live,
                    noSignal = vm.recorder.noSignal.collectAsStateWithLifecycle().value,
                    error = vm.recorder.error.collectAsStateWithLifecycle().value,
                    speakers = speakers,
                    onSpeakers = { speakers = it },
                    onStop = vm::stop,
                    onTranscribe = { vm.transcribe(speakers)?.let(onTranscribe) },
                    onDelete = close,
                )
            }
            AnimatedVisibility(
                visible = revealed != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                revealed?.let { (id, name) -> NameRevealChip(id, name) }
            }
        }
    }
}

// ---------------- Шапка ----------------

@Composable
private fun RecordingClock(phase: RecPhase, elapsed: Long) {
    val t = rememberInfiniteTransition(label = "rec")
    val pulse by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    Row(verticalAlignment = Alignment.CenterVertically) {
        val dot = when (phase) {
            RecPhase.Recording -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        }
        Box(
            Modifier.size(10.dp)
                .graphicsLayer { alpha = if (phase == RecPhase.Recording) pulse else 1f }
                .clip(CircleShape).background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Text(formatClock(elapsed), style = MaterialTheme.typography.titleLarge.merge(TabularNums))
    }
}

// ---------------- Живая лента ----------------

@Composable
private fun LiveTranscript(live: LiveTranscriber.State, phase: RecPhase, modifier: Modifier) {
    val list = live.utterances
    if (list.isEmpty()) {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(34.dp), tint = cs.onPrimaryContainer) }
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    phase == RecPhase.Idle -> "Готово к записи"
                    live.warmingUp -> "Загружаю распознавание…"
                    live.speaking -> "Слушаю…"
                    else -> "Говорите — текст появится здесь"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Расшифровка идёт прямо на телефоне, без интернета. Имена участников подставятся сами, когда прозвучат.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val state = rememberLazyListState()
    val drafting = live.speaking || live.partial.isNotEmpty()
    val total = list.size + (if (drafting) 1 else 0)
    // Новая реплика, новый текст или черновик — мягко прокручиваем вниз
    LaunchedEffect(list.size, list.lastOrNull()?.text?.length, drafting, live.partial.length) {
        if (total > 0) state.animateScrollToItem(total - 1)
    }
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        itemsIndexed(list, key = { i, _ -> i }) { i, u ->
            LiveUtteranceRow(
                u,
                name = live.names[u.speakerId],
                showHeader = i == 0 || list[i - 1].speakerId != u.speakerId,
                modifier = Modifier.animateItem(fadeInSpec = tween(400)),
            )
        }
        if (drafting) {
            item(key = "typing") { TypingIndicator(live.partial, Modifier.animateItem()) }
        }
    }
}

/**
 * Реплика живой ленты. Когда у спикера появляется имя, «Спикер N» уезжает вверх, имя въезжает снизу,
 * вокруг вспыхивает и гаснет подсветка цвета спикера, цифра в аватаре сменяется буквой.
 */
@Composable
private fun LiveUtteranceRow(u: Utterance, name: String?, showHeader: Boolean, modifier: Modifier = Modifier) {
    val idx = speakerIndex(u.speakerId)
    val color = LocalSpeakerPalette.current[idx]
    val label = name ?: speakerDisplayName(u.speakerId)
    val glow = remember { Animatable(0f) }
    var prevName by remember { mutableStateOf(name) }
    LaunchedEffect(name) {
        if (name != null && name != prevName) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(1800))
        }
        prevName = name
    }
    Row(modifier.fillMaxWidth().padding(top = if (showHeader) 16.dp else 6.dp)) {
        Box(Modifier.width(32.dp)) {
            if (showHeader) {
                Box(
                    Modifier.size(32.dp).scale(1f + 0.18f * glow.value).clip(CircleShape).background(color.container),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = name?.firstOrNull { it.isLetter() }?.uppercase() ?: "${idx + 1}",
                        transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                        label = "avatar",
                    ) { ch ->
                        Text(ch, color = color.onContainer, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.accent.copy(alpha = 0.28f * glow.value))
                        .padding(horizontal = 4.dp * glow.value.coerceAtLeast(0f)),
                ) {
                    AnimatedContent(
                        targetState = label,
                        transitionSpec = {
                            (slideInVertically(spring(dampingRatio = 0.7f)) { it } + fadeIn()) togetherWith
                                (slideOutVertically { -it } + fadeOut()) using SizeTransform(clip = false)
                        },
                        label = "name",
                    ) { l ->
                        Text(
                            l,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (name != null) FontWeight.Bold else FontWeight.Medium,
                            color = color.accent,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(u.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Черновик текущей фразы (распознаётся по ходу) и три «печатающие» точки — речь идёт, итоговый текст сейчас появится. */
@Composable
private fun TypingIndicator(partial: String, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "typing")
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(start = 44.dp, top = 12.dp)) {
        if (partial.isNotEmpty()) {
            Text(
                partial,
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.animateContentSize(),
            )
            Spacer(Modifier.height(6.dp))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    val a by t.animateFloat(
                        0.25f, 1f,
                        infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse),
                        label = "dot$i",
                    )
                    Box(Modifier.size(8.dp).graphicsLayer { alpha = a; translationY = -4f * a }.clip(CircleShape).background(cs.onSurfaceVariant))
                }
            }
        }
    }
}

@Composable
private fun NameRevealChip(speakerId: String, name: String) {
    val color = LocalSpeakerPalette.current[speakerIndex(speakerId)]
    Surface(shape = CircleShape, color = color.container, shadowElevation = 6.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = color.onContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                "${speakerDisplayName(speakerId)} — это $name",
                style = MaterialTheme.typography.labelLarge,
                color = color.onContainer,
            )
        }
    }
}

// ---------------- Низ: волна и кнопки ----------------

@Composable
private fun BottomPanel(
    phase: RecPhase,
    level: Float,
    live: LiveTranscriber.State,
    noSignal: Boolean,
    error: String?,
    speakers: Int,
    onSpeakers: (Int) -> Unit,
    onStop: () -> Unit,
    onTranscribe: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val palette = LocalSpeakerPalette.current
    // Волна окрашивается в цвет того, кто говорил последним
    val lastSpk = live.utterances.lastOrNull()?.let { speakerIndex(it.speakerId) }
    val waveColors = listOf(cs.primary, lastSpk?.let { palette[it].accent } ?: cs.tertiary, cs.primary)
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        val warning = error ?: if (noSignal && phase == RecPhase.Recording) "Микрофон не передаёт звук — проверьте, не занят ли он" else null
        AnimatedVisibility(warning != null, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.errorContainer,
                contentColor = cs.onErrorContainer,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(warning.orEmpty(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(if (phase == RecPhase.Stopped) 72.dp else 168.dp), contentAlignment = Alignment.Center) {
            LiveWave(level, active = phase == RecPhase.Recording, colors = waveColors, modifier = Modifier.fillMaxSize())
            androidx.compose.animation.AnimatedVisibility(phase != RecPhase.Stopped, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                // Кнопка «Стоп» с мягко «дышащим» кольцом, пока идёт запись
                val ring = rememberInfiniteTransition(label = "ring")
                val k by ring.animateFloat(0f, 1f, infiniteRepeatable(tween(1600)), label = "k")
                Box(contentAlignment = Alignment.Center) {
                    if (phase == RecPhase.Recording) {
                        Box(
                            Modifier.size(76.dp)
                                .graphicsLayer { scaleX = 1f + 0.45f * k; scaleY = 1f + 0.45f * k; alpha = 0.45f * (1f - k) }
                                .clip(CircleShape).background(MaterialTheme.colorScheme.error)
                        )
                    }
                    FilledIconButton(
                        onClick = onStop,
                        enabled = phase == RecPhase.Recording,
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(if (phase == RecPhase.Recording) Icons.Rounded.Stop else Icons.Rounded.Mic, "Остановить", Modifier.size(36.dp))
                    }
                }
            }
        }
        AnimatedContent(
            targetState = phase == RecPhase.Stopped,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "controls",
        ) { stopped ->
            if (!stopped) {
                Text(
                    if (phase == RecPhase.Recording) "Нажмите, чтобы остановить" else "Подготовка…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
                    SpeakerCountSelector(speakers, onSpeakers)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onTranscribe, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Notes, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Расшифровать начисто", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Удалить запись") }
                }
            }
        }
    }
}

@Composable
private fun PermissionDenied(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.MicOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(
            "Нужен доступ к микрофону, чтобы записать разговор. Запись не покидает устройство.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Разрешить") }
    }
}

// ---------------- Запись с часов ----------------

/**
 * Живой экран записи, которая идёт на часах: звук приходит потоком ([WatchHub]), текст распознаётся здесь, на телефоне.
 * Остановка — на часах; после неё сразу полная расшифровка и переход к записи.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchLiveScreen(hub: WatchHub, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val session by hub.live.collectAsStateWithLifecycle()
    val live by hub.transcript.collectAsStateWithLifecycle()
    val level by hub.level.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { hub.finished.collect { onOpen(it) } }
    val close = { hub.dismissBroken(); onClose() }
    BackHandler(onBack = close)
    val broken = session?.broken == true
    val recording = session != null && !broken
    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RecordingClock(if (recording) RecPhase.Recording else RecPhase.Stopped, session?.elapsedMs ?: 0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Watch, null, Modifier.size(14.dp), tint = cs.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("Запись с часов", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, "Закрыть") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LiveTranscript(live, RecPhase.Recording, Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                AnimatedVisibility(broken, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = cs.errorContainer,
                        contentColor = cs.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Связь с часами прервалась. Запись сохранена на часах и придёт целиком после остановки.",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                val palette = LocalSpeakerPalette.current
                val lastSpk = live.utterances.lastOrNull()?.let { speakerIndex(it.speakerId) }
                LiveWave(
                    level,
                    active = recording,
                    colors = listOf(cs.primary, lastSpk?.let { palette[it].accent } ?: cs.tertiary, cs.primary),
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
                Text(
                    when {
                        broken -> ""
                        recording -> "Остановите запись на часах — расшифровка начнётся сама"
                        else -> "Готовлю расшифровку…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 20.dp),
                )
                if (broken) {
                    TextButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("На главную") }
                }
            }
        }
    }
}

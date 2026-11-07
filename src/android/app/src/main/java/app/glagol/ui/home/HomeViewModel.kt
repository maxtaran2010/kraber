package app.glagol.ui.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.glagol.AppContainer
import app.glagol.audio.AudioDecoder
import app.glagol.data.Recording
import app.glagol.data.RecordingStatus
import app.glagol.export.ZipExport
import app.glagol.export.ZipOptions
import app.glagol.ui.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ImportDraft(val uri: Uri, val name: String, val durationMs: Long)

/** Найденная запись и фрагмент текста вокруг совпадения (если нашлось в расшифровке). */
data class SearchHit(val rec: Recording, val snippet: String?)

class HomeViewModel(private val app: Application, private val container: AppContainer) : ViewModel() {

    private val repo = container.repository
    val recordings = repo.recordings
    val processing = container.processing.state

    private val _draft = MutableStateFlow<ImportDraft?>(null)
    val draft: StateFlow<ImportDraft?> = _draft.asStateFlow()

    /** Короткие сообщения для снекбара («Удалено 3 записи», «Архив сохранён»). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages = _messages.asSharedFlow()

    fun prepareImport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = AudioDecoder.displayName(app, uri)
            val duration = AudioDecoder.probeDurationMs(app, uri)
            _draft.value = ImportDraft(uri, name, duration)
        }
    }

    fun dismissDraft() { _draft.value = null }

    fun startImport(draft: ImportDraft, speakers: Int): String {
        _draft.value = null
        return container.processing.startFromUri(draft.uri, draft.name, speakers)
    }

    fun rename(id: String, title: String) {
        if (title.isBlank()) return
        repo.update(id) { it.copy(title = title.trim(), titleEdited = true) }
    }

    private fun delete(id: String) {
        container.processing.cancel(id)
        repo.delete(id)
    }

    // ---------------- Выделение ----------------

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    init {
        // Удалённые записи не должны «висеть» в выделении
        viewModelScope.launch {
            recordings.collect { list ->
                val ids = list.mapTo(HashSet()) { it.id }
                _selected.update { sel -> if (sel.all { it in ids }) sel else sel.filterTo(HashSet()) { it in ids } }
            }
        }
    }

    fun toggle(id: String) = _selected.update { if (id in it) it - id else it + id }
    fun selectAll(ids: Collection<String>) { _selected.value = ids.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        _selected.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach(::delete)
            _messages.tryEmit("Удалено: ${plural(ids.size, "запись", "записи", "записей")}")
        }
    }

    // ---------------- Экспорт ZIP ----------------

    private val _exportProgress = MutableStateFlow<Float?>(null)
    /** null — архив не собирается; иначе 0..1. */
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _shareIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val shareIntents = _shareIntents.asSharedFlow()

    private fun selectedRecordings(): List<Recording> {
        val ids = _selected.value
        return recordings.value.filter { it.id in ids }.sortedBy { it.createdAt }
    }

    fun audioBytesOfSelection(): Long = ZipExport.audioBytes(repo, selectedRecordings())

    fun zipName(): String = ZipExport.fileName(_selected.value.size)

    /** Собирает архив во временный файл и отдаёт системному «Поделиться». */
    fun shareZip(opts: ZipOptions) {
        val recs = selectedRecordings().ifEmpty { return }
        if (_exportProgress.value != null) return
        viewModelScope.launch {
            _exportProgress.value = 0f
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val file = ZipExport.tempFile(app, ZipExport.fileName(recs.size))
                    file.outputStream().use { out -> ZipExport.write(repo, recs, opts, out) { _exportProgress.value = it } }
                    ZipExport.shareIntent(app, file)
                }
            }
            _exportProgress.value = null
            result.onSuccess { _shareIntents.tryEmit(it); _selected.value = emptySet() }
                .onFailure { _messages.tryEmit("Не удалось собрать архив: ${it.message}") }
        }
    }

    /** Сохраняет архив в выбранное пользователем место (системный диалог «Сохранить как»). */
    fun saveZip(uri: Uri, opts: ZipOptions) {
        val recs = selectedRecordings().ifEmpty { return }
        if (_exportProgress.value != null) return
        viewModelScope.launch {
            _exportProgress.value = 0f
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val out = app.contentResolver.openOutputStream(uri, "wt") ?: error("нет доступа к файлу")
                    out.use { ZipExport.write(repo, recs, opts, it) { p -> _exportProgress.value = p } }
                }
            }
            _exportProgress.value = null
            result.onSuccess {
                _selected.value = emptySet()
                _messages.tryEmit("Архив сохранён")
            }.onFailure { _messages.tryEmit("Не удалось сохранить архив: ${it.message}") }
        }
    }

    // ---------------- Поиск ----------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    /** Текст расшифровок в нижнем регистре; ключ — id + время обработки (меняется при пересчёте). */
    private val textCache = ConcurrentHashMap<String, String>()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<SearchHit>?> = combine(recordings, _query.debounce(150)) { list, q -> list to q.trim() }
        .mapLatest { (list, q) -> if (q.isEmpty()) null else search(list, q) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun search(list: List<Recording>, q: String): List<SearchHit> {
        val needle = q.lowercase()
        return list.mapNotNull { rec ->
            val meta = buildString {
                append(rec.title).append('\n')
                rec.digest?.let { d -> append(d.gist).append('\n'); d.points.forEach { append(it).append('\n') } }
                rec.summary?.let { append(it).append('\n') }
                rec.knownSpeakerNames.values.forEach { append(it).append('\n') }
                append(rec.sourceName)
            }
            if (meta.lowercase().contains(needle)) return@mapNotNull SearchHit(rec, null)
            if (rec.status != RecordingStatus.Done) return@mapNotNull null
            val key = "${rec.id}:${rec.processingMs}"
            val raw = textCache.getOrPut(key) {
                repo.loadTranscript(rec.id).orEmpty().joinToString(" ") { it.text }
            }
            val at = raw.lowercase().indexOf(needle)
            if (at < 0) null else SearchHit(rec, snippet(raw, at, needle.length))
        }
    }

    private fun snippet(text: String, at: Int, len: Int): String {
        val from = (at - 40).coerceAtLeast(0).let { s -> if (s == 0) 0 else text.indexOf(' ', s).takeIf { it in s until at }?.plus(1) ?: s }
        val to = (at + len + 60).coerceAtMost(text.length).let { e -> if (e == text.length) e else text.lastIndexOf(' ', e).takeIf { it > at + len } ?: e }
        return (if (from > 0) "…" else "") + text.substring(from, to).trim() + (if (to < text.length) "…" else "")
    }
}

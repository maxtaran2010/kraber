package app.glagol.ui.detail

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.glagol.AppContainer
import app.glagol.audio.Player
import app.glagol.data.Recording
import app.glagol.data.RecordingStatus
import app.glagol.data.Utterance
import app.glagol.export.TranscriptExport
import app.glagol.ml.SpeakerNamer
import app.glagol.service.ProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailViewModel(
    private val app: Application,
    private val container: AppContainer,
    val id: String,
) : ViewModel() {

    private val repo = container.repository
    private val manager = container.processing

    val recording: StateFlow<Recording?> = repo.observe(id)
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.get(id))

    /** Расшифровка перечитывается, когда обработка завершилась (в т.ч. повторная). */
    val transcript: StateFlow<List<Utterance>?> = repo.observe(id)
        .map { it?.status to it?.processingMs }
        .distinctUntilChanged()
        .map { (status, _) -> if (status == RecordingStatus.Done) repo.loadTranscript(id) else null }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Таймкоды слов для подсветки при проигрывании (null — старая запись, без words.json). */
    val wordTimes: StateFlow<List<LongArray>?> = transcript
        .map { t -> if (t == null) null else repo.loadWords(id)?.takeIf { it.size == t.size } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val player = Player(viewModelScope) { repo.audioFile(id) }

    /** true — после фразы играть дальше; false — только эту фразу. */
    val continuous = MutableStateFlow(true)

    /** Тап по реплике: играть с неё; тап по играющей — пауза/продолжить. */
    fun playUtterance(index: Int) {
        val list = transcript.value ?: return
        val u = list.getOrNull(index) ?: return
        val pos = player.position.value
        val current = player.started.value && pos >= u.startMs && pos < u.endMs
        when {
            current && player.playing.value -> player.pause()
            current -> player.resume()
            else -> player.play(u.startMs, if (continuous.value) null else u.endMs)
        }
    }

    fun seekTo(ms: Long) {
        if (player.playing.value) player.play(ms) else player.seek(ms)
    }

    fun setContinuous(on: Boolean) {
        continuous.value = on
        if (on) player.clearStopAt()
    }

    init {
        // Обработка запущена заново — старое аудио/таймкоды больше не актуальны
        viewModelScope.launch {
            recording.map { it?.status }.distinctUntilChanged().collect { if (it != RecordingStatus.Done) player.release() }
        }
    }

    override fun onCleared() {
        player.release()
    }

    init {
        // Записи, расшифрованные до появления поиска имён, — ищем имена при первом открытии
        viewModelScope.launch(Dispatchers.Default) {
            val rec = repo.get(id) ?: return@launch
            if (rec.status != RecordingStatus.Done || rec.speakerNames != null) return@launch
            val names = SpeakerNamer.detect(repo.loadTranscript(id).orEmpty())
            repo.update(id) { if (it.speakerNames == null) it.copy(speakerNames = names) else it }
        }
    }

    val processing: StateFlow<ProcessingState?> = manager.state
        .map { it?.takeIf { s -> s.recordingId == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val liveSummary: StateFlow<String?> = manager.liveSummary
        .map { it[id] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ответ LLM на вопрос, пока он печатается. */
    val liveAnswer: StateFlow<String?> = manager.liveAnswer
        .map { it[id] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val asking: StateFlow<Boolean> = manager.asking
        .map { id in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun ask(question: String) {
        if (question.isNotBlank()) manager.ask(id, question.trim())
    }

    fun suggestedJsonName(): String = recording.value?.let { TranscriptExport.suggestedFileName(it, "json") } ?: "transcript.json"

    suspend fun writeJsonTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val list = transcript.value ?: repo.loadTranscript(id) ?: return@withContext false
        runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(TranscriptExport.toJson(list).toByteArray()) }
                ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    fun shareJsonIntent(): Intent? {
        val rec = recording.value ?: return null
        val list = transcript.value ?: return null
        return TranscriptExport.shareIntent(app, TranscriptExport.suggestedFileName(rec, "json"), TranscriptExport.toJson(list), "application/json")
    }

    fun shareTextIntent(): Intent? {
        val rec = recording.value ?: return null
        val list = transcript.value ?: return null
        return TranscriptExport.shareIntent(app, TranscriptExport.suggestedFileName(rec, "txt"), TranscriptExport.toText(rec, list), "text/plain")
    }

    fun plainText(): String? {
        val rec = recording.value ?: return null
        val list = transcript.value ?: return null
        return TranscriptExport.toText(rec, list)
    }

    fun cancel() = manager.cancel(id)
    fun reprocess(speakers: Int) = manager.reprocess(id, speakers)
    fun retrySummary() = manager.retrySummary(id)
    fun rename(title: String) {
        if (title.isNotBlank()) repo.update(id) { it.copy(title = title.trim(), titleEdited = true) }
    }
    /** Пустое имя — вернуть найденное автоматически (или «Спикер N»). */
    fun renameSpeaker(speakerId: String, name: String) {
        val n = name.trim()
        repo.update(id) {
            it.copy(speakerNamesUser = if (n.isEmpty()) it.speakerNamesUser - speakerId else it.speakerNamesUser + (speakerId to n))
        }
    }

    fun delete() {
        manager.cancel(id)
        repo.delete(id)
    }
}

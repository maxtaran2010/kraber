package app.glagol.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Хранилище записей: всё лежит локально в filesDir/recordings/<id>/
 *  - meta.json        — [Recording]
 *  - audio.wav        — аудио 16 кГц mono (после декодирования)
 *  - transcript.json  — расшифровка, сразу в формате экспорта из ТЗ
 *  - words.json       — таймкоды слов каждой реплики (подсветка при проигрывании; в экспорт не идёт)
 */
class RecordingsRepository(context: Context) {

    private val root = File(context.filesDir, "recordings").apply { mkdirs() }
    private val lock = Any()

    private val _recordings = MutableStateFlow(loadAll())
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()

    fun dir(id: String) = File(root, id)
    fun audioFile(id: String) = File(dir(id), "audio.wav")
    private fun metaFile(id: String) = File(dir(id), "meta.json")
    private fun transcriptFile(id: String) = File(dir(id), "transcript.json")
    private fun wordsFile(id: String) = File(dir(id), "words.json")

    fun get(id: String): Recording? = _recordings.value.firstOrNull { it.id == id }

    fun observe(id: String): Flow<Recording?> =
        recordings.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun create(title: String, sourceName: String, requestedSpeakers: Int, fromMic: Boolean): Recording {
        val now = System.currentTimeMillis()
        val id = "r" + now.toString(36) + (100..999).random()
        dir(id).mkdirs()
        val rec = Recording(
            id = id,
            title = title,
            sourceName = sourceName,
            createdAt = now,
            fromMic = fromMic,
            requestedSpeakers = requestedSpeakers,
        )
        synchronized(lock) {
            writeMeta(rec)
            _recordings.update { listOf(rec) + it }
        }
        return rec
    }

    fun update(id: String, transform: (Recording) -> Recording) {
        synchronized(lock) {
            val current = get(id) ?: return
            val updated = transform(current)
            writeMeta(updated)
            _recordings.update { list -> list.map { if (it.id == id) updated else it } }
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            dir(id).deleteRecursively()
            _recordings.update { list -> list.filterNot { it.id == id } }
        }
    }

    fun saveTranscript(id: String, utterances: List<Utterance>) {
        transcriptFile(id).writeText(json.encodeToString(TranscriptFile.serializer(), TranscriptFile(utterances)))
    }

    fun loadTranscript(id: String): List<Utterance>? = runCatching {
        json.decodeFromString(TranscriptFile.serializer(), transcriptFile(id).readText()).transcript
    }.getOrNull()

    fun saveWords(id: String, wordTimes: List<LongArray>) {
        runCatching {
            wordsFile(id).writeText(compact.encodeToString(WordTimesFile.serializer(), WordTimesFile(wordTimes.map { it.toList() })))
        }.onFailure { Log.w(TAG, "words.json not saved: $it") }
    }

    /** Таймкоды слов `[s0, e0, s1, e1, …]` для каждой реплики; null — файла нет (старая запись). */
    fun loadWords(id: String): List<LongArray>? = runCatching {
        compact.decodeFromString(WordTimesFile.serializer(), wordsFile(id).readText()).words.map { it.toLongArray() }
    }.getOrNull()

    private fun writeMeta(rec: Recording) {
        val f = metaFile(rec.id)
        val tmp = File(f.parentFile, "meta.json.tmp")
        tmp.writeText(json.encodeToString(Recording.serializer(), rec))
        tmp.renameTo(f)
    }

    private fun loadAll(): List<Recording> {
        val list = root.listFiles()?.mapNotNull { d ->
            runCatching { json.decodeFromString(Recording.serializer(), File(d, "meta.json").readText()) }
                .onFailure { Log.w(TAG, "skip ${d.name}: $it") }
                .getOrNull()
        }.orEmpty()
        // Если процесс был убит посреди обработки — помечаем такие записи как прерванные.
        return list.map { rec ->
            var r = rec
            if (r.status == RecordingStatus.Processing) {
                r = r.copy(status = RecordingStatus.Failed, error = "Обработка была прервана")
            }
            if (r.summaryStatus == SummaryStatus.Generating || r.summaryStatus == SummaryStatus.Pending) {
                r = r.copy(summaryStatus = SummaryStatus.Failed)
            }
            if (r != rec) writeMeta(r)
            r
        }.sortedByDescending { it.createdAt }
    }

    @Serializable
    private data class WordTimesFile(val words: List<List<Long>>)

    companion object {
        private const val TAG = "Repo"
        private val compact = Json { ignoreUnknownKeys = true }
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}

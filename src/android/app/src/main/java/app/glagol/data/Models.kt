package app.glagol.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Одна реплика. Имена полей совпадают с форматом из ТЗ. */
@Serializable
data class Utterance(
    @SerialName("speaker_id") val speakerId: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("text") val text: String,
)

/** Корневой объект JSON-экспорта: `{"transcript": [...]}`. */
@Serializable
data class TranscriptFile(
    @SerialName("transcript") val transcript: List<Utterance>,
)

@Serializable
enum class RecordingStatus { Processing, Done, Failed }

@Serializable
enum class SummaryStatus { None, Pending, Generating, Done, Failed }

@Serializable
data class Recording(
    val id: String,
    val title: String,
    /** Пользователь сам переименовал — не перезаписываем названием от LLM. */
    val titleEdited: Boolean = false,
    val sourceName: String,
    val createdAt: Long,
    val fromMic: Boolean = false,
    val durationMs: Long = 0,
    val status: RecordingStatus = RecordingStatus.Processing,
    /** 0 — определить автоматически. */
    val requestedSpeakers: Int = 0,
    val speakerCount: Int = 0,
    /** Доля времени речи speaker_1..N, сумма = 1. */
    val speakerShare: List<Float> = emptyList(),
    val processingMs: Long = 0,
    val stageMs: Map<String, Long> = emptyMap(),
    val error: String? = null,
    val summary: String? = null,
    val summaryStatus: SummaryStatus = SummaryStatus.None,
    /** Понятная причина, если конспект недоступен. */
    val summaryError: String? = null,
    /** Имена, найденные по тексту разговора (speaker_N → имя); null — ещё не искали. В JSON-экспорт не попадают. */
    val speakerNames: Map<String, String>? = null,
    /** Имена, заданные пользователем, — важнее найденных. */
    val speakerNamesUser: Map<String, String> = emptyMap(),
    /** Структурированный конспект (v2). [summary] — его текстовая версия для старых экранов и TXT. */
    val digest: Summary? = null,
    /** Какая LLM составила конспект (null — старые записи, 1.7B). */
    val summaryModel: String? = null,
    /** Вопросы к LLM по этой записи и ответы на них. */
    val qa: List<QaItem> = emptyList(),
) {
    /** Real-time factor: время обработки / длительность аудио. */
    val rtf: Float get() = if (durationMs > 0) processingMs.toFloat() / durationMs else 0f

    /** Имя спикера для показа: заданное пользователем → найденное в разговоре → «Спикер N». */
    fun speakerLabel(speakerId: String): String =
        speakerNamesUser[speakerId] ?: speakerNames?.get(speakerId) ?: speakerDisplayName(speakerId)

    /** Только известные имена (без «Спикер N»). */
    val knownSpeakerNames: Map<String, String> get() = (speakerNames.orEmpty() + speakerNamesUser)
}

/** Конспект: суть, ключевые моменты, договорённости и хронология (для длинных записей). */
@Serializable
data class Summary(
    val gist: String = "",
    val points: List<String> = emptyList(),
    val agreed: List<String> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
) {
    val isEmpty: Boolean get() = gist.isBlank() && points.isEmpty() && agreed.isEmpty() && chapters.isEmpty()
}

/** Часть длинного разговора: о чём говорили с [startMs] по [endMs]. */
@Serializable
data class Chapter(val startMs: Long, val endMs: Long, val text: String)

/** Вопрос к LLM по записи и ответ; [at] — когда спросили (epoch ms). */
@Serializable
data class QaItem(val q: String, val a: String, val at: Long)

/** "speaker_3" -> 2 */
fun speakerIndex(speakerId: String): Int =
    speakerId.substringAfterLast('_').toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0

fun speakerDisplayName(speakerId: String): String = "Спикер ${speakerIndex(speakerId) + 1}"

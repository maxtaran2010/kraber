package app.glagol.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.glagol.data.Recording
import app.glagol.data.TranscriptFile
import app.glagol.data.Utterance
import kotlinx.serialization.json.Json
import java.io.File

object TranscriptExport {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Строго формат из ТЗ: {"transcript":[{"speaker_id","start_ms","end_ms","text"}]} */
    fun toJson(utterances: List<Utterance>): String =
        json.encodeToString(TranscriptFile.serializer(), TranscriptFile(utterances))

    fun toText(recording: Recording, utterances: List<Utterance>): String = buildString {
        appendLine(recording.title)
        appendLine("Длительность: ${formatClock(recording.durationMs)} · спикеров: ${recording.speakerCount}")
        appendLine()
        val digest = recording.digest?.takeUnless { it.isEmpty }
        if (digest != null) {
            appendLine("Кратко:")
            if (digest.gist.isNotBlank()) appendLine(digest.gist.trim())
            digest.points.forEach { appendLine("• $it") }
            if (digest.agreed.isNotEmpty()) {
                appendLine()
                appendLine("Договорились:")
                digest.agreed.forEach { appendLine("✓ $it") }
            }
            if (digest.chapters.isNotEmpty()) {
                appendLine()
                appendLine("Хронология:")
                digest.chapters.forEach { appendLine("[${formatClock(it.startMs)}–${formatClock(it.endMs)}] ${it.text}") }
            }
            appendLine()
        } else recording.summary?.takeIf { it.isNotBlank() }?.let {
            // Записи до конспекта v2 — только текст
            appendLine("Кратко:")
            appendLine(it.trim())
            appendLine()
        }
        utterances.forEach { u ->
            appendLine("[${formatClock(u.startMs)}–${formatClock(u.endMs)}] ${recording.speakerLabel(u.speakerId)}: ${u.text}")
        }
    }

    fun suggestedFileName(recording: Recording, ext: String): String {
        val base = recording.sourceName.substringBeforeLast('.').ifBlank { recording.title }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        return "$base.$ext"
    }

    /** Кладёт файл в cache/export и отдаёт Intent «Поделиться». */
    fun shareIntent(context: Context, fileName: String, content: String, mime: String): Intent {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, fileName)
    }
}

/** 83_250 -> "1:23", 3_723_000 -> "1:02:03" */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

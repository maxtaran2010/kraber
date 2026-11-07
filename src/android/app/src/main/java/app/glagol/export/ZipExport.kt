package app.glagol.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.glagol.data.Recording
import app.glagol.data.RecordingsRepository
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Что класть в архив. */
data class ZipOptions(val json: Boolean = true, val text: Boolean = true, val audio: Boolean = false)

/**
 * Экспорт нескольких записей одним ZIP: по папке на запись —
 * `transcript.json` (строго формат ТЗ), `transcript.txt` (имена, конспект, таймкоды) и, по желанию, `audio.wav`.
 */
object ZipExport {

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US)

    fun fileName(count: Int): String =
        "Kraber-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}-$count.zip"

    /** Примерный размер аудио, байт (для подписи «+ 45 МБ»). */
    fun audioBytes(repo: RecordingsRepository, recs: List<Recording>): Long =
        recs.sumOf { repo.audioFile(it.id).takeIf(File::exists)?.length() ?: 0L }

    fun write(repo: RecordingsRepository, recs: List<Recording>, opts: ZipOptions, out: OutputStream, onProgress: (Float) -> Unit = {}) {
        val used = HashSet<String>()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            recs.forEachIndexed { i, rec ->
                val folder = uniqueName(folderName(rec), used)
                val transcript = repo.loadTranscript(rec.id)
                if (transcript != null) {
                    if (opts.json) zip.putText("$folder/transcript.json", TranscriptExport.toJson(transcript))
                    if (opts.text) zip.putText("$folder/transcript.txt", TranscriptExport.toText(rec, transcript))
                }
                if (opts.audio) {
                    val wav = repo.audioFile(rec.id)
                    if (wav.exists()) {
                        zip.putNextEntry(ZipEntry("$folder/audio.wav"))
                        wav.inputStream().use { it.copyTo(zip, 1 shl 16) }
                        zip.closeEntry()
                    }
                }
                onProgress((i + 1f) / recs.size)
            }
        }
    }

    /** Собирает архив в cache/export и возвращает Intent «Поделиться». */
    fun shareIntent(context: Context, zip: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, zip.name)
    }

    fun tempFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles { f -> f.name.endsWith(".zip") }?.forEach { it.delete() } // старые архивы не копим
        return File(dir, name)
    }

    private fun folderName(rec: Recording): String =
        "${stamp.format(Date(rec.createdAt))} ${rec.title}"
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
            .trim()
            .take(80)

    private fun uniqueName(base: String, used: MutableSet<String>): String {
        var name = base
        var k = 2
        while (!used.add(name.lowercase())) name = "$base ($k)".also { k++ }
        return name
    }

    private fun ZipOutputStream.putText(path: String, text: String) {
        putNextEntry(ZipEntry(path))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}

package app.glagol.wear

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.random.Random

/** Отправка готового WAV по [WatchProtocol] в режиме FILE (чистый JVM-код — покрыт тестом). */
object FileFraming {
    /** 0,1 с звука в кадре: 1600 сэмплов = 3200 байт. */
    const val FRAME_SAMPLES = 1600

    /**
     * Пишет в [out] заголовок [header], всё тело [wav] кадрами AUDIO и END с числом сэмплов, затем flush.
     * [onProgress] (отправлено, всего сэмплов) — после каждого кадра. Возвращает число сэмплов.
     * Файл не 16 кГц mono PCM16 — [IOException] до того, как что-либо записано.
     */
    fun writeFileStream(
        wav: File,
        out: DataOutputStream,
        header: WatchProtocol.Header,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        val info = WavFile.info(wav)
        if (!info.isOurFormat) {
            throw IOException("${wav.name}: ${info.sampleRate} Гц, ${info.channels} кан., ${info.bitsPerSample} бит — нужен 16 кГц mono PCM16")
        }
        val total = info.samples
        WatchProtocol.writeHeader(out, header)
        val buf = ByteArray(FRAME_SAMPLES * 2)
        FileInputStream(wav).use { fis ->
            fis.channel.position(info.dataOffset)
            val inp = DataInputStream(BufferedInputStream(fis, 1 shl 16))
            var sent = 0L
            while (sent < total) {
                val n = minOf(FRAME_SAMPLES.toLong(), total - sent).toInt()
                inp.readFully(buf, 0, n * 2)
                WatchProtocol.writeAudio(out, buf, n * 2)
                sent += n
                onProgress(sent, total)
            }
        }
        WatchProtocol.writeEnd(out, total)
        out.flush()
        return total
    }
}

/**
 * Идентификатор записи = имя её файла на часах: время старта (base36) + «-» + 4 случайных символа.
 * Время старта из него восстанавливается — отдельный файл с метаданными не нужен.
 */
object SessionId {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun new(startedAt: Long, random: Random = Random.Default): String =
        startedAt.toString(36) + "-" + String(CharArray(4) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** Время старта записи (мс эпохи) или null, если [sid] не нашего формата. */
    fun startedAt(sid: String): Long? = sid.substringBefore('-').toLongOrNull(36)?.takeIf { it > 0 }
}

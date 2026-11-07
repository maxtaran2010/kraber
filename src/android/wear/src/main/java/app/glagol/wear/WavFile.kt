package app.glagol.wear

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV PCM16 без Android-зависимостей (используется и в JVM-тестах). */
object WavFile {

    /** Где в файле звук и какого он формата. [dataBytes] — уже с поправкой на оборванную запись. */
    data class Info(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long,
    ) {
        /** Наш формат: PCM 16 бит, mono, 16 кГц. */
        val isOurFormat: Boolean
            get() = format == 1 && channels == 1 && bitsPerSample == 16 && sampleRate == WatchProtocol.SAMPLE_RATE

        val samples: Long get() = dataBytes / (channels.coerceAtLeast(1) * 2)
    }

    /** 44-байтный заголовок mono PCM16. */
    fun header(dataBytes: Long, sampleRate: Int = WatchProtocol.SAMPLE_RATE): ByteArray {
        val data = dataBytes.coerceIn(0, Int.MAX_VALUE - 36L).toInt()
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(36 + data); bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(data)
        return bb.array()
    }

    /**
     * Разбирает заголовок. Если в нём длина данных 0 или больше реальной (запись оборвалась
     * до того, как заголовок исправили при остановке), берём длину по размеру файла.
     */
    fun info(file: File): Info = RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        val b = ByteArray(16)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (len < 12) throw IOException("${file.name}: не WAV")
        raf.readFully(b, 0, 12)
        if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("${file.name}: не WAV")
        }
        var fmt: IntArray? = null // format, channels, rate, bits
        var pos = 12L
        while (pos + 8 <= len) {
            raf.seek(pos)
            raf.readFully(b, 0, 8)
            val id = String(b, 0, 4, Charsets.US_ASCII)
            val size = bb.getInt(4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > len) throw IOException("${file.name}: битый fmt")
                    raf.readFully(b, 0, 16)
                    fmt = intArrayOf(
                        bb.getShort(0).toInt() and 0xFFFF,
                        bb.getShort(2).toInt() and 0xFFFF,
                        bb.getInt(4),
                        bb.getShort(14).toInt() and 0xFFFF,
                    )
                }
                "data" -> {
                    val f = fmt ?: throw IOException("${file.name}: нет fmt перед data")
                    val actual = len - body
                    var bytes = if (size == 0L || size > actual) actual else size
                    val block = (f[1] * f[3] / 8).coerceAtLeast(1)
                    bytes -= bytes % block
                    return@use Info(f[0], f[1], f[2], f[3], body, bytes)
                }
            }
            pos = body + size + (size and 1)
        }
        throw IOException("${file.name}: нет данных")
    }
}

package app.glagol.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavIO {

    const val SAMPLE_RATE = 16_000

    fun write(file: File, samples: FloatArray, sampleRate: Int = SAMPLE_RATE) {
        file.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            out.write(header(samples.size * 2, sampleRate))
            val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (buf.remaining() < 2) {
                    out.write(buf.array(), 0, buf.position()); buf.clear()
                }
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buf.putShort(v)
            }
            out.write(buf.array(), 0, buf.position())
        }
    }

    /** Читает PCM16 WAV (наш собственный формат: mono 16 кГц, но обработает и stereo / другие частоты). */
    fun read(file: File): Pair<FloatArray, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt())
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a wav" }
            var pos = 12
            var channels = 1
            var rate = SAMPLE_RATE
            var bits = 16
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = bb.getInt(pos + 4)
                val body = pos + 8
                if (id == "fmt ") {
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                } else if (id == "data") {
                    require(bits == 16) { "only PCM16" }
                    val len = minOf(size, bytes.size - body)
                    val frames = len / (2 * channels)
                    val out = FloatArray(frames)
                    for (i in 0 until frames) {
                        var acc = 0f
                        for (c in 0 until channels) acc += bb.getShort(body + (i * channels + c) * 2) / 32768f
                        out[i] = acc / channels
                    }
                    return out to rate
                }
                pos = body + size + (size and 1)
            }
            error("no data chunk")
        }
    }

    fun header(dataBytes: Int, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataBytes); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(channels.toShort())
        bb.putInt(sampleRate); bb.putInt(sampleRate * channels * 2); bb.putShort((channels * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataBytes)
        return bb.array()
    }
}

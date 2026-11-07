package app.glagol.wear

// ВНИМАНИЕ: этот файл — общий контракт часы ↔ телефон. Его копия лежит в
// app/src/main/java/app/glagol/watch/WatchProtocol.kt (отличается только package). Менять — в обоих местах.

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Протокол передачи записи с часов на телефон поверх двунаправленного потока
 * (канал Wearable Data Layer или, в отладке, TCP-сокет).
 *
 * Часы → телефон: заголовок, затем кадры AUDIO (PCM16LE mono) или ULAW (G.711 μ-law, 1 байт на сэмпл — вдвое меньше
 * трафика для живого потока по Bluetooth) и в конце END с общим числом сэмплов.
 * Телефон → часы (после END): ACK + sessionId — запись целиком получена и поставлена в расшифровку; NACK — не сошлось.
 * Всё big-endian ([DataOutputStream]), кроме самих сэмплов (little-endian, как в WAV).
 */
object WatchProtocol {
    const val MAGIC = 0x474C4757 // «GLGW»
    const val VERSION = 1
    const val SAMPLE_RATE = 16_000

    /** Путь канала Data Layer (телефон слушает pathPrefix «/glagol»). */
    const val CHANNEL_PATH = "/glagol/audio"
    /** Capability телефонного приложения (res/values/wear.xml). */
    const val PHONE_CAPABILITY = "glagol_phone"
    /** Отладочный мост эмуляторов: часы → 10.0.2.2:PORT → adb forward → телефон 127.0.0.1:PORT. */
    const val DEBUG_PORT = 7788

    const val MODE_LIVE: Byte = 0
    const val MODE_FILE: Byte = 1

    const val FRAME_AUDIO: Byte = 1
    const val FRAME_END: Byte = 2
    /** μ-law: int число сэмплов + по байту на сэмпл. [readFrame] сразу раскодирует в PCM16LE. */
    const val FRAME_ULAW: Byte = 3

    const val REPLY_NACK: Byte = 0
    const val REPLY_ACK: Byte = 1

    /**
     * Телефон → источник во время живого потока: «останови запись» (нажали «Стоп» на телефоне).
     * Часы останавливаются сами и этот байт не получают; его шлёт только путь с колонки (см. StationBridge),
     * где управление записью на телефоне. Источник в ответ досылает кадр END.
     */
    const val REQ_STOP: Byte = 0x10

    /** Защита от мусора в потоке: кадр больше 1 с звука — ошибка. */
    const val MAX_FRAME_BYTES = SAMPLE_RATE * 2

    data class Header(val sessionId: String, val sampleRate: Int, val startedAt: Long, val mode: Byte)

    sealed interface Frame {
        /** [pcm] — PCM16LE, [size] байт. */
        class Audio(val pcm: ByteArray, val size: Int) : Frame
        data class End(val totalSamples: Long) : Frame
    }

    fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        out.writeUTF(h.sessionId)
        out.writeInt(h.sampleRate)
        out.writeLong(h.startedAt)
        out.writeByte(h.mode.toInt())
    }

    fun readHeader(inp: DataInputStream): Header {
        if (inp.readInt() != MAGIC) throw IOException("не наш поток")
        val v = inp.readUnsignedByte()
        if (v != VERSION) throw IOException("версия протокола $v")
        return Header(inp.readUTF(), inp.readInt(), inp.readLong(), inp.readByte())
    }

    /** Кадр звука: [pcm] — little-endian PCM16, первые [size] байт. */
    fun writeAudio(out: DataOutputStream, pcm: ByteArray, size: Int = pcm.size) {
        out.writeByte(FRAME_AUDIO.toInt())
        out.writeInt(size)
        out.write(pcm, 0, size)
    }

    /** Кадр звука в μ-law: [pcm] — little-endian PCM16, первые [size] байт; на провод уходит size/2 байт. */
    fun writeUlaw(out: DataOutputStream, pcm: ByteArray, size: Int = pcm.size) {
        val n = size / 2
        val u = ByteArray(n)
        for (i in 0 until n) {
            val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            u[i] = ulawEncode(s)
        }
        out.writeByte(FRAME_ULAW.toInt())
        out.writeInt(n)
        out.write(u)
    }

    fun writeEnd(out: DataOutputStream, totalSamples: Long) {
        out.writeByte(FRAME_END.toInt())
        out.writeLong(totalSamples)
    }

    /** Следующий кадр; [buf] переиспользуется (растёт при необходимости). Обрыв потока — [java.io.EOFException]. */
    fun readFrame(inp: DataInputStream, buf: ByteArray): Frame = when (val t = inp.readByte()) {
        FRAME_AUDIO -> {
            val n = inp.readInt()
            if (n < 0 || n > MAX_FRAME_BYTES || n % 2 != 0) throw IOException("плохой кадр: $n байт")
            val b = if (buf.size >= n) buf else ByteArray(n)
            inp.readFully(b, 0, n)
            Frame.Audio(b, n)
        }
        FRAME_ULAW -> {
            val n = inp.readInt()
            if (n < 0 || n > MAX_FRAME_BYTES / 2) throw IOException("плохой кадр: $n сэмплов")
            val u = ByteArray(n)
            inp.readFully(u)
            val b = if (buf.size >= 2 * n) buf else ByteArray(2 * n)
            for (i in 0 until n) {
                val s = ULAW_DECODE[u[i].toInt() and 0xFF].toInt()
                b[2 * i] = s.toByte()
                b[2 * i + 1] = (s shr 8).toByte()
            }
            Frame.Audio(b, 2 * n)
        }
        FRAME_END -> Frame.End(inp.readLong())
        else -> throw IOException("неизвестный кадр $t")
    }

    // ---- G.711 μ-law ----
    private const val ULAW_BIAS = 0x84
    private const val ULAW_CLIP = 32635

    fun ulawEncode(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > ULAW_CLIP) s = ULAW_CLIP
        s += ULAW_BIAS
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
        val man = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or man).inv().toByte()
    }

    private val ULAW_DECODE = ShortArray(256) { b ->
        val u = b.inv() and 0xFF
        val exp = (u shr 4) and 7
        val s = (((u and 0x0F) shl 3) + ULAW_BIAS shl exp) - ULAW_BIAS
        (if (u and 0x80 != 0) -s else s).toShort()
    }

    fun ulawDecode(b: Byte): Short = ULAW_DECODE[b.toInt() and 0xFF]

    /** Телефон → источник: просьба остановить живую запись. */
    fun writeStopRequest(out: DataOutputStream) {
        out.writeByte(REQ_STOP.toInt())
        out.flush()
    }

    fun writeReply(out: DataOutputStream, ok: Boolean, sessionId: String) {
        out.writeByte((if (ok) REPLY_ACK else REPLY_NACK).toInt())
        out.writeUTF(sessionId)
        out.flush()
    }

    /** true — телефон подтвердил именно эту сессию. */
    fun readReply(inp: DataInputStream, sessionId: String): Boolean {
        val r = inp.readByte()
        val sid = inp.readUTF()
        return r == REPLY_ACK && sid == sessionId
    }
}

package app.glagol.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

class WatchProtocolTest {

    private fun stream(block: (DataOutputStream) -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(block)
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test fun roundtrip() {
        val pcm = ByteArray(3200) { (it % 251).toByte() }
        val h = WatchProtocol.Header("k3x9-ab", 16_000, 1_762_500_000_000, WatchProtocol.MODE_LIVE)
        val inp = stream { out ->
            WatchProtocol.writeHeader(out, h)
            WatchProtocol.writeAudio(out, pcm)
            WatchProtocol.writeAudio(out, pcm, 1600)
            WatchProtocol.writeEnd(out, 2400)
        }
        assertEquals(h, WatchProtocol.readHeader(inp))
        val buf = ByteArray(WatchProtocol.MAX_FRAME_BYTES)
        val a = WatchProtocol.readFrame(inp, buf) as WatchProtocol.Frame.Audio
        assertEquals(3200, a.size)
        assertArrayEquals(pcm, a.pcm.copyOf(a.size))
        val b = WatchProtocol.readFrame(inp, buf) as WatchProtocol.Frame.Audio
        assertEquals(1600, b.size)
        var samples = (a.size + b.size) / 2L
        assertEquals(WatchProtocol.Frame.End(2400), WatchProtocol.readFrame(inp, buf))
        assertEquals(2400L, samples)
    }

    @Test fun reply() {
        assertTrue(WatchProtocol.readReply(stream { WatchProtocol.writeReply(it, true, "s1") }, "s1"))
        assertFalse(WatchProtocol.readReply(stream { WatchProtocol.writeReply(it, false, "s1") }, "s1"))
        // ACK чужой сессии — не наш
        assertFalse(WatchProtocol.readReply(stream { WatchProtocol.writeReply(it, true, "s2") }, "s1"))
    }

    @Test fun stopRequestByte() {
        // «Стоп» с телефона — один байт REQ_STOP, который источник читает и в ответ шлёт END.
        val inp = stream { out -> WatchProtocol.writeStopRequest(out) }
        assertEquals(WatchProtocol.REQ_STOP, inp.readByte())
    }

    @Test(expected = EOFException::class) fun truncatedStreamFails() {
        val inp = stream { out ->
            WatchProtocol.writeHeader(out, WatchProtocol.Header("s", 16_000, 0, WatchProtocol.MODE_FILE))
            WatchProtocol.writeAudio(out, ByteArray(100))
        }
        WatchProtocol.readHeader(inp)
        val buf = ByteArray(WatchProtocol.MAX_FRAME_BYTES)
        WatchProtocol.readFrame(inp, buf)
        WatchProtocol.readFrame(inp, buf) // END так и не пришёл
    }

    @Test(expected = IOException::class) fun garbageRejected() {
        WatchProtocol.readHeader(stream { it.writeInt(0x12345678) })
    }

    @Test fun ulawFrameDecodesToPcmCloseToSource() {
        // синус 440 Гц разной громкости + края диапазона
        val n = 800
        val src = ShortArray(n) { i ->
            when (i) {
                0 -> Short.MAX_VALUE
                1 -> Short.MIN_VALUE
                2 -> 0
                else -> (Math.sin(2 * Math.PI * 440 * i / 16_000.0) * (200 + 30 * i)).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        val pcm = ByteArray(2 * n)
        for (i in 0 until n) { pcm[2 * i] = src[i].toByte(); pcm[2 * i + 1] = (src[i].toInt() shr 8).toByte() }
        val inp = stream { out ->
            WatchProtocol.writeUlaw(out, pcm)
            WatchProtocol.writeEnd(out, n.toLong())
        }
        val a = WatchProtocol.readFrame(inp, ByteArray(16)) as WatchProtocol.Frame.Audio
        assertEquals(2 * n, a.size)
        for (i in 0 until n) {
            val got = (a.pcm[2 * i].toInt() and 0xFF) or (a.pcm[2 * i + 1].toInt() shl 8)
            val want = src[i].toInt()
            // μ-law: ошибка не больше ~3 % от амплитуды (+ квант у нуля)
            assertTrue("i=$i want=$want got=$got", Math.abs(got - want) <= Math.abs(want) / 32 + 40)
        }
        assertEquals(WatchProtocol.Frame.End(n.toLong()), WatchProtocol.readFrame(inp, ByteArray(16)))
    }

    @Test fun ulawCodecIsStableOnItsOwnOutput() {
        for (b in 0..255) {
            val s = WatchProtocol.ulawDecode(b.toByte()).toInt()
            assertEquals(s, WatchProtocol.ulawDecode(WatchProtocol.ulawEncode(s)).toInt())
        }
    }
}

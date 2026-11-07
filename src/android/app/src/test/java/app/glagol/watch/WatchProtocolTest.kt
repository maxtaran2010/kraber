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
}

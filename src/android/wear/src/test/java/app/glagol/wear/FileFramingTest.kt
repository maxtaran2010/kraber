package app.glagol.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class FileFramingTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun pcm(n: Int): ByteArray {
        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) bb.putShort(((i * 37) % 65536 - 32768).toShort())
        return bb.array()
    }

    private fun wav(name: String, body: ByteArray, headerDataBytes: Long = body.size.toLong(), rate: Int = 16_000): File =
        tmp.newFile(name).apply { outputStream().use { it.write(WavFile.header(headerDataBytes, rate)); it.write(body) } }

    /** Прогоняет файл через writeFileStream и разбирает поток телефонным парсером. */
    private fun roundtrip(file: File, header: WatchProtocol.Header): Pair<ByteArray, Long> {
        val bytes = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val written = FileFraming.writeFileStream(file, DataOutputStream(bytes), header) { sent, total ->
            assertTrue(sent <= total)
            progress += sent
        }
        val inp = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(header, WatchProtocol.readHeader(inp))
        val audio = ByteArrayOutputStream()
        val buf = ByteArray(WatchProtocol.MAX_FRAME_BYTES)
        var frames = 0
        while (true) {
            when (val f = WatchProtocol.readFrame(inp, buf)) {
                is WatchProtocol.Frame.Audio -> {
                    assertTrue("кадр ${f.size} байт", f.size in 2..FileFraming.FRAME_SAMPLES * 2)
                    audio.write(f.pcm, 0, f.size)
                    frames++
                }
                is WatchProtocol.Frame.End -> {
                    assertEquals(written, f.totalSamples)
                    assertEquals(audio.size() / 2L, f.totalSamples)
                    break
                }
            }
        }
        assertEquals("после END ничего быть не должно", -1, inp.read())
        assertEquals(((written + FileFraming.FRAME_SAMPLES - 1) / FileFraming.FRAME_SAMPLES).toInt(), frames)
        assertEquals(frames, progress.size)
        if (written > 0) assertEquals(written, progress.last())
        return audio.toByteArray() to written
    }

    @Test
    fun fileRoundtripKeepsEverySample() {
        val n = 16_000 * 2 + 123 // последний кадр неполный
        val body = pcm(n)
        val header = WatchProtocol.Header("mh3k2j1a-x7f2", 16_000, 1_794_000_000_000L, WatchProtocol.MODE_FILE)
        val (audio, total) = roundtrip(wav("a.wav", body), header)
        assertEquals(n.toLong(), total)
        assertArrayEquals(body, audio)
    }

    @Test
    fun brokenHeaderFromCrashedRecordingUsesFileLength() {
        // Процесс умер во время записи: заголовок так и остался с длиной 0, плюс «полсэмпла» в хвосте
        val n = 5_000
        val body = pcm(n) + byteArrayOf(7)
        val header = WatchProtocol.Header("x", 16_000, 1L, WatchProtocol.MODE_FILE)
        val (audio, total) = roundtrip(wav("b.wav", body, headerDataBytes = 0), header)
        assertEquals(n.toLong(), total)
        assertArrayEquals(body.copyOf(n * 2), audio)
    }

    @Test
    fun foreignFormatIsRejectedBeforeWriting() {
        val f = wav("c.wav", pcm(100), rate = 44_100)
        val bytes = ByteArrayOutputStream()
        try {
            FileFraming.writeFileStream(f, DataOutputStream(bytes), WatchProtocol.Header("x", 16_000, 1L, WatchProtocol.MODE_FILE))
            fail("ожидали IOException")
        } catch (_: IOException) {
        }
        assertEquals(0, bytes.size())
    }

    @Test
    fun sessionIdCarriesStartTime() {
        val t = 1_794_123_456_789L
        val sid = SessionId.new(t, Random(1))
        assertEquals(t, SessionId.startedAt(sid))
        assertTrue(sid, sid.matches(Regex("[0-9a-z]+-[0-9a-z]{4}")))
        assertNull(SessionId.startedAt("-абв"))
    }
}

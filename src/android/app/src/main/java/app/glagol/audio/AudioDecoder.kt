package app.glagol.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class UnsupportedAudioException(message: String) : Exception(message)

/**
 * Любой аудиоформат, который умеет Android (mp3, m4a/aac, ogg/opus, flac, wav, amr, 3gp…)
 * -> mono float PCM 16 кГц.
 */
object AudioDecoder {

    suspend fun decodeFile(path: File, onProgress: (Float) -> Unit): FloatArray {
        // Быстрый путь для наших собственных WAV (читается целиком, поэтому только небольшие;
        // длинные WAV идут через MediaExtractor потоково)
        if (path.name.endsWith(".wav", ignoreCase = true) && path.length() < WAV_FAST_MAX_BYTES) {
            runCatching { WavIO.read(path) }.getOrNull()?.let { (pcm, rate) ->
                onProgress(1f)
                return Resampler.resample(pcm, rate, WavIO.SAMPLE_RATE)
            }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path.absolutePath)
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioException("Не удалось открыть файл: формат не поддерживается")
        }
        return decode(extractor, onProgress)
    }

    private suspend fun decode(extractor: MediaExtractor, onProgress: (Float) -> Unit): FloatArray {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw UnsupportedAudioException("В файле нет аудиодорожки")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = try {
            MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioException("Кодек $mime не поддерживается на этом устройстве")
        }

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        // Сразу моно 16 кГц: каждый кусок из декодера сводится в моно и ресемплируется на лету.
        // Раньше весь файл копился на исходной частоте (48 кГц: 52 мин = 600 МБ) → OutOfMemory на длинных записях
        val outRate = WavIO.SAMPLE_RATE
        val out = FloatBuilder(maxOf(1, (durationUs / 1_000_000.0 * outRate).toInt() + outRate))
        var stream: Resampler.Stream? = null
        var streamRate = 0
        var mono = FloatArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = -1f
        val t0 = android.os.SystemClock.elapsedRealtime()
        var sTmp = ShortArray(0)
        var fTmp = FloatArray(0)

        try {
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                // Заполняем все свободные входные буферы без ожидания…
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                // …и забираем готовый выход (короткое ожидание, пока кодек работает)
                val outIdx = codec.dequeueOutputBuffer(info, 2_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIdx >= 0 -> {
                        if (stream == null || streamRate != sampleRate) {
                            stream?.finish()
                            stream = Resampler.Stream(sampleRate, outRate) { src, n -> out.add(src, n) }
                            streamRate = sampleRate
                        }
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> {
                                val fb = buf.asFloatBuffer()
                                val n = fb.remaining()
                                if (fTmp.size < n) fTmp = FloatArray(n)
                                fb.get(fTmp, 0, n)
                                if (mono.size < n / channels) mono = FloatArray(n / channels)
                                stream.push(mono, downmix(fTmp, n, channels, mono))
                            }
                            else -> {
                                val sb = buf.asShortBuffer()
                                val n = sb.remaining()
                                if (sTmp.size < n) sTmp = ShortArray(n)
                                sb.get(sTmp, 0, n)
                                if (mono.size < n / channels) mono = FloatArray(n / channels)
                                stream.push(mono, downmix(sTmp, n, channels, mono))
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) * 0.85f
                            if (p - lastProgress > 0.01f) { onProgress(p); lastProgress = p }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        stream?.finish()
        val pcm = out.toArray()
        if (pcm.isEmpty()) throw UnsupportedAudioException("Не удалось декодировать аудио")
        android.util.Log.i("AudioDecoder", "$mime ${sampleRate}Hz x$channels → ${pcm.size / outRate} s mono 16k: decode+resample ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        onProgress(1f)
        return pcm
    }

    /** Чередующиеся каналы → моно (среднее). Возвращает число кадров. */
    private fun downmix(src: ShortArray, n: Int, channels: Int, dst: FloatArray): Int {
        val frames = n / channels
        val k = 1f / (32768f * channels)
        var j = 0
        for (i in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += src[j++]
            dst[i] = acc * k
        }
        return frames
    }

    private fun downmix(src: FloatArray, n: Int, channels: Int, dst: FloatArray): Int {
        val frames = n / channels
        val k = 1f / channels
        var j = 0
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += src[j++]
            dst[i] = acc * k
        }
        return frames
    }

    private const val WAV_FAST_MAX_BYTES = 120L shl 20

    /** Убираем постоянную составляющую и мягко поднимаем громкость тихих записей. */
    fun normalize(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        var mean = 0.0
        for (v in x) mean += v
        mean /= x.size
        var peak = 0f
        var sq = 0.0
        for (i in x.indices) {
            val v = (x[i] - mean).toFloat()
            x[i] = v
            val a = abs(v)
            if (a > peak) peak = a
            sq += v * v
        }
        val rms = sqrt(sq / x.size).toFloat()
        if (peak < 1e-4f) return x
        // Цель — пик 0.9, но не усиливаем больше чем в 10 раз и не делаем RMS выше 0.2
        var gain = 0.9f / peak
        if (rms > 0f) gain = minOf(gain, 0.2f / rms)
        gain = gain.coerceIn(1f, 10f)
        if (gain > 1.05f) for (i in x.indices) x[i] = (x[i] * gain).coerceIn(-1f, 1f)
        return x
    }

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    fun probeDurationMs(context: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    private class FloatBuilder(initial: Int) {
        private var data = FloatArray(initial)
        private var size = 0
        fun add(src: FloatArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size + data.size / 4, size + n))
            System.arraycopy(src, 0, data, size, n)
            size += n
        }
        fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }
}

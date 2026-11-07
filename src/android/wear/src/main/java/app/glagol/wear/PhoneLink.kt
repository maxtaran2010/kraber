package app.glagol.wear

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Связь с телефоном: канал Wearable Data Layer (Bluetooth/Wi-Fi через Google Play Services, без интернета).
 * Только в debug-сборке — запасной TCP-мост эмуляторов: часы → 10.0.2.2:[WatchProtocol.DEBUG_PORT] → adb forward → телефон.
 */
class PhoneLink(context: Context) {

    private val ctx = context.applicationContext
    private val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Открытый двунаправленный поток к телефону. [close] — идемпотентный, из любого потока; будит зависшие чтение/запись. */
    class Link(val input: InputStream, val output: OutputStream, val close: () -> Unit)

    /** Открывает поток к телефону; null — телефона нет (ждём не дольше ~5 с). */
    suspend fun open(): Link? {
        val dl = withTimeoutOrNull(DATA_LAYER_TIMEOUT_MS) { openDataLayer() }
        if (dl != null) return dl
        if (!debuggable) return null
        return openDebugSocket(DEBUG_CONNECT_TIMEOUT_MS)
    }

    /** Есть ли телефон с Крабером в досягаемости (для строки статуса). */
    suspend fun phoneNearby(): Boolean {
        val node = withTimeoutOrNull(DATA_LAYER_TIMEOUT_MS) { findPhone() }
        if (node != null) return true
        if (!debuggable) return false
        // Отладка: быстрый connect к мосту эмуляторов и сразу закрыть
        val probe = openDebugSocket(DEBUG_PROBE_TIMEOUT_MS) ?: return false
        probe.close()
        return true
    }

    /** Узел-телефон, объявивший [WatchProtocol.PHONE_CAPABILITY]; ближний (по Bluetooth) — в приоритете. */
    private suspend fun findPhone(): Node? = try {
        val info = Wearable.getCapabilityClient(ctx)
            .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
        info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Нет Google Play Services / Wearable API на часах — живём без Data Layer
        Log.w(TAG, "Data Layer недоступен: $e")
        null
    }

    private suspend fun openDataLayer(): Link? {
        val node = findPhone() ?: return null
        val client = Wearable.getChannelClient(ctx)
        val channel = try {
            client.openChannel(node.id, WatchProtocol.CHANNEL_PATH).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "канал к ${node.displayName} не открылся: $e")
            return null
        }
        try {
            val output = client.getOutputStream(channel).await()
            val input = client.getInputStream(channel).await()
            Log.i(TAG, "канал к ${node.displayName} открыт")
            return Link(input, output, once {
                runCatching { client.close(channel) }
                runCatching { output.close() }
                runCatching { input.close() }
            })
        } catch (e: Throwable) {
            runCatching { client.close(channel) }
            if (e is CancellationException) throw e
            Log.w(TAG, "потоки канала не открылись: $e")
            return null
        }
    }

    private suspend fun openDebugSocket(timeoutMs: Int): Link? = withContext(Dispatchers.IO) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(EMULATOR_HOST, WatchProtocol.DEBUG_PORT), timeoutMs)
            s.tcpNoDelay = true
            Log.i(TAG, "отладочный мост $EMULATOR_HOST:${WatchProtocol.DEBUG_PORT} открыт")
            Link(s.getInputStream(), s.getOutputStream(), once { runCatching { s.close() } })
        } catch (e: IOException) {
            runCatching { s.close() }
            null
        } catch (e: SecurityException) { // release без INTERNET
            runCatching { s.close() }
            null
        }
    }

    private fun once(block: () -> Unit): () -> Unit {
        val done = AtomicBoolean(false)
        return { if (done.compareAndSet(false, true)) block() }
    }

    companion object {
        private const val TAG = "WatchLink"
        /** Хост эмулятора, видимый из гостя. */
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val DATA_LAYER_TIMEOUT_MS = 3_500L
        private const val DEBUG_CONNECT_TIMEOUT_MS = 1_500
        private const val DEBUG_PROBE_TIMEOUT_MS = 800
    }
}

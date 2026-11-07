package app.glagol.watch

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Только для отладки на эмуляторах (без пары по Bluetooth): часы-эмулятор стучатся на 10.0.2.2:7788 (это Mac),
 * `adb -s <телефон> forward tcp:7788 tcp:7788` переправляет соединение сюда. Протокол тот же, что и по Data Layer.
 * В release не запускается (и разрешения INTERNET там нет).
 */
object DebugBridge {
    fun start(hub: WatchHub) {
        thread(name = "watch-debug-bridge", isDaemon = true) {
            runCatching {
                ServerSocket(WatchProtocol.DEBUG_PORT, 4, InetAddress.getLoopbackAddress()).use { server ->
                    Log.i("WatchBridge", "listening on 127.0.0.1:${WatchProtocol.DEBUG_PORT}")
                    while (true) {
                        val s = server.accept()
                        s.tcpNoDelay = true
                        hub.accept(s.getInputStream(), s.getOutputStream()) { s.close() }
                    }
                }
            }.onFailure { Log.w("WatchBridge", "bridge stopped: $it") }
        }
    }
}

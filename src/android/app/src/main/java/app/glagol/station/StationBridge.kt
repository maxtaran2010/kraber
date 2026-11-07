package app.glagol.station

import android.util.Log
import app.glagol.watch.WatchHub
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Отладочный транспорт для внешнего микрофона (Яндекс Станция с Алисой) на эмуляторе и в локальной сети.
 * Телефон слушает TCP-порт, агент на колонке (tools/station/station_agent.py) подключается и шлёт звук тем же
 * протоколом [app.glagol.watch.WatchProtocol], что и часы. Приёмник — тот же [WatchHub] с источником
 * [WatchHub.Source.STATION].
 *
 * Только в debug-сборке: TCP требует разрешения INTERNET, которого в release нет (см. AndroidManifest). Боевой
 * транспорт — Bluetooth RFCOMM ([StationBluetooth]), он интернета не требует и сохраняет полностью офлайновую работу.
 *
 * Эмулятор: `adb -s <телефон> forward tcp:$PORT tcp:$PORT`, агент стучится на 10.0.2.2:$PORT.
 * Реальный телефон в той же сети: агент подключается на Wi-Fi-адрес телефона, порт $PORT.
 */
object StationBridge {
    const val PORT = 7799

    fun start(hub: WatchHub) {
        thread(name = "station-debug-bridge", isDaemon = true) {
            runCatching {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(PORT))
                    Log.i(TAG, "listening on 0.0.0.0:$PORT")
                    while (true) {
                        val s = server.accept()
                        s.tcpNoDelay = true
                        hub.accept(s.getInputStream(), s.getOutputStream(), WatchHub.Source.STATION) { s.close() }
                    }
                }
            }.onFailure { Log.w(TAG, "bridge stopped: $it") }
        }
    }

    private const val TAG = "StationBridge"
}

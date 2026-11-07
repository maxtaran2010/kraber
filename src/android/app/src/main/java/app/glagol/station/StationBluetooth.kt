package app.glagol.station

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import app.glagol.watch.WatchHub
import java.io.Closeable
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Боевой транспорт для внешнего микрофона (Яндекс Станция с Алисой): Bluetooth RFCOMM (профиль SPP).
 *
 * Телефон — клиент, колонка — сервер: агент на колонке (tools/station/station_agent.py в режиме rfcomm) поднимает
 * SPP-сервис на стандартном UUID, телефон к нему подключается и принимает звук тем же протоколом
 * [app.glagol.watch.WatchProtocol], что и часы. Bluetooth не требует разрешения INTERNET, поэтому приложение остаётся
 * полностью офлайновым (в release интернет-разрешение удалено).
 *
 * Пара с колонкой делается один раз в системных настройках Bluetooth. Для [connect] нужно разрешение
 * BLUETOOTH_CONNECT (на Android 12+ запрашивается в рантайме).
 *
 * База: подключение к уже выбранному устройству. Экран выбора колонки и кнопка «Стоп» (вызывает [WatchHub.requestStop])
 * — следующий шаг, см. tools/station/README.md.
 */
object StationBluetooth {
    /** Serial Port Profile — стандартный UUID, тот же поднимает агент на колонке. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Фиксированный RFCOMM-канал сервера на колонке (kraber_relay btserve). На колонке нет SDP-записи
     * (bluetoothd без --compat), поэтому подключаемся прямо к каналу, без поиска по UUID.
     */
    const val RFCOMM_CHANNEL = 22

    /**
     * Сокет к колонке. Сначала — напрямую к фиксированному каналу (insecure, без SDP; так работает наш
     * сервер), затем откат на обычный поиск по SPP-UUID — на случай, если колонка когда-нибудь отдаёт SDP.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        // 1) insecure фиксированный канал через reflection (createInsecureRfcommSocket(int))
        runCatching {
            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
            return m.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
        }
        // 2) secure фиксированный канал через reflection (createRfcommSocket(int))
        runCatching {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            return m.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
        }
        // 3) обычный путь по SDP-UUID
        return runCatching { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull()
            ?: runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID) }.getOrNull()
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Сопряжённые устройства, похожие на колонку с Алисой (по имени). Нужен BLUETOOTH_CONNECT. */
    @SuppressLint("MissingPermission")
    fun pairedStations(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().filter { d ->
                val n = (d.name ?: "").lowercase()
                HINTS.any { it in n }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Подключиться к колонке и завести поток в [hub] (источник [WatchHub.Source.STATION]). Возвращает [Closeable]
     * для разрыва; [onState] сообщает connecting/connected/failed для UI. Нужен BLUETOOTH_CONNECT.
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        hub: WatchHub,
        device: BluetoothDevice,
        onState: (State) -> Unit = {},
    ): Closeable {
        var socket: BluetoothSocket? = null
        val handle = Closeable { runCatching { socket?.close() } }
        if (!hasPermission(context)) {
            onState(State.FAILED); return handle
        }
        thread(name = "station-bt") {
            onState(State.CONNECTING)
            // discovery сильно замедляет и роняет connect — выключаем, если идёт
            runCatching {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                    ?.takeIf { it.isDiscovering }?.cancelDiscovery()
            }
            val s = openSocket(device)
            socket = s
            if (s == null) { onState(State.FAILED); return@thread }
            try {
                s.connect()
                onState(State.CONNECTED)
                hub.accept(s.inputStream, s.outputStream, WatchHub.Source.STATION) { runCatching { s.close() } }
            } catch (e: Exception) {
                Log.w(TAG, "connect ${device.address}: $e")
                runCatching { s.close() }
                onState(State.FAILED)
            }
        }
        return handle
    }

    enum class State { CONNECTING, CONNECTED, FAILED }

    private val HINTS = listOf("алиса", "alice", "яндекс", "yandex", "станция", "station")
    private const val TAG = "StationBluetooth"
}

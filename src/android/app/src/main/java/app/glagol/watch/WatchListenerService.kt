package app.glagol.watch

import android.util.Log
import app.glagol.GlagolApp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Часы открыли канал Wearable Data Layer (Bluetooth через Google Play Services, без интернета) — отдаём его [WatchHub].
 * Сервис поднимается системой, даже если Крабер на телефоне закрыт.
 */
class WatchListenerService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith("/glagol")) return
        val client = Wearable.getChannelClient(this)
        try {
            // Колбэк приходит в фоновом потоке сервиса — ждать задачи здесь можно
            val input = Tasks.await(client.getInputStream(channel))
            val output = Tasks.await(client.getOutputStream(channel))
            (application as GlagolApp).container.watch.accept(input, output) { client.close(channel) }
        } catch (e: Exception) {
            Log.w("WatchListener", "channel ${channel.path}: $e")
            client.close(channel)
        }
    }
}

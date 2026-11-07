package app.glagol.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground-сервис (тип microphone) на время записи: держит процесс и микрофон живыми,
 * когда экран гаснет или пользователь уходит на циферблат. Саму запись ведёт [RecordingController].
 */
class RecordService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Запись", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Крабер записывает")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Например, нет разрешения на микрофон — запись всё равно идёт, пока приложение на экране
            Log.w(TAG, "foreground не запустился: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "WatchRecService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, RecordService::class.java))

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordService::class.java))
        }
    }
}

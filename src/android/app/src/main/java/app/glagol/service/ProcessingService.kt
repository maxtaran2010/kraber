package app.glagol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.glagol.GlagolApp
import app.glagol.MainActivity
import app.glagol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Foreground-сервис: не даёт системе убить процесс, пока идёт расшифровка. */
class ProcessingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // ServiceCompat вырезает новые типы FGS (mediaProcessing, API 35) — вызываем напрямую
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, build(null), type)
        else startForeground(NOTIFICATION_ID, build(null))

        val manager = (application as GlagolApp).container.processing
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            combine(manager.state, manager.activeJobs) { s, n -> s to n }
                .distinctUntilChanged { a, b ->
                    a.second == b.second && a.first?.stage == b.first?.stage &&
                        ((a.first?.overall ?: 0f) * 100).roundToInt() == ((b.first?.overall ?: 0f) * 100).roundToInt()
                }
                .collect { (state, active) ->
                    if (active == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(state))
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun build(state: ProcessingState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state != null) {
            val pct = (state.overall * 100).roundToInt()
            b.setContentTitle("Расшифровка · $pct%")
                .setContentText(state.stage.title + " — на устройстве, без интернета")
                .setProgress(100, pct, false)
        } else {
            b.setContentTitle("Крабер")
                .setContentText("Составляю конспект на устройстве…")
                .setProgress(0, 0, true)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            // Из фона (запись пришла с часов, пока Крабер закрыт) Android 12+ не даёт поднять foreground-сервис —
            // расшифровка всё равно идёт, просто без уведомления
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java)) }
                .onFailure { android.util.Log.w("ProcessingService", "FGS не запущен: ${it.message}") }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Обработка записей", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}

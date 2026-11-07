package app.glagol

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import app.glagol.ui.nav.AppNav
import app.glagol.ui.theme.GlagolTheme

class MainActivity : ComponentActivity() {

    /** Аудио, присланное из другого приложения («Открыть в Крабере» / «Поделиться»). */
    private var pendingImport by mutableStateOf<Uri?>(null)

    /** Счётчик тапов по уведомлению «Часы записывают» — каждый открывает живой экран. */
    private var watchLiveRequest by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            GlagolTheme {
                AppNav(pendingImport = pendingImport, onPendingImportConsumed = { pendingImport = null }, watchLiveRequest = watchLiveRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WATCH_LIVE, false) == true) {
            watchLiveRequest++
            return
        }
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        // Отладка: `am start ... --es resummarize <id>` — пересчитать конспект
        intent?.getStringExtra("resummarize")?.takeIf { debuggable }?.let {
            (application as GlagolApp).container.processing.retrySummary(it)
            return
        }
        // Отладка живого режима: `am start ... --es live_wav /data/local/tmp/x.wav`, затем кнопка записи
        intent?.getStringExtra("live_wav")?.takeIf { debuggable }?.let {
            app.glagol.audio.AudioRecorder.debugSource = java.io.File(it)
            return
        }
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri == null) return
        // Отладка/автотесты: `am start ... --ez autostart true [--ei speakers N]` — без листа импорта
        if (debuggable && intent?.getBooleanExtra("autostart", false) == true) {
            val name = app.glagol.audio.AudioDecoder.displayName(this, uri)
            (application as GlagolApp).container.processing.startFromUri(uri, name, intent.getIntExtra("speakers", 0))
            return
        }
        pendingImport = uri
    }

    companion object {
        /** Из уведомления «Часы записывают»: открыть живой экран. */
        const val EXTRA_WATCH_LIVE = "watch_live"
    }
}

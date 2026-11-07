package app.glagol.wear

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.glagol.wear.ui.WatchApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val debuggable get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) RecordingController.start()
        else RecordingController.showMessage("Нужен доступ к микрофону")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordingController.attach(this)
        setContent { WatchApp(onRecord = ::record, onStop = RecordingController::stop) }
        // Пока идёт запись, экран не гаснет
        lifecycleScope.launch {
            RecordingController.state.map { it is UiState.Recording }.distinctUntilChanged().collect { recording ->
                if (recording) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (savedInstanceState == null) handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        RecordingController.setVisible(true)
    }

    override fun onPause() {
        RecordingController.setVisible(false)
        super.onPause()
    }

    /** Тап по кнопке записи: при первом разе спрашиваем микрофон (и уведомления на API 33+). */
    private fun record() {
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            RecordingController.start()
            return
        }
        val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            ask += Manifest.permission.POST_NOTIFICATIONS
        }
        askPermissions.launch(ask.toTypedArray())
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Только debug-сборка:
     * `--es live_wav <путь>` — следующая запись берёт звук из WAV (16 кГц mono PCM16) в реальном темпе;
     * `--ez autostart true` — сразу начать запись (без звука в эмуляторе: вместе с live_wav).
     */
    private fun handleDebugIntent(i: Intent?) {
        if (i == null || !debuggable) return
        i.getStringExtra(EXTRA_LIVE_WAV)?.let { path ->
            MicRecorder.debugSource = File(path)
            Log.i(TAG, "отладочный источник звука: $path")
        }
        val autostart = i.getBooleanExtra(EXTRA_AUTOSTART, false)
        i.removeExtra(EXTRA_LIVE_WAV)
        i.removeExtra(EXTRA_AUTOSTART)
        if (autostart) record()
    }

    private companion object {
        const val TAG = "WatchMain"
        const val EXTRA_LIVE_WAV = "live_wav"
        const val EXTRA_AUTOSTART = "autostart"
    }
}

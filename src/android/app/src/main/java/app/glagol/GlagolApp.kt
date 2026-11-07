package app.glagol

import android.app.Application
import android.content.Context
import app.glagol.data.RecordingsRepository
import app.glagol.ml.AsrEngine
import app.glagol.ml.LlmClient
import app.glagol.ml.SpeakerEmbedder
import app.glagol.ml.VadEngine
import app.glagol.ml.TranscriptionPipeline
import app.glagol.service.ProcessingManager
import app.glagol.watch.DebugBridge
import app.glagol.watch.WatchHub

/** Простейший DI: всё создаётся один раз на процесс. */
class AppContainer(context: Context) {
    val repository = RecordingsRepository(context)
    /** Общий ASR: и для пайплайна, и для живой расшифровки во время записи. */
    val asr = AsrEngine(context.assets)
    private val pipeline = TranscriptionPipeline(VadEngine(context.assets), asr, SpeakerEmbedder(context.assets))
    private val llm = LlmClient(context)
    val processing = ProcessingManager(context, repository, pipeline, llm)
    /** Приём записей с часов (Wear OS). */
    val watch = WatchHub(context, processing, asr)
}

class GlagolApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // В процессе :llm (LlmService) репозиторий и пайплайн не нужны — и не должны трогать файлы записей.
        if (getProcessName() == packageName) {
            container = AppContainer(this)
            // Эмуляторы без пары по Bluetooth: часы подключаются через adb forward (только debug-сборка)
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) DebugBridge.start(container.watch)
        }
    }
}

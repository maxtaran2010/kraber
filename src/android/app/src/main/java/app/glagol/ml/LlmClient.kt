package app.glagol.ml

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.TransactionTooLargeException
import android.util.Log
import androidx.core.content.edit
import app.glagol.data.QaItem
import app.glagol.data.Summary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Процесс LLM упал в нативном коде (например, неподдерживаемые инструкции CPU). */
class LlmCrashedException(val backend: LlmBackend) : Exception("LLM process died ($backend)")

/** Движок вернул ошибку (без падения процесса). */
class LlmBackendException(val backend: LlmBackend, message: String?) : Exception(message)

/** Нет бэкенда, на котором LLM работает на этом устройстве. */
class LlmUnsupportedException : Exception("LLM is not supported on this device")

/**
 * Клиент к [LlmService] в процессе `:llm`: конспект ([summarize]) и вопросы ([ask]).
 * Если процесс падает на CPU — один раз пробуем GPU; запоминаем результат для этого устройства и версии.
 * Пока не вызван [release], процесс с загруженной моделью живёт — повторные вопросы идут без перезагрузки.
 */
class LlmClient(private val context: Context, private val settings: AppSettings) {

    private val mutex = Mutex()
    @Volatile private var conn: Conn? = null
    private var nextRequestId = 1
    private val main = Handler(Looper.getMainLooper())

    private val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)
    private val deviceKey: String by lazy {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        "crashed_${Build.FINGERPRINT.hashCode()}_$version"
    }

    private fun crashed(): Set<String> = prefs.getStringSet(deviceKey, emptySet()).orEmpty()

    /** Бэкенд, который ещё не падал на этом устройстве; null — LLM здесь недоступна. */
    fun availableBackend(): LlmBackend? = LlmBackend.entries.firstOrNull { it.name !in crashed() }

    private fun markCrashed(backend: LlmBackend) {
        prefs.edit { putStringSet(deviceKey, crashed() + backend.name) }
    }

    /** Конспект с автоматическим переходом CPU → GPU, если процесс LLM падает. */
    suspend fun summarize(lines: List<PromptLine>, onPartial: (String) -> Unit): SummaryResult {
        val linesJson = SummaryLogic.encodeLines(lines)
        var used: LlmModel? = null
        val done = withFallback(onPartial) { backend, model ->
            used = model
            call(LlmService.MSG_SUMMARIZE, backend, model, onPartial) {
                putLines(linesJson)
            }
        }
        return SummaryResult(
            done.getString(LlmService.KEY_TITLE),
            SummaryLogic.decodeSummary(done.getString(LlmService.KEY_DIGEST)),
            used?.title,
        )
    }

    /** Ответ на вопрос по расшифровке; [history] — последние вопросы и ответы (для уточняющих вопросов). */
    suspend fun ask(
        lines: List<PromptLine>,
        digest: Summary?,
        history: List<QaItem>,
        question: String,
        onPartial: (String) -> Unit,
    ): String {
        val linesJson = SummaryLogic.encodeLines(lines)
        val done = withFallback(onPartial) { backend, model ->
            call(LlmService.MSG_ASK, backend, model, onPartial) {
                putLines(linesJson)
                putString(LlmService.KEY_DIGEST, SummaryLogic.encodeSummary(digest))
                putString(LlmService.KEY_HISTORY, SummaryLogic.encodeQa(history))
                putString(LlmService.KEY_QUESTION, question)
            }
        }
        return done.getString(LlmService.KEY_TEXT).orEmpty()
    }

    /**
     * Общая схема для всех запросов: один запрос к LLM за раз (mutex), при падении процесса на CPU — GPU.
     * Бэкенд, на котором процесс упал, запоминаем для этого устройства и версии приложения.
     * Если 1.7B дважды убили по памяти (4 ГБ ОЗУ) — повторяем на 0.6B, и «Авто» дальше выбирает её.
     */
    private suspend fun <T> withFallback(onPartial: (String) -> Unit, block: suspend (LlmBackend, LlmModel) -> T): T {
        var deaths = 0
        var model = settings.llmModel()
        while (true) {
            val backend = availableBackend() ?: throw LlmUnsupportedException()
            mutex.withLock {
                val started = System.currentTimeMillis()
                try {
                    return block(backend, model)
                } catch (e: LlmCrashedException) {
                    disconnect()
                    onPartial("")
                    deaths++
                    when {
                        // Настоящее падение в нативном коде (SIGILL на неподдерживаемых инструкциях и т. п.) —
                        // этот бэкенд здесь не работает, запоминаем и пробуем следующий
                        nativeCrash(started) -> {
                            Log.w(TAG, "LLM process crashed natively on $backend, trying next backend")
                            markCrashed(backend)
                            deaths = 0
                        }
                        // Процесс убили по памяти и т. п. — один повтор на том же бэкенде
                        deaths == 1 -> Log.w(TAG, "LLM process died on $backend (not a native crash), retrying")
                        // Большой модели не хватило памяти — та же работа на лёгкой
                        model == LlmModel.FULL -> {
                            Log.w(TAG, "${model.title} died twice (memory?), falling back to ${LlmModel.LITE.title}")
                            settings.markFullTooHeavy()
                            model = LlmModel.LITE
                            deaths = 0
                        }
                        // Старый Android не сообщает причину — два падения подряд считаем «не работает»
                        Build.VERSION.SDK_INT < 30 -> { markCrashed(backend); deaths = 0 }
                        else -> throw java.io.IOException("LLM process died twice on $backend")
                    }
                } catch (e: LlmBackendException) {
                    // GPU — только запасной путь: если он не завёлся (нет OpenCL и т.п.), больше его не пробуем.
                    if (e.backend != LlmBackend.GPU) throw e
                    Log.w(TAG, "GPU backend failed: ${e.message}")
                    markCrashed(backend)
                    onPartial("")
                }
            }
        }
    }

    /**
     * Процесс `:llm` упал в нативном коде после [since] (Android 11+: причина завершения процесса).
     * Убийство по памяти, наш собственный killProcess и т. п. — не считаются.
     */
    private suspend fun nativeCrash(since: Long): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        // Запись о завершении появляется чуть позже уведомления о смерти binder — подождём до ~1.5 с
        repeat(6) {
            val reasons = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrNull().orEmpty()
            val r = reasons.firstOrNull { it.processName.endsWith(":llm") && it.timestamp >= since - 1000 }
            if (r != null) {
                Log.i(TAG, "LLM process exit: reason=${r.reason} status=${r.status} ${r.description.orEmpty()}")
                return r.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    r.reason == ApplicationExitInfo.REASON_SIGNALED && r.status in FATAL_SIGNALS
            }
            delay(250)
        }
        return false
    }

    /** Отправляет запрос [what] в процесс `:llm` и ждёт [LlmService.MSG_DONE]; возвращает его данные. */
    private suspend fun call(
        what: Int,
        backend: LlmBackend,
        model: LlmModel,
        onPartial: (String) -> Unit,
        fill: Bundle.() -> Unit,
    ): Bundle {
        val c = conn ?: Conn().also { c ->
            conn = c
            val ok = context.bindService(Intent(context, LlmService::class.java), c, Context.BIND_AUTO_CREATE)
            if (!ok) { conn = null; throw IllegalStateException("bindService failed") }
        }
        val service = try {
            withTimeout(30_000) { c.ready.await() }
        } catch (e: LlmCrashedException) {
            throw LlmCrashedException(backend)
        } catch (e: TimeoutCancellationException) {
            // Не путать с отменой корутины: это обычная ошибка (конспект → «Повторить»), не падение
            disconnect()
            throw java.io.IOException("LLM service did not connect in 30 s")
        }

        val id = nextRequestId++
        val result = CompletableDeferred<Bundle>()
        // После отмены/завершения запоздавшие сообщения игнорируем (иначе «Читаю часть 3 из 17…» вернётся в UI)
        val active = AtomicBoolean(true)
        val replyTo = Messenger(Handler(Looper.getMainLooper()) { m ->
            if (m.arg1 == id && active.get()) when (m.what) {
                LlmService.MSG_PARTIAL -> if (!result.isCompleted) onPartial(m.data.getString(LlmService.KEY_TEXT).orEmpty())
                LlmService.MSG_DONE -> result.complete(Bundle(m.data))
                LlmService.MSG_ERROR -> result.completeExceptionally(
                    LlmBackendException(backend, m.data.getString(LlmService.KEY_TEXT))
                )
            }
            true
        })
        c.onDeath = { result.completeExceptionally(LlmCrashedException(backend)) }
        try {
            service.send(Message.obtain(null, what, id, 0).apply {
                data = Bundle().apply {
                    putString(LlmService.KEY_BACKEND, backend.name)
                    putString(LlmService.KEY_MODEL, model.name)
                    fill()
                }
                this.replyTo = replyTo
            })
            return result.await()
        } catch (e: TransactionTooLargeException) {
            // Слишком большая расшифровка для binder — обычная ошибка, а не «LLM не поддерживается»
            throw java.io.IOException("transcript too large for IPC", e)
        } catch (e: RemoteException) {
            throw LlmCrashedException(backend)
        } catch (e: CancellationException) {
            // Конспект поставили на паузу (или экран ушёл) — просим процесс :llm бросить эту работу
            runCatching { service.send(Message.obtain(null, LlmService.MSG_CANCEL, id, 0)) }
            throw e
        } finally {
            active.set(false)
            c.onDeath = null
        }
    }

    /**
     * Расшифровка в запрос: небольшая — прямо в Bundle; большая (часовой созвон) — через файл в cache,
     * чтобы не упереться в лимит binder (~1 МБ на процесс).
     */
    private fun Bundle.putLines(json: String) {
        if (json.length <= INLINE_LINES_CHARS) { putString(LlmService.KEY_LINES, json); return }
        val f = runCatching {
            File(context.cacheDir, "llm_ipc").apply { mkdirs() }
                .let { File(it, "lines_${System.nanoTime()}.json") }
                .apply { writeText(json) }
        }.getOrNull()
        if (f != null) putString(LlmService.KEY_LINES_FILE, f.absolutePath) else putString(LlmService.KEY_LINES, json)
    }

    /** Отвязываемся — процесс `:llm` завершится и освободит память. */
    suspend fun release() = mutex.withLock { disconnect() }

    private fun disconnect() {
        val c = conn ?: return
        conn = null
        runCatching { context.unbindService(c) }
    }

    private inner class Conn : ServiceConnection {
        val ready = CompletableDeferred<Messenger>()
        @Volatile var onDeath: (() -> Unit)? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            ready.complete(Messenger(binder))
        }

        /** Процесс :llm умер. Отвязываемся, чтобы система не перезапускала его по кругу. */
        override fun onServiceDisconnected(name: ComponentName?) = died()
        override fun onBindingDied(name: ComponentName?) = died()
        override fun onNullBinding(name: ComponentName?) = died()

        private fun died() {
            Log.w(TAG, "LLM process died")
            ready.completeExceptionally(LlmCrashedException(LlmBackend.CPU))
            onDeath?.invoke()
            main.post { if (conn === this) disconnect() }
        }
    }

    private companion object {
        const val TAG = "LlmClient"
        /** SIGILL, SIGABRT, SIGBUS, SIGFPE, SIGSEGV — падение в нативном коде (не SIGKILL). */
        val FATAL_SIGNALS = setOf(4, 6, 7, 8, 11)
        /** Больше — через файл (≈ 2 байта на символ в Bundle; лимит binder — ~1 МБ на процесс). */
        const val INLINE_LINES_CHARS = 150_000
    }
}

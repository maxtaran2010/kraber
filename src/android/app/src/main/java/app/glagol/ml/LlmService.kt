package app.glagol.ml

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * LLM живёт в отдельном процессе `:llm`.
 *
 * Зачем: нативный движок (LiteRT-LM/XNNPACK) на некоторых CPU/эмуляторах может упасть
 * (например, SIGILL на SME-инструкциях). Падение изолированного процесса не роняет приложение —
 * расшифровка уже сохранена, а конспект просто помечается недоступным.
 * Плюс после работы процесс завершается и отдаёт системе всю память модели.
 *
 * Протокол (Messenger): запрос [MSG_SUMMARIZE] или [MSG_ASK] с `arg1` = id запроса;
 * ответы [MSG_PARTIAL] (потоковый текст), затем [MSG_DONE] или [MSG_ERROR].
 * Расшифровка — JSON списка [PromptLine] в [KEY_LINES].
 */
class LlmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var summarizer: LlmSummarizer

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })

    override fun onCreate() {
        super.onCreate()
        summarizer = LlmSummarizer(applicationContext)
        Log.i(TAG, "LLM process started, pid=${Process.myPid()}")
    }

    /** Идущие запросы: id → задача (для [MSG_CANCEL]). */
    private val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    private fun handle(msg: Message) {
        if (msg.what == MSG_CANCEL) {
            // Клиент больше не ждёт (конспект на паузе) — бросаем генерацию, mutex освободится для следующего запроса
            jobs.remove(msg.arg1)?.let { Log.i(TAG, "cancel request ${msg.arg1}"); it.cancel() }
            return
        }
        if (msg.what != MSG_SUMMARIZE && msg.what != MSG_ASK) return
        val reply = msg.replyTo ?: return
        val requestId = msg.arg1
        val what = msg.what
        // Message переиспользуется после handle() — забираем всё из Bundle сразу
        val data = msg.data
        val backend = runCatching { LlmBackend.valueOf(data.getString(KEY_BACKEND).orEmpty()) }
            .getOrDefault(LlmBackend.CPU)
        val linesJson = data.getString(KEY_LINES)
        val linesFile = data.getString(KEY_LINES_FILE)
        val digestJson = data.getString(KEY_DIGEST)
        val historyJson = data.getString(KEY_HISTORY)
        val question = data.getString(KEY_QUESTION).orEmpty()
        val job = scope.launch {
            try {
                // Большая расшифровка приходит файлом (см. LlmClient.putLines)
                val json = linesJson ?: linesFile?.let { f -> java.io.File(f).let { val t = it.readText(); it.delete(); t } }
                val lines = SummaryLogic.decodeLines(json)
                val partial: (String) -> Unit = { p -> send(reply, MSG_PARTIAL, requestId) { putString(KEY_TEXT, p) } }
                if (what == MSG_SUMMARIZE) {
                    val res = summarizer.summarize(lines, backend, partial)
                    send(reply, MSG_DONE, requestId) {
                        putString(KEY_TITLE, res.title)
                        putString(KEY_DIGEST, SummaryLogic.encodeSummary(res.digest))
                        putString(KEY_TEXT, res.text)
                    }
                } else {
                    val answer = summarizer.answer(
                        lines,
                        SummaryLogic.decodeSummary(digestJson),
                        SummaryLogic.decodeQa(historyJson),
                        question,
                        backend,
                        partial,
                    )
                    send(reply, MSG_DONE, requestId) { putString(KEY_TEXT, answer) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "request $requestId cancelled")
            } catch (e: Throwable) {
                Log.e(TAG, "request $what failed", e)
                send(reply, MSG_ERROR, requestId) { putString(KEY_TEXT, e.message ?: e.javaClass.simpleName) }
            }
        }
        jobs[requestId] = job
        job.invokeOnCompletion { jobs.remove(requestId, job) }
    }

    private fun send(to: Messenger, what: Int, requestId: Int, fill: Bundle.() -> Unit) {
        runCatching { to.send(Message.obtain(null, what, requestId, 0).apply { data = Bundle().apply(fill) }) }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        // Клиенты отвязались — процесс больше не нужен, отдаём системе всю память модели разом.
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val TAG = "LlmService"
        const val MSG_SUMMARIZE = 1
        const val MSG_PARTIAL = 2
        const val MSG_DONE = 3
        const val MSG_ERROR = 4
        const val MSG_ASK = 5
        /** Отменить запрос `arg1` (клиент больше не ждёт ответа). */
        const val MSG_CANCEL = 6
        const val KEY_LINES = "lines"
        /** Путь к файлу с JSON строк — для длинных расшифровок вместо [KEY_LINES]. */
        const val KEY_LINES_FILE = "lines_file"
        const val KEY_BACKEND = "backend"
        const val KEY_TEXT = "text"
        const val KEY_TITLE = "title"
        const val KEY_DIGEST = "digest"
        const val KEY_QUESTION = "question"
        const val KEY_HISTORY = "history"
    }
}

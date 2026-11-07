package app.glagol.ml

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Модели локальной LLM, встроенные в APK (обе — Qwen3, int4, LiteRT-LM). */
enum class LlmModel(
    /** Путь в assets. */
    val asset: String,
    /** Имя копии во внутренней памяти (LiteRT-LM нужен файл, а не asset). */
    val fileName: String,
    val title: String,
) {
    /** Лучше пишет конспект; процесс LLM занимает ~1.9 ГБ ОЗУ. */
    FULL("models/llm/model.litertlm", "model.litertlm", "Qwen3 1.7B"),

    /** Для устройств с 4 ГБ ОЗУ: процесс LLM ~0.8 ГБ, генерация в 2–3 раза быстрее. */
    LITE("models/llm_lite/model.litertlm", "model_lite.litertlm", "Qwen3 0.6B"),
}

/** Что выбрано в настройках. [AUTO] — по объёму памяти устройства. */
enum class LlmChoice { AUTO, FULL, LITE }

/** Выбор модели: чистая логика (покрыта тестами). */
object LlmModelPolicy {
    /**
     * Меньше этого объёма ОЗУ — 0.6B. Устройства «4 ГБ» сообщают ~3.3–3.8 ГиБ (часть занимает ядро),
     * «6 ГБ» — от ~5.2 ГиБ. Сам процесс 1.7B — ~1.9 ГБ, на 4 ГБ рядом с распознаванием его убивает система.
     */
    const val FULL_MIN_RAM_BYTES = 9L shl 29 // 4.5 ГиБ

    /** [totalRam] = 0 — неизвестно (тогда полная модель). [fullTooHeavy] — 1.7B уже убивали по памяти. */
    fun auto(totalRam: Long, lowRamDevice: Boolean, fullTooHeavy: Boolean = false): LlmModel =
        if (lowRamDevice || fullTooHeavy || totalRam in 1 until FULL_MIN_RAM_BYTES) LlmModel.LITE else LlmModel.FULL

    fun resolve(choice: LlmChoice, auto: LlmModel): LlmModel = when (choice) {
        LlmChoice.AUTO -> auto
        LlmChoice.FULL -> LlmModel.FULL
        LlmChoice.LITE -> LlmModel.LITE
    }
}

/** Настройки приложения (SharedPreferences «settings»). Живут в основном процессе. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val am = context.getSystemService(ActivityManager::class.java)

    /** Объём ОЗУ устройства, байт (0 — не удалось узнать). */
    val totalRam: Long = runCatching { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem }.getOrDefault(0L)
    val lowRamDevice: Boolean = runCatching { am.isLowRamDevice }.getOrDefault(false)

    private val _llmChoice = MutableStateFlow(
        runCatching { LlmChoice.valueOf(prefs.getString(KEY_LLM, null).orEmpty()) }.getOrDefault(LlmChoice.AUTO)
    )
    val llmChoice: StateFlow<LlmChoice> = _llmChoice.asStateFlow()

    private val _fullTooHeavy = MutableStateFlow(prefs.getBoolean(KEY_FULL_TOO_HEAVY, false))
    /** 1.7B на этом устройстве дважды убивали по памяти — «Авто» дальше берёт 0.6B. */
    val fullTooHeavy: StateFlow<Boolean> = _fullTooHeavy.asStateFlow()

    fun setLlmChoice(choice: LlmChoice) {
        prefs.edit { putString(KEY_LLM, choice.name) }
        _llmChoice.value = choice
    }

    fun markFullTooHeavy() {
        if (_fullTooHeavy.value) return
        prefs.edit { putBoolean(KEY_FULL_TOO_HEAVY, true) }
        _fullTooHeavy.value = true
    }

    /** Модель для «Авто» на этом устройстве. */
    val autoModel: LlmModel get() = LlmModelPolicy.auto(totalRam, lowRamDevice, _fullTooHeavy.value)

    /** Модель для следующего запроса к LLM. */
    fun llmModel(): LlmModel = LlmModelPolicy.resolve(_llmChoice.value, autoModel)

    private companion object {
        const val KEY_LLM = "llm_model"
        const val KEY_FULL_TOO_HEAVY = "llm_full_too_heavy"
    }
}

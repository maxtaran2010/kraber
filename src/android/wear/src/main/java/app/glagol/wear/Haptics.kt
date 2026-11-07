package app.glagol.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Вибро-отклик: запись пошла / остановлена / телефон всё получил. */
internal class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Короткий толчок. */
    fun start() = play(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))

    /** Двойной. */
    fun stop() = play(VibrationEffect.createWaveform(longArrayOf(0, 45, 90, 45), -1))

    /** «Успех»: нарастающая тройка. */
    fun success() = play(
        VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30, 70, 140), intArrayOf(0, 90, 0, 160, 0, 255), -1),
    )

    private fun play(effect: VibrationEffect) {
        val v = vibrator ?: return
        runCatching { if (v.hasVibrator()) v.vibrate(effect) }
    }
}

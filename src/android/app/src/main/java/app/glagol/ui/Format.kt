package app.glagol.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val ru = Locale.forLanguageTag("ru")
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", ru)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", ru)

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

fun speakersLabel(n: Int) = plural(n, "спикер", "спикера", "спикеров")

fun dayHeader(epochMs: Long): String {
    val d = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> if (d.year == today.year) dayFmt.format(d) else dayYearFmt.format(d)
    }
}

fun timeOfDay(epochMs: Long): String =
    timeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** 12_400 мс -> "12 с", 95_000 -> "1 мин 35 с" */
fun humanDuration(ms: Long): String {
    val s = (ms / 1000.0).roundToInt()
    return when {
        s < 60 -> "$s с"
        else -> "${s / 60} мин" + if (s % 60 != 0) " ${s % 60} с" else ""
    }
}

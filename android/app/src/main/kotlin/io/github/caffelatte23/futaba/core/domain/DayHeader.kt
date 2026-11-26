package io.github.caffelatte23.futaba.core.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val sameYearFormat = DateTimeFormatter.ofPattern("M月d日（EEE）", Locale.JAPANESE)
private val differentYearFormat = DateTimeFormatter.ofPattern("uuuu年M月d日（EEE）", Locale.JAPANESE)

fun dayHeader(
    date: LocalDate,
    today: LocalDate,
): String {
    val prefix =
        when {
            date == today -> "今日 · "
            date.toEpochDay() == today.toEpochDay() - 1 -> "昨日 · "
            else -> ""
        }
    val format = if (date.year == today.year) sameYearFormat else differentYearFormat
    return prefix + date.format(format)
}

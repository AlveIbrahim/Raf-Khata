package com.rafkhata.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.rafkhata.core.TimeFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

object Fmt {
    fun instant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    }

    fun localDate(iso: String?): LocalDate? = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun dateTime(iso: String?, locale: Locale): String = instant(iso)?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
        .orEmpty()

    fun dateTime(epochMillis: Long, locale: Locale): String = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

    fun date(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))

    fun time(hhmm: String, locale: Locale): String = runCatching {
        LocalTime.parse(hhmm).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    }.getOrDefault(hhmm)

    /** Backend weekday (0 = Monday) to a localized name. */
    fun weekday(weekday: Int, locale: Locale, style: TextStyle = TextStyle.FULL): String =
        DayOfWeek.of(weekday + 1).getDisplayName(style, locale)

    fun duration(seconds: Double): String = TimeFormat.duration(seconds)

    /** Weekdays in Bangladeshi order, Saturday first. */
    val WEEK_ORDER = listOf(5, 6, 0, 1, 2, 3, 4)
}

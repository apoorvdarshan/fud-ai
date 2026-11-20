package com.apoorvdarshan.calorietracker.ui.util

import android.content.Context
import android.text.format.DateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The hour:minute pattern for the device's clock setting — "H:mm" (24-hour) or "h:mm a" (12-hour).
 * Honors Settings → System → Date & time → Use 24-hour format, so a phone set to 24-hour shows
 * "20:30" instead of "8:30 PM". Build a [java.time.format.DateTimeFormatter] from this at the call
 * site (adding `.withZone(...)` when formatting an Instant).
 */
fun clockTimePattern(context: Context): String =
    if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"

/**
 * Locale-aware date pattern for an ICU skeleton, so a fixed US layout such as "Sep 15, 2026"
 * becomes "15. 9. 2026" in Czech or "15 sept. 2026" in French. Returns null when the skeleton
 * cannot be resolved (for example under unit tests), so callers can fall back.
 */
fun localizedDatePattern(skeleton: String, locale: Locale = Locale.getDefault()): String? =
    runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()?.takeIf { it.isNotBlank() }

private fun localizedDateFormatter(skeleton: String, fallback: FormatStyle, locale: Locale): DateTimeFormatter {
    val pattern = localizedDatePattern(skeleton, locale)
        ?: return DateTimeFormatter.ofLocalizedDate(fallback).withLocale(locale)
    return runCatching { DateTimeFormatter.ofPattern(pattern, locale) }
        .getOrElse { DateTimeFormatter.ofLocalizedDate(fallback).withLocale(locale) }
}

/** Abbreviated month, day and year — "Sep 15, 2026" (en-US), "15. 9. 2026" (cs). Skeleton `yMMMd`. */
fun mediumDateFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    localizedDateFormatter("yMMMd", FormatStyle.MEDIUM, locale)

/** Abbreviated month and day without year — "Sep 15" (en-US), "15. 9." (cs). Skeleton `MMMd`. */
fun monthDayFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    localizedDateFormatter("MMMd", FormatStyle.SHORT, locale)

/** Abbreviated month and year — "Sep 2026" (en-US), "9/2026" (cs). Skeleton `yMMM`. */
fun monthYearFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    localizedDateFormatter("yMMM", FormatStyle.MEDIUM, locale)

/** Full weekday, month and day — "Saturday, Oct 24" (en-US), "sobota 24. 10." (cs). Skeleton `EEEEMMMd`. */
fun weekdayMonthDayFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    localizedDateFormatter("EEEEMMMd", FormatStyle.FULL, locale)

/** Short weekday, month and day — "Sat, Oct 24" (en-US), "so 24. 10." (cs). Skeleton `EEEMMMd`. */
fun shortWeekdayMonthDayFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    localizedDateFormatter("EEEMMMd", FormatStyle.MEDIUM, locale)

/** Short weekday with the full date — "Sat, Oct 24, 2026" (en-US), "so 24. 10. 2026" (cs). Skeleton `yMMMEd`. */
fun shortWeekdayMediumDateFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    localizedDateFormatter("yMMMEd", FormatStyle.MEDIUM, locale)

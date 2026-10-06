package com.rafkhata.core

import java.util.Locale

object TimeFormat {
    private const val BANGLA_DIGITS = "০১২৩৪৫৬৭৮৯"

    /** 75.4 -> "01:15", 3725 -> "1:02:05". */
    fun duration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun toBanglaDigits(text: String): String = buildString(text.length) {
        for (ch in text) append(if (ch in '0'..'9') BANGLA_DIGITS[ch - '0'] else ch)
    }

    /** "09:05" -> "9:05 AM" style 12-hour clock. */
    fun clock12(hhmm: String): String {
        val minutes = Slot.minutesOf(hhmm)
        val h = minutes / 60
        val m = minutes % 60
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return String.format(Locale.ROOT, "%d:%02d %s", h12, m, suffix)
    }
}

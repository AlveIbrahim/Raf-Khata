package com.rafkhata.core

/**
 * A weekly class slot. [weekday] uses the backend convention: 0 = Monday ... 6 = Sunday.
 * Times are local "HH:MM".
 */
data class Slot(
    val courseId: String,
    val weekday: Int,
    val start: String,
    val end: String,
    val room: String? = null,
) {
    val startMinutes: Int get() = minutesOf(start)
    val endMinutes: Int get() = minutesOf(end)

    companion object {
        fun minutesOf(hhmm: String): Int {
            val (h, m) = hhmm.split(":").map { it.trim().toInt() }
            return h * 60 + m
        }
    }
}

object Routine {
    /** Classes on [weekday] (0 = Monday), in time order. */
    fun classesOn(slots: List<Slot>, weekday: Int): List<Slot> =
        slots.filter { it.weekday == weekday }.sortedBy { it.startMinutes }

    /**
     * The course to preselect when recording starts at [weekday] [minuteOfDay]: a class in progress,
     * one starting within [earlyMinutes], or one that ended less than [lateMinutes] ago. Null if none.
     */
    fun currentCourse(
        slots: List<Slot>,
        weekday: Int,
        minuteOfDay: Int,
        earlyMinutes: Int = 15,
        lateMinutes: Int = 10,
    ): Slot? {
        val today = classesOn(slots, weekday)
        today.firstOrNull { minuteOfDay in it.startMinutes until it.endMinutes }?.let { return it }
        today.filter { it.startMinutes > minuteOfDay && it.startMinutes - minuteOfDay <= earlyMinutes }
            .minByOrNull { it.startMinutes }?.let { return it }
        return today.filter { it.endMinutes <= minuteOfDay && minuteOfDay - it.endMinutes < lateMinutes }
            .maxByOrNull { it.endMinutes }
    }

    /** The next class from now, today or later in the week. */
    fun nextClass(slots: List<Slot>, weekday: Int, minuteOfDay: Int): Slot? {
        if (slots.isEmpty()) return null
        for (offset in 0..7) {
            val day = (weekday + offset) % 7
            val candidates = classesOn(slots, day).filter { offset > 0 || it.startMinutes > minuteOfDay }
            if (candidates.isNotEmpty()) return candidates.first()
        }
        return null
    }

    /** Convert java.time DayOfWeek.value (1 = Monday ... 7 = Sunday) to our 0-based weekday. */
    fun fromIsoDayOfWeek(isoValue: Int): Int = (isoValue - 1).mod(7)
}

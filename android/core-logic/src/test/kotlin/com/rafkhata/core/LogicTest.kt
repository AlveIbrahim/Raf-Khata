package com.rafkhata.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentsTest {
    @Test
    fun namesAndRotation() {
        assertEquals("0007.aac", Segments.fileName(7))
        assertEquals(12, Segments.indexOf("0012.aac"))
        assertNull(Segments.indexOf("12.aac"))
        assertFalse(Segments.shouldRotate(16000L * 299, 16000))
        assertTrue(Segments.shouldRotate(16000L * 300, 16000))
    }

    @Test
    fun fileNamesUseAsciiDigitsInABanglaLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("bn-BD"))
            assertEquals("0012.aac", Segments.fileName(12))
            assertEquals(12, Segments.indexOf(Segments.fileName(12)))
            assertEquals("01:15", TimeFormat.duration(75.0))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun clockCountsRecordedSamplesOnly() {
        val clock = RecordedClock(16000)
        repeat(160) { clock.add(1000) }
        assertEquals(10.0, clock.seconds, 1e-9)
    }
}

class RoutineTest {
    private val os = Slot("os", weekday = 6, start = "09:00", end = "10:15") // Sunday
    private val dsa = Slot("dsa", weekday = 6, start = "10:30", end = "11:45")
    private val math = Slot("math", weekday = 1, start = "14:00", end = "15:00") // Tuesday
    private val slots = listOf(dsa, math, os)

    @Test
    fun picksTheClassInProgressOrAboutToStart() {
        assertEquals("os", Routine.currentCourse(slots, 6, Slot.minutesOf("09:40"))?.courseId)
        assertEquals("dsa", Routine.currentCourse(slots, 6, Slot.minutesOf("10:20"))?.courseId)
        // Just after the end of OS, before the DSA window opens.
        assertEquals("os", Routine.currentCourse(slots, 6, Slot.minutesOf("10:16"), earlyMinutes = 5)?.courseId)
        assertNull(Routine.currentCourse(slots, 6, Slot.minutesOf("13:00")))
        assertNull(Routine.currentCourse(slots, 0, Slot.minutesOf("09:30")))
    }

    @Test
    fun nextClassWrapsAroundTheWeek() {
        assertEquals("dsa", Routine.nextClass(slots, 6, Slot.minutesOf("10:00"))?.courseId)
        assertEquals("math", Routine.nextClass(slots, 6, Slot.minutesOf("12:00"))?.courseId)
        assertEquals("os", Routine.nextClass(slots, 2, Slot.minutesOf("08:00"))?.courseId)
        assertNull(Routine.nextClass(emptyList(), 0, 0))
    }

    @Test
    fun isoWeekdayConversion() {
        assertEquals(0, Routine.fromIsoDayOfWeek(1)) // Monday
        assertEquals(6, Routine.fromIsoDayOfWeek(7)) // Sunday
    }
}

class UploadPlanTest {
    @Test
    fun stepsInOrder() {
        var state = UploadState(false, mapOf(0 to false, 1 to false), mapOf("p1" to false), false, false)
        assertEquals(UploadStep.CreateLecture, UploadPlan.next(state))
        state = state.copy(createdOnServer = true)
        assertEquals(UploadStep.Segment(0), UploadPlan.next(state))
        state = state.copy(segments = mapOf(0 to true, 1 to false))
        assertEquals(UploadStep.Segment(1), UploadPlan.next(state))
        state = state.copy(segments = mapOf(0 to true, 1 to true))
        assertEquals(UploadStep.Photo("p1"), UploadPlan.next(state))
        state = state.copy(photos = mapOf("p1" to true))
        assertEquals(UploadStep.Bookmarks, UploadPlan.next(state))
        state = state.copy(bookmarksSynced = true)
        assertEquals(UploadStep.Finalize, UploadPlan.next(state))
        assertTrue(UploadPlan.progress(state) < 1f)
        state = state.copy(finalized = true)
        assertEquals(UploadStep.Done, UploadPlan.next(state))
        assertEquals(1f, UploadPlan.progress(state))
    }

    @Test
    fun detectsGaps() {
        assertFalse(UploadPlan.hasGaps(listOf(2, 0, 1)))
        assertTrue(UploadPlan.hasGaps(listOf(0, 2)))
    }
}

class AudioLevelsTest {
    @Test
    fun levels() {
        assertEquals(AudioLevels.SILENCE_DB, AudioLevels.rmsDb(ShortArray(100)))
        val full = ShortArray(100) { if (it % 2 == 0) 32767 else -32767 }
        assertEquals(0.0, AudioLevels.rmsDb(full), 0.01)
        assertEquals(1.0, AudioLevels.clippedFraction(full), 1e-9)
        assertEquals(0f, AudioLevels.meterFraction(-80.0))
        assertEquals(1f, AudioLevels.meterFraction(0.0))
    }

    @Test
    fun soundCheckTips() {
        val classroom = List(100) { if (it % 3 == 0) -25.0 else -55.0 }
        assertTrue(SoundChecker.evaluate(classroom, 0.0).good)
        val farAway = List(100) { -60.0 + (it % 5) }
        assertTrue(SoundTip.TOO_QUIET in SoundChecker.evaluate(farAway, 0.0).tips)
        val fan = List(100) { if (it % 3 == 0) -30.0 else -35.0 }
        assertTrue(SoundTip.NOISY in SoundChecker.evaluate(fan, 0.0).tips)
        assertTrue(SoundTip.CLIPPING in SoundChecker.evaluate(classroom, 0.05).tips)
    }
}

class TimeFormatTest {
    @Test
    fun formats() {
        assertEquals("01:15", TimeFormat.duration(75.4))
        assertEquals("1:02:05", TimeFormat.duration(3725.0))
        assertEquals("০৯:০৫", TimeFormat.toBanglaDigits("09:05"))
        assertEquals("9:05 AM", TimeFormat.clock12("09:05"))
        assertEquals("12:00 PM", TimeFormat.clock12("12:00"))
        assertEquals("1:30 PM", TimeFormat.clock12("13:30"))
        assertEquals("12:10 AM", TimeFormat.clock12("00:10"))
    }
}

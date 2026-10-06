package com.rafkhata.app

import com.rafkhata.app.data.api.CourseDto
import com.rafkhata.app.data.api.LectureDto
import com.rafkhata.app.data.api.LectureUpsertIn
import com.rafkhata.app.data.api.parseMissingSegments
import com.rafkhata.app.notify.ReminderScheduler
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppLogicTest {
    // Same settings as AppContainer.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    @Test
    fun decodesBackendLecture() {
        val body = """
            {"id":"6f1c","course_id":null,"course_title":"Operating Systems","space_id":null,
             "recorded_by":"u1","recorded_by_name":"Rafi","is_mine":true,"title":"",
             "started_at":"2026-10-04T03:00:00Z","duration_s":3120.5,"status":"processing",
             "status_detail":"transcribe","progress":15,"error":null,"notes_lang":"mixed",
             "quality_score":null,"notes_langs":[],"has_study":false,
             "created_at":"2026-10-04T04:30:00Z","updated_at":"2026-10-04T04:31:00Z","processed_at":null,
             "added_later":"ignored"}
        """.trimIndent()
        val lecture = json.decodeFromString(LectureDto.serializer(), body)
        assertEquals("transcribe", lecture.statusDetail)
        assertEquals(15, lecture.progress)
        assertEquals("mixed", lecture.notesLang)
        assertNull(lecture.courseId)
    }

    @Test
    fun decodesCourseWithRoutine() {
        val body = """
            {"id":"c1","title":"DSA","code":"CSE 2201","teacher_name":null,"semester":null,"space_id":null,
             "space_name":null,"glossary":["stack","queue"],"notes_lang":null,"archived":false,
             "routine":[{"weekday":6,"start_time":"09:00","end_time":"10:15","room":"402"}],
             "is_owner":true,"can_edit":true,"consent_confirmed":false,"lecture_count":3,
             "created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z"}
        """.trimIndent()
        val course = json.decodeFromString(CourseDto.serializer(), body)
        assertEquals(6, course.routine.single().weekday)
        assertEquals("10:15", course.routine.single().endTime)
        assertEquals(listOf("stack", "queue"), course.glossary)
    }

    @Test
    fun omitsNullFieldsWhenEncoding() {
        val encoded = json.encodeToString(LectureUpsertIn.serializer(), LectureUpsertIn(title = "Deadlock", consentConfirmed = true))
        assertFalse("course_id" in encoded)
        assertTrue("\"consent_confirmed\":true" in encoded)
    }

    @Test
    fun readsMissingSegmentsFromFinalizeConflict() {
        assertEquals(listOf(1, 3), parseMissingSegments("""{"detail":{"message":"segments missing","missing":[1,3]}}"""))
        assertEquals(emptyList<Int>(), parseMissingSegments("""{"detail":"lecture is already queued"}"""))
        assertEquals(emptyList<Int>(), parseMissingSegments(null))
    }

    @Test
    fun remindsTheEveningBeforeOrThatMorning() {
        val zone = ZoneId.of("Asia/Dhaka")
        val tuesdayNoon = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, zone)

        val (evening, onTheDay) = ReminderScheduler.reminderTime(LocalDate.of(2026, 10, 8), tuesdayNoon)!!
        assertEquals(ZonedDateTime.of(2026, 10, 7, 20, 0, 0, 0, zone), evening)
        assertFalse(onTheDay)

        val lateNight = ZonedDateTime.of(2026, 10, 7, 23, 0, 0, 0, zone)
        val (morning, today) = ReminderScheduler.reminderTime(LocalDate.of(2026, 10, 8), lateNight)!!
        assertEquals(ZonedDateTime.of(2026, 10, 8, 7, 30, 0, 0, zone), morning)
        assertTrue(today)

        assertNull(ReminderScheduler.reminderTime(LocalDate.of(2026, 10, 6), tuesdayNoon))
    }
}

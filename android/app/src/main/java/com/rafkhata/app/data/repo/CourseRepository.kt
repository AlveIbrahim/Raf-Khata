package com.rafkhata.app.data.repo

import com.rafkhata.app.data.api.CourseDto
import com.rafkhata.app.data.api.CourseIn
import com.rafkhata.app.data.api.RafKhataApi
import com.rafkhata.app.data.api.RoutineIn
import com.rafkhata.core.Slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import retrofit2.HttpException

class CourseRepository(private val api: RafKhataApi, private val cache: CacheStore) {
    private val _courses = MutableStateFlow<List<CourseDto>?>(null)

    /** Last known course list (null until loaded once). */
    val courses: StateFlow<List<CourseDto>?> = _courses.asStateFlow()

    suspend fun cached(): List<CourseDto>? {
        _courses.value?.let { return it }
        return cache.get(KEY, SERIALIZER)?.also { _courses.value = it }
    }

    suspend fun refresh(): List<CourseDto> = api.courses().also { save(it) }

    suspend fun get(id: String): CourseDto = api.course(id).also { upsertLocal(it) }

    fun local(id: String): CourseDto? = _courses.value?.firstOrNull { it.id == id }

    suspend fun create(input: CourseIn): CourseDto = api.createCourse(input).also { upsertLocal(it) }

    suspend fun update(id: String, input: CourseIn): CourseDto {
        // Explicit nulls clear a field; that is how a course moves from a section back to "only me".
        val body = buildJsonObject {
            put("title", input.title)
            put("code", input.code)
            put("teacher_name", input.teacherName)
            put("semester", input.semester)
            put("space_id", input.spaceId)
            put("notes_lang", input.notesLang)
            putJsonArray("glossary") { input.glossary.forEach { add(it) } }
        }
        api.updateCourse(id, body)
        return api.putRoutine(id, RoutineIn(input.routine)).also { upsertLocal(it) }
    }

    suspend fun setArchived(id: String, archived: Boolean): CourseDto =
        api.updateCourse(id, buildJsonObject { put("archived", archived) }).also { upsertLocal(it) }

    suspend fun delete(id: String) {
        val response = api.deleteCourse(id)
        if (!response.isSuccessful) throw HttpException(response)
        _courses.update { list -> list?.filterNot { it.id == id } }
        _courses.value?.let { cache.put(KEY, SERIALIZER, it) }
    }

    suspend fun confirmConsent(id: String): CourseDto = api.confirmConsent(id).also { upsertLocal(it) }

    /** Weekly slots of active courses, for picking the course when recording starts. */
    fun slots(courses: List<CourseDto>): List<Slot> = courses.filterNot { it.archived }.flatMap { course ->
        course.routine.map { Slot(course.id, it.weekday, it.startTime, it.endTime, it.room) }
    }

    fun clearLocal() {
        _courses.value = null
    }

    private suspend fun save(list: List<CourseDto>) {
        _courses.value = list
        cache.put(KEY, SERIALIZER, list)
    }

    private suspend fun upsertLocal(course: CourseDto) {
        val list = _courses.value ?: cache.get(KEY, SERIALIZER) ?: emptyList()
        val updated = if (list.any { it.id == course.id }) list.map { if (it.id == course.id) course else it } else list + course
        save(updated)
    }

    private companion object {
        const val KEY = "courses"
        val SERIALIZER = ListSerializer(CourseDto.serializer())
    }
}

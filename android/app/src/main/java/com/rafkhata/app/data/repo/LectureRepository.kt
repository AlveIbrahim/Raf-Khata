package com.rafkhata.app.data.repo

import com.rafkhata.app.data.api.DeadlineDto
import com.rafkhata.app.data.api.FeedbackIn
import com.rafkhata.app.data.api.LectureDto
import com.rafkhata.app.data.api.NotesDto
import com.rafkhata.app.data.api.RafKhataApi
import com.rafkhata.app.data.api.RegenerateIn
import com.rafkhata.app.data.api.SearchHitDto
import com.rafkhata.app.data.api.SegmentEditIn
import com.rafkhata.app.data.api.StudyDto
import com.rafkhata.app.data.api.TranscriptDto
import com.rafkhata.app.data.api.TranscriptSegmentDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** Generated content can still be on its way: the server answers 202 until it is ready. */
sealed interface Generated<out T> {
    data class Ready<T>(val value: T) : Generated<T>

    data object Pending : Generated<Nothing>

    /** Not generated in this language yet; it can be requested. */
    data object Missing : Generated<Nothing>
}

class LectureRepository(private val api: RafKhataApi, private val cache: CacheStore, private val json: Json) {
    // ----- lectures -----

    suspend fun cachedList(courseId: String? = null): List<LectureDto>? = cache.get(listKey(courseId), LECTURES)

    suspend fun list(courseId: String? = null, limit: Int = 50): List<LectureDto> =
        api.lectures(courseId, limit).also { cache.put(listKey(courseId), LECTURES, it) }

    suspend fun cachedLecture(id: String): LectureDto? = cache.get("lecture:$id", LectureDto.serializer())

    suspend fun get(id: String): LectureDto = api.lecture(id).also { cache.put("lecture:$id", LectureDto.serializer(), it) }

    suspend fun retry(id: String): LectureDto = api.retryLecture(id).also { cache.put("lecture:$id", LectureDto.serializer(), it) }

    suspend fun delete(id: String) {
        val response = api.deleteLecture(id)
        if (!response.isSuccessful) throw HttpException(response)
        listOf("lecture:", "transcript:").forEach { cache.remove(it + id) }
        LANGS.forEach { cache.remove("notes:$id:$it"); cache.remove("study:$id:$it") }
    }

    suspend fun audioUrl(id: String): String = api.audioUrl(id).url

    // ----- content -----

    suspend fun cachedTranscript(id: String): TranscriptDto? = cache.get("transcript:$id", TranscriptDto.serializer())

    suspend fun transcript(id: String): TranscriptDto =
        api.transcript(id).also { cache.put("transcript:$id", TranscriptDto.serializer(), it) }

    suspend fun editSegment(id: String, segmentId: String, text: String): TranscriptSegmentDto {
        val updated = api.editSegment(id, segmentId, SegmentEditIn(text))
        cachedTranscript(id)?.let { t ->
            val segments = t.segments.map { if (it.id == segmentId) updated else it }
            cache.put("transcript:$id", TranscriptDto.serializer(), t.copy(segments = segments))
        }
        return updated
    }

    suspend fun cachedNotes(id: String, lang: String): NotesDto? = cache.get("notes:$id:$lang", NotesDto.serializer())

    suspend fun notes(id: String, lang: String): Generated<NotesDto> =
        generated(api.notes(id, lang), NotesDto.serializer())
            .also { if (it is Generated.Ready) cache.put("notes:$id:$lang", NotesDto.serializer(), it.value) }

    suspend fun regenerateNotes(id: String, lang: String) {
        api.regenerateNotes(id, RegenerateIn(lang))
    }

    suspend fun cachedStudy(id: String, lang: String): StudyDto? = cache.get("study:$id:$lang", StudyDto.serializer())

    suspend fun study(id: String, lang: String): Generated<StudyDto> =
        generated(api.study(id, lang), StudyDto.serializer())
            .also { if (it is Generated.Ready) cache.put("study:$id:$lang", StudyDto.serializer(), it.value) }

    suspend fun feedback(id: String, body: FeedbackIn) {
        val response = api.feedback(id, body)
        if (!response.isSuccessful) throw HttpException(response)
    }

    // ----- deadlines and search -----

    suspend fun cachedDeadlines(): List<DeadlineDto>? = cache.get("deadlines", DEADLINES)

    suspend fun deadlines(): List<DeadlineDto> = api.deadlines().also { cache.put("deadlines", DEADLINES, it) }

    suspend fun search(query: String): List<SearchHitDto> = api.search(query)

    private fun <T> generated(response: Response<ResponseBody>, serializer: KSerializer<T>): Generated<T> =
        when (response.code()) {
            200 -> Generated.Ready(json.decodeFromString(serializer, response.body()?.string().orEmpty()))
            202 -> Generated.Pending
            404 -> Generated.Missing
            else -> throw HttpException(response)
        }

    private fun listKey(courseId: String?) = if (courseId == null) "lectures" else "lectures:$courseId"

    private companion object {
        val LANGS = listOf("bn", "en", "mixed")
        val LECTURES = ListSerializer(LectureDto.serializer())
        val DEADLINES = ListSerializer(DeadlineDto.serializer())
    }
}

package com.rafkhata.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors backend/src/rafkhata/api/schemas.py. Unknown fields are ignored, so the backend can add
// fields without breaking older app versions.

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val university: String? = null,
    val department: String? = null,
    val batch: String? = null,
    val section: String? = null,
    @SerialName("notes_lang") val notesLang: String = "bn",
    val onboarded: Boolean = false,
)

@Serializable
data class UserPatch(
    val name: String? = null,
    val university: String? = null,
    val department: String? = null,
    val batch: String? = null,
    val section: String? = null,
    @SerialName("notes_lang") val notesLang: String? = null,
    val onboarded: Boolean? = null,
)

@Serializable
data class GoogleLoginIn(@SerialName("id_token") val idToken: String)

@Serializable
data class DevLoginIn(val email: String, val name: String = "")

@Serializable
data class RefreshIn(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class TokenOut(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    val user: UserDto,
)

// ---------- sections ----------

@Serializable
data class SpaceIn(
    val name: String,
    val university: String? = null,
    @SerialName("section_label") val sectionLabel: String? = null,
)

@Serializable
data class SpaceJoinIn(@SerialName("invite_code") val inviteCode: String)

@Serializable
data class SpaceMemberDto(
    @SerialName("user_id") val userId: String,
    val name: String,
    val role: String,
)

@Serializable
data class SpaceDto(
    val id: String,
    val name: String,
    val university: String? = null,
    @SerialName("section_label") val sectionLabel: String? = null,
    @SerialName("invite_code") val inviteCode: String,
    val role: String,
    @SerialName("member_count") val memberCount: Int = 0,
    val members: List<SpaceMemberDto> = emptyList(),
)

@Serializable
data class RoleIn(val role: String)

// ---------- courses ----------

@Serializable
data class RoutineSlotDto(
    val weekday: Int,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val room: String? = null,
)

@Serializable
data class CourseIn(
    val title: String,
    val code: String? = null,
    @SerialName("teacher_name") val teacherName: String? = null,
    val semester: String? = null,
    @SerialName("space_id") val spaceId: String? = null,
    val glossary: List<String> = emptyList(),
    @SerialName("notes_lang") val notesLang: String? = null,
    val routine: List<RoutineSlotDto> = emptyList(),
)

@Serializable
data class RoutineIn(val slots: List<RoutineSlotDto>)

@Serializable
data class CourseDto(
    val id: String,
    val title: String,
    val code: String? = null,
    @SerialName("teacher_name") val teacherName: String? = null,
    val semester: String? = null,
    @SerialName("space_id") val spaceId: String? = null,
    @SerialName("space_name") val spaceName: String? = null,
    val glossary: List<String> = emptyList(),
    @SerialName("notes_lang") val notesLang: String? = null,
    val archived: Boolean = false,
    val routine: List<RoutineSlotDto> = emptyList(),
    @SerialName("is_owner") val isOwner: Boolean = true,
    @SerialName("can_edit") val canEdit: Boolean = true,
    @SerialName("consent_confirmed") val consentConfirmed: Boolean = false,
    @SerialName("lecture_count") val lectureCount: Int = 0,
)

// ---------- lectures ----------

@Serializable
data class LectureUpsertIn(
    @SerialName("course_id") val courseId: String? = null,
    val title: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("consent_confirmed") val consentConfirmed: Boolean = false,
    @SerialName("notes_lang") val notesLang: String? = null,
)

@Serializable
data class LectureDto(
    val id: String,
    @SerialName("course_id") val courseId: String? = null,
    @SerialName("course_title") val courseTitle: String? = null,
    @SerialName("space_id") val spaceId: String? = null,
    @SerialName("recorded_by_name") val recordedByName: String = "",
    @SerialName("is_mine") val isMine: Boolean = true,
    val title: String = "",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("duration_s") val durationS: Double = 0.0,
    val status: String,
    @SerialName("status_detail") val statusDetail: String? = null,
    val progress: Int = 0,
    val error: String? = null,
    @SerialName("notes_lang") val notesLang: String = "bn",
    @SerialName("quality_score") val qualityScore: Double? = null,
    @SerialName("notes_langs") val notesLangs: List<String> = emptyList(),
    @SerialName("has_study") val hasStudy: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class UploadUrlIn(
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("content_type") val contentType: String,
)

@Serializable
data class UploadUrlOut(
    val url: String,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class SegmentCompleteIn(
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("duration_s") val durationS: Double,
)

@Serializable
data class PhotoIn(
    @SerialName("photo_id") val photoId: String,
    @SerialName("t_offset_s") val tOffsetS: Double,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

@Serializable
data class BookmarkDto(
    @SerialName("t_offset_s") val tOffsetS: Double,
    val kind: String,
    val text: String? = null,
)

@Serializable
data class BookmarksIn(val bookmarks: List<BookmarkDto>)

@Serializable
data class FinalizeIn(
    @SerialName("segment_count") val segmentCount: Int,
    @SerialName("duration_s") val durationS: Double,
)

@Serializable
data class AudioUrlOut(val url: String, @SerialName("expires_in") val expiresIn: Int = 3600)

// ---------- content ----------

@Serializable
data class TranscriptSegmentDto(
    val id: String,
    val start: Double,
    val end: Double,
    val speaker: String? = null,
    val text: String,
)

@Serializable
data class PhotoDto(
    val id: String,
    @SerialName("t_offset_s") val tOffsetS: Double,
    val url: String? = null,
)

@Serializable
data class TranscriptDto(
    @SerialName("lecture_id") val lectureId: String,
    val version: Int = 1,
    val kind: String = "",
    @SerialName("can_edit") val canEdit: Boolean = false,
    val segments: List<TranscriptSegmentDto> = emptyList(),
    val bookmarks: List<BookmarkDto> = emptyList(),
    val photos: List<PhotoDto> = emptyList(),
)

@Serializable
data class SegmentEditIn(val text: String)

@Serializable
data class AnnouncementDto(
    val kind: String,
    val text: String,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_text") val dueText: String? = null,
)

@Serializable
data class ExamAlertDto(val text: String, @SerialName("teacher_quote") val teacherQuote: String = "")

/** Only the parts of the notes the app shows natively; everything else is in [NotesDto.html]. */
@Serializable
data class NotesContent(
    val title: String = "",
    val summary: List<String> = emptyList(),
    val announcements: List<AnnouncementDto> = emptyList(),
    @SerialName("exam_alerts") val examAlerts: List<ExamAlertDto> = emptyList(),
)

@Serializable
data class NotesDto(
    @SerialName("lecture_id") val lectureId: String,
    val lang: String,
    val version: Int = 1,
    val content: NotesContent = NotesContent(),
    val html: String = "",
    val markdown: String = "",
)

@Serializable
data class RegenerateIn(val lang: String)

@Serializable
data class PendingOut(val status: String = "pending", val lang: String = "")

@Serializable
data class FlashcardDto(val front: String, val back: String)

@Serializable
data class McqDto(
    val question: String,
    val options: List<String>,
    @SerialName("answer_index") val answerIndex: Int,
    val explanation: String = "",
)

@Serializable
data class WrittenQuestionDto(
    val kind: String,
    val question: String,
    @SerialName("answer_points") val answerPoints: List<String> = emptyList(),
)

@Serializable
data class StudyContent(
    val flashcards: List<FlashcardDto> = emptyList(),
    val mcqs: List<McqDto> = emptyList(),
    val questions: List<WrittenQuestionDto> = emptyList(),
)

@Serializable
data class StudyDto(
    @SerialName("lecture_id") val lectureId: String,
    val lang: String,
    val content: StudyContent = StudyContent(),
)

@Serializable
data class FeedbackIn(
    val kind: String,
    val rating: Int? = null,
    val comment: String? = null,
    val lang: String? = null,
)

// ---------- other ----------

@Serializable
data class DeadlineDto(
    val id: String,
    @SerialName("lecture_id") val lectureId: String,
    @SerialName("course_id") val courseId: String? = null,
    @SerialName("course_title") val courseTitle: String? = null,
    val kind: String,
    val title: String,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("due_text") val dueText: String? = null,
)

@Serializable
data class SearchHitDto(
    @SerialName("lecture_id") val lectureId: String,
    @SerialName("lecture_title") val lectureTitle: String = "",
    @SerialName("course_title") val courseTitle: String? = null,
    val source: String,
    val snippet: String,
    @SerialName("segment_id") val segmentId: String? = null,
    @SerialName("t_start") val tStart: Double? = null,
)

@Serializable
data class DeviceIn(
    @SerialName("fcm_token") val fcmToken: String,
    val platform: String = "android",
    @SerialName("app_version") val appVersion: String? = null,
    val locale: String? = null,
)

@Serializable
data class ErrorBody(val detail: kotlinx.serialization.json.JsonElement? = null)

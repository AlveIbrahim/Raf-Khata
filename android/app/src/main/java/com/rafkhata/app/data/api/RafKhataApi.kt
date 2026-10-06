package com.rafkhata.app.data.api

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** The Raf-Khata REST API (see docs/api.md). */
interface RafKhataApi {
    // auth
    @POST("auth/google")
    suspend fun googleLogin(@Body body: GoogleLoginIn): TokenOut

    @POST("auth/dev-login")
    suspend fun devLogin(@Body body: DevLoginIn): TokenOut

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshIn): Response<Unit>

    // me
    @GET("me")
    suspend fun me(): UserDto

    @PATCH("me")
    suspend fun updateMe(@Body body: UserPatch): UserDto

    @DELETE("me")
    suspend fun deleteMe(): Response<Unit>

    @Streaming
    @GET("me/export")
    suspend fun exportMe(): ResponseBody

    // sections
    @GET("spaces")
    suspend fun spaces(): List<SpaceDto>

    @POST("spaces")
    suspend fun createSpace(@Body body: SpaceIn): SpaceDto

    @POST("spaces/join")
    suspend fun joinSpace(@Body body: SpaceJoinIn): SpaceDto

    @GET("spaces/{id}")
    suspend fun space(@Path("id") id: String): SpaceDto

    @POST("spaces/{id}/invite-code")
    suspend fun rotateInviteCode(@Path("id") id: String): SpaceDto

    @PATCH("spaces/{id}/members/{userId}")
    suspend fun setMemberRole(@Path("id") id: String, @Path("userId") userId: String, @Body body: RoleIn): SpaceDto

    @DELETE("spaces/{id}/members/me")
    suspend fun leaveSpace(@Path("id") id: String): Response<Unit>

    // courses
    @GET("courses")
    suspend fun courses(): List<CourseDto>

    @POST("courses")
    suspend fun createCourse(@Body body: CourseIn): CourseDto

    @GET("courses/{id}")
    suspend fun course(@Path("id") id: String): CourseDto

    /** A JSON object rather than a data class so fields can be cleared with an explicit null. */
    @PATCH("courses/{id}")
    suspend fun updateCourse(@Path("id") id: String, @Body body: JsonObject): CourseDto

    @DELETE("courses/{id}")
    suspend fun deleteCourse(@Path("id") id: String): Response<Unit>

    @PUT("courses/{id}/routine")
    suspend fun putRoutine(@Path("id") id: String, @Body body: RoutineIn): CourseDto

    @POST("courses/{id}/consent")
    suspend fun confirmConsent(@Path("id") id: String): CourseDto

    // lecture upload
    @PUT("lectures/{id}")
    suspend fun upsertLecture(@Path("id") id: String, @Body body: LectureUpsertIn): LectureDto

    @POST("lectures/{id}/segments/{idx}/upload-url")
    suspend fun segmentUploadUrl(@Path("id") id: String, @Path("idx") idx: Int, @Body body: UploadUrlIn): UploadUrlOut

    @POST("lectures/{id}/segments/{idx}/complete")
    suspend fun segmentComplete(@Path("id") id: String, @Path("idx") idx: Int, @Body body: SegmentCompleteIn): Response<Unit>

    @POST("lectures/{id}/photos")
    suspend fun photoUploadUrl(@Path("id") id: String, @Body body: PhotoIn): UploadUrlOut

    @POST("lectures/{id}/photos/{photoId}/complete")
    suspend fun photoComplete(@Path("id") id: String, @Path("photoId") photoId: String): Response<Unit>

    @PUT("lectures/{id}/bookmarks")
    suspend fun putBookmarks(@Path("id") id: String, @Body body: BookmarksIn): List<BookmarkDto>

    @POST("lectures/{id}/finalize")
    suspend fun finalizeLecture(@Path("id") id: String, @Body body: FinalizeIn): LectureDto

    @POST("lectures/{id}/retry")
    suspend fun retryLecture(@Path("id") id: String): LectureDto

    // lectures
    @GET("lectures")
    suspend fun lectures(@Query("course_id") courseId: String?, @Query("limit") limit: Int): List<LectureDto>

    @GET("lectures/{id}")
    suspend fun lecture(@Path("id") id: String): LectureDto

    @GET("lectures/{id}/audio-url")
    suspend fun audioUrl(@Path("id") id: String): AudioUrlOut

    @DELETE("lectures/{id}")
    suspend fun deleteLecture(@Path("id") id: String): Response<Unit>

    // content
    @GET("lectures/{id}/transcript")
    suspend fun transcript(@Path("id") id: String): TranscriptDto

    @PATCH("lectures/{id}/transcript/segments/{segmentId}")
    suspend fun editSegment(
        @Path("id") id: String,
        @Path("segmentId") segmentId: String,
        @Body body: SegmentEditIn,
    ): TranscriptSegmentDto

    /** 200 with [NotesDto], or 202 with [PendingOut] while being generated. */
    @GET("lectures/{id}/notes")
    suspend fun notes(@Path("id") id: String, @Query("lang") lang: String?): Response<ResponseBody>

    @POST("lectures/{id}/notes/regenerate")
    suspend fun regenerateNotes(@Path("id") id: String, @Body body: RegenerateIn): PendingOut

    /** 200 with [StudyDto], or 202 while being generated. */
    @GET("lectures/{id}/study")
    suspend fun study(@Path("id") id: String, @Query("lang") lang: String?): Response<ResponseBody>

    @POST("lectures/{id}/feedback")
    suspend fun feedback(@Path("id") id: String, @Body body: FeedbackIn): Response<Unit>

    // other
    @GET("deadlines")
    suspend fun deadlines(): List<DeadlineDto>

    @GET("search")
    suspend fun search(@Query("q") query: String): List<SearchHitDto>

    @POST("devices")
    suspend fun registerDevice(@Body body: DeviceIn): Response<Unit>

    @DELETE("devices/{token}")
    suspend fun unregisterDevice(@Path("token") token: String): Response<Unit>
}

/** Used by the token authenticator, which runs synchronously inside OkHttp. */
interface AuthApi {
    @POST("auth/refresh")
    fun refresh(@Body body: RefreshIn): Call<TokenOut>
}

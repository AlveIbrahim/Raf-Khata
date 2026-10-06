package com.rafkhata.app.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.BookmarkDto
import com.rafkhata.app.data.api.BookmarksIn
import com.rafkhata.app.data.api.FinalizeIn
import com.rafkhata.app.data.api.LectureUpsertIn
import com.rafkhata.app.data.api.PhotoIn
import com.rafkhata.app.data.api.RafKhataApi
import com.rafkhata.app.data.api.SegmentCompleteIn
import com.rafkhata.app.data.api.UploadUrlIn
import com.rafkhata.app.data.api.UploadUrlOut
import com.rafkhata.app.data.api.isTransient
import com.rafkhata.app.data.api.parseMissingSegments
import com.rafkhata.app.data.api.serverDetail
import com.rafkhata.app.data.db.RecordingEntity
import com.rafkhata.app.data.db.RecordingState
import com.rafkhata.app.data.repo.RecordingRepository
import com.rafkhata.app.notify.Notifications
import com.rafkhata.core.UploadPlan
import com.rafkhata.core.UploadStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Uploads one recording: create the lecture, upload each 5-minute segment and photo through a
 * presigned URL, sync bookmarks, then finalize. Every step is idempotent, so a retry after a
 * dropped connection continues where it stopped.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.container
    private val repo: RecordingRepository get() = app.recordings
    private val api: RafKhataApi get() = app.api

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val recording = repo.get(id) ?: return Result.success()
        if (recording.state == RecordingState.RECORDING || recording.state == RecordingState.INTERRUPTED) {
            return Result.success() // not ready; queued again when the user decides
        }
        if (!app.auth.isSignedIn) {
            repo.setState(id, RecordingState.FAILED, applicationContext.getString(R.string.error_signed_out))
            return Result.failure()
        }
        repo.setState(id, RecordingState.UPLOADING)
        return try {
            upload(recording)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e.isTransient()) {
                repo.setState(id, RecordingState.QUEUED)
                Result.retry()
            } else {
                val detail = (e as? HttpException)?.serverDetail() ?: e.message
                repo.setState(id, RecordingState.FAILED, detail)
                Notifications.uploadFailed(applicationContext, recording.courseTitle ?: recording.title)
                Result.failure()
            }
        }
    }

    private suspend fun upload(recording: RecordingEntity) {
        val id = recording.id
        var finalizeAttempts = 0
        while (true) {
            val state = repo.uploadState(id) ?: return // deleted meanwhile
            repo.setProgress(id, UploadPlan.progress(state))
            when (val step = UploadPlan.next(state)) {
                UploadStep.CreateLecture -> createLecture(recording)
                is UploadStep.Segment -> uploadSegment(id, step.index)
                is UploadStep.Photo -> uploadPhoto(id, step.id)
                UploadStep.Bookmarks -> {
                    val bookmarks = repo.bookmarks(id).map { BookmarkDto(it.tOffsetS, it.kind, it.text) }
                    api.putBookmarks(id, BookmarksIn(bookmarks))
                    repo.markBookmarksSynced(id)
                }
                UploadStep.Finalize -> {
                    check(++finalizeAttempts <= 3) { "the server keeps reporting missing segments" }
                    finalize(id)
                }
                UploadStep.Done -> {
                    repo.delete(id) // the server has everything; free the phone's storage
                    return
                }
            }
        }
    }

    private suspend fun createLecture(recording: RecordingEntity) {
        val body = LectureUpsertIn(
            courseId = recording.courseId,
            title = recording.title,
            startedAt = Instant.ofEpochMilli(recording.startedAt).toString(),
            consentConfirmed = recording.consentConfirmed,
            notesLang = recording.notesLang,
        )
        val lecture = try {
            api.upsertLecture(recording.id, body)
        } catch (e: HttpException) {
            // The course was deleted or the user left its section: keep the lecture without a course.
            if (recording.courseId != null && (e.code() == 403 || e.code() == 404)) {
                api.upsertLecture(recording.id, body.copy(courseId = null))
            } else {
                throw e
            }
        }
        repo.markCreated(recording.id)
        if (lecture.status !in EDITABLE_STATES) repo.markFinalized(recording.id) // done in an earlier run
    }

    private suspend fun uploadSegment(id: String, index: Int) {
        val segment = repo.segments(id).first { it.idx == index }
        val file = repo.segmentFile(segment)
        check(file.exists()) { "recording file ${file.name} is missing" }
        val size = file.length()
        val target = api.segmentUploadUrl(id, index, UploadUrlIn(size, AUDIO_TYPE))
        putFile(target, file, AUDIO_TYPE)
        api.segmentComplete(id, index, SegmentCompleteIn(size, segment.durationS))
        repo.markSegmentUploaded(id, index)
    }

    private suspend fun uploadPhoto(id: String, photoId: String) {
        val found = repo.photo(id, photoId)
        if (found == null || !found.second.exists()) {
            repo.dropPhoto(photoId)
            return
        }
        val (photo, file) = found
        val size = file.length()
        val target = try {
            api.photoUploadUrl(id, PhotoIn(photo.id, photo.tOffsetS, photo.contentType, size))
        } catch (e: HttpException) {
            if (e.code() == 413 || e.code() == 422) {
                repo.dropPhoto(photoId) // a photo the server won't take must not block the lecture
                return
            }
            throw e
        }
        putFile(target, file, photo.contentType)
        api.photoComplete(id, photo.id)
        repo.markPhotoUploaded(photo.id)
    }

    private suspend fun finalize(id: String) {
        val segments = repo.segments(id)
        try {
            api.finalizeLecture(id, FinalizeIn(segments.size, segments.sumOf { it.durationS }))
            repo.markFinalized(id)
        } catch (e: HttpException) {
            val missing = if (e.code() == 409) parseMissingSegments(e.response()?.errorBody()?.string()) else emptyList()
            if (missing.isEmpty()) throw e
            repo.markSegmentsMissing(id, missing) // upload those again on the next loop
        }
    }

    private suspend fun putFile(target: UploadUrlOut, file: File, contentType: String) = withContext(Dispatchers.IO) {
        val headers = target.headers.filterKeys { !it.equals("Content-Type", ignoreCase = true) }
        val mediaType = (target.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value ?: contentType)
            .toMediaType()
        val request = Request.Builder()
            .url(target.url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .method(target.method.uppercase(), file.asRequestBody(mediaType))
            .build()
        uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("storage upload failed: HTTP ${response.code}")
        }
    }

    private val uploadClient: OkHttpClient get() = app.uploadClient

    companion object {
        const val KEY_RECORDING_ID = "recording_id"
        private const val AUDIO_TYPE = "audio/aac"
        private val EDITABLE_STATES = setOf("created", "uploading")
    }
}

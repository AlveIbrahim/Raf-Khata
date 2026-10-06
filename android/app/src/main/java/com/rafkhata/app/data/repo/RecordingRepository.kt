package com.rafkhata.app.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.room.withTransaction
import com.rafkhata.app.data.db.AppDatabase
import com.rafkhata.app.data.db.BookmarkEntity
import com.rafkhata.app.data.db.PhotoEntity
import com.rafkhata.app.data.db.RecordingEntity
import com.rafkhata.app.data.db.RecordingState
import com.rafkhata.app.data.db.SegmentEntity
import com.rafkhata.core.Adts
import com.rafkhata.core.Segments
import com.rafkhata.core.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.max

/**
 * Recordings that live on the phone until the server has them: audio segments, bookmarks and photos.
 * Files: `files/recordings/{id}/0000.aac ...` and `files/photos/{id}/{photoId}.jpg`.
 */
class RecordingRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.recordings()

    val all: Flow<List<RecordingEntity>> = dao.observeAll()

    fun audioDir(id: String) = File(context.filesDir, "recordings/$id")

    private fun photoDir(id: String) = File(context.filesDir, "photos/$id")

    suspend fun get(id: String): RecordingEntity? = dao.get(id)

    suspend fun create(
        ownerId: String,
        courseId: String?,
        courseTitle: String?,
        title: String,
        consentConfirmed: Boolean,
        notesLang: String?,
    ): RecordingEntity {
        val recording = RecordingEntity(
            id = UUID.randomUUID().toString(),
            ownerId = ownerId,
            courseId = courseId,
            courseTitle = courseTitle,
            title = title,
            startedAt = System.currentTimeMillis(),
            consentConfirmed = consentConfirmed,
            notesLang = notesLang,
        )
        withContext(Dispatchers.IO) { audioDir(recording.id).mkdirs() }
        dao.insert(recording)
        return recording
    }

    suspend fun onSegmentClosed(id: String, index: Int, file: File, durationS: Double) {
        dao.upsertSegment(SegmentEntity(id, index, file.name, file.length(), durationS))
    }

    suspend fun addBookmark(id: String, tOffsetS: Double, kind: String, text: String? = null) {
        dao.insertBookmark(BookmarkEntity(recordingId = id, tOffsetS = tOffsetS, kind = kind, text = text))
    }

    /** A file for the camera app to write a board photo into. */
    fun newPhotoFile(id: String): Pair<String, File> {
        val photoId = UUID.randomUUID().toString()
        val dir = photoDir(id).apply { mkdirs() }
        return photoId to File(dir, "$photoId.jpg")
    }

    suspend fun addPhoto(id: String, photoId: String, file: File, tOffsetS: Double) {
        if (!file.exists() || file.length() == 0L) return
        withContext(Dispatchers.IO) { shrinkPhoto(file) }
        dao.insertPhoto(PhotoEntity(id = photoId, recordingId = id, fileName = file.name, tOffsetS = tOffsetS))
    }

    /**
     * Called when recording stops: makes the segment list match the files on disk and queues the
     * recording for upload. Returns false (and deletes it) if nothing usable was recorded.
     */
    suspend fun finish(id: String): Boolean {
        val segments = rebuildSegments(id)
        if (segments.isEmpty()) {
            delete(id)
            return false
        }
        val recording = dao.get(id) ?: return false
        dao.update(recording.copy(durationS = segments.sumOf { it.durationS }, state = RecordingState.QUEUED, error = null))
        return true
    }

    /**
     * Recordings still marked as recording when the app starts were cut off by a crash or the
     * system. Their files are repaired and kept for the user to upload or delete.
     */
    suspend fun recoverInterrupted(): List<RecordingEntity> {
        val cut = dao.withStates(listOf(RecordingState.RECORDING))
        return cut.mapNotNull { recording ->
            val segments = rebuildSegments(recording.id)
            if (segments.isEmpty()) {
                delete(recording.id)
                null
            } else {
                recording.copy(durationS = segments.sumOf { it.durationS }, state = RecordingState.INTERRUPTED)
                    .also { dao.update(it) }
            }
        }
    }

    suspend fun setState(id: String, state: String, error: String? = null) = dao.setState(id, state, error)

    suspend fun setProgress(id: String, progress: Float) = dao.setProgress(id, progress)

    suspend fun withStates(vararg states: String): List<RecordingEntity> = dao.withStates(states.toList())

    suspend fun uploadState(id: String): UploadState? {
        val recording = dao.get(id) ?: return null
        return UploadState(
            createdOnServer = recording.createdOnServer,
            segments = dao.segments(id).associate { it.idx to it.uploaded },
            photos = dao.photos(id).associate { it.id to it.uploaded },
            bookmarksSynced = recording.bookmarksSynced,
            finalized = recording.finalized,
        )
    }

    suspend fun segments(id: String): List<SegmentEntity> = dao.segments(id)

    fun segmentFile(segment: SegmentEntity): File = File(audioDir(segment.recordingId), segment.fileName)

    suspend fun photo(id: String, photoId: String): Pair<PhotoEntity, File>? =
        dao.photos(id).firstOrNull { it.id == photoId }?.let { it to File(photoDir(id), it.fileName) }

    suspend fun bookmarks(id: String): List<BookmarkEntity> = dao.bookmarks(id)

    suspend fun markCreated(id: String) = dao.markCreated(id)

    suspend fun markSegmentUploaded(id: String, index: Int) = dao.setSegmentsUploaded(id, listOf(index), true)

    suspend fun markSegmentsMissing(id: String, indices: List<Int>) = dao.setSegmentsUploaded(id, indices, false)

    suspend fun markPhotoUploaded(photoId: String) = dao.markPhotoUploaded(photoId)

    /** A photo whose file vanished is dropped rather than blocking the upload forever. */
    suspend fun dropPhoto(photoId: String) = dao.deletePhoto(photoId)

    suspend fun markBookmarksSynced(id: String) = dao.setBookmarksSynced(id, true)

    suspend fun markFinalized(id: String) = dao.markFinalized(id)

    suspend fun delete(id: String) {
        dao.delete(id)
        withContext(Dispatchers.IO) {
            audioDir(id).deleteRecursively()
            photoDir(id).deleteRecursively()
        }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        withContext(Dispatchers.IO) {
            File(context.filesDir, "recordings").deleteRecursively()
            File(context.filesDir, "photos").deleteRecursively()
        }
    }

    /**
     * Scans the segment files: cuts a half-written last frame, drops empty files and renumbers the
     * rest 0..n-1 so the server sees no gaps. Durations come from the frame count.
     */
    private suspend fun rebuildSegments(id: String): List<SegmentEntity> {
        val kept = withContext(Dispatchers.IO) {
            val dir = audioDir(id)
            val files = dir.listFiles().orEmpty()
                .mapNotNull { file -> Segments.indexOf(file.name)?.let { it to file } }
                .sortedBy { it.first }
            val result = mutableListOf<SegmentEntity>()
            for ((_, file) in files) {
                val bytes = file.readBytes()
                val scan = Adts.scan(bytes)
                if (scan.completeFrames == 0) {
                    file.delete()
                    continue
                }
                if (scan.validBytes < bytes.size) {
                    RandomAccessFile(file, "rw").use { it.setLength(scan.validBytes.toLong()) }
                }
                val target = File(dir, Segments.fileName(result.size))
                if (target.name != file.name && !file.renameTo(target)) continue
                result += SegmentEntity(id, result.size, target.name, target.length(), scan.durationSeconds)
            }
            result
        }
        db.withTransaction {
            dao.deleteSegments(id)
            dao.insertSegments(kept)
        }
        return kept
    }

    /** Re-encodes big camera photos to at most [MAX_PHOTO_EDGE] px so they upload quickly. */
    private fun shrinkPhoto(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (file.length() < 1_500_000) return
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_PHOTO_EDGE) {
                    val scale = MAX_PHOTO_EDGE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private companion object {
        const val MAX_PHOTO_EDGE = 2560
    }
}

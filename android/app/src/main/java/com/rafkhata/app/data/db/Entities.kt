package com.rafkhata.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of a recording on the phone. Rows are deleted once the server has everything. */
object RecordingState {
    const val RECORDING = "recording"

    /** The app was killed mid-recording; the user decides whether to upload what was saved. */
    const val INTERRUPTED = "interrupted"
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val FAILED = "failed"
}

@Entity(tableName = "recordings")
data class RecordingEntity(
    /** Also the lecture id on the server (a client-generated UUID). */
    @PrimaryKey val id: String,
    val courseId: String?,
    val courseTitle: String?,
    val title: String,
    val startedAt: Long,
    val durationS: Double = 0.0,
    val state: String = RecordingState.RECORDING,
    val consentConfirmed: Boolean,
    val notesLang: String? = null,
    val createdOnServer: Boolean = false,
    val bookmarksSynced: Boolean = false,
    val finalized: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

@Entity(
    tableName = "segments",
    primaryKeys = ["recordingId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SegmentEntity(
    val recordingId: String,
    val idx: Int,
    val fileName: String,
    val sizeBytes: Long,
    val durationS: Double,
    val uploaded: Boolean = false,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("recordingId")],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: String,
    val tOffsetS: Double,
    /** important | confused | note */
    val kind: String,
    val text: String? = null,
)

@Entity(
    tableName = "photos",
    indices = [Index("recordingId")],
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val recordingId: String,
    val fileName: String,
    val tOffsetS: Double,
    val contentType: String = "image/jpeg",
    val uploaded: Boolean = false,
)

/** Last server response for a screen, so the app shows something offline. */
@Entity(tableName = "cache")
data class CacheEntity(
    @PrimaryKey val cacheKey: String,
    val json: String,
    val updatedAt: Long,
)

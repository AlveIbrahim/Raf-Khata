package com.rafkhata.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE state IN (:states) ORDER BY startedAt")
    suspend fun withStates(states: List<String>): List<RecordingEntity>

    @Insert
    suspend fun insert(recording: RecordingEntity)

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("UPDATE recordings SET state = :state, error = :error WHERE id = :id")
    suspend fun setState(id: String, state: String, error: String?)

    @Query("UPDATE recordings SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: String, progress: Float)

    @Query("UPDATE recordings SET createdOnServer = 1 WHERE id = :id")
    suspend fun markCreated(id: String)

    @Query("UPDATE recordings SET bookmarksSynced = :synced WHERE id = :id")
    suspend fun setBookmarksSynced(id: String, synced: Boolean)

    @Query("UPDATE recordings SET finalized = 1 WHERE id = :id")
    suspend fun markFinalized(id: String)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()

    @Query("SELECT * FROM segments WHERE recordingId = :id ORDER BY idx")
    suspend fun segments(id: String): List<SegmentEntity>

    @Upsert
    suspend fun upsertSegment(segment: SegmentEntity)

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE recordingId = :id")
    suspend fun deleteSegments(id: String)

    @Query("UPDATE segments SET uploaded = :uploaded WHERE recordingId = :id AND idx IN (:indices)")
    suspend fun setSegmentsUploaded(id: String, indices: List<Int>, uploaded: Boolean)

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE recordingId = :id ORDER BY tOffsetS")
    suspend fun bookmarks(id: String): List<BookmarkEntity>

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE recordingId = :id ORDER BY tOffsetS")
    suspend fun photos(id: String): List<PhotoEntity>

    @Query("UPDATE photos SET uploaded = 1 WHERE id = :photoId")
    suspend fun markPhotoUploaded(photoId: String)

    @Query("DELETE FROM photos WHERE id = :photoId")
    suspend fun deletePhoto(photoId: String)
}

@Dao
interface CacheDao {
    @Query("SELECT json FROM cache WHERE cacheKey = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: CacheEntity)

    @Query("DELETE FROM cache WHERE cacheKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM cache")
    suspend fun clear()
}

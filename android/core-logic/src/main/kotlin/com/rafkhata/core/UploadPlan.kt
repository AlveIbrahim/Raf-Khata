package com.rafkhata.core

/** What the upload worker should do next for a lecture. The worker loops until [UploadStep.Done]. */
sealed interface UploadStep {
    data object CreateLecture : UploadStep
    data class Segment(val index: Int) : UploadStep
    data class Photo(val id: String) : UploadStep
    data object Bookmarks : UploadStep
    data object Finalize : UploadStep
    data object Done : UploadStep
}

data class UploadState(
    val createdOnServer: Boolean,
    /** index -> uploaded? */
    val segments: Map<Int, Boolean>,
    /** photo id -> uploaded? */
    val photos: Map<String, Boolean>,
    val bookmarksSynced: Boolean,
    val finalized: Boolean,
)

object UploadPlan {
    fun next(state: UploadState): UploadStep = when {
        state.finalized -> UploadStep.Done
        !state.createdOnServer -> UploadStep.CreateLecture
        state.segments.any { !it.value } -> UploadStep.Segment(state.segments.filter { !it.value }.keys.min())
        state.photos.any { !it.value } -> UploadStep.Photo(state.photos.filter { !it.value }.keys.sorted().first())
        !state.bookmarksSynced -> UploadStep.Bookmarks
        else -> UploadStep.Finalize
    }

    /** Segments must be numbered 0..n-1 with no gaps before a lecture can be finalized. */
    fun hasGaps(indices: Collection<Int>): Boolean = indices.sorted() != (0 until indices.size).toList()

    /** Fraction done, for the progress bar. */
    fun progress(state: UploadState): Float {
        val total = state.segments.size + state.photos.size + 3
        val done = state.segments.count { it.value } + state.photos.count { it.value } +
            (if (state.createdOnServer) 1 else 0) + (if (state.bookmarksSynced) 1 else 0) +
            (if (state.finalized) 1 else 0)
        return done.toFloat() / total
    }
}

package com.rafkhata.app.ui.lecture

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rafkhata.app.R
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.FeedbackIn
import com.rafkhata.app.data.api.LectureDto
import com.rafkhata.app.data.api.NotesDto
import com.rafkhata.app.data.api.StudyDto
import com.rafkhata.app.data.api.TranscriptDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.data.repo.Generated
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.isProcessing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Something generated on the server, as the screen sees it. */
sealed interface Content<out T> {
    data object Loading : Content<Nothing>

    data object Pending : Content<Nothing>

    data object Missing : Content<Nothing>

    data class Failed(val error: ErrorMessage) : Content<Nothing>

    data class Ready<T>(val value: T) : Content<T>
}

private fun <T> Generated<T>.toContent(): Content<T> = when (this) {
    is Generated.Ready -> Content.Ready(value)
    Generated.Pending -> Content.Pending
    Generated.Missing -> Content.Missing
}

data class PlayerUi(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

class LectureViewModel(private val app: AppContainer, private val lectureId: String, initialSeekMs: Long) : ViewModel() {
    data class State(
        val lecture: LectureDto? = null,
        val lectureError: ErrorMessage? = null,
        val lang: String? = null,
        val notes: Content<NotesDto> = Content.Loading,
        val transcript: Content<TranscriptDto> = Content.Loading,
        val study: Content<StudyDto> = Content.Loading,
        val deleted: Boolean = false,
        @param:StringRes val message: Int? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(app.appContext).build()
    private val _player = MutableStateFlow(PlayerUi())
    val playerUi: StateFlow<PlayerUi> = _player.asStateFlow()
    private var pendingSeekMs: Long = initialSeekMs

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _player.update { it.copy(playing = isPlaying, positionMs = player.currentPosition) }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _player.update { it.copy(loading = false, durationMs = player.duration.coerceAtLeast(0L)) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Usually an expired download link: fetch a new one on the next play.
                    _player.update { PlayerUi(speed = it.speed) }
                    _state.update { it.copy(message = R.string.audio_error) }
                }
            },
        )
        viewModelScope.launch { load() }
        viewModelScope.launch { app.lectureEvents.filter { it == lectureId }.collect { reloadAll() } }
        viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying) _player.update { it.copy(positionMs = player.currentPosition) }
                delay(250)
            }
        }
    }

    private suspend fun load() {
        app.lectures.cachedLecture(lectureId)?.let { cached ->
            _state.update { it.copy(lecture = cached, lang = it.lang ?: cached.notesLang) }
        }
        refreshLecture()
        reloadAll()
        if (pendingSeekMs >= 0 && _state.value.lecture?.status == "ready") prepareAudio(playWhenReady = false)
    }

    private suspend fun refreshLecture() {
        runCatching { app.lectures.get(lectureId) }
            .onSuccess { lecture -> _state.update { it.copy(lecture = lecture, lang = it.lang ?: lecture.notesLang, lectureError = null) } }
            .onFailure { e -> _state.update { it.copy(lectureError = e.toErrorMessage()) } }
    }

    private fun reloadAll() {
        val lang = _state.value.lang ?: return
        loadNotes(lang)
        loadTranscript()
        loadStudy(lang)
    }

    private fun loadNotes(lang: String) {
        viewModelScope.launch {
            val cached = app.lectures.cachedNotes(lectureId, lang)
            if (cached != null) {
                _state.update { it.copy(notes = Content.Ready(cached)) }
            } else if (_state.value.notes !is Content.Pending) {
                _state.update { it.copy(notes = Content.Loading) }
            }
            runCatching { app.lectures.notes(lectureId, lang) }
                .onSuccess { result -> if (_state.value.lang == lang) _state.update { it.copy(notes = result.toContent()) } }
                .onFailure { e -> if (cached == null) _state.update { it.copy(notes = Content.Failed(e.toErrorMessage())) } }
        }
    }

    private fun loadStudy(lang: String) {
        viewModelScope.launch {
            val cached = app.lectures.cachedStudy(lectureId, lang)
            if (cached != null) _state.update { it.copy(study = Content.Ready(cached)) }
            runCatching { app.lectures.study(lectureId, lang) }
                .onSuccess { result -> if (_state.value.lang == lang) _state.update { it.copy(study = result.toContent()) } }
                .onFailure { e -> if (cached == null) _state.update { it.copy(study = Content.Failed(e.toErrorMessage())) } }
        }
    }

    private fun loadTranscript() {
        viewModelScope.launch {
            val cached = app.lectures.cachedTranscript(lectureId)
            if (cached != null) _state.update { it.copy(transcript = Content.Ready(cached)) }
            if (_state.value.lecture?.status != "ready" && cached == null) {
                _state.update { it.copy(transcript = Content.Pending) }
                return@launch
            }
            runCatching { app.lectures.transcript(lectureId) }
                .onSuccess { t -> _state.update { it.copy(transcript = Content.Ready(t)) } }
                .onFailure { e ->
                    if (cached == null) _state.update { it.copy(transcript = Content.Failed(e.toErrorMessage())) }
                }
        }
    }

    /** Polls while the lecture is being processed or notes are being written. */
    suspend fun poll() {
        while (true) {
            delay(5_000)
            val before = _state.value
            val wasProcessing = before.lecture?.isProcessing() == true
            if (wasProcessing || before.lecture == null) refreshLecture()
            val after = _state.value
            val lang = after.lang ?: continue
            when {
                wasProcessing && after.lecture?.isProcessing() == false -> reloadAll()
                after.notes is Content.Pending || after.study is Content.Pending -> {
                    if (after.notes is Content.Pending) loadNotes(lang)
                    if (after.study is Content.Pending) loadStudy(lang)
                }
            }
        }
    }

    fun setLang(lang: String) {
        if (lang == _state.value.lang) return
        _state.update { it.copy(lang = lang, notes = Content.Loading, study = Content.Loading) }
        loadNotes(lang)
        loadStudy(lang)
    }

    fun generate(lang: String) {
        viewModelScope.launch {
            runCatching { app.lectures.regenerateNotes(lectureId, lang) }
                .onSuccess { _state.update { it.copy(notes = Content.Pending, study = Content.Pending) } }
                .onFailure { e -> _state.update { it.copy(notes = Content.Failed(e.toErrorMessage())) } }
        }
    }

    fun retryProcessing() {
        viewModelScope.launch {
            runCatching { app.lectures.retry(lectureId) }
                .onSuccess { lecture -> _state.update { it.copy(lecture = lecture, notes = Content.Pending) } }
                .onFailure { e -> _state.update { it.copy(lectureError = e.toErrorMessage()) } }
        }
    }

    fun editSegment(segmentId: String, text: String) {
        viewModelScope.launch {
            runCatching { app.lectures.editSegment(lectureId, segmentId, text) }
                .onSuccess { updated ->
                    _state.update { s ->
                        val t = s.transcript.let { if (it is Content.Ready) it.value else null } ?: return@update s
                        s.copy(
                            transcript = Content.Ready(t.copy(segments = t.segments.map { if (it.id == segmentId) updated else it })),
                            message = R.string.transcript_saved,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(message = R.string.transcript_save_failed) } }
        }
    }

    fun sendFeedback(kind: String, rating: Int?, comment: String?) {
        viewModelScope.launch {
            runCatching { app.lectures.feedback(lectureId, FeedbackIn(kind, rating, comment?.ifBlank { null }, _state.value.lang)) }
                .onSuccess { _state.update { it.copy(message = R.string.feedback_thanks) } }
                .onFailure { _state.update { it.copy(message = R.string.error_network) } }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { app.lectures.delete(lectureId) }
                .onSuccess {
                    _state.update { it.copy(deleted = true) }
                    app.lectureEvents.tryEmit(lectureId)
                }
                .onFailure { e -> _state.update { it.copy(lectureError = e.toErrorMessage()) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ----- audio -----

    fun togglePlay() {
        when {
            !_player.value.loaded -> prepareAudio(playWhenReady = true)
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(seconds: Double, play: Boolean = true) {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0)
        if (!_player.value.loaded) {
            pendingSeekMs = ms
            prepareAudio(playWhenReady = play)
            return
        }
        player.seekTo(ms)
        _player.update { it.copy(positionMs = ms) }
        if (play) player.play()
    }

    fun seekToFraction(fraction: Float) {
        val duration = _player.value.durationMs
        if (duration > 0) seekTo(duration * fraction / 1000.0, play = player.isPlaying)
    }

    fun cycleSpeed() {
        val speeds = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        val next = speeds[(speeds.indexOf(_player.value.speed) + 1).mod(speeds.size)]
        player.setPlaybackSpeed(next)
        _player.update { it.copy(speed = next) }
    }

    private fun prepareAudio(playWhenReady: Boolean) {
        if (_player.value.loading) return
        _player.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { app.lectures.audioUrl(lectureId) }
                .onSuccess { url ->
                    player.setMediaItem(MediaItem.fromUri(url), pendingSeekMs.coerceAtLeast(0))
                    pendingSeekMs = -1
                    player.setPlaybackSpeed(_player.value.speed)
                    player.prepare()
                    player.playWhenReady = playWhenReady
                    _player.update { it.copy(loaded = true) }
                }
                .onFailure {
                    _player.update { it.copy(loading = false) }
                    _state.update { it.copy(message = R.string.audio_not_ready) }
                }
        }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

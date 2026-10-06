package com.rafkhata.app.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import kotlin.math.min

/**
 * Synchronous AAC-LC encoder for mono 16-bit PCM. Emits raw AAC frames; the caller adds ADTS
 * headers. Used from a single recording thread.
 */
class AacEncoder(private val sampleRate: Int, bitRate: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var samplesQueued = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** Encodes [length] bytes of little-endian PCM and passes every finished AAC frame to [onFrame]. */
    fun encode(pcm: ByteArray, length: Int, onFrame: (ByteArray) -> Unit) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), 0)
                } else {
                    buffer.clear()
                    val n = min(buffer.remaining(), length - offset)
                    buffer.put(pcm, offset, n)
                    codec.queueInputBuffer(index, 0, n, presentationUs(), 0)
                    samplesQueued += n / 2
                    offset += n
                }
            }
            drain(onFrame, waitForEnd = false)
        }
    }

    /** Flushes the encoder's buffered audio at the end of a recording. */
    fun finish(onFrame: (ByteArray) -> Unit) {
        var endQueued = false
        repeat(100) {
            if (!endQueued) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, presentationUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    endQueued = true
                }
            }
            if (endQueued && drain(onFrame, waitForEnd = true)) return
        }
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

    private fun presentationUs(): Long = samplesQueued * 1_000_000L / sampleRate

    /** Returns true once the end-of-stream buffer has come out. */
    private fun drain(onFrame: (ByteArray) -> Unit, waitForEnd: Boolean): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (waitForEnd) TIMEOUT_US else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index < 0) continue // output format changed
            val buffer = codec.getOutputBuffer(index)
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (buffer != null && !isConfig && info.size > 0) {
                val frame = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(frame, 0, info.size)
                onFrame(frame)
            }
            codec.releaseOutputBuffer(index, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}

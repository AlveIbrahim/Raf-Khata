package com.rafkhata.app.recording

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.rafkhata.core.Adts
import com.rafkhata.core.AudioLevels
import com.rafkhata.core.RecordedClock
import com.rafkhata.core.Segments
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * Microphone -> AAC-LC -> ADTS files, a new file every 5 minutes. Each frame is self-contained,
 * so if the app dies only the last unflushed second is lost.
 */
class AudioRecorder(
    private val dir: File,
    private val preferredSource: Int,
    private val callbackExecutor: Executor,
    private val listener: Listener,
) {
    interface Listener {
        /** About 10 times a second while recording. */
        fun onAudio(levelDb: Double, clipped: Double, recordedSeconds: Double)

        /** A phone call or another app took the microphone (Android 10+). */
        fun onSilenced(silenced: Boolean)

        /** Recording stopped because of an error; what was recorded so far is on disk. */
        fun onError(error: Throwable)
    }

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false
    private var thread: Thread? = null
    private val clock = RecordedClock(SAMPLE_RATE)

    val recordedSeconds: Double get() = clock.seconds

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        val record = openAudioRecord()
        val callback = registerSilenceCallback(record)
        thread = Thread({ loop(record, callback) }, "rk-recorder").also { it.start() }
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    /** Stops recording and waits until the last file is closed. */
    fun stop() {
        stopRequested = true
        thread?.join(10_000)
        thread = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw IllegalStateException("16 kHz mono recording is not supported")
        val sources = listOf(preferredSource, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            .distinct()
        for (source in sources) {
            val record = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, max(minBuffer * 4, SAMPLE_RATE))
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        throw IllegalStateException("microphone unavailable")
    }

    private fun registerSilenceCallback(record: AudioRecord): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val callback = silenceCallback(record)
        record.registerAudioRecordingCallback(callbackExecutor, callback)
        return callback
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun silenceCallback(record: AudioRecord) = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
            listener.onSilenced(mine.isClientSilenced)
        }
    }

    private fun unregisterSilenceCallback(record: AudioRecord, callback: Any?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && callback is AudioManager.AudioRecordingCallback) {
            record.unregisterAudioRecordingCallback(callback)
        }
    }

    private fun loop(record: AudioRecord, callback: Any?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var encoder: AacEncoder? = null
        var writer: SegmentWriter? = null
        try {
            val aac = AacEncoder(SAMPLE_RATE, BIT_RATE).also { encoder = it }
            var index = nextIndex()
            var out = SegmentWriter(File(dir, Segments.fileName(index))).also { writer = it }
            val pcm = ShortArray(CHUNK_SAMPLES)
            val bytes = ByteArray(CHUNK_SAMPLES * 2)
            val onFrame: (ByteArray) -> Unit = { frame -> out.write(frame) }
            var capturing = false
            var lastFlush = SystemClock.elapsedRealtime()

            while (!stopRequested) {
                if (paused) {
                    if (capturing) {
                        record.stop()
                        capturing = false
                        out.flush()
                    }
                    Thread.sleep(50)
                    continue
                }
                if (!capturing) {
                    record.startRecording()
                    capturing = true
                }
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) throw IOException("AudioRecord.read failed with $n")
                if (n == 0) continue

                for (i in 0 until n) {
                    val s = pcm[i].toInt()
                    bytes[2 * i] = (s and 0xFF).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                }
                aac.encode(bytes, n * 2, onFrame)
                clock.add(n)
                listener.onAudio(AudioLevels.rmsDb(pcm, n), AudioLevels.clippedFraction(pcm, n), clock.seconds)

                if (Segments.shouldRotate(out.frames * Adts.SAMPLES_PER_FRAME, SAMPLE_RATE)) {
                    out.close()
                    index++
                    out = SegmentWriter(File(dir, Segments.fileName(index))).also { writer = it }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastFlush >= 1000) {
                    out.flush()
                    lastFlush = now
                }
            }
            if (capturing) record.stop()
            aac.finish(onFrame)
            out.close()
            writer = null
        } catch (e: Throwable) {
            writer?.let { runCatching { it.close() } }
            listener.onError(e)
        } finally {
            unregisterSilenceCallback(record, callback)
            record.release()
            encoder?.release()
        }
    }

    /** Continue numbering after files that already exist (e.g. a resumed recording). */
    private fun nextIndex(): Int =
        (dir.listFiles().orEmpty().mapNotNull { Segments.indexOf(it.name) }.maxOrNull() ?: -1) + 1

    private class SegmentWriter(file: File) {
        private val stream = FileOutputStream(file)
        private val out = BufferedOutputStream(stream, 64 * 1024)
        var frames = 0L
            private set

        fun write(frame: ByteArray) {
            out.write(Adts.header(frame.size, SAMPLE_RATE))
            out.write(frame)
            frames++
        }

        fun flush() = out.flush()

        fun close() {
            out.flush()
            runCatching { stream.fd.sync() }
            out.close()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 32_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 10
    }
}

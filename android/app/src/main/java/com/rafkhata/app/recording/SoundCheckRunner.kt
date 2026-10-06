package com.rafkhata.app.recording

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.annotation.RequiresPermission
import com.rafkhata.core.AudioLevels
import com.rafkhata.core.SoundCheck
import com.rafkhata.core.SoundChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Listens for a few seconds before class and judges noise and loudness. */
object SoundCheckRunner {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME = SAMPLE_RATE * 30 / 1000

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun run(audioSource: Int, seconds: Int, onLevel: (Float) -> Unit): SoundCheck = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer * 2, SAMPLE_RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("microphone unavailable")
        }
        val frame = ShortArray(FRAME)
        val levels = ArrayList<Double>()
        var clippedSamples = 0.0
        var samples = 0
        try {
            record.startRecording()
            while (samples < SAMPLE_RATE * seconds) {
                ensureActive()
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
                val db = AudioLevels.rmsDb(frame, n)
                levels += db
                clippedSamples += AudioLevels.clippedFraction(frame, n) * n
                samples += n
                onLevel(AudioLevels.meterFraction(db))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        SoundChecker.evaluate(levels, if (samples > 0) clippedSamples / samples else 0.0)
    }
}

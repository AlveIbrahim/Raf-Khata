package com.rafkhata.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Audio level measurement for the live meter and the 5-second sound check before recording. */
object AudioLevels {
    const val SILENCE_DB = -90.0

    /** RMS level of 16-bit PCM in dBFS (0 = full scale). */
    fun rmsDb(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / count)
        return max(20 * log10(max(rms, 1e-9)), SILENCE_DB)
    }

    fun clippedFraction(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var clipped = 0
        for (i in 0 until count) if (samples[i] >= 32700 || samples[i] <= -32700) clipped++
        return clipped.toDouble() / count
    }

    /** Map dBFS to 0..1 for a level bar (-60 dB and below = empty). */
    fun meterFraction(db: Double): Float = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
}

enum class SoundTip { TOO_QUIET, NOISY, CLIPPING }

data class SoundCheck(
    val noiseFloorDb: Double,
    val speechDb: Double,
    val snrDb: Double,
    val clipping: Double,
    val tips: List<SoundTip>,
) {
    val good: Boolean get() = tips.isEmpty()
}

object SoundChecker {
    /**
     * Judge recording conditions from per-frame levels (e.g. one value per 30 ms over 5 seconds).
     * Thresholds are tuned for a phone on a desk in a classroom.
     */
    fun evaluate(frameDb: List<Double>, clipping: Double): SoundCheck {
        if (frameDb.isEmpty()) return SoundCheck(SILENCE, SILENCE, 0.0, 0.0, listOf(SoundTip.TOO_QUIET))
        val sorted = frameDb.sorted()
        val noise = sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.size - 1)]
        val speech = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        val snr = speech - noise
        val tips = buildList {
            if (speech < -45.0) add(SoundTip.TOO_QUIET)
            if (noise > -40.0 || snr < 10.0) add(SoundTip.NOISY)
            if (clipping > 0.01) add(SoundTip.CLIPPING)
        }
        return SoundCheck(noise, speech, snr, clipping, tips)
    }

    private const val SILENCE = AudioLevels.SILENCE_DB
}

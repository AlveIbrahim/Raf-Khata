package com.rafkhata.core

import java.util.Locale

/** How a recording is cut into 5-minute files. */
object Segments {
    const val DEFAULT_SEGMENT_SECONDS = 300

    // Locale.ROOT: with a Bangla locale, String.format would write Bengali digits.
    fun fileName(index: Int): String = String.format(Locale.ROOT, "%04d.aac", index)

    fun indexOf(fileName: String): Int? =
        Regex("""^(\d{4})\.aac$""").matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()

    /** True when the current segment has reached its length and a new file should start. */
    fun shouldRotate(samplesInSegment: Long, sampleRate: Int, segmentSeconds: Int = DEFAULT_SEGMENT_SECONDS): Boolean =
        samplesInSegment >= sampleRate.toLong() * segmentSeconds
}

/** Recording clock that counts only recorded audio, so pauses don't shift bookmark times. */
class RecordedClock(private val sampleRate: Int) {
    var totalSamples: Long = 0
        private set

    fun add(samples: Int) {
        totalSamples += samples
    }

    val seconds: Double get() = totalSamples.toDouble() / sampleRate
}

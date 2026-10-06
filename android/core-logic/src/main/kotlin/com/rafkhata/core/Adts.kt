package com.rafkhata.core

/**
 * ADTS framing for raw AAC frames from Android's MediaCodec encoder.
 *
 * Each frame gets a 7-byte header, so a file is readable up to the last complete frame even if the
 * app is killed mid-recording (unlike MP4, which needs a final index written at the end).
 */
object Adts {
    const val HEADER_SIZE = 7
    const val SAMPLES_PER_FRAME = 1024
    private const val AAC_LC = 2

    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun sampleRateIndex(sampleRate: Int): Int {
        val index = SAMPLE_RATES.indexOf(sampleRate)
        require(index >= 0) { "unsupported AAC sample rate $sampleRate" }
        return index
    }

    fun sampleRateAt(index: Int): Int = SAMPLE_RATES[index]

    /** Header for one AAC-LC frame of [payloadLength] bytes. */
    fun header(payloadLength: Int, sampleRate: Int, channels: Int = 1): ByteArray {
        require(channels in 1..7) { "channels must be 1..7" }
        val frameLength = payloadLength + HEADER_SIZE
        require(frameLength < (1 shl 13)) { "frame too long: $frameLength" }
        val sr = sampleRateIndex(sampleRate)
        val profile = AAC_LC - 1
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            ((profile shl 6) or (sr shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (frameLength shr 11)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    data class FrameInfo(val frameLength: Int, val sampleRate: Int, val channels: Int)

    /** Parse the header at [offset], or null if there is no valid ADTS sync word there. */
    fun parseHeader(bytes: ByteArray, offset: Int = 0): FrameInfo? {
        if (offset + HEADER_SIZE > bytes.size) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        val b4 = bytes[offset + 4].toInt() and 0xFF
        val b5 = bytes[offset + 5].toInt() and 0xFF
        val srIndex = (b2 shr 2) and 0x0F
        if (srIndex >= SAMPLE_RATES.size) return null
        val channels = ((b2 and 1) shl 2) or (b3 shr 6)
        val frameLength = ((b3 and 3) shl 11) or (b4 shl 3) or (b5 shr 5)
        if (frameLength < HEADER_SIZE) return null
        return FrameInfo(frameLength, SAMPLE_RATES[srIndex], channels)
    }

    data class Scan(val completeFrames: Int, val validBytes: Int, val durationSeconds: Double)

    /**
     * Count complete frames in an ADTS stream. Used to recover a segment after a crash: the file is
     * truncated to [Scan.validBytes] and its duration is known exactly.
     */
    fun scan(bytes: ByteArray): Scan {
        var offset = 0
        var frames = 0
        var sampleRate = 0
        while (true) {
            val info = parseHeader(bytes, offset) ?: break
            if (offset + info.frameLength > bytes.size) break
            frames++
            sampleRate = info.sampleRate
            offset += info.frameLength
        }
        val duration = if (sampleRate == 0) 0.0 else frames.toDouble() * SAMPLES_PER_FRAME / sampleRate
        return Scan(frames, offset, duration)
    }
}

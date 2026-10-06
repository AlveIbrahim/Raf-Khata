package com.rafkhata.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdtsTest {
    @Test
    fun headerRoundTrips() {
        val header = Adts.header(payloadLength = 200, sampleRate = 16000)
        assertEquals(7, header.size)
        val info = Adts.parseHeader(header + ByteArray(200))!!
        assertEquals(207, info.frameLength)
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
    }

    @Test
    fun headerBitsMatchTheSpec() {
        val header = Adts.header(payloadLength = 1, sampleRate = 16000)
        // 0xFFF1, profile LC (01), sampling index 8 (1000), private 0, channel config 1.
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        assertEquals(0b0110_0000, header[2].toInt() and 0xFF)
        assertEquals(0b0100_0000, header[3].toInt() and 0xFF)
        assertEquals(0xFC, header[6].toInt() and 0xFF)
    }

    @Test
    fun scanCountsCompleteFramesAndDropsTruncatedTail() {
        val frame = Adts.header(100, 16000) + ByteArray(100) { 1 }
        val stream = frame + frame + frame + frame.copyOf(50) // last frame cut off by a crash
        val scan = Adts.scan(stream)
        assertEquals(3, scan.completeFrames)
        assertEquals(frame.size * 3, scan.validBytes)
        assertEquals(3 * 1024 / 16000.0, scan.durationSeconds, 1e-9)
    }

    @Test
    fun scanOfGarbageIsEmpty() {
        assertEquals(0, Adts.scan(ByteArray(64) { 7 }).completeFrames)
        assertNull(Adts.parseHeader(ByteArray(3)))
    }

    @Test
    fun rejectsUnsupportedRates() {
        assertFailsWith<IllegalArgumentException> { Adts.sampleRateIndex(15000) }
        assertEquals(8, Adts.sampleRateIndex(16000))
    }
}

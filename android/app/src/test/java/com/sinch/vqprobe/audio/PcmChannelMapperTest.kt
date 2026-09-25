package com.sinch.vqprobe.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Test

class PcmChannelMapperTest {
    @Test fun duplicatesSignedPcm16ExtremesWithoutChangingLittleEndianBytes() {
        // 0, +32767, -32768, -1, +4660, -4660 as PCM16LE.
        val source = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1, 0x34, 0x12, -52, -19)
        val before = source.copyOf()
        val stereo = PcmChannelMapper.dualMono(source, source.size)
        val expectedBytes = byteArrayOf(
            0, 0, 0, 0,
            -1, 127, -1, 127,
            0, -128, 0, -128,
            -1, -1, -1, -1,
            0x34, 0x12, 0x34, 0x12,
            -52, -19, -52, -19,
        )
        assertArrayEquals(expectedBytes, stereo)
        assertArrayEquals(before, source)
        val decoded = ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in listOf(0, 32767, -32768, -1, 4660, -4660)) {
            assertEquals(sample, decoded.short.toInt())
            assertEquals(sample, decoded.short.toInt())
        }
        assertEquals(0, decoded.remaining())
    }

    @Test fun finalPartialReadDuplicatesOnlyValidFramesAndIgnoresTrailingBuffer() {
        val source = byteArrayOf(1, 2, 3, 4, 99, 98, 97)
        val before = source.copyOf()
        assertArrayEquals(byteArrayOf(1, 2, 1, 2, 3, 4, 3, 4), PcmChannelMapper.dualMono(source, 4))
        assertArrayEquals(before, source)
    }

    @Test fun outputAndInputNeverShareMutableStorage() {
        val source = byteArrayOf(0x34, 0x12)
        val stereo = PcmChannelMapper.dualMono(source, 2)
        assertNotSame(source, stereo)
        stereo[0] = 0
        assertArrayEquals(byteArrayOf(0x34, 0x12), source)
        source[1] = 0
        assertArrayEquals(byteArrayOf(0, 0x12, 0x34, 0x12), stereo)
    }

    @Test fun zeroCountReturnsNoFramesEvenWhenSourceContainsData() {
        assertArrayEquals(byteArrayOf(), PcmChannelMapper.dualMono(byteArrayOf(), 0))
        assertArrayEquals(byteArrayOf(), PcmChannelMapper.dualMono(byteArrayOf(1, 2, 3), 0))
    }

    @Test fun rejectsIncompleteSamplesNegativeCountsAndCountsOutsideBuffer() {
        val source = byteArrayOf(1, 2, 3, 4)
        for (count in listOf(-2, -1, 1, 3, 5, 6, Int.MAX_VALUE)) {
            val before = source.copyOf()
            try {
                PcmChannelMapper.dualMono(source, count)
                fail("Expected PCM alignment rejection for count=$count")
            } catch (e: IllegalArgumentException) {
                assertEquals("INVALID_PCM16_FRAME_ALIGNMENT", e.message)
            }
            assertArrayEquals(before, source)
        }
    }
}

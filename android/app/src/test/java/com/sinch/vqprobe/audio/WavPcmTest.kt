package com.sinch.vqprobe.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavPcmTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsAllSupportedRatesAndExactPcmFrames() {
        for (rate in listOf(8_000, 16_000, 48_000)) {
            val wav = write(riff(chunk("fmt ", fmt(rate)), chunk("data", byteArrayOf(1, 2, 3, 4))))
            assertEquals(WavInfo(rate, 1, 16, 44, 4), WavPcm.read(wav))
            assertEquals(2L, WavPcm.read(wav).frames)
            assertEquals(2, WavPcm.read(wav).blockAlign)
        }
    }

    @Test fun skipsUnknownChunksPaddingAndExtendedPcmFormat() {
        val bytes = riff(
            chunk("JUNK", byteArrayOf(7, 8, 9)),
            chunk("fmt ", fmt() + byteArrayOf(0, 0)),
            chunk("LIST", byteArrayOf(3)),
            chunk("data", byteArrayOf(10, 11, 12, 13)),
            chunk("bext", byteArrayOf(42, 43, 44)),
        )
        val info = WavPcm.read(write(bytes))
        assertEquals(68L, info.dataOffset)
        assertEquals(4L, info.dataBytes)
        assertEquals(16_000, info.sampleRate)
    }

    @Test fun acceptsDataBeforeFormatAndEmptyPcm() {
        val info = WavPcm.read(write(riff(chunk("data", byteArrayOf()), chunk("fmt ", fmt()))))
        assertEquals(20L, info.dataOffset)
        assertEquals(0L, info.frames)
    }

    @Test fun rejectsTruncatedHeadersChunkPayloadsAndPadding() {
        fails("WAV_TRUNCATED") { WavPcm.read(write(ByteArray(11))) }
        val full = riff(chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2)))
        fails("WAV_TRUNCATED") { WavPcm.read(write(full.copyOf(full.size - 1))) }
        val missingPad = riff(chunk("fmt ", fmt()), "JUNK".toByteArray() + u32(1) + byteArrayOf(5))
        fails("WAV_TRUNCATED") { WavPcm.read(write(missingPad)) }
        val missingChunkHeader = riff(chunk("fmt ", fmt()), byteArrayOf(1, 2, 3))
        fails("WAV_TRUNCATED") { WavPcm.read(write(missingChunkHeader)) }
        val tooLong = riff(chunk("fmt ", fmt()), "data".toByteArray() + u32(0xffffffffL))
        fails("WAV_TRUNCATED") { WavPcm.read(write(tooLong)) }
    }

    @Test fun rejectsTrailingBytesOutsideRiffAndInvalidContainer() {
        val bytes = riff(chunk("fmt ", fmt()), chunk("data", byteArrayOf()))
        fails("WAV_INVALID_RIFF_SIZE") { WavPcm.read(write(bytes + byteArrayOf(0))) }
        for (magic in listOf("RF64", "RIFX", "JUNK")) {
            val bad = bytes.copyOf()
            magic.toByteArray().copyInto(bad, 0)
            fails("WAV_INVALID_HEADER") { WavPcm.read(write(bad)) }
        }
        val badType = bytes.copyOf()
        "AVI ".toByteArray().copyInto(badType, 8)
        fails("WAV_INVALID_HEADER") { WavPcm.read(write(badType)) }
    }

    @Test fun rejectsUnsupportedCodecsFormatsAndInvalidByteRates() {
        val cases = listOf(
            Triple(0, 3, "WAV_UNSUPPORTED_FORMAT"),
            Triple(2, 2, "WAV_UNSUPPORTED_CHANNELS"),
            Triple(14, 8, "WAV_UNSUPPORTED_ENCODING"),
            Triple(12, 4, "WAV_INVALID_BLOCK_ALIGN"),
        )
        for ((offset, value, code) in cases) {
            val bad = fmt().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, value.toShort()) }
            fails(code) { WavPcm.read(write(riff(chunk("fmt ", bad), chunk("data", byteArrayOf())))) }
        }
        fails("WAV_UNSUPPORTED_SAMPLE_RATE") {
            WavPcm.read(write(riff(chunk("fmt ", fmt(44_100)), chunk("data", byteArrayOf()))))
        }
        val badByteRate = fmt().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1) }
        fails("WAV_INVALID_BYTE_RATE") {
            WavPcm.read(write(riff(chunk("fmt ", badByteRate), chunk("data", byteArrayOf()))))
        }
    }

    @Test fun rejectsMalformedFormatChunksAndUnalignedData() {
        for (badFmt in listOf(fmt().copyOf(15), fmt() + byteArrayOf(0), fmt() + byteArrayOf(3, 0))) {
            fails("WAV_INVALID_FMT") {
                WavPcm.read(write(riff(chunk("fmt ", badFmt), chunk("data", byteArrayOf()))))
            }
        }
        fails("WAV_UNALIGNED_DATA") {
            WavPcm.read(write(riff(chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2, 3)))))
        }
    }

    @Test fun rejectsMissingAndDuplicateChunks() {
        fails("WAV_MISSING_FMT") { WavPcm.read(write(riff(chunk("data", byteArrayOf())))) }
        fails("WAV_MISSING_DATA") { WavPcm.read(write(riff(chunk("fmt ", fmt())))) }
        fails("WAV_DUPLICATE_FMT") {
            WavPcm.read(write(riff(chunk("fmt ", fmt()), chunk("fmt ", fmt()), chunk("data", byteArrayOf()))))
        }
        fails("WAV_DUPLICATE_DATA") {
            WavPcm.read(write(riff(chunk("fmt ", fmt()), chunk("data", byteArrayOf()), chunk("data", byteArrayOf()))))
        }
    }

    @Test fun rewritesCanonicalHeaderWithoutChangingPcmOrFilePointer() {
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128)
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use {
            it.seek(44)
            it.write(pcm)
            val position = it.filePointer
            WavPcm.writeHeader(it, 48_000, pcm.size.toLong())
            assertEquals(position, it.filePointer)
            assertEquals(44L + pcm.size, it.length())
        }
        assertArrayEquals(riff(chunk("fmt ", fmt(48_000)), chunk("data", pcm)), file.readBytes())
        assertEquals(3L, WavPcm.read(file).frames)
    }

    @Test fun repairsStaleCaptureHeaderAndDropsOnlyIncompleteFinalSample() {
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use {
            WavPcm.writeHeader(it, 16_000, 0)
            it.seek(44)
            it.write(byteArrayOf(1, 2, 3, 4, 5))
        }
        fails("WAV_INVALID_RIFF_SIZE") { WavPcm.read(file) }
        val repaired = WavPcm.repair(file, 16_000)
        assertEquals(WavInfo(16_000, 1, 16, 44, 4), repaired)
        assertEquals(repaired, WavPcm.read(file))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes().copyOfRange(44, 48))
        val once = file.readBytes()
        assertEquals(repaired, WavPcm.repair(file, 16_000))
        assertArrayEquals(once, file.readBytes())
    }

    @Test fun repairsTornSizeFieldsButNeverAnUnrecognizedHeader() {
        val canonical = riff(chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2)))
        val damagedLengths = canonical.copyOf().also {
            u32(0xffffffffL).copyInto(it, 4)
            u32(0xffffffffL).copyInto(it, 40)
        }
        val damagedFile = write(damagedLengths)
        assertEquals(2L, WavPcm.repair(damagedFile, 16_000).dataBytes)
        assertArrayEquals(canonical, damagedFile.readBytes())

        val noncanonical = write(riff(chunk("JUNK", byteArrayOf(1, 2)), chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2))))
        val before = noncanonical.readBytes()
        fails("WAV_REPAIR_NOT_CANONICAL") { WavPcm.repair(noncanonical, 16_000) }
        assertArrayEquals(before, noncanonical.readBytes())
        val wrongRate = write(canonical)
        fails("WAV_REPAIR_NOT_CANONICAL") { WavPcm.repair(wrongRate, 8_000) }
        assertArrayEquals(canonical, wrongRate.readBytes())
    }

    @Test fun enforcesFullArtifactLimitIncludingHeaderWithoutAllocatingPayload() {
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use {
            it.setLength(WavPcm.MAX_FILE_BYTES)
            WavPcm.writeHeader(it, 8_000, WavPcm.MAX_DATA_BYTES)
        }
        assertEquals(WavPcm.MAX_DATA_BYTES, WavPcm.read(file).dataBytes)
        RandomAccessFile(file, "rw").use { it.setLength(WavPcm.MAX_FILE_BYTES + 1) }
        fails("WAV_TOO_LARGE") { WavPcm.read(file) }
        fails("WAV_TOO_LARGE") { WavPcm.repair(file, 8_000) }
        assertEquals(WavPcm.MAX_FILE_BYTES + 1, file.length())
    }

    @Test fun invalidWriterArgumentsNeverMutateAnExistingFile() {
        val original = riff(chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2)))
        val file = write(original)
        RandomAccessFile(file, "rw").use {
            fails("WAV_UNSUPPORTED_SAMPLE_RATE") { WavPcm.writeHeader(it, 44_100, 2) }
            fails("WAV_INVALID_DATA_SIZE") { WavPcm.writeHeader(it, 16_000, -2) }
            fails("WAV_UNALIGNED_DATA") { WavPcm.writeHeader(it, 16_000, 1) }
            fails("WAV_TOO_LARGE") { WavPcm.writeHeader(it, 16_000, WavPcm.MAX_DATA_BYTES + 2) }
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun everyTruncatedPrefixOfAValidWavIsRejected() {
        val bytes = riff(chunk("JUNK", byteArrayOf(1)), chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2, 3, 4)))
        for (size in bytes.indices) {
            fails("WAV_TRUNCATED") { WavPcm.read(write(bytes.copyOf(size))) }
        }
        assertTrue(WavPcm.read(write(bytes)).frames == 2L)
    }

    private fun write(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }
    private fun fmt(rate: Int = 16_000): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
    }.array()
    private fun u32(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    private fun chunk(id: String, bytes: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + u32(bytes.size.toLong()) + bytes + if (bytes.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII))
            chunks.forEach { write(it) }
        }.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + u32(body.size.toLong()) + body
    }
    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: IllegalArgumentException) {
            assertEquals(code, e.message)
        }
    }
}

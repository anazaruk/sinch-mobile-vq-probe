package com.sinch.vqprobe.audio

import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val blockAlign: Int get() = channels * (bitsPerSample / 8)
    val frames: Long get() = dataBytes / blockAlign
}

/**
 * Strict, bounded RIFF/WAVE PCM reader and capture-file header writer.
 *
 * Validation failures are IllegalArgumentException with a stable WAV_* message. Ordinary
 * filesystem failures remain IOException. No audio conversion or whole-file allocation occurs.
 * Callers must provide exclusive access while writing or repairing a recording.
 */
object WavPcm {
    const val HEADER_BYTES = 44
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_DATA_BYTES = MAX_FILE_BYTES - HEADER_BYTES
    private val supportedRates = setOf(8_000, 16_000, 48_000)

    fun read(file: File): WavInfo = truncatedAsValidation {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            require(length <= MAX_FILE_BYTES) { "WAV_TOO_LARGE" }
            require(length >= 12) { "WAV_TRUNCATED" }
            require(input.readInt() == RIFF) { "WAV_INVALID_HEADER" }
            val riffBytes = input.readU32()
            require(input.readInt() == WAVE) { "WAV_INVALID_HEADER" }
            require(riffBytes >= 4) { "WAV_INVALID_RIFF_SIZE" }
            val end = riffBytes + 8
            require(end <= length) { "WAV_TRUNCATED" }
            // Do not silently accept bytes outside the declared RIFF container.
            require(end == length) { "WAV_INVALID_RIFF_SIZE" }

            var format: Format? = null
            var dataOffset: Long? = null
            var dataBytes = 0L
            while (input.filePointer < end) {
                require(end - input.filePointer >= 8) { "WAV_TRUNCATED" }
                val id = input.readInt()
                val chunkBytes = input.readU32()
                val payload = input.filePointer
                val paddedBytes = chunkBytes + (chunkBytes and 1L)
                require(paddedBytes <= end - payload) { "WAV_TRUNCATED" }
                when (id) {
                    FMT -> {
                        require(format == null) { "WAV_DUPLICATE_FMT" }
                        require(chunkBytes >= 16) { "WAV_INVALID_FMT" }
                        val codec = input.readU16()
                        val channels = input.readU16()
                        val rate = input.readU32()
                        val byteRate = input.readU32()
                        val blockAlign = input.readU16()
                        val bits = input.readU16()
                        require(codec == 1) { "WAV_UNSUPPORTED_FORMAT" }
                        require(channels == 1) { "WAV_UNSUPPORTED_CHANNELS" }
                        require(bits == 16) { "WAV_UNSUPPORTED_ENCODING" }
                        require(rate in supportedRates.map { it.toLong() }) { "WAV_UNSUPPORTED_SAMPLE_RATE" }
                        require(blockAlign == 2) { "WAV_INVALID_BLOCK_ALIGN" }
                        require(byteRate == rate * blockAlign) { "WAV_INVALID_BYTE_RATE" }
                        if (chunkBytes != 16L) {
                            require(chunkBytes >= 18) { "WAV_INVALID_FMT" }
                            require(input.readU16().toLong() == chunkBytes - 18) { "WAV_INVALID_FMT" }
                        }
                        format = Format(rate.toInt(), channels, bits)
                    }
                    DATA -> {
                        require(dataOffset == null) { "WAV_DUPLICATE_DATA" }
                        dataOffset = payload
                        dataBytes = chunkBytes
                    }
                }
                // Skip PCM/unknown chunks with seek; even a large data chunk uses constant memory.
                input.seek(payload + paddedBytes)
            }
            val fmt = requireNotNull(format) { "WAV_MISSING_FMT" }
            val offset = requireNotNull(dataOffset) { "WAV_MISSING_DATA" }
            require(dataBytes % 2 == 0L) { "WAV_UNALIGNED_DATA" }
            WavInfo(fmt.sampleRate, fmt.channels, fmt.bits, offset, dataBytes)
        }
    }

    /** Writes exactly 44 header bytes, preserving existing PCM and the file pointer; never truncates. */
    fun writeHeader(file: RandomAccessFile, sampleRate: Int, dataBytes: Long) {
        validateRate(sampleRate)
        require(dataBytes >= 0) { "WAV_INVALID_DATA_SIZE" }
        require(dataBytes <= MAX_DATA_BYTES && file.length() <= MAX_FILE_BYTES) { "WAV_TOO_LARGE" }
        require(dataBytes % 2 == 0L) { "WAV_UNALIGNED_DATA" }
        val position = file.filePointer
        try {
            file.seek(0)
            file.write(header(sampleRate, dataBytes))
        } finally {
            file.seek(position)
        }
    }

    /**
     * Finalizes a caller-owned partial recording created by writeHeader, never an arbitrary WAV.
     * All immutable canonical header bytes must match. Only RIFF/data lengths may be stale or
     * torn by a crash. Lengths are rebuilt from actual bytes; an incomplete final PCM16 sample is
     * discarded (at most one byte). Noncanonical files are rejected before any mutation.
     */
    fun repair(file: File, sampleRate: Int): WavInfo = truncatedAsValidation {
        validateRate(sampleRate)
        if (!file.isFile) throw FileNotFoundException(file.path)
        RandomAccessFile(file, "rw").use { output ->
            val length = output.length()
            require(length <= MAX_FILE_BYTES) { "WAV_TOO_LARGE" }
            require(length >= HEADER_BYTES) { "WAV_TRUNCATED" }
            val actualHeader = ByteArray(HEADER_BYTES)
            output.readFully(actualHeader)
            val expectedHeader = header(sampleRate, 0)
            require(actualHeader.indices.all {
                it in 4..7 || it in 40..43 || actualHeader[it] == expectedHeader[it]
            }) { "WAV_REPAIR_NOT_CANONICAL" }
            val rawBytes = length - HEADER_BYTES
            val dataBytes = rawBytes - (rawBytes and 1L)
            output.setLength(HEADER_BYTES + dataBytes)
            writeHeader(output, sampleRate, dataBytes)
            output.fd.sync()
            WavInfo(sampleRate, 1, 16, HEADER_BYTES.toLong(), dataBytes)
        }
    }

    private fun validateRate(sampleRate: Int) {
        require(sampleRate in supportedRates) { "WAV_UNSUPPORTED_SAMPLE_RATE" }
    }

    private fun header(sampleRate: Int, dataBytes: Long): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()

    private fun RandomAccessFile.readU16(): Int = java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff
    private fun RandomAccessFile.readU32(): Long = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
    private inline fun <T> truncatedAsValidation(block: () -> T): T = try {
        block()
    } catch (e: EOFException) {
        throw IllegalArgumentException("WAV_TRUNCATED", e)
    }

    private data class Format(val sampleRate: Int, val channels: Int, val bits: Int)
    private const val RIFF = 0x52494646
    private const val WAVE = 0x57415645
    private const val FMT = 0x666d7420
    private const val DATA = 0x64617461
}

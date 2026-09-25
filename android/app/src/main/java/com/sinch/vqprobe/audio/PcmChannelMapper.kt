package com.sinch.vqprobe.audio

/** Exact PCM16LE channel duplication; no gain change, interpolation, or source-file mutation. */
internal object PcmChannelMapper {
    fun dualMono(source: ByteArray, count: Int): ByteArray {
        require(count in 0..source.size && count % 2 == 0) { "INVALID_PCM16_FRAME_ALIGNMENT" }
        require(count <= Int.MAX_VALUE / 2) { "PCM_BUFFER_TOO_LARGE" }
        return ByteArray(count * 2).also { output ->
            for (offset in 0 until count step 2) {
                output[offset * 2] = source[offset]
                output[offset * 2 + 1] = source[offset + 1]
                output[offset * 2 + 2] = source[offset]
                output[offset * 2 + 3] = source[offset + 1]
            }
        }
    }
}

package com.sinch.vqprobe.audio

import android.content.Context
import com.sinch.vqprobe.storage.ProbeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/** App-private immutable capture artifacts. No eviction of pending recordings or uploads. */
class AudioArtifactStore(context: Context, private val store: ProbeStore) {
    private val directory = File(context.filesDir, "captured_audio").apply { check(mkdirs() || isDirectory) }
    private val writers = mutableSetOf<String>()
    private val readers = mutableSetOf<String>()

    @Synchronized fun create(fileId: String, sampleRate: Int, data: JSONObject = JSONObject()): File {
        valid(fileId)
        require(sampleRate in setOf(8000, 16000, 48000)) { "WAV_UNSUPPORTED_SAMPLE_RATE" }
        check(fileId !in writers && !part(fileId).exists() && !finished(fileId).exists() && metadata(fileId) == null) { "ARTIFACT_ALREADY_EXISTS" }
        // Reserve the full possible recording so a concurrent writer cannot overcommit quota.
        val used = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val reserved = writers.sumOf { WavPcm.MAX_FILE_BYTES - part(it).length() }
        check(used + reserved + WavPcm.MAX_FILE_BYTES <= TOTAL_BYTES) { "AUDIO_ARTIFACT_QUOTA_EXCEEDED" }
        check(directory.usableSpace >= WavPcm.MAX_FILE_BYTES + 8L * 1024 * 1024) { "INSUFFICIENT_AUDIO_STORAGE" }
        store.putMeta(key(fileId), JSONObject(data.toString()).put("file_id", fileId).put("state", "RECORDING")
            .put("sample_rate", sampleRate).put("channels", 1).put("bits_per_sample", 16)
            .put("encoding", "PCM_16BIT").put("created_at", Instant.now().toString()).toString())
        try {
            RandomAccessFile(part(fileId), "rw").use { WavPcm.writeHeader(it, sampleRate, 0); it.fd.sync() }
            writers.add(fileId)
            return part(fileId)
        } catch (e: Exception) {
            updateMetadata(fileId, JSONObject().put("state", "CREATION_FAILED").put("error", e.javaClass.simpleName))
            throw e
        }
    }

    @Synchronized fun updateMetadata(fileId: String, data: JSONObject): JSONObject {
        valid(fileId)
        val current = metadata(fileId) ?: throw IllegalStateException("ARTIFACT_NOT_FOUND")
        data.keys().forEach { if (it != "file_id") current.put(it, data.get(it)) }
        store.putMeta(key(fileId), current.toString())
        return current
    }

    @Synchronized fun writerFile(fileId: String): File {
        valid(fileId)
        check(fileId in writers && part(fileId).isFile) { "ARTIFACT_WRITER_NOT_ACTIVE" }
        return part(fileId)
    }

    /** Caller has stopped/closed its writer before finalization. Existing WAV is never replaced. */
    @Synchronized fun finalize(fileId: String, data: JSONObject): JSONObject {
        valid(fileId)
        val meta = metadata(fileId) ?: throw IllegalStateException("ARTIFACT_NOT_FOUND")
        check(fileId !in readers) { "ARTIFACT_UPLOAD_ACTIVE" }
        val input = if (part(fileId).isFile) part(fileId) else finished(fileId)
        check(input.isFile) { "ARTIFACT_FILE_MISSING" }
        check(input.length() <= WavPcm.MAX_FILE_BYTES) { "ARTIFACT_TOO_LARGE" }
        val wav = if (input == part(fileId)) WavPcm.repair(input, meta.getInt("sample_rate")) else WavPcm.read(input)
        if (input == part(fileId)) {
            check(!finished(fileId).exists()) { "ARTIFACT_ALREADY_EXISTS" }
            Files.move(input.toPath(), finished(fileId).toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        val result = JSONObject(data.toString()).put("state", "FINALIZED")
            .put("sample_rate", wav.sampleRate).put("channels", wav.channels).put("bits_per_sample", wav.bitsPerSample)
            .put("data_bytes", wav.dataBytes).put("frames_captured", wav.frames)
            .put("duration_seconds", wav.frames.toDouble() / wav.sampleRate)
            .put("size_bytes", finished(fileId).length()).put("sha256", sha256(finished(fileId)))
            .put("finalized_at", Instant.now().toString())
        val updated = updateMetadata(fileId, result)
        writers.remove(fileId)
        return updated
    }

    @Synchronized fun file(fileId: String): File {
        valid(fileId)
        check(fileId !in writers && metadata(fileId)?.optString("state") == "FINALIZED") { "ARTIFACT_NOT_FINALIZED" }
        val file = finished(fileId)
        check(file.isFile) { "ARTIFACT_FILE_MISSING" }
        return file
    }

    @Synchronized fun metadata(fileId: String): JSONObject? {
        valid(fileId)
        return store.getMeta(key(fileId))?.let { JSONObject(it) }
    }

    /** Bounded metadata only; the engineering bridge never exposes PCM or credential files. */
    @Synchronized fun diagnosticMetadata(limit: Int = 20): JSONObject {
        val ids = directory.listFiles().orEmpty().filter { it.extension in setOf("part", "wav") }
            .sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension }.distinct()
        val entries = JSONArray()
        for (id in ids.take(limit.coerceIn(1, 20))) {
            val meta = runCatching { metadata(id) }.getOrNull()
                ?: JSONObject().put("file_id", id).put("error", "ARTIFACT_METADATA_UNAVAILABLE")
            entries.put(meta)
        }
        return JSONObject().put("items", entries).put("total", ids.size).put("truncated", ids.size > entries.length())
    }

    /** A durable upload pin survives a lost HTTP response. Retry with the same command ID. */
    @Synchronized fun beginUpload(fileId: String, commandId: String): File {
        require(commandId.isNotBlank()) { "UPLOAD_COMMAND_REQUIRED" }
        val file = file(fileId)
        check(fileId !in readers) { "ARTIFACT_UPLOAD_ACTIVE" }
        // An explicit new upload command may retry a previously failed transfer.
        // The live-reader lock still prevents replacement during an in-flight upload.
        store.putMeta(uploadKey(fileId), commandId)
        readers.add(fileId)
        return file
    }

    @Synchronized fun endUpload(fileId: String, commandId: String, success: Boolean) {
        valid(fileId)
        check(store.getMeta(uploadKey(fileId)) == commandId) { "UPLOAD_COMMAND_MISMATCH" }
        readers.remove(fileId)
        if (success) {
            updateMetadata(fileId, JSONObject().put("uploaded_at", Instant.now().toString()).put("upload_command_id", commandId))
            store.putMeta(uploadKey(fileId), null)
        }
    }

    /** Repair our known partial WAVs on process restart; never resume recording implicitly. */
    @Synchronized fun recover(): JSONArray {
        val recovered = JSONArray()
        val ids = directory.listFiles().orEmpty().filter { it.extension in setOf("part", "wav") }
            .map { it.nameWithoutExtension }.distinct()
        for (id in ids) {
            if (id in writers || id in readers) continue
            try {
                val meta = metadata(id)
                if (meta == null) {
                    // Preserve an unidentified file; never guess its rate or publish it as audio.
                    recovered.put(JSONObject().put("file_id", id).put("state", "UNRECOVERABLE")
                        .put("error", "ARTIFACT_METADATA_MISSING"))
                } else if (meta.optString("state") != "FINALIZED") {
                    recovered.put(finalize(id, JSONObject().put("recovered", true)
                        .put("status", "FAILED").put("error", "CAPTURE_PROCESS_INTERRUPTED")
                        .put("terminal_event_pending", true)
                        .put("capture_stopped_at", JSONObject.NULL).put("stop_timestamp_known", false)))
                } else if (meta.optBoolean("terminal_event_pending")) {
                    recovered.put(meta.put("event_delivery_recovered", true))
                }
            } catch (e: Exception) {
                recovered.put(JSONObject().put("file_id", id).put("state", "UNRECOVERABLE")
                    .put("error", e.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "ARTIFACT_RECOVERY_FAILED"))
            }
        }
        return recovered
    }

    @Synchronized fun delete(fileId: String): JSONObject {
        valid(fileId)
        check(fileId !in writers) { "ARTIFACT_RECORDING_ACTIVE" }
        check(fileId !in readers && store.getMeta(uploadKey(fileId)) == null) { "ARTIFACT_UPLOAD_PENDING" }
        val existed = part(fileId).exists() || finished(fileId).exists() || metadata(fileId) != null
        check(!part(fileId).exists() || part(fileId).delete()) { "ARTIFACT_DELETE_FAILED" }
        check(!finished(fileId).exists() || finished(fileId).delete()) { "ARTIFACT_DELETE_FAILED" }
        store.putMeta(key(fileId), null)
        return JSONObject().put("file_id", fileId).put("deleted", existed)
    }

    /** Releases only the in-process writer lock after a failed finalization; preserves bytes. */
    @Synchronized fun abandonWriter(fileId: String) { writers.remove(fileId) }
    private fun valid(id: String) { require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "INVALID_FILE_ID" } }
    private fun part(id: String) = File(directory, "$id.part")
    private fun finished(id: String) = File(directory, "$id.wav")
    private fun key(id: String) = "audio_artifact:$id"
    private fun uploadKey(id: String) = "audio_upload:$id"
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    companion object { const val TOTAL_BYTES = 256L * 1024 * 1024 }
}

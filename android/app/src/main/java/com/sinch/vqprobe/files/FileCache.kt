package com.sinch.vqprobe.files

import android.content.Context
import com.sinch.vqprobe.storage.ProbeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Opaque file IDs in app-private storage; same-origin/allowlisted HTTPS only. */
class FileCache(context: Context, private val store: ProbeStore) {
    private val readers = mutableMapOf<String, Int>()
    private val directory = File(context.filesDir, "reference_audio").apply {
        mkdirs()
        // Only this process owns the cache; partial files cannot be valid across a restart.
        listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }
    @Synchronized fun download(parameters: JSONObject): JSONObject {
        val id = validId(parameters.getString("file_id"))
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val expected = parameters.getString("sha256").lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "INVALID_SHA256" }
        val uri = URI(parameters.getString("url"))
        val base = URI(store.settings().getString("base_url"))
        val allow = store.settings().optJSONObject("config")?.optJSONArray("download_allowed_hosts") ?: JSONArray()
        val trustedHosts = (0 until allow.length()).map { allow.getString(it).lowercase() }.toSet()
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) { "HTTPS_URL_REQUIRED" }
        require((uri.host.equals(base.host, true) && effectivePort(uri) == effectivePort(base)) || (uri.host.lowercase() in trustedHosts && effectivePort(uri) == 443)) { "DOWNLOAD_HOST_NOT_ALLOWED" }
        val target = File(directory, "$id.bin")
        if (target.isFile && sha256(target) == expected) return JSONObject().put("file_id", id).put("sha256", expected).put("bytes", target.length()).put("cached", true)
        check((readers[id] ?: 0) == 0) { "FILE_IN_USE" }
        val maxBytes = 32L * 1024 * 1024
        val used = directory.listFiles()?.filter { it.isFile && it != target }?.sumOf { it.length() } ?: 0L
        require(used + maxBytes <= 256L * 1024 * 1024) { "CACHE_QUOTA_EXCEEDED" }
        val temporary = File(directory, "$id.part")
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            require(connection.responseCode == 200) { "DOWNLOAD_HTTP_${connection.responseCode}" }
            require(connection.contentLengthLong <= maxBytes) { "FILE_TOO_LARGE" }
            var bytes = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(16 * 1024)
                while(true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("DOWNLOAD_INTERRUPTED")
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    require(bytes <= maxBytes) { "FILE_TOO_LARGE" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                output.fd.sync()
            } }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == expected) { "CHECKSUM_MISMATCH" }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return JSONObject().put("file_id", id).put("sha256", actual).put("bytes", bytes).put("cached", false)
        } finally { connection.disconnect(); temporary.delete() }
    }
    @Synchronized fun delete(parameters: JSONObject): JSONObject {
        val id = validId(parameters.getString("file_id"))
        check((readers[id] ?: 0) == 0) { "FILE_IN_USE" }
        val file = File(directory, "$id.bin")
        val partial = File(directory, "$id.part")
        check(!partial.exists() || partial.delete()) { "DELETE_FAILED" }
        val existed = file.exists()
        check(!existed || file.delete()) { "DELETE_FAILED" }
        return JSONObject().put("file_id", id).put("deleted", existed)
    }
    @Synchronized fun referenceFile(fileId: String): File {
        val file = File(directory, "${validId(fileId)}.bin")
        check(file.isFile) { "REFERENCE_FILE_NOT_FOUND" }
        return file
    }
    @Synchronized fun acquireReference(fileId: String): File {
        val file = referenceFile(fileId)
        readers[fileId] = (readers[fileId] ?: 0) + 1
        return file
    }
    @Synchronized fun releaseReference(fileId: String) {
        val remaining = (readers[fileId] ?: 0) - 1
        if (remaining <= 0) readers.remove(fileId) else readers[fileId] = remaining
    }
    private fun validId(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "INVALID_FILE_ID" }
        return id
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(16 * 1024); while(true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun effectivePort(uri: URI) = if(uri.port == -1) 443 else uri.port
}

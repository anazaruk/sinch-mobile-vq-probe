package com.sinch.vqprobe.api

import com.sinch.vqprobe.BuildConfig
import com.sinch.vqprobe.storage.ProbeStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.net.URI
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

class ApiException(val httpStatus: Int, val code: String) : Exception(code)

/** Normal certificate and hostname verification; no HTTP, redirect, or insecure TLS fallback. */
class ApiClient(private val store: ProbeStore) {
    @Synchronized fun enroll(baseUrl: String, code: String, name: String): JSONObject {
        require(code.isNotBlank() && name.isNotBlank()) { "ENROLLMENT_FIELDS_REQUIRED" }
        check(store.deviceId() == null) { "ALREADY_ENROLLED" }
        val base = origin(baseUrl)
        val result = request(base, "/api/v1/probe/enroll", JSONObject()
            .put("enrollment_code", code).put("device_name", name).put("app_version", BuildConfig.VERSION_NAME))
        require(result.optString("device_id").isNotBlank() && result.optString("access_token").isNotBlank() && result.optString("refresh_token").isNotBlank()) { "INVALID_ENROLLMENT_RESPONSE" }
        // Origin/config first: any identity committed after this has enough state to reconnect.
        // A crash before credential persistence requires a new one-use enrollment code.
        store.saveSettings(store.settings().put("base_url", base).put("device_name", name)
            .put("config", result.optJSONObject("config") ?: JSONObject()).put("enabled", false))
        store.saveCredentials(result)
        store.saveSettings(store.settings().put("enabled", true))
        return result
    }
    @Synchronized fun poll(status: JSONObject): JSONObject = authenticated("/api/v1/probe/poll", status)

    /** Separate connection from polling: a long WAV upload must not block HANGUP delivery. */
    fun uploadArtifact(commandId: String, fileId: String, file: File): JSONObject {
        require(commandId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))) { "INVALID_COMMAND_ID" }
        require(fileId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "INVALID_FILE_ID" }
        require(file.isFile && file.length() in 44L..(64L * 1024 * 1024)) { "INVALID_ARTIFACT_SIZE" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val base = origin(store.settings().getString("base_url"))
        fun token(forceRenew: Boolean): JSONObject = synchronized(this) {
            val current = store.credentials() ?: throw IllegalStateException("NOT_ENROLLED")
            val expires = runCatching { Instant.parse(current.getString("access_expires_at")) }.getOrDefault(Instant.EPOCH)
            if (forceRenew || expires.isBefore(Instant.now().plusSeconds(120))) renew(base, current) else current
        }
        var credential = token(false)
        for (attempt in 0..1) {
            val connection = URI("$base/api/v1/probe/artifacts/$commandId").toURL().openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer ${credential.getString("access_token")}")
                connection.setRequestProperty("X-Device-ID", credential.getString("device_id"))
                connection.setRequestProperty("X-File-ID", fileId)
                connection.setRequestProperty("X-SHA256", sha)
                connection.setRequestProperty("Content-Type", "audio/wav")
                connection.setRequestProperty("Accept", "application/json")
                connection.setFixedLengthStreamingMode(file.length())
                file.inputStream().use { input -> connection.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("UPLOAD_INTERRUPTED")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                } }
                val status = connection.responseCode
                val input = if (status in 200..299) connection.inputStream else connection.errorStream
                val output = ByteArrayOutputStream()
                input?.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        check(output.size() + n <= 64 * 1024) { "API_RESPONSE_TOO_LARGE" }
                        output.write(buffer, 0, n)
                    }
                }
                val response = runCatching { JSONObject(output.toString("UTF-8")) }.getOrElse { JSONObject() }
                if (status == 401 && attempt == 0) { credential = token(true); continue }
                if (status !in 200..299) throw ApiException(status,
                    response.optString("error").takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "HTTP_$status")
                check(response.optString("file_id") == fileId && response.optString("sha256") == sha && response.optLong("size_bytes", -1) == file.length()) { "ARTIFACT_RECEIPT_MISMATCH" }
                return response
            } finally { connection.disconnect() }
        }
        throw ApiException(401, "INVALID_ACCESS_TOKEN")
    }

    /** Durable outbox: remove nothing until the server acknowledges these exact IDs. */
    @Synchronized fun flush() {
        for (kind in listOf("results", "events", "logs")) {
            val candidates = store.pending(kind, if (kind == "events") 2 else 20)
            val items = JSONArray()
            var bytes = 512
            for (i in 0 until candidates.length()) {
                val item = candidates.getJSONObject(i)
                val size = item.toString().toByteArray(Charsets.UTF_8).size + 1
                if (items.length() > 0 && bytes + size > MAX_BYTES - 1024) break
                check(bytes + size <= MAX_BYTES - 1024) { "OUTBOX_ITEM_TOO_LARGE" }
                items.put(item); bytes += size
            }
            if (items.length() == 0) continue
            if (kind == "logs") for (i in 0 until items.length()) items.getJSONObject(i).put("device_id", store.deviceId())
            val response = authenticated("/api/v1/probe/$kind", JSONObject().put("device_id", store.deviceId()).put(kind, items))
            val field = when (kind) { "results" -> "command_id"; "events" -> "event_id"; else -> "log_id" }
            val sent = (0 until items.length()).map { items.getJSONObject(it).getString(field) }.toSet()
            val acknowledged = response.optJSONArray("accepted") ?: JSONArray()
            val filtered = JSONArray()
            for (i in 0 until acknowledged.length()) if (acknowledged.optString(i) in sent) filtered.put(acknowledged.getString(i))
            store.acknowledge(kind, filtered)
        }
    }
    private fun authenticated(path: String, payload: JSONObject): JSONObject {
        val base = origin(store.settings().getString("base_url"))
        var credential = store.credentials() ?: throw IllegalStateException("NOT_ENROLLED")
        val expiry = runCatching { Instant.parse(credential.getString("access_expires_at")) }.getOrDefault(Instant.EPOCH)
        if (expiry.isBefore(Instant.now().plusSeconds(60))) credential = renew(base, credential)
        return try { request(base, path, payload, credential.getString("access_token")) }
        catch (e: ApiException) {
            if (e.httpStatus != 401) throw e
            credential = renew(base, credential)
            request(base, path, payload, credential.getString("access_token"))
        }
    }
    private fun renew(base: String, current: JSONObject): JSONObject {
        val response = request(base, "/api/v1/probe/token", JSONObject().put("device_id", current.getString("device_id")).put("refresh_token", current.getString("refresh_token")))
        require(response.getString("device_id") == current.getString("device_id")) { "DEVICE_ID_MISMATCH" }
        // Merge allows a renewable nonrotating refresh credential in the reference server.
        val updated = JSONObject(current.toString())
        response.keys().forEach { updated.put(it, response.get(it)) }
        store.saveCredentials(updated)
        return updated
    }
    private fun request(base: String, path: String, body: JSONObject, bearer: String? = null): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "API_PAYLOAD_TOO_LARGE" }
        val connection = URI(base + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use {
                val buffer = ByteArray(8192)
                while(true) {
                    val n = it.read(buffer)
                    if(n < 0) break
                    if(output.size() + n > MAX_BYTES) throw IllegalStateException("API_RESPONSE_TOO_LARGE")
                    output.write(buffer, 0, n)
                }
            }
            val response = runCatching { JSONObject(output.toString("UTF-8")) }.getOrElse { JSONObject() }
            if(status !in 200..299) {
                val code = response.optString("error").takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "HTTP_$status"
                throw ApiException(status, code)
            }
            return response
        } finally { connection.disconnect() }
    }
    companion object {
        private const val MAX_BYTES = 1_048_576
        fun origin(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) { "HTTPS_ORIGIN_REQUIRED" }
            require(uri.port == -1 || uri.port in 1..65535) { "INVALID_PORT" }
            return uri.toString().trimEnd('/')
        }
    }
}

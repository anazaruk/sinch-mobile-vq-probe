package com.sinch.vqprobe.logging

import com.sinch.vqprobe.storage.ProbeStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class ProbeLogger(private val store: ProbeStore) {
    fun write(component: String, event: String, data: JSONObject = JSONObject(), severity: String = "INFO", commandId: String? = null, callId: String? = null) {
        store.addLog(JSONObject().put("log_id", UUID.randomUUID().toString()).put("timestamp", Instant.now().toString())
            .put("device_id", store.deviceId() ?: JSONObject.NULL).put("command_id", commandId ?: JSONObject.NULL)
            .put("call_id", callId ?: JSONObject.NULL).put("component", component).put("severity", severity)
            .put("event", event).put("data", redact(data)))
    }
    fun recent(limit: Int = 100): String = store.recentLogs(limit).toString(2)
    private fun redact(value: Any?): Any? = when(value) {
        is JSONObject -> JSONObject().apply { value.keys().forEach { key ->
            put(key, if (Regex("token|secret|password|authorization|credential|enrollment_code|url", RegexOption.IGNORE_CASE).containsMatchIn(key)) "[REDACTED]" else redact(value.opt(key)))
        } }
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(redact(value.opt(i))) }
        else -> value
    }
}

package com.sinch.vqprobe.audio

import com.sinch.vqprobe.ProbeGraph
import com.sinch.vqprobe.service.ProbeService
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioOperationException(val code: String, val details: JSONObject = JSONObject()) : IllegalStateException(code)

/** Primitive audio operations only; test order and timing remain on the orchestrator. */
class AudioController(private val graph: ProbeGraph) {
    val tx = AudioInjectionController(graph)
    val rx = CallAudioCaptureController(graph)
    private val diagnostics = AudioDiagnostics(graph)
    private val callContexts = Collections.synchronizedMap(object : LinkedHashMap<String, JSONObject>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean = size > 32
    })
    private val recovered = AtomicBoolean(false)
    private val cleanup = Executors.newFixedThreadPool(2)
    init {
        graph.telecom.onProbeCallStateChanged = { state ->
            if (state.optString("state") !in listOf("ACTIVE")) {
                // Do not block the Telecom main-thread callback while AudioRecord stops/finalizes.
                cleanup.execute { if (graph.telecom.status().optString("state") != "ACTIVE") tx.stop("CALL_NOT_ACTIVE") }
                cleanup.execute { if (graph.telecom.status().optString("state") != "ACTIVE") rx.stop("CALL_NOT_ACTIVE") }
            }
        }
    }
    fun callContext(): JSONObject {
        val call = graph.telecom.status()
        if (call.optString("state") != "ACTIVE" || !call.optBoolean("probe_call_owned")) {
            throw AudioOperationException("ACTIVE_OWNED_CALL_REQUIRED", JSONObject().put("call", call))
        }
        val id = call.getString("call_id")
        callContexts.putIfAbsent(id, call)
        return call
    }
    fun ensureCaptureForeground(active: Boolean) = ProbeService.setCaptureForeground(graph.context, active)
    fun status(): JSONObject = JSONObject().put("tx", tx.status()).put("rx", rx.status())
        .put("recovery_error", graph.store.getMeta("audio_recovery_error") ?: JSONObject.NULL)
        .put("physical_audio_validation", "NOT_PERFORMED_BY_APP")
    fun diagnostics(probeDownlink: Boolean = false): JSONObject = diagnostics.snapshot(probeDownlink)
    fun emit(commandId: String, callId: String, event: String, data: JSONObject) {
        val details = JSONObject(data.toString()).put("device_id", graph.store.deviceId() ?: JSONObject.NULL)
            .put("command_id", commandId).put("call_id", callId)
        if (!details.has("call_command_id")) {
            val activeCall = graph.telecom.status()
            val original = callContexts[callId]?.optString("command_id") ?: activeCall.takeIf { it.optString("call_id") == callId }?.optString("command_id")
            if (original != null) details.put("call_command_id", original)
        }
        // Compact telemetry accompanies each operation; do not duplicate all neighboring cells.
        val sub = callContexts[callId]?.optInt("subscription_id", -1)?.takeIf { it >= 0 }
            ?: details.optInt("subscription_id", -1).takeIf { it >= 0 }
        val radio = graph.telemetry.snapshot(sub)
        val summary = JSONObject()
        for (key in listOf("carrier", "mcc", "mnc", "voice_network_type", "data_network_type", "signal", "timestamp")) {
            summary.put(key, radio.opt(key) ?: JSONObject.NULL)
        }
        val cells = radio.optJSONArray("cells")
        if (cells != null) for (i in 0 until cells.length()) {
            val cell = cells.optJSONObject(i) ?: continue
            if (cell.optBoolean("registered")) { summary.put("serving_cell", cell); break }
        }
        details.put("radio", summary)
        val eventId = details.optString("audio_event_id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        graph.store.enqueueEvent(JSONObject().put("event_id", eventId)
            .put("timestamp", Instant.now().toString()).put("event", event)
            .put("command_id", commandId).put("call_id", callId).put("data", details))
        graph.log.write("audio", event, JSONObject().put("file_id", details.opt("file_id") ?: JSONObject.NULL)
            .put("error", details.opt("error") ?: JSONObject.NULL), if (event.endsWith("FAILED")) "ERROR" else "INFO", commandId, callId)
    }
    fun recover() {
        if (!recovered.compareAndSet(false, true)) return
        graph.store.putMeta("audio_recovery_error", null)
        for ((component, action) in listOf("tx" to { tx.recover() }, "rx" to { rx.recover() })) {
            try { action() } catch (error: Exception) {
                graph.store.putMeta("audio_recovery_error", "${component.uppercase()}_RECOVERY_FAILED")
                graph.log.write("audio", "RECOVERY_FAILED", JSONObject().put("component", component)
                    .put("exception_type", error.javaClass.simpleName), "ERROR")
            }
        }
    }
}

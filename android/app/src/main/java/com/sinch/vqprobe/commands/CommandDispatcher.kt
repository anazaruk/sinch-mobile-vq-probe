package com.sinch.vqprobe.commands

import android.content.Intent
import com.sinch.vqprobe.ProbeGraph
import com.sinch.vqprobe.audio.AudioOperationException
import com.sinch.vqprobe.service.ProbeService
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scheduling belongs to the server. This dispatcher executes one primitive per command. */
class CommandDispatcher(private val graph: ProbeGraph) {
    private val workers = Executors.newFixedThreadPool(2)
    private val fileWorker = Executors.newSingleThreadExecutor()
    private val audioStarter = Executors.newFixedThreadPool(2)
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val recovered = AtomicBoolean(false)
    fun recover() {
        if (!recovered.compareAndSet(false, true)) return
        graph.telecom.recover()
        graph.audio.recover()
        for (id in graph.store.interruptedCommands()) {
            // Side effects may have happened before a crash; never replay a CALL to find out.
            graph.store.completeCommand(id, "FAILED", JSONObject().put("outcome_unknown", true), "EXECUTION_INTERRUPTED")
        }
    }
    fun execute(command: JSONObject) {
        if (command.optString("action") == "NONE") return
        val id = command.optString("command_id")
        if (!id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))) {
            graph.log.write("commands", "INVALID_COMMAND_ID", severity = "ERROR")
            return
        }
        when (graph.store.claim(command)) {
            "DUPLICATE" -> return
            "CONFLICT" -> {
                graph.log.write("commands", "COMMAND_ID_CONFLICT", commandId = id, severity = "ERROR")
                return
            }
        }
        val executor = when(command.optString("action")) {
            "DOWNLOAD_FILE", "DELETE_FILE", "UPLOAD_FILE", "UPLOAD_AUDIO" -> fileWorker
            "PLAY_AUDIO", "START_RECORDING" -> audioStarter
            else -> workers
        }
        executor.execute work@{
            val action = command.optString("action")
            graph.store.putMeta("current_command_id", id)
            graph.log.write("commands", "STARTED", JSONObject().put("action", action), commandId = id)
            try {
                val expiry = command.optString("expires_at").takeIf { it.isNotBlank() && it != "null" }
                if (expiry != null) require(Instant.parse(expiry).isAfter(Instant.now())) { "COMMAND_EXPIRED" }
                val parameters = command.optJSONObject("parameters") ?: JSONObject()
                if (action == "WAIT") {
                    val delay = parameters.getLong("duration_ms")
                    require(delay in 0..3_600_000) { "INVALID_WAIT_DURATION" }
                    timers.schedule({ finish(id, "SUCCESS", JSONObject().put("waited_ms", delay)) }, delay, TimeUnit.MILLISECONDS)
                    return@work
                }
                val data = when (action) {
                    "CALL" -> graph.telecom.placeCall(id, parameters)
                    "HANGUP" -> graph.telecom.hangup(parameters)
                    "GET_STATUS" -> graph.status()
                    "GET_RADIO_STATS" -> graph.telemetry.snapshot(parameters.optInt("subscription_id", graph.store.settings().optJSONObject("config")?.optInt("subscription_id", -1) ?: -1).takeIf { it >= 0 })
                    "DOWNLOAD_FILE" -> graph.files.download(parameters)
                    "DELETE_FILE" -> if (parameters.optString("kind") == "recording") graph.artifacts.delete(parameters.getString("file_id")) else graph.files.delete(parameters)
                    "PLAY_AUDIO" -> graph.audio.tx.start(id, parameters)
                    "STOP_AUDIO" -> graph.audio.tx.stop("COMMAND")
                    "START_RECORDING" -> graph.audio.rx.start(id, parameters)
                    "STOP_RECORDING" -> graph.audio.rx.stop("COMMAND")
                    "GET_AUDIO_STATUS" -> graph.audio.status()
                    "GET_AUDIO_DIAGNOSTICS" -> {
                        require(!parameters.has("probe_downlink") || parameters.opt("probe_downlink") is Boolean) { "INVALID_AUDIO_PARAMETERS" }
                        graph.audio.diagnostics(parameters.optBoolean("probe_downlink", false))
                    }
                    "UPLOAD_FILE", "UPLOAD_AUDIO" -> upload(id, parameters)
                    "REBOOT_APP" -> JSONObject().put("restarted", "agent_loop").put("process_reboot", false)
                    "SET_NETWORK_MODE" -> throw IllegalStateException("NOT_IMPLEMENTED")
                    else -> throw IllegalArgumentException("UNKNOWN_ACTION")
                }
                finish(id, "SUCCESS", data)
                if (action == "REBOOT_APP") graph.context.startService(Intent(graph.context, ProbeService::class.java).setAction(ProbeService.ACTION_RESTART_AGENT))
            } catch (e: AudioOperationException) {
                finish(id, "FAILED", e.details, e.code)
            } catch (e: Exception) {
                val code = e.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "COMMAND_FAILED"
                finish(id, "FAILED", JSONObject().put("exception_type", e.javaClass.simpleName), code)
            }
        }
    }
    private fun upload(commandId: String, parameters: JSONObject): JSONObject {
        val fileId = parameters.getString("file_id")
        require(!commandId.startsWith("local-")) { "UPLOAD_REQUIRES_ORCHESTRATOR_COMMAND" }
        val file = graph.artifacts.beginUpload(fileId, commandId)
        var success = false
        try {
            val receipt = graph.api.uploadArtifact(commandId, fileId, file)
            success = true
            return receipt.put("recording", graph.artifacts.metadata(fileId) ?: JSONObject.NULL)
        } finally { graph.artifacts.endUpload(fileId, commandId, success) }
    }
    private fun finish(id: String, status: String, data: JSONObject, error: String? = null) {
        graph.store.completeCommand(id, status, data, error)
        graph.log.write("commands", "COMPLETED", JSONObject().put("status", status).put("error", error ?: JSONObject.NULL), if (status == "FAILED") "ERROR" else "INFO", id)
        if (graph.store.getMeta("current_command_id") == id) graph.store.putMeta("current_command_id", null)
    }
    fun shutdown() { workers.shutdownNow(); fileWorker.shutdownNow(); audioStarter.shutdownNow(); timers.shutdownNow() }
}

package com.sinch.vqprobe

import android.app.Application
import android.content.Context
import com.sinch.vqprobe.audio.AudioArtifactStore
import com.sinch.vqprobe.audio.AudioController
import com.sinch.vqprobe.api.ApiClient
import com.sinch.vqprobe.commands.CommandDispatcher
import com.sinch.vqprobe.files.FileCache
import com.sinch.vqprobe.logging.ProbeLogger
import com.sinch.vqprobe.storage.ProbeStore
import com.sinch.vqprobe.telecom.TelecomController
import com.sinch.vqprobe.telemetry.RadioCollector
import org.json.JSONObject
import java.time.Instant

class ProbeApp : Application() {
    val graph: ProbeGraph by lazy { ProbeGraph(this) }
}
fun Context.graph(): ProbeGraph = (applicationContext as ProbeApp).graph

class ProbeGraph(val context: Context) {
    val store = ProbeStore(context)
    val log = ProbeLogger(store)
    val api = ApiClient(store)
    val telemetry = RadioCollector(context)
    val telecom = TelecomController(context, store, log, telemetry)
    val files = FileCache(context, store)
    val artifacts = AudioArtifactStore(context, store)
    val audio: AudioController by lazy { AudioController(this) }
    val commands = CommandDispatcher(this)
    fun status(): JSONObject {
        val settings = store.settings()
        val config = settings.optJSONObject("config") ?: JSONObject()
        val sub = config.optInt("subscription_id", -1).takeIf { it >= 0 }
        val radio = telemetry.snapshot(sub)
        val call = telecom.status()
        val connection = store.getMeta("connection_state") ?: "OFFLINE"
        val callState = call.optString("state", "IDLE")
        val state = when {
            callState !in listOf("", "IDLE", "DISCONNECTED", "NONE") -> "CALLING"
            !(store.getMeta("service_error").isNullOrBlank()) || !(store.getMeta("upload_error").isNullOrBlank()) -> "ERROR"
            connection == "ONLINE" -> "ONLINE"
            else -> "OFFLINE"
        }
        val lastResult = store.lastCallResult()?.apply {
            put("radio_sample_count", optJSONArray("radio_samples")?.length() ?: 0)
            remove("radio_samples")
        }
        return JSONObject().put("device_id", store.deviceId() ?: JSONObject.NULL)
            .put("device_name", settings.optString("device_name")).put("app_version", BuildConfig.VERSION_NAME)
            .put("state", state).put("assigned_carrier", config.opt("carrier") ?: JSONObject.NULL)
            .put("site_id", config.opt("site_id") ?: JSONObject.NULL).put("timestamp", Instant.now().toString())
            .put("telemetry", radio).put("call", call).put("carrier", radio.opt("carrier") ?: JSONObject.NULL)
            .put("sim_state", radio.opt("sim_state") ?: JSONObject.NULL).put("mcc", radio.opt("mcc") ?: JSONObject.NULL)
            .put("mnc", radio.opt("mnc") ?: JSONObject.NULL).put("voice_registration", radio.opt("voice_registration") ?: JSONObject.NULL)
            .put("radio_access_technology", radio.opt("voice_network_type") ?: JSONObject.NULL)
            .put("battery_level", radio.opt("battery_level") ?: JSONObject.NULL).put("charging", radio.opt("charging") ?: JSONObject.NULL)
            .put("current_command", store.getMeta("current_command_id") ?: JSONObject.NULL)
            .put("last_command_id", store.getMeta("last_command_id") ?: JSONObject.NULL)
            .put("orchestrator_connection", connection).put("last_poll_at", store.getMeta("last_poll_at") ?: JSONObject.NULL)
            .put("last_call_result", lastResult ?: JSONObject.NULL)
            .put("service_error", store.getMeta("service_error") ?: JSONObject.NULL)
            .put("upload_error", store.getMeta("upload_error") ?: JSONObject.NULL)
            .put("audio", audio.status())
    }
}

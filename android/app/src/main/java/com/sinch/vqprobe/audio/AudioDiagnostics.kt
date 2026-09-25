package com.sinch.vqprobe.audio

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.sinch.vqprobe.ProbeGraph
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class AudioDiagnostics(private val graph: ProbeGraph) {
    @Suppress("DEPRECATION")
    fun snapshot(probeDownlink: Boolean = false): JSONObject {
        val context = graph.context
        val manager = context.getSystemService(AudioManager::class.java)
        val unavailable = JSONObject()
        if (manager == null) unavailable.put("audio_manager", "SERVICE_UNAVAILABLE")
        val permissions = JSONObject()
        val names = listOf(Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.CAPTURE_AUDIO_OUTPUT,
            Manifest.permission.READ_PRECISE_PHONE_STATE, "android.permission.READ_PRIVILEGED_PHONE_STATE",
            Manifest.permission.MODIFY_AUDIO_SETTINGS, Manifest.permission.RECORD_AUDIO)
        for (permission in names) permissions.put(permission, context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        val outputs = runCatching { manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrElse {
            unavailable.put("outputs", it.javaClass.simpleName); null
        }
        val inputs = runCatching { manager?.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrElse {
            unavailable.put("inputs", it.javaClass.simpleName); null
        }
        val outputsAt = Instant.now().toString()
        val txAvailable = outputs?.any { it.type == AudioDeviceInfo.TYPE_TELEPHONY && it.isSink }
        val roleManager = context.getSystemService(RoleManager::class.java)
        val dialer = runCatching { roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) }.getOrElse {
            unavailable.put("default_dialer_role", it.javaClass.simpleName); null
        }
        val initialization = graph.audio.rx.downlinkInitialization(probeDownlink)
        val currentCall = graph.telecom.status()
        val observedAt = Instant.now().toString()
        val result = JSONObject().put("timestamp", observedAt).put("observed_at", observedAt)
            .put("android_release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("build_id", Build.ID).put("build_fingerprint", Build.FINGERPRINT)
            .put("installed_as_system_app", context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            .put("default_dialer_role", dialer ?: JSONObject.NULL)
            .put("permissions", permissions)
            .put("outputs", outputs?.let { JSONArray().apply { it.forEach { value -> put(device(value)) } } } ?: JSONObject.NULL)
            .put("inputs", inputs?.let { JSONArray().apply { it.forEach { value -> put(device(value)) } } } ?: JSONObject.NULL)
            .put("outputs_observed_at", outputsAt)
            .put("call", currentCall).put("call_id", currentCall.opt("call_id") ?: JSONObject.NULL)
            .put("call_command_id", currentCall.opt("command_id") ?: JSONObject.NULL)
            .put("voice_downlink_initialization", initialization).put("audio", graph.audio.status())
            .put("unavailable", unavailable)
        fun observe(name: String, action: () -> Any?) {
            val value = runCatching { action() }.getOrElse { unavailable.put(name, it.javaClass.simpleName); null }
            result.put(name, value ?: JSONObject.NULL)
        }
        observe("audio_mode") { manager?.mode }
        observe("audio_mode_name") { manager?.mode?.let { modeName(it) } }
        observe("microphone_muted") { manager?.isMicrophoneMute }
        observe("speakerphone_on") { manager?.isSpeakerphoneOn }
        result.put("capabilities", JSONObject().put("telephony_tx_available", txAvailable ?: JSONObject.NULL)
            .put("telephony_tx_scope", "ENUMERATED_NOW_REPEAT_DURING_ACTIVE_CALL")
            .put("telephony_tx_observed_at", outputsAt)
            .put("privileged_permissions_ok", names.take(3).all { permissions.optBoolean(it) })
            .put("tx_permission_ok", permissions.optBoolean(Manifest.permission.MODIFY_PHONE_STATE))
            .put("rx_permissions_ok", permissions.optBoolean(Manifest.permission.CAPTURE_AUDIO_OUTPUT) && permissions.optBoolean(Manifest.permission.RECORD_AUDIO))
            .put("default_dialer_role", dialer ?: JSONObject.NULL)
            .put("voice_downlink_initialization_available", initialization.opt("available") ?: JSONObject.NULL)
            .put("incall_music_supported", JSONObject.NULL)
            .put("voice_downlink_capture_available", JSONObject.NULL)
            .put("reason", "Initialization tests only allocate/release AudioRecord. Routing, non-silent downlink, and far-end signal verification require the physical acceptance test."))
        return result
    }
    companion object {
        fun device(value: AudioDeviceInfo?): Any = value?.let {
            JSONObject().put("id", it.id).put("type", it.type).put("type_name", typeName(it.type))
                .put("is_sink", it.isSink).put("is_source", it.isSource).put("address", it.address)
                .put("product_name", it.productName?.toString() ?: JSONObject.NULL)
                .put("sample_rates", JSONArray(it.sampleRates.toList())).put("channel_counts", JSONArray(it.channelCounts.toList()))
                .put("encodings", JSONArray(it.encodings.toList()))
                .put("empty_profiles_mean", "DYNAMIC_OR_UNREPORTED_NOT_A_PROOF_OF_SUPPORT")
        } ?: JSONObject.NULL
        fun typeName(type: Int): String = when(type) {
            AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
            else -> "TYPE_$type"
        }
        fun modeName(mode: Int) = when(mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "MODE_$mode"
        }
    }
}

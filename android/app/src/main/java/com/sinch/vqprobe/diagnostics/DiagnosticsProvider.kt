package com.sinch.vqprobe.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.sinch.vqprobe.BuildConfig
import com.sinch.vqprobe.graph
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** USB/ADB engineering bridge: no call control, credentials, file reads or remote listener. */
class DiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call does not itself enforce readPermission on every Android version.
        // Check the original Binder identity before clearing it for app-owned framework calls.
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != 2000) throw SecurityException("ADB_SHELL_OR_ROOT_REQUIRED")
        require(method in setOf("snapshot", "probe_downlink")) { "UNSUPPORTED_DIAGNOSTIC_METHOD" }
        require(arg == null && (extras == null || extras.isEmpty)) { "DIAGNOSTIC_ARGUMENTS_NOT_SUPPORTED" }
        val identity = Binder.clearCallingIdentity()
        try {
            val graph = requireNotNull(context).graph()
            val diagnostics = graph.audio.diagnostics(method == "probe_downlink")
            val audio = graph.audio.status()
            val artifacts = graph.artifacts.diagnosticMetadata()
            val result = JSONObject().put("schema_version", 1).put("generated_at", Instant.now().toString())
                .put("app", JSONObject().put("package", BuildConfig.APPLICATION_ID)
                    .put("version", BuildConfig.VERSION_NAME).put("version_code", BuildConfig.VERSION_CODE)
                    .put("process_uid", android.os.Process.myUid()))
                .put("diagnostics", diagnostics).put("call_result", graph.store.lastCallResult() ?: JSONObject.NULL)
                .put("tx", audio.getJSONObject("tx")).put("rx", audio.getJSONObject("rx"))
                .put("recording_artifacts", artifacts.getJSONArray("items"))
                .put("recording_artifacts_total", artifacts.getInt("total"))
                .put("recording_artifacts_truncated", artifacts.getBoolean("truncated"))
                .put("local_logs", graph.store.recentLogs(80))
                .put("physical_audio_validation", "NOT_PERFORMED_BY_APP")
            // Keep the Binder transaction bounded. Full radio/call results remain available
            // through authenticated orchestrator events. Every omission is explicit.
            if (bytes(result) > MAX_BYTES) {
                result.put("local_logs", JSONArray()).put("local_logs_omitted", "BINDER_SIZE_LIMIT")
                val call = result.optJSONObject("call_result")
                val samples = call?.optJSONArray("radio_samples")
                if (samples != null && samples.length() > 2) {
                    call.put("radio_samples", JSONArray().put(samples.get(0)).put(samples.get(samples.length() - 1)))
                        .put("bridge_radio_samples_omitted", samples.length() - 2)
                }
            }
            if (bytes(result) > MAX_BYTES) {
                result.put("recording_artifacts", JSONArray()).put("recording_artifacts_truncated", true)
                    .put("recording_artifacts_omitted", "BINDER_SIZE_LIMIT")
            }
            check(bytes(result) <= MAX_BYTES) { "DIAGNOSTIC_SNAPSHOT_TOO_LARGE" }
            return Bundle().apply { putString("json", result.toString()) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("USE_DIAGNOSTIC_CALL")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("READ_ONLY_DIAGNOSTICS")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("READ_ONLY_DIAGNOSTICS")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("READ_ONLY_DIAGNOSTICS")
    private fun bytes(value: JSONObject) = value.toString().toByteArray(Charsets.UTF_8).size
    companion object { private const val MAX_BYTES = 320 * 1024 }
}

package com.sinch.vqprobe.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sinch.vqprobe.graph
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** Explicit engineering controls; this screen is not a local test-sequence engine. */
class AudioDiagnosticsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var reference: EditText
    private lateinit var recording: EditText
    private lateinit var mute: CheckBox
    private lateinit var loop: CheckBox
    private lateinit var digitalValidation: CheckBox
    private var commandId: String? = null
    private var resumed = false
    private val refresh = object : Runnable {
        override fun run() { if (resumed) { render(); main.postDelayed(this, 1000) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { fitsSystemWindows = true; addView(body) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets
        }
        setContentView(scroll)
        text("AUDIO DIAGNOSTICS", 23f)
        text("Digital telephony TX / RX • engineering validation", 14f)
        status = text("Loading…", 15f)
        button("Grant Audio Recording Permission") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
        text("Privileged permissions must be granted by the system installation. Start the probe agent and establish an owned ACTIVE call before audio operations.", 13f)
        reference = field("Cached reference file_id", "reference01")
        mute = CheckBox(this).apply { text = "Mute physical microphone during injection"; isChecked = true; body.addView(this) }
        digitalValidation = CheckBox(this).apply {
            text = "DIGITAL_TX_VALIDATION (verified microphone mute)"; isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (checked) mute.isChecked = true
                mute.isEnabled = !checked
            }
            body.addView(this)
        }
        mute.isEnabled = false
        loop = CheckBox(this).apply { text = "Loop reference WAV until stopped"; body.addView(this) }
        button("Play Audio to Telephony TX") { command("PLAY_AUDIO", JSONObject().put("file_id", reference.text.toString().trim())
            .put("loop", loop.isChecked).put("mute_microphone", mute.isChecked)
            .put("mode", if (digitalValidation.isChecked) "DIGITAL_TX_VALIDATION" else "NORMAL")) }
        button("Stop Audio") { command("STOP_AUDIO") }
        recording = field("New recording file_id", "rx-${System.currentTimeMillis()}")
        button("Start DOWNLINK Recording • 16 kHz") { command("START_RECORDING", JSONObject().put("direction", "DOWNLINK").put("file_id", recording.text.toString().trim()).put("sample_rate", 16000)) }
        button("Stop Recording") { command("STOP_RECORDING") }
        text("To upload a recording, queue UPLOAD_FILE from the orchestrator with its file_id. Framework route/source checks alone do not prove that the far end received digital audio.", 13f)
        button("Refresh Audio Diagnostics") { loadDiagnostics() }
        button("Probe VOICE_DOWNLINK Initialization") { loadDiagnostics(probeDownlink = true) }
        detail = text("", 12f).apply { setTextIsSelectable(true) }
        loadDiagnostics()
    }
    private fun text(value: String, size: Float): TextView = TextView(this).apply {
        text = value; textSize = size; setPadding(0, 10, 0, 10); body.addView(this)
    }
    private fun field(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText; setText(value); inputType = InputType.TYPE_CLASS_TEXT; body.addView(this)
    }
    private fun button(title: String, callback: () -> Unit) { body.addView(Button(this).apply { text = title; setOnClickListener { callback() } }) }
    private fun command(action: String, parameters: JSONObject = JSONObject()) {
        val id = "local-${UUID.randomUUID()}"
        commandId = id
        val now = Instant.now()
        graph().commands.execute(JSONObject().put("command_id", id).put("action", action).put("parameters", parameters)
            .put("issued_at", now.toString()).put("expires_at", now.plusSeconds(300).toString()))
        render()
    }
    private fun render() {
        val value = graph().audio.status()
        val result = commandId?.let { graph().store.getCommand(it)?.optJSONObject("result") }
        status.text = "Call: ${graph().telecom.status().optString("state")}\n${value.toString(2)}\nLast command: ${result?.toString(2) ?: "—"}"
    }
    private fun loadDiagnostics(probeDownlink: Boolean = false) {
        worker.execute {
            val value = runCatching { graph().audio.diagnostics(probeDownlink).toString(2) }.getOrElse { "Diagnostics failed: ${it.javaClass.simpleName}" }
            main.post { if (!isDestroyed) detail.text = value }
        }
    }
    override fun onResume() { super.onResume(); resumed = true; main.post(refresh) }
    override fun onPause() { resumed = false; main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy() }
}

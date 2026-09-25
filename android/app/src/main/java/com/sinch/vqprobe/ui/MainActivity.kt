package com.sinch.vqprobe.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sinch.vqprobe.graph
import com.sinch.vqprobe.service.ProbeService
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Small engineering console; scenarios, schedules, and call retries belong to the orchestrator. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-console").apply { isDaemon = true }
    }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var setupStatus: TextView
    private lateinit var baseUrl: EditText
    private lateinit var deviceName: EditText
    private lateinit var enrollmentCode: EditText
    private lateinit var manualNumber: EditText
    private lateinit var enrollButton: Button
    private var localCommandId: String? = null
    private var resumed = false
    private var refreshing = false
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshStatus()
            main.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(14, 31, 54)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        // Target 35 is edge-to-edge: keep controls clear of system bars and the keyboard.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        label("SINCH MOBILE VQ PROBE", 24f)
        label("Native SIM calls • digital audio engineering console", 14f)
        status = label("Loading probe status…", 15f).apply { setTextIsSelectable(true) }
        setupStatus = label("", 13f)

        label("Enrollment", 20f)
        val settings = graph().store.settings()
        baseUrl = field("HTTPS orchestrator origin", settings.optString("base_url"), InputType.TYPE_TEXT_VARIATION_URI)
        deviceName = field("Device name", settings.optString("device_name", "NJ-VZ-001"))
        enrollmentCode = field("One-use enrollment code", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { isSaveEnabled = false }
        enrollButton = button("Enroll Device") { enroll() }
        button("Request Default Dialer Role") { requestDialer() }
        button("Grant Phone / Radio Permissions") { requestProbePermissions() }
        button("Background Radio Access") { backgroundLocation() }
        button("Battery / Startup Settings") { batterySettings() }

        label("Probe control", 20f)
        button("Start Agent") { enableAndStart(false) }
        button("Stop Agent") {
            graph().store.saveSettings(graph().store.settings().put("enabled", false))
            stopService(Intent(this, ProbeService::class.java))
            showMessage("Agent stopped. An existing call remains under Telecom control; use Hang Up to end it.")
            refreshStatus()
        }
        button("Poll Now") { enableAndStart(true) }
        button("Call Test Number") {
            val number = graph().store.settings().optJSONObject("config")?.optString("test_number").orEmpty()
            if (number.isBlank()) showMessage("Set test_number in the orchestrator device configuration.")
            else executeLocal("CALL", JSONObject().put("number", number))
        }
        button("Hang Up") { executeLocal("HANGUP") }
        button("Refresh Radio Stats") { executeLocal("GET_RADIO_STATS") }
        button("Download Test WAV") { downloadDialog() }
        button("View Logs") { showLogs() }
        button("Audio Diagnostics / TX / RX") { startActivity(Intent(this, AudioDiagnosticsActivity::class.java)) }

        label("Manual dial pad", 20f)
        manualNumber = field("Phone number", "", InputType.TYPE_CLASS_PHONE)
        button("Dial Manually") { manualDial() }
        label("Background cell details require precise location, Allow all the time, and device Location enabled. " +
            "Unavailable measurements do not prevent test calls. Keep this dedicated probe powered and permit " +
            "background operation in the handset's battery settings. Force-stop and OEM restrictions can require reopening the app.", 13f)
        receiveDialIntent(intent)
        updateEnrollmentControls()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveDialIntent(intent)
    }

    private fun receiveDialIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL && intent.data?.scheme == "tel") {
            manualNumber.setText(intent.data?.schemeSpecificPart.orEmpty())
            manualNumber.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (graph().store.deviceId() != null && graph().store.settings().optBoolean("enabled", false)) {
            try { ProbeService.start(this) }
            catch (error: RuntimeException) { ProbeService.reportStartFailure(this, error) }
        }
        main.post(refresh)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        // Let a one-use enrollment finish and persist even if the Activity rotates.
        worker.shutdown()
        super.onDestroy()
    }

    private fun updateEnrollmentControls() {
        val enrolled = graph().store.deviceId() != null
        baseUrl.isEnabled = !enrolled
        deviceName.isEnabled = !enrolled
        enrollmentCode.isEnabled = !enrolled
        enrollButton.isEnabled = !enrolled && !enrollmentInFlight.get()
        enrollButton.text = if (enrolled) "Device Enrolled" else if (enrollmentInFlight.get()) "Enrolling…" else "Enroll Device"
    }

    private fun enroll() {
        if (graph().store.deviceId() != null || enrollmentInFlight.get()) return
        val origin = baseUrl.text.toString().trim().trimEnd('/')
        val code = enrollmentCode.text.toString().trim()
        val name = deviceName.text.toString().trim()
        val uri = try { URI(origin) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.query != null || uri.fragment != null ||
            !uri.path.isNullOrEmpty()) {
            showMessage("Use an HTTPS origin, for example https://vq.example:8443 (no path or credentials).")
            return
        }
        if (code.isBlank() || name.isBlank()) {
            showMessage("Enter the device name and one-use enrollment code.")
            return
        }
        if (!enrollmentInFlight.compareAndSet(false, true)) return
        updateEnrollmentControls()
        enrollmentCode.setText("")
        val appContext = applicationContext
        background {
            try {
                val graph = appContext.graph()
                val credentials = graph.api.enroll(origin, code, name)
                check(credentials.optString("device_id").isNotBlank() &&
                    credentials.optString("access_token").isNotBlank()) { "INVALID_ENROLLMENT_RESPONSE" }
                // ApiClient commits origin/config before device identity, within its enrollment lock.
                graph.log.write("ui", "ENROLLED")
                val started = try {
                    ProbeService.start(appContext)
                    true
                } catch (error: RuntimeException) {
                    ProbeService.reportStartFailure(appContext, error)
                    false
                }
                enrollmentInFlight.set(false)
                main.post {
                    if (isDestroyed) return@post
                    updateEnrollmentControls()
                    refreshStatus()
                    showMessage(if (started) "Enrolled. Grant the dialer role and phone permissions before the first call."
                        else "Enrolled. Tap Start Agent to resume, then grant the dialer role and phone permissions.")
                }
            } catch (error: Exception) {
                enrollmentInFlight.set(false)
                main.post {
                    if (isDestroyed) return@post
                    updateEnrollmentControls()
                    showMessage("Enrollment failed: ${ProbeService.safeError(error)}")
                }
            }
        }
    }

    private fun enableAndStart(pollNow: Boolean) {
        if (graph().store.deviceId() == null) {
            showMessage("Enroll this device first.")
            return
        }
        graph().store.saveSettings(graph().store.settings().put("enabled", true))
        try {
            if (pollNow) ProbeService.pollNow(this) else ProbeService.start(this)
            refreshStatus()
        } catch (error: RuntimeException) {
            ProbeService.reportStartFailure(this, error)
            showMessage("Agent could not start: ${ProbeService.safeError(error)}")
        }
    }

    private fun requestDialer() {
        val roles = getSystemService(RoleManager::class.java)
        when {
            !roles.isRoleAvailable(RoleManager.ROLE_DIALER) -> showMessage("This device does not offer the default dialer role.")
            roles.isRoleHeld(RoleManager.ROLE_DIALER) -> showMessage("Sinch Probe is already the default dialer.")
            else -> try {
                @Suppress("DEPRECATION")
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), ROLE_REQUEST)
            } catch (error: RuntimeException) { showMessage("Role request failed: ${ProbeService.safeError(error)}") }
        }
    }

    private fun requestProbePermissions() {
        val requested = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        val missing = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        // Android 12 requires COARSE and FINE together, including an upgrade from approximate access.
        if (Manifest.permission.ACCESS_FINE_LOCATION in missing && Manifest.permission.ACCESS_COARSE_LOCATION !in missing) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (missing.isEmpty()) {
            showMessage("Phone and foreground radio permissions are granted.")
            return
        }
        AlertDialog.Builder(this).setTitle("Probe permissions")
            .setMessage("Phone permission allows SIM call control. Precise location allows Android to expose serving-cell IDs and signal measurements; no microphone recording is used. Notifications keep probe operation visible.")
            .setPositiveButton("Continue") { _, _ -> requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun backgroundLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showMessage("Grant precise foreground location first using Phone / Radio Permissions.")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            showMessage("Background location is granted. Keep device Location enabled for serving-cell telemetry.")
            return
        }
        AlertDialog.Builder(this).setTitle("Radio telemetry while the screen is off")
            .setMessage("Android gates serving-cell information behind location permission. Choose Permissions → Location → Allow all the time and keep precise location enabled. Calls can still run without this, but cell details may be unavailable.")
            .setPositiveButton(if (Build.VERSION.SDK_INT >= 30) "Open Settings" else "Continue") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) openAppSettings()
                else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), BACKGROUND_PERMISSION_REQUEST)
            }.setNegativeButton("Later", null).show()
    }

    private fun batterySettings() {
        AlertDialog.Builder(this).setTitle("Dedicated-device operation")
            .setMessage("In this phone's battery settings, allow unrestricted background operation for Sinch Probe. Keep the phone charged. After a reboot, unlock the phone once. Force-stop prevents automatic recovery until the app is opened again.")
            .setPositiveButton("Open Settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: RuntimeException) { openAppSettings() }
            }.setNegativeButton("Close", null).show()
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (error: RuntimeException) { showMessage("Open Android Settings → Apps → Sinch Probe manually.") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            showMessage("Some permissions were denied. Open app settings to review them; unavailable telemetry is reported explicitly.")
        }
    }

    private fun executeLocal(action: String, parameters: JSONObject = JSONObject()) {
        val id = "local-${UUID.randomUUID()}"
        localCommandId = id
        val now = Instant.now()
        val command = JSONObject().put("command_id", id).put("action", action).put("parameters", parameters)
            .put("issued_at", now.toString()).put("expires_at", now.plusSeconds(300).toString())
        background {
            try {
                graph().commands.execute(command)
                main.post { if (!isDestroyed) refreshStatus() }
            } catch (error: Exception) {
                main.post { if (!isDestroyed) showMessage("$action failed: ${ProbeService.safeError(error)}") }
            }
        }
    }

    private fun downloadDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun input(hintText: String): EditText = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            fields.addView(this)
        }
        val url = input("HTTPS WAV URL")
        val fileId = input("File ID, e.g. reference01")
        val checksum = input("SHA-256 (64 hexadecimal characters)")
        AlertDialog.Builder(this).setTitle("Download reference WAV").setView(fields)
            .setMessage("The host must be allowed by device configuration. The file is cached only; Phase A does not inject audio into a call.")
            .setPositiveButton("Download") { _, _ ->
                executeLocal("DOWNLOAD_FILE", JSONObject().put("url", url.text.toString().trim())
                    .put("file_id", fileId.text.toString().trim()).put("sha256", checksum.text.toString().trim()))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun manualDial() {
        val number = manualNumber.text.toString().trim()
        if (number.isBlank()) { showMessage("Enter a number."); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestProbePermissions()
            return
        }
        try {
            // Human-initiated default-dialer action; Telecom retains emergency routing and account selection.
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (error: RuntimeException) { showMessage("Dial failed: ${ProbeService.safeError(error)}") }
    }

    private fun refreshStatus() {
        if (refreshing || isDestroyed) return
        refreshing = true
        val localId = localCommandId
        background {
            try {
                val graph = graph()
                val snapshot = graph.status()
                val local = localId?.let { graph.store.getCommand(it) }
                val statusText = formatStatus(snapshot, local)
                val role = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
                val phone = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val precise = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val background = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
                main.post {
                    refreshing = false
                    if (isDestroyed) return@post
                    status.text = statusText
                    setupStatus.text = "Dialer role: ${yesNo(role)}  |  Phone: ${yesNo(phone)}\n" +
                        "Precise location: ${yesNo(precise)}  |  Background: ${yesNo(background)}"
                    updateEnrollmentControls()
                }
            } catch (error: Exception) {
                main.post {
                    refreshing = false
                    if (!isDestroyed) status.text = "ERROR — ${ProbeService.safeError(error)}"
                }
            }
        }
    }

    private fun formatStatus(snapshot: JSONObject, local: JSONObject?): String {
        val store = graph().store
        val radio = snapshot.optJSONObject("telemetry") ?: JSONObject()
        val call = snapshot.optJSONObject("call") ?: JSONObject()
        val config = store.settings().optJSONObject("config") ?: JSONObject()
        fun value(source: JSONObject, vararg keys: String): String =
            keys.firstNotNullOfOrNull { key -> source.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString() }
                ?: "unavailable"
        val connection = store.getMeta("connection_state") ?: "OFFLINE"
        val callState = value(call, "state", "call_state")
        val calling = callState in setOf("RESERVED", "SUBMITTED", "DIALING", "CONNECTING", "ACTIVE", "RINGING", "HOLDING", "DISCONNECTING")
        val indicator = if (calling) "CALLING" else if (store.getMeta("upload_error") != null) "ERROR" else connection
        val last = snapshot.optJSONObject("last_call_result")
        val cells = radio.optJSONArray("cells")
        val serving = if (cells == null) emptyList() else (0 until cells.length())
            .mapNotNull { cells.optJSONObject(it) }.filter { it.optBoolean("registered", false) }
        val bandText = serving.joinToString("; ") { cell ->
            "${value(cell, "rat")} ${value(cell, "bands")}" }.ifBlank { "unavailable" }
        val signal = radio.optJSONObject("signal")
        val measures = signal?.optJSONArray("measurements")
        val signalText = if (measures == null) "unavailable" else (0 until measures.length())
            .mapNotNull { measures.optJSONObject(it) }.joinToString("; ") { sample ->
                "${value(sample, "rat")}: ${value(sample, "dbm")} dBm, level ${value(sample, "level")}/4" }
        return buildString {
            appendLine(indicator)
            appendLine("Device ID: ${store.deviceId() ?: "not enrolled"}")
            appendLine("Carrier: ${value(radio, "operator_name", "carrier", "carrier_name")}")
            appendLine("Assigned carrier: ${value(config, "carrier")}")
            appendLine("SIM State: ${value(radio, "sim_state")}")
            appendLine("Voice Registration: ${value(radio, "voice_registration", "service_state")}")
            appendLine("Network Type: voice ${value(radio, "voice_network_type")}; data ${value(radio, "data_network_type")}")
            appendLine("LTE/NR Band: $bandText")
            appendLine("Signal: $signalText")
            appendLine("Orchestrator Connection: $connection")
            appendLine("Last poll: ${store.getMeta("last_poll_at") ?: "never"}")
            appendLine("Current Command: ${store.getMeta("current_command_id") ?: "none"}")
            appendLine("Call State: $callState")
            appendLine("Last Call Result: ${last?.toString(2)?.take(1800) ?: "none"}")
            store.getMeta("service_error")?.let { appendLine("Agent error: $it") }
            store.getMeta("upload_error")?.let { appendLine("Upload error (outbox retained): $it") }
            if (local != null) {
                val result = local.optJSONObject("result")
                appendLine("Engineering command: ${local.optString("action")} / ${result?.optString("status") ?: local.optString("journal_state")}")
                result?.optString("error")?.takeIf { it.isNotBlank() }?.let { appendLine("Command error: $it") }
                result?.optJSONObject("data")?.let { appendLine(it.toString(2).take(1800)) }
            }
        }
    }

    private fun showLogs() {
        background {
            val logs = graph().log.recent(100)
            main.post {
                if (isDestroyed) return@post
                val text = TextView(this).apply {
                    textSize = 12f
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    text = logs.ifBlank { "No logs yet." }
                    setTextIsSelectable(true)
                }
                AlertDialog.Builder(this).setTitle("Structured probe logs")
                    .setView(ScrollView(this).apply { addView(text) }).setPositiveButton("Close", null).show()
            }
        }
    }

    private fun background(work: () -> Unit) {
        try { worker.execute(work) } catch (_: RejectedExecutionException) { refreshing = false }
    }

    private fun field(hintText: String, initial: String, kind: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            hint = hintText
            inputType = kind
            setSingleLine(true)
            setText(initial)
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    private fun label(value: String, size: Float): TextView = TextView(this).apply {
        text = value
        textSize = size
        setPadding(0, dp(12), 0, dp(8))
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun yesNo(value: Boolean) = if (value) "granted" else "missing"
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val enrollmentInFlight = AtomicBoolean(false)
        private const val ROLE_REQUEST = 200
        private const val PERMISSION_REQUEST = 201
        private const val BACKGROUND_PERMISSION_REQUEST = 202
    }
}

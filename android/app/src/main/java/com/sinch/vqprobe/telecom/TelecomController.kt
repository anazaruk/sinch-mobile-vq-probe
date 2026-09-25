package com.sinch.vqprobe.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.sinch.vqprobe.logging.ProbeLogger
import com.sinch.vqprobe.storage.ProbeStore
import com.sinch.vqprobe.telemetry.RadioCollector
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Default-dialer controller for the platform's existing SIM PhoneAccounts.
 * It never registers a ConnectionService and never originates a SIP/OTT call.
 * All live Call objects and the active snapshot are confined to the main looper.
 */
@SuppressLint("MissingPermission")
class TelecomController(
    private val context: Context,
    private val store: ProbeStore,
    private val log: ProbeLogger,
    private val telemetry: RadioCollector
) {
    private val handler = Handler(Looper.getMainLooper())
    private val sampler = Executors.newSingleThreadExecutor { r -> Thread(r, "probe-call-radio") }
    private val telecomService = context.getSystemService(TelecomManager::class.java)
    private val telephonyService = context.getSystemService(TelephonyManager::class.java)
    private val manager: TelecomManager get() = telecomService ?: fail("TELEPHONY_NOT_AVAILABLE")
    private val phone: TelephonyManager get() = telephonyService ?: fail("TELEPHONY_NOT_AVAILABLE")
    private val callbacks = IdentityHashMap<Call, Call.Callback>()
    private var ownedCall: Call? = null
    private var active: JSONObject? = readJson(ACTIVE_KEY)?.also {
        if (!it.optBoolean("finalized")) it.put("recovery_pending", true).put("timing_continuity", false)
    }
    private var recovered = false
    @Volatile private var publicStatus = JSONObject().put("state", "IDLE")
    var onUiChanged: (() -> Unit)? = null
    var onProbeCallStateChanged: ((JSONObject) -> Unit)? = null

    private data class Account(val handle: PhoneAccountHandle, val subscriptionId: Int)

    /** SUCCESS means submitted to Telecom, not answered. A replay never invokes placeCall. */
    fun placeCall(commandId: String, parameters: JSONObject): JSONObject {
        val number = parameters.optString("number").trim()
        if (!Regex("^\\+[1-9][0-9]{7,14}$").matches(number)) fail("INVALID_E164_NUMBER")
        return onMain {
            receipt(commandId)?.let { previous ->
                if (previous.optString("state") == "SUBMITTED" ||
                    (previous.optString("state") == "FINISHED" && previous.optBoolean("submission_accepted"))) {
                    return@onMain JSONObject().put("call_id", previous.getString("call_id"))
                        .put("accepted", true).put("duplicate", true)
                }
                fail(previous.optString("error").ifBlank { "CALL_SUBMISSION_UNCERTAIN" })
            }
            requireDialerAndPermissions()
            if (active != null || callbacks.keys.any { it.state != Call.STATE_DISCONNECTED } || manager.isInCall) {
                fail("CALL_IN_PROGRESS")
            }
            if (phone.isEmergencyNumber(number)) fail("EMERGENCY_NUMBER_NOT_ALLOWED")
            val account = selectAccount(parameters)
            val before = telemetry.snapshot(account.subscriptionId)
            val callId = UUID.randomUUID().toString()
            val now = Instant.now().toString()
            val snapshot = JSONObject()
                .put("call_id", callId).put("command_id", commandId).put("destination", number)
                .put("subscription_id", account.subscriptionId)
                .put("phone_account", accountFingerprint(account.handle))
                .put("phone_account_component", account.handle.componentName.flattenToShortString())
                .put("state", "RESERVED").put("boot_count", bootCount())
                .put("dial_request_time", now).put("dial_request_epoch_ms", System.currentTimeMillis())
                .put("dial_request_elapsed_ms", SystemClock.elapsedRealtime())
                .put("dialing_time", JSONObject.NULL).put("connected_time", JSONObject.NULL)
                .put("disconnect_time", JSONObject.NULL).put("radio_samples", JSONArray())
                .put("volte_confirmed", JSONObject.NULL)
                .put("volte_availability_reason", "Native SIM calling does not by itself establish the IMS bearer")
                .put("timing_source", "ELAPSED_REALTIME_CALLBACK_OBSERVATIONS")
                .put("timing_continuity", true).put("finalized", false)

            // Tombstone first: a crash anywhere after this write must never originate again.
            store.putMeta(receiptKey(commandId), JSONObject().put("call_id", callId)
                .put("state", "RESERVED").toString())
            active = snapshot
            persist()
            addSample(snapshot, "BEFORE", before)
            for (key in listOf("carrier", "mcc", "mnc", "voice_network_type", "data_network_type", "signal")) {
                snapshot.put(key, before.opt(key) ?: JSONObject.NULL)
            }
            persist()
            emit(snapshot, "DIAL_REQUESTED", JSONObject().put("destination", number)
                .put("subscription_id", account.subscriptionId))
            val outgoing = Bundle().apply { putString(EXTRA_PROBE_CALL_ID, callId) }
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account.handle)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoing)
                putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY)
            }
            try {
                manager.placeCall(Uri.fromParts("tel", number, null), extras)
            } catch (_: SecurityException) {
                finish(snapshot, "FAILED", "CALL_PERMISSION_DENIED", null, false)
                store.putMeta(receiptKey(commandId), JSONObject().put("call_id", callId)
                    .put("state", "FAILED").put("error", "CALL_PERMISSION_DENIED").toString())
                fail("CALL_PERMISSION_DENIED")
            } catch (_: RuntimeException) {
                // A Binder failure can occur after Telecom accepted the call. Preserve uncertainty.
                snapshot.put("state", "UNCERTAIN").put("submission_uncertain", true)
                persist()
                scheduleSubmissionCheck(callId)
                fail("CALL_SUBMISSION_UNCERTAIN")
            }
            snapshot.put("submission_accepted", true)
            if (snapshot.optString("state") == "RESERVED") snapshot.put("state", "SUBMITTED")
            store.putMeta(receiptKey(commandId), JSONObject().put("call_id", callId)
                .put("state", "SUBMITTED").toString())
            persist()
            scheduleSubmissionCheck(callId)
            scheduleSample(callId)
            JSONObject().put("call_id", callId).put("accepted", true)
                .put("subscription_id", account.subscriptionId)
        }
    }

    /** Remote control deliberately cannot terminate an unrelated or unproven call. */
    fun hangup(parameters: JSONObject): JSONObject = onMain {
        val expected = parameters.optString("call_id").takeIf { it.isNotBlank() }
        val snapshot = active
        if (snapshot == null) {
            if (expected != null && store.lastCallResult()?.optString("call_id") != expected) {
                fail("CALL_NOT_FOUND")
            }
            return@onMain JSONObject().put("already_ended", true)
        }
        if (expected != null && snapshot.optString("call_id") != expected) fail("CALL_ID_MISMATCH")
        val call = ownedCall
        if (call == null) {
            // Submission can precede InCallService binding. Queue intent, never terminate
            // a global/unmatched call; only bindOwnedCall may apply this request.
            snapshot.put("hangup_pending", true).put("hangup_requested_time", Instant.now().toString())
            persist()
            return@onMain JSONObject().put("call_id", snapshot.getString("call_id"))
                .put("hangup_queued", true).put("awaiting_call_ownership", true)
        }
        if (call.state != Call.STATE_DISCONNECTED) {
            snapshot.put("hangup_requested_time", Instant.now().toString())
            persist()
            call.disconnect()
        }
        JSONObject().put("call_id", snapshot.getString("call_id")).put("hangup_requested", true)
    }

    /** Local dialer UI may manage an incoming/manual call; remote HANGUP never uses this. */
    fun hangupLocal() = onMain { foregroundCall()?.disconnect(); Unit }
    fun answer() = onMain {
        callbacks.keys.firstOrNull { it.state == Call.STATE_RINGING }?.answer(VideoProfile.STATE_AUDIO_ONLY)
        Unit
    }
    fun reject() = onMain {
        callbacks.keys.firstOrNull { it.state == Call.STATE_RINGING }?.reject(false, null)
        Unit
    }
    fun sendDtmf(character: Char) = onMain {
        if (character in "0123456789*#") foregroundCall()?.let { call ->
            call.playDtmfTone(character)
            handler.postDelayed({ call.stopDtmfTone() }, 180)
        }
        Unit
    }

    fun status(): JSONObject = JSONObject(publicStatus.toString())

    fun onCallAdded(call: Call) = onMain {
        if (callbacks.containsKey(call)) return@onMain
        val callback = object : Call.Callback() {
            override fun onStateChanged(changed: Call, state: Int) { observe(changed, state) }
            override fun onDetailsChanged(changed: Call, details: Call.Details) {
                bindOwnedCall(changed)
                observe(changed, changed.state)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback, handler)
        bindOwnedCall(call)
        observe(call, call.state)
    }

    fun onCallRemoved(call: Call) = onMain {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        if (ownedCall === call) {
            active?.let { snapshot ->
                finish(snapshot, "UNCERTAIN", "REMOVED_WITHOUT_DISCONNECT_CALLBACK", call, false)
            }
            ownedCall = null
        }
        refreshPublicStatus()
    }

    fun onServiceDisconnected() = onMain {
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        ownedCall = null
        val snapshot = active
        if (snapshot != null && !snapshot.optBoolean("finalized")) {
            snapshot.put("state", "RECOVERING").put("recovery_pending", true).put("timing_continuity", false)
            persist()
            val id = snapshot.getString("call_id")
            handler.postDelayed({
                if (active?.optString("call_id") == id && ownedCall == null) {
                    active?.let { finish(it, "UNCERTAIN", "IN_CALL_SERVICE_DISCONNECTED", null, false) }
                }
            }, 15_000)
        }
        refreshPublicStatus()
    }

    /** Called once at agent startup; framework callbacks can arrive before or after it. */
    fun recover() = onMain {
        if (recovered) return@onMain
        recovered = true
        val snapshot = active ?: run { refreshPublicStatus(); return@onMain }
        if (snapshot.optBoolean("finalized")) {
            publishFinal(snapshot)
            return@onMain
        }
        if (snapshot.optInt("boot_count", -1) < 0 || snapshot.optInt("boot_count") != bootCount()) {
            finish(snapshot, "UNCERTAIN", "DEVICE_REBOOT_DURING_CALL", null, false)
            return@onMain
        }
        if (ownedCall != null) return@onMain
        snapshot.put("state", "RECOVERING").put("timing_continuity", false)
            .put("process_recovery_time", Instant.now().toString())
        persist()
        emit(snapshot, "CALL_RECOVERING", JSONObject().put("auto_redial", false))
        val id = snapshot.getString("call_id")
        // Wait for Telecom to rebind InCallService, then report an honest unknown outcome.
        handler.postDelayed({
            if (active?.optString("call_id") == id && ownedCall == null) {
                active?.let { finish(it, "UNCERTAIN", "PROCESS_RESTART_CALL_UNRESOLVED", null, false) }
            }
        }, 15_000)
    }

    private fun requireDialerAndPermissions() {
        if (telecomService == null || telephonyService == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            fail("TELEPHONY_NOT_AVAILABLE")
        }
        if (manager.defaultDialerPackage != context.packageName) fail("DEFAULT_DIALER_REQUIRED")
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            fail("CALL_PERMISSION_DENIED")
        }
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            fail("READ_PHONE_STATE_REQUIRED")
        }
    }

    private fun selectAccount(parameters: JSONObject): Account {
        val config = store.settings().optJSONObject("config") ?: JSONObject()
        val requested = when {
            parameters.has("subscription_id") && !parameters.isNull("subscription_id") -> parameters.getInt("subscription_id")
            config.has("subscription_id") && !config.isNull("subscription_id") -> config.getInt("subscription_id")
            else -> null
        }
        val activeSubscriptions = context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty()
        if (activeSubscriptions.isEmpty()) fail("NO_ACTIVE_SIM")
        val handles = manager.callCapablePhoneAccounts.filter { handle ->
            manager.getPhoneAccount(handle)?.let { account ->
                account.isEnabled && account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) &&
                    account.supportsUriScheme(PhoneAccount.SCHEME_TEL) &&
                    !account.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            } == true
        }
        if (handles.isEmpty()) fail("NO_NATIVE_SIM_PHONE_ACCOUNT")
        val candidates = if (Build.VERSION.SDK_INT >= 30) {
            handles.mapNotNull { handle ->
                val id = phone.getSubscriptionId(handle)
                if (activeSubscriptions.any { it.subscriptionId == id }) Account(handle, id) else null
            }
        } else {
            // API 29 has no public PhoneAccount->subscription ID mapping. Infer only sole SIM + account.
            if (handles.size != 1 || activeSubscriptions.size != 1) fail("SUBSCRIPTION_MAPPING_UNAVAILABLE")
            listOf(Account(handles.single(), activeSubscriptions.single().subscriptionId))
        }
        val matching = if (requested == null) candidates else candidates.filter { it.subscriptionId == requested }
        if (matching.isEmpty()) fail("SUBSCRIPTION_UNAVAILABLE")
        if (matching.size != 1) fail("AMBIGUOUS_SIM_SELECTION")
        val result = matching.single()
        val info = activeSubscriptions.first { it.subscriptionId == result.subscriptionId }
        val simState = phone.getSimState(info.simSlotIndex)
        if (simState != TelephonyManager.SIM_STATE_READY) {
            fail("SIM_NOT_READY")
        }
        return result
    }

    private fun bindOwnedCall(call: Call) {
        val snapshot = active ?: return
        if (snapshot.optBoolean("finalized") || ownedCall != null) return
        val details = call.details ?: return
        if (details.callDirection != Call.Details.DIRECTION_OUTGOING) return
        val account = details.accountHandle ?: return
        if (accountFingerprint(account) != snapshot.optString("phone_account")) return
        val extras = details.intentExtras
        val token = extras?.getString(EXTRA_PROBE_CALL_ID)
            ?: extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString(EXTRA_PROBE_CALL_ID)
            ?: details.extras?.getString(EXTRA_PROBE_CALL_ID)
        val sameToken = token == snapshot.optString("call_id")
        val sameNumber = details.handle?.scheme == "tel" &&
            details.handle?.schemeSpecificPart == snapshot.optString("destination")
        val creationDelta = details.creationTimeMillis - snapshot.optLong("dial_request_epoch_ms")
        val sameCreation = details.creationTimeMillis > 0 && creationDelta in -2_000L..30_000L
        val sameBoot = snapshot.optInt("boot_count", -1) >= 0 && snapshot.optInt("boot_count") == bootCount()
        // If an OEM drops the caller's extras, exact destination/account and call creation time
        // are needed. An unmatched call remains local-only; it is never terminated remotely.
        if (!sameToken && !(sameNumber && sameCreation && sameBoot)) return
        ownedCall = call
        snapshot.put("ownership_match", if (sameToken) "PROBE_TOKEN" else "ACCOUNT_NUMBER_CREATION_TIME")
        if (snapshot.optBoolean("recovery_pending") && call.state == Call.STATE_ACTIVE && snapshot.isNull("connected_time")) {
            snapshot.put("connection_time_uncertain", true)
        }
        snapshot.put("recovery_pending", false)
        persist()
        scheduleSample(snapshot.getString("call_id"))
        if (snapshot.optBoolean("hangup_pending") && call.state != Call.STATE_DISCONNECTED) {
            call.disconnect()
        }
    }

    private fun observe(call: Call, state: Int) {
        bindOwnedCall(call)
        val snapshot = active
        if (call !== ownedCall || snapshot == null || snapshot.optBoolean("finalized")) {
            refreshPublicStatus()
            return
        }
        val label = stateName(state)
        val previous = snapshot.optString("state")
        snapshot.put("state", label)
        call.details?.let { details ->
            snapshot.put("wifi_calling_observed", snapshot.optBoolean("wifi_calling_observed") ||
                details.hasProperty(Call.Details.PROPERTY_WIFI))
            if (Build.VERSION.SDK_INT >= 31) snapshot.put("cross_sim_calling_observed",
                snapshot.optBoolean("cross_sim_calling_observed") || details.hasProperty(Call.Details.PROPERTY_CROSS_SIM))
            snapshot.put("hd_audio_indicated", details.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO))
        }
        when (state) {
            Call.STATE_DIALING -> stampOnce(snapshot, "dialing")
            Call.STATE_ACTIVE -> stampOnce(snapshot, "connected")
            Call.STATE_DISCONNECTED -> {
                stampOnce(snapshot, "disconnect")
                val cause = call.details?.disconnectCause
                val wasConnected = !snapshot.isNull("connected_time")
                val outcome = when {
                    cause?.code == DisconnectCause.ERROR -> "FAILED"
                    wasConnected -> "COMPLETED"
                    else -> "NOT_CONNECTED"
                }
                persist()
                emit(snapshot, "DISCONNECTED", disconnectData(cause))
                finish(snapshot, outcome, if (outcome == "COMPLETED") null else "DISCONNECT_${cause?.code ?: 0}", call, true)
                return
            }
        }
        persist()
        if (previous != label) emit(snapshot, label, JSONObject().put("state", label))
    }

    private fun stampOnce(snapshot: JSONObject, prefix: String) {
        if (snapshot.isNull("${prefix}_time")) {
            snapshot.put("${prefix}_time", Instant.now().toString())
                .put("${prefix}_elapsed_ms", SystemClock.elapsedRealtime())
        }
    }

    private fun finish(snapshot: JSONObject, outcome: String, error: String?, call: Call?, observedDisconnect: Boolean) {
        if (snapshot.optBoolean("finalized")) return
        addSample(snapshot, "AFTER", telemetry.snapshot(snapshot.optInt("subscription_id")))
        snapshot.put("finalized", true).put("state", "ENDED").put("outcome", outcome)
            .put("error", error ?: JSONObject.NULL).put("result_time", Instant.now().toString())
            .put("disconnect_observed", observedDisconnect)
            .put("disconnect_cause", disconnectData(call?.details?.disconnectCause))
        snapshot.put("cellular_access_validation", when {
            snapshot.optBoolean("wifi_calling_observed") -> "WIFI_CALLING_OBSERVED"
            snapshot.optBoolean("cross_sim_calling_observed") -> "CROSS_SIM_CALLING_OBSERVED"
            else -> "BEARER_NOT_CONFIRMED"
        })
        val sameBoot = snapshot.optInt("boot_count", -1) >= 0 && snapshot.optInt("boot_count") == bootCount()
        val connected = snapshot.optLong("connected_elapsed_ms", -1)
        val dial = snapshot.optLong("dial_request_elapsed_ms", -1)
        val ended = snapshot.optLong("disconnect_elapsed_ms", -1)
        snapshot.put("call_setup_ms", if (sameBoot && connected >= dial && dial >= 0 && !snapshot.optBoolean("connection_time_uncertain")) connected - dial else JSONObject.NULL)
        snapshot.put("connected_duration_ms", if (sameBoot && ended >= connected && connected >= 0 && !snapshot.optBoolean("connection_time_uncertain")) ended - connected else JSONObject.NULL)
        // Save final snapshot first so recovery can replay final event if the process dies mid-write.
        active = snapshot
        persist()
        publishFinal(snapshot)
    }

    private fun publishFinal(snapshot: JSONObject) {
        val result = JSONObject(snapshot.toString())
        result.remove("dial_request_epoch_ms")
        store.saveLastCallResult(result)
        emit(snapshot, "CALL_RESULT", result)
        store.putMeta(receiptKey(snapshot.getString("command_id")), JSONObject()
            .put("call_id", snapshot.getString("call_id")).put("state", "FINISHED")
            .put("submission_accepted", snapshot.optBoolean("submission_accepted")).toString())
        store.putMeta(ACTIVE_KEY, null)
        active = null
        ownedCall = null
        refreshPublicStatus()
    }

    private fun disconnectData(cause: DisconnectCause?): JSONObject = JSONObject()
        .put("code", cause?.code ?: JSONObject.NULL)
        .put("label", cause?.label?.toString()?.take(200) ?: JSONObject.NULL)
        .put("description", cause?.description?.toString()?.take(500) ?: JSONObject.NULL)
        .put("reason", cause?.reason?.take(200) ?: JSONObject.NULL)

    private fun scheduleSubmissionCheck(id: String) {
        handler.postDelayed({
            if (active?.optString("call_id") == id && ownedCall == null) {
                active?.let { finish(it, "UNCERTAIN", "TELECOM_CALLBACK_MISSING", null, false) }
            }
        }, 30_000)
    }

    private var scheduledSampleFor: String? = null
    private fun scheduleSample(id: String) {
        if (scheduledSampleFor == id) return
        scheduledSampleFor = id
        handler.postDelayed(object : Runnable {
            override fun run() {
                val current = active
                if (current?.optString("call_id") != id || current.optBoolean("finalized")) {
                    if (scheduledSampleFor == id) scheduledSampleFor = null
                    return
                }
                val sub = current.optInt("subscription_id")
                sampler.execute {
                    val sample = telemetry.snapshot(sub)
                    handler.post {
                        active?.takeIf { it.optString("call_id") == id && !it.optBoolean("finalized") }?.let {
                            addSample(it, "DURING", sample)
                            persist()
                        }
                    }
                }
                handler.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    private fun addSample(snapshot: JSONObject, phase: String, sample: JSONObject) {
        val samples = snapshot.optJSONArray("radio_samples") ?: JSONArray().also { snapshot.put("radio_samples", it) }
        // Keep the pre-call sample and the newest 59 observations, including the final one.
        if (samples.length() >= 60) {
            samples.remove(1)
            snapshot.put("radio_samples_dropped", snapshot.optInt("radio_samples_dropped") + 1)
        }
        samples.put(JSONObject().put("phase", phase).put("timestamp", Instant.now().toString())
            .put("elapsed_ms", SystemClock.elapsedRealtime()).put("telemetry", compactSample(sample)))
        while (samples.length() > 2 && utf8Size(samples.toString()) > 240 * 1024) {
            samples.remove(1)
            snapshot.put("radio_samples_dropped", snapshot.optInt("radio_samples_dropped") + 1)
        }
        snapshot.put("radio_sample_budget_bytes", 240 * 1024)
    }

    private fun compactSample(sample: JSONObject): JSONObject {
        val result = JSONObject(sample.toString())
        if (utf8Size(result.toString()) <= 8 * 1024) return result
        result.put("call_sample_truncated", true)
        val cells = result.optJSONArray("cells")
        if (cells != null) {
            val retained = (0 until cells.length()).mapNotNull { cells.optJSONObject(it) }
                .sortedByDescending { it.optBoolean("registered") }.take(4)
            result.put("cells_original_count", cells.length()).put("cells", JSONArray(retained))
            val kept = result.getJSONArray("cells")
            while (kept.length() > 0 && utf8Size(result.toString()) > 8 * 1024) kept.remove(kept.length() - 1)
        }
        if (utf8Size(result.toString()) <= 8 * 1024) return result
        // Hard bound even if OEM strings or vendor collections are unexpectedly large.
        val bounded = JSONObject().put("call_sample_truncated", true)
            .put("sample_unavailable_reason", "SNAPSHOT_EXCEEDS_CALL_SAMPLE_SIZE_LIMIT")
        for (key in listOf("timestamp", "subscription_id", "carrier", "mcc", "mnc", "sim_state",
            "voice_registration", "voice_network_type", "data_network_type", "cell_info_scope")) {
            val value = result.opt(key)
            bounded.put(key, if (value is String) value.take(256) else value ?: JSONObject.NULL)
        }
        return bounded
    }

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun emit(snapshot: JSONObject, event: String, data: JSONObject) {
        val callId = snapshot.getString("call_id")
        val commandId = snapshot.getString("command_id")
        // Replaying a finalized snapshot after a crash cannot create a second result event.
        val eventId = if (event == "CALL_RESULT" || event == "DIAL_REQUESTED" || event == "DISCONNECTED") "$callId:$event" else UUID.randomUUID().toString()
        store.enqueueEvent(JSONObject().put("event_id", eventId).put("command_id", commandId)
            .put("call_id", callId).put("event", event).put("timestamp", Instant.now().toString()).put("data", data))
        // Full sample arrays stay in the durable event, not duplicated into every local log.
        log.write("telecom", event, JSONObject().put("state", snapshot.optString("state"))
            .put("outcome", snapshot.optString("outcome")), commandId = commandId, callId = callId)
    }

    private fun persist() { active?.let { store.putMeta(ACTIVE_KEY, it.toString()) }; refreshPublicStatus() }

    private fun refreshPublicStatus() {
        val snapshot = active
        val result = JSONObject().put("state", snapshot?.optString("state") ?: "IDLE")
            .put("probe_call_owned", ownedCall != null)
            .put("platform_calls", callbacks.keys.count { it.state != Call.STATE_DISCONNECTED })
        if (snapshot != null) {
            for (key in listOf("call_id", "command_id", "destination", "subscription_id", "phone_account", "dial_request_time", "dialing_time", "connected_time", "ownership_match")) {
                result.put(key, snapshot.opt(key) ?: JSONObject.NULL)
            }
        }
        foregroundCall()?.let { call ->
            result.put("ui_state", stateName(call.state)).put("ui_number", call.details?.handle?.schemeSpecificPart ?: "Unknown")
                .put("incoming", call.state == Call.STATE_RINGING)
        }
        val previousState = publicStatus.optString("state")
        val previousCall = publicStatus.optString("call_id")
        publicStatus = result
        if (previousState != result.optString("state") || previousCall != result.optString("call_id")) {
            onProbeCallStateChanged?.invoke(JSONObject(result.toString()))
        }
        onUiChanged?.invoke()
    }

    private fun foregroundCall(): Call? = callbacks.keys.firstOrNull { it.state == Call.STATE_RINGING }
        ?: ownedCall?.takeIf { it.state != Call.STATE_DISCONNECTED }
        ?: callbacks.keys.firstOrNull { it.state != Call.STATE_DISCONNECTED }

    private fun bootCount(): Int = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }
    private fun readJson(key: String): JSONObject? = try { store.getMeta(key)?.let { JSONObject(it) } } catch (_: Exception) { null }
    private fun receipt(id: String): JSONObject? = readJson(receiptKey(id))
    private fun receiptKey(id: String) = "call_receipt:$id"
    private fun accountFingerprint(handle: PhoneAccountHandle): String = MessageDigest.getInstance("SHA-256")
        .digest("${handle.componentName.flattenToString()}:${handle.id}".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        handler.post(task)
        // No timeout that could return failure while leaving a queued side effect to run later.
        return try { task.get() } catch (e: ExecutionException) { throw (e.cause ?: e) }
    }
    private fun fail(code: String): Nothing = throw IllegalStateException(code)

    companion object {
        private const val ACTIVE_KEY = "active_call"
        const val EXTRA_PROBE_CALL_ID = "com.sinch.vqprobe.CALL_ID"
        fun stateName(state: Int): String = when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            else -> "UNKNOWN_$state"
        }
    }
}

package com.sinch.vqprobe.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.*
import android.telephony.ims.ImsManager
import android.telephony.ims.RegistrationManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Best-effort public-API telemetry. No hidden APIs, polling waits or call-control decisions. */
@SuppressLint("MissingPermission")
class RadioCollector(context: Context) {
    private val context = context.applicationContext
    private val imsSamples = ConcurrentHashMap<Int, ImsSample>()
    private val imsRequests = ConcurrentHashMap<Int, Long>()
    private data class ImsSample(val state: Int? = null, val stateAt: Long? = null,
                                 val transport: Int? = null, val transportAt: Long? = null)

    fun subscriptions(): JSONArray = try {
        JSONArray(activeSubscriptions().map { subscriptionJson(it) })
    } catch (_: Exception) { JSONArray() }

    /** Subscription-specific state; cell identities are explicitly scoped to all device radios. */
    fun snapshot(subscriptionId: Int? = null): JSONObject {
        val out = JSONObject().put("timestamp", Instant.now().toString())
            .put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("android_api", Build.VERSION.SDK_INT)
        val unavailable = JSONObject()
        out.put("unavailable", unavailable)
        listOf("subscription_id", "slot_index", "carrier", "operator_name", "mcc", "mnc",
            "sim_state", "service_state", "voice_registration", "voice_network_type",
            "data_network_type", "phone_account", "signal", "cells", "battery_level",
            "charging", "ims_registration", "volte_confirmed").forEach { out.put(it, JSONObject.NULL) }
        out.put("volte_confirmation_reason", "PUBLIC_TELEPHONY_STATE_DOES_NOT_PROVE_CALL_BEARER")
        out.put("cell_info_scope", "DEVICE_ALL_RADIOS_NOT_ATTRIBUTED_TO_SUBSCRIPTION")
        out.put("cell_info_source", "ANDROID_CACHED_CELL_INFO")
        try {
            battery(out, unavailable)
            out.put("permissions", JSONObject()
                .put("read_phone_state", has(Manifest.permission.READ_PHONE_STATE))
                .put("fine_location", has(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("coarse_location", has(Manifest.permission.ACCESS_COARSE_LOCATION))
                .put("background_location", has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
            val base = context.getSystemService(TelephonyManager::class.java)
            if (base == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
                unavailable.put("telephony", "NO_TELEPHONY_HARDWARE")
                return out
            }
            val active = read(unavailable, "subscriptions") { activeSubscriptions() } ?: emptyList()
            out.put("subscriptions", JSONArray(active.map { subscriptionJson(it) }))
            val defaultVoice = SubscriptionManager.getDefaultVoiceSubscriptionId()
            val selected = when {
                subscriptionId != null -> active.firstOrNull { it.subscriptionId == subscriptionId }
                active.any { it.subscriptionId == defaultVoice } -> active.first { it.subscriptionId == defaultVoice }
                active.size == 1 -> active[0]
                else -> null
            }
            if (selected == null) {
                unavailable.put("subscription_id", when {
                    !has(Manifest.permission.READ_PHONE_STATE) -> "READ_PHONE_STATE_NOT_GRANTED"
                    subscriptionId != null -> "REQUESTED_SUBSCRIPTION_NOT_ACTIVE_OR_VISIBLE"
                    active.size > 1 -> "AMBIGUOUS_SUBSCRIPTION"
                    else -> "NO_ACTIVE_SUBSCRIPTION"
                })
                read(unavailable, "sim_state") { simState(base.simState) }?.let { out.put("sim_state", it) }
            } else {
                val id = selected.subscriptionId
                val tm = base.createForSubscriptionId(id)
                out.put("subscription_id", id).put("slot_index", selected.simSlotIndex)
                put(out, "carrier", selected.carrierName?.toString())
                put(out, "mcc", selected.mccString)
                put(out, "mnc", selected.mncString)
                read(unavailable, "sim_state") { simState(tm.simState) }?.let { out.put("sim_state", it) }
                read(unavailable, "operator_name") { tm.networkOperatorName }?.let { put(out, "operator_name", it) }
                read(unavailable, "network_operator") { tm.networkOperator }?.let {
                    if (it.matches(Regex("[0-9]{5,6}"))) {
                        out.put("network_mcc", it.take(3)).put("network_mnc", it.drop(3))
                    } else out.put("network_mcc", JSONObject.NULL).put("network_mnc", JSONObject.NULL)
                }
                if (has(Manifest.permission.READ_PHONE_STATE)) {
                    read(unavailable, "voice_network_type") { rat(tm.voiceNetworkType) }?.let { out.put("voice_network_type", it) }
                    read(unavailable, "data_network_type") { rat(tm.dataNetworkType) }?.let { out.put("data_network_type", it) }
                    val service = read(unavailable, "service_state") {
                        if (Build.VERSION.SDK_INT >= 33) tm.getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_NONE)
                        else tm.serviceState
                    }
                    if (service != null) {
                        out.put("service_state", serviceState(service.state))
                        out.put("voice_registration", serviceState(service.state))
                        out.put("voice_registration_source", "ServiceState.getState")
                        out.put("roaming", service.roaming)
                    }
                    val signal = read(unavailable, "signal") { tm.signalStrength }
                    if (signal != null) {
                        val signalJson = JSONObject().put("level", signal.level)
                            .put("level_scale", "0_NONE_OR_UNKNOWN_TO_4_GREAT")
                            .put("measurements", JSONArray(signal.cellSignalStrengths.map { signalJson(it) }))
                        if (Build.VERSION.SDK_INT >= 30) age(signalJson, signal.timestampMillis)
                        else signalJson.put("age_ms", JSONObject.NULL).put("age_unavailable", "REQUIRES_API_30")
                        out.put("signal", signalJson)
                    }
                    val account = read(unavailable, "phone_account") { phoneAccount(base, id) }
                    put(out, "phone_account", account)
                    if (account == null && Build.VERSION.SDK_INT < 30) unavailable.put("phone_account", "SUBSCRIPTION_MAPPING_REQUIRES_API_30")
                } else {
                    listOf("voice_network_type", "data_network_type", "service_state", "signal", "phone_account")
                        .forEach { unavailable.put(it, "READ_PHONE_STATE_NOT_GRANTED") }
                }
                out.put("ims_registration", ims(tm, id))
            }
            val location = read(unavailable, "location_enabled") {
                context.getSystemService(LocationManager::class.java)?.isLocationEnabled
            }
            put(out, "location_enabled", location)
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
                unavailable.put("cells", "ACCESS_FINE_LOCATION_NOT_GRANTED")
            } else if (location == false) {
                unavailable.put("cells", "LOCATION_SERVICES_DISABLED")
            } else {
                val cells = read(unavailable, "cells") { base.allCellInfo }
                if (cells != null) {
                    // getAllCellInfo is documented to return cells from all radios, even when
                    // a TelephonyManager is pinned to a subscription. Do not guess attribution.
                    val bounded = cells.take(64).map { cell ->
                        try { cellJson(cell) } catch (_: Exception) {
                            JSONObject().put("unavailable", "CELL_DECODING_FAILED")
                        }
                    }
                    out.put("cells", JSONArray(bounded)).put("cells_truncated", cells.size > 64)
                    if (cells.isEmpty()) unavailable.put("cells", "EMPTY_MODEM_CACHE_OR_LOCATION_RESTRICTED")
                }
            }
        } catch (e: Exception) {
            unavailable.put("snapshot", failure(e))
        }
        return out
    }

    private fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!has(Manifest.permission.READ_PHONE_STATE)) throw SecurityException()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return manager.activeSubscriptionInfoList ?: emptyList()
    }

    private fun subscriptionJson(info: SubscriptionInfo): JSONObject = JSONObject()
        .put("subscription_id", info.subscriptionId).put("slot_index", info.simSlotIndex)
        .apply { put(this, "carrier", info.carrierName?.toString()); put(this, "mcc", info.mccString); put(this, "mnc", info.mncString) }

    private fun phoneAccount(tm: TelephonyManager, id: Int): JSONObject? {
        if (Build.VERSION.SDK_INT < 30) return null
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        val handle = telecom.callCapablePhoneAccounts.firstOrNull {
            telecom.getPhoneAccount(it)?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) == true &&
                tm.getSubscriptionId(it) == id
        } ?: return null
        // A SIM PhoneAccountHandle ID can contain the ICCID. Match TelecomController's
        // opaque account reference and never send the raw handle outside the device.
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${handle.componentName.flattenToString()}:${handle.id}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        return JSONObject().put("component", handle.componentName.flattenToShortString())
            .put("id", fingerprint)
    }

    private fun battery(out: JSONObject, unavailable: JSONObject) {
        val intent = read(unavailable, "battery_level") { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) out.put("battery_level", level * 100.0 / scale)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (status != -1) out.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    /** Asynchronous privileged public API only; absence of access is UNKNOWN, never not registered. */
    private fun ims(tm: TelephonyManager, id: Int): JSONObject {
        val result = JSONObject().put("registered", JSONObject.NULL).put("state", "UNKNOWN")
            .put("transport", JSONObject.NULL).put("age_ms", JSONObject.NULL)
            .put("call_bearer", "UNKNOWN")
        if (Build.VERSION.SDK_INT < 30) return result.put("reason", "IMS_QUERY_REQUIRES_API_30")
        val privileged = has(Manifest.permission.READ_PRECISE_PHONE_STATE) ||
            has("android.permission.READ_PRIVILEGED_PHONE_STATE") || runCatching { tm.hasCarrierPrivileges() }.getOrDefault(false)
        if (!privileged) return result.put("reason", "PRECISE_PHONE_STATE_OR_CARRIER_PRIVILEGE_REQUIRED")
        try {
            val now = SystemClock.elapsedRealtime()
            val last = imsRequests[id]
            if (last == null || now - last >= 10_000) {
                imsRequests[id] = now
                val manager = context.getSystemService(ImsManager::class.java)?.getImsMmTelManager(id)
                    ?: return result.put("reason", "IMS_SERVICE_UNAVAILABLE")
                manager.getRegistrationState(context.mainExecutor) { state ->
                    imsSamples.compute(id) { _, old -> (old ?: ImsSample()).copy(state = state, stateAt = SystemClock.elapsedRealtime()) }
                }
                manager.getRegistrationTransportType(context.mainExecutor) { transport ->
                    imsSamples.compute(id) { _, old -> (old ?: ImsSample()).copy(transport = transport, transportAt = SystemClock.elapsedRealtime()) }
                }
            }
            val sample = imsSamples[id] ?: return result.put("reason", "IMS_QUERY_PENDING")
            val stateAge = sample.stateAt?.let { now - it }
            if (stateAge == null || stateAge !in 0..30_000) return result.put("reason", "IMS_STATE_PENDING_OR_STALE")
            result.put("age_ms", stateAge).put("state", when (sample.state) {
                RegistrationManager.REGISTRATION_STATE_REGISTERED -> "REGISTERED"
                RegistrationManager.REGISTRATION_STATE_REGISTERING -> "REGISTERING"
                RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED -> "NOT_REGISTERED"
                else -> "UNKNOWN"
            })
            when (sample.state) {
                RegistrationManager.REGISTRATION_STATE_REGISTERED -> result.put("registered", true)
                RegistrationManager.REGISTRATION_STATE_REGISTERING,
                RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED -> result.put("registered", false)
            }
            val transportAge = sample.transportAt?.let { now - it }
            if (transportAge != null && transportAge in 0..30_000) {
                put(result, "transport", when (sample.transport) {
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN -> "WWAN"
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN -> "WLAN"
                    else -> null
                })
                result.put("transport_age_ms", transportAge)
            }
        } catch (e: Exception) { result.put("reason", failure(e)) }
        return result
    }

    private fun cellJson(cell: CellInfo): JSONObject {
        val out = JSONObject().put("registered", cell.isRegistered)
            .put("connection_status", cell.cellConnectionStatus)
            .put("subscription_id", JSONObject.NULL)
            .put("subscription_reason", "CELLINFO_DOES_NOT_EXPOSE_SUBSCRIPTION_ID")
        @Suppress("DEPRECATION")
        val timestamp = if (Build.VERSION.SDK_INT >= 30) cell.timestampMillis else cell.timeStamp / 1_000_000
        age(out, timestamp)
        when (cell) {
            is CellInfoLte -> {
                val id = cell.cellIdentity
                out.put("rat", "LTE").put("signal", signalJson(cell.cellSignalStrength))
                metric(out, "cell_id", id.ci); metric(out, "pci", id.pci)
                metric(out, "tac", id.tac); metric(out, "earfcn", id.earfcn)
                put(out, "mcc", id.mccString); put(out, "mnc", id.mncString)
                if (Build.VERSION.SDK_INT >= 30) bands(out, id.bands) else bands(out, null)
            }
            is CellInfoNr -> {
                val id = cell.cellIdentity as CellIdentityNr
                out.put("rat", "NR").put("signal", signalJson(cell.cellSignalStrength))
                metric(out, "cell_id", id.nci); metric(out, "pci", id.pci)
                metric(out, "tac", id.tac); metric(out, "nrarfcn", id.nrarfcn)
                put(out, "mcc", id.mccString); put(out, "mnc", id.mncString)
                if (Build.VERSION.SDK_INT >= 30) bands(out, id.bands) else bands(out, null)
            }
            is CellInfoGsm -> {
                val id = cell.cellIdentity
                out.put("rat", "GSM").put("signal", signalJson(cell.cellSignalStrength))
                metric(out, "cell_id", id.cid); metric(out, "lac", id.lac); metric(out, "arfcn", id.arfcn)
                put(out, "mcc", id.mccString); put(out, "mnc", id.mncString)
            }
            is CellInfoWcdma -> {
                val id = cell.cellIdentity
                out.put("rat", "WCDMA").put("signal", signalJson(cell.cellSignalStrength))
                metric(out, "cell_id", id.cid); metric(out, "lac", id.lac)
                metric(out, "psc", id.psc); metric(out, "uarfcn", id.uarfcn)
                put(out, "mcc", id.mccString); put(out, "mnc", id.mncString)
            }
            is CellInfoCdma -> {
                val id = cell.cellIdentity
                out.put("rat", "CDMA").put("signal", signalJson(cell.cellSignalStrength))
                metric(out, "basestation_id", id.basestationId); metric(out, "network_id", id.networkId)
            }
            is CellInfoTdscdma -> {
                val id = cell.cellIdentity
                out.put("rat", "TD_SCDMA").put("signal", signalJson(cell.cellSignalStrength))
                metric(out, "cell_id", id.cid); metric(out, "lac", id.lac); metric(out, "uarfcn", id.uarfcn)
                put(out, "mcc", id.mccString); put(out, "mnc", id.mncString)
            }
            else -> out.put("rat", "UNKNOWN").put("reason", "UNSUPPORTED_CELLINFO_CLASS")
        }
        return out
    }

    private fun signalJson(signal: CellSignalStrength): JSONObject {
        val out = JSONObject().put("level", signal.level).put("level_scale", "0_NONE_OR_UNKNOWN_TO_4_GREAT")
        metric(out, "dbm", signal.dbm)
        listOf("rsrp_dbm", "rsrq_db", "rssi_dbm", "sinr_db").forEach { out.put(it, JSONObject.NULL) }
        when (signal) {
            is CellSignalStrengthLte -> {
                out.put("rat", "LTE")
                metric(out, "rsrp_dbm", signal.rsrp); metric(out, "rsrq_db", signal.rsrq)
                metric(out, "rssi_dbm", signal.rssi); metric(out, "lte_rssnr_raw", signal.rssnr)
                // AOSP Android 11+ exposes dB; legacy releases/OEM builds had differing scales.
                if (Build.VERSION.SDK_INT >= 30) metric(out, "sinr_db", signal.rssnr)
                else out.put("sinr_unavailable", "API_29_RSSNR_SCALE_NOT_NORMALIZED_SEE_RAW")
                out.put("sinr_source", "LTE_RSSNR")
            }
            is CellSignalStrengthNr -> {
                out.put("rat", "NR").put("sinr_source", "NR_SS_SINR")
                metric(out, "rsrp_dbm", signal.ssRsrp); metric(out, "rsrq_db", signal.ssRsrq)
                metric(out, "sinr_db", signal.ssSinr)
                metric(out, "csi_rsrp_dbm", signal.csiRsrp); metric(out, "csi_rsrq_db", signal.csiRsrq)
                metric(out, "csi_sinr_db", signal.csiSinr)
                out.put("rssi_unavailable", "NOT_EXPOSED_FOR_NR_BY_PUBLIC_API")
            }
            is CellSignalStrengthGsm -> { out.put("rat", "GSM"); metric(out, "rssi_dbm", signal.dbm) }
            is CellSignalStrengthWcdma -> out.put("rat", "WCDMA")
            is CellSignalStrengthCdma -> out.put("rat", "CDMA")
            is CellSignalStrengthTdscdma -> out.put("rat", "TD_SCDMA")
            else -> out.put("rat", "UNKNOWN")
        }
        out.put("null_metric_reason", "UNREPORTED_OR_NOT_APPLICABLE_TO_RAT")
        return out
    }

    private fun bands(out: JSONObject, values: IntArray?) {
        put(out, "bands", if (values == null || values.isEmpty()) null else JSONArray(values.toList()))
        if (values == null || values.isEmpty()) out.put("bands_unavailable", if (values == null) "REQUIRES_API_30" else "MODEM_DID_NOT_REPORT")
    }

    private fun age(out: JSONObject, timestamp: Long) {
        val now = SystemClock.elapsedRealtime()
        if (timestamp > 0 && timestamp <= now) {
            out.put("sample_elapsed_realtime_ms", timestamp).put("age_ms", now - timestamp)
        } else out.put("sample_elapsed_realtime_ms", JSONObject.NULL).put("age_ms", JSONObject.NULL)
            .put("age_unavailable", "INVALID_MODEM_TIMESTAMP")
    }

    private fun has(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun put(out: JSONObject, key: String, value: Any?) { out.put(key, if (value == null || value == "") JSONObject.NULL else value) }
    // Negative values, including -1 dB SINR, can be valid measurements.
    private fun metric(out: JSONObject, key: String, value: Int) { put(out, key, value.takeUnless { it == CellInfo.UNAVAILABLE }) }
    private fun metric(out: JSONObject, key: String, value: Long) { put(out, key, value.takeUnless { it == Long.MAX_VALUE || it == -1L }) }
    private fun <T> read(unavailable: JSONObject, name: String, block: () -> T?): T? = try {
        block().also { if (it == null) unavailable.put(name, "NOT_REPORTED") }
    } catch (e: Exception) { unavailable.put(name, failure(e)); null }
    private fun failure(e: Exception): String = when (e) {
        is SecurityException -> "PERMISSION_OR_BACKGROUND_ACCESS_DENIED"
        is UnsupportedOperationException -> "UNSUPPORTED_ON_DEVICE"
        else -> "TELEPHONY_UNAVAILABLE_${e.javaClass.simpleName}"
    }

    private fun serviceState(state: Int): String = when (state) {
        ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
        ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
        ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
        ServiceState.STATE_POWER_OFF -> "POWER_OFF"
        else -> "UNKNOWN"
    }

    private fun simState(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
        else -> "UNKNOWN"
    }

    private fun rat(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "UNKNOWN"
    }
}

package com.sinch.vqprobe.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import com.sinch.vqprobe.graph
import com.sinch.vqprobe.ui.InCallActivity

/** In-call UI for the default dialer; actual transport remains the platform SIM service. */
class ProbeInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cellular calls", NotificationManager.IMPORTANCE_HIGH)
        )
        graph().telecom.onUiChanged = { updateNotification() }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        graph().telecom.onCallAdded(call)
        updateNotification()
    }

    override fun onCallRemoved(call: Call) {
        graph().telecom.onCallRemoved(call)
        super.onCallRemoved(call)
        updateNotification()
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        startActivity(Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onDestroy() {
        graph().telecom.onUiChanged = null
        graph().telecom.onServiceDisconnected()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        super.onDestroy()
    }

    private fun updateNotification() {
        val status = graph().telecom.status()
        val notifications = getSystemService(NotificationManager::class.java)
        if (status.optInt("platform_calls") == 0) {
            notifications.cancel(NOTIFICATION)
            return
        }
        val intent = Intent(this, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Sinch cellular call · ${status.optString("ui_state")}")
            .setContentText(status.optString("ui_number", "Open call controls"))
            .setContentIntent(pending).setOngoing(true).setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (status.optBoolean("incoming")) builder.setFullScreenIntent(pending, true)
        try { notifications.notify(NOTIFICATION, builder.build()) } catch (_: SecurityException) {
            // Setup UI requests POST_NOTIFICATIONS; a revoked grant must not crash call control.
        }
    }

    companion object {
        private const val CHANNEL = "probe_cellular_calls"
        private const val NOTIFICATION = 2002
    }
}

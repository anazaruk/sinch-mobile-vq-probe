package com.sinch.vqprobe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sinch.vqprobe.graph

/** Credential-protected enrollment is available after unlock; no pre-unlock calls are attempted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!context.graph().store.settings().optBoolean("enabled", false)) return
        if (context.graph().store.deviceId() == null) return
        try {
            ProbeService.start(context)
        } catch (error: RuntimeException) {
            // Persist the failure and make recovery visible; do not evade OS start restrictions.
            ProbeService.reportStartFailure(context, error)
        }
    }
}

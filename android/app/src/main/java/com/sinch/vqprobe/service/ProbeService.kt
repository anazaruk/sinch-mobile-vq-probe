package com.sinch.vqprobe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.sinch.vqprobe.api.ApiException
import com.sinch.vqprobe.audio.AudioOperationException
import com.sinch.vqprobe.graph
import com.sinch.vqprobe.ui.MainActivity
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** User-visible execution agent. The polling loop never waits for a cellular call or WAIT command. */
class ProbeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "probe-poll").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    // These fields, except closed/foregroundReady, belong exclusively to worker.
    private var nextPoll: ScheduledFuture<*>? = null
    private var failures = 0
    private var recovered = false
    @Volatile private var closed = false
    private var foregroundReady = false
    private var networkCallbackRegistered = false
    private lateinit var connectivity: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = requestPoll()
        override fun onLost(network: Network) = requestPoll()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(this, "Connecting to orchestrator"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                // Do not activate the microphone type during boot/reconnect.
                startForeground(NOTIFICATION_ID, notification(this, "Connecting to orchestrator"), 0)
            }
            foregroundReady = true
            instance = this
        } catch (error: RuntimeException) {
            reportStartFailure(this, error)
            stopSelf()
            return
        }
        graph().store.putMeta("service_running", "true")
        graph().log.write("service", "STARTED")
        connectivity = getSystemService(ConnectivityManager::class.java)
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (error: RuntimeException) {
            graph().log.write("service", "CONNECTIVITY_CALLBACK_UNAVAILABLE",
                JSONObject().put("error", safeError(error)), "WARN")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady || !graph().store.settings().optBoolean("enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART_AGENT) {
            enqueue {
                failures = 0
                graph().store.putMeta("service_error", null)
                graph().log.write("service", "AGENT_LOOP_RESTARTED")
                schedulePoll(0L)
            }
        } else {
            requestPoll()
        }
        return START_STICKY
    }

    private fun enqueue(block: () -> Unit) {
        if (closed) return
        try {
            worker.execute { if (!closed) block() }
        } catch (_: RejectedExecutionException) {
            // A network callback raced with onDestroy; the service is already stopped.
        }
    }

    private fun requestPoll() = enqueue { schedulePoll(0L) }

    private fun schedulePoll(delayMs: Long) {
        if (closed) return
        nextPoll?.cancel(false)
        nextPoll = worker.schedule({ pollOnce() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun pollOnce() {
        if (closed) return
        val graph = graph()
        if (!graph.store.settings().optBoolean("enabled", false)) {
            main.post { stopSelf() }
            return
        }
        var wakeLock: PowerManager.WakeLock? = null
        var nextDelay = 10_000L
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SinchVQ:poll").apply {
                    setReferenceCounted(false)
                    acquire(120_000L)
                }
            if (!recovered) {
                graph.commands.recover()
                recovered = true
            }
            if (graph.store.deviceId() == null) throw IllegalStateException("NOT_ENROLLED")
            val network = connectivity.activeNetwork
            if (network == null || connectivity.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                throw IllegalStateException("NO_NETWORK")
            }
            // Flush before polling so the server can advance past a completed command.
            flushOutbox()
            if (closed || !graph.store.settings().optBoolean("enabled", false)) return
            val command = graph.api.poll(graph.status())
            if (closed || !graph.store.settings().optBoolean("enabled", false)) return
            graph.store.putMeta("connection_state", "ONLINE")
            graph.store.putMeta("last_poll_at", Instant.now().toString())
            graph.store.putMeta("service_error", null)
            failures = 0
            if (command.optString("action", "NONE") != "NONE") {
                graph.commands.execute(command)
            }
            flushOutbox()
            val interval = graph.store.settings().optJSONObject("config")
                ?.optLong("poll_interval_seconds", 5L)?.coerceIn(2L, 300L) ?: 5L
            nextDelay = jitter(interval * 1_000L)
            updateNotification("Online • polling every ${interval}s")
        } catch (error: Exception) {
            if (closed || Thread.currentThread().isInterrupted) return
            failures = (failures + 1).coerceAtMost(12)
            val code = safeError(error)
            val terminal = (error is ApiException && error.httpStatus in setOf(401, 403)) ||
                code in setOf("NOT_ENROLLED", "UNAUTHORIZED", "DEVICE_REVOKED",
                "TOKEN_REFRESH_FAILED", "INVALID_REFRESH_TOKEN", "HTTP_401", "HTTP_403")
            graph.store.putMeta("connection_state", if (terminal) "ERROR" else "OFFLINE")
            graph.store.putMeta("service_error", code)
            graph.log.write("service", "POLL_FAILED", JSONObject().put("error", code)
                .put("consecutive_failures", failures), if (terminal) "ERROR" else "WARN")
            nextDelay = jitter((2_000L shl failures.coerceAtMost(8)).coerceAtMost(300_000L))
            updateNotification(if (terminal) "Action required: $code" else "Offline • retrying")
        } finally {
            if (wakeLock?.isHeld == true) wakeLock.release()
            if (!closed) schedulePoll(nextDelay)
        }
    }

    private fun jitter(base: Long): Long =
        (base * ThreadLocalRandom.current().nextDouble(0.85, 1.15)).toLong().coerceIn(1_000L, 300_000L)

    private fun flushOutbox() {
        try {
            graph().api.flush()
            graph().store.putMeta("upload_error", null)
        } catch (error: Exception) {
            if (closed || Thread.currentThread().isInterrupted) return
            val code = safeError(error)
            val changed = graph().store.getMeta("upload_error") != code
            graph().store.putMeta("upload_error", code)
            // A rejected event/result must not prevent polling a HANGUP command.
            if (changed) graph().log.write("service", "OUTBOX_UPLOAD_FAILED",
                JSONObject().put("error", code), "ERROR")
        }
    }

    private fun updateNotification(message: String) {
        main.post {
            if (!closed && foregroundReady) {
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(this, message))
                } catch (_: SecurityException) {
                    // A user may revoke notification permission while the agent is running.
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        closed = true
        if (instance === this) instance = null
        // Loss of the capture foreground service is an audio stop boundary.
        Thread {
            runCatching { graph().audio.rx.stop("SERVICE_STOPPED") }
            runCatching { graph().audio.tx.stop("SERVICE_STOPPED") }
        }.start()
        if (networkCallbackRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: RuntimeException) { }
        }
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        graph().store.putMeta("service_running", "false")
        if (foregroundReady) graph().store.putMeta("connection_state", "OFFLINE")
        graph().log.write("service", "STOPPED")
        // Graph/dispatcher are application-scoped. Do not destroy them on a service-only restart.
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: ProbeService? = null
        const val ACTION_RESTART_AGENT = "com.sinch.vqprobe.RESTART_AGENT"
        private const val ACTION_POLL_NOW = "com.sinch.vqprobe.POLL_NOW"
        private const val CHANNEL_ID = "probe_agent"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_NOTIFICATION_ID = 1002

        /** Promote the existing agent, never start a microphone FGS from BOOT_COMPLETED. */
        fun setCaptureForeground(context: Context, active: Boolean) {
            val service = instance
            if (service == null || service.closed || !service.foregroundReady) {
                if (active) throw AudioOperationException("AUDIO_FOREGROUND_SERVICE_REQUIRED")
                return
            }
            val action = {
                val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                val microphone = if (active && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                try {
                    service.startForeground(NOTIFICATION_ID, notification(context,
                        if (active) "Recording cellular audio • probe active" else "Cellular probe active"), base or microphone)
                } catch (error: RuntimeException) {
                    throw AudioOperationException("AUDIO_FOREGROUND_START_REJECTED", JSONObject().put("exception_type", error.javaClass.simpleName))
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else {
                val task = FutureTask(action)
                service.main.post(task)
                try { task.get(5, TimeUnit.SECONDS) }
                catch (error: ExecutionException) { throw (error.cause ?: error) }
                catch (_: TimeoutException) { task.cancel(false); throw AudioOperationException("AUDIO_FOREGROUND_UPDATE_TIMEOUT") }
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProbeService::class.java))
        }

        fun pollNow(context: Context) {
            context.startForegroundService(Intent(context, ProbeService::class.java).setAction(ACTION_POLL_NOW))
        }

        internal fun safeError(error: Throwable): String {
            val message = error.message.orEmpty()
            return if (message.matches(Regex("[A-Z][A-Z0-9_]{1,63}"))) message
            else error.javaClass.simpleName.uppercase().take(64)
        }

        internal fun reportStartFailure(context: Context, error: Throwable) {
            val code = safeError(error)
            context.graph().store.putMeta("connection_state", "ERROR")
            context.graph().store.putMeta("service_error", "START_BLOCKED_$code")
            context.graph().log.write("service", "START_BLOCKED",
                JSONObject().put("error", code), "ERROR")
            createChannel(context)
            try {
                context.getSystemService(NotificationManager::class.java).notify(
                    RESTART_NOTIFICATION_ID, notification(context, "Open app to resume the probe", false))
            } catch (_: SecurityException) { }
        }

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mobile probe agent", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Dedicated cellular probe status and recovery" })
        }

        private fun notification(context: Context, message: String, ongoing: Boolean = true): Notification {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("SINCH MOBILE VQ PROBE")
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }
}

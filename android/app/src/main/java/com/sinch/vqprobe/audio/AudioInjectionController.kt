package com.sinch.vqprobe.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.sinch.vqprobe.ProbeGraph
import org.json.JSONArray
import org.json.JSONObject
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Pure option validation, shared by the command boundary and JVM tests. */
internal enum class TxEngineeringMode {
    NORMAL, DIGITAL_TX_VALIDATION;

    companion object {
        fun parse(value: String?, muteMicrophone: Boolean?): TxEngineeringMode {
            val mode = entries.firstOrNull { it.name == (value ?: NORMAL.name) }
                ?: throw IllegalArgumentException("INVALID_AUDIO_MODE")
            require(mode != DIGITAL_TX_VALIDATION || muteMicrophone != false) {
                "DIGITAL_TX_VALIDATION_REQUIRES_MICROPHONE_MUTE"
            }
            return mode
        }
    }
}

/**
 * Engineering telephony-TX implementation. Public routing APIs only; there is no speaker,
 * microphone, Bluetooth, global audio-mode, or hidden AudioTrack-flag fallback.
 *
 * AOSP AudioPolicyManager assigns INCALL_MUSIC for explicitly selected Telephony TX,
 * linear PCM, MUSIC/VOICE_COMMUNICATION and an existing in-call mode. Only actual
 * hardware testing can establish that the vendor HAL/modem delivers this PCM remotely.
 */
class AudioInjectionController(private val graph: ProbeGraph) {
    private val audio = graph.context.getSystemService(AudioManager::class.java)
    private val lifecycle = Any()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "probe-telephony-tx") }
    private val routingThread = HandlerThread("probe-tx-route-guard").apply { start() }
    private val routingHandler = Handler(routingThread.looper)
    private var current: Session? = null
    @Volatile private var last = JSONObject().put("state", "IDLE").put("running", false)

    private class Session(val commandId: String, val call: JSONObject, val parameters: JSONObject) {
        val operationId = UUID.randomUUID().toString()
        val dataLock = Any()
        val trackLock = Any()
        val startLock = Any()
        val startDeadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        val canceled = AtomicBoolean(false)
        val reason = AtomicReference("END_OF_FILE")
        val failure = AtomicReference<AudioOperationException?>(null)
        val startReady = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val startResult = AtomicReference<JSONObject?>(null)
        val sourceFrames = AtomicLong(0)
        val silenceFrames = AtomicLong(0)
        val loops = AtomicLong(0)
        val routeCallbacks = AtomicLong(0)
        val routeCallbackLogFailures = AtomicLong(0)
        @Volatile var track: AudioTrack? = null
        @Volatile var routeVerified = false
        @Volatile var closing = false
        @Volatile var outputChannels = 1
        @Volatile var sourceRate = 0
        @Volatile var routeId = -1
        @Volatile var hadMicLease = false
        @Volatile var micLease: JSONObject? = null
        @Volatile var micIsolationArmed = false
        @Volatile var underruns = 0
        @Volatile var totalFrames = 0L
        var lastHeadRaw = 0L
        var headWraps = 0L
        val data = JSONObject().put("operation_id", operationId).put("command_id", commandId)
            .put("call_id", call.getString("call_id")).put("call_command_id", call.opt("command_id") ?: JSONObject.NULL)
            .put("file_id", parameters.optString("file_id")).put("state", "STARTING")
            .put("mode", parameters.getString("mode"))
            .put("digital_tx_validation", parameters.getString("mode") == TxEngineeringMode.DIGITAL_TX_VALIDATION.name)
            .put("speaker_source_audio_enabled", false).put("external_audio_fallback_allowed", false)
            .put("running", false).put("requested_device", "TYPE_TELEPHONY")
            .put("actual_device", JSONObject.NULL).put("route_verified", false)
            .put("source_pcm_started", false).put("audio_started_at", JSONObject.NULL)
            .put("audio_stopped_at", JSONObject.NULL).put("far_end_audio_verified", JSONObject.NULL)
            .put("incall_music_flag_observed", JSONObject.NULL)
            .put("incall_music_flag_reason", "AudioPolicy assigns native flags; verify with device dumpsys")
            .put("hardware_sample_rate", JSONObject.NULL).put("hardware_channels", JSONObject.NULL)
            .put("route_proof_scope", "AudioTrack.getRoutedDevice; vendor path and far end require hardware validation")
            .put("reroute_guard", "Routing callback and before/after each nonblocking PCM write; asynchronous race cannot be excluded by public API")
            .put("mute_microphone_requested", parameters.optBoolean("mute_microphone", true))
            .put("route_callback_count", 0).put("route_callback_history", JSONArray())
            .put("route_callback_history_limit", ROUTE_HISTORY_LIMIT)
            .put("loop", parameters.optBoolean("loop", false))
    }

    /** Bounded wait for actual telephony routing and the first accepted source-PCM write. */
    fun start(commandId: String, parameters: JSONObject): JSONObject {
        val fileId = parameters.optString("file_id")
        if (!fileId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) throw error("INVALID_FILE_ID")
        for (key in listOf("loop", "mute_microphone")) {
            if (parameters.has(key) && parameters.opt(key) !is Boolean) throw error("INVALID_AUDIO_PARAMETERS")
        }
        if (parameters.has("sample_rate") && parameters.opt("sample_rate") !is Int && parameters.opt("sample_rate") !is Long) {
            throw error("INVALID_AUDIO_PARAMETERS")
        }
        if (parameters.has("mode") && parameters.opt("mode") !is String) throw error("INVALID_AUDIO_MODE")
        val mode = try {
            TxEngineeringMode.parse(if (parameters.has("mode")) parameters.getString("mode") else null,
                parameters.opt("mute_microphone") as? Boolean)
        } catch (e: IllegalArgumentException) {
            throw error(safeCode(e.message, "INVALID_AUDIO_MODE"))
        }
        val normalized = JSONObject(parameters.toString()).put("mode", mode.name)
            .put("mute_microphone", parameters.optBoolean("mute_microphone", true))
        if (audio == null) throw error("AUDIO_SERVICE_UNAVAILABLE")
        requirePermission(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        requirePermission(Manifest.permission.MODIFY_PHONE_STATE)
        // Flush a retained terminal event before a new operation can overwrite its snapshot.
        if (graph.store.getMeta(TX_SNAPSHOT) != null || graph.store.getMeta(MIC_LEASE) != null) recover()
        val call = graph.audio.callContext()
        val session = Session(commandId, JSONObject(call.toString()), normalized)
        synchronized(lifecycle) {
            if (current != null) throw error("AUDIO_TX_BUSY")
            if (graph.store.getMeta(MIC_LEASE) != null) throw error("MICROPHONE_RESTORE_REQUIRED")
            if (graph.store.getMeta(TX_SNAPSHOT) != null) throw error("AUDIO_TX_RECOVERY_REQUIRED")
            current = session
        }
        worker.execute { run(session) }
        val ready = try { session.startReady.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            requestStop(session, "START_INTERRUPTED")
            throw error("AUDIO_START_INTERRUPTED", snapshot(session))
        }
        if (!ready) {
            synchronized(session.startLock) {
                session.startResult.get()?.let {
                    session.failure.get()?.let { failure -> throw error(failure.code, snapshot(session)) }
                    return JSONObject(it.toString())
                }
                session.canceled.set(true)
                session.reason.set("START_TIMEOUT")
            }
            requestStop(session, "START_TIMEOUT")
            val details = snapshot(session).put("cleanup_pending", session.finished.count != 0L)
            throw error("AUDIO_TX_START_TIMEOUT", details)
        }
        session.startResult.get()?.let {
            session.failure.get()?.let { failure -> throw error(failure.code, snapshot(session)) }
            return JSONObject(it.toString())
        }
        val failure = session.failure.get()
        throw error(failure?.code ?: "AUDIO_TX_START_CANCELED", snapshot(session)
            .put("failure_details", failure?.details ?: JSONObject.NULL))
    }

    /** Requests stop immediately; does not hold a controller monitor while cleanup completes. */
    fun stop(reason: String = "COMMAND"): JSONObject {
        val session = synchronized(lifecycle) { current }
        if (session == null) {
            if (graph.store.getMeta(MIC_LEASE) != null) recover()
            return status().put("already_stopped", true)
        }
        requestStop(session, reason.take(100))
        val complete = try { session.finished.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        return snapshot(session).put("stop_requested", true).put("stop_completed", complete)
            .put("cleanup_pending", !complete)
    }

    fun status(): JSONObject {
        val session = synchronized(lifecycle) { current }
        return if (session == null) JSONObject(last.toString()) else snapshot(session)
    }

    /** Restore a durable microphone lease and report interrupted playback; never resume PCM. */
    fun recover() {
        synchronized(lifecycle) {
            if (current != null) return
            // Reserve recovery against start; no waits or audio streaming while holding this lock.
            val lease = readMeta(MIC_LEASE)
            if (lease != null) {
                val restored = restoreLease(lease)
                if (!restored) {
                    last = JSONObject().put("state", "ERROR").put("running", false)
                        .put("error", "MICROPHONE_RESTORE_FAILED").put("microphone_restored", false)
                }
            }
            val interrupted = readMeta(TX_SNAPSHOT) ?: return
            val terminal = interrupted.optString("state") in listOf("STOPPED", "ERROR") &&
                !interrupted.isNull("audio_stopped_at")
            if (!terminal) interrupted.put("state", "STOPPED").put("running", false).put("reason", "PROCESS_RESTART")
                    .put("error", "PROCESS_RESTART_INTERRUPTED_AUDIO").put("audio_stopped_at", JSONObject.NULL)
                    .put("recovery_time", now()).put("frames_written_final_unknown", true)
                    .put("microphone_restored", graph.store.getMeta(MIC_LEASE) == null)
                    .put("source_pcm_resumed", false)
            interrupted.put("audio_event_id", "${interrupted.getString("operation_id")}:TX_TERMINAL")
            last = JSONObject(interrupted.toString())
            graph.audio.emit(interrupted.getString("command_id"), interrupted.getString("call_id"),
                if (interrupted.optString("state") == "ERROR") "AUDIO_TX_FAILED" else "AUDIO_TX_STOPPED", interrupted)
            graph.store.putMeta(TX_SNAPSHOT, null)
        }
    }

    private fun run(session: Session) {
        var source: RandomAccessFile? = null
        var listener: AudioRouting.OnRoutingChangedListener? = null
        var referencePinned = false
        try {
            checkCallAndStop(session)
            val file = graph.files.acquireReference(session.parameters.getString("file_id"))
            referencePinned = true
            // Hold the descriptor throughout playback; atomic cache replacement cannot change it.
            source = RandomAccessFile(file, "r")
            val info = WavPcm.read(file)
            if (info.channels != 1 || info.bitsPerSample != 16) throw error("UNSUPPORTED_WAV_FORMAT")
            if (info.sampleRate !in listOf(8_000, 16_000, 48_000)) throw error("UNSUPPORTED_AUDIO_SAMPLE_RATE")
            if (info.dataBytes <= 0) throw error("EMPTY_WAV_DATA")
            val requestedRate = session.parameters.optInt("sample_rate", info.sampleRate)
            if (requestedRate != info.sampleRate) throw error("UNSUPPORTED_SAMPLE_RATE_CONVERSION", JSONObject()
                .put("input_sample_rate", info.sampleRate).put("requested_sample_rate", requestedRate)
                .put("supported_native_sample_rates", JSONArray(listOf(8_000, 16_000, 48_000)))
                .put("reason", "Use a reference WAV at the desired native telephony rate; preserve the reference signal without hidden resampling"))
            val devices = audio!!.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            if (devices.isEmpty()) throw error("TELEPHONY_TX_UNAVAILABLE", JSONObject().put("available_outputs",
                JSONArray(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { deviceJson(it) })))
            if (devices.size != 1) throw error("AMBIGUOUS_TELEPHONY_TX_DEVICE")
            val sink = devices.single()
            session.routeId = sink.id
            session.sourceRate = info.sampleRate
            // Device capabilities need not describe the incall_music profile separately.
            // Prefer its common stereo format (redfin); honor an explicit mono-only sink.
            // Source mono is duplicated exactly and the conversion is always reported.
            session.outputChannels = when {
                sink.channelCounts.isEmpty() || sink.channelCounts.contains(2) -> 2
                sink.channelCounts.contains(1) -> 1
                else -> throw error("UNSUPPORTED_TELEPHONY_TX_CHANNELS")
            }
            if (sink.channelCounts.isNotEmpty() && !sink.channelCounts.contains(session.outputChannels)) {
                throw error("UNSUPPORTED_TELEPHONY_TX_CHANNELS")
            }
            val digest = sha256(source)
            update(session) {
                put("source_sha256", digest).put("source_sample_rate", info.sampleRate)
                    .put("requested_sample_rate", requestedRate).put("actual_sample_rate", info.sampleRate)
                    .put("actual_client_sample_rate", info.sampleRate).put("source_channels", 1)
                    .put("channels", session.outputChannels).put("encoding", "PCM_16BIT")
                    .put("source_data_bytes", info.dataBytes).put("source_frames", info.dataBytes / 2)
                    .put("conversion", if (session.outputChannels == 2) "MONO_TO_DUAL_MONO_STEREO" else "NONE")
                    .put("sample_rate_conversion", "NONE").put("source_file_modified", false)
                    .put("sample_rate_conversion_reason", "Source rate is a native telephony-profile rate; software resampling is disabled to preserve the VQ reference")
                    .put("channel_selection", if (session.outputChannels == 2) "STEREO_INCALL_PROFILE_COMPATIBILITY" else "ADVERTISED_MONO_ONLY")
                    .put("requested_device_info", deviceJson(sink))
                    .put("audio_mode", audio.mode).put("audio_mode_changed", false)
                    .put("media_stream_volume_index", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("media_stream_max_volume_index", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .put("microphone_before", audio.isMicrophoneMute)
                    .put("speakerphone_on", audio.isSpeakerphoneOn)
            }
            if (audio.mode != AudioManager.MODE_IN_CALL) throw error("AUDIO_MODE_NOT_IN_CALL", snapshot(session))
            persist(session)
            graph.audio.emit(session.commandId, session.call.getString("call_id"), "AUDIO_TX_STARTING",
                snapshot(session).put("audio_event_id", "${session.operationId}:TX_STARTING"))
            acquireMicLease(session)
            checkCallAndStop(session)
            val channelMask = if (session.outputChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(info.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0 || minimum > 2 * 1024 * 1024) throw error("AUDIO_TRACK_FORMAT_UNSUPPORTED")
            val frameBytes = session.outputChannels * 2
            val bufferBytes = maxOf(minimum, info.sampleRate * frameBytes / 10).let { it + (frameBytes - it % frameBytes) % frameBytes }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelMask).setSampleRate(info.sampleRate).build())
                .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM).build()
            synchronized(session.trackLock) { session.track = track }
            if (track.state != AudioTrack.STATE_INITIALIZED) throw error("AUDIO_TRACK_INITIALIZATION_FAILED")
            if (track.sampleRate != info.sampleRate || track.channelCount != session.outputChannels) {
                throw error("AUDIO_TRACK_CLIENT_FORMAT_MISMATCH")
            }
            update(session) {
                put("audio_session_id", track.audioSessionId).put("audio_track_state", track.state)
                    .put("buffer_size_frames", track.bufferSizeInFrames)
                    .put("actual_sample_rate", track.sampleRate).put("channels", track.channelCount)
            }
            checkCallAndStop(session)
            val preferredAccepted = track.setPreferredDevice(sink)
            update(session) { put("set_preferred_device_accepted", preferredAccepted) }
            if (!preferredAccepted) throw error("TELEPHONY_AUDIO_ROUTE_FAILED",
                JSONObject().put("route_failure", "PREFERRED_DEVICE_REJECTED"))
            track.setVolume(0f)
            listener = AudioRouting.OnRoutingChangedListener {
                val routed = try { track.routedDevice } catch (_: Exception) { null }
                val duringCleanup = session.closing || session.canceled.get()
                // Preserve every callback, including null routes, repeated notifications and
                // callbacks dispatched during cleanup. Guard source audio before slow logging.
                if (!duringCleanup) {
                    if (routed != null && !matches(session, routed)) {
                        abort(session, "TELEPHONY_AUDIO_ROUTE_FAILED", routed)
                    } else if (routed == null && session.routeVerified) {
                        abort(session, "TELEPHONY_AUDIO_ROUTE_FAILED", null)
                    }
                }
                val observation = recordRouteCallback(session, track, routed, duringCleanup)
                publishRouteCallback(session, observation)
            }
            track.addOnRoutingChangedListener(listener, routingHandler)
            synchronized(session.trackLock) {
                checkCallAndStop(session)
                track.play()
            }
            establishSilentRoute(session, track)
            checkVerifiedRoute(session, track)
            synchronized(session.trackLock) {
                checkCallAndStop(session)
                if (track.setVolume(1f) != AudioTrack.SUCCESS) throw error("AUDIO_TRACK_VOLUME_FAILED")
            }
            streamSource(session, source, info, track)
        } catch (e: AudioOperationException) {
            if (!session.canceled.get() || session.failure.get() != null) session.failure.compareAndSet(null, e)
        } catch (e: IllegalArgumentException) {
            session.failure.compareAndSet(null, error(safeCode(e.message, "AUDIO_TX_INVALID_ARGUMENT")))
        } catch (_: SecurityException) {
            session.failure.compareAndSet(null, error("AUDIO_TX_PERMISSION_DENIED"))
        } catch (_: Exception) {
            if (!session.canceled.get()) session.failure.compareAndSet(null, error("AUDIO_TX_IO_FAILED"))
        } finally {
            session.closing = true
            synchronized(session.trackLock) {
                val track = session.track
                if (track != null) {
                    try { session.underruns = track.underrunCount } catch (_: Exception) { }
                    try { track.setVolume(0f) } catch (_: Exception) { }
                    try { track.pause() } catch (_: Exception) { }
                    try { track.flush() } catch (_: Exception) { }
                    try { listener?.let { track.removeOnRoutingChangedListener(it) } } catch (_: Exception) { }
                    try { track.release() } catch (_: Exception) { }
                    session.track = null
                }
            }
            // A routing callback may be logging while release completes. Drain callbacks
            // already queued before publishing the terminal history, with no track lock held.
            val callbacksFlushed = CountDownLatch(1)
            routingHandler.post { callbacksFlushed.countDown() }
            val callbackFlushComplete = try { callbacksFlushed.await(500, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            try { source?.close() } catch (_: Exception) { }
            if (referencePinned) {
                try { graph.files.releaseReference(session.parameters.getString("file_id")) } catch (_: Exception) { }
            }
            val micRestored = if (session.hadMicLease) session.micLease?.let { restoreLease(it) } ?: false else true
            if (!micRestored) session.failure.compareAndSet(null, error("MICROPHONE_RESTORE_FAILED"))
            update(session) {
                put("state", if (session.failure.get() == null) "STOPPED" else "ERROR").put("running", false)
                    .put("audio_event_id", "${session.operationId}:TX_TERMINAL")
                    .put("audio_stopped_at", now()).put("audio_stop_elapsed_ms", SystemClock.elapsedRealtime())
                    .put("reason", session.reason.get()).put("microphone_restored", micRestored)
                    .put("route_callback_flush_complete", callbackFlushComplete)
                    .put("microphone_after", microphoneState())
                    .put("error", session.failure.get()?.code ?: JSONObject.NULL)
                    .put("error_details", session.failure.get()?.details ?: JSONObject.NULL)
                    .put("underrun_count", session.underruns)
            }
            val final = snapshot(session)
            last = final
            try {
                persist(session)
                graph.audio.emit(session.commandId, session.call.getString("call_id"),
                    if (session.failure.get() == null) "AUDIO_TX_STOPPED" else "AUDIO_TX_FAILED", final)
                graph.store.putMeta(TX_SNAPSHOT, null)
            } catch (_: Exception) {
                // Keep the persisted terminal snapshot for restart recovery if the durable queue failed.
            }
            synchronized(lifecycle) { if (current === session) current = null }
            session.startReady.countDown()
            session.finished.countDown()
        }
    }

    /** Only zero samples enter AudioTrack until the actual sink is repeatedly confirmed. */
    private fun establishSilentRoute(session: Session, track: AudioTrack) {
        val silence = ByteArray(session.sourceRate / 100 * session.outputChannels * 2)
        val deadline = SystemClock.elapsedRealtime() + ROUTE_TIMEOUT_MS
        var confirmedAt = -1L
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCallAndStop(session)
            val route = track.routedDevice
            if (route != null) {
                if (!matches(session, route)) {
                    abort(session, "TELEPHONY_AUDIO_ROUTE_FAILED", route)
                    throw session.failure.get() ?: error("AUDIO_TX_CANCELED")
                }
                if (confirmedAt < 0) confirmedAt = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - confirmedAt >= 30) {
                    session.routeVerified = true
                    update(session) {
                        put("actual_device", "TYPE_TELEPHONY").put("actual_device_info", deviceJson(route))
                            .put("route_verified", true).put("route_verified_at", now())
                            .put("route_priming", "DIGITAL_SILENCE_ONLY")
                    }
                    return
                }
            } else confirmedAt = -1
            val count = synchronized(session.trackLock) {
                checkCallAndStop(session)
                track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            if (count < 0) throw error("AUDIO_TRACK_SILENCE_WRITE_FAILED", JSONObject().put("audio_track_error", count))
            session.silenceFrames.addAndGet(count.toLong() / (session.outputChannels * 2))
            session.totalFrames += count.toLong() / (session.outputChannels * 2)
            Thread.sleep(5)
        }
        throw error("TELEPHONY_AUDIO_ROUTE_FAILED", JSONObject().put("route_failure", "ROUTE_NOT_CONFIRMED_BEFORE_TIMEOUT"))
    }

    private fun streamSource(session: Session, file: RandomAccessFile, info: WavInfo, track: AudioTrack) {
        val sourceBuffer = ByteArray(maxOf(2, info.sampleRate / 100 * 2)) // 10 ms source frames.
        var lastPersist = SystemClock.elapsedRealtime()
        do {
            file.seek(info.dataOffset)
            var remaining = info.dataBytes
            while (remaining > 0) {
                checkCallAndStop(session)
                checkVerifiedRoute(session, track)
                val bytes = minOf(remaining, sourceBuffer.size.toLong()).toInt()
                file.readFully(sourceBuffer, 0, bytes)
                val output = if (session.outputChannels == 1) sourceBuffer else PcmChannelMapper.dualMono(sourceBuffer, bytes)
                val outputBytes = bytes * session.outputChannels
                var offset = 0
                var stalledAt = SystemClock.elapsedRealtime()
                while (offset < outputBytes) {
                    checkCallAndStop(session)
                    checkVerifiedRoute(session, track)
                    val written = synchronized(session.trackLock) {
                        checkCallAndStop(session)
                        checkVerifiedRoute(session, track)
                        track.write(output, offset, outputBytes - offset, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    if (written < 0) throw error("AUDIO_TRACK_WRITE_FAILED", JSONObject().put("audio_track_error", written))
                    if (written % (session.outputChannels * 2) != 0) throw error("AUDIO_TRACK_WRITE_ALIGNMENT_ERROR")
                    if (written == 0) {
                        if (SystemClock.elapsedRealtime() - stalledAt > 2_000) throw error("AUDIO_TRACK_WRITE_STALLED")
                        Thread.sleep(3)
                        continue
                    }
                    val frames = written.toLong() / (session.outputChannels * 2)
                    session.sourceFrames.addAndGet(frames)
                    session.totalFrames += frames
                    offset += written
                    stalledAt = SystemClock.elapsedRealtime()
                    checkCallAndStop(session)
                    checkVerifiedRoute(session, track)
                    session.underruns = track.underrunCount
                    frameHead(session, track)
                    if (session.startResult.get() == null) {
                        update(session) {
                            put("state", "ACTIVE").put("running", true).put("source_pcm_started", true)
                                .put("audio_started_at", now()).put("audio_start_elapsed_ms", SystemClock.elapsedRealtime())
                                .put("microphone_during", audio!!.isMicrophoneMute)
                        }
                        val started = snapshot(session)
                        persist(session)
                        checkCallAndStop(session)
                        synchronized(session.startLock) {
                            if (session.canceled.get()) throw error("AUDIO_TX_CANCELED")
                            session.failure.get()?.let { throw it }
                            if (SystemClock.elapsedRealtime() >= session.startDeadline) throw error("AUDIO_TX_START_TIMEOUT")
                            session.startResult.set(started)
                            session.startReady.countDown()
                        }
                        // The START was committed before any subsequent STOP request. Terminal
                        // emission uses this same worker, preserving ACTIVE -> terminal order.
                        graph.audio.emit(session.commandId, session.call.getString("call_id"), "AUDIO_TX_ACTIVE",
                            JSONObject(started.toString()).put("audio_event_id", "${session.operationId}:TX_ACTIVE"))
                    }
                    if (SystemClock.elapsedRealtime() - lastPersist > 2_000) {
                        persist(session)
                        lastPersist = SystemClock.elapsedRealtime()
                    }
                }
                remaining -= bytes
            }
            session.loops.incrementAndGet()
        } while (session.parameters.optBoolean("loop", false) && !session.canceled.get())
        // Drain accepted frames before releasing; elapsed playback head is client evidence only.
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (frameHead(session, track) < session.totalFrames) {
            checkCallAndStop(session)
            checkVerifiedRoute(session, track)
            if (SystemClock.elapsedRealtime() > deadline) throw error("AUDIO_TRACK_DRAIN_TIMEOUT")
            Thread.sleep(5)
        }
    }

    private fun acquireMicLease(session: Session) {
        checkCallAndStop(session)
        if (!session.parameters.optBoolean("mute_microphone", true)) {
            update(session) { put("microphone_during", audio!!.isMicrophoneMute).put("microphone_changed_by_probe", false) }
            return
        }
        val previous = audio!!.isMicrophoneMute
        val lease = JSONObject().put("operation_id", session.operationId).put("command_id", session.commandId)
            .put("call_id", session.call.getString("call_id")).put("previous_state", previous).put("created_at", now())
        session.micLease = lease
        session.hadMicLease = true
        val encoded = lease.toString()
        graph.store.putMeta(MIC_LEASE, encoded)
        if (graph.store.getMeta(MIC_LEASE) != encoded) throw error("MICROPHONE_LEASE_PERSIST_FAILED")
        checkCallAndStop(session)
        audio.isMicrophoneMute = true
        if (!audio.isMicrophoneMute) throw error("MICROPHONE_MUTE_NOT_CONFIRMED")
        session.micIsolationArmed = true
        update(session) {
            put("microphone_before", previous).put("microphone_during", true).put("microphone_changed_by_probe", !previous)
                .put("microphone_isolation_proof", "Framework microphone mute; modem/HAL isolation still requires the physical test")
        }
    }

    private fun restoreLease(lease: JSONObject): Boolean = try {
        val previous = lease.getBoolean("previous_state")
        audio?.isMicrophoneMute = previous
        val restored = audio != null && audio.isMicrophoneMute == previous
        if (restored) graph.store.putMeta(MIC_LEASE, null)
        restored
    } catch (_: Exception) { false }

    private fun microphoneState(): Any = try { audio?.isMicrophoneMute ?: JSONObject.NULL } catch (_: Exception) { JSONObject.NULL }

    private fun requestStop(session: Session, reason: String) {
        synchronized(session.startLock) {
            session.reason.set(reason)
            session.canceled.set(true)
        }
        synchronized(session.trackLock) {
            session.track?.let {
                try { it.setVolume(0f) } catch (_: Exception) { }
                try { it.pause() } catch (_: Exception) { }
                try { it.flush() } catch (_: Exception) { }
            }
        }
    }

    private fun abort(session: Session, code: String, actual: AudioDeviceInfo?) {
        synchronized(session.startLock) {
            if (session.canceled.get() || session.closing) return
            session.failure.compareAndSet(null, error(code, JSONObject()
                .put("requested_device", "TYPE_TELEPHONY").put("requested_device_id", session.routeId)
                .put("actual_device_info", actual?.let { deviceJson(it) } ?: JSONObject.NULL)
                .put("route_failure", if (actual == null) "ACTUAL_ROUTE_UNAVAILABLE" else "ACTUAL_ROUTE_DIFFERS_FROM_REQUESTED")))
        }
        session.routeVerified = false
        update(session) {
            put("route_verified", false).put("route_lost_at", now())
                .put("actual_device", actual?.let { if (it.type == AudioDeviceInfo.TYPE_TELEPHONY) "TYPE_TELEPHONY" else "OTHER_${it.type}" } ?: JSONObject.NULL)
                .put("actual_device_info", actual?.let { deviceJson(it) } ?: JSONObject.NULL)
        }
        requestStop(session, code)
    }

    private fun checkCallAndStop(session: Session) {
        session.failure.get()?.let { throw it }
        if (session.canceled.get()) throw error("AUDIO_TX_CANCELED")
        if (session.startResult.get() == null && SystemClock.elapsedRealtime() >= session.startDeadline) {
            throw error("AUDIO_TX_START_TIMEOUT")
        }
        val call = graph.telecom.status()
        if (call.optString("state") != "ACTIVE" || !call.optBoolean("probe_call_owned") ||
            call.optString("call_id") != session.call.getString("call_id")) {
            throw error("CALL_LEFT_ACTIVE")
        }
        if (session.micIsolationArmed && !audio!!.isMicrophoneMute) {
            throw error("MICROPHONE_ISOLATION_LOST")
        }
    }

    private fun checkVerifiedRoute(session: Session, track: AudioTrack) {
        if (session.canceled.get()) throw error("AUDIO_TX_CANCELED")
        val route = track.routedDevice
        if (!session.routeVerified || route == null || !matches(session, route)) {
            if (session.canceled.get()) throw error("AUDIO_TX_CANCELED")
            abort(session, "TELEPHONY_AUDIO_ROUTE_FAILED", route)
            throw session.failure.get() ?: error("AUDIO_TX_CANCELED")
        }
    }

    private fun matches(session: Session, device: AudioDeviceInfo) =
        device.isSink && device.type == AudioDeviceInfo.TYPE_TELEPHONY && device.id == session.routeId

    private fun recordRouteCallback(session: Session, track: AudioTrack, actual: AudioDeviceInfo?, duringCleanup: Boolean): JSONObject {
        val index = session.routeCallbacks.incrementAndGet()
        val observation = JSONObject().put("audio_event_id", "${session.operationId}:TX_ROUTE:$index")
            .put("operation_id", session.operationId).put("file_id", session.parameters.getString("file_id"))
            .put("call_command_id", session.call.opt("command_id") ?: JSONObject.NULL)
            .put("mode", session.parameters.getString("mode"))
            .put("callback_index", index).put("callback_timestamp", now())
            .put("callback_elapsed_ms", SystemClock.elapsedRealtime())
            .put("requested_device", "TYPE_TELEPHONY").put("requested_device_id", session.routeId)
            .put("actual_device", actual?.let { typeName(it) } ?: JSONObject.NULL)
            .put("actual_device_info", actual?.let { deviceJson(it) } ?: JSONObject.NULL)
            .put("route_matches_requested", actual?.let { matches(session, it) } ?: false)
            .put("during_cleanup", duringCleanup)
            .put("guard_stop_requested", session.canceled.get())
            .put("audio_track_state", try { track.state } catch (_: Exception) { JSONObject.NULL })
            .put("play_state", try { track.playState } catch (_: Exception) { JSONObject.NULL })
            .put("frames_written", session.sourceFrames.get())
            .put("microphone_muted", microphoneState())
        update(session) {
            val history = getJSONArray("route_callback_history")
            history.put(observation)
            while (history.length() > ROUTE_HISTORY_LIMIT) history.remove(0)
            put("route_callback_count", index).put("route_callback_history_dropped", (index - ROUTE_HISTORY_LIMIT).coerceAtLeast(0))
                .put("last_route_callback", observation)
        }
        return observation
    }

    private fun publishRouteCallback(session: Session, observation: JSONObject) {
        try {
            // Unlike the concise general audio logger, retain requested and actual route in
            // each local record as well as the durable orchestrator event.
            graph.log.write("audio_tx", "ROUTING_CALLBACK", observation,
                commandId = session.commandId, callId = session.call.getString("call_id"))
            graph.audio.emit(session.commandId, session.call.getString("call_id"), "AUDIO_TX_ROUTE_CHANGED", observation)
        } catch (_: Exception) {
            session.routeCallbackLogFailures.incrementAndGet()
            // Retained history still exposes the callback. Stop source audio if evidence could
            // not be durably queued; do not silently continue an uninstrumented validation.
            synchronized(session.startLock) {
                if (!session.closing && !session.canceled.get()) {
                    session.failure.compareAndSet(null, error("AUDIO_ROUTE_LOGGING_FAILED"))
                }
            }
            if (!session.closing && !session.canceled.get()) requestStop(session, "AUDIO_ROUTE_LOGGING_FAILED")
        }
    }

    private fun frameHead(session: Session, track: AudioTrack): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (raw < session.lastHeadRaw) session.headWraps += 1L shl 32
        session.lastHeadRaw = raw
        return session.headWraps + raw
    }

    private fun snapshot(session: Session): JSONObject = synchronized(session.dataLock) {
        JSONObject(session.data.toString()).put("frames_written", session.sourceFrames.get())
            .put("silence_priming_frames", session.silenceFrames.get()).put("completed_loops", session.loops.get())
            .put("underrun_count", session.underruns).put("stop_requested", session.canceled.get())
            .put("route_callback_log_failures", session.routeCallbackLogFailures.get())
    }
    private fun update(session: Session, block: JSONObject.() -> Unit) = synchronized(session.dataLock) { session.data.block() }
    private fun persist(session: Session) { graph.store.putMeta(TX_SNAPSHOT, snapshot(session).toString()) }
    private fun readMeta(key: String): JSONObject? = try { graph.store.getMeta(key)?.let { JSONObject(it) } } catch (_: Exception) { null }
    private fun requirePermission(permission: String) {
        if (graph.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw error("AUDIO_PRIVILEGED_PERMISSION_REQUIRED", JSONObject().put("permission", permission))
        }
    }
    private fun sha256(file: RandomAccessFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.seek(0)
        while (true) { val size = file.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun deviceJson(device: AudioDeviceInfo): JSONObject = JSONObject().put("id", device.id)
        .put("type", device.type).put("type_name", typeName(device))
        .put("is_sink", device.isSink).put("address", device.address.take(200))
        .put("sample_rates", JSONArray(device.sampleRates.toList())).put("channel_counts", JSONArray(device.channelCounts.toList()))
    private fun typeName(device: AudioDeviceInfo): String = if (device.type == AudioDeviceInfo.TYPE_TELEPHONY) "TYPE_TELEPHONY" else "OTHER_${device.type}"
    private fun safeCode(text: String?, fallback: String): String = text?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: fallback
    private fun error(code: String, details: JSONObject = JSONObject()) = AudioOperationException(code, details)
    private fun now(): String = Instant.now().toString()

    companion object {
        private const val MIC_LEASE = "audio_tx_microphone_lease"
        private const val TX_SNAPSHOT = "audio_tx_active"
        private const val START_TIMEOUT_MS = 6_000L
        private const val STOP_TIMEOUT_MS = 1_500L
        private const val ROUTE_TIMEOUT_MS = 2_500L
        private const val ROUTE_HISTORY_LIMIT = 32
    }
}

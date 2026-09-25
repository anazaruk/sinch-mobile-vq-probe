package com.sinch.vqprobe.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.sinch.vqprobe.ProbeGraph
import org.json.JSONObject
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/** Privileged digital telephony capture only. There is deliberately no microphone fallback. */
@SuppressLint("MissingPermission")
class CallAudioCaptureController(private val graph: ProbeGraph) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "probe-digital-rx") }
    private val lock = Any()
    private var active: Session? = null
    private var initializationProbeRunning = false
    private var lastInitializationProbe: JSONObject? = null
    @Volatile private var publicState = JSONObject().put("state", "IDLE")

    private class Session(val commandId: String, val callId: String, val callCommandId: String,
                          val fileId: String, val rate: Int, val source: Int, val direction: String) {
        val cancelled = AtomicBoolean(false)
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        @Volatile var stopReason = "COMMAND"
        @Volatile var initial: JSONObject? = null
        @Volatile var terminal: JSONObject? = null
        @Volatile var failure: AudioOperationException? = null
    }

    fun start(commandId: String, parameters: JSONObject): JSONObject {
        requirePermission(Manifest.permission.RECORD_AUDIO, "RECORD_AUDIO_PERMISSION_REQUIRED")
        requirePermission("android.permission.CAPTURE_AUDIO_OUTPUT", "CAPTURE_AUDIO_OUTPUT_PERMISSION_REQUIRED")
        val context = graph.audio.callContext()
        val direction = parameters.optString("direction", "DOWNLINK").uppercase()
        val source = when (direction) {
            "DOWNLINK" -> MediaRecorder.AudioSource.VOICE_DOWNLINK
            "UPLINK" -> MediaRecorder.AudioSource.VOICE_UPLINK
            "BOTH" -> MediaRecorder.AudioSource.VOICE_CALL
            else -> throw AudioOperationException("INVALID_CAPTURE_DIRECTION", JSONObject())
        }
        val rate = parameters.optInt("sample_rate", 16000)
        if (rate !in setOf(8000, 16000, 48000)) throw AudioOperationException("UNSUPPORTED_CAPTURE_SAMPLE_RATE", JSONObject().put("requested_sample_rate", rate))
        val id = parameters.optString("file_id")
        if (!id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) throw AudioOperationException("INVALID_FILE_ID", JSONObject())
        val session = Session(commandId, context.getString("call_id"), context.getString("command_id"), id, rate, source, direction)
        synchronized(lock) {
            if (active != null) throw AudioOperationException("CAPTURE_ALREADY_ACTIVE", status())
            if (initializationProbeRunning) throw AudioOperationException("CAPTURE_DIAGNOSTIC_BUSY", JSONObject())
            graph.artifacts.create(id, rate, base(session).put("capture_requested_at", Instant.now().toString()))
            active = session
            publicState = base(session).put("state", "STARTING")
        }
        try { worker.execute { capture(session) } } catch (e: Exception) {
            graph.artifacts.abandonWriter(id)
            synchronized(lock) { if (active === session) active = null }
            throw AudioOperationException("CAPTURE_WORKER_UNAVAILABLE", JSONObject())
        }
        try {
            if (!session.ready.await(6, TimeUnit.SECONDS)) {
                synchronized(session) {
                    // Publication may have won immediately after await timed out.
                    session.initial?.let { return it }
                    session.stopReason = "START_TIMEOUT"
                    session.cancelled.set(true)
                }
                throw AudioOperationException("CAPTURE_START_TIMEOUT", status())
            }
        } catch (e: InterruptedException) {
            synchronized(session) { session.stopReason = "START_INTERRUPTED"; session.cancelled.set(true) }
            Thread.currentThread().interrupt()
            throw AudioOperationException("CAPTURE_START_INTERRUPTED", status())
        }
        session.failure?.let { throw it }
        return session.initial ?: throw AudioOperationException("CAPTURE_START_CANCELLED", session.terminal ?: JSONObject())
    }

    /** Stop returns finalized metadata when ready, or explicit stopping=true with a later event. */
    fun stop(reason: String = "COMMAND"): JSONObject {
        val session = synchronized(lock) { active } ?: return JSONObject().put("state", "IDLE").put("already_stopped", true)
        synchronized(session) { session.stopReason = reason; session.cancelled.set(true) }
        if (session.done.await(3, TimeUnit.SECONDS)) return session.terminal ?: status()
        return base(session).put("state", "STOPPING").put("stopping", true)
    }

    fun status(): JSONObject = JSONObject(publicState.toString())

    /**
     * Allocate/release VOICE_DOWNLINK only. Never start recording, request a foreground
     * service, select MIC, or infer that initialization proves the telephony signal path.
     * The lease prevents a concurrent START_RECORDING from changing the capture policy.
     */
    fun downlinkInitialization(probe: Boolean = false): JSONObject {
        val call = runCatching { graph.audio.callContext() }.getOrNull()
        val result = JSONObject().put("available", JSONObject.NULL).put("attempted", false)
            .put("requested", probe).put("source", "VOICE_DOWNLINK").put("sample_rate", 16000)
            .put("channels", 1).put("encoding", "PCM_16BIT")
            .put("scope", "AUDIORECORD_INITIALIZATION_ONLY").put("recording_started", false)
            .put("recording_started_by_diagnostic", false)
            .put("route_verified", JSONObject.NULL).put("signal_verified", JSONObject.NULL)
            .put("observed_at", Instant.now().toString()).put("observed_elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("call_id", call?.opt("call_id") ?: JSONObject.NULL)
            .put("call_command_id", call?.opt("command_id") ?: JSONObject.NULL)
            .put("audio_record_state", JSONObject.NULL).put("recording_state", JSONObject.NULL)
            .put("initialization_is_running_capture_proof", false)
        if (call == null) return result.put("reason", "ACTIVE_OWNED_CALL_REQUIRED")
        val missing = listOf(Manifest.permission.RECORD_AUDIO, "android.permission.CAPTURE_AUDIO_OUTPUT")
            .filter { graph.context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) return result.put("available", false)
            .put("reason", "REQUIRED_PERMISSION_NOT_GRANTED").put("missing_permissions", org.json.JSONArray(missing))
        synchronized(lock) {
            val session = active
            if (session != null) {
                val current = status()
                val config = current.optJSONObject("recording_configuration")
                val age = config?.optLong("observed_elapsed_realtime_ms", -1)?.let { SystemClock.elapsedRealtime() - it }
                if (session.direction == "DOWNLINK" && !session.cancelled.get() &&
                    session.callId == call.optString("call_id") && current.optString("state") == "RECORDING" &&
                    config?.optBoolean("source_verified") == true && !config.optBoolean("client_silenced", true) &&
                    age != null && age in 0..2_000) {
                    return result.put("available", true).put("attempted", true).put("attempted_by", "START_RECORDING")
                        .put("evidence", "EXISTING_VERIFIED_DOWNLINK_SESSION")
                        .put("initialization_probe_skipped", "CAPTURE_ALREADY_RUNNING")
                        .put("existing_recording_active", true)
                        .put("audio_record_state_scope", "LIVE_SESSION_OBSERVATION")
                        .put("sample_rate", session.rate).put("capture_command_id", session.commandId)
                        .put("observed_at", config.opt("observed_at") ?: JSONObject.NULL)
                        .put("observed_elapsed_realtime_ms", config.getLong("observed_elapsed_realtime_ms"))
                        .put("evidence_age_ms", age).put("audio_record_state", config.opt("audio_record_state") ?: JSONObject.NULL)
                        .put("recording_state", config.opt("recording_state") ?: JSONObject.NULL)
                        .put("recording_configuration", config)
                }
                return result.put("reason", "CAPTURE_BUSY_NO_VERIFIED_DOWNLINK_EVIDENCE")
            }
            if (initializationProbeRunning) return result.put("reason", "INITIALIZATION_PROBE_BUSY")
            if (!probe) {
                val previous = lastInitializationProbe
                val age = previous?.optLong("observed_elapsed_realtime_ms", -1)?.let { SystemClock.elapsedRealtime() - it }
                if (previous != null && previous.optString("call_id") == call.optString("call_id") && age != null && age in 0..60_000) {
                    return JSONObject(previous.toString()).put("requested", false).put("cached", true).put("evidence_age_ms", age)
                }
                return result.put("reason", "INITIALIZATION_NOT_PROBED_FOR_CURRENT_CALL")
            }
            initializationProbeRunning = true
        }
        var record: AudioRecord? = null
        var operation = "AudioRecord.getMinBufferSize"
        try {
            result.put("attempted", true).put("attempted_by", "CAPABILITY_PROBE")
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            result.put("minimum_buffer_size_bytes", minimum)
            check(minimum > 0) { "CAPTURE_FORMAT_UNSUPPORTED" }
            operation = "AudioRecord.Builder.build"
            record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 6400).coerceAtMost(1024 * 1024)).build()
            operation = "AudioRecord.getState"
            val observation = recorderObservation(record)
            observation.keys().forEach { result.put(it, observation.get(it)) }
            result.put("audio_record_state_scope", "INITIALIZATION_OBSERVED_BEFORE_RELEASE")
            check(record.state == AudioRecord.STATE_INITIALIZED) { "CAPTURE_INITIALIZATION_FAILED" }
            check(record.audioSource == MediaRecorder.AudioSource.VOICE_DOWNLINK) { "CAPTURE_SOURCE_MISMATCH" }
            check(record.sampleRate == 16000 && record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "CAPTURE_CLIENT_FORMAT_MISMATCH" }
            result.put("available", true).put("evidence", "AUDIORECORD_STATE_INITIALIZED")
        } catch (e: Exception) {
            result.put("available", false).put("error", code(e)).put("operation", operation)
                .put("exception", exceptionDetails(e))
            record?.let { value -> recorderObservation(value).let { data -> data.keys().forEach { result.put(it, data.get(it)) } } }
        } finally {
            var releaseError: Exception? = null
            try { record?.release() } catch (e: Exception) { releaseError = e }
            result.put("released", record != null && releaseError == null)
                .put("observed_at", Instant.now().toString()).put("observed_elapsed_realtime_ms", SystemClock.elapsedRealtime())
            if (releaseError != null) result.put("release_exception", exceptionDetails(releaseError))
            val current = runCatching { graph.audio.callContext() }.getOrNull()
            val sameCall = current?.optString("call_id") == call.optString("call_id")
            result.put("call_context_current", sameCall)
            if (!sameCall) result.put("initialization_observed", result.opt("available") ?: JSONObject.NULL)
                .put("available", JSONObject.NULL).put("reason", "CALL_CHANGED_DURING_INITIALIZATION_PROBE")
            synchronized(lock) {
                lastInitializationProbe = JSONObject(result.toString())
                initializationProbeRunning = false
            }
        }
        return result
    }

    fun recover() {
        val recovered = graph.artifacts.recover()
        for (i in 0 until recovered.length()) {
            val artifact = recovered.getJSONObject(i)
            val commandId = artifact.optString("command_id")
            val callId = artifact.optString("call_id")
            if (commandId.isNotBlank() && callId.isNotBlank()) {
                graph.audio.emit(commandId, callId, "AUDIO_RX_RECOVERED", artifact)
                graph.artifacts.updateMetadata(artifact.getString("file_id"), JSONObject().put("terminal_event_pending", false))
            } else graph.log.write("audio_rx", "ARTIFACT_RECOVERY", artifact, "WARN")
        }
    }

    private fun capture(session: Session) {
        var recorder: AudioRecord? = null
        var output: RandomAccessFile? = null
        var foregroundRequested = false
        var frames = 0L
        var nonzero = 0L
        var energy = 0.0
        var peak = 0
        var startedAt: String? = null
        var startedElapsed: Long? = null
        var config = JSONObject().put("requested_source", sourceName(session.source))
            .put("audio_record_state", JSONObject.NULL).put("recording_state", JSONObject.NULL)
        var error: String? = null
        var operation = "CAPTURE_PRECONDITIONS"
        var exception: JSONObject? = null
        try {
            if (session.cancelled.get()) throw IllegalStateException("CAPTURE_START_CANCELLED")
            assertCall(session)
            graph.audio.ensureCaptureForeground(true)
            foregroundRequested = true
            operation = "AudioRecord.getMinBufferSize"
            val minimum = AudioRecord.getMinBufferSize(session.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "CAPTURE_FORMAT_UNSUPPORTED" }
            val bufferSize = maxOf(minimum * 2, session.rate / 5 * 2).coerceAtMost(1024 * 1024)
            operation = "AudioRecord.Builder.build"
            val record = AudioRecord.Builder().setAudioSource(session.source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(session.rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(bufferSize).build()
            recorder = record
            config = recorderObservation(record)
            operation = "AudioRecord.getState"
            check(record.state == AudioRecord.STATE_INITIALIZED) { "CAPTURE_INITIALIZATION_FAILED" }
            check(record.audioSource == session.source) { "CAPTURE_SOURCE_MISMATCH" }
            check(record.sampleRate == session.rate && record.channelCount == 1 && record.audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "CAPTURE_CLIENT_FORMAT_MISMATCH" }
            if (session.cancelled.get()) throw IllegalStateException("CAPTURE_START_CANCELLED")
            operation = "AudioRecord.startRecording"
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "CAPTURE_START_FAILED" }
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (true) {
                if (session.cancelled.get()) throw IllegalStateException("CAPTURE_START_CANCELLED")
                assertCall(session)
                operation = "AudioRecord.getActiveRecordingConfiguration"
                config = inspect(record, session)
                if (config.optBoolean("source_verified")) break
                check(SystemClock.elapsedRealtime() < deadline) { "CAPTURE_CONFIGURATION_UNAVAILABLE" }
                Thread.sleep(20)
            }
            output = RandomAccessFile(graph.artifacts.writerFile(session.fileId), "rw")
            output.seek(44)
            startedAt = Instant.now().toString()
            startedElapsed = SystemClock.elapsedRealtime()
            val initial = base(session).put("state", "RECORDING").put("framework_started", true)
                .put("capture_started_at", startedAt).put("recording_configuration", config)
                .put("signal_detected", JSONObject.NULL).put("digital_path_verified", JSONObject.NULL)
                .put("verification_required", "COMPARE_KNOWN_FAR_END_SIGNAL_AND_ISOLATE_LOCAL_MICROPHONE")
            synchronized(session) {
                check(!session.cancelled.get()) { "CAPTURE_START_CANCELLED" }
                assertCall(session)
                graph.artifacts.updateMetadata(session.fileId, initial)
                graph.audio.emit(session.commandId, session.callId, "AUDIO_RX_RECORDING_STARTED", initial)
                publicState = JSONObject(initial.toString())
                session.initial = initial
                session.ready.countDown()
            }
            val samples = ShortArray((bufferSize / 2).coerceAtMost(8192))
            val bytes = ByteArray(samples.size * 2)
            var lastData = SystemClock.elapsedRealtime()
            var lastStatus = 0L
            while (!session.cancelled.get()) {
                assertCall(session)
                operation = "AudioRecord.getActiveRecordingConfiguration"
                config = inspect(record, session)
                check(config.optBoolean("source_verified")) { "CAPTURE_CONFIGURATION_LOST" }
                operation = "AudioRecord.read"
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw AudioOperationException(when (count) {
                    AudioRecord.ERROR_DEAD_OBJECT -> "CAPTURE_DEAD_OBJECT"
                    AudioRecord.ERROR_INVALID_OPERATION -> "CAPTURE_INVALID_OPERATION"
                    AudioRecord.ERROR_BAD_VALUE -> "CAPTURE_BAD_VALUE"
                    else -> "CAPTURE_READ_FAILED"
                }, JSONObject(config.toString()).put("read_return_code", count).put("read_mode", "READ_NON_BLOCKING"))
                if (count == 0) {
                    check(SystemClock.elapsedRealtime() - lastData < 5_000) { "CAPTURE_NO_PCM_DATA" }
                    Thread.sleep(10)
                    continue
                }
                // Discard the current chunk if routing/source changed during read.
                operation = "AudioRecord.getActiveRecordingConfiguration"
                config = inspect(record, session)
                check(config.optBoolean("source_verified")) { "CAPTURE_CONFIGURATION_LOST" }
                assertCall(session)
                if (session.cancelled.get()) break
                val capacity = ((WavPcm.MAX_DATA_BYTES - frames * 2) / 2).coerceAtLeast(0).toInt()
                val accepted = minOf(count, capacity)
                for (i in 0 until accepted) {
                    val value = samples[i].toInt()
                    bytes[i * 2] = (value and 255).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 255).toByte()
                }
                operation = "RandomAccessFile.write"
                output.write(bytes, 0, accepted * 2)
                // Only count samples after the full write succeeds.
                for (i in 0 until accepted) {
                    val value = samples[i].toInt()
                    if (value != 0) nonzero++
                    energy += value.toDouble() * value
                    peak = maxOf(peak, abs(value))
                }
                frames += accepted
                lastData = SystemClock.elapsedRealtime()
                if (lastData - lastStatus >= 1_000) {
                    publicState = JSONObject(initial.toString()).put("frames_captured", frames)
                        .put("signal_detected", nonzero > 0).put("recording_configuration", config)
                    lastStatus = lastData
                }
                if (frames * 2 >= WavPcm.MAX_DATA_BYTES) { session.stopReason = "ARTIFACT_BYTE_LIMIT"; break }
            }
        } catch (e: Exception) {
            error = code(e)
            if (e is AudioOperationException) config = e.details
            exception = exceptionDetails(e)
            config.put("failed_operation", operation).put("exception", exception)
            recorder?.let { value -> config.put("recorder_at_failure", recorderObservation(value)) }
            session.failure = if (e is AudioOperationException) e else AudioOperationException(error, config)
        } finally {
            // Close the record source before repairing/publishing the WAV.
            recorder?.let { record ->
                runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
                runCatching { record.release() }
            }
            runCatching { output?.fd?.sync() }
            runCatching { output?.close() }
            if (foregroundRequested) runCatching { graph.audio.ensureCaptureForeground(false) }
            if (error == null && session.stopReason in setOf("CALL_NOT_ACTIVE", "CALL_CHANGED")) error = "CAPTURE_CALL_NOT_ACTIVE"
            if (error == null && frames == 0L) error = "CAPTURE_EMPTY"
            if (error == null && nonzero == 0L) error = "CAPTURE_ALL_ZERO_PCM"
            val result = base(session).put("state", "STOPPED").put("status", if (error == null) "SUCCESS" else "FAILED")
                .put("error", error ?: JSONObject.NULL).put("stop_reason", session.stopReason)
                .put("capture_started_at", startedAt ?: JSONObject.NULL).put("capture_stopped_at", Instant.now().toString())
                .put("capture_elapsed_ms", startedElapsed?.let { SystemClock.elapsedRealtime() - it } ?: JSONObject.NULL)
                .put("frames_captured", frames).put("data_bytes", frames * 2).put("nonzero_frames", nonzero)
                .put("rms_pcm16", if (frames > 0) sqrt(energy / frames) else 0.0).put("peak_pcm16", peak)
                .put("all_zero_pcm", nonzero == 0L).put("signal_detected", nonzero > 0)
                .put("recording_configuration", config).put("digital_path_verified", JSONObject.NULL)
                .put("exception", exception ?: JSONObject.NULL)
                .put("voice_downlink_capture_available", JSONObject.NULL)
                .put("verification_required", "PHYSICAL_FAR_END_AND_MICROPHONE_ISOLATION_TEST")
                .put("terminal_event_pending", true)
                .put("audio_event_id", UUID.randomUUID().toString())
            try {
                val artifact = graph.artifacts.finalize(session.fileId, result)
                result.put("artifact", artifact)
                result.put("frames_captured", artifact.getLong("frames_captured"))
                    .put("data_bytes", artifact.getLong("data_bytes"))
                    .put("duration_seconds", artifact.getDouble("duration_seconds"))
                    .put("size_bytes", artifact.getLong("size_bytes")).put("sha256", artifact.getString("sha256"))
                if (artifact.getLong("frames_captured") != frames) {
                    for (field in listOf("nonzero_frames", "rms_pcm16", "peak_pcm16", "all_zero_pcm", "signal_detected")) {
                        result.put(field, JSONObject.NULL); artifact.put(field, JSONObject.NULL)
                    }
                    result.put("signal_statistics_reason", "PARTIAL_WRITE_RECOVERY_DIFFERENT_FRAME_COUNT")
                    graph.artifacts.updateMetadata(session.fileId, JSONObject().put("nonzero_frames", JSONObject.NULL)
                        .put("rms_pcm16", JSONObject.NULL).put("peak_pcm16", JSONObject.NULL)
                        .put("all_zero_pcm", JSONObject.NULL).put("signal_detected", JSONObject.NULL)
                        .put("signal_statistics_reason", "PARTIAL_WRITE_RECOVERY_DIFFERENT_FRAME_COUNT"))
                }
            } catch (e: Exception) {
                result.put("status", "FAILED").put("artifact_error", code(e)).put("partial_preserved", true)
                graph.artifacts.abandonWriter(session.fileId)
            }
            session.terminal = result
            publicState = JSONObject(result.toString())
            if (session.initial == null && session.failure == null) session.failure = AudioOperationException(error ?: "CAPTURE_START_CANCELLED", result)
            runCatching {
                graph.audio.emit(session.commandId, session.callId,
                    if (result.optString("status") == "SUCCESS") "AUDIO_RX_RECORDING_STOPPED" else "AUDIO_RX_FAILED", result)
                graph.artifacts.updateMetadata(session.fileId, JSONObject().put("terminal_event_pending", false))
            }
            synchronized(lock) { if (active === session) active = null }
            session.ready.countDown()
            session.done.countDown()
        }
    }

    private fun inspect(record: AudioRecord, session: Session): JSONObject {
        val config = record.activeRecordingConfiguration
        val routed = record.routedDevice
        val configured = config?.audioDevice
        val route = routed ?: configured
        val out = JSONObject().put("requested_source", sourceName(session.source))
            .put("observed_at", Instant.now().toString()).put("observed_elapsed_realtime_ms", SystemClock.elapsedRealtime())
            .put("audio_session_id", record.audioSessionId).put("audio_record_state", record.state)
            .put("audio_record_state_name", if (record.state == AudioRecord.STATE_INITIALIZED) "STATE_INITIALIZED" else "STATE_UNINITIALIZED")
            .put("recording_state", record.recordingState)
            .put("recording_state_name", if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) "RECORDSTATE_RECORDING" else "RECORDSTATE_STOPPED")
            .put("actual_input_device", route?.let { device(it) } ?: JSONObject.NULL)
            .put("routed_input_device", routed?.let { device(it) } ?: JSONObject.NULL)
            .put("configuration_input_device", configured?.let { device(it) } ?: JSONObject.NULL)
            .put("route_verified", if (route == null) JSONObject.NULL else route.type == AudioDeviceInfo.TYPE_TELEPHONY)
            .put("source_verified", false).put("client_silenced", JSONObject.NULL)
        if (listOfNotNull(routed, configured).any { it.type in MIC_ROUTES }) throw AudioOperationException("CAPTURE_MICROPHONE_ROUTE_REJECTED", out)
        if (record.state != AudioRecord.STATE_INITIALIZED) throw AudioOperationException("CAPTURE_INITIALIZATION_LOST", out)
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioOperationException("CAPTURE_NOT_RECORDING", out)
        if (config == null) return out.put("configuration_available", false)
        val client = config.clientFormat
        out.put("configuration_available", true)
            .put("client_source_id", config.clientAudioSource).put("capture_path_source_id", config.audioSource)
            .put("client_source", sourceName(config.clientAudioSource)).put("capture_path_source", sourceName(config.audioSource))
            .put("client_silenced", config.isClientSilenced).put("client_format", format(client)).put("device_format", format(config.format))
            .put("framework_format_conversion", client.sampleRate != config.format.sampleRate || client.channelCount != config.format.channelCount || client.encoding != config.format.encoding)
        if (config.isClientSilenced) throw AudioOperationException("CAPTURE_CLIENT_SILENCED", out)
        if (config.clientAudioSource != session.source || config.audioSource != session.source) throw AudioOperationException("CAPTURE_SOURCE_MISMATCH", out)
        if (client.sampleRate != session.rate || client.channelCount != 1 || client.encoding != AudioFormat.ENCODING_PCM_16BIT) throw AudioOperationException("CAPTURE_CLIENT_FORMAT_MISMATCH", out)
        return out.put("source_verified", true)
    }

    private fun base(session: Session) = JSONObject().put("command_id", session.commandId)
        .put("call_id", session.callId).put("call_command_id", session.callCommandId).put("file_id", session.fileId)
        .put("direction", session.direction).put("requested_source", sourceName(session.source))
        .put("requested_sample_rate", session.rate).put("sample_rate", session.rate)
        .put("channels", 1).put("bits_per_sample", 16).put("encoding", "PCM_16BIT")
        .put("application_conversion", false).put("max_file_bytes", WavPcm.MAX_FILE_BYTES)

    private fun assertCall(session: Session) {
        val call = try { graph.audio.callContext() } catch (_: Exception) { throw IllegalStateException("CAPTURE_CALL_NOT_ACTIVE") }
        check(call.optString("call_id") == session.callId) { "CAPTURE_CALL_CHANGED" }
    }
    private fun requirePermission(permission: String, error: String) {
        if (graph.context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw AudioOperationException(error, JSONObject())
    }
    private fun code(error: Exception): String = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) }
        ?: if (error is SecurityException) "CAPTURE_PERMISSION_OR_APP_OP_DENIED" else "CAPTURE_${error.javaClass.simpleName.uppercase()}"
    private fun format(value: AudioFormat) = JSONObject().put("sample_rate", value.sampleRate)
        .put("channels", value.channelCount).put("encoding", value.encoding)
    private fun recorderObservation(record: AudioRecord): JSONObject {
        val out = JSONObject()
        fun observe(key: String, action: () -> Any) { out.put(key, runCatching { action() }.getOrElse { JSONObject.NULL }) }
        observe("audio_record_state") { record.state }
        observe("recording_state") { record.recordingState }
        observe("audio_session_id") { record.audioSessionId }
        observe("client_source_id") { record.audioSource }
        observe("client_source") { sourceName(record.audioSource) }
        observe("actual_sample_rate") { record.sampleRate }
        observe("actual_channels") { record.channelCount }
        observe("actual_encoding") { record.audioFormat }
        return out
    }
    /** These messages originate only in audio APIs/file I/O; no credential is passed to them. */
    private fun exceptionDetails(error: Exception): JSONObject = JSONObject().put("type", error.javaClass.name)
        .put("message", error.message?.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")?.take(512) ?: JSONObject.NULL)
        .put("message_truncated", (error.message?.length ?: 0) > 512)
    private fun device(value: AudioDeviceInfo) = JSONObject().put("id", value.id).put("type", value.type)
        .put("type_name", if (value.type == AudioDeviceInfo.TYPE_TELEPHONY) "TYPE_TELEPHONY" else "OTHER_${value.type}")
        .put("is_source", value.isSource)
    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
        MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
        MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
        else -> "UNEXPECTED_$source"
    }
    companion object {
        private val MIC_ROUTES = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)
    }
}

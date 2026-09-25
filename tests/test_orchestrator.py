"""Protocol/security checks; no Android handset or production network required."""
import concurrent.futures
import copy
import hashlib
import http.client
import io
import json
import os
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest
import uuid
import wave

from orchestrator.server import (ACCESS_SECONDS, REFRESH_SECONDS, MAX_ARTIFACT, ApiError,
                                 Controller, Handler, ProbeHTTPServer, utc)


def wav_bytes(sample_rate=16000, channels=1, frames=800):
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(b"\x00\x10" * frames * channels)
    return output.getvalue()


class BoundedReadStream(io.BytesIO):
    def read(self, size=-1):
        if not 0 < size <= 65536:
            raise AssertionError("Artifact read must remain bounded")
        return super().read(size)


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.now = 1_800_000_000.0
        self.controller = Controller(os.path.join(self.directory.name, "probe.db"), clock=lambda: self.now)
        self.a = self.enroll("NJ-VZ-001")
        self.b = self.enroll("IL-ATT-001")

    def enrollment(self, name):
        return self.controller.create_enrollment(name, {"carrier": "Verizon", "download_allowed_hosts": ["media.example"]})

    def enroll(self, name):
        code = self.enrollment(name)
        return self.controller.enroll({"enrollment_code": code["enrollment_code"], "device_name": name, "app_version": "0.1"})

    def poll(self, device=None):
        device = device or self.a
        return self.controller.poll(device["access_token"], {"device_id": device["device_id"], "state": "IDLE"})

    def queue(self, action="CALL", parameters=None, device=None, **kwargs):
        device = device or self.a
        return self.controller.queue(device["device_id"], action,
                                     parameters if parameters is not None else {"number": "+12165551212"}, **kwargs)

    def result(self, command, **changes):
        payload = {"command_id": command["command_id"], "action": command["action"], "status": "SUCCESS",
                   "data": {}, "received_at": utc(self.now), "execution_at": utc(self.now), "completed_at": utc(self.now)}
        payload.update(changes)
        return payload

    def send_result(self, result, device=None):
        device = device or self.a
        return self.controller.results(device["access_token"], {"device_id": device["device_id"], "results": [result]})

    def expect_error(self, code, function, *args, **kwargs):
        with self.assertRaises(ApiError) as caught:
            function(*args, **kwargs)
        self.assertEqual(caught.exception.code, code)

    def test_enrollment_is_one_use_and_name_bound(self):
        code = self.enrollment("TX-TMO-001")
        request = {"enrollment_code": code["enrollment_code"], "device_name": "wrong", "app_version": "0.1"}
        self.expect_error("ENROLLMENT_NAME_MISMATCH", self.controller.enroll, request)
        request["device_name"] = "TX-TMO-001"
        credentials = self.controller.enroll(request)
        self.assertTrue(credentials["device_id"].startswith("probe-"))
        self.expect_error("INVALID_OR_USED_ENROLLMENT", self.controller.enroll, request)

    def test_concurrent_enrollment_only_one_wins(self):
        code = self.enrollment("single-use")
        request = {"enrollment_code": code["enrollment_code"], "device_name": "single-use", "app_version": "0.1"}
        def attempt():
            try:
                return self.controller.enroll(request)["device_id"]
            except ApiError as exc:
                return exc.code
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _: attempt(), range(4)))
        self.assertEqual(sum(x.startswith("probe-") for x in results), 1)
        self.assertEqual(results.count("INVALID_OR_USED_ENROLLMENT"), 3)

    def test_expired_enrollment_refused(self):
        code = self.enrollment("expired")
        self.now += 3601
        self.expect_error("INVALID_OR_USED_ENROLLMENT", self.controller.enroll,
                          {"enrollment_code": code["enrollment_code"], "device_name": "expired", "app_version": "0.1"})

    def test_device_id_cannot_override_bearer_owner(self):
        self.expect_error("DEVICE_MISMATCH", self.controller.poll, self.a["access_token"], {"device_id": self.b["device_id"]})
        self.expect_error("AUTHENTICATION_REQUIRED", self.controller.poll, None, {"device_id": self.a["device_id"]})
        self.expect_error("INVALID_ACCESS_TOKEN", self.controller.poll, "bad", {"device_id": self.a["device_id"]})

    def test_access_expiry_refresh_replay_and_old_access_overlap(self):
        refresh_body = {"device_id": self.a["device_id"], "refresh_token": self.a["refresh_token"]}
        replacement = self.controller.refresh(refresh_body)
        replay = self.controller.refresh(refresh_body)  # First refresh response was lost.
        self.assertEqual(replacement["refresh_token"], self.a["refresh_token"])
        self.assertEqual(replay["refresh_token"], self.a["refresh_token"])
        self.assertNotEqual(replacement["access_token"], replay["access_token"])
        self.assertEqual(self.poll()["action"], "NONE")
        self.assertEqual(self.poll(replacement)["action"], "NONE")
        self.now += ACCESS_SECONDS + 1
        self.expect_error("INVALID_ACCESS_TOKEN", self.poll)
        self.a = self.controller.refresh(refresh_body)
        self.assertEqual(self.poll()["action"], "NONE")

    def test_refresh_expiry_and_revocation(self):
        self.now += REFRESH_SECONDS + 1
        self.expect_error("INVALID_REFRESH_TOKEN", self.controller.refresh,
                          {"device_id": self.a["device_id"], "refresh_token": self.a["refresh_token"]})
        self.now -= REFRESH_SECONDS + 1
        self.controller.revoke(self.a["device_id"])
        self.expect_error("INVALID_ACCESS_TOKEN", self.poll)
        self.expect_error("INVALID_REFRESH_TOKEN", self.controller.refresh,
                          {"device_id": self.a["device_id"], "refresh_token": self.a["refresh_token"]})

    def test_stored_credentials_are_hashes(self):
        with self.controller.connection() as db:
            raw = json.dumps([dict(row) for row in db.execute("SELECT * FROM devices")])
            tokens = json.dumps([dict(row) for row in db.execute("SELECT * FROM access_tokens")])
        self.assertNotIn(self.a["refresh_token"], raw)
        self.assertNotIn(self.a["access_token"], tokens)
        self.assertIn(hashlib.sha256(self.a["refresh_token"].encode()).hexdigest(), raw)
        self.assertEqual(os.stat(self.controller.path).st_mode & 0o777, 0o600)

    def test_redelivery_acknowledgement_and_immutable_command_ids(self):
        call = self.queue(command_id="cmd-fixed")
        self.assertEqual(self.poll()["command_id"], call["command_id"])
        self.assertEqual(self.poll()["command_id"], call["command_id"])
        repeated = self.queue(command_id="cmd-fixed")
        self.assertEqual(repeated["expires_at"], call["expires_at"])
        self.expect_error("COMMAND_ID_CONFLICT", self.queue, command_id="cmd-fixed", parameters={"number": "+13055551212"})
        result = self.result(call)
        self.assertEqual(self.send_result(result), {"accepted": [call["command_id"]]})
        self.assertEqual(self.send_result(result), {"accepted": [call["command_id"]]})
        self.assertEqual(self.poll(), {"action": "NONE"})
        self.expect_error("RESULT_REPLAY_CONFLICT", self.send_result, dict(result, data={"changed": True}))

    def test_hangup_bypasses_wait_and_unacknowledged_call(self):
        wait = self.queue("WAIT", {"duration_ms": 10000})
        self.assertEqual(self.poll()["command_id"], wait["command_id"])
        call = self.queue()
        hangup = self.queue("HANGUP", {})
        self.assertEqual(self.poll()["command_id"], hangup["command_id"])
        self.send_result(self.result(hangup))
        self.assertEqual(self.poll()["command_id"], wait["command_id"])
        self.send_result(self.result(wait))
        self.assertEqual(self.poll()["command_id"], call["command_id"])
        urgent = self.queue("HANGUP", {})
        self.assertEqual(self.poll()["command_id"], urgent["command_id"])

    def test_status_can_bypass_blocked_command(self):
        self.queue("WAIT", {"duration_ms": 10000})
        inspect = self.queue("GET_STATUS", {})
        self.assertEqual(self.poll()["command_id"], inspect["command_id"])

    def test_command_result_ownership_and_batch_atomicity(self):
        call = self.queue()
        self.poll()
        foreign = self.queue(device=self.b)
        self.poll(self.b)
        self.expect_error("COMMAND_NOT_OWNED", self.send_result, self.result(foreign))
        self.expect_error("COMMAND_NOT_OWNED", self.controller.results, self.a["access_token"],
                          {"device_id": self.a["device_id"], "results": [self.result(call), self.result(foreign)]})
        self.assertEqual(self.poll()["command_id"], call["command_id"])
        undelivered = self.queue()
        self.expect_error("COMMAND_NOT_DELIVERED", self.send_result, self.result(undelivered))

    def test_expiry_and_delayed_result_for_execution_before_expiry(self):
        command = self.queue(ttl=5)
        self.poll()
        result = self.result(command)
        self.now += 6
        self.assertEqual(self.poll(), {"action": "NONE"})
        self.assertEqual(self.controller.inspect("results")[0]["status"], "EXPIRED")
        self.assertEqual(self.send_result(result)["accepted"], [command["command_id"]])
        late = self.queue(ttl=1)
        self.poll()
        self.now += 2
        self.expect_error("COMMAND_EXECUTED_AFTER_EXPIRY", self.send_result, self.result(late))
        self.assertEqual(self.send_result(self.result(late, status="FAILED", error="COMMAND_EXPIRED"))["accepted"], [late["command_id"]])

    def test_final_call_events_after_ack_and_event_replay(self):
        call = self.queue(ttl=1)
        self.poll()
        self.send_result(self.result(call))
        self.now += 10
        event = {"event_id": "event-1", "command_id": call["command_id"], "call_id": "call-1",
                 "event": "CALL_RESULT", "timestamp": utc(self.now), "data": {"connected_duration_ms": 5000}}
        body = {"device_id": self.a["device_id"], "events": [event]}
        self.assertEqual(self.controller.events(self.a["access_token"], body)["accepted"], ["event-1"])
        self.assertEqual(self.controller.events(self.a["access_token"], body)["accepted"], ["event-1"])
        tampered = copy.deepcopy(body)
        tampered["events"][0]["data"] = {}
        self.expect_error("EVENT_REPLAY_CONFLICT", self.controller.events, self.a["access_token"], tampered)
        forged = copy.deepcopy(body)
        forged["device_id"] = self.b["device_id"]
        forged["events"][0]["event_id"] = "new-event"
        self.expect_error("COMMAND_NOT_OWNED", self.controller.events, self.b["access_token"], forged)

    def test_unknown_expired_and_wrong_action_call_events_refused(self):
        command = self.queue("WAIT", {"duration_ms": 1000})
        self.poll()
        event = {"event_id": "event-1", "command_id": command["command_id"], "call_id": "call-1",
                 "event": "CALL_RESULT", "timestamp": utc(self.now), "data": {}}
        body = {"device_id": self.a["device_id"], "events": [event]}
        self.expect_error("CALL_EVENT_REQUIRES_CALL_COMMAND", self.controller.events, self.a["access_token"], body)
        event["command_id"] = "missing"
        self.expect_error("COMMAND_NOT_OWNED", self.controller.events, self.a["access_token"], body)
        call = self.queue(ttl=1)
        self.send_result(self.result(command))
        self.poll()
        self.now += 2
        self.poll()
        event["command_id"] = call["command_id"]
        self.expect_error("COMMAND_NOT_LIVE", self.controller.events, self.a["access_token"], body)

    def test_logs_allow_local_ids_enforce_ownership_and_forbid_credential_fields(self):
        log = {"log_id": "log-1", "device_id": self.a["device_id"], "timestamp": utc(self.now),
               "command_id": "local-1", "component": "telecom", "severity": "INFO", "event": "DIAL", "data": {}}
        body = {"device_id": self.a["device_id"], "logs": [log]}
        self.assertEqual(self.controller.logs(self.a["access_token"], body)["accepted"], ["log-1"])
        self.assertEqual(self.controller.logs(self.a["access_token"], body)["accepted"], ["log-1"])
        log["data"] = {"access_token": "should-not-persist"}
        self.expect_error("CREDENTIAL_FIELD_FORBIDDEN", self.controller.logs, self.a["access_token"], body)
        log["data"] = {}
        log["device_id"] = self.b["device_id"]
        self.expect_error("LOG_DEVICE_MISMATCH", self.controller.logs, self.a["access_token"], body)
        self.assertEqual(len(self.controller.inspect("logs", self.a["device_id"])), 1)

    def test_online_expires_without_heartbeat(self):
        self.assertEqual(self.controller.list_devices()[1]["connection"], "OFFLINE")
        self.poll()
        self.assertEqual(self.controller.list_devices()[1]["connection"], "ONLINE")
        self.now += 61
        self.assertEqual(self.controller.list_devices()[1]["connection"], "OFFLINE")

    def test_downloads_require_tls_allowed_host_and_sha256(self):
        params = {"url": "https://media.example/reference.wav", "file_id": "reference", "sha256": "a" * 64}
        self.queue("DOWNLOAD_FILE", params)
        self.expect_error("HTTPS_DOWNLOAD_URL_REQUIRED", self.queue, "DOWNLOAD_FILE", dict(params, url="http://media.example/reference.wav"))
        self.expect_error("DOWNLOAD_HOST_NOT_ALLOWED", self.queue, "DOWNLOAD_FILE", dict(params, url="https://other.example/reference.wav"))
        self.expect_error("SHA256_REQUIRED", self.queue, "DOWNLOAD_FILE", dict(params, sha256="bad"))
        self.queue("DOWNLOAD_FILE", dict(params, url="https://media.example:443/reference.wav"))
        for bad_url in ("https://media.example/reference.wav#part", "https://media.example/reference.wav#",
                        "https://@media.example/reference.wav", "https://media.example:bad/reference.wav",
                        "https://media.example:99999/reference.wav", "HTTPS://media.example/reference.wav"):
            with self.subTest(url=bad_url):
                self.expect_error("HTTPS_DOWNLOAD_URL_REQUIRED", self.queue, "DOWNLOAD_FILE", dict(params, url=bad_url))
        self.expect_error("DOWNLOAD_HOST_NOT_ALLOWED", self.queue, "DOWNLOAD_FILE", dict(params, url="https://media.example:8443/reference.wav"))

    def test_parameter_boundaries_match_android(self):
        for valid in (0, 3_600_000):
            self.queue("WAIT", {"duration_ms": valid})
        for invalid in (-1, 3_600_001, 86_400_000, True, "1000", 1.5):
            with self.subTest(duration_ms=invalid):
                self.expect_error("INVALID_WAIT_DURATION", self.queue, "WAIT", {"duration_ms": invalid})
        for valid in ("A", "a" * 64, "reference_01-v2"):
            self.queue("DELETE_FILE", {"file_id": valid})
        for invalid in ("", "a" * 65, "_leading", "-leading", "has.dot", "../escape"):
            with self.subTest(file_id=invalid):
                self.expect_error("INVALID_FILE_ID", self.queue, "DELETE_FILE", {"file_id": invalid})
        self.expect_error("INVALID_COMMAND_ID", self.queue, command_id="-invalid-leading-character")


    def test_audio_parameters_and_stop_inspection_priority(self):
        self.queue("WAIT", {"duration_ms": 10000})
        for action in ("PLAY_AUDIO", "START_RECORDING"):
            for rate in (8000, 16000, 48000):
                self.queue(action, {"file_id": "test", "sample_rate": rate})
            self.expect_error("INVALID_SAMPLE_RATE", self.queue, action, {"file_id": "test", "sample_rate": 44100})
            self.expect_error("INVALID_SAMPLE_RATE", self.queue, action, {"file_id": "test", "sample_rate": "16000"})
            self.expect_error("INVALID_FILE_ID", self.queue, action, {})
        for flag in ("loop", "mute_microphone"):
            self.expect_error("INVALID_" + flag.upper(), self.queue, "PLAY_AUDIO", {"file_id": "tx", flag: "true"})
        for direction in ("MIC", "VOICE_COMMUNICATION", [], None):
            self.expect_error("INVALID_AUDIO_DIRECTION", self.queue, "START_RECORDING", {"file_id": "rx", "direction": direction})
        status = self.queue("GET_AUDIO_STATUS", {})
        diagnostics = self.queue("GET_AUDIO_DIAGNOSTICS", {})
        stop = self.queue("STOP_AUDIO", {})
        stop_rx = self.queue("STOP_RECORDING", {})
        for command in (stop, stop_rx, status, diagnostics):
            self.assertEqual(self.poll()["command_id"], command["command_id"])
            self.send_result(self.result(command))

    def test_audio_events_require_original_audio_and_owned_call_commands(self):
        call = self.queue()
        self.poll()
        self.send_result(self.result(call, data={"call_id": "owned-call"}))
        play = self.queue("PLAY_AUDIO", {"file_id": "tx", "loop": False})
        self.poll()
        event = {"event_id": "tx-event", "command_id": play["command_id"], "call_id": "owned-call",
                 "event": "AUDIO_TX_ACTIVE", "timestamp": utc(self.now),
                 "data": {"call_command_id": call["command_id"], "far_end_audio_verified": False}}
        body = {"device_id": self.a["device_id"], "events": [event]}
        self.assertEqual(self.controller.events(self.a["access_token"], body)["accepted"], ["tx-event"])
        event["event_id"] = "tx-bad-call"
        event["call_id"] = "wrong-call"
        self.expect_error("AUDIO_CALL_ID_MISMATCH", self.controller.events, self.a["access_token"], body)
        event["call_id"] = "owned-call"
        event["event"] = "AUDIO_RX_RECORDING_STARTED"
        self.expect_error("AUDIO_EVENT_COMMAND_MISMATCH", self.controller.events, self.a["access_token"], body)
        event["event"] = "AUDIO_TX_ACTIVE"
        foreign = self.queue(device=self.b)
        self.poll(self.b)
        event["data"]["call_command_id"] = foreign["command_id"]
        self.expect_error("COMMAND_NOT_OWNED", self.controller.events, self.a["access_token"], body)
        event["data"]["call_command_id"] = play["command_id"]
        self.expect_error("AUDIO_EVENT_REQUIRES_LIVE_CALL_COMMAND", self.controller.events, self.a["access_token"], body)
        event["data"]["call_command_id"] = call["command_id"]
        event.pop("command_id")
        self.expect_error("AUDIO_EVENT_REQUIRES_COMMAND", self.controller.events, self.a["access_token"], body)

    def test_call_ids_cannot_be_rebound_to_other_device_or_command(self):
        call = self.queue()
        self.poll()
        self.send_result(self.result(call, data={"call_id": "global-call"}))
        foreign = self.queue(device=self.b)
        self.poll(self.b)
        self.expect_error("CALL_ID_BINDING_CONFLICT", self.send_result,
                          self.result(foreign, data={"call_id": "global-call"}), self.b)

    def upload(self, command, content=None, device=None, **changes):
        device = device or self.a
        content = wav_bytes() if content is None else content
        args = {"command_id": command["command_id"], "bearer": device["access_token"],
                "device_id": device["device_id"], "file_id": "rx", "expected_sha": hashlib.sha256(content).hexdigest(),
                "size": len(content), "stream": BoundedReadStream(content)}
        args.update(changes)
        return self.controller.upload_artifact(**args)

    def delivered_upload(self, device=None):
        command = self.queue("UPLOAD_FILE", {"file_id": "rx"}, device=device)
        self.poll(device)
        return command

    def test_artifact_streaming_idempotence_metadata_and_immutability(self):
        command = self.delivered_upload()
        content = wav_bytes(frames=100000)
        first = self.upload(command, content)
        self.assertEqual(first, self.upload(command, content))
        self.assertEqual(first["file_id"], "rx")
        self.assertEqual(first["size_bytes"], len(content))
        self.send_result(self.result(command, data=first))
        self.now += 301
        self.assertEqual(first, self.upload(command, content))  # Exact replay survives completed-command TTL.
        stored = self.controller.inspect("artifacts", self.a["device_id"])
        self.assertEqual(len(stored), 1)
        self.assertEqual(stored[0]["wav_info"]["frames"], 100000)
        self.assertEqual(stored[0]["wav_info"]["validation"], "PCM_WAV_FORMAT_ONLY")
        with open(stored[0]["path"], "rb") as source:
            self.assertEqual(source.read(), content)
        self.assertEqual(os.stat(stored[0]["path"]).st_mode & 0o777, 0o600)
        self.assertEqual(self.controller.inspect("artifacts", self.b["device_id"]), [])
        self.expect_error("ARTIFACT_REPLAY_CONFLICT", self.upload, command, wav_bytes(frames=900))
        self.assertFalse(any(name.endswith(".part") for name in os.listdir(self.controller.artifact_dir)))

    def test_artifact_authorization_file_binding_and_command_expiry(self):
        command = self.delivered_upload()
        self.expect_error("AUTHENTICATION_REQUIRED", self.upload, command, bearer=None)
        self.expect_error("DEVICE_MISMATCH", self.upload, command, device_id=self.b["device_id"])
        self.expect_error("COMMAND_NOT_OWNED", self.upload, command, device=self.b)
        self.expect_error("UPLOAD_FILE_ID_MISMATCH", self.upload, command, file_id="other")
        self.expect_error("INVALID_FILE_ID", self.upload, command, file_id="../escape")
        queued = self.queue("UPLOAD_FILE", {"file_id": "rx"})
        self.expect_error("UPLOAD_COMMAND_NOT_LIVE", self.upload, queued)
        wrong_action = self.queue("GET_STATUS", {})
        self.poll()
        self.expect_error("UPLOAD_FILE_COMMAND_REQUIRED", self.upload, wrong_action)
        self.now += 301
        self.expect_error("UPLOAD_COMMAND_EXPIRED", self.upload, command)
        self.assertFalse(os.path.exists(self.controller.artifact_dir))

    def test_bad_artifacts_never_publish_and_partial_files_are_removed(self):
        command = self.delivered_upload()
        content = wav_bytes()
        self.expect_error("SHA256_MISMATCH", self.upload, command, expected_sha="0" * 64)
        self.expect_error("INVALID_PCM_WAV", self.upload, command, content[:-2])
        self.expect_error("INVALID_PCM_WAV", self.upload, command, wav_bytes(channels=2))
        self.expect_error("INVALID_PCM_WAV", self.upload, command, wav_bytes(sample_rate=44100))
        self.expect_error("INCOMPLETE_BODY", self.upload, command, size=len(content) + 1)
        self.expect_error("BODY_TOO_LARGE", self.upload, command, size=MAX_ARTIFACT + 1)
        self.assertEqual(self.controller.inspect("artifacts"), [])
        self.assertEqual(os.listdir(self.controller.artifact_dir), [])

    def test_concurrent_artifact_replay_publishes_one_file(self):
        command = self.delivered_upload()
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            results = list(pool.map(lambda _: self.upload(command), range(3)))
        self.assertTrue(all(result == results[0] for result in results))
        self.assertEqual(len(self.controller.inspect("artifacts")), 1)
        self.assertEqual(len(os.listdir(self.controller.artifact_dir)), 1)

    def test_upload_audio_alias_and_recording_delete_kind(self):
        command = self.queue("UPLOAD_AUDIO", {"file_id": "rx"})
        self.poll()
        self.assertEqual(self.upload(command)["file_id"], "rx")
        self.queue("DELETE_FILE", {"file_id": "rx", "kind": "recording"})
        self.queue("DELETE_FILE", {"file_id": "tx", "kind": "reference"})
        for bad_kind in ("unknown", [], None):
            self.expect_error("INVALID_FILE_KIND", self.queue, "DELETE_FILE", {"file_id": "rx", "kind": bad_kind})
        self.expect_error("INVALID_FILE_ID", self.queue, "UPLOAD_AUDIO", {})

    def test_digital_tx_validation_requires_mute_and_diagnostics_probe_is_explicit(self):
        self.queue("PLAY_AUDIO", {"file_id": "tx", "mode": "DIGITAL_TX_VALIDATION"})
        self.queue("PLAY_AUDIO", {"file_id": "tx", "mode": "DIGITAL_TX_VALIDATION", "mute_microphone": True})
        self.queue("PLAY_AUDIO", {"file_id": "tx", "mode": "NORMAL", "mute_microphone": False})
        self.expect_error("DIGITAL_TX_VALIDATION_REQUIRES_MICROPHONE_MUTE", self.queue, "PLAY_AUDIO",
                          {"file_id": "tx", "mode": "DIGITAL_TX_VALIDATION", "mute_microphone": False})
        for mode in ("digital", [], None):
            self.expect_error("INVALID_AUDIO_MODE", self.queue, "PLAY_AUDIO", {"file_id": "tx", "mode": mode})
        for explicit in (True, False):
            self.queue("GET_AUDIO_DIAGNOSTICS", {"probe_downlink": explicit})
        for invalid in ("true", 1, None):
            self.expect_error("INVALID_PROBE_DOWNLINK", self.queue, "GET_AUDIO_DIAGNOSTICS", {"probe_downlink": invalid})


@unittest.skipUnless(shutil.which("openssl"), "HTTPS integration requires openssl to issue ephemeral test certificate")
class HttpsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        key = os.path.join(cls.directory.name, "key.pem")
        cert = os.path.join(cls.directory.name, "cert.pem")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key,
                        "-out", cert, "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.controller = Controller(os.path.join(cls.directory.name, "https.db"))
        cls.server = ProbeHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.controller = cls.controller
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True, do_handshake_on_connect=False)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.client_context = ssl.create_default_context(cafile=cert)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(2)
        cls.directory.cleanup()

    def request(self, path, body, headers=None):
        conn = http.client.HTTPSConnection("localhost", self.server.server_port, context=self.client_context, timeout=3)
        self.addCleanup(conn.close)
        conn.request("POST", path, body=json.dumps(body), headers={"Content-Type": "application/json", **(headers or {})})
        response = conn.getresponse()
        return response.status, json.loads(response.read())

    def test_https_enroll_poll_and_authorization(self):
        code = self.controller.create_enrollment("TLS-PHONE", {"carrier": "Verizon"})
        status, credentials = self.request("/api/v1/probe/enroll", {"enrollment_code": code["enrollment_code"], "device_name": "TLS-PHONE", "app_version": "0.1"})
        self.assertEqual(status, 200)
        body = {"device_id": credentials["device_id"]}
        self.assertEqual(self.request("/api/v1/probe/poll", body)[0], 401)
        self.assertEqual(self.request("/api/v1/probe/poll", body, {"Authorization": "Bearer " + credentials["access_token"]}), (200, {"action": "NONE"}))

    def test_request_size_and_json_content_type(self):
        conn = http.client.HTTPSConnection("localhost", self.server.server_port, context=self.client_context, timeout=3)
        self.addCleanup(conn.close)
        conn.request("POST", "/api/v1/probe/poll", body="{}", headers={"Content-Length": "1048577", "Content-Type": "application/json"})
        self.assertEqual(conn.getresponse().status, 413)
        self.assertEqual(self.request("/api/v1/probe/poll", {}, {"Content-Type": "text/plain"})[0], 415)


    def prepare_upload(self):
        name = "UPLOAD-" + uuid.uuid4().hex
        enrollment = self.controller.create_enrollment(name, {"carrier": "Verizon"})
        credentials = self.controller.enroll({"enrollment_code": enrollment["enrollment_code"], "device_name": name, "app_version": "0.2"})
        command = self.controller.queue(credentials["device_id"], "UPLOAD_FILE", {"file_id": "rx"})
        self.controller.poll(credentials["access_token"], {"device_id": credentials["device_id"]})
        return credentials, command

    def upload_request(self, credentials, command, content=None, **headers):
        content = wav_bytes() if content is None else content
        request_headers = {"Content-Type": "audio/wav", "X-Device-ID": credentials["device_id"],
                           "X-File-ID": "rx", "X-SHA256": hashlib.sha256(content).hexdigest(),
                           "Authorization": "Bearer " + credentials["access_token"]}
        request_headers.update(headers)
        conn = http.client.HTTPSConnection("localhost", self.server.server_port, context=self.client_context, timeout=3)
        self.addCleanup(conn.close)
        conn.request("POST", "/api/v1/probe/artifacts/" + command["command_id"], body=content, headers=request_headers)
        response = conn.getresponse()
        return response.status, json.loads(response.read())

    def test_https_artifact_upload_idempotence_and_device_ownership(self):
        credentials, command = self.prepare_upload()
        status, artifact = self.upload_request(credentials, command)
        self.assertEqual(status, 200)
        self.assertEqual(set(artifact), {"artifact_id", "file_id", "sha256", "size_bytes"})
        self.assertEqual(self.upload_request(credentials, command), (200, artifact))
        self.assertEqual(self.upload_request(credentials, command, wav_bytes(frames=200))[0], 409)
        foreign, _ = self.prepare_upload()
        self.assertEqual(self.upload_request(foreign, command)[0], 403)
        self.assertEqual(self.upload_request(credentials, command, Authorization="")[0], 401)
        conn = http.client.HTTPSConnection("localhost", self.server.server_port, context=self.client_context, timeout=3)
        self.addCleanup(conn.close)
        conn.request("GET", "/api/v1/probe/artifacts/" + artifact["artifact_id"],
                     headers={"Authorization": "Bearer " + foreign["access_token"]})
        response = conn.getresponse()
        self.assertEqual(response.status, 405)
        self.assertEqual(json.loads(response.read()), {"error": "POST_REQUIRED"})

    def test_https_artifact_limits_hash_and_truncated_wav(self):
        credentials, command = self.prepare_upload()
        self.assertEqual(self.upload_request(credentials, command, **{"Content-Length": str(MAX_ARTIFACT + 1)})[0], 413)
        self.assertEqual(self.upload_request(credentials, command, **{"Content-Type": "application/octet-stream"})[0], 415)
        self.assertEqual(self.upload_request(credentials, command, **{"X-SHA256": "0" * 64})[0], 422)
        self.assertEqual(self.upload_request(credentials, command, wav_bytes()[:-2])[0], 415)
        self.assertEqual(self.controller.inspect("artifacts", credentials["device_id"]), [])
        self.assertFalse(any(name.endswith(".part") for name in os.listdir(self.controller.artifact_dir)))


if __name__ == "__main__":
    unittest.main()

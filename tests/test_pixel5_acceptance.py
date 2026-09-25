"""Synthetic software fixtures only: none of these tests claim handset validation."""
import array
import copy
import json
from pathlib import Path
import tempfile
import unittest
import wave

from orchestrator.server import Controller
from tools.generate_test_audio import generate_bundle
from tools.pixel5_phaseb_acceptance import Runner, evaluate, marker_check, prepare, save_json, sha256, template


def attenuated_recording(source, destination):
    with wave.open(str(source), "rb") as reader:
        rate = reader.getframerate()
        values = array.array("h", reader.readframes(reader.getnframes()))
    values = array.array("h", [0] * (rate // 5) + [round(v * .5) for v in values] + [0] * (rate // 5))
    with wave.open(str(destination), "wb") as writer:
        writer.setparams((1, 2, rate, len(values), "NONE", "not compressed"))
        writer.writeframes(values.tobytes())


class AcceptanceEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.directory = Path(cls.temp.name)
        cls.manifest = generate_bundle(cls.directory / "markers", duration=4, run_id="acceptance-fixture-distinct-32")
        cls.refs = {d: cls.directory / "markers" / f"{d}-reference.wav" for d in ("tx", "rx")}
        cls.recordings = {d: cls.directory / f"{d}-received.wav" for d in ("tx", "rx")}
        for d in ("tx", "rx"):
            attenuated_recording(cls.refs[d], cls.recordings[d])
        cls.proof = cls.directory / "runtime.txt"
        cls.proof.write_text("Synthetic fixture only. No physical handset evidence.\n")
        cls.props = cls.directory / "getprop.txt"
        cls.props.write_text("[ro.product.model]: [Pixel 5]\n[ro.product.device]: [redfin]\n[ro.build.version.release]: [14]\n[ro.build.id]: [UP1A.231105.001.B2]\n")
        cls.policy = cls.directory / "policy.xml"
        cls.policy.write_text('<fixture>incall_music_uplink Telephony Tx Telephony Rx</fixture>\n')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def fixtures(self):
        state = {"run_id": "run-fixture", "device_id": "probe-fixture", "rx_file_id": "rx-fixture",
                 "tx": {"call_id": "tx-call"}, "rx": {"call_id": "rx-call"},
                 "commands": {"tx-call": "cmd-tx-call", "rx-call": "cmd-rx-call", "tx-play": "cmd-tx-play", "rx-start": "cmd-rx-start", "rx-upload": "cmd-rx-upload"},
                 "references": {d: {"path": str(self.refs[d]), "sha256": sha256(self.refs[d]),
                                    "frequencies_hz": self.manifest["files"][d]["frequencies_hz"]} for d in ("tx", "rx")}}
        state["rx"]["artifact"] = {"sha256": sha256(self.recordings["rx"]), "device_id": state["device_id"],
                                  "upload_command_id": state["commands"]["rx-upload"], "file_id": state["rx_file_id"]}
        evidence = template(state)
        entry = {"path": str(self.proof), "sha256": sha256(self.proof)}
        evidence["getprop"] = {"path": str(self.props), "sha256": sha256(self.props)}
        evidence["runtime_audio_policy"] = [{"path": str(self.policy), "sha256": sha256(self.policy)}]
        for d in ("tx", "rx"):
            evidence[d + "_bearer"].update(call_id=d + "-call", native_cellular=True, volte_confirmed=True, wifi_calling_disabled=True, evidence=[entry])
            isolation = evidence[d + "_isolation"]
            for key in isolation:
                if key not in ("call_id", "evidence"):
                    isolation[key] = True
            isolation.update(call_id=d + "-call", evidence=[entry])
        evidence["tx_recording"].update(call_id="tx-call", path=str(self.recordings["tx"]), sha256=sha256(self.recordings["tx"]), evidence=[entry])
        events = []
        for d in ("tx", "rx"):
            for name in ("DIALING", "ACTIVE", "CALL_RESULT"):
                events.append({"command_id": "cmd-" + d + "-call", "call_id": d + "-call", "event": name, "data": {}})
        def add(key, name, data):
            d = key.split("-")[0]
            events.append({"command_id": state["commands"][key], "call_id": d + "-call", "event": name,
                           "data": {"call_command_id": state["commands"][d + "-call"], **data}})
        add("tx-play", "AUDIO_TX_ACTIVE", {"mode": "DIGITAL_TX_VALIDATION", "requested_device": "TYPE_TELEPHONY",
            "actual_device": "TYPE_TELEPHONY", "route_verified": True, "microphone_during": True,
            "speaker_source_audio_enabled": False, "external_audio_fallback_allowed": False,
            "source_sha256": state["references"]["tx"]["sha256"]})
        add("tx-play", "AUDIO_TX_STOPPED", {"state": "STOPPED", "error": None, "reason": "END_OF_FILE",
            "frames_written": 64000, "source_frames": 64000, "microphone_restored": True, "route_callback_flush_complete": True})
        # Actual Android schema nests source/route data under recording_configuration.
        add("rx-start", "AUDIO_RX_RECORDING_STARTED", {"requested_source": "VOICE_DOWNLINK", "file_id": state["rx_file_id"],
            "recording_configuration": {"client_source": "VOICE_DOWNLINK", "capture_path_source": "VOICE_DOWNLINK",
                                        "source_verified": True, "client_silenced": False}})
        add("rx-start", "AUDIO_RX_RECORDING_STOPPED", {"status": "SUCCESS", "error": None, "stop_reason": "COMMAND", "frames_captured": 70400})
        return state, events, evidence

    def check(self, state, events, evidence, tx=None, rx=None):
        return evaluate(state, events, tx or self.recordings["tx"], rx or self.recordings["rx"], evidence, self.directory, max_lag_seconds=1)

    def test_complete_synthetic_checks_still_never_claim_physical_success(self):
        report = self.check(*self.fixtures())
        self.assertEqual(report["failed_gates"], [])
        self.assertTrue(report["automated_checks_passed"])
        self.assertEqual(report["status"], "AUTOMATED_CHECKS_PASSED_REVIEW_REQUIRED")
        self.assertIsNone(report["physical_tx_success"])
        self.assertIsNone(report["physical_rx_success"])

    def test_unknown_volte_and_missing_evidence_never_pass(self):
        state, events, evidence = self.fixtures()
        evidence["tx_bearer"]["volte_confirmed"] = None
        evidence["rx_isolation"]["evidence"] = []
        report = self.check(state, events, evidence)
        self.assertFalse(report["automated_checks_passed"])
        self.assertIn("tx_volte_evidence", report["failed_gates"])
        self.assertIn("rx_isolation_evidence", report["failed_gates"])

    def test_framework_evidence_and_nonzero_pcm_are_insufficient(self):
        state, events, evidence = self.fixtures()
        evidence = template(state)
        report = self.check(state, events, evidence, tx=self.recordings["rx"])
        self.assertFalse(report["automated_checks_passed"])
        self.assertIn("tx_recorded_marker", report["failed_gates"])
        self.assertIn("tx_far_end_recording_call_binding", report["failed_gates"])

    def test_reference_cannot_be_substituted_as_far_end_recording(self):
        check = marker_check(self.refs["tx"], self.refs["tx"], (733, 1379), 1)
        self.assertFalse(check["passed"])
        self.assertEqual(check["reason"], "REFERENCE_IDENTICAL_BYTES_NOT_INDEPENDENT_RECORDING")

    def test_wrong_run_same_frequency_marker_fails_pattern_matching(self):
        alternate = self.directory / "other-marker"
        generate_bundle(alternate, duration=4, run_id="entirely-different-run-773")
        recorded = alternate / "different-received.wav"
        attenuated_recording(alternate / "tx-reference.wav", recorded)
        result = marker_check(self.refs["tx"], recorded, (733, 1379), 1)
        self.assertFalse(result["passed"], result)

    def test_stale_call_binding_and_modified_proof_files_fail(self):
        state, events, evidence = self.fixtures()
        state["tx"]["call_id"] = "another-call"
        evidence["getprop"]["sha256"] = "0" * 64
        report = self.check(state, events, evidence)
        for key in ("tx_volte_evidence", "tx_isolation_evidence", "tx_far_end_recording_call_binding", "tx_framework_route_and_mute", "pixel5_stock_target_properties"):
            self.assertIn(key, report["failed_gates"])

    def test_rx_microphone_source_and_tx_recovery_terminal_fail(self):
        state, events, evidence = self.fixtures()
        for event in events:
            if event["event"] == "AUDIO_RX_RECORDING_STARTED":
                event["data"]["recording_configuration"]["client_source"] = "MIC"
            if event["event"] == "AUDIO_TX_STOPPED":
                event["data"].update(reason="PROCESS_RESTART", frames_written_final_unknown=True)
        report = self.check(state, events, evidence)
        self.assertIn("rx_downlink_source", report["failed_gates"])
        self.assertIn("tx_completed_and_mic_restored", report["failed_gates"])

    def test_null_route_after_verification_and_failed_terminal_are_rejected(self):
        state, events, evidence = self.fixtures()
        events.append({"command_id": "cmd-tx-play", "call_id": "tx-call", "event": "AUDIO_TX_ROUTE_CHANGED",
                       "data": {"actual_device": None, "route_was_verified": True, "route_matches_requested": False, "during_cleanup": False}})
        for event in events:
            if event["event"] == "AUDIO_RX_RECORDING_STOPPED":
                event["data"].update(status="FAILED", error="CAPTURE_ALL_ZERO_PCM")
        report = self.check(state, events, evidence)
        self.assertIn("no_nontelephony_route", report["failed_gates"])
        self.assertIn("rx_capture_completed", report["failed_gates"])


class RunnerSafetyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.controller = Controller(self.directory / "probe.db")
        issued = self.controller.create_enrollment("Pixel5", {"carrier": "Verizon", "download_allowed_hosts": ["media.example"]})
        self.credentials = self.controller.enroll({"device_name": "Pixel5", "enrollment_code": issued["enrollment_code"], "app_version": "0.2.1"})
        self.state = {"run_id": "safe-run", "device_id": self.credentials["device_id"], "number": "+12165551212",
                      "commands": {}, "tx": {}, "rx": {}}
        save_json(self.directory / "run.json", self.state)
        self.runner = Runner(self.controller, self.directory, timeout=1)

    def poll(self, **fields):
        return self.controller.poll(self.credentials["access_token"], {"device_id": self.credentials["device_id"], **fields})

    def test_pending_call_is_expired_atomically_on_abort_and_cannot_arrive_later(self):
        self.runner.queue("tx-call", "CALL", {"number": self.state["number"]})
        self.runner.hangup("tx")
        self.assertEqual(self.poll(), {"action": "NONE"})
        self.assertEqual(self.runner.record("tx-call")["status"], "EXPIRED")
        self.assertEqual(self.runner.state["tx"]["cleanup"], "UNDELIVERED_CALL_EXPIRED")
        # Re-issuing the deterministic stage ID cannot revive the expired call.
        self.runner.queue("tx-call", "CALL", {"number": self.state["number"]})
        self.assertEqual(self.poll(), {"action": "NONE"})

    def test_delivered_unknown_call_does_not_hang_up_unrelated_probe_call(self):
        self.runner.queue("tx-call", "CALL", {"number": self.state["number"]})
        self.poll(call={"command_id": "other-command", "call_id": "other-call", "probe_call_owned": True})
        self.runner.state["tx"]["call_id"] = "other-call"  # Stale local state must never authorize hangup.
        with self.assertRaisesRegex(ValueError, "OUTCOME_UNKNOWN.*safe-run-tx-call"):
            self.runner.hangup("tx")
        self.assertEqual([row["action"] for row in self.controller.inspect("results")], ["CALL"])
        self.assertEqual(self.runner.state["tx"]["cleanup"], "OUTCOME_UNKNOWN")
        self.assertEqual(self.poll(), {"action": "NONE"})
        self.assertEqual(self.runner.record("tx-call")["status"], "EXPIRED")
        with self.assertRaisesRegex(ValueError, "OUTCOME_UNKNOWN"):
            self.runner.establish("tx")

    def test_bounded_wait_times_out_without_sleeping_forever(self):
        current = [0.0]
        runner = Runner(self.controller, self.directory, timeout=1, clock=lambda: current[0], sleep=lambda delay: current.__setitem__(0, current[0] + delay))
        with self.assertRaises(TimeoutError):
            runner.wait("fake missing event", lambda: None)
        self.assertEqual(current[0], 1.0)

    def test_prepare_creates_template_and_queues_no_commands(self):
        directory = self.directory / "new-run"
        generate_bundle(self.directory / "markers", duration=2, run_id="fresh-test")
        result = prepare(directory, self.credentials["device_id"], "+12165551212", self.directory / "markers" / "manifest.json",
                         "https://media.example/tx-reference.wav", self.controller)
        self.assertEqual(result["status"], "PREPARED_NO_CALLS_QUEUED")
        self.assertEqual(self.controller.inspect("results"), [])
        self.assertTrue((directory / "physical-evidence-template.json").is_file())
        proof = json.loads((directory / "physical-evidence-template.json").read_text())
        self.assertIsNone(proof["tx_bearer"]["volte_confirmed"])


if __name__ == "__main__":
    unittest.main()

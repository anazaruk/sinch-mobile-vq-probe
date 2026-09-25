#!/usr/bin/env python3
"""Host-only Pixel 5 Phase B stages and conservative evidence checks.

No stage is run implicitly. No unsecured admin listener is created. The far-end
recorder/player is external and must be operated for the indicated call. Even a
complete automated check returns physical success as UNKNOWN pending review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from orchestrator.server import ApiError, Controller, NUMBER_RE, utc
from tools.audio_verify import analyze_file

PREFERRED_BUILD = "UP1A.231105.001.B2"
EXPECTED_TONES = {"tx": (733.0, 1379.0), "rx": (1091.0, 1877.0)}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def save_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(value, output, indent=2, allow_nan=False)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def template(state):
    def bearer():
        return {"call_id": None, "carrier": "Verizon", "native_cellular": None,
                "volte_confirmed": None, "wifi_calling_disabled": None, "evidence": []}
    return {"schema": "sinch-pixel5-physical-evidence-v1", "run_id": state["run_id"],
            "device_id": state["device_id"],
            "getprop": {"path": None, "sha256": None}, "runtime_audio_policy": [],
            "tx_bearer": bearer(), "rx_bearer": bearer(),
            "tx_recording": {"call_id": None, "path": None, "sha256": None,
                             "capture_origin": "SINCH_FAR_END", "evidence": []},
            "tx_isolation": {"call_id": None, "microphone_muted_verified": None, "speaker_source_disabled": None,
                             "bluetooth_audio_disconnected": None, "usb_audio_disconnected": None,
                             "external_audio_disconnected": None, "incall_music_uplink_observed": None,
                             "evidence": []},
            "rx_isolation": {"call_id": None, "voice_downlink_source_confirmed": None, "physical_microphone_excluded": None,
                             "evidence": []},
            "review_note": "Populate from this physical run. Evidence entries require path and SHA256. Statements alone are insufficient."}


def prepare(directory, device_id, number, marker_manifest, tx_url, controller):
    directory = Path(directory)
    if (directory / "run.json").exists():
        raise ValueError("run.json already exists; use a new run directory for a new physical call")
    if not NUMBER_RE.fullmatch(number):
        raise ValueError("number must be an E.164 Sinch test DID")
    if not any(device["device_id"] == device_id and not device["revoked"] for device in controller.list_devices()):
        raise ValueError("device is not enrolled or is revoked")
    manifest_path = Path(marker_manifest).resolve()
    manifest = load_json(manifest_path)
    if manifest.get("schema") != "sinch-audio-markers-v1":
        raise ValueError("use generate_test_audio.py to produce the marker manifest")
    refs = {}
    for direction, tones in EXPECTED_TONES.items():
        info = manifest["files"][direction]
        source = (manifest_path.parent / info["path"]).resolve()
        if sha256(source) != info["sha256"] or tuple(info["frequencies_hz"]) != tones:
            raise ValueError("marker reference hash or direction-specific tones do not match")
        analysis = analyze_file(source, tones)
        if analysis["channels"] != 1 or analysis["sample_rate"] not in (8000, 16000, 48000):
            raise ValueError("marker must be PCM16 mono at 8/16/48 kHz")
        refs[direction] = {"source": source, "sha256": info["sha256"], "frequencies_hz": list(tones),
                           "duration_seconds": analysis["duration_seconds"], "sample_rate": analysis["sample_rate"]}
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "references").mkdir(exist_ok=True)
    run_id = "pixel5-" + uuid.uuid4().hex[:16]
    for direction, info in refs.items():
        target = directory / "references" / (direction + "-reference.wav")
        shutil.copyfile(info.pop("source"), target)
        info["path"] = str(target.resolve())
    state = {"schema": "sinch-pixel5-acceptance-run-v1", "run_id": run_id, "marker_run_id": manifest.get("run_id"), "created_at": utc(),
             "device_id": device_id, "number": number, "references": refs, "tx_url": tx_url,
             "tx_file_id": run_id + "-tx", "rx_file_id": run_id + "-rx", "commands": {},
             "tx": {}, "rx": {}, "physical_tx_success": None, "physical_rx_success": None}
    save_json(directory / "run.json", state)
    save_json(directory / "physical-evidence-template.json", template(state))
    return {"status": "PREPARED_NO_CALLS_QUEUED", "run_id": run_id,
            "run_file": str((directory / "run.json").resolve()),
            "next": "Start far-end recording, then run tx --far-end-ready. RX uses separate rx-start and rx-stop stages."}


class Runner:
    def __init__(self, controller, directory, timeout=180, clock=time.monotonic, sleep=time.sleep):
        if not 1 <= timeout <= 1800:
            raise ValueError("timeout must be 1..1800 seconds per wait")
        self.controller, self.directory, self.timeout = controller, Path(directory), timeout
        self.state = load_json(self.directory / "run.json")
        self.clock, self.sleep = clock, sleep

    def persist(self):
        save_json(self.directory / "run.json", self.state)

    def record(self, key):
        command_id = self.state["commands"].get(key)
        if not command_id:
            return None
        with self.controller.connection() as db:
            row = db.execute("SELECT * FROM commands WHERE command_id=? AND device_id=?",
                             (command_id, self.state["device_id"])).fetchone()
            return self.controller._command_dict(row) if row else None

    def queue(self, key, action, parameters):
        identifier = self.state["commands"].get(key) or self.state["run_id"] + "-" + key
        self.state["commands"][key] = identifier
        self.persist()  # Persist deterministic ID before any command can reach the phone.
        return self.controller.queue(self.state["device_id"], action, parameters,
                                     ttl=min(7 * 86400, max(300, self.timeout * 3)), command_id=identifier)

    def events(self, key):
        command_id = self.state["commands"].get(key)
        with self.controller.connection() as db:
            return [json.loads(row["payload"]) for row in db.execute(
                "SELECT payload FROM events WHERE command_id=? AND device_id=? ORDER BY received,event_id",
                (command_id, self.state["device_id"]))]

    def wait(self, description, probe):
        deadline = self.clock() + self.timeout
        while True:
            result = probe()
            if result is not None:
                return result
            if self.clock() >= deadline:
                raise TimeoutError(description + " timed out; inspect run.json and the recorded commands before resuming")
            self.sleep(min(1, max(0, deadline - self.clock())))

    def result(self, key):
        def probe():
            row = self.record(key)
            if row and row["status"] in ("FAILED", "EXPIRED"):
                raise ValueError(key + " failed: " + json.dumps(row.get("result") or {"status": row["status"]}))
            if row and row["status"] == "SUCCESS":
                return row["result"]
            return None
        return self.wait(key, probe)

    def event(self, key, accepted):
        def probe():
            events = self.events(key)
            for event in reversed(events):
                if event["event"].endswith("FAILED"):
                    raise ValueError(key + " async failure: " + json.dumps(event))
                if event["event"] in accepted:
                    return event
            row = self.record(key)
            if row and row["status"] in ("FAILED", "EXPIRED"):
                raise ValueError(key + " command failed before expected event")
            if key.endswith("call") and any(e["event"] in ("DISCONNECTED", "CALL_RESULT") for e in events):
                raise ValueError("call disconnected before required event")
            return None
        return self.wait(key + " " + "/".join(accepted), probe)

    def establish(self, direction):
        if self.state[direction].get("cleanup") == "OUTCOME_UNKNOWN":
            raise ValueError("OUTCOME_UNKNOWN for " + self.state["commands"].get(direction + "-call", "unknown") + "; inspect device and original call evidence before creating another run")
        key = direction + "-call"
        self.queue(key, "CALL", {"number": self.state["number"]})
        result = self.result(key)
        event = self.event(key, {"ACTIVE"})
        if any(e["event"] in ("DISCONNECTED", "CALL_RESULT") for e in self.events(key)):
            raise ValueError("this stage's previous call already ended; create a new run instead of redialing")
        self.state[direction].update(call_id=event["call_id"], call_command_id=self.state["commands"][key])
        self.persist()
        return result

    def hangup(self, direction):
        command_id = self.state["commands"].get(direction + "-call")
        if not command_id:
            return
        call_id = None
        with self.controller.connection() as db:
            command = db.execute("SELECT * FROM commands WHERE command_id=? AND device_id=? AND action='CALL'",
                                 (command_id, self.state["device_id"])).fetchone()
            if not command:
                return
            if command["delivered"] is None:
                db.execute("UPDATE commands SET state='EXPIRED' WHERE command_id=? AND delivered IS NULL AND state='QUEUED'", (command_id,))
                self.state[direction]["cleanup"] = "UNDELIVERED_CALL_EXPIRED"
                self.persist()
                return
            binding = db.execute("SELECT call_id FROM call_bindings WHERE command_id=? AND device_id=?",
                                 (command_id, self.state["device_id"])).fetchone()
            if binding:
                call_id = binding["call_id"]
            if not call_id and command["result"]:
                call_id = json.loads(command["result"]).get("data", {}).get("call_id")
            if not call_id:
                for row in db.execute("SELECT payload FROM events WHERE command_id=? AND device_id=? ORDER BY received",
                                      (command_id, self.state["device_id"])):
                    call_id = json.loads(row["payload"]).get("call_id")
                    if call_id:
                        break
            if not call_id:
                device = db.execute("SELECT last_status FROM devices WHERE device_id=?", (self.state["device_id"],)).fetchone()
                current = json.loads(device["last_status"] or "{}").get("call", {})
                if current.get("command_id") == command_id and current.get("probe_call_owned") is True:
                    call_id = current.get("call_id")
            if not call_id:
                # Stop server redelivery after abort without pretending that an
                # in-flight delivery or already executing native call was canceled.
                # Preserve original expires/delivered timestamps for late reports.
                db.execute("UPDATE commands SET state='EXPIRED' WHERE command_id=? AND state='DELIVERED'", (command_id,))
        if call_id:
            if any(e.get("event") == "CALL_RESULT" and e.get("call_id") == call_id for e in self.events(direction + "-call")):
                self.state[direction].update(hangup_complete=True, cleanup="ALREADY_DISCONNECTED")
                self.persist()
                return
            self.queue(direction + "-hangup", "HANGUP", {"call_id": call_id})
            self.result(direction + "-hangup")
            self.event(direction + "-call", {"CALL_RESULT"})
            self.state[direction]["hangup_complete"] = True
            self.persist()
        else:
            self.state[direction].update(cleanup="OUTCOME_UNKNOWN", unresolved_command_id=command_id)
            self.persist()
            raise ValueError("OUTCOME_UNKNOWN: delivered CALL " + command_id + " has no correlated call_id; inspect this device/original command, do not redial or hang up an unrelated call")

    def tx(self, ready):
        if not ready:
            raise ValueError("start the external Sinch far-end recorder before supplying --far-end-ready")
        if self.state["tx"].get("complete"):
            if not self.state["tx"].get("hangup_complete"):
                self.hangup("tx")
            return {"status": "TX_ALREADY_FINISHED_NO_REDIAL", "call_id": self.state["tx"]["call_id"]}
        ref = self.state["references"]["tx"]
        if sha256(ref["path"]) != ref["sha256"]:
            raise ValueError("TX reference changed")
        self.queue("tx-download", "DOWNLOAD_FILE", {"file_id": self.state["tx_file_id"], "url": self.state["tx_url"], "sha256": ref["sha256"]})
        self.result("tx-download")
        try:
            self.establish("tx")
            self.queue("tx-play", "PLAY_AUDIO", {"file_id": self.state["tx_file_id"], "mode": "DIGITAL_TX_VALIDATION",
                       "mute_microphone": True, "loop": False})
            self.result("tx-play")
            self.event("tx-play", {"AUDIO_TX_ACTIVE"})
            terminal = self.event("tx-play", {"AUDIO_TX_STOPPED"})
            self.state["tx"].update(complete=True, terminal_event=terminal)
            self.persist()
        finally:
            self.hangup("tx")
        return {"status": "TX_FRAMEWORK_STAGE_FINISHED_PHYSICAL_SUCCESS_UNKNOWN", "call_id": self.state["tx"]["call_id"],
                "next": "Preserve the actual far-end recording and runtime/VoLTE/isolation evidence for evaluate."}

    def rx_start(self):
        if self.state["rx"].get("complete"):
            if not self.state["rx"].get("hangup_complete"):
                self.hangup("rx")
            raise ValueError("RX already ended; create a new run instead of redialing")
        try:
            self.establish("rx")
            self.queue("rx-diagnostics", "GET_AUDIO_DIAGNOSTICS", {"probe_downlink": True})
            self.result("rx-diagnostics")
            self.queue("rx-start", "START_RECORDING", {"file_id": self.state["rx_file_id"], "direction": "DOWNLINK",
                       "sample_rate": self.state["references"]["rx"]["sample_rate"]})
            self.result("rx-start")
            self.event("rx-start", {"AUDIO_RX_RECORDING_STARTED"})
        except BaseException:
            self.hangup("rx")
            raise
        return {"status": "RX_RECORDING_ACTIVE_EXTERNAL_PLAYBACK_HANDOFF", "call_id": self.state["rx"]["call_id"],
                "far_end_must_play": self.state["references"]["rx"],
                "next": "Play this exact marker on the Sinch far end now; after playback run rx-stop. The call remains active."}

    def rx_stop(self):
        if not self.state["rx"].get("call_id"):
            raise ValueError("rx-start has not established an owned call")
        if self.state["rx"].get("complete"):
            if not self.state["rx"].get("hangup_complete"):
                self.hangup("rx")
            return {"status": "RX_ALREADY_FINISHED_NO_REDIAL", "artifact": self.state["rx"].get("artifact")}
        try:
            self.queue("rx-stop", "STOP_RECORDING", {})
            self.result("rx-stop")
            terminal = self.event("rx-start", {"AUDIO_RX_RECORDING_STOPPED"})
            self.queue("rx-upload", "UPLOAD_FILE", {"file_id": self.state["rx_file_id"]})
            self.result("rx-upload")
            artifacts = self.controller.inspect("artifacts", self.state["device_id"], 10000)
            artifact = next((a for a in artifacts if a["upload_command_id"] == self.state["commands"]["rx-upload"]), None)
            if not artifact:
                raise ValueError("uploaded RX artifact is missing from the server")
            self.state["rx"].update(complete=True, terminal_event=terminal, artifact=artifact)
            self.persist()
        finally:
            self.hangup("rx")
        return {"status": "RX_FRAMEWORK_STAGE_FINISHED_PHYSICAL_SUCCESS_UNKNOWN", "artifact": artifact,
                "next": "Run evaluate with the TX far-end WAV and completed physical-evidence JSON."}


def marker_check(reference, received, tones, max_lag_seconds=10):
    """Compare this run's time-varying two-tone code, never nonzero PCM alone."""
    if not math.isfinite(max_lag_seconds) or not 0 <= max_lag_seconds <= 60:
        raise ValueError("max_lag_seconds must be 0..60")
    a, b = analyze_file(reference, tones), analyze_file(received, tones)
    if sha256(reference) == sha256(received):
        return {"passed": False, "reason": "REFERENCE_IDENTICAL_BYTES_NOT_INDEPENDENT_RECORDING", "reference": a["sha256"], "received": b["sha256"]}
    def labels(analysis):
        output = []
        for frame in analysis["measurements"][0]["envelope"]:
            ratios = [frame[f"tone_{tone:g}_energy_ratio"] for tone in tones]
            output.append(-1 if frame["rms"] < 10 ** (-50 / 20) else ratios.index(max(ratios)) if max(ratios) >= .20 else -2)
        return output
    x, y = labels(a), labels(b)
    voiced = sum(v >= 0 for v in x)
    if voiced < 10 or not all(x.count(i) >= 5 for i in range(len(tones))):
        return {"passed": False, "reason": "REFERENCE_MARKER_NOT_DISTINCT", "reference": a["sha256"], "received": b["sha256"]}
    best = None
    maximum = round(max_lag_seconds / .020)
    for lag in range(-maximum, maximum + 1):
        pairs = [(i, i + lag) for i in range(len(x)) if 0 <= i + lag < len(y)]
        if len(pairs) < len(x) * .85:
            continue
        matched = sum(x[i] >= 0 and x[i] == y[j] for i, j in pairs)
        observed = sum(x[i] >= 0 for i, _ in pairs)
        quiet = [(i, j) for i, j in pairs if x[i] == -1]
        quiet_ratio = sum(y[j] == -1 for _, j in quiet) / len(quiet) if quiet else 0
        each = [sum(x[i] == tone and y[j] == tone for i, j in pairs) for tone in range(len(tones))]
        score = matched / voiced
        candidate = {"match_fraction": score, "coverage_fraction": observed / voiced,
                     "quiet_match_fraction": quiet_ratio, "aligned_tone_seconds": [count * .020 for count in each],
                     "lag_seconds": lag * .020}
        if best is None or score > best["match_fraction"]:
            best = candidate
    duration_ok = b["duration_seconds"] >= a["duration_seconds"] - .10
    passed = bool(best and best["match_fraction"] >= .85 and best["coverage_fraction"] >= .90
                  and best["quiet_match_fraction"] >= .60 and all(t >= .10 for t in best["aligned_tone_seconds"]) and duration_ok)
    return {"passed": passed, "reason": "CODED_MARKER_MATCH" if passed else "CODED_MARKER_MISMATCH_OR_DURATION",
            "reference_sha256": a["sha256"], "received_sha256": b["sha256"], "reference_duration": a["duration_seconds"],
            "received_duration": b["duration_seconds"], "alignment": best,
            "limits": "Conservative coded-tone evidence only; not speech matching, MOS, codec, or physical route proof."}


def checked_evidence(entries, base):
    if not isinstance(entries, list) or not entries:
        return False, []
    files = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) or not entry.get("sha256"):
            return False, files
        path = (base / entry["path"]).resolve()
        if not path.is_file() or path.stat().st_size == 0 or sha256(path) != entry["sha256"]:
            return False, files
        files.append(path)
    return True, files


def evaluate(state, events, tx_recording, rx_recording, evidence, evidence_dir, max_lag_seconds=10):
    """Pure evidence evaluation. Never promote framework reports into physical pass."""
    gates = {}
    evidence_dir = Path(evidence_dir)
    gates["run_identity"] = (evidence.get("schema") == "sinch-pixel5-physical-evidence-v1"
        and evidence.get("run_id") == state["run_id"] and evidence.get("device_id") == state["device_id"])
    ok, props = checked_evidence([evidence.get("getprop")], evidence_dir)
    platform = props[0].read_text(errors="replace") if ok else ""
    expected = {"ro.product.model": "Pixel 5", "ro.product.device": "redfin",
                "ro.build.version.release": "14", "ro.build.id": PREFERRED_BUILD}
    gates["pixel5_stock_target_properties"] = ok and all(f"[{key}]: [{value}]" in platform for key, value in expected.items())
    ok, policies = checked_evidence(evidence.get("runtime_audio_policy"), evidence_dir)
    policy_text = "\n".join(path.read_text(errors="replace") for path in policies) if ok else ""
    gates["runtime_policy_paths_present"] = ok and all(term in policy_text for term in ("incall_music_uplink", "Telephony Tx", "Telephony Rx"))
    for direction in ("tx", "rx"):
        bearer = evidence.get(direction + "_bearer", {})
        files_ok, _ = checked_evidence(bearer.get("evidence"), evidence_dir)
        gates[direction + "_volte_evidence"] = (files_ok and bearer.get("call_id") == state[direction].get("call_id")
            and bool(bearer.get("call_id")) and bearer.get("carrier") == "Verizon"
            and all(bearer.get(key) is True for key in ("native_cellular", "volte_confirmed", "wifi_calling_disabled")))
        isolation = evidence.get(direction + "_isolation", {})
        fields = ("microphone_muted_verified", "speaker_source_disabled", "bluetooth_audio_disconnected",
                  "usb_audio_disconnected", "external_audio_disconnected", "incall_music_uplink_observed") if direction == "tx" else (
                  "voice_downlink_source_confirmed", "physical_microphone_excluded")
        files_ok, _ = checked_evidence(isolation.get("evidence"), evidence_dir)
        gates[direction + "_isolation_evidence"] = (files_ok and isolation.get("call_id") == state[direction].get("call_id")
            and bool(isolation.get("call_id")) and all(isolation.get(key) is True for key in fields))
    tx_proof = evidence.get("tx_recording", {})
    recording_ok, recording_paths = checked_evidence([tx_proof], evidence_dir)
    capture_logs_ok, _ = checked_evidence(tx_proof.get("evidence"), evidence_dir)
    gates["tx_far_end_recording_call_binding"] = bool(recording_ok and capture_logs_ok
        and tx_proof.get("capture_origin") == "SINCH_FAR_END"
        and tx_proof.get("call_id") == state["tx"].get("call_id") and tx_proof.get("call_id")
        and tx_recording and recording_paths[0] == Path(tx_recording).resolve())
    def selected(key, name):
        return [event["data"] for event in events if event.get("command_id") == state["commands"].get(key)
                and event.get("event") == name and event.get("call_id") == state[key.split("-")[0]].get("call_id")
                and event.get("data", {}).get("call_command_id") == state["commands"].get(key.split("-")[0] + "-call")]
    tx_active, tx_end = selected("tx-play", "AUDIO_TX_ACTIVE"), selected("tx-play", "AUDIO_TX_STOPPED")
    gates["tx_framework_route_and_mute"] = bool(tx_active and all(d.get("mode") == "DIGITAL_TX_VALIDATION"
        and d.get("requested_device") == "TYPE_TELEPHONY" and d.get("actual_device") == "TYPE_TELEPHONY"
        and d.get("route_verified") is True and d.get("microphone_during") is True
        and d.get("speaker_source_audio_enabled") is False and d.get("external_audio_fallback_allowed") is False
        and d.get("source_sha256") == state["references"]["tx"]["sha256"] for d in tx_active))
    tx_final = tx_end[-1] if tx_end else {}
    gates["tx_completed_and_mic_restored"] = bool(tx_end and tx_final.get("state") == "STOPPED"
        and tx_final.get("error") is None and tx_final.get("reason") == "END_OF_FILE"
        and tx_final.get("frames_written", 0) > 0
        and tx_final.get("frames_written") == tx_final.get("source_frames")
        and tx_final.get("frames_written_final_unknown") is not True and tx_final.get("microphone_restored") is True
        and tx_final.get("route_callback_flush_complete") is True)
    rx_active, rx_end = selected("rx-start", "AUDIO_RX_RECORDING_STARTED"), selected("rx-start", "AUDIO_RX_RECORDING_STOPPED")
    gates["rx_downlink_source"] = bool(rx_active and all(d.get("requested_source") == "VOICE_DOWNLINK"
        and d.get("recording_configuration", {}).get("client_source") == "VOICE_DOWNLINK"
        and d.get("recording_configuration", {}).get("capture_path_source") == "VOICE_DOWNLINK"
        and d.get("recording_configuration", {}).get("source_verified") is True
        and d.get("recording_configuration", {}).get("client_silenced") is False
        and d.get("file_id") == state["rx_file_id"] for d in rx_active))
    rx_final = rx_end[-1] if rx_end else {}
    gates["rx_capture_completed"] = bool(rx_end and rx_final.get("status") == "SUCCESS" and rx_final.get("error") is None
        and rx_final.get("stop_reason") == "COMMAND" and rx_final.get("frames_captured", 0) > 0)
    ours = {state["commands"].get("tx-play"), state["commands"].get("rx-start")}
    gates["no_audio_failure_events"] = not any(e.get("command_id") in ours and e.get("event", "").endswith("FAILED") for e in events)
    # Initial null route callbacks may precede silence-only priming; a wrong active
    # route is never tolerated, even when a later event says TYPE_TELEPHONY again.
    gates["no_nontelephony_route"] = not any(e.get("command_id") == state["commands"].get("tx-play")
        and e.get("event") == "AUDIO_TX_ROUTE_CHANGED" and not e.get("data", {}).get("during_cleanup")
        and (str(e.get("data", {}).get("actual_device", "")).startswith("OTHER_")
             or (e.get("data", {}).get("route_was_verified") is True and e.get("data", {}).get("route_matches_requested") is not True)) for e in events)
    for direction in ("tx", "rx"):
        names = {e.get("event") for e in events if e.get("command_id") == state["commands"].get(direction + "-call")
                 and e.get("call_id") == state[direction].get("call_id")}
        gates[direction + "_native_call_sequence"] = {"DIALING", "ACTIVE", "CALL_RESULT"}.issubset(names)
    comparisons = {}
    for direction, recorded in (("tx", tx_recording), ("rx", rx_recording)):
        ref = state["references"][direction]
        try:
            gates[direction + "_reference_unchanged"] = sha256(ref["path"]) == ref["sha256"]
            comparisons[direction] = marker_check(ref["path"], recorded, ref["frequencies_hz"], max_lag_seconds)
            gates[direction + "_recorded_marker"] = comparisons[direction]["passed"]
        except (OSError, ValueError, TypeError) as exc:
            gates[direction + "_recorded_marker"] = False
            comparisons[direction] = {"passed": False, "error": str(exc)}
    artifact = state["rx"].get("artifact", {})
    try:
        gates["rx_uploaded_artifact_matches"] = bool(artifact.get("sha256") and sha256(rx_recording) == artifact["sha256"]
            and artifact.get("device_id") == state["device_id"] and artifact.get("file_id") == state["rx_file_id"]
            and artifact.get("upload_command_id") == state["commands"].get("rx-upload"))
    except (OSError, TypeError):
        gates["rx_uploaded_artifact_matches"] = False
    passed = bool(gates) and all(value is True for value in gates.values())
    return {"schema": "sinch-pixel5-acceptance-evaluation-v1", "run_id": state["run_id"], "evaluated_at": utc(),
            "status": "AUTOMATED_CHECKS_PASSED_REVIEW_REQUIRED" if passed else "INCOMPLETE_OR_FAILED",
            "automated_checks_passed": passed, "physical_tx_success": None, "physical_rx_success": None,
            "gates": gates, "failed_gates": [key for key, value in gates.items() if value is not True],
            "marker_comparisons": comparisons,
            "review_required": "An engineer must inspect original runtime, bearer, isolation and far-end evidence. This tool cannot establish evidence authenticity or infer VoLTE from LTE data RAT."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default="probe.db")
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--timeout", type=int, default=180, help="Bound each command/event wait, 1..1800 seconds")
    sub = parser.add_subparsers(dest="stage", required=True)
    prep = sub.add_parser("prepare", help="Save local run and empty evidence template; no calls queued")
    prep.add_argument("--device-id", required=True)
    prep.add_argument("--number", required=True)
    prep.add_argument("--markers", required=True, help="generate_test_audio.py manifest.json")
    prep.add_argument("--tx-url", required=True, help="HTTPS URL serving byte-identical TX marker; allowed by enrolled device config")
    tx = sub.add_parser("tx", help="Run bounded TX stage; far-end recorder must already be ready")
    tx.add_argument("--far-end-ready", action="store_true")
    sub.add_parser("rx-start", help="Start DOWNLINK capture and return external playback handoff; leaves call active")
    sub.add_parser("rx-stop", help="Stop capture, upload artifact and hang up")
    evaluation = sub.add_parser("evaluate", help="Check actual WAVs plus physical evidence; never automatically declare physical success")
    evaluation.add_argument("--tx-recording", required=True)
    evaluation.add_argument("--rx-recording", help="Default: this run's uploaded server artifact")
    evaluation.add_argument("--evidence", required=True)
    evaluation.add_argument("--max-lag-seconds", type=float, default=10)
    args = parser.parse_args(argv)
    try:
        controller = Controller(args.db)
        if args.stage == "prepare":
            output = prepare(args.run_dir, args.device_id, args.number, args.markers, args.tx_url, controller)
        else:
            runner = Runner(controller, args.run_dir, args.timeout)
            if args.stage == "tx":
                output = runner.tx(args.far_end_ready)
            elif args.stage == "rx-start":
                output = runner.rx_start()
            elif args.stage == "rx-stop":
                output = runner.rx_stop()
            else:
                evidence_path = Path(args.evidence).resolve()
                rx = args.rx_recording or runner.state["rx"].get("artifact", {}).get("path")
                events = []
                for key in ("tx-call", "rx-call", "tx-play", "rx-start"):
                    events.extend(runner.events(key))
                output = evaluate(runner.state, events, args.tx_recording, rx, load_json(evidence_path), evidence_path.parent, args.max_lag_seconds)
                save_json(Path(args.run_dir) / "acceptance-report.json", output)
        print(json.dumps(output, indent=2, allow_nan=False))
        return 2 if args.stage == "evaluate" and not output["automated_checks_passed"] else 0
    except KeyboardInterrupt:
        print(json.dumps({"error": "INTERRUPTED; inspect run.json cleanup and original command IDs", "physical_tx_success": None, "physical_rx_success": None}), file=sys.stderr)
        return 130
    except (ApiError, ValueError, KeyError, TypeError, OSError, TimeoutError) as exc:
        print(json.dumps({"error": str(exc), "physical_tx_success": None, "physical_rx_success": None}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

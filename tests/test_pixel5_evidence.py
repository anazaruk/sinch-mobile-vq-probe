"""Host evidence tests use a fake ADB transport; they do not validate a phone/audio path."""
from datetime import datetime, timezone, timedelta
import hashlib
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("pixel5_evidence", ROOT / "tools/pixel5_evidence.py")
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)
NOW = datetime(2026, 9, 25, 12, 0, tzinfo=timezone.utc)
PACKAGE_DUMP = """Packages:
  Package [com.sinch.vqprobe] (fixture):
    pkgFlags=[ SYSTEM HAS_CODE ]
    privateFlags=[ PRIVATE_FLAG_PRIVILEGED ]
    android.permission.MODIFY_PHONE_STATE: granted=true
    android.permission.CAPTURE_AUDIO_OUTPUT: granted=true
    android.permission.READ_PRECISE_PHONE_STATE: granted=true
"""


def envelope():
    return {"schema_version": 1, "generated_at": NOW.isoformat(), "app": {"package": tool.PACKAGE},
        "diagnostics": {"permissions": {"android.permission." + p: True for p in tool.PRIVILEGES},
            "default_dialer_role": True, "outputs": [{"type_name": "TYPE_TELEPHONY", "is_sink": True}],
            "call": {"state": "ACTIVE", "probe_call_owned": True, "call_id": "call-1", "command_id": "cmd-call-1"},
            "voice_downlink_initialization": {"available": True, "attempted": True, "scope": "AUDIORECORD_INITIALIZATION_ONLY",
                "source": "VOICE_DOWNLINK", "call_id": "call-1", "call_command_id": "cmd-call-1", "observed_at": NOW.isoformat()}},
        "call_result": None, "tx": {"running": False}, "rx": {"running": False}, "recording_artifacts": []}


class FakeAdb:
    def __init__(self):
        self.calls = []
        self.files = {"/vendor/etc/audio_policy_configuration.xml": b'<?xml version="1.0"?>\r\n<route sink="Telephony Tx" sources="incall_music_uplink"/>\r\n',
                      "/vendor/etc/mixer_paths_redfin.xml": b'<path name="voice_rx"/>\n',
                      "/vendor/etc/audio_platform_info.xml": b'<path name="voice_tx"/>\n'}
        self.bad_checksum = False
        self.bridge_error = False
        self.snapshot = envelope()

    def shell(self, *args, root=False, binary=False):
        self.calls.append((args, root, binary))
        if args[:2] == ("sh", "-c") and args[2] == tool.LIST_POLICY:
            return ("\n".join(sorted(self.files)) + "\n").encode()
        if args[:2] == ("head", "-c"):
            if args[-1] not in self.files:
                raise tool.DeviceError("Permission denied or missing")
            return self.files[args[-1]][:int(args[2])]
        if args[:1] == ("sha256sum",):
            digest = "0" * 64 if self.bad_checksum else hashlib.sha256(self.files[args[1]]).hexdigest()
            return (digest + "  " + args[1] + "\n").encode()
        if args[:2] == ("content", "call"):
            if self.bridge_error:
                raise tool.DeviceError("Permission Denial: requires android.permission.DUMP")
            return ("Result: Bundle[{json=" + json.dumps(self.snapshot) + "}]\n").encode()
        if args == ("getprop",):
            return b"[ro.product.model]: [Pixel 5]\n[ro.product.device]: [redfin]\n[ro.build.id]: [UP1A.231105.001.B2]\n[ro.build.fingerprint]: [google/redfin/test]\n[ro.build.version.release]: [14]\n"
        if args[:2] == ("dumpsys", "package"):
            return PACKAGE_DUMP.encode()
        if args[:3] == ("cmd", "role", "get-role-holders"):
            return (tool.PACKAGE + "\n").encode()
        if args == ("magisk", "-v"):
            return b"30.7:MAGISK:R\n"
        return b"test fixture evidence\n"


class Pixel5EvidenceTests(unittest.TestCase):
    def test_bridge_parses_bundle_without_greedy_json_matching(self):
        source = envelope()
        source["tx"] = {"note": "braces } ] { and json= inside a JSON string"}
        raw = ("Result: Bundle[{json=" + json.dumps(source) + "}]\n").encode()
        self.assertEqual(tool.decode_bridge(raw), source)

    def test_bridge_rejects_wrong_package_and_invalid_schema(self):
        for field, value in (("app", {"package": "other"}), ("schema_version", True), ("diagnostics", [])):
            source = envelope(); source[field] = value
            with self.assertRaises(ValueError):
                tool.decode_bridge(json.dumps(source).encode())
        with self.assertRaises(ValueError):
            tool.decode_bridge(b"Permission Denial: requires DUMP")

    def test_bridge_preserves_raw_and_splits_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); fake = FakeAdb()
            result, error = tool.collect_bridge(fake, directory, probe=True)
            self.assertIsNone(error)
            self.assertEqual(result, envelope())
            self.assertTrue((directory / "app-diagnostics-bridge-response.txt").read_bytes().startswith(b"Result: Bundle"))
            self.assertEqual(fake.calls[-1][0][-1], "probe_downlink")
            self.assertTrue((directory / "received-wav-metadata.json").is_file())

    def test_bridge_denial_is_explicit_without_insecure_fallback(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = FakeAdb(); fake.bridge_error = True
            result, error = tool.collect_bridge(fake, Path(tmp))
            self.assertIsNone(result)
            self.assertIn("DUMP", error)
            self.assertEqual(len(fake.calls), 1)
            self.assertFalse(json.loads((Path(tmp) / "app-diagnostics.unavailable.json").read_text())["fallback_to_exported_files"])

    def test_vendor_file_bytes_paths_and_sha_are_exact(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); fake = FakeAdb()
            report = tool.collect_vendor_files(fake, directory, root=True)
            self.assertEqual(len(report["files"]), 3)
            for item in report["files"]:
                self.assertTrue(item["available"])
                self.assertEqual((directory / item["local_path"]).read_bytes(), fake.files[item["device_path"]])
                self.assertEqual(item["sha256"], item["device_sha256"])
            self.assertTrue(all(binary for args, _, binary in fake.calls if args[0] == "head"))
            hits = json.loads((directory / "vendor-search-hits.json").read_text())["matches"]
            self.assertTrue(any("incall_music_uplink" in row["terms"] for row in hits))

    def test_hash_mismatch_never_publishes_a_vendor_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = FakeAdb(); fake.bad_checksum = True
            report = tool.collect_vendor_files(fake, Path(tmp))
            self.assertTrue(all(not row["available"] for row in report["files"]))
            self.assertFalse((Path(tmp) / "vendor-files").exists())

    def test_oversized_and_unexpected_paths_are_explicitly_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = FakeAdb()
            fake.files["/vendor/etc/audio_policy_configuration.xml"] = b"x" * (tool.MAX_VENDOR_BYTES + 1)
            fake.files["/vendor/etc/../../data/secret.xml"] = b"bad"
            report = tool.collect_vendor_files(fake, Path(tmp))
            errors = [row.get("error", "") for row in report["files"]]
            self.assertTrue(any("FILE_TOO_LARGE" in error for error in errors))
            self.assertIn("UNEXPECTED_VENDOR_PATH", errors)
            self.assertFalse(any(args[0] == "head" and ".." in args[-1] for args, _, _ in fake.calls))

    def test_verifier_pass_is_only_fresh_same_call_initialization(self):
        result = tool.verify_snapshot(envelope(), PACKAGE_DUMP, now=NOW)
        self.assertTrue(result["all_prerequisites_pass"])
        self.assertFalse(result["tx_digital_audio_proven"])
        self.assertFalse(result["rx_digital_audio_proven"])

    def test_unknown_stale_wrong_call_or_source_never_passes(self):
        edits = [lambda x: x["diagnostics"]["voice_downlink_initialization"].update(available=None),
                 lambda x: x["diagnostics"]["voice_downlink_initialization"].update(call_id="other-call"),
                 lambda x: x["diagnostics"]["voice_downlink_initialization"].update(source="MIC"),
                 lambda x: x["diagnostics"]["voice_downlink_initialization"].update(observed_at=(NOW-timedelta(seconds=61)).isoformat()),
                 lambda x: x.update(generated_at=(NOW-timedelta(seconds=61)).isoformat()),
                 lambda x: x["diagnostics"]["call"].update(probe_call_owned=False)]
        for edit in edits:
            source = envelope(); edit(source)
            result = tool.verify_snapshot(source, PACKAGE_DUMP, now=NOW)
            self.assertFalse(result["all_prerequisites_pass"])
            self.assertEqual(result["checks"][-1]["status"], "FAIL_NOT_TESTED")

    def test_initialization_failure_reports_fail_and_exact_error(self):
        source = envelope()
        source["diagnostics"]["voice_downlink_initialization"].update(available=False, error="AUDIORECORD_INIT_FAILED")
        result = tool.verify_snapshot(source, PACKAGE_DUMP, now=NOW)
        self.assertEqual(result["checks"][-1]["status"], "FAIL")
        self.assertIn("AUDIORECORD_INIT_FAILED", result["checks"][-1]["detail"])

    def test_device_build_scope_does_not_imply_magisk_or_variant(self):
        fake = FakeAdb()
        result = tool.properties_report(fake.shell("getprop"))
        self.assertTrue(result["matches_preferred_target"])
        self.assertIsNone(result["magisk_version_matches"])
        self.assertTrue(tool.properties_report(fake.shell("getprop"), "30.7:MAGISK:R")["magisk_version_matches"])
        self.assertFalse(result["carrier_or_voLTE_verified"])

    def test_safe_root_shell_quoting_and_binary_transport(self):
        with patch.object(tool.shutil, "which", return_value="/test/adb"), patch.object(tool.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, stdout=b"exact\r\n", stderr=b"")
            adb = tool.Adb("SERIAL")
            result = adb.shell("head", "-c", "10", "/vendor/etc/example's.xml", root=True, binary=True)
            argv = run.call_args.args[0]
            self.assertEqual(argv[:4], ["adb", "-s", "SERIAL", "exec-out"])
            outer = shlex.split(argv[4]); self.assertEqual(outer[:2], ["su", "-c"])
            self.assertEqual(shlex.split(outer[2])[-1], "/vendor/etc/example's.xml")
            self.assertEqual(result, b"exact\r\n")

    def test_full_collection_never_writes_device_state_or_claims_physical_success(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(tool, "utc_now", return_value=NOW):
            fake = FakeAdb(); directory = Path(tmp)
            report = tool.collect(fake, directory, mode="collect", root=True, probe=False)
            tool.manifest(directory)
            self.assertTrue(report["all_prerequisites_pass"])
            for args, _, _ in fake.calls:
                command = " ".join(args)
                for forbidden in ("pm grant", "appops set", "setenforce", "fastboot", "reboot", "magisk --install"):
                    self.assertNotIn(forbidden, command)
            self.assertEqual([args[-1] for args, _, _ in fake.calls if args[0] == "content"], ["snapshot"])
            log_commands = [args for args, _, _ in fake.calls if args[0] == "logcat"]
            self.assertEqual(len(log_commands), 1)
            for tag in ("audio_hw_primary", "audio_hw_voice", "audio_hw_extn", "audio_hw_platform", "voice_extn", "ACDB-LOADER"):
                self.assertIn(tag + ":V", log_commands[0])
            coverage = json.loads((directory / "vendor-hal-log-coverage.json").read_text())
            self.assertFalse(any(coverage["observed_tags"].values()))
            self.assertIn("UNAVAILABLE_OR_NOT_EMITTED", coverage["unobserved_tag_reason"])
            self.assertFalse(coverage["absence_proves_unsupported"])
            self.assertFalse(json.loads((directory / "collection-summary.json").read_text())["physical_tx_rx_verified"])
            self.assertTrue((directory / "evidence-manifest.json").is_file())


if __name__ == "__main__":
    unittest.main()

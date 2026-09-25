#!/usr/bin/env python3
"""Read-only Pixel 5 device evidence, with an opt-in DOWNLINK initialization probe.

The diagnostics provider accepts only Android shell/root callers and android.permission.DUMP.
No root, bootloader, firmware, routing, mixer or permission mutation is performed here.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import wave

PACKAGE = "com.sinch.vqprobe"
URI = "content://com.sinch.vqprobe.diagnostics"
PRIVILEGES = ("MODIFY_PHONE_STATE", "CAPTURE_AUDIO_OUTPUT", "READ_PRECISE_PHONE_STATE")
TERMS = ("incall_music", "incall_music_uplink", "Telephony Tx", "Telephony Rx", "voice_tx", "voice_rx")
MAX_VENDOR_BYTES = 2 * 1024 * 1024
MAX_VENDOR_FILES = 128
MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_WAV_BYTES = 64 * 1024 * 1024 + 65536
VENDOR_PATH = re.compile(r"^/vendor/etc/[A-Za-z0-9_.+-]+\.xml$")
LIST_POLICY = "find /vendor/etc -maxdepth 1 \\( -type f -o -type l \\) \\( -name 'audio_policy*.xml' -o -name 'mixer_paths*.xml' -o -name 'audio_platform_info*.xml' \\) -print | sort -u | head -n 129"


def utc_now():
    return datetime.now(timezone.utc)


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()


class DeviceError(RuntimeError):
    pass


class Adb:
    def __init__(self, serial=None):
        if shutil.which("adb") is None:
            raise DeviceError("adb missing; install Android SDK Platform Tools")
        self.prefix = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args):
        try:
            result = subprocess.run(self.prefix + list(args), capture_output=True, timeout=90)
        except subprocess.TimeoutExpired as error:
            raise DeviceError("ADB_TIMEOUT: check USB and the on-device Magisk prompt") from error
        if result.returncode:
            message = (result.stderr or result.stdout).decode(errors="replace").strip()[:1000]
            raise DeviceError(message or f"ADB_COMMAND_FAILED_{result.returncode}")
        return result.stdout

    def shell(self, *args, root=False, binary=False):
        command = shlex.join(list(args))
        if root:
            command = shlex.join(["su", "-c", command])
        # exec-out has no PTY/CRLF transformation; XML bytes are preserved exactly.
        return self.run("exec-out" if binary else "shell", command)

    def connect(self, root=False):
        if self.run("get-state").strip() != b"device":
            raise DeviceError("A unique authorized Android device is required; use --serial")
        if self.shell("am", "get-current-user").strip() != b"0":
            raise DeviceError("Dedicated owner user 0 is required for this validation workflow")
        if root and self.shell("id", "-u", root=True).strip() != b"0":
            raise DeviceError("--root requested but existing Magisk root is unavailable; rooting is not performed")


def write_file(directory, relative, data):
    path = directory / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"local_path": relative, "size_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def capture(adb, directory, relative, *args, root=False):
    try:
        data = adb.shell(*args, root=root)
        return {"available": True, **write_file(directory, relative, data)}
    except DeviceError as error:
        record = {"available": False, "error": str(error)}
        write_file(directory, relative + ".unavailable.json", json_bytes(record))
        return record


def properties_report(raw, magisk=None):
    props = dict(re.findall(r"^\[([^]]+)\]: \[([^]]*)\]\r?$", raw.decode(errors="replace"), re.M))
    actual = {"model": props.get("ro.product.model"), "device": props.get("ro.product.device"),
              "build_id": props.get("ro.build.id"), "fingerprint": props.get("ro.build.fingerprint"),
              "android_release": props.get("ro.build.version.release"), "android_api": props.get("ro.build.version.sdk"),
              "hardware_sku": props.get("ro.boot.hardware.sku") or props.get("ro.boot.product.hardware.sku"),
              "magisk": magisk}
    expected = {"model": "Pixel 5", "device": "redfin", "android_release": "14", "build_id": "UP1A.231105.001.B2"}
    return {"observed_at": utc_now().isoformat(), "actual": actual, "expected": expected,
            "matches_preferred_target": all(actual.get(k) == v for k, v in expected.items()),
            "target_match_scope": "MODEL_CODENAME_STOCK_OS_AND_BUILD_ONLY",
            "magisk_expected": "30.7", "magisk_version_matches": bool(re.match(r"^30\.7(?:[:\s-]|$)", magisk)) if magisk else None,
            "us_model_expected": "GD1YQ", "us_model_verification": "Inspect handset labeling/SKU; redfin alone does not establish the US variant",
            "carrier_or_voLTE_verified": False}


def collect_vendor_files(adb, directory, root=False):
    records, hits = [], []
    listing_error = None
    try:
        listing = adb.shell("sh", "-c", LIST_POLICY, root=root).decode(errors="replace").splitlines()
    except DeviceError as error:
        listing, listing_error = [], str(error)
    paths = sorted(set(["/vendor/etc/audio_policy_configuration.xml"] + [x.strip() for x in listing if x.strip()]))
    truncated_listing = len(paths) > MAX_VENDOR_FILES
    for path in paths[:MAX_VENDOR_FILES]:
        item = {"device_path": path}
        if not VENDOR_PATH.fullmatch(path):
            records.append({**item, "available": False, "error": "UNEXPECTED_VENDOR_PATH"})
            continue
        try:
            data = adb.shell("head", "-c", str(MAX_VENDOR_BYTES + 1), path, root=root, binary=True)
            if len(data) > MAX_VENDOR_BYTES:
                raise DeviceError("FILE_TOO_LARGE: excluded instead of publishing truncated XML")
            checksum = adb.shell("sha256sum", path, root=root).decode(errors="replace").split()[0].lower()
            if not re.fullmatch(r"[0-9a-f]{64}", checksum):
                raise DeviceError("DEVICE_SHA256_UNAVAILABLE")
            if checksum != hashlib.sha256(data).hexdigest():
                raise DeviceError("DEVICE_SHA256_MISMATCH: file changed or transfer was not exact")
            item.update(available=True, device_sha256=checksum,
                        **write_file(directory, "vendor-files/" + path.lstrip("/"), data))
            for number, line in enumerate(data.decode(errors="replace").splitlines(), 1):
                found = [term for term in TERMS if term.casefold() in line.casefold()]
                if found and len(hits) < 5000:
                    hits.append({"device_path": path, "line": number, "terms": found, "text": line[:600]})
        except (DeviceError, IndexError) as error:
            item.update(available=False, error=str(error) or "EMPTY_DEVICE_SHA256")
        records.append(item)
    groups = {}
    for prefix in ("audio_policy", "mixer_paths", "audio_platform_info"):
        matches = [x["device_path"] for x in records if Path(x["device_path"]).name.startswith(prefix)]
        groups[prefix + "*.xml"] = {"listed_paths": matches, "availability": "LISTED" if matches else "UNAVAILABLE_OR_NO_MATCH"}
    report = {"files": records, "requested_groups": groups, "listing_error": listing_error, "listing_truncated": truncated_listing,
              "max_files": MAX_VENDOR_FILES, "max_bytes_per_file": MAX_VENDOR_BYTES,
              "exact_bytes_required": True, "root_reads_requested": root}
    write_file(directory, "vendor-file-index.json", json_bytes(report))
    write_file(directory, "vendor-search-hits.json", json_bytes({"terms": TERMS, "matches": hits,
        "scope": "STATIC_DEVICE_CONFIGURATION_ONLY_NOT_A_RUNTIME_ROUTE_PROOF", "max_matches": 5000}))
    return report


def decode_bridge(raw):
    if len(raw) > MAX_JSON_BYTES:
        raise ValueError("DIAGNOSTICS_RESPONSE_TOO_LARGE")
    text = raw.decode("utf-8").strip()
    if text.startswith("{"):
        value = json.loads(text)
    else:
        match = re.search(r"(?:\[|,|\{)\s*json=", text)
        if not match:
            raise ValueError("DIAGNOSTICS_BRIDGE_JSON_MISSING")
        value, _ = json.JSONDecoder().raw_decode(text[match.end():].lstrip())
    if not isinstance(value, dict) or type(value.get("schema_version")) is not int or value.get("schema_version") != 1 or not isinstance(value.get("diagnostics"), dict):
        raise ValueError("DIAGNOSTICS_BRIDGE_SCHEMA_UNSUPPORTED")
    if not isinstance(value.get("app"), dict) or value["app"].get("package") != PACKAGE:
        raise ValueError("DIAGNOSTICS_PACKAGE_MISMATCH")
    return value


def collect_bridge(adb, directory, probe=False):
    method = "probe_downlink" if probe else "snapshot"
    try:
        raw = adb.shell("content", "call", "--uri", URI, "--method", method)
        if len(raw) <= MAX_JSON_BYTES:
            write_file(directory, "app-diagnostics-bridge-response.txt", raw)
        envelope = decode_bridge(raw)
        write_file(directory, "app-diagnostics.json", json_bytes(envelope))
        for key, filename in (("call_result", "call-result.json"), ("tx", "tx-diagnostics.json"),
                              ("rx", "rx-diagnostics.json"), ("recording_artifacts", "received-wav-metadata.json")):
            write_file(directory, filename, json_bytes({"source": "APP_DIAGNOSTICS_BRIDGE", "generated_at": envelope.get("generated_at"),
                "available": envelope.get(key) is not None, "data": envelope.get(key),
                "note": "Reported metadata only; original server-exported WAV is optional separate evidence" if key == "recording_artifacts" else None}))
        return envelope, None
    except (DeviceError, ValueError, UnicodeError) as error:
        reason = str(error)
        write_file(directory, "app-diagnostics.unavailable.json", json_bytes({"available": False, "error": reason,
            "method": method, "transport": "ADB shell/root + DUMP-protected provider", "fallback_to_exported_files": False}))
        return None, reason


def fresh(timestamp, now, limit):
    try:
        moment = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        if moment.tzinfo is None:
            return False
        return -5 <= (now - moment).total_seconds() <= limit
    except (AttributeError, TypeError, ValueError):
        return False


def verify_snapshot(envelope, package_dump="", role_dump="", max_age=60, now=None):
    now = now or utc_now()
    diagnostics = envelope.get("diagnostics", {}) if envelope else {}
    checks = []
    def add(name, value, detail=""):
        status = "PASS" if value is True else "FAIL" if value is False else "FAIL_NOT_TESTED"
        checks.append({"check": name, "status": status, "detail": detail})
    live = bool(envelope and fresh(envelope.get("generated_at"), now, max_age))
    add("fresh app diagnostics", live if envelope else None, "Snapshot age bound includes device/host clock synchronization")
    current = package_dump.split("Hidden system packages:")[0]
    permissions = diagnostics.get("permissions", {})
    if not isinstance(permissions, dict):
        permissions = {}
    for name in PRIVILEGES:
        full = "android.permission." + name
        value = permissions.get(full) if live else None
        if value is None and re.search(r"Package \[com\.sinch\.vqprobe\]", current):
            value = bool(re.search(r"^\s*" + re.escape(full) + r":\s*granted=true\b", current, re.M))
        add(name, value, "Actual app permission or exact PackageManager granted=true evidence")
    privileged = bool(re.search(r"privateFlags=\[[^\]]*\b(?:PRIVATE_FLAG_)?PRIVILEGED\b", current)) if current.strip() else None
    add("privileged application flag", privileged, "UID 0 is not a replacement for the APK's privileged status")
    role = diagnostics.get("default_dialer_role") if live else None
    if role is None and role_dump.strip():
        role = PACKAGE in role_dump.splitlines()
    add("default dialer role", role)
    outputs = diagnostics.get("outputs") if live else None
    telephony = any(isinstance(x, dict) and (x.get("type_name") == "TYPE_TELEPHONY" or x.get("type") == 18)
                    and x.get("is_sink") is True for x in outputs) if isinstance(outputs, list) else None
    add("TYPE_TELEPHONY output available", telephony, "Live AudioManager enumeration; this does not establish an AudioTrack route")
    call = diagnostics.get("call", {})
    if not isinstance(call, dict):
        call = {}
    owned = live and call.get("state") == "ACTIVE" and call.get("probe_call_owned") is True and bool(call.get("call_id"))
    add("ACTIVE owned cellular call", bool(owned) if live else None)
    init = diagnostics.get("voice_downlink_initialization", {})
    if not isinstance(init, dict):
        init = {}
    same_call = bool(owned and init.get("call_id") == call.get("call_id") and
                     init.get("call_command_id") == call.get("command_id") and call.get("command_id"))
    tested = same_call and init.get("attempted") is True and fresh(init.get("observed_at"), now, max_age) and init.get("scope") == "AUDIORECORD_INITIALIZATION_ONLY"
    known_source = init.get("source") == "VOICE_DOWNLINK"
    result = init.get("available") if tested and known_source else None
    add("VOICE_DOWNLINK initialization available", result,
        "Initialization only; no DOWNLINK PCM, modem path, or physical success implied. " +
        (str(init.get("error") or init.get("reason") or init.get("exception") or "No fresh initialization for this current call") if init else "Use --probe-downlink during an ACTIVE probe-owned call"))
    return {"generated_at": now.isoformat(), "all_prerequisites_pass": all(x["status"] == "PASS" for x in checks),
            "checks": checks, "tx_digital_audio_proven": False, "rx_digital_audio_proven": False,
            "physical_acceptance": "NOT_TESTED_BY_THIS_TOOL"}


def read_text(directory, relative):
    path = directory / relative
    return path.read_text(errors="replace") if path.exists() else ""


def import_evidence(args, directory):
    records = []
    for field in ("call_result", "tx_json", "rx_json", "wav_metadata", "audio_verification", "wav", "far_end_wav", "reference_wav"):
        source = getattr(args, field, None)
        if source is None:
            continue
        path = Path(source)
        limit = MAX_WAV_BYTES if field.endswith("wav") else MAX_JSON_BYTES
        if not path.is_file() or path.stat().st_size > limit:
            raise ValueError(f"{field}: missing file or size bound exceeded")
        data = path.read_bytes()
        if not field.endswith("wav"):
            json.loads(data)
        filename = "external/" + field.replace("_", "-") + (".wav" if field.endswith("wav") else ".json")
        item = {"field": field, "source_filename": path.name, "provenance": "EXPLICIT_OPERATOR_PROVIDED_FILE", **write_file(directory, filename, data)}
        if field.endswith("wav"):
            with wave.open(str(path), "rb") as wav:
                expected = wav.getnframes() * wav.getnchannels() * wav.getsampwidth()
                actual = 0
                while True:
                    chunk = wav.readframes(65536)
                    if not chunk:
                        break
                    actual += len(chunk)
                if actual != expected:
                    raise ValueError(f"{field}: truncated WAV data")
                item["wav_metadata"] = {"sample_rate": wav.getframerate(), "channels": wav.getnchannels(),
                    "bits_per_sample": wav.getsampwidth() * 8, "frames": wav.getnframes(),
                    "duration_seconds": wav.getnframes() / wav.getframerate(), "pcm_bytes": actual,
                    "compression": wav.getcomptype(), "marker_verified": False}
        records.append(item)
    write_file(directory, "external-evidence-index.json", json_bytes(records))
    return records


def collect(adb, directory, mode="capability", root=False, probe=False, max_age=60, logcat_lines=4000):
    evidence = {}
    for filename, command in {
        "getprop.txt": ("getprop",), "package.txt": ("dumpsys", "package", PACKAGE),
        "audio.txt": ("dumpsys", "audio"), "audio-policy.txt": ("dumpsys", "media.audio_policy"),
        "default-dialer.txt": ("cmd", "role", "get-role-holders", "--user", "0", "android.app.role.DIALER"),
        "selinux.txt": ("getenforce",),
    }.items():
        evidence[filename] = capture(adb, directory, filename, *command)
    magisk = None
    if root:
        evidence["magisk-version.txt"] = capture(adb, directory, "magisk-version.txt", "magisk", "-v", root=True)
        magisk = read_text(directory, "magisk-version.txt").strip() or None
    props = properties_report((directory / "getprop.txt").read_bytes() if (directory / "getprop.txt").exists() else b"", magisk)
    write_file(directory, "device-properties.json", json_bytes(props))
    vendor = collect_vendor_files(adb, directory, root)
    if mode == "collect":
        for filename, command in {
            "audio-flinger.txt": ("dumpsys", "media.audio_flinger"), "telecom.txt": ("dumpsys", "telecom"),
            "telephony-registry.txt": ("dumpsys", "telephony.registry"),
            "foreground-services.txt": ("dumpsys", "activity", "services", PACKAGE),
            "record-appop.txt": ("cmd", "appops", "get", "--user", "0", PACKAGE, "RECORD_AUDIO"),
            "audio-logcat.txt": ("logcat", "-b", "all", "-d", "-v", "threadtime", "-t", str(logcat_lines),
                "AudioFlinger:V", "AudioPolicyManager:V", "AudioPolicyService:V", "AudioTrack:V", "AudioRecord:V", "Telecom:V", "Telephony:V", "SinchVQ:V",
                "audio_hw_primary:V", "audio_hw_voice:V", "audio_hw_extn:V", "audio_hw_platform:V", "voice_extn:V", "ACDB-LOADER:V", "*:S"),
        }.items():
            evidence[filename] = capture(adb, directory, filename, *command)
        vendor_tags = ("audio_hw_primary", "audio_hw_voice", "audio_hw_extn", "audio_hw_platform", "voice_extn", "ACDB-LOADER")
        log = read_text(directory, "audio-logcat.txt")
        observed = {tag: bool(re.search(r"\b" + re.escape(tag) + r"\s*:", log)) for tag in vendor_tags}
        write_file(directory, "vendor-hal-log-coverage.json", json_bytes({"requested_tags": vendor_tags, "observed_tags": observed,
            "capture_available": evidence["audio-logcat.txt"]["available"], "max_lines": logcat_lines,
            "unobserved_tag_reason": "UNAVAILABLE_OR_NOT_EMITTED_IN_BOUNDED_WINDOW; release vendor builds may suppress debug logging",
            "absence_proves_unsupported": False}))
        evidence["selinux-denials.txt"] = capture(adb, directory, "selinux-denials.txt", "sh", "-c",
            f"logcat -b all -d -v threadtime -t {logcat_lines} | grep -i 'avc:.*denied' || true", root=root)
        if root:
            evidence["mixer-controls.txt"] = capture(adb, directory, "mixer-controls.txt", "sh", "-c",
                "if command -v tinymix >/dev/null 2>&1; then tinymix; elif [ -x /vendor/bin/tinymix ]; then /vendor/bin/tinymix; else echo 'UNAVAILABLE: tinymix missing'; exit 1; fi", root=True)
    # Take the application snapshot last so the verifier's freshness bound is meaningful.
    envelope, error = collect_bridge(adb, directory, probe)
    verification = verify_snapshot(envelope, read_text(directory, "package.txt"), read_text(directory, "default-dialer.txt"), max_age)
    write_file(directory, "audio-privileges.json", json_bytes(verification))
    summary = {"mode": mode, "collected_at": utc_now().isoformat(), "device": props,
        "evidence": evidence, "vendor_files_available": sum(x["available"] for x in vendor["files"]),
        "app_diagnostics_available": envelope is not None, "app_diagnostics_error": error,
        "downlink_probe_requested": probe, "physical_tx_rx_verified": False,
        "missing_external_recordings_are_not_success": True}
    write_file(directory, "collection-summary.json", json_bytes(summary))
    return verification


def manifest(directory):
    entries = {str(p.relative_to(directory)): {"size_bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
               for p in sorted(directory.rglob("*")) if p.is_file() and p.name != "evidence-manifest.json"}
    write_file(directory, "evidence-manifest.json", json_bytes({"created_at": utc_now().isoformat(), "files": entries,
        "physical_acceptance": "REQUIRES_CORRELATED_REFERENCE_AND_FAR_END_WAV_TESTS"}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("capability", "verify", "collect"))
    parser.add_argument("--serial", help="ADB serial; required if multiple devices are connected")
    parser.add_argument("--output", type=Path, help="New evidence directory; default is timestamped")
    parser.add_argument("--root", action="store_true", help="Use existing Magisk only for read-only vendor/mixer evidence")
    parser.add_argument("--probe-downlink", action="store_true", help="Explicit initialization-only VOICE_DOWNLINK probe in the app during an ACTIVE owned call")
    parser.add_argument("--max-age-seconds", type=int, default=60)
    parser.add_argument("--logcat-lines", type=int, default=4000)
    for option in ("call-result", "tx-json", "rx-json", "wav-metadata", "audio-verification", "wav", "far-end-wav", "reference-wav"):
        parser.add_argument("--" + option, type=Path, help="Copy an explicitly supplied server/operator evidence file (collect mode)")
    args = parser.parse_args()
    if not 5 <= args.max_age_seconds <= 600 or not 100 <= args.logcat_lines <= 20000:
        parser.error("--max-age-seconds must be 5..600; --logcat-lines must be 100..20000")
    if args.mode != "collect" and any(getattr(args, k) for k in ("call_result", "tx_json", "rx_json", "wav_metadata", "audio_verification", "wav", "far_end_wav", "reference_wav")):
        parser.error("External artifacts are accepted only by collect_pixel5_phaseb_evidence.sh")
    directory = args.output or Path("pixel5-" + args.mode + "-" + utc_now().strftime("%Y%m%dT%H%M%S.%fZ"))
    try:
        adb = Adb(args.serial)
        adb.connect(args.root)
        os.umask(0o077)
        directory.mkdir(parents=True, exist_ok=False)
        verification = collect(adb, directory, args.mode, args.root, args.probe_downlink, args.max_age_seconds, args.logcat_lines)
        if args.mode == "collect":
            import_evidence(args, directory)
        manifest(directory)
        if args.mode == "verify":
            for check in verification["checks"]:
                print(f"{check['status']}: {check['check']}")
                if check["status"] != "PASS" and check["detail"]:
                    print("  " + check["detail"])
            print("Initialization/enumeration prerequisites only; this tool does not declare physical TX/RX success.")
        print("Evidence: " + str(directory.resolve()))
        return 2 if args.mode == "verify" and not verification["all_prerequisites_pass"] else 0
    except (DeviceError, ValueError, OSError, wave.Error) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        if directory.is_dir():
            print("Partial evidence retained: " + str(directory.resolve()), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

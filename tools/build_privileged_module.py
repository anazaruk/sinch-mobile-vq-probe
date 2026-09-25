#!/usr/bin/env python3
"""Validate and reproducibly package an already-built APK as a Magisk system overlay."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

PACKAGE = "com.sinch.vqprobe"
APK_PATH = "system/priv-app/SinchVQ/SinchVQ.apk"
REQUIRED = ("MODIFY_PHONE_STATE", "CAPTURE_AUDIO_OUTPUT", "READ_PRECISE_PHONE_STATE", "READ_PRIVILEGED_PHONE_STATE")


def sdk_tool(name, explicit):
    if explicit:
        path = Path(explicit).resolve()
        if not path.is_file():
            raise ValueError(f"{name} not found: {path}")
        return str(path)
    found = shutil.which(name)
    if found:
        return found
    for root in (os.getenv("ANDROID_SDK_ROOT"), os.getenv("ANDROID_HOME")):
        if root:
            matches = sorted(Path(root).glob(f"build-tools/*/{name}"), reverse=True)
            if matches:
                return str(matches[0])
    raise ValueError(f"{name} missing; use --{name} PATH or install Android SDK Build Tools and set ANDROID_SDK_ROOT")


def run(*args):
    process = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
    if process.returncode:
        raise ValueError(f"{Path(args[0]).name} failed: {process.stderr.strip()[:1000]}")
    return process.stdout


def inspect_apk(apk, aapt2, apksigner):
    if not apk.is_file() or apk.stat().st_size > 100 * 1024 * 1024:
        raise ValueError("APK is missing or exceeds the 100 MiB package limit")
    badging = run(aapt2, "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != PACKAGE:
        raise ValueError(f"Expected APK package {PACKAGE}")
    permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", badging))
    missing = [p for p in REQUIRED if "android.permission." + p not in permissions]
    if missing:
        raise ValueError("APK lacks required manifest permissions: " + ", ".join(missing))
    signature = run(apksigner, "verify", "--print-certs", str(apk))
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.M)
    if len(signers) != 1:
        raise ValueError("Expected one valid signing certificate; multi-signer APK is unsupported by this installer")
    return {"package": PACKAGE, "version_code": int(package[2]), "version_name": package[3],
            "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "signer_sha256": signers[0].lower(), "apk_size_bytes": apk.stat().st_size,
            "required_privileged_permissions": ["android.permission." + p for p in REQUIRED],
            "build_reproducibility": "identical APK and source template produce identical ZIP bytes"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--aapt2")
    parser.add_argument("--apksigner")
    args = parser.parse_args()
    try:
        apk = args.apk.resolve()
        info = inspect_apk(apk, sdk_tool("aapt2", args.aapt2), sdk_tool("apksigner", args.apksigner))
        template = Path(__file__).resolve().parent.parent / "privileged-module"
        entries = {}
        for path in ("module.prop", "customize.sh", "system/etc/permissions/privapp-permissions-com.sinch.vqprobe.xml"):
            entries[path] = (template / path).read_bytes()
        prop = entries["module.prop"].decode()
        prop = re.sub(r"(?m)^version=.*$", "version=v" + info["version_name"], prop)
        prop = re.sub(r"(?m)^versionCode=.*$", "versionCode=" + str(info["version_code"]), prop)
        entries["module.prop"] = prop.encode()
        entries[APK_PATH] = apk.read_bytes()
        entries["apk.sha256"] = (info["apk_sha256"] + "  " + APK_PATH + "\n").encode()
        entries["build-info.json"] = (json.dumps(info, indent=2, sort_keys=True) + "\n").encode()
        output = args.output.resolve()
        if output == apk or output.exists():
            raise ValueError("Output must be a new path, distinct from the APK")
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path, data in sorted(entries.items()):
                entry = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
                entry.create_system = 3
                entry.external_attr = (0o100755 if path.endswith(".sh") else 0o100644) << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        print(json.dumps({"module": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest(), **info}, indent=2))
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()

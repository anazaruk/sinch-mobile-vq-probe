# Privileged installation and v0.1 migration

The v0.2.1 engineering module overlays the signed APK and its permission allowlist through **an already installed, working Magisk**, on the owner's Android user 0. The primary target is Pixel 5 GD1YQ/redfin, stock Android 14 UP1A.231105.001.B2 and Magisk 30.7. The installer accepts Magisk 24+ and Android 12/API 31 or later; this does not establish compatibility for other combinations. Android 15+ remains unverified. It does not unlock a bootloader, flash a boot image, wipe data, change radio/IMS firmware, edit mixer controls or disable SELinux. These instructions have not been executed on a handset.

For a factory-reset, non-rooted Pixel 5, start with [PIXEL5_REDFIN_SETUP.md](PIXEL5_REDFIN_SETUP.md). That runbook covers the explicitly manual stock-image and Magisk preparation, including the pre-root ordinary Verizon call. Return here when Magisk is working. This guide remains focused on APK/module migration, permissions and rollback.

## 1. Identify the connected phone

Use a Linux/macOS shell, or an environment with Bash, Python 3.10+, Android Platform Tools and USB access. Enable USB debugging on the dedicated phone, connect it, unlock it, and accept the computer's debugging authorization.

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.product.device
adb shell getprop ro.build.fingerprint
adb shell getprop ro.boot.flash.locked
adb shell getprop ro.boot.vbmeta.device_state
bash tools/pixel5_audio_capability_dump.sh --output evidence/pre-install
```

Exactly one authorized device should be connected. With multiple phones, pass `--serial SERIAL` to every supplied script and `-s SERIAL` to direct `adb` commands. Empty boot properties are inconclusive. The script output records what it could inspect; `incall_music` text found in XML is not a hardware pass.

For a phone already prepared with Magisk:

```bash
adb shell su -c id
adb shell su -c 'magisk -v'
adb shell getenforce
bash tools/pixel5_audio_capability_dump.sh --root --output evidence/pre-install-root
```

Expected indicators: `uid=0` from the explicit root check, a Magisk version, and `Enforcing`. Grant the interactive Magisk authorization for the debugging shell if prompted. Do not change SELinux mode to make a failing check pass.

## 2. Preserve the current installation

The package name remains `com.sinch.vqprobe`. A same-signer upgrade can retain enrollment, command deduplication and cached data. Stop tests, hang up the owned call, and keep the orchestrator database/artifacts backed up using your normal host procedure. Do not clear app data or uninstall to solve a signature error.

The module installer compares an existing APK's signer against the bundled APK. It fails on mismatch instead of uninstalling. If your previous APK was built with a different debug key, rebuild v0.2.1 with that original key or use a planned re-enrollment on a separate prepared device. Copying a different APK into `priv-app` does not bypass Android signature checks. Keep the signing key stable across releases.

An existing data APK can take precedence over a system overlay if versions differ. The installer/verification output must establish which APK is active. Prefer upgrading the existing app to the same v0.2.1 APK before installing that APK's module:

```bash
adb install -r Sinch-Mobile-VQ-Probe-v0.2.1-debug.apk
```

Expected: `Success`. A failure such as `INSTALL_FAILED_UPDATE_INCOMPATIBLE` requires resolving the signer mismatch; do not uninstall automatically. No privileged audio capability is expected from this ordinary APK installation alone.

## 3. Build or use the module

The delivered `Sinch-Mobile-VQ-Probe-v0.2.1-privileged-module.zip` contains:

```text
module.prop
customize.sh
apk.sha256
build-info.json
system/priv-app/SinchVQ/SinchVQ.apk
system/etc/permissions/privapp-permissions-com.sinch.vqprobe.xml
```

To rebuild from the source tree after compiling Android:

```bash
python3 tools/build_privileged_module.py \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --output Sinch-Mobile-VQ-Probe-v0.2.1-privileged-module.zip
```

If SDK tools are not discoverable, supply `--aapt2 /absolute/path/to/aapt2 --apksigner /absolute/path/to/apksigner`. The module grants requested privileged permissions through the same `system` partition's allowlist: `MODIFY_PHONE_STATE`, `CAPTURE_AUDIO_OUTPUT`, `READ_PRECISE_PHONE_STATE`, and `READ_PRIVILEGED_PHONE_STATE`. Runtime permissions still require consent or the explicit grant step below. It contains no persistent `su` command execution inside audio operations.

## 4. Stage, reboot manually, unlock, verify

```bash
bash tools/install_privileged_module.sh \
  --module Sinch-Mobile-VQ-Probe-v0.2.1-privileged-module.zip
```

Use `--apksigner /absolute/path/to/apksigner --aapt2 /absolute/path/to/aapt2` if SDK verification tools are not discoverable. This stages the module in Magisk and reports the next step; it does not reboot automatically. After checking the installer result and ending all calls, reboot manually:

```bash
adb reboot
adb wait-for-device
```

Unlock the phone once after it boots. The app uses credential-encrypted storage, so a successful `adb wait-for-device` alone does not mean the agent can access its credentials. Then verify:

```bash
bash tools/verify_privileged_install.sh
adb shell dumpsys package com.sinch.vqprobe
```

Expected evidence includes system/privileged installation and granted required permissions. `pm path` may show a data update of a system app; the actual privileged flag and granted permissions are decisive. A module merely listed in Magisk is insufficient.

For the dedicated test phone, the verifier offers explicit mutations instead of hiding them in installation:

```bash
bash tools/verify_privileged_install.sh --grant-runtime
```

`--grant-runtime` requests available declared runtime permissions for user 0. Inspect the output and repeat verification. If diagnostics specifically show a denied recording app-op after the grant, `--allow-record-appop` is a separate optional correction; it changes only RECORD_AUDIO to allow and is usually unnecessary. These flags do not confer signature/privileged permissions if the package was installed incorrectly. The verifier can return exit code 2 until remaining runtime permissions or the default-dialer role are granted.

Open the app, accept its default-dialer role dialog, and confirm phone, precise/background location, notifications and recording setup. Keep Location enabled for radio details. Recheck battery/OEM background settings. If this is an upgrade, confirm the original device ID and HTTPS origin are still present; do not re-enroll unnecessarily.

## 5. Inspect capability before and during a call

```bash
bash tools/pixel5_audio_capability_dump.sh --root --output evidence/privileged-idle
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --action GET_AUDIO_DIAGNOSTICS --parameters '{}'
```

Repeat diagnostics during an owned `ACTIVE` native SIM call:

```bash
bash tools/verify_audio_privileges.sh --root --probe-downlink \
  --output evidence/privileged-active
```

This prints PASS/FAIL for the three required audio privileges, default dialer role, live `TYPE_TELEPHONY` output and `VOICE_DOWNLINK` initialization, plus privilege/call/freshness checks. Unknown or stale evidence prints `FAIL_NOT_TESTED`; exit 2 means prerequisites remain unmet, and exit 1 indicates a collection error. Without `--probe-downlink` the script is passive. Even all PASS establishes prerequisites only. `incall_music_supported` or `voice_downlink_capture_available` remaining null is intentional: enumeration and initialization do not establish end-to-end audio. Continue with [AUDIO_ACCEPTANCE_TEST.md](AUDIO_ACCEPTANCE_TEST.md).

## Rollback without wiping app data

Stop audio and hang up first. Disable only this module, then reboot manually:

```bash
adb shell su -c 'touch /data/adb/modules/sinch_vq_privileged/disable'
adb reboot
```

After the first unlock, verify the current app/permissions and return to ordinary Phase A controls as appropriate. An existing data APK may remain; a module-only app may disappear when the overlay is disabled. Do not assume enrollment survives every OEM package-removal behavior: preserve server evidence and be prepared to re-enroll if needed. The script itself does not clear app data. If the device cannot boot far enough for authorized ADB, use the recovery procedure already established for that device's Magisk installation. This package does not automate a global module removal or factory reset.

Primary references: [Magisk module developer guide](https://topjohnwu.github.io/Magisk/guides.html), [AOSP privileged permission allowlists](https://source.android.com/docs/core/permissions/perms-allowlist). Allowlist placement and module packaging do not substitute for verification on the actual Android build.

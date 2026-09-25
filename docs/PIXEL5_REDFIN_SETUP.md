# Pixel 5 / redfin first-device setup — v0.2.1

This is the primary Phase B runbook. Start with a factory-reset, stock **Pixel 5 GD1YQ**, an unlockable bootloader and **no installed root**. The target is Google Android 14 **UP1A.231105.001.B2**, **Magisk 30.7**, and a Verizon US voice-enabled physical SIM. Unlocking and stock restoration erase data; these steps are intended for the dedicated Sinch engineering phone authorized for that purpose.

The APK, module and host tools have been built and tested in software. **No handset was unlocked, flashed or tested during this release. TX/RX physical acceptance is NOT RUN.** Complete the steps in order and retain the outputs. Use one connected phone and owner user 0.

## 1. Prepare the host and identify the phone

Use Bash on Linux/macOS, Python 3.10+, current [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools), Android SDK Build Tools (`aapt2`, `apksigner`) and JDK 17. Windows users can use an equivalent Bash environment with working USB access; the commands below are Bash commands, not PowerShell.

Extract the v0.2.1 source archive. Put the delivered APK and privileged-module ZIP in the project root alongside `README.md`. Run host commands from this directory unless a step explicitly changes directory.

```bash
set -euo pipefail
adb version
fastboot --version
python3 --version
adb devices -l
VQ_SERIAL='REPLACE_WITH_THIS_PHONES_SERIAL'
mkdir -p pixel5-bootstrap
VQ_BOOTSTRAP=$(cd pixel5-bootstrap && pwd)
adb -s "$VQ_SERIAL" shell getprop ro.product.model
adb -s "$VQ_SERIAL" shell getprop ro.product.device
adb -s "$VQ_SERIAL" shell getprop ro.build.id
adb -s "$VQ_SERIAL" shell getprop ro.build.fingerprint
adb -s "$VQ_SERIAL" shell getprop > "$VQ_BOOTSTRAP/pre-unlock-getprop.txt"
```

Expected model/codename: `Pixel 5` / `redfin`. Verify **GD1YQ** in the handset's regulatory/model information or original labeling. `redfin` alone does not prove the US variant or bootloader unlockability.

On the phone, finish stock setup, connect to the Internet, enable Developer options by tapping **Build number** seven times, then enable **USB debugging** and authorize this computer. Verify that **OEM unlocking** exists and can be enabled. If it is unavailable/disabled and `get_unlock_ability` below is 0, stop: do not use an exploit or a different-device image to bypass that restriction. A Verizon SIM does not require buying a Verizon-branded, bootloader-locked handset.

## 2. Unlock the bootloader

If the phone is already unlocked, record that state and skip the unlock command. Otherwise enable OEM unlocking before rebooting:

```bash
adb -s "$VQ_SERIAL" reboot bootloader
fastboot devices
fastboot -s "$VQ_SERIAL" getvar product
fastboot -s "$VQ_SERIAL" getvar unlocked
fastboot -s "$VQ_SERIAL" flashing get_unlock_ability
```

Confirm `product: redfin` and unlock ability `1` before the following **data-erasing** command:

```bash
fastboot -s "$VQ_SERIAL" flashing unlock
```

Confirm **Unlock the bootloader** using the phone's volume/power keys. This physical confirmation is required by the bootloader. Verify `unlocked: yes` afterward. Do not run `flashing unlock_critical`; it is not part of this procedure. Leave the bootloader unlocked for the Magisk-patched engineering configuration; never relock it while a patched boot image/module is installed.

## 3. Restore the exact stock Google B2 build

Use [Google's factory-image page, redfin section](https://developers.google.com/android/images#redfin). Acknowledge its terms yourself and select **14.0.0 (UP1A.231105.001.B2)**. The official file is:

```text
https://dl.google.com/dl/android/aosp/redfin-up1a.231105.001.b2-factory-4e5a2679.zip
```

The official URL returned HTTP 200 with a size of **2,477,429,231 bytes** during preparation. The complete ZIP was not downloaded here. Copy the **full 64-character SHA-256 from Google's exact redfin/B2 row** into the variable below. The `4e5a2679` filename suffix and HTTP ETag are not that hash. No unverified full checksum is supplied in this runbook.

```bash
VQ_FACTORY_ZIP="$VQ_BOOTSTRAP/redfin-up1a.231105.001.b2-factory-4e5a2679.zip"
curl --fail --location --proto '=https' --tlsv1.2 \
  'https://dl.google.com/dl/android/aosp/redfin-up1a.231105.001.b2-factory-4e5a2679.zip' \
  --output "$VQ_FACTORY_ZIP"
VQ_FACTORY_SHA256='PASTE_THE_FULL_SHA256_FROM_GOOGLE_HERE'
python3 - "$VQ_FACTORY_ZIP" "$VQ_FACTORY_SHA256" <<'PY'
import hashlib,re,sys
from pathlib import Path
p=Path(sys.argv[1]); expected=sys.argv[2].lower()
if not re.fullmatch(r'[0-9a-f]{64}',expected): raise SystemExit('STOP: full official SHA256 required')
h=hashlib.sha256()
with p.open('rb') as f:
    for b in iter(lambda:f.read(1024*1024),b''): h.update(b)
if h.hexdigest()!=expected: raise SystemExit('STOP: factory image checksum mismatch')
print('PASS: official factory ZIP SHA256 matches',h.hexdigest())
PY
unzip "$VQ_FACTORY_ZIP" -d "$VQ_BOOTSTRAP/stock"
```

Do not proceed if verification fails. The extracted directory should be `redfin-up1a.231105.001.b2`. Inspect its contents and Google's `flash-all.sh` before running it. Keep exactly this phone attached; `ANDROID_SERIAL` below selects it for the Google script. The stock restore writes the matching **official** bootloader/radio/vendor/system components; it preserves the commercial Google/Qualcomm implementation rather than substituting custom firmware.

```bash
VQ_STOCK_DIR="$VQ_BOOTSTRAP/stock/redfin-up1a.231105.001.b2"
ls "$VQ_STOCK_DIR"
fastboot -s "$VQ_SERIAL" getvar product
fastboot -s "$VQ_SERIAL" getvar unlocked
(
  cd "$VQ_STOCK_DIR"
  ANDROID_SERIAL="$VQ_SERIAL" bash ./flash-all.sh
)
```

Use the supplied script unchanged, including its stock wipe behavior. Inspect every flash result and wait for the stock first boot. Do not install LineageOS, GrapheneOS, generic AOSP, custom recovery, another modem/IMS stack, or a replacement Audio HAL. Do not disable verity/verification or patch `vbmeta` as a routine workaround. If already on the exact verified stock B2 build, restoring it is optional; still obtain and verify the matching factory package for its original `boot.img`.

## 4. Establish a stock Verizon baseline BEFORE rooting

Complete setup again after the wipe. Re-enable USB debugging and authorize the host. Insert the Verizon voice SIM now, enable only that subscription for this first test, and confirm the SIM is active for voice. Disable **Wi-Fi Calling** and cross-SIM/backup calling where offered. Internet for orchestration may still use Wi-Fi. Confirm automatic network selection and LTE service; an LTE data icon alone is not voice-bearer proof.

```bash
adb -s "$VQ_SERIAL" wait-for-device
adb -s "$VQ_SERIAL" shell getprop ro.product.device
adb -s "$VQ_SERIAL" shell getprop ro.build.id
adb -s "$VQ_SERIAL" shell getprop ro.build.version.release
adb -s "$VQ_SERIAL" shell getprop ro.build.fingerprint
adb -s "$VQ_SERIAL" shell getprop > "$VQ_BOOTSTRAP/stock-b2-getprop.txt"
bash tools/pixel5_audio_capability_dump.sh --serial "$VQ_SERIAL" \
  --output "$VQ_BOOTSTRAP/stock-capability"
```

Require `redfin`, `UP1A.231105.001.B2`, Android `14`. Open the stock Phone app and place an ordinary call to the controlled Sinch DID. Confirm two-way conversation and termination before adding root or the probe. During the call save:

```bash
adb -s "$VQ_SERIAL" shell dumpsys telephony.registry > "$VQ_BOOTSTRAP/stock-active-telephony.txt"
adb -s "$VQ_SERIAL" shell dumpsys telecom > "$VQ_BOOTSTRAP/stock-active-telecom.txt"
```

Record UTC time, destination, carrier, observed voice RAT/IMS information and the Sinch-side call identifier. Do not root to solve a baseline SIM/voice/IMS provisioning failure. Missing privileged measurements before rooting are expected; retain them as unknown.

## 5. Install Magisk 30.7 and patch the matching boot image

Download **Magisk-v30.7.apk** only from the [official v30.7 release](https://github.com/topjohnwu/Magisk/releases/tag/v30.7), into `pixel5-bootstrap/`. Record its hash for the run inventory. Follow the [official Magisk installation procedure](https://topjohnwu.github.io/Magisk/install.html); use **Select and Patch a File on this phone**, not someone else's patched image.

Pixel 5 uses the matching **`boot.img`**, not `init_boot.img`. Inspect the inner factory image archive and extract that exact file:

```bash
VQ_INNER_IMAGE="$VQ_STOCK_DIR/image-redfin-up1a.231105.001.b2.zip"
unzip -l "$VQ_INNER_IMAGE" boot.img
unzip -p "$VQ_INNER_IMAGE" boot.img > "$VQ_BOOTSTRAP/boot-stock-B2.img"
test -s "$VQ_BOOTSTRAP/boot-stock-B2.img"
adb -s "$VQ_SERIAL" install -r "$VQ_BOOTSTRAP/Magisk-v30.7.apk"
adb -s "$VQ_SERIAL" push "$VQ_BOOTSTRAP/boot-stock-B2.img" /sdcard/Download/boot-stock-B2.img
```

Open Magisk, confirm app version **30.7** and the normal ramdisk/boot installation path. Choose **Install → Select and Patch a File → boot-stock-B2.img**. Keep the displayed patch output/log. List the result and copy its **exact filename**—do not use an ambiguous wildcard if several old patched images exist:

```bash
adb -s "$VQ_SERIAL" shell ls -l /sdcard/Download/
# Replace this example with the exact filename produced by this patch operation.
VQ_PATCHED_PHONE='/sdcard/Download/magisk_patched-30700_REPLACE.img'
adb -s "$VQ_SERIAL" pull "$VQ_PATCHED_PHONE" "$VQ_BOOTSTRAP/boot-magisk-30.7-B2.img"
```

Keep original and patched images separately. Before flashing, verify the phone still reports build B2 and record its current slot. Do not apply an OTA between extracting/patching the image and this step:

```bash
adb -s "$VQ_SERIAL" shell getprop ro.build.id
adb -s "$VQ_SERIAL" shell getprop ro.boot.slot_suffix
adb -s "$VQ_SERIAL" reboot bootloader
fastboot -s "$VQ_SERIAL" getvar product
fastboot -s "$VQ_SERIAL" getvar unlocked
fastboot -s "$VQ_SERIAL" getvar current-slot
```

Set `VQ_SLOT` to the **observed** `a` or `b` value. Check it matches the pre-reboot slot suffix, and flash only that slot's boot partition:

```bash
VQ_SLOT='REPLACE_WITH_OBSERVED_a_OR_b'
case "$VQ_SLOT" in a|b) ;; *) printf '%s\n' 'STOP: select the observed active slot'; exit 1 ;; esac
fastboot -s "$VQ_SERIAL" flash "boot_$VQ_SLOT" "$VQ_BOOTSTRAP/boot-magisk-30.7-B2.img"
fastboot -s "$VQ_SERIAL" reboot
adb -s "$VQ_SERIAL" wait-for-device
```

Unlock the phone after boot. Open Magisk and complete any required additional setup/reboot it requests. Approve the explicit debugging-shell root request, then verify:

```bash
adb -s "$VQ_SERIAL" shell su -c 'id -u'
adb -s "$VQ_SERIAL" shell su -c 'magisk -v'
adb -s "$VQ_SERIAL" shell su -c 'magisk -V'
adb -s "$VQ_SERIAL" shell getenforce
adb -s "$VQ_SERIAL" shell getprop ro.build.id
```

Expected: root UID `0` for the shell, Magisk `30.7` / version code `30700`, SELinux **Enforcing**, build B2. Do not enable Zygisk/hiding modules or alter carrier configuration for this test. The Sinch app itself will run under its own normal app UID; root alone is not an audio-permission grant.

If the patched boot cannot boot, return to the bootloader and restore the saved stock B2 `boot.img` to the same recorded active slot. Do not change slots blindly or flash an image from another build. A complete stock factory restore is the documented reset path; keep the baseline artifacts first.

## 6. Install the Sinch privileged module and migrate the APK

End all calls. The delivered module contains the exact v0.2.1 APK (versionCode 3), the same signer as the preceding delivered release, and the privileged-permission allowlist. No audio-policy/IMS/HAL patch is included.

For an existing probe, preserve enrollment/state and check signer compatibility using the installer. Do not uninstall or clear data to bypass a mismatch. Upgrade an existing data APK to the exact delivered APK before staging its module when applicable:

```bash
adb -s "$VQ_SERIAL" install -r Sinch-Mobile-VQ-Probe-v0.2.1-debug.apk
bash tools/install_privileged_module.sh --serial "$VQ_SERIAL" \
  --module Sinch-Mobile-VQ-Probe-v0.2.1-privileged-module.zip
```

The same commands also work for first installation. `aapt2` and `apksigner` must be discoverable through the Android SDK or supplied with the installer's `--aapt2` / `--apksigner` options. The installer uses existing Magisk, checks signatures and stages the module; it does not root, wipe, reboot or silently uninstall.

```bash
adb -s "$VQ_SERIAL" reboot
adb -s "$VQ_SERIAL" wait-for-device
# Unlock the handset once before proceeding.
adb -s "$VQ_SERIAL" install -r Sinch-Mobile-VQ-Probe-v0.2.1-debug.apk
```

The final same-APK install/migration preserves the active version; it does not replace the need for the system overlay. Open Sinch Probe and accept **Request Default Dialer Role**. Complete required phone/recording permissions, notification permission and precise/background location for radio measurements. Keep device Location enabled and permit persistent background operation on this dedicated, powered phone. Then run the explicit runtime grant/verification step:

```bash
bash tools/verify_privileged_install.sh --serial "$VQ_SERIAL" --grant-runtime
```

If a check fails, resolve the reported prerequisite and rerun it before continuing; do not mask its exit code.

Require actual app grants for `MODIFY_PHONE_STATE`, `CAPTURE_AUDIO_OUTPUT`, `READ_PRECISE_PHONE_STATE` and the supplied additional `READ_PRIVILEGED_PHONE_STATE`, plus the system/privileged package flag. Ordinary sideload/UID 0 is insufficient. See [PRIVILEGED_INSTALL.md](PRIVILEGED_INSTALL.md) for same-signer migration, optional scoped AppOp repair and module rollback.

## 7. Enroll, repeat the carrier baseline, collect actual policy

Run the reference orchestrator/provisioning commands from [README.md](../README.md), or use the existing Sinch orchestrator implementing the documented API. Enroll the phone with its one-use code and HTTPS origin, start the foreground agent, and confirm its device ID/ONLINE heartbeat. Configure the correct controlled Sinch DID and allowed HTTPS media host on the server.

Recheck the physical Verizon SIM, Wi-Fi Calling disabled, LTE registration and an ordinary Verizon call after rooting/module installation. Record any difference from the stock baseline before testing injection. Where observable, verify that the active voice call is native IMS/LTE; do not promote a data-RAT LTE indication into a VoLTE PASS.

```bash
bash tools/pixel5_audio_capability_dump.sh --serial "$VQ_SERIAL" --root \
  --output "$VQ_BOOTSTRAP/privileged-idle"
```

This preserves the phone's real `/vendor/etc/audio_policy*.xml`, `mixer_paths*.xml` and `audio_platform_info*.xml` where readable, with exact paths/device SHA-256 and explicit unavailable records. Inspect `vendor-search-hits.json` for `incall_music`, `incall_music_uplink`, `Telephony Tx`, `Telephony Rx`, `voice_tx`, `voice_rx`. Runtime files are authoritative; upstream redfin policy is a design lead only.

Queue an owned call and wait for its `ACTIVE` event before the initialization probe:

```bash
python3 -m orchestrator.server --db probe.db queue --device-id DEVICE_ID \
  --action CALL --parameters '{"number":"+1REPLACE_WITH_SINCH_DID"}'
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
bash tools/verify_audio_privileges.sh --serial "$VQ_SERIAL" --root --probe-downlink \
  --output "$VQ_BOOTSTRAP/active-audio-prerequisites"
```

The verifier prints PASS/FAIL/FAIL_NOT_TESTED for the required permissions, dialer role, currently enumerated TYPE_TELEPHONY output and VOICE_DOWNLINK initialization. It allocates/releases AudioRecord for the explicit initialization test; it does not start recording or fall back to MIC. An idle phone cannot pass the active-call DOWNLINK prerequisite. The initialization PASS still does not prove received PCM.

```bash
bash tools/collect_pixel5_phaseb_evidence.sh --serial "$VQ_SERIAL" --root \
  --output "$VQ_BOOTSTRAP/active-call-evidence"
python3 -m orchestrator.server --db probe.db queue --device-id DEVICE_ID \
  --action HANGUP --parameters '{}'
```

The local ADB diagnostic bridge requires Android DUMP and an explicit shell/root UID check. It returns bounded app diagnostics, call result, TX/RX state and recording metadata; it never exports credentials or arbitrary private files. Framework capability checks run with the app identity after caller authorization.

## 8. Execute the highest-priority digital TX test

Generate separate known TX/RX markers, download the TX file using DOWNLOAD_FILE with its SHA-256, and prepare a recording at the Sinch far end. Use the exact manual commands or staged host automation in [AUDIO_ACCEPTANCE_TEST.md](AUDIO_ACCEPTANCE_TEST.md).

The required sequence is **CALL → DIALING → ACTIVE → PLAY_AUDIO** with:

```json
{"file_id":"reference01","mode":"DIGITAL_TX_VALIDATION","mute_microphone":true,"loop":false}
```

This mode verifies handset microphone mute before streaming and continually checks it. The app uses silence while establishing the route, then requires actual `AudioTrack.getRoutedDevice()` TYPE_TELEPHONY before source PCM. It does not play the reference through the speaker. Every route callback records requested and actual devices; a failed/mismatched route returns `TELEPHONY_AUDIO_ROUTE_FAILED`. An accepted preferred-device request is insufficient.

During playback, collect the full evidence script above into a new directory. Require actual Telephony TX, active AudioTrack, mute true, expected frames, underruns, and AudioPolicy/AudioFlinger evidence of the `incall_music_uplink` route. Keep Bluetooth, USB/external audio disconnected. Retain a local-microphone negative control and a STOP_AUDIO negative control at the far-end recorder. The mute API may also suppress injected audio on a vendor implementation; if so, this test fails and requires diagnosis, not silent unmuting.

Record the Sinch far-end WAV and bind it to this call ID/time. Compare the known marker/reference to the **actual recording**, not a copied reference file. Conclude physical TX PASS only after that signal is present and the runtime/bearer/isolation evidence supports the digital path. Confirm microphone state restoration after STOP/EOF/HANGUP and retain the final call result.

## 9. Execute digital DOWNLINK capture

Establish a separate owned ACTIVE call. Queue START_RECORDING with `direction: DOWNLINK` and a new file ID. After `AUDIO_RX_RECORDING_STARTED`, play the distinct known RX reference from Sinch, then STOP_RECORDING, UPLOAD_FILE and HANGUP. DOWNLINK is the acceptance source; VOICE_CALL/BOTH is only a separate diagnostic alternative, never an automatic replacement. MIC fallback is absent.

Preserve AudioRecord state/recording state, the actual recording configuration's source/device, original call/command IDs, signal counters and finalized WAV metadata. Require non-zero PCM, plausible/expected duration, valid PCM WAV and the correct reference marker. Use a local acoustic challenge to exclude physical microphone capture. Upload the immutable WAV through the authenticated command path and verify its SHA-256 on the server. Exact init/read failures remain diagnostic evidence, not RX success.

The host automation intentionally leaves the RX call/recording active after `rx-start` so the far end can play its reference. Finish with `rx-stop`; do not leave the handoff unattended. Its internal Android resource limits are not scenario scheduling.

## 10. Preserve one complete evidence package and review

Run this during the active operation for route/HAL state, then again after completion with actual files from the server. Choose new output directories each time:

```bash
bash tools/collect_pixel5_phaseb_evidence.sh --serial "$VQ_SERIAL" --root \
  --output evidence/pixel5-final \
  --call-result /path/to/call-result.json \
  --tx-json /path/to/tx-diagnostics.json \
  --rx-json /path/to/rx-diagnostics.json \
  --wav /path/to/received.wav \
  --far-end-wav /path/to/sinch-far-end.wav \
  --reference-wav /path/to/reference-test.wav
```

The collector includes getprop, audio/audio-policy/AudioFlinger, Telecom, telephony.registry, package/role, bounded relevant platform/Qualcomm logs, SELinux denials, available mixer state, exact vendor XML, raw/parsed app diagnostics, call result and audio artifact metadata. Missing files/permissions are explicit. `evidence-manifest.json` hashes the collected files. Keep original WAVs unchanged; the analysis tools can hand them to the existing Sinch quality/STT pipeline.

Use `pixel5_phaseb_acceptance.py evaluate` with the run's actual far-end/RX files and completed physical evidence template. Missing/unknown bearer proof, wrong call binding, wrong marker, failed route/source, missing recordings or corrupted evidence cannot pass automated gates. Even `AUTOMATED_CHECKS_PASSED_REVIEW_REQUIRED` requires an engineer to inspect the original evidence before recording physical TX/RX acceptance.

If injection fails, follow the exact investigation order in [AUDIO_TROUBLESHOOTING.md](AUDIO_TROUBLESHOOTING.md): permissions, ACTIVE call, enumeration, preferred/actual route, policy logs, runtime XML, incall route, AudioFlinger thread, vendor HAL logs, mixer state, SELinux, then an isolated minimal policy experiment. No custom HAL or custom ROM is the starting point. Future platform experiments belong in `device-support/redfin/`, separate from the generic Android agent.

Primary references: [Google factory images](https://developers.google.com/android/images#redfin), [AOSP bootloader locking/unlocking](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking), [Magisk 30.7](https://github.com/topjohnwu/Magisk/releases/tag/v30.7), [Magisk installation](https://topjohnwu.github.io/Magisk/install.html), [privileged permission allowlists](https://source.android.com/docs/core/permissions/perms-allowlist), [upstream redfin audio policy](https://android.googlesource.com/device/google/redfin/+/refs/heads/master/audio/audio_policy_configuration_a2dp_offload_disabled.xml). Phone runtime evidence and physical recordings remain the acceptance authority.

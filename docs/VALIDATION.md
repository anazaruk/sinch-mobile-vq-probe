# Delivery validation — v0.2.1

Date: 2026-09-25. Target: Pixel 5 GD1YQ/redfin, stock Google Android 14 UP1A.231105.001.B2, Magisk 30.7, Verizon voice SIM. This record covers source, build, protocol and packaging checks. No physical handset was connected. Digital TX/RX acceptance is **NOT RUN**.

| Check | Outcome |
| --- | --- |
| Android compile and debug APK assembly | PASS; JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20, SDK 35 |
| Android JVM unit tests | 22 PASS: WAV parser/recovery 14, PCM channel mapping 5, engineering-mode validation 3 |
| Android lint | PASS; 0 errors, 23 warnings |
| Reference orchestrator suite | 31 PASS, including actual local HTTPS, device ownership, artifact upload and v0.2.1 parameter validation |
| Host acceptance automation | 12 PASS: marker/evidence gates, stale call bindings, failed routes, timeout cleanup, unknown outcomes and no redial |
| Pixel evidence tooling | 13 PASS with mocked ADB: exact XML bytes/hash, private bridge, fresh same-call prerequisites, unavailable evidence and read-only collection |
| Audio generation/verification utilities | 9 PASS |
| Total automated tests | 87 PASS: 22 JVM + 65 Python |
| Privileged module | APK identity, permissions and signature verified with Android SDK tools; byte-identical independent rebuild |
| Packaged ADB diagnostics manifest | DUMP read/write permissions, fixed authority and no URI grants present; explicit shell/root Binder UID guard reviewed |
| Official preferred factory-image URL | Google HTTP 200 and 2,477,429,231-byte size verified; factory ZIP not downloaded or locally hashed |
| Pixel unlock / restore / rooting / module install | NOT RUN |
| Actual permission grants / dialer role / UI / ADB provider on handset | NOT RUN |
| Verizon native VoLTE call, bearer and codec verification | NOT RUN |
| Muted-microphone digital WAV received by Sinch far end | NOT RUN |
| Isolated VOICE_DOWNLINK captured and uploaded from handset | NOT RUN |
| Concurrent TX/RX, vendor mute behavior, interruption and reboot recovery | NOT RUN |

## Reproduce checks

From the project root:

```bash
python3 -m unittest discover -s tests -v
cd android
./gradlew --no-daemon :app:clean :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Then, from the repository root:

```bash
python3 tools/build_privileged_module.py \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --output Sinch-Mobile-VQ-Probe-v0.2.1-privileged-module.zip
```

The Android tests execute pure PCM/WAV and mode-validation code on the JVM. They do not execute AudioTrack, AudioRecord, Telecom, HAL, modem or microphone routing. Mocked ADB tests verify host control flow and evidence interpretation, not an Android installation. Acceptance fixtures are synthetic and cannot establish physical audio success. The CI workflow repeats software checks; no remote CI execution is claimed.

Lint warnings concern API-level constants, telephony requirements, engineering UI strings, storage-space guidance and existing Intent/SAM diagnostics. The four deliberate system permission requests retain narrow `ProtectedPermissions` suppressions in the manifest. Actual grants are checked at runtime; ordinary APK installation does not confer them. The diagnostic provider checks the original Binder caller UID before clearing identity, so AudioRecord capability tests run with the app's normal identity rather than shell/root identity.

## Release scope

The source package includes Android source/tests, pinned Gradle wrapper, CI, reference HTTPS orchestrator, staged host acceptance CLI, the three Pixel-specific evidence/verification scripts, module template, audio utilities and setup/acceptance/troubleshooting runbooks. It excludes build caches, enrolled credentials, TLS keys, local databases and signing private keys. Google firmware and Magisk binaries are not redistributed.

This is a development-signed APK with the same signing certificate as the preceding delivered release. The module embeds its exact bytes. Migration must retain a compatible signer; the installer refuses mismatches and does not uninstall or clear app data. The hardware runbook starts before rooting and includes explicit manual wipe/unlock/stock-restore steps authorized for this dedicated device. No such action was performed during development.

The full factory-image SHA-256 must be taken from Google's exact redfin/B2 download row and checked before extraction/flashing. A filename suffix or ETag is not that SHA-256. The runbook does not claim that the large factory archive has been verified locally.

`DIGITAL_TX_VALIDATION` requires microphone mute, confirms actual Telephony TX routing while playing, and records every routing callback. Native PCM16 mono inputs at 8/16/48 kHz are retained; explicit mono-to-dual-mono mapping is reported. Mismatched sample-rate overrides fail rather than silently alter the VQ reference. These checks still require a far-end recording to establish physical digital injection. Likewise, `VOICE_DOWNLINK` initialization is a prerequisite only; actual capture needs source/configuration, signal and microphone-isolation evidence.

The host evaluator never converts framework success, non-zero PCM, unknown VoLTE or missing evidence into physical acceptance. Even a fully satisfied automated evaluation requires engineering review of the original evidence. Follow [PIXEL5_REDFIN_SETUP.md](PIXEL5_REDFIN_SETUP.md) and [AUDIO_ACCEPTANCE_TEST.md](AUDIO_ACCEPTANCE_TEST.md).

## Artifact identities and evidence

- APK: `Sinch-Mobile-VQ-Probe-v0.2.1-debug.apk`, versionName `0.2.1`, versionCode `3`, 2,857,131 bytes.
- APK SHA-256: `b3dcc8eddf8e8e138a6eba9f4fd4509062d6359dcb325a3ce15bd03137a8b020`.
- Module: `Sinch-Mobile-VQ-Probe-v0.2.1-privileged-module.zip`.
- Module SHA-256: `1d2b4e2f73b747568a158498cf739a2ba734946ef7d3d13a5397d781c80b7e22`.
- Signer certificate SHA-256: `2a1fa74d4fe2bb699d2cc06416fa1dc63a3546e926d55a90151dd436284893f0`.

`verification/` contains JVM XML results, the full final Python test output, lint text, Android build/source-consistency logs, APK signature output and module verification. No file in that directory is presented as a handset measurement.

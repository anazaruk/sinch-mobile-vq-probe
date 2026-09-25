# Sinch Mobile Voice Quality Probe v0.2.1

This engineering release extends the existing Phase A probe with experimental privileged digital TX injection and cellular voice-source capture. A dedicated Android phone receives commands through outbound HTTPS polling, originates native SIM voice calls, observes Telecom states, and reports measurements/audio artifacts. The phone contains no carrier test scenarios or schedules.

**Кратко:** v0.2.1 уточняет цифровой TX/RX для Pixel 5/redfin: stock Android 14 UP1A.231105.001.B2, Magisk 30.7, первая SIM — Verizon. Phase A сохранена. Для телефона без root начните с [пошаговой подготовки Pixel 5](docs/PIXEL5_REDFIN_SETUP.md). Затем установите привилегированный модуль, проверьте разрешения и выполните отдельные TX/RX-тесты. Физический результат подтверждается реальными записями и изоляцией микрофона; сборка и программные тесты этого не доказывают.

This is an engineering implementation for dedicated Sinch-owned devices. Real Verizon/AT&T/T-Mobile handset acceptance, digital audio paths and unattended reliability still need to be measured. An LTE data indicator does not establish that a call used VoLTE. Preferred-device acceptance does not establish actual routing; actual routing does not establish far-end delivery.

Start with the [Pixel 5 stock-device setup runbook](docs/PIXEL5_REDFIN_SETUP.md), then [privileged module installation and same-signer migration](docs/PRIVILEGED_INSTALL.md), [Phase B audio design/API](docs/PHASE_B_AUDIO.md), [physical acceptance](docs/AUDIO_ACCEPTANCE_TEST.md), and [troubleshooting](docs/AUDIO_TROUBLESHOOTING.md). The primary target is the US Pixel 5 GD1YQ/redfin with stock Google Android 14 build UP1A.231105.001.B2 and Magisk 30.7. Rooting/flashing steps are explicit manual runbook actions; no supplied script performs them automatically. Keep the Google userspace, carrier configuration, Qualcomm modem/vendor Audio HAL and OEM IMS stack.

## What is included

| Area | v0.2.1 behavior |
| --- | --- |
| Android | Kotlin, Android 10+ / API 29 minimum, target API 35, default dialer and `InCallService` |
| Control | Enrollment, renewable per-device credentials, HTTPS polling and durable result/event upload |
| Native calling | Explicit SIM phone-account selection; one probe call at a time; call-state timestamps |
| Measurements | Best-effort SIM, service, RAT and cell/signal snapshots with unavailable values represented explicitly |
| Files | HTTPS download, SHA-256 verification, private cache, delete |
| Operation | Foreground status notification, reconnect/backoff, boot receiver, local structured diagnostics |
| Reference orchestrator | Python standard library, SQLite and TLS; CLI administration, no web dashboard |
| Experimental TX | Privileged AudioTrack to actual observed `TYPE_TELEPHONY`; PCM16 mono 8/16/48 kHz, reported mono/dual-mono client output |
| Experimental RX | Privileged voice-source AudioRecord, finalized/recovered WAV and authenticated artifact upload |
| Installation/evidence | Reproducible Magisk overlay, permission/capability/debug scripts, distinct TX/RX marker generation and waveform analysis |
| Pixel 5 validation | `DIGITAL_TX_VALIDATION`, route-change evidence, exact vendor XML copies, fresh same-call DOWNLINK initialization checks and staged host acceptance CLI |
| Deferred | Guaranteed handset compatibility, forced network mode, custom HAL work and audio-quality analytics |

See [architecture](docs/ARCHITECTURE.md), [API](docs/API.md), [handset limitations](docs/ANDROID_LIMITATIONS.md), and the [acceptance runbook](docs/ACCEPTANCE_TEST.md).

## 1. Run the reference orchestrator

Use Python 3.10+ on a host the phone can reach. Commands below run from the repository root. No Python packages are required.

Obtain a TLS certificate and matching private key for the hostname entered on the phone. The certificate chain must be trusted by Android and the hostname must match; the client does not disable verification. Installing a user CA alone does not make this target-SDK-35 app trust it. An internal CA requires an explicit, restricted Android network-security configuration and a rebuilt APK. A public, trusted certificate is the simplest initial setup.

```bash
python3 -m orchestrator.server --db probe.db serve \
  --host 0.0.0.0 --port 8443 \
  --cert /path/to/fullchain.pem --key /path/to/privkey.pem
```

The default bind address is loopback; the explicit address above permits phone access. Expose only the intended HTTPS port and run the PoC service on a controlled host. This reference server has no production ingress, fleet UI or deployment automation.

In another terminal, provision one device:

```bash
python3 -m orchestrator.server --db probe.db create-enrollment \
  --device-name NJ-VZ-001 --carrier Verizon --site-id NJ \
  --test-number +12165551212 --poll-seconds 15 \
  --download-host media.example.com
```

Replace the illustrative number with your controlled answering endpoint. Save the returned one-use enrollment code for initial setup. Use exactly `NJ-VZ-001` in the app; the server binds the code to this name. The code expires after one hour by default. Create a separate code for each phone.

## 2. Build and install Android

Open `android/` in Android Studio. Use JDK 17 and install Android SDK Platform 35. Pinned build versions are AGP 8.9.2, Kotlin 2.1.20 and Gradle 8.11.1. The first Gradle run needs access to the configured dependency repositories.

From `android/`:

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. A debug APK is for engineering deployment; sign a release build using your organization's key before wider distribution. No key or permanent server credential is embedded in source.

Ordinary installation supports Phase A but does not grant cellular-audio privilege. For an unrooted Pixel 5 follow [stock-device preparation](docs/PIXEL5_REDFIN_SETUP.md), then the [module installation guide](docs/PRIVILEGED_INSTALL.md), including same-signer migration, manual reboot/first unlock, permission verification and rollback. Run host tests from the repository root:

```bash
python3 -m unittest discover -s tests -v
```

Pixel evidence tools are `tools/pixel5_audio_capability_dump.sh`, `tools/verify_audio_privileges.sh`, and `tools/collect_pixel5_phaseb_evidence.sh`. The verifier's `--probe-downlink` option requires an ACTIVE probe-owned call and tests initialization only. `tools/pixel5_phaseb_acceptance.py` runs explicit `prepare`, `tx`, `rx-start`, `rx-stop`, and `evaluate` stages on the orchestrator host; [the acceptance guide](docs/AUDIO_ACCEPTANCE_TEST.md#staged-host-acceptance-cli) gives exact commands and the external media handoff. PASS for permissions, enumeration or initialization is separate from physical digital-audio proof.

## 3. Provision the handset

Use a dedicated, voice-capable handset with an activated carrier voice SIM and a working ordinary outgoing call. For the first test, enable only one SIM. An emulator cannot validate carrier calling.

1. Launch **Sinch Mobile VQ Probe** once after installation.
2. Grant the requested phone/call permissions and notification permission where Android asks for it.
3. Request the **default Phone/dialer** role in the app and accept Android's role dialog. Merely installing the APK does not grant this role.
4. Grant precise location and enable device Location for cell information. For measurements while the UI is closed, grant **Allow all the time** in app location settings when available; this is separate from the initial permission prompt. Calls must still work if a radio field is unavailable.
5. Review the device's battery settings. For the dedicated test phone, permit background operation and disable applicable OEM sleeping-app restrictions. Keep it powered for the initial run. These settings improve reliability but do not make polling a real-time guarantee.
6. Disable Wi-Fi Calling and cross-SIM/backup calling for the cellular-bearer acceptance run. Wi-Fi can still carry HTTPS control traffic. Record any operator setting for VoLTE/advanced calling and the handset software version.
7. Enter an HTTPS origin such as `https://vq.example.com:8443` with no path, the one-use code, and the exact enrolled name. Enroll and start the probe agent.
8. Verify the persistent status notification, device ID, SIM state, and successful polling. Check the server with the command below.

```bash
python3 -m orchestrator.server --db probe.db list-devices
```

ONLINE means the server recently received a poll. It does not guarantee SIM registration or IMS availability; inspect the reported state as well.

## 4. Execute the first call

Replace `DEVICE_ID` with the enrolled device ID and the number with your test endpoint:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --action CALL \
  --parameters '{"number":"+12165551212"}'
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
```

Wait for `ACTIVE`, then queue `HANGUP` in a separate command:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --action HANGUP --parameters '{}'
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db logs --device-id DEVICE_ID
```

`CALL` command `SUCCESS` means Android accepted submission. The later `CALL_RESULT` event is the final call outcome. Polling continues during the call so the phone can receive `HANGUP`. Do not queue a second `CALL` to retry a lost response; inspect the existing command, events and current handset state first.

The [acceptance runbook](docs/ACCEPTANCE_TEST.md) defines the evidence needed to declare the Verizon milestone complete.

## Project layout

The Android project has one Gradle app module with responsibility packages under `com.sinch.vqprobe`: `api`, `commands`, `telecom`, `telemetry`, `audio`, `files`, `service`, `storage`, `ui`, and `logging`. Keeping these packages in one module reduces PoC build complexity.

| Path | Purpose |
| --- | --- |
| `android/` | Gradle project and Android source |
| `orchestrator/` | HTTPS reference server and SQLite-backed CLI |
| `tests/` | Automated protocol/backend verification |
| `tools/` | Privileged packaging/install checks, device evidence and PCM analysis utilities |
| `privileged-module/` | Magisk overlay template and permission allowlist |
| `docs/` | Protocol, design constraints and physical-device runbook |

## Operational boundaries

`REBOOT_APP` restarts the agent loop; it does not reboot Android or intentionally kill the process. `BOOT_COMPLETED` recovery requires successful initial setup and may require the first unlock after a reboot. Force-stop requires user interaction to resume. Background-start restrictions, Doze and OEM behavior remain part of device qualification.

Renewal uses 15-minute access tokens and a stable per-device refresh credential with a sliding 30-day lifetime in this PoC. The backend stores credential hashes. Revoke a lost/retired device with:

```bash
python3 -m orchestrator.server --db probe.db revoke-device --device-id DEVICE_ID
```

The experimental Phase B path requires an owned `ACTIVE` call and actual permissions/route/source checks. Unsupported routes fail; there is no speaker/MIC fallback. Physical proof requires muted-microphone TX received at the far end and isolated known downlink recorded on Android. `UPLOAD_FILE` is an orchestrator-owned command; local engineering controls do not bypass server artifact ownership. MOS, WER and audio quality scoring remain external work.

## Validation status

See the [validation record](docs/VALIDATION.md) for build and automated-check results. No physical-handset, carrier-bearer or unattended-recovery result is claimed by this README. Record the exact device, SIM/carrier, Android version, APK version and run evidence before marking hardware acceptance complete.

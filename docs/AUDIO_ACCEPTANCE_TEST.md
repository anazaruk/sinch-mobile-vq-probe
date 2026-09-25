# Physical Phase B acceptance — Verizon first

**Status: NOT RUN.** This runbook requires the real Android phone, carrier voice SIM, working privileged installation, and a controlled Sinch endpoint that records the phone-to-server leg and independently plays a known file toward the phone. The supplied orchestrator is not that media endpoint. Existing Sinch VQ/Asterisk endpoint configuration is site-specific and is not silently modified by this release.

For v0.2.1 the primary target is US Pixel 5 GD1YQ/redfin, stock Google Android 14 UP1A.231105.001.B2, Magisk 30.7 and Verizon. Begin a non-rooted phone with [PIXEL5_REDFIN_SETUP.md](PIXEL5_REDFIN_SETUP.md), then [module installation](PRIVILEGED_INSTALL.md). Keep the OEM modem/carrier IMS implementation. Record model, Android/vendor build, Magisk version, APK signer/version, SIM/subscription, carrier, call ID and endpoint recording ID. Complete [Phase A call acceptance](ACCEPTANCE_TEST.md) first.

## Prepare distinct reference signals

From the repository root:

```bash
python3 tools/generate_test_audio.py --out-dir evidence/markers \
  --sample-rate 16000 --duration 12 --run-id vz-audio-001
```

This creates `tx-reference.wav`, `rx-reference.wav`, and `manifest.json`. TX contains a deterministic pulsed sequence of 733/1379 Hz; RX uses 1091/1877 Hz. The run ID changes the pulse pattern reproducibly. These generated inputs are not evidence of a completed hardware test.

Publish **tx-reference.wav** to a trusted HTTPS media host on port 443 allowed by the probe enrollment. Put **rx-reference.wav** on the controlled far-end playback application. Preserve both exact originals and hashes. Do not play the TX reference through a phone speaker or external device.

For the following commands replace `DEVICE_ID`, your actual DID and the media hostname. Obtain the exact TX SHA-256 from `manifest.json`; replace `TX_SHA256` below. Each deliberate new trial must use new command and recording IDs.

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b1-download \
  --action DOWNLOAD_FILE \
  --parameters '{"file_id":"tx-reference","url":"https://media.example.com/tx-reference.wav","sha256":"TX_SHA256"}'
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID
```

Require successful checksum/cache acknowledgment before calling. The placeholder digest is intentionally invalid until replaced. Disable Wi-Fi Calling/cross-SIM backup calling and Bluetooth audio for the bearer/isolation trial. Wi-Fi may still carry HTTPS control. A native SIM call or LTE data field does not by itself prove VoLTE; retain independent bearer evidence or label it unknown.

## Test 1 — digital TX with microphone muted

1. Arm recording on the far-end **incoming leg only**, without mixing its own playback into that recording. Keep far-end playback silent for this TX trial.
2. Place the call and wait for the original call's `ACTIVE` event:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b1-call --action CALL \
  --parameters '{"number":"+12165551212"}'
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
```

3. Capture idle-versus-active diagnostics, verify the active-call prerequisites, then start TX in `DIGITAL_TX_VALIDATION` mode. This mode requires microphone mute and records the previous/during/restored state:

```bash
bash tools/pixel5_audio_capability_dump.sh --root --output evidence/b1-before
bash tools/verify_audio_privileges.sh --root --probe-downlink --output evidence/b1-active-check
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b1-diag --action GET_AUDIO_DIAGNOSTICS --parameters '{}'
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b1-play --action PLAY_AUDIO \
  --parameters '{"file_id":"tx-reference","mode":"DIGITAL_TX_VALIDATION","loop":false,"mute_microphone":true}'
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
bash tools/collect_pixel5_phaseb_evidence.sh --root --output evidence/b1-playing
```

4. Require actual telephony route evidence, source frames written and an `AUDIO_TX_ACTIVE` event. `setPreferredDevice` returning true alone fails this gate. The source must not be emitted through the speaker/earpiece/headset. Verify the physical microphone is reported muted; challenge it with local speech/tapping during a known silent interval and confirm that challenge does not appear in the far-end recording. This challenge is an isolation check, never the test audio transport.
5. Let the 12-second non-looping file finish and inspect its terminal event. Then clean up explicitly:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b1-stop --action STOP_AUDIO --parameters '{}'
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b1-hangup --action HANGUP --parameters '{}'
```

6. Retrieve the actual far-end recording as `evidence/far-end-tx.wav`, retaining the endpoint CDR/recording identity. Analyze it:

```bash
python3 tools/audio_verify.py evidence/far-end-tx.wav \
  --reference evidence/markers/tx-reference.wav \
  --tone 733 --tone 1379 --tone 1091 --tone 1877 \
  --call-id ACTUAL_CALL_ID --device-id DEVICE_ID \
  --out-dir evidence/tx-analysis --copy-wavs
```

Expected evidence: TX marker frequencies and pulse pattern appear after plausible call/recording offset; the unrelated RX marker does not dominate; no clipping/silence problem conceals the result; no local challenge is received; the prior mute state is restored. Codec filtering may change amplitudes and correlations, so the utility reports measurements, not a universal pass threshold.

If the far end is silent only when mute is enabled, record **MUTE ALSO AFFECTS INJECTION / NOT ACCEPTED**, collect diagnostics, and investigate the vendor mute path. An unmuted repeat is diagnostic only and cannot satisfy the specified muted-microphone proof.

## Test 2 — digital RX/downlink

Use a new call ID and a new recording file ID. The far end should remain silent until RX has acknowledged recording. Do not start Android TX in this first RX trial.

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b2-call --action CALL \
  --parameters '{"number":"+12165551212"}'
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
```

After `ACTIVE`:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b2-record --action START_RECORDING \
  --parameters '{"direction":"DOWNLINK","file_id":"rx-b2","sample_rate":16000}'
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
bash tools/collect_pixel5_phaseb_evidence.sh --root --output evidence/b2-recording
```

Require `AUDIO_RX_RECORDING_STARTED`, matching `VOICE_DOWNLINK` configuration, and no framework-silenced/microphone-route error. Now make the far-end application play its exact **rx-reference.wav** once. Keep the handset microphone isolated/muted using the call UI where available; record that setting. Do not substitute a microphone recorder if the digital source is rejected.

After far-end playback finishes:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b2-stop --action STOP_RECORDING --parameters '{}'
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
```

Wait for the finalized artifact in the RX stopped/failed event. A STOPPING acknowledgment is not finalization. Then:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b2-upload --action UPLOAD_FILE \
  --ttl-seconds 600 --parameters '{"file_id":"rx-b2"}'
python3 -m orchestrator.server --db probe.db artifacts --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id b2-hangup --action HANGUP --parameters '{}'
```

The artifact CLI provides the server filesystem path. Use that actual path for analysis; there is no public artifact URL:

```bash
python3 tools/audio_verify.py /actual/server/artifact/path.wav \
  --reference evidence/markers/rx-reference.wav \
  --tone 1091 --tone 1877 --tone 733 --tone 1379 \
  --call-id ACTUAL_CALL_ID --device-id DEVICE_ID \
  --out-dir evidence/rx-analysis --copy-wavs
```

Expected evidence: valid PCM16 WAV, plausible rate/duration/frames, nonzero receive signal, RX frequencies/pulse pattern matching the far-end file, and local microphone challenges absent. Compare the file hash to upload metadata. `VOICE_DOWNLINK` selection, successful `startRecording`, or a nonsilent WAV alone is insufficient proof.

## Negative controls and robustness

Run these as separate, identified trials after the basic TX/RX paths work:

| Trial | Required observation |
| --- | --- |
| Far end silent, no TX | Downlink artifact does not invent either known marker |
| TX muted/off baseline versus muted TX on | Far-end marker follows digital TX activation |
| Local microphone challenge while digital RX records | Challenge absent from claimed downlink; signal identity retained |
| Far-end RX marker plus Android distinct TX marker | Inspect both directions for unintended mixed/cross-coupled markers |
| Unsupported/missing route or permission | Explicit failure; no speaker/MIC fallback |
| Stop races start / call disconnects | Resources released, mute restored, partial RX finalized or recovered |
| Process death and next launch | Mute lease recovery attempted, partial WAV repaired, interruption reported |
| Repeated upload | Same checksum/command gives same artifact identity; changed replay rejected |
| 8/16/48 kHz source/capture trials | Actual client format and any mono duplication recorded; no implied codec/rate proof |

Do not queue a complete command sequence in advance: priority stops can overtake starts. The operator/orchestrator waits for each observed prerequisite. The phone contains no local 100-call scenario.

## Staged host acceptance CLI

Run this alternative to the manual queue sequence **on the orchestrator host with access to the same SQLite database**. It preserves stage/command IDs in `run.json`; each stage is explicit, and no scenario moves onto Android. The controlled Sinch recorder/player remains external.

```bash
python3 tools/pixel5_phaseb_acceptance.py --db probe.db \
  --run-dir evidence/pixel5-run-001 --timeout 180 prepare \
  --device-id DEVICE_ID --number +12165551212 \
  --markers evidence/markers/manifest.json \
  --tx-url https://media.example.com/tx-reference.wav
```

`prepare` copies references and creates `physical-evidence-template.json`; it queues no calls. The HTTPS URL must serve the exact TX reference bytes. Arm the far-end recorder, then run:

```bash
python3 tools/pixel5_phaseb_acceptance.py --db probe.db \
  --run-dir evidence/pixel5-run-001 --timeout 180 tx --far-end-ready
```

The TX stage downloads the reference, waits for ACTIVE, uses `DIGITAL_TX_VALIDATION`, waits for terminal playback evidence and hangs up. Collect Pixel evidence while the test is active from a separate terminal. Save the actual far-end recording and its call identity.

```bash
python3 tools/pixel5_phaseb_acceptance.py --db probe.db \
  --run-dir evidence/pixel5-run-001 --timeout 180 rx-start
```

`rx-start` waits for DOWNLINK recording and returns the exact far-end playback handoff. **The call stays active.** Play the specified RX reference from the Sinch endpoint, wait until playback finishes, then run:

```bash
python3 tools/pixel5_phaseb_acceptance.py --db probe.db \
  --run-dir evidence/pixel5-run-001 --timeout 180 rx-stop
```

`rx-stop` finalizes, uploads and hangs up. Complete a copy of `physical-evidence-template.json` as `physical-evidence.json` using this run's actual call IDs, device/build evidence, runtime policy, bearer/isolation observations and files with SHA-256. File paths resolve relative to that evidence JSON. Do not replace unknown observations with true merely to satisfy a check.

```bash
python3 tools/pixel5_phaseb_acceptance.py --db probe.db \
  --run-dir evidence/pixel5-run-001 evaluate \
  --tx-recording evidence/far-end-tx.wav \
  --evidence evidence/pixel5-run-001/physical-evidence.json \
  --max-lag-seconds 10
```

An optional `--rx-recording /actual/received.wav` overrides the default uploaded server artifact; its hash must still match this run's upload. `--timeout` bounds each wait from 1–1800 seconds. `evaluate` exits 2 when checks are incomplete/failed; even `AUTOMATED_CHECKS_PASSED_REVIEW_REQUIRED` leaves physical TX/RX success unknown pending review. Missing recordings, copied reference bytes, wrong-call evidence and initialization alone cannot produce a physical pass. Use a new run directory for a new deliberate trial; do not redial an already ended stage by reusing its run.

## Evidence package and decision

On the USB-connected engineering host, capture the runtime bundle and optionally add files explicitly exported from the Sinch/orchestrator host:

```bash
bash tools/collect_pixel5_phaseb_evidence.sh --root \
  --output evidence/pixel5-final --logcat-lines 4000 \
  --call-result evidence/call-result.json \
  --wav /actual/received.wav --far-end-wav evidence/far-end-tx.wav \
  --reference-wav evidence/markers/tx-reference.wav
```

Omit optional file flags when those files are not yet available. Additional inputs are `--tx-json`, `--rx-json`, `--wav-metadata`, and `--audio-verification`. The bundle includes original vendor XML with device/local hashes, raw and parsed app diagnostics, call/TX/RX/artifact metadata, dumps, bounded logs and an evidence manifest. Unavailable fields remain explicit. The verifier's PASS covers permissions/enumeration/initialization only; fresh initialization must belong to the currently ACTIVE owned call.

Keep unchanged reference and recorded WAVs, generator manifest, utility JSON/CSV, command results/events, app diagnostics, handset dumps, far-end CDR, and the record of microphone/isolation challenges together. `--copy-wavs` copies PCM files without normalization, trimming or resampling, ready for a separate existing Sinch VQ/STT ingestion process. Supply the speech transcript/reference text separately when running WER; this tone tool neither computes MOS/WER nor replaces that pipeline.

Record separate decisions: privileged installation; framework TX routing; physical muted TX; framework RX source; physical downlink capture; native cellular/VoLTE bearer; cleanup/recovery. Mark each **NOT RUN / PASS / FAIL / INCONCLUSIVE**, with the exact device/build/carrier. Phase B physical acceptance requires both muted TX and isolated downlink evidence. A generated marker or a local software test is never a hardware pass.

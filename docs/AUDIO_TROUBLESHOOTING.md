# Audio investigation runbook

Keep the failed run's command/call IDs, UTC window, reference SHA-256, handset build and far-end recording. Diagnose the built-in route before considering vendor changes. A failed route check must not be bypassed by falling back to the speaker, microphone or Bluetooth.

The v0.2.1 primary target is Pixel 5 GD1YQ/redfin with stock Android 14 UP1A.231105.001.B2, Magisk 30.7 and Verizon. For initial non-rooted device preparation use [PIXEL5_REDFIN_SETUP.md](PIXEL5_REDFIN_SETUP.md); this investigation preserves the stock Google/Qualcomm carrier stack.

## Capture a reproducible bundle

Capture once idle, once during `ACTIVE` and once while audio starts/fails:

```bash
bash tools/pixel5_audio_capability_dump.sh --root --output evidence/capabilities
bash tools/verify_audio_privileges.sh --root --probe-downlink --output evidence/active-privileges
bash tools/collect_pixel5_phaseb_evidence.sh --root --output evidence/audio-failure --logcat-lines 4000
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --action GET_AUDIO_DIAGNOSTICS --parameters '{}'
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID --limit 100
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID --limit 100
python3 -m orchestrator.server --db probe.db logs --device-id DEVICE_ID --limit 100
```

The root flag permits additional read-only vendor/kernel inspection through the already authorized debugging shell. It does not change policy or routing. These dumps may contain phone numbers, device identifiers and system logs; retain them as engineering evidence with the same access controls as call recordings.

Run `--probe-downlink` only for the explicit initialization check during an ACTIVE probe-owned call. Unknown/stale evidence is `FAIL_NOT_TESTED`, not PASS. The full collector preserves exact vendor XML under `vendor-files/vendor/etc/`, hashes in `vendor-file-index.json`, raw/parsed app bridge responses, and call/TX/RX/artifact metadata. `vendor-hal-log-coverage.json` distinguishes observed Qualcomm tags from `UNAVAILABLE_OR_NOT_EMITTED_IN_BOUNDED_WINDOW`; release builds may suppress those messages. Missing logs are not proof that a route is unsupported.

If the application bridge is unavailable, inspect `app-diagnostics.unavailable.json` and the raw response when present. Its URI is `content://com.sinch.vqprobe.diagnostics`, with methods `snapshot` and `probe_downlink`. It requires `android.permission.DUMP` plus Android shell/root UID; do not make diagnostics world-readable or bypass these checks. Initialization success is distinct from verified non-silent DOWNLINK recording and physical marker reception.

Individual commands, if needed:

```bash
adb shell dumpsys audio
adb shell dumpsys media.audio_policy
adb shell dumpsys media.audio_flinger
adb shell dumpsys telecom
adb shell dumpsys telephony.registry
adb shell dumpsys package com.sinch.vqprobe
adb logcat -d -v threadtime -s AudioFlinger AudioPolicyManager Telecom Telephony SinchVQ
adb shell su -c 'dmesg'
```

Do not assume every vendor uses the same log tags or grants shell access to every dump. The supplied bundle records unavailable commands rather than declaring support from missing output.

## Common observations

| Observation/error | Check next |
| --- | --- |
| `AUDIO_PRIVILEGED_PERMISSION_REQUIRED` | Post-reboot privileged flag, exact missing permission, active APK signer/path and partition allowlist |
| `CAPTURE_AUDIO_OUTPUT_PERMISSION_REQUIRED` or `RECORD_AUDIO_PERMISSION_REQUIRED` | Privileged permission versus runtime grant; they require different fixes |
| Capture foreground-service rejection | App-op/runtime permission, actual granted `CAPTURE_AUDIO_OUTPUT`, Android/OEM foreground-start behavior; retry once while UI is visible and record the difference |
| `TELEPHONY_TX_UNAVAILABLE` | Repeat enumeration during owned `ACTIVE` call; inspect active policy and modem voice state |
| `AMBIGUOUS_TELEPHONY_TX_DEVICE` | Enumerated sink IDs and selected subscription; do not guess the correct endpoint |
| `AUDIO_MODE_NOT_IN_CALL` | Native call and Telecom state; app does not force global audio mode |
| `TELEPHONY_AUDIO_ROUTE_REJECTED` / `TELEPHONY_AUDIO_ROUTE_FAILED` | Preferred versus actual device, active AudioTrack session, policy profile, `INCALL_MUSIC` flags and route patches |
| `AUDIO_TRACK_FORMAT_UNSUPPORTED` / unsupported channels | Installed telephony profile; try a separately generated supported-rate source; inspect reported mono/dual-mono client format |
| `UNSUPPORTED_SAMPLE_RATE_CONVERSION` | Use matching source rate or prepare an explicitly converted reference outside Android; retain both hashes |
| `MICROPHONE_MUTE_NOT_CONFIRMED` / `MICROPHONE_ISOLATION_LOST` | Actual mute state and ownership of competing call/UI controls; preserve isolation failure |
| `MICROPHONE_RESTORE_FAILED` / `MICROPHONE_RESTORE_REQUIRED` | Stop TX, reopen app to trigger saved-state recovery, inspect diagnostics and restore the prior state manually if recovery still fails |
| Framework TX route accepted, far end silent | Source frames/underruns, carrier leg, mute effect, HAL session selection and actual mixer path |
| `CAPTURE_SOURCE_MISMATCH` / `CAPTURE_MICROPHONE_ROUTE_REJECTED` | Recording source/configuration versus routed input; no microphone substitution |
| `CAPTURE_CLIENT_SILENCED` | App-op, audio privacy controls, concurrent recording and privileged state |
| `CAPTURE_CONFIGURATION_UNAVAILABLE` / `CAPTURE_NO_PCM_DATA` | Recording session configuration, HAL input use case and platform logs |
| `CAPTURE_EMPTY` / `CAPTURE_ALL_ZERO_PCM` | Actual far-end playback, source support, mute/volume effects and empty versus zero-filled frames |
| Nonzero RX contains local speech or TX marker | Directional mixing/crosstalk or incorrect HAL source; label downlink proof inconclusive |
| Artifact upload expiry | Queue a new `UPLOAD_FILE` command with a longer TTL; keep the same finalized file ID, not a new recording |
| `SHA256_MISMATCH` / `INVALID_PCM_WAV` | Local finalized metadata and actual file; preserve the original partial/error evidence |

The exact failure event is more informative than a generic UI ERROR. A success acknowledgment to `STOP_RECORDING` may only say STOPPING; wait for the terminal artifact event before upload.

## Ordered vendor investigation

1. **Privileged permission state:** verify actual APK grants, privileged flag, runtime RECORD_AUDIO and app-op; an allowlist entry or UID 0 alone is insufficient.
2. **Active cellular call state:** match the owned call ID and Telecom ACTIVE event; retain native carrier/bearer evidence.
3. **TYPE_TELEPHONY enumeration:** repeat during this active call and inspect the selected sink ID/profile.
4. **setPreferredDevice result:** record whether the framework accepted the request; this alone proves no route.
5. **getRoutedDevice result:** inspect the running track and every routing callback. Non-telephony routing fails `DIGITAL_TX_VALIDATION`.
6. **AudioPolicy logs:** correlate policy selection, session IDs, route changes and failures with the call's UTC window.
7. **Runtime vendor audio policy XML:** use the exact files copied from this phone, follow includes and identify the loaded variant.
8. **incall_music_uplink route presence:** inspect its connection to `Telephony Tx`, PCM16 rates/channel masks, and `Telephony Rx`/`voice_rx` mappings.
9. **AudioFlinger output thread:** inspect actual session/output flags, frame progress, underruns and client format.
10. **Qualcomm/vendor HAL logs:** inspect `audio_hw_primary`, `audio_hw_voice`, `audio_hw_extn`, `audio_hw_platform`, `voice_extn` and `ACDB-LOADER`; document suppressed/unavailable tags.
11. **Mixer path/state:** compare vendor mixer XML and read-only current controls. Do not apply another phone's `tinymix` writes.
12. **SELinux denial:** retain source domain, target type/class, permission and triggering operation for any `avc: denied`; do not globally disable SELinux.
13. **Minimal policy/audio-policy patch:** only after the evidence identifies a specific defect, prepare an isolated redfin/build-specific change with rollback and before/after evidence. No speculative policy or mixer patch ships here.

Only after all thirteen steps should a custom HAL change be considered. Keep any justified platform change in the [redfin device-support layer](../device-support/redfin/README.md), preserve OEM IMS/modem/carrier configuration, and leave the generic agent independent of that patch.

Where mute also suppresses injected PCM, trace the vendor mute implementation. Do not silently unmute and call the muted-microphone test passed. Where RX returns zeros, distinguish legitimate remote silence from a blocked/unsupported source using the distinct known far-end marker.

## Analysis utility interpretation

`audio_verify.py` accepts PCM16 mono/stereo WAV, checks complete frames, and calculates each channel separately so opposite-polarity stereo cannot cancel into artificial silence. Silence is the frame-weighted share of 20 ms windows below an RMS threshold (default −50 dBFS), not the fraction of exact zero samples. RMS/peak use full-scale normalization; a silent dBFS value is null.

Goertzel analysis measures the requested frequencies. A reported tone uses an energy-ratio threshold of 0.20, the RMS threshold, and at least 0.10 seconds of qualifying windows. These are engineering heuristics; speech/noise and codec distortion can cause misses or spurious detections. Check both expected and wrong-direction tones and retain the waveform.

The comparison searches channel-1 RMS envelopes for the best Pearson correlation, retaining at least 60% overlap of the shorter signal. Positive lag means the received marker starts later. Constant envelopes/silence are explicitly unavailable. It does not correct clock drift, normalize a carrier codec, prove raw-waveform equality, measure one-way network delay or certify digital isolation. Repeated patterns can have ambiguous lag.

Use the JSON, per-channel CSV envelope and unchanged WAV copies to inspect evidence and hand speech files to the existing Sinch VQ/STT pipeline. The utility never sets a hardware or digital-path PASS and never computes MOS/WER.

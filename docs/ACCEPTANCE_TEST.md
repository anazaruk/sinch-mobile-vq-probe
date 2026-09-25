# Physical-device acceptance runbook

**Status: not executed on a real handset.** Source/build/protocol checks do not establish native carrier call control, VoLTE bearer use, radio availability, or unattended recovery. Run this on a dedicated Sinch-owned phone before declaring milestone 1 complete.

## Record the test environment

| Item | Value to record |
| --- | --- |
| Run ID / UTC start time | |
| Device name and assigned device ID | |
| Handset model / modem / Android build | |
| APK version / source revision | |
| Carrier and active SIM slot / subscription ID | Verizon for first acceptance |
| Test destination / answering application | Controlled answering endpoint |
| HTTPS origin / control connectivity | Wi-Fi or mobile data |
| Default dialer / phone permissions | |
| Precise/background location and Location setting | |
| Battery/OEM background settings / charging state | |
| Wi-Fi Calling / cross-SIM or backup calling | Disabled for cellular-bearer test |
| Carrier/OEM bearer verification evidence | Unknown if unavailable |

Keep endpoint logs/CDRs alongside phone and orchestrator records. Use UTC throughout. Redact credentials from any exported troubleshooting bundle.

## Preconditions

1. Complete [README setup](../README.md), including successful enrollment and default-dialer consent.
2. Verify an ordinary call using the carrier voice SIM to the controlled destination. This isolates account/provisioning problems from the new application.
3. Use one enabled SIM for initial acceptance. Record the native phone account selected by the agent.
4. Ensure the orchestrator HTTPS certificate is trusted by Android and the device can reach its hostname/port.
5. Start the agent and confirm the persistent notification and fresh server poll. Start testing with the screen unlocked, then repeat with the screen locked.

The server CLI is the PoC's operator display. `list-devices` must show a recent poll and online state; a GUI is not required.

## Required first call

From the repository root on the orchestrator host, substitute the actual device ID and test number:

```bash
python3 -m orchestrator.server --db probe.db list-devices
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id vz-acceptance-001 \
  --action CALL --parameters '{"number":"+12165551212"}'
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
```

Wait for the answering endpoint to answer and for an `ACTIVE` event. Then:

```bash
python3 -m orchestrator.server --db probe.db queue \
  --device-id DEVICE_ID --command-id vz-hangup-001 \
  --action HANGUP --parameters '{}'
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID
python3 -m orchestrator.server --db probe.db logs --device-id DEVICE_ID
```

Use a new command ID for a deliberately new call. Reusing the original ID tests deduplication, not a new call attempt. Export evidence after the agent has successfully uploaded its pending outbox.

| Check | Pass condition | Observed evidence |
| --- | --- | --- |
| ONLINE | Fresh poll and correct device/SIM state visible | |
| Native origination | Selected account is SIM telephony; destination receives the call | |
| Submission | `CALL` terminal acknowledgment appears promptly; polling continues | |
| DIALING | Phone emits `DIALING` before answer on this test route | |
| ACTIVE | `ACTIVE` corresponds to answered call, not merely `OFFHOOK` | |
| HANGUP | Remote command disconnects the owned test call | |
| Final result | One logical `CALL_RESULT` associated with original command and call ID | |
| Timing | Dial, dialing, connected and disconnect UTC observations; nonnegative monotonic durations when available | |
| Measurements | Carrier/MCC/MNC, voice/data RAT, signal and before/during/after samples, or explicit unavailable reasons | |
| Destination evidence | Endpoint CDR/call log matches destination, approximate times and single call | |
| Credentials | No access/refresh/enrollment secret in app logs or returned diagnostics | |

For a route that answers too quickly to expose `DIALING`, record the callback limitation and repeat with a controlled ringing interval. Do not synthesize a dialing timestamp.

Declare **native cellular control accepted** only after all required control checks pass. Declare **VoLTE verified** only with bearer evidence beyond the LTE data indicator; keep those conclusions separate. Missing OEM radio fields should be documented and do not alone fail a call.

## Reliability and command checks

Run these after the single-call path works. Record measured delay, expected behavior, and handset-specific exceptions rather than assuming a service restart deadline.

| Test | Expected behavior |
| --- | --- |
| Redeliver identical `CALL` command ID | Stored result/event history reused; exactly one destination call observed |
| Queue second `CALL` while a call is reserved/active | Rejected as busy; no second native call |
| Repeat owned-call `HANGUP` | No additional call affected; already-ended case handled safely |
| `HANGUP` for a different call ID | No unrelated call disconnected |
| Deny location | Available call controls work; radio fields show unavailable reasons |
| Remove default-dialer role or phone permission | Clear command failure; no uncontrolled fallback origination |
| No service / busy / no answer / remote release | Appropriate observed disconnect/outcome; no invented answer time |
| Internet outage before/during call | No duplicate origination; reconnect flushes durable results/events |
| Orchestrator outage | Backoff/reconnect; phone continues to expose local status and hangup |
| Access-token expiry | Renewal succeeds; polling resumes without re-enrollment |
| Revoked device | Server rejects subsequent authenticated use |
| Process death during submission/active call | Surviving call reconciled when possible, otherwise uncertainty reported; no automatic redial |
| Reboot and first unlock | Previously enabled agent reconnects without re-enrollment; record any required interaction |
| Screen locked / idle / battery saving | Measure poll latency and command execution; record OEM/Doze restrictions |
| User force-stop | Remains stopped until user relaunch; this is an expected platform boundary |
| Restart agent command | Agent loop restarts, credentials and journal preserved; no phone reboot |
| Same verified file downloaded twice | Matching cache reused |
| Wrong checksum / HTTP / disallowed host | Download fails without publishing a corrupt cache entry |
| Delete twice | File remains absent with safe repeat behavior |

Use the current call ID for the wrong-call and owned-call hangup cases. Do not use someone else's live call as the test subject; arrange controlled local/incoming calls on the dedicated handset.

For file tests, serve a real WAV over trusted HTTPS from a hostname passed to `create-enrollment --download-host`. Calculate its SHA-256 on the source file and provide that exact digest. The reference API server is not a media server. Verify cache behavior; no audio should be injected into the cellular call.

## Acceptance summary to complete

- Native cellular control: **NOT RUN / PASS / FAIL**
- VoLTE bearer verification: **UNKNOWN / VERIFIED / OTHER BEARER**
- Radio fields available and unavailable: **record list**
- Screen-locked calling: **NOT RUN / PASS / FAIL**
- Reboot recovery after first unlock: **NOT RUN / observed delay / manual action required**
- Idle/Doze/OEM recovery: **NOT RUN / observed behavior**
- Duplicate-call prevention during redelivery and recovery: **NOT RUN / PASS / FAIL**
- Evidence files and remaining issues: **record paths or references**

Repeat the same recorded procedure for AT&T and T-Mobile after the Verizon path passes. A pass on one handset, carrier and Android build is not qualification of all combinations.

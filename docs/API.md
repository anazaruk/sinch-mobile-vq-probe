# Probe REST API v1

This document covers the original Phase A protocol. Version 0.2 keeps these endpoints and adds experimental privileged audio commands and artifact transfer in [PHASE_B_AUDIO.md](PHASE_B_AUDIO.md).

Base URL is an HTTPS origin with optional port and no path, for example `https://vq.example.com:8443`. All endpoints use `POST` with `Content-Type: application/json`. Only enrollment and token renewal omit the access-token header. Other requests send `Authorization: Bearer <access_token>`.

The phone initiates every request. Responses contain zero or one command. The reference backend is intentionally a command queue and result store; schedules and multi-step scenarios belong in the external orchestrator.

## Endpoints

| Endpoint | Request | Successful response |
| --- | --- | --- |
| `/api/v1/probe/enroll` | `enrollment_code`, `device_name`, `app_version` | Device ID, credentials and config |
| `/api/v1/probe/token` | `device_id`, `refresh_token` | Renewed access credentials |
| `/api/v1/probe/poll` | Device ID, version, state, telemetry, call state and timestamp | `NONE` or one command |
| `/api/v1/probe/results` | `device_id`, `results` array | `accepted` command-ID array |
| `/api/v1/probe/events` | `device_id`, `events` array | `accepted` event-ID array |
| `/api/v1/probe/logs` | `device_id`, `logs` array | `accepted` log-ID array |

The authenticated identity determines ownership. A device cannot upload a result for another device's command by changing a JSON `device_id`. Unknown command references are rejected. Results, events and logs have stable IDs; retry exact payloads after lost acknowledgments. Reusing an acknowledged ID with altered content is a conflict.

## Enrollment and credentials

Create an enrollment code through the server CLI. Codes are single use and bound to the provisioned device name and configuration.

```json
{
  "enrollment_code": "ONE_USE_CODE",
  "device_name": "NJ-VZ-001",
  "app_version": "0.1.0"
}
```

Illustrative enrollment response; credentials below are placeholders:

```json
{
  "device_id": "probe-generated-id",
  "access_token": "REDACTED",
  "access_expires_at": "2026-09-25T15:15:00Z",
  "refresh_token": "REDACTED",
  "config": {
    "carrier": "Verizon",
    "site_id": "NJ",
    "poll_interval_seconds": 15,
    "test_number": "+12165551212",
    "subscription_id": null,
    "download_allowed_hosts": ["media.example.com"]
  }
}
```

The PoC uses 15-minute access tokens and a stable refresh credential with a sliding 30-day expiry. This deliberate simplification makes a lost refresh response retryable without keeping server-side plaintext refresh credentials. Successful refresh returns a new access token and the same refresh credential. Earlier access tokens remain usable until their own expiry; device revocation blocks further authenticated use. Protect phone credentials and server database access. A production credential-rotation scheme would need an explicit replay/grace protocol.

Enrollment is different: if its one-use response is lost before the phone stores credentials, issue a new enrollment code and retire the orphaned record; do not silently reuse a consumed code.

## Polling and commands

The poll payload is the probe's status snapshot. It includes current state and call information plus a `telemetry` object. The object preserves available SIM/carrier, service, voice/data RAT, signal and cell information; missing metrics are null or accompanied by an unavailable reason. Do not treat assigned carrier metadata as an independently observed network identity.

No work:

```json
{"action":"NONE"}
```

An outgoing command:

```json
{
  "command_id": "cmd-10045",
  "action": "CALL",
  "parameters": {"number":"+12165551212"},
  "issued_at": "2026-09-25T15:00:00Z",
  "expires_at": "2026-09-25T15:05:00Z"
}
```

| Action | Parameters | Completion meaning |
| --- | --- | --- |
| `CALL` | `number`; optional `subscription_id` | Native call submission accepted; final outcome arrives as an event |
| `HANGUP` | Optional `call_id` | Disconnect requested/queued for the owned probe call, or no owned call remains |
| `GET_STATUS` | `{}` | Current device/agent state returned |
| `GET_RADIO_STATS` | `{}` | Best-effort radio snapshot returned |
| `WAIT` | Integer `duration_ms`, 0–3,600,000 | Delay elapsed without blocking the polling thread |
| `DOWNLOAD_FILE` | `url`, `file_id`, 64-character `sha256` | Verified local file stored or existing matching cache reused |
| `DELETE_FILE` | `file_id`; optional `kind` `reference` (default) or `recording` | Selected cached reference or finalized recording absent; active artifacts protected |
| `REBOOT_APP` | `{}` | Agent-loop restart requested; not an Android reboot |
| Audio commands | See [Phase B command table](PHASE_B_AUDIO.md) | Experimental privileged audio implementation; `UPLOAD_AUDIO` aliases preferred `UPLOAD_FILE` |
| `SET_NETWORK_MODE` | Reserved | `FAILED` / `NOT_IMPLEMENTED` |

The backend prioritizes pending `HANGUP`, then status/radio inspection, then other commands in queue order. It redelivers unfinished commands within that ordering. `CALL` completes its command acknowledgment at submission; it never blocks the queue until call disconnect. `WAIT` remains unfinished until its delay completes and delays later ordinary commands, while `HANGUP` and inspection can bypass it. Scenarios should normally schedule their next action server-side instead of inserting long waits.

The server expires unfinished commands when polling after their deadline. The phone also rejects expired work. A delayed result for a delivered command can still be accepted if execution began before its deadline; a failed expired-command result can be uploaded without executing a side effect. A final call event may arrive after the submission command TTL. Command expiration is a delivery/execution-start boundary, not an automatic call-duration limit. Schedule termination separately and inspect final events.

## Command results versus call results

The device stores command receipt, execution and completion timestamps in UTC. Terminal status is `SUCCESS` or `FAILED`; failed results include an error code. A successful submission result looks like this:

```json
{
  "device_id": "probe-generated-id",
  "results": [{
    "command_id": "cmd-10045",
    "action": "CALL",
    "status": "SUCCESS",
    "received_at": "2026-09-25T15:00:01Z",
    "execution_at": "2026-09-25T15:00:01Z",
    "completed_at": "2026-09-25T15:00:01Z",
    "data": {"call_id":"call-generated-id","accepted":true}
  }]
}
```

The server responds:

```json
{"accepted":["cmd-10045"]}
```

Do not infer answer from that result. The event stream separately includes `DIAL_REQUESTED`, `DIALING`, `ACTIVE`, `DISCONNECTED`, and final `CALL_RESULT`, using the original `command_id` and a stable `call_id`. Callback order can vary; a call that fails before answer has no `ACTIVE` event.

Event envelope:

```json
{
  "device_id": "probe-generated-id",
  "events": [{
    "event_id": "event-generated-id",
    "command_id": "cmd-10045",
    "call_id": "call-generated-id",
    "event": "ACTIVE",
    "timestamp": "2026-09-25T15:00:04Z",
    "data": {}
  }]
}
```

`CALL_RESULT.data` contains the destination, selected subscription/account, observed timestamps, outcome/disconnect information and radio samples. Setup duration is measured from dial request to first observed `ACTIVE`; connected duration is measured from that observation to disconnect. Metrics that cannot be established, including those spanning uncertain process recovery, remain absent/null with an uncertainty reason. Neither duration measures first audio.

Abbreviated final-event example, illustrating the schema rather than a recorded test result:

```json
{
  "device_id": "probe-generated-id",
  "events": [{
    "event_id": "final-event-generated-id",
    "command_id": "cmd-10045",
    "call_id": "call-generated-id",
    "event": "CALL_RESULT",
    "timestamp": "2026-09-25T15:00:14Z",
    "data": {
      "call_id": "call-generated-id",
      "command_id": "cmd-10045",
      "destination": "+12165551212",
      "subscription_id": 1,
      "state": "ENDED",
      "outcome": "COMPLETED",
      "error": null,
      "dial_request_time": "2026-09-25T15:00:01Z",
      "dialing_time": "2026-09-25T15:00:02Z",
      "connected_time": "2026-09-25T15:00:04Z",
      "disconnect_time": "2026-09-25T15:00:14Z",
      "call_setup_ms": 3000,
      "connected_duration_ms": 10000,
      "disconnect_observed": true,
      "timing_source": "ELAPSED_REALTIME_CALLBACK_OBSERVATIONS",
      "timing_continuity": true,
      "volte_confirmed": null,
      "radio_samples": [{
        "phase": "AFTER",
        "timestamp": "2026-09-25T15:00:14Z",
        "elapsed_ms": 314000,
        "telemetry": {
          "carrier": "Verizon",
          "mcc": "311",
          "mnc": "480",
          "voice_network_type": "LTE",
          "data_network_type": "LTE",
          "cells": null,
          "unavailable": {"cells":"ACCESS_FINE_LOCATION_NOT_GRANTED"}
        }
      }]
    }
  }]
}
```

Actual data additionally includes the selected phone-account fingerprint/component, observed elapsed timestamps, `disconnect_cause`, and `BEFORE`/`DURING`/`AFTER` snapshots when available. The before-call carrier, MCC/MNC, voice/data RAT and signal are also copied to top-level result fields. Outcomes are `COMPLETED`, `FAILED`, `NOT_CONNECTED`, or `UNCERTAIN`. MCC/MNC above are illustrative and must come from the actual SIM/network measurements. Cell entries are explicitly scoped to **all device radios**, not silently attributed to the selected subscription.

`wifi_calling_observed`, `cross_sim_calling_observed` where exposed, and `hd_audio_indicated` preserve Telecom hints without treating them as complete bearer proof. `cellular_access_validation` distinguishes `WIFI_CALLING_OBSERVED`, `CROSS_SIM_CALLING_OBSERVED`, and `BEARER_NOT_CONFIRMED`. Radio history is bounded to 60 samples and 240 KiB, with an individual 8 KiB sample cap and explicit truncation/dropped-sample information.

Common call errors include `DEFAULT_DIALER_REQUIRED`, `READ_PHONE_STATE_REQUIRED`, `CALL_PERMISSION_DENIED`, `NO_ACTIVE_SIM`, `NO_NATIVE_SIM_PHONE_ACCOUNT`, `SUBSCRIPTION_UNAVAILABLE`, `AMBIGUOUS_SIM_SELECTION`, `SIM_NOT_READY`, `CALL_IN_PROGRESS`, `INVALID_E164_NUMBER`, and `CALL_SUBMISSION_UNCERTAIN`. Android 10 multi-SIM mapping can report `SUBSCRIPTION_MAPPING_UNAVAILABLE`. Unknown IMS status is not a call error. A targeted hangup can report `CALL_ID_MISMATCH` or `CALL_NOT_FOUND`. If a reserved call has not yet bound to `InCallService`, hangup is queued with `hangup_queued` and `awaiting_call_ownership`; it applies only after ownership is established.

## Delivery and recovery

1. Persist a new command before any side effect.
2. Replayed completed command IDs return the stored result instead of executing again.
3. Persist call reservation before native submission. If recovery cannot establish whether the call was submitted, report uncertainty; never automatically redial that command.
4. Persist results/events in an outbox, upload with stable IDs, and remove only IDs acknowledged by the server.
5. Continue polling and flushing during active calls. Transient connectivity loss must not become a new dialing instruction.

`HANGUP` with a specific `call_id` helps prevent an operator from ending a different later call. The server-side command ID deduplicates that hangup command; it is separate from the call ID.

The phone retains the command ledger for deduplication. After event acknowledgment, it discards the event payload while retaining a small ID tombstone. Local diagnostic logs are capped at 5,000 rows; oldest entries may be dropped during a prolonged outage, so this local history is not an unlimited audit archive.

## Files

Downloads require HTTPS on port 443, an enrolled allowed hostname, no URL fragment, a logical `file_id` matching `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`, and an expected SHA-256. Android limits each downloaded file to 32 MiB and the cache to 256 MiB. Download into private storage, verify before publishing the cache entry, and reuse an existing verified file. A checksum mismatch or disallowed host fails the command. The media host does not receive the probe API bearer token.

```json
{
  "command_id": "cmd-20001",
  "action": "DOWNLOAD_FILE",
  "parameters": {
    "url": "https://media.example.com/reference01.wav",
    "file_id": "reference01",
    "sha256": "REPLACE_WITH_ACTUAL_64_HEX_CHARACTER_DIGEST"
  }
}
```

The digest string above is illustrative and intentionally invalid until replaced. Downloading alone does not play audio; the separate experimental `PLAY_AUDIO` command is described in Phase B.

## Logs and administration

A structured log contains `log_id`, UTC `timestamp`, `device_id`, optional `command_id`/`call_id`, `component`, `severity`, `event`, and `data`. Credentials must not appear in logs. Logs are uploaded outbound and retrieved locally on the orchestrator host:

```bash
python3 -m orchestrator.server --db probe.db logs --device-id DEVICE_ID --limit 100
python3 -m orchestrator.server --db probe.db results --device-id DEVICE_ID --limit 100
python3 -m orchestrator.server --db probe.db events --device-id DEVICE_ID --limit 100
```

Use `python3 -m orchestrator.server --help` and the relevant subcommand's `--help` for CLI arguments. No administrative HTTP endpoint is exposed by this reference server.

`results` lists commands and their queue/terminal status with a nested `result` when present. `events` and `logs` return stored records newest first. `list-devices` considers a device ONLINE when its last poll is within `max(60 seconds, 3 × poll interval)` and it is not revoked. This freshness threshold does not guarantee current voice service.

Uploads are atomic batches of at most 200 records. The reference HTTP server limits request bodies to 1 MiB and socket timeouts to 15 seconds; clients should keep batches well below the size limit. TLS 1.2 or later is required.

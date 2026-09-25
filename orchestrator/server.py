#!/usr/bin/env python3
"""Small HTTPS + SQLite reference controller for dedicated mobile probes.

Run `python3 -m orchestrator.server --help`. Admin actions use the local database,
not an exposed admin API. This is a lab controller, not a production fleet service.
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import sqlite3
import ssl
import struct
import sys
import tempfile
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

UTC = dt.timezone.utc
MAX_BODY = 1024 * 1024
MAX_ARTIFACT = 64 * 1024 * 1024
AUDIO_RATES = {8000, 16000, 48000}
MAX_BATCH = 200
ACCESS_SECONDS = 15 * 60
REFRESH_SECONDS = 30 * 24 * 60 * 60
ACTIONS = {
    "DOWNLOAD_FILE", "DELETE_FILE", "CALL", "HANGUP", "GET_RADIO_STATS", "WAIT",
    "REBOOT_APP", "GET_STATUS", "PLAY_AUDIO", "START_RECORDING", "STOP_RECORDING",
    "SET_NETWORK_MODE", "UPLOAD_AUDIO", "STOP_AUDIO", "UPLOAD_FILE",
    "GET_AUDIO_STATUS", "GET_AUDIO_DIAGNOSTICS",
}
CALL_EVENTS = {"DIAL_REQUESTED", "DIALING", "ACTIVE", "DISCONNECTED", "CALL_RESULT"}
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
NUMBER_RE = re.compile(r"^\+[1-9][0-9]{6,14}$")
SENSITIVE_KEYS = {"authorization", "access_token", "refresh_token", "enrollment_code",
                  "authentication_token", "password", "api_key"}


def utc(epoch=None):
    return dt.datetime.fromtimestamp(time.time() if epoch is None else epoch, UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def epoch(value, label="timestamp"):
    if not isinstance(value, str):
        raise ApiError(400, "INVALID_" + label.upper())
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.utcoffset() != dt.timedelta(0):
            raise ValueError("UTC required")
        return parsed.timestamp()
    except (ValueError, TypeError, OverflowError):
        raise ApiError(400, "INVALID_" + label.upper()) from None


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def token():
    return secrets.token_urlsafe(32)


def valid_id(value, label="id"):
    if not isinstance(value, str) or not ID_RE.fullmatch(value):
        raise ApiError(400, "INVALID_" + label.upper())
    return value


def text_field(value, label, maximum=256):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ApiError(400, "INVALID_" + label.upper())
    return value


def no_credentials(value):
    """Refuse common credential fields in persisted telemetry, events, and logs."""
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() in SENSITIVE_KEYS:
                raise ApiError(400, "CREDENTIAL_FIELD_FORBIDDEN")
            no_credentials(item)
    elif isinstance(value, list):
        for item in value:
            no_credentials(item)


class ApiError(Exception):
    def __init__(self, status, code):
        super().__init__(code)
        self.status, self.code = status, code


def inspect_wav(path, size):
    """Validate bounded RIFF PCM chunks; this is format proof, not audio quality proof."""
    def invalid():
        raise ApiError(415, "INVALID_PCM_WAV")

    if size < 44 or size > MAX_ARTIFACT:
        invalid()
    with open(path, "rb") as stream:
        header = stream.read(12)
        if len(header) != 12:
            invalid()
        riff, declared, wave = struct.unpack("<4sI4s", header)
        if riff != b"RIFF" or wave != b"WAVE" or declared + 8 != size:
            invalid()
        fmt, audio_bytes, chunks = None, None, 0
        while stream.tell() < size:
            chunks += 1
            chunk = stream.read(8)
            if len(chunk) != 8 or chunks > 4096:
                invalid()
            tag, length = struct.unpack("<4sI", chunk)
            start = stream.tell()
            end = start + length + (length & 1)
            if end > size:
                invalid()
            if tag == b"fmt ":
                if fmt is not None or length < 16:
                    invalid()
                fmt = struct.unpack("<HHIIHH", stream.read(16))
                encoding, channels, rate, byte_rate, alignment, bits = fmt
                if (encoding != 1 or channels != 1 or rate not in AUDIO_RATES
                        or bits != 16 or alignment != 2 or byte_rate != rate * 2):
                    invalid()
            elif tag == b"data":
                if audio_bytes is not None or fmt is None or length % 2:
                    invalid()
                audio_bytes = length
            stream.seek(end)
        if fmt is None or audio_bytes is None:
            invalid()
        return {"sample_rate": fmt[2], "channels": fmt[1], "bits_per_sample": fmt[5],
                "data_bytes": audio_bytes, "frames": audio_bytes // 2,
                "duration_seconds": audio_bytes / (2 * fmt[2]),
                "validation": "PCM_WAV_FORMAT_ONLY"}


class Controller:
    def __init__(self, path, clock=time.time, artifact_dir=None):
        self.path, self.clock = os.fspath(path), clock
        self.artifact_dir = os.path.abspath(artifact_dir or os.path.splitext(self.path)[0] + "-artifacts")
        # Create new DB owner-only before SQLite can write enrollment/credential data.
        fd = os.open(self.path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(fd)
        os.chmod(self.path, 0o600)
        with sqlite3.connect(self.path, timeout=15) as db:
            db.execute("PRAGMA journal_mode=WAL")
        with self.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS enrollments (
                    code_hash TEXT PRIMARY KEY, device_name TEXT NOT NULL,
                    config TEXT NOT NULL, expires REAL NOT NULL, used REAL);
                CREATE TABLE IF NOT EXISTS devices (
                    device_id TEXT PRIMARY KEY, device_name TEXT NOT NULL,
                    config TEXT NOT NULL, enrolled REAL NOT NULL, last_seen REAL,
                    last_status TEXT, refresh_hash TEXT NOT NULL UNIQUE,
                    refresh_expires REAL NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS access_tokens (
                    token_hash TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(device_id),
                    expires REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS commands (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT UNIQUE NOT NULL,
                    device_id TEXT NOT NULL REFERENCES devices(device_id), action TEXT NOT NULL,
                    parameters TEXT NOT NULL, issued REAL NOT NULL, expires REAL NOT NULL,
                    state TEXT NOT NULL DEFAULT 'QUEUED', delivered REAL, result TEXT);
                CREATE INDEX IF NOT EXISTS command_queue ON commands(device_id,state,sequence);
                CREATE TABLE IF NOT EXISTS events (
                    event_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(device_id),
                    command_id TEXT, received REAL NOT NULL, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS logs (
                    log_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(device_id),
                    received REAL NOT NULL, payload TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS events_device ON events(device_id,received);
                CREATE INDEX IF NOT EXISTS logs_device ON logs(device_id,received);
                CREATE TABLE IF NOT EXISTS artifacts (
                    artifact_id TEXT PRIMARY KEY,
                    upload_command_id TEXT NOT NULL UNIQUE REFERENCES commands(command_id),
                    device_id TEXT NOT NULL REFERENCES devices(device_id), file_id TEXT NOT NULL,
                    sha256 TEXT NOT NULL, size_bytes INTEGER NOT NULL, path TEXT NOT NULL,
                    created REAL NOT NULL, wav_info TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS artifacts_device ON artifacts(device_id,created);
                CREATE TABLE IF NOT EXISTS call_bindings (
                    call_id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(device_id),
                    command_id TEXT UNIQUE NOT NULL REFERENCES commands(command_id));
            """)

    @contextlib.contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            # Writes serialize before reads so enrollment consumption/command replay are atomic.
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def create_enrollment(self, device_name, config, ttl=3600):
        text_field(device_name, "device_name", 128)
        text_field(config.get("carrier"), "carrier", 128)
        if not 60 <= ttl <= 7 * 86400:
            raise ApiError(400, "INVALID_ENROLLMENT_TTL")
        if not 5 <= config.get("poll_interval_seconds", 15) <= 3600:
            raise ApiError(400, "INVALID_POLL_INTERVAL")
        if config.get("test_number") and not NUMBER_RE.fullmatch(config["test_number"]):
            raise ApiError(400, "INVALID_TEST_NUMBER")
        config = {"carrier": config["carrier"], "site_id": config.get("site_id"),
                  "poll_interval_seconds": config.get("poll_interval_seconds", 15),
                  "test_number": config.get("test_number"),
                  "subscription_id": config.get("subscription_id"),
                  "download_allowed_hosts": config.get("download_allowed_hosts", [])}
        code, expires = token(), self.clock() + ttl
        with self.connection() as db:
            db.execute("INSERT INTO enrollments VALUES(?,?,?,?,NULL)",
                       (digest(code), device_name, canonical(config), expires))
        return {"enrollment_code": code, "device_name": device_name,
                "expires_at": utc(expires), "config": config}

    def _access(self, db, device_id, now):
        raw = token()
        db.execute("DELETE FROM access_tokens WHERE expires<=?", (now,))
        db.execute("INSERT INTO access_tokens VALUES(?,?,?)", (digest(raw), device_id, now + ACCESS_SECONDS))
        return raw, utc(now + ACCESS_SECONDS)

    def _credentials(self, db, row, refresh, now):
        access, expires = self._access(db, row["device_id"], now)
        return {"device_id": row["device_id"], "access_token": access,
                "access_expires_at": expires, "refresh_token": refresh,
                "refresh_expires_at": utc(now + REFRESH_SECONDS),
                "config": json.loads(row["config"])}

    def enroll(self, body):
        code = text_field(body.get("enrollment_code"), "enrollment_code")
        name = text_field(body.get("device_name"), "device_name", 128)
        text_field(body.get("app_version"), "app_version", 128)
        now = self.clock()
        with self.connection() as db:
            row = db.execute("SELECT * FROM enrollments WHERE code_hash=?", (digest(code),)).fetchone()
            if not row or row["used"] is not None or row["expires"] <= now:
                raise ApiError(401, "INVALID_OR_USED_ENROLLMENT")
            if name != row["device_name"]:
                raise ApiError(403, "ENROLLMENT_NAME_MISMATCH")
            device_id, refresh = "probe-" + uuid.uuid4().hex, token()
            db.execute("UPDATE enrollments SET used=? WHERE code_hash=?", (now, digest(code)))
            db.execute("INSERT INTO devices(device_id,device_name,config,enrolled,refresh_hash,refresh_expires) VALUES(?,?,?,?,?,?)",
                       (device_id, name, row["config"], now, digest(refresh), now + REFRESH_SECONDS))
            device = db.execute("SELECT * FROM devices WHERE device_id=?", (device_id,)).fetchone()
            return self._credentials(db, device, refresh, now)

    def refresh(self, body):
        device_id = valid_id(body.get("device_id"), "device_id")
        refresh = text_field(body.get("refresh_token"), "refresh_token")
        now = self.clock()
        with self.connection() as db:
            row = db.execute("SELECT * FROM devices WHERE device_id=?", (device_id,)).fetchone()
            if (not row or row["revoked"] or row["refresh_expires"] <= now
                    or not secrets.compare_digest(row["refresh_hash"], digest(refresh))):
                raise ApiError(401, "INVALID_REFRESH_TOKEN")
            db.execute("UPDATE devices SET refresh_expires=? WHERE device_id=?", (now + REFRESH_SECONDS, device_id))
            return self._credentials(db, row, refresh, now)

    def _authenticate(self, db, bearer, body):
        if not isinstance(bearer, str) or not bearer or len(bearer) > 256:
            raise ApiError(401, "AUTHENTICATION_REQUIRED")
        row = db.execute("""SELECT d.* FROM devices d JOIN access_tokens t USING(device_id)
                            WHERE t.token_hash=? AND t.expires>? AND d.revoked=0""",
                         (digest(bearer), self.clock())).fetchone()
        if not row:
            raise ApiError(401, "INVALID_ACCESS_TOKEN")
        if body.get("device_id") != row["device_id"]:
            raise ApiError(403, "DEVICE_MISMATCH")
        return row["device_id"]

    def _owned_command(self, db, command_id, device_id):
        valid_id(command_id, "command_id")
        row = db.execute("SELECT * FROM commands WHERE command_id=? AND device_id=?", (command_id, device_id)).fetchone()
        if not row:
            raise ApiError(403, "COMMAND_NOT_OWNED")
        return row

    def _audio_call_owner(self, db, event, device_id):
        call_id = valid_id(event.get("call_id"), "call_id")
        call_command_id = event["data"].get("call_command_id")
        call = self._owned_command(db, call_command_id, device_id)
        if call["action"] != "CALL" or call["delivered"] is None or call["state"] == "EXPIRED":
            raise ApiError(409, "AUDIO_EVENT_REQUIRES_LIVE_CALL_COMMAND")
        known_ids = set()
        if call["result"]:
            accepted_call_id = json.loads(call["result"]).get("data", {}).get("call_id")
            if accepted_call_id:
                known_ids.add(accepted_call_id)
        for row in db.execute("SELECT payload FROM events WHERE command_id=? AND device_id=?", (call_command_id, device_id)):
            value = json.loads(row["payload"])
            if value.get("event") in CALL_EVENTS and value.get("call_id"):
                known_ids.add(value["call_id"])
        if known_ids and call_id not in known_ids:
            raise ApiError(409, "AUDIO_CALL_ID_MISMATCH")
        # Offline uploads may arrive before a CALL result is known. Ownership is
        # still tied to the explicit CALL command; no cross-device ID inference.
        binding = db.execute("SELECT * FROM call_bindings WHERE call_id=?", (call_id,)).fetchone()
        if binding and (binding["device_id"] != device_id or binding["command_id"] != call_command_id):
            raise ApiError(403, "CALL_NOT_OWNED")

    def _bind_call(self, db, call_id, device_id, command_id):
        valid_id(call_id, "call_id")
        existing = db.execute("SELECT * FROM call_bindings WHERE call_id=? OR command_id=?", (call_id, command_id)).fetchall()
        if existing:
            if any(row["device_id"] != device_id or row["command_id"] != command_id or row["call_id"] != call_id for row in existing):
                raise ApiError(409, "CALL_ID_BINDING_CONFLICT")
        else:
            db.execute("INSERT INTO call_bindings VALUES(?,?,?)", (call_id, device_id, command_id))

    def _authorize_artifact(self, db, command_id, bearer, device_id, file_id, expected_sha):
        self._authenticate(db, bearer, {"device_id": device_id})
        command = self._owned_command(db, command_id, device_id)
        if command["action"] not in {"UPLOAD_FILE", "UPLOAD_AUDIO"}:
            raise ApiError(409, "UPLOAD_FILE_COMMAND_REQUIRED")
        if json.loads(command["parameters"]).get("file_id") != file_id:
            raise ApiError(409, "UPLOAD_FILE_ID_MISMATCH")
        previous = db.execute("SELECT * FROM artifacts WHERE upload_command_id=?", (command_id,)).fetchone()
        if previous and (previous["sha256"] != expected_sha or previous["file_id"] != file_id):
            raise ApiError(409, "ARTIFACT_REPLAY_CONFLICT")
        if command["delivered"] is None or (not previous and command["state"] not in {"DELIVERED", "SUCCESS"}):
            raise ApiError(409, "UPLOAD_COMMAND_NOT_LIVE")
        if not previous and self.clock() > command["expires"]:
            raise ApiError(409, "UPLOAD_COMMAND_EXPIRED")
        return previous

    @staticmethod
    def _artifact_response(row):
        return {key: row[key] for key in ("artifact_id", "file_id", "sha256", "size_bytes")}

    def upload_artifact(self, command_id, bearer, device_id, file_id, expected_sha, size, stream):
        """Authorize before reading, stream bounded bytes, validate, publish atomically."""
        valid_id(command_id, "command_id")
        if not isinstance(file_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", file_id):
            raise ApiError(400, "INVALID_FILE_ID")
        if not isinstance(expected_sha, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", expected_sha):
            raise ApiError(400, "SHA256_REQUIRED")
        expected_sha = expected_sha.lower()
        if type(size) is not int or not 0 < size <= MAX_ARTIFACT:
            raise ApiError(413, "BODY_TOO_LARGE")
        with self.connection() as db:
            self._authorize_artifact(db, command_id, bearer, device_id, file_id, expected_sha)
        os.makedirs(self.artifact_dir, mode=0o700, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(prefix="upload-", suffix=".part", dir=self.artifact_dir, delete=False) as target:
                temporary = target.name
                checksum, remaining = hashlib.sha256(), size
                while remaining:
                    chunk = stream.read(min(64 * 1024, remaining))
                    if not chunk or len(chunk) > remaining:
                        raise ApiError(400, "INCOMPLETE_BODY")
                    target.write(chunk)
                    checksum.update(chunk)
                    remaining -= len(chunk)
                target.flush()
                os.fsync(target.fileno())
            if not secrets.compare_digest(checksum.hexdigest(), expected_sha):
                raise ApiError(422, "SHA256_MISMATCH")
            wav_info = inspect_wav(temporary, size)
            artifact_id = "artifact-" + digest(device_id + ":" + command_id)[:32]
            destination = os.path.join(self.artifact_dir, artifact_id + ".wav")
            with self.connection() as db:
                # Recheck revocation and the command after potentially slow I/O.
                previous = self._authorize_artifact(db, command_id, bearer, device_id, file_id, expected_sha)
                if previous:
                    if previous["size_bytes"] != size:
                        raise ApiError(409, "ARTIFACT_REPLAY_CONFLICT")
                    if not os.path.isfile(previous["path"]):
                        raise ApiError(409, "ARTIFACT_STORAGE_MISSING")
                    return self._artifact_response(previous)
                # Recover the narrow crash window after atomic rename but before
                # SQLite commit without permitting an altered same-command replay.
                if os.path.exists(destination):
                    old_hash = hashlib.sha256()
                    with open(destination, "rb") as old:
                        for block in iter(lambda: old.read(64 * 1024), b""):
                            old_hash.update(block)
                    if os.path.getsize(destination) != size or old_hash.hexdigest() != expected_sha:
                        raise ApiError(409, "ARTIFACT_REPLAY_CONFLICT")
                else:
                    os.replace(temporary, destination)
                    temporary = None
                    directory_fd = os.open(self.artifact_dir, os.O_RDONLY)
                    try:
                        os.fsync(directory_fd)
                    finally:
                        os.close(directory_fd)
                db.execute("INSERT INTO artifacts VALUES(?,?,?,?,?,?,?,?,?)",
                           (artifact_id, command_id, device_id, file_id, expected_sha, size, destination,
                            self.clock(), canonical(wav_info)))
                return {"artifact_id": artifact_id, "file_id": file_id, "sha256": expected_sha, "size_bytes": size}
        finally:
            if temporary is not None:
                with contextlib.suppress(FileNotFoundError):
                    os.unlink(temporary)

    def poll(self, bearer, body):
        no_credentials(body)
        with self.connection() as db:
            device_id = self._authenticate(db, bearer, body)
            now = self.clock()
            db.execute("UPDATE devices SET last_seen=?,last_status=? WHERE device_id=?", (now, canonical(body), device_id))
            db.execute("UPDATE commands SET state='EXPIRED' WHERE device_id=? AND state IN ('QUEUED','DELIVERED') AND expires<=?", (device_id, now))
            # Emergency control and inspection can bypass a slow WAIT/download/unacknowledged CALL.
            row = db.execute("""SELECT * FROM commands WHERE device_id=? AND state IN ('QUEUED','DELIVERED')
                ORDER BY CASE action WHEN 'HANGUP' THEN 0 WHEN 'STOP_AUDIO' THEN 0 WHEN 'STOP_RECORDING' THEN 0
                WHEN 'GET_STATUS' THEN 1 WHEN 'GET_RADIO_STATS' THEN 1
                WHEN 'GET_AUDIO_STATUS' THEN 1 WHEN 'GET_AUDIO_DIAGNOSTICS' THEN 1 ELSE 2 END,
                sequence LIMIT 1""", (device_id,)).fetchone()
            if not row:
                return {"action": "NONE"}
            db.execute("UPDATE commands SET state='DELIVERED',delivered=COALESCE(delivered,?) WHERE command_id=?", (now, row["command_id"]))
            return {"command_id": row["command_id"], "action": row["action"],
                    "parameters": json.loads(row["parameters"]), "issued_at": utc(row["issued"]), "expires_at": utc(row["expires"])}

    def _batch(self, body, key):
        rows = body.get(key)
        if not isinstance(rows, list) or len(rows) > MAX_BATCH or any(not isinstance(x, dict) for x in rows):
            raise ApiError(400, "INVALID_" + key.upper())
        return rows

    def results(self, bearer, body):
        rows = self._batch(body, "results")
        no_credentials(rows)
        with self.connection() as db:
            device_id = self._authenticate(db, bearer, body)
            accepted = []
            for result in rows:
                row = self._owned_command(db, result.get("command_id"), device_id)
                if result.get("action") != row["action"]:
                    raise ApiError(409, "COMMAND_ACTION_MISMATCH")
                if result.get("status") not in {"SUCCESS", "FAILED"}:
                    raise ApiError(400, "INVALID_RESULT_STATUS")
                if not isinstance(result.get("data"), dict):
                    raise ApiError(400, "INVALID_RESULT_DATA")
                if result["status"] == "FAILED":
                    text_field(result.get("error"), "error")
                for key in ("received_at", "execution_at", "completed_at"):
                    epoch(result.get(key), key)
                payload = canonical(result)
                if row["result"] is not None:
                    if row["result"] != payload:
                        raise ApiError(409, "RESULT_REPLAY_CONFLICT")
                else:
                    if row["delivered"] is None:
                        raise ApiError(409, "COMMAND_NOT_DELIVERED")
                    # Permit delayed upload for a command begun before expiry. Expired local
                    # commands can always report a failure without executing a side effect.
                    if epoch(result["execution_at"]) > row["expires"] and result["status"] == "SUCCESS":
                        raise ApiError(409, "COMMAND_EXECUTED_AFTER_EXPIRY")
                    if row["action"] == "CALL" and result["data"].get("call_id"):
                        self._bind_call(db, result["data"]["call_id"], device_id, row["command_id"])
                    db.execute("UPDATE commands SET state=?,result=? WHERE command_id=?",
                               (result["status"], payload, row["command_id"]))
                accepted.append(row["command_id"])
            return {"accepted": accepted}

    def events(self, bearer, body):
        rows = self._batch(body, "events")
        no_credentials(rows)
        with self.connection() as db:
            device_id = self._authenticate(db, bearer, body)
            accepted = []
            for event in rows:
                event_id = valid_id(event.get("event_id"), "event_id")
                name = text_field(event.get("event"), "event", 128)
                epoch(event.get("timestamp"))
                if not isinstance(event.get("data"), dict):
                    raise ApiError(400, "INVALID_EVENT_DATA")
                if event.get("call_id") is not None:
                    valid_id(event["call_id"], "call_id")
                if name in CALL_EVENTS and (not event.get("command_id") or not event.get("call_id")):
                    raise ApiError(400, "CALL_EVENT_REQUIRES_COMMAND_AND_CALL")
                payload = canonical(event)
                previous = db.execute("SELECT device_id,payload FROM events WHERE event_id=?", (event_id,)).fetchone()
                if previous:
                    if previous["device_id"] != device_id or previous["payload"] != payload:
                        raise ApiError(409, "EVENT_REPLAY_CONFLICT")
                    accepted.append(event_id)
                    continue
                if event.get("command_id"):
                    command = self._owned_command(db, event["command_id"], device_id)
                    if command["delivered"] is None or command["state"] == "EXPIRED":
                        raise ApiError(409, "COMMAND_NOT_LIVE")
                    if name in CALL_EVENTS and command["action"] != "CALL":
                        raise ApiError(409, "CALL_EVENT_REQUIRES_CALL_COMMAND")
                    if name in CALL_EVENTS:
                        self._bind_call(db, event["call_id"], device_id, command["command_id"])
                    if name.startswith(("AUDIO_TX_", "TX_", "AUDIO_RX_", "RX_")):
                        expected = "PLAY_AUDIO" if name.startswith(("AUDIO_TX_", "TX_")) else "START_RECORDING"
                        if command["action"] != expected:
                            raise ApiError(409, "AUDIO_EVENT_COMMAND_MISMATCH")
                        self._audio_call_owner(db, event, device_id)
                elif name.startswith(("AUDIO_TX_", "TX_", "AUDIO_RX_", "RX_")):
                    raise ApiError(400, "AUDIO_EVENT_REQUIRES_COMMAND")
                db.execute("INSERT INTO events VALUES(?,?,?,?,?)", (event_id, device_id, event.get("command_id"), self.clock(), payload))
                accepted.append(event_id)
            return {"accepted": accepted}

    def logs(self, bearer, body):
        rows = self._batch(body, "logs")
        no_credentials(rows)
        with self.connection() as db:
            device_id = self._authenticate(db, bearer, body)
            accepted = []
            for log in rows:
                log_id = valid_id(log.get("log_id"), "log_id")
                if log.get("device_id") != device_id:
                    raise ApiError(403, "LOG_DEVICE_MISMATCH")
                epoch(log.get("timestamp"))
                for key in ("component", "severity", "event"):
                    text_field(log.get(key), key, 128)
                if not isinstance(log.get("data"), dict):
                    raise ApiError(400, "INVALID_LOG_DATA")
                if log.get("command_id") and not str(log["command_id"]).startswith("local-"):
                    self._owned_command(db, log["command_id"], device_id)
                payload = canonical(log)
                previous = db.execute("SELECT device_id,payload FROM logs WHERE log_id=?", (log_id,)).fetchone()
                if previous:
                    if previous["device_id"] != device_id or previous["payload"] != payload:
                        raise ApiError(409, "LOG_REPLAY_CONFLICT")
                else:
                    db.execute("INSERT INTO logs VALUES(?,?,?,?)", (log_id, device_id, self.clock(), payload))
                accepted.append(log_id)
            return {"accepted": accepted}

    def queue(self, device_id, action, parameters, ttl=300, command_id=None):
        valid_id(device_id, "device_id")
        command_id = valid_id(command_id or "cmd-" + uuid.uuid4().hex, "command_id")
        if action not in ACTIONS or not isinstance(parameters, dict):
            raise ApiError(400, "INVALID_COMMAND")
        if not 1 <= ttl <= 7 * 86400:
            raise ApiError(400, "INVALID_COMMAND_TTL")
        if action == "CALL" and not NUMBER_RE.fullmatch(str(parameters.get("number", ""))):
            raise ApiError(400, "CALL_REQUIRES_E164_NUMBER")
        if action == "WAIT" and (type(parameters.get("duration_ms")) is not int or not 0 <= parameters["duration_ms"] <= 3_600_000):
            raise ApiError(400, "INVALID_WAIT_DURATION")
        if action in {"PLAY_AUDIO", "START_RECORDING"}:
            if "sample_rate" in parameters and (type(parameters["sample_rate"]) is not int or parameters["sample_rate"] not in AUDIO_RATES):
                raise ApiError(400, "INVALID_SAMPLE_RATE")
        if action == "PLAY_AUDIO":
            mode = parameters.get("mode", "NORMAL")
            if not isinstance(mode, str) or mode not in {"NORMAL", "DIGITAL_TX_VALIDATION"}:
                raise ApiError(400, "INVALID_AUDIO_MODE")
            for flag in ("loop", "mute_microphone"):
                if flag in parameters and type(parameters[flag]) is not bool:
                    raise ApiError(400, "INVALID_" + flag.upper())
            if mode == "DIGITAL_TX_VALIDATION" and parameters.get("mute_microphone", True) is not True:
                raise ApiError(400, "DIGITAL_TX_VALIDATION_REQUIRES_MICROPHONE_MUTE")
        if action == "GET_AUDIO_DIAGNOSTICS" and "probe_downlink" in parameters and type(parameters["probe_downlink"]) is not bool:
            raise ApiError(400, "INVALID_PROBE_DOWNLINK")
        if action == "START_RECORDING" and (not isinstance(parameters.get("direction", "DOWNLINK"), str)
                or parameters.get("direction", "DOWNLINK") not in {"DOWNLINK", "UPLINK", "BOTH"}):
            raise ApiError(400, "INVALID_AUDIO_DIRECTION")
        if action == "DELETE_FILE" and (not isinstance(parameters.get("kind", "reference"), str)
                or parameters.get("kind", "reference") not in {"reference", "recording"}):
            raise ApiError(400, "INVALID_FILE_KIND")
        if action in {"DOWNLOAD_FILE", "DELETE_FILE", "PLAY_AUDIO", "START_RECORDING", "UPLOAD_FILE", "UPLOAD_AUDIO"}:
            if not isinstance(parameters.get("file_id"), str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", parameters["file_id"]):
                raise ApiError(400, "INVALID_FILE_ID")
        if action == "DOWNLOAD_FILE":
            raw_url = parameters.get("url", "")
            try:
                url = urlsplit(raw_url) if isinstance(raw_url, str) else None
                if (url is None or not raw_url.startswith("https://") or not url.hostname
                        or url.username is not None or url.password is not None or "#" in raw_url):
                    raise ValueError("HTTPS URL without credentials or fragment required")
                port = url.port
            except ValueError:
                raise ApiError(400, "HTTPS_DOWNLOAD_URL_REQUIRED") from None
            # This reference controller serves no media itself: allowlisted media
            # origins must use standard HTTPS, matching the probe's host policy.
            if port not in (None, 443):
                raise ApiError(400, "DOWNLOAD_HOST_NOT_ALLOWED")
            if not re.fullmatch(r"[a-fA-F0-9]{64}", str(parameters.get("sha256", ""))):
                raise ApiError(400, "SHA256_REQUIRED")
        payload, now = canonical(parameters), self.clock()
        with self.connection() as db:
            device = db.execute("SELECT * FROM devices WHERE device_id=? AND revoked=0", (device_id,)).fetchone()
            if not device:
                raise ApiError(404, "DEVICE_NOT_FOUND")
            if action == "DOWNLOAD_FILE":
                hosts = json.loads(device["config"])["download_allowed_hosts"]
                if url.hostname.lower() not in {str(x).lower() for x in hosts}:
                    raise ApiError(400, "DOWNLOAD_HOST_NOT_ALLOWED")
            existing = db.execute("SELECT * FROM commands WHERE command_id=?", (command_id,)).fetchone()
            if existing:
                if existing["device_id"] != device_id or existing["action"] != action or existing["parameters"] != payload:
                    raise ApiError(409, "COMMAND_ID_CONFLICT")
                return self._command_dict(existing)
            db.execute("INSERT INTO commands(command_id,device_id,action,parameters,issued,expires) VALUES(?,?,?,?,?,?)",
                       (command_id, device_id, action, payload, now, now + ttl))
            return self._command_dict(db.execute("SELECT * FROM commands WHERE command_id=?", (command_id,)).fetchone())

    def _command_dict(self, row):
        return {"command_id": row["command_id"], "device_id": row["device_id"], "action": row["action"],
                "parameters": json.loads(row["parameters"]), "issued_at": utc(row["issued"]),
                "expires_at": utc(row["expires"]), "status": row["state"],
                "result": json.loads(row["result"]) if row["result"] else None}

    def list_devices(self):
        with self.connection() as db:
            result = []
            for row in db.execute("SELECT * FROM devices ORDER BY device_name"):
                config = json.loads(row["config"])
                last = json.loads(row["last_status"]) if row["last_status"] else None
                online = not row["revoked"] and row["last_seen"] is not None and self.clock() - row["last_seen"] <= max(60, config["poll_interval_seconds"] * 3)
                result.append({"device_id": row["device_id"], "device_name": row["device_name"],
                               "connection": "ONLINE" if online else "OFFLINE", "revoked": bool(row["revoked"]),
                               "config": config, "last_seen": utc(row["last_seen"]) if row["last_seen"] else None,
                               "last_status": last})
            return result

    def inspect(self, collection, device_id=None, limit=100):
        if collection not in {"results", "events", "logs", "artifacts"} or not 1 <= limit <= 10000:
            raise ApiError(400, "INVALID_QUERY")
        table = "commands" if collection == "results" else collection
        order = "sequence" if collection == "results" else "created" if collection == "artifacts" else "received"
        where, args = ("WHERE device_id=?", [device_id]) if device_id else ("", [])
        with self.connection() as db:
            rows = db.execute(f"SELECT * FROM {table} {where} ORDER BY {order} DESC LIMIT ?", args + [limit]).fetchall()
            if collection == "artifacts":
                return [{**dict(row), "created": utc(row["created"]), "wav_info": json.loads(row["wav_info"])} for row in rows]
            return [self._command_dict(row) if collection == "results" else json.loads(row["payload"]) for row in rows]

    def revoke(self, device_id):
        with self.connection() as db:
            changed = db.execute("UPDATE devices SET revoked=1 WHERE device_id=?", (device_id,)).rowcount
            if not changed:
                raise ApiError(404, "DEVICE_NOT_FOUND")
            db.execute("DELETE FROM access_tokens WHERE device_id=?", (device_id,))
        return {"device_id": device_id, "revoked": True}

    def dispatch(self, path, body, bearer=None):
        if not isinstance(body, dict):
            raise ApiError(400, "JSON_OBJECT_REQUIRED")
        if path == "/api/v1/probe/enroll":
            return self.enroll(body)
        if path == "/api/v1/probe/token":
            return self.refresh(body)
        methods = {"poll": self.poll, "results": self.results, "events": self.events, "logs": self.logs}
        name = path.removeprefix("/api/v1/probe/")
        if path != "/api/v1/probe/" + name or name not in methods:
            raise ApiError(404, "NOT_FOUND")
        return methods[name](bearer, body)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SinchProbe/0.2"
    sys_version = ""

    def setup(self):
        self.request.settimeout(15)
        super().setup()

    def log_message(self, fmt, *args):
        # No URLs, authorization headers, request bodies, or raw user input in logs.
        pass

    def _reply(self, status, payload):
        data = canonical(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def do_GET(self):
        self._reply(405, {"error": "POST_REQUIRED"})

    def do_POST(self):
        try:
            if self.headers.get("Transfer-Encoding"):
                raise ApiError(400, "TRANSFER_ENCODING_UNSUPPORTED")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1:
                raise ApiError(411, "CONTENT_LENGTH_REQUIRED")
            try:
                size = int(lengths[0])
            except ValueError:
                raise ApiError(400, "INVALID_CONTENT_LENGTH") from None
            upload_prefix = "/api/v1/probe/artifacts/"
            upload = self.path.startswith(upload_prefix)
            if not 0 < size <= (MAX_ARTIFACT if upload else MAX_BODY):
                raise ApiError(413, "BODY_TOO_LARGE")
            auth = self.headers.get("Authorization", "")
            bearer = auth[7:] if auth.startswith("Bearer ") else None
            if upload:
                if self.headers.get_content_type() != "audio/wav":
                    raise ApiError(415, "WAV_CONTENT_TYPE_REQUIRED")
                upload_headers = {}
                for header in ("X-Device-ID", "X-File-ID", "X-SHA256"):
                    values = self.headers.get_all(header, [])
                    if len(values) != 1:
                        raise ApiError(400, "UPLOAD_HEADERS_REQUIRED")
                    upload_headers[header] = values[0]
                result = self.server.controller.upload_artifact(
                    self.path[len(upload_prefix):], bearer, upload_headers["X-Device-ID"],
                    upload_headers["X-File-ID"], upload_headers["X-SHA256"], size, self.rfile)
                self._reply(200, result)
                return
            if self.headers.get_content_type() != "application/json":
                raise ApiError(415, "JSON_CONTENT_TYPE_REQUIRED")
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ApiError(400, "INCOMPLETE_BODY")
            try:
                body = json.loads(raw.decode("utf-8"), parse_constant=lambda x: (_ for _ in ()).throw(ValueError("nonfinite")))
            except (UnicodeError, ValueError, RecursionError):
                raise ApiError(400, "INVALID_JSON") from None
            result = self.server.controller.dispatch(self.path, body, bearer)
            self._reply(200, result)
        except ApiError as exc:
            self._reply(exc.status, {"error": exc.code})
        except (TimeoutError, ConnectionError, BrokenPipeError):
            self.close_connection = True
        except Exception:
            # Do not leak SQLite statements, secrets, or filesystem paths.
            print(utc() + " request_failed", file=sys.stderr)
            self._reply(500, {"error": "INTERNAL_ERROR"})


class ProbeHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 32

    def __init__(self, *args, **kwargs):
        self.workers = threading.BoundedSemaphore(32)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        # Bound worker/socket resources, including peers that never finish TLS.
        if not self.workers.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.workers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.workers.release()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", default="probe.db", help="SQLite path, owner-only permissions")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve", help="Run HTTPS only; no admin HTTP endpoints")
    serve.add_argument("--cert", required=True)
    serve.add_argument("--key", required=True)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8443)
    serve.add_argument("--artifact-dir", help="Private WAV storage; default <db stem>-artifacts")
    enroll = sub.add_parser("create-enrollment", help="Issue one-use code; output is sensitive")
    enroll.add_argument("--device-name", required=True)
    enroll.add_argument("--carrier", required=True)
    enroll.add_argument("--site-id")
    enroll.add_argument("--test-number")
    enroll.add_argument("--subscription-id", type=int)
    enroll.add_argument("--poll-seconds", type=int, default=15)
    enroll.add_argument("--download-host", action="append", default=[])
    enroll.add_argument("--ttl-seconds", type=int, default=3600)
    sub.add_parser("list-devices")
    queue = sub.add_parser("queue")
    queue.add_argument("--device-id", required=True)
    queue.add_argument("--action", required=True, choices=sorted(ACTIONS))
    queue.add_argument("--parameters", default="{}", help="JSON object")
    queue.add_argument("--command-id")
    queue.add_argument("--ttl-seconds", type=int, default=300)
    for name in ("results", "events", "logs", "artifacts"):
        inspect = sub.add_parser(name)
        inspect.add_argument("--device-id")
        inspect.add_argument("--limit", type=int, default=100)
    revoke = sub.add_parser("revoke-device")
    revoke.add_argument("--device-id", required=True)
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        controller = Controller(args.db, artifact_dir=getattr(args, "artifact_dir", None))
        if args.command == "serve":
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(args.cert, args.key)
            with ProbeHTTPServer((args.host, args.port), Handler) as server:
                server.controller = controller
                # Handshake after accept, in the worker, so a slow TLS peer cannot stop accept().
                server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
                print(f"HTTPS listening on {args.host}:{args.port}", flush=True)
                try:
                    server.serve_forever()
                except KeyboardInterrupt:
                    pass
            return 0
        if args.command == "create-enrollment":
            result = controller.create_enrollment(args.device_name,
                {"carrier": args.carrier, "site_id": args.site_id, "test_number": args.test_number,
                 "subscription_id": args.subscription_id, "poll_interval_seconds": args.poll_seconds,
                 "download_allowed_hosts": args.download_host}, args.ttl_seconds)
        elif args.command == "list-devices":
            result = controller.list_devices()
        elif args.command == "queue":
            result = controller.queue(args.device_id, args.action, json.loads(args.parameters), args.ttl_seconds, args.command_id)
        elif args.command == "revoke-device":
            result = controller.revoke(args.device_id)
        else:
            result = controller.inspect(args.command, args.device_id, args.limit)
        print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
        return 0
    except (ApiError, ValueError, OSError) as exc:
        print(json.dumps({"error": exc.code if isinstance(exc, ApiError) else str(exc)}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

# SPDX-License-Identifier: MIT
"""Minimal synchronous TEST-event relay."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import sqlite3
import stat
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_BODY_BYTES = 1024
MAX_PROVIDER_RESPONSE_BYTES = 4096
MAX_CREATED_AT = 2_147_482_747
EVENT_PATH = "/v1/events"
ID_RE = re.compile(r"^[A-Za-z0-9_-]{22}$")
SIGNATURE_RE = re.compile(r"^v1=([A-Za-z0-9_-]{43})$")
KEY_RE = re.compile(r"^[0-9a-f]{64}$")
TOKEN_RE = re.compile(r"^[A-Za-z0-9]{30}$")
EVENT_FIELDS = {
    "v",
    "event_id",
    "incident_id",
    "device_id",
    "kind",
    "sequence",
    "created_at",
    "expires_at",
    "payload",
}
DUMMY_KEY = bytes(32)
TEST_MESSAGE = "TEST ONLY — Garmin alert transport check. No emergency action required."
LOGGER = logging.getLogger("smart-panic-relay")


class ProviderFailure(Exception):
    """A provider outcome safe to expose to the watch."""

    def __init__(self, result: str, code: str):
        super().__init__(code)
        self.result = result
        self.code = code


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class RelayHTTPServer(ThreadingHTTPServer):
    def handle_error(self, _request, _client_address):
        LOGGER.error("request handling failed")


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _reject_non_json_number(value):
    raise ValueError(f"invalid JSON number: {value}")


def parse_json(data: bytes):
    return json.loads(
        data.decode("utf-8"),
        object_pairs_hook=_reject_duplicate_keys,
        parse_constant=_reject_non_json_number,
    )


def is_canonical_id(value) -> bool:
    if not isinstance(value, str) or not ID_RE.fullmatch(value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "==")
    except (ValueError, TypeError):
        return False
    return (
        len(decoded) == 16
        and base64.urlsafe_b64encode(decoded).rstrip(b"=").decode() == value
    )


def decode_key(value: str) -> bytes:
    if not isinstance(value, str) or not KEY_RE.fullmatch(value):
        raise ValueError("device key must be 32 bytes encoded as 64 lowercase hex characters")
    return bytes.fromhex(value)


def load_devices(path: str | os.PathLike[str]) -> dict[str, dict]:
    config_path = Path(path)
    if config_path.stat().st_size > 1_048_576:
        raise ValueError("device configuration is too large")
    raw = parse_json(config_path.read_bytes())
    if type(raw) is not dict or len(raw) > 1000:
        raise ValueError("device configuration must be an object with at most 1000 devices")

    devices = {}
    for device_id, record in raw.items():
        if not is_canonical_id(device_id):
            raise ValueError("invalid device ID in configuration")
        if type(record) is not dict or set(record) != {"key", "enabled"}:
            raise ValueError(f"device {device_id!r} must contain only key and enabled")
        if type(record["enabled"]) is not bool:
            raise ValueError(f"device {device_id!r} enabled must be boolean")
        devices[device_id] = {
            "key": decode_key(record["key"]),
            "enabled": record["enabled"],
        }
    if any(device["enabled"] for device in devices.values()):
        mode = stat.S_IMODE(config_path.stat().st_mode)
        if mode & 0o077:
            raise ValueError("enabled device configuration must have mode 0600")
    return devices


def signature_for(key: bytes, data: bytes) -> str:
    digest = hmac.digest(key, data, "sha256")
    return "v1=" + base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def signature_matches(header: str | None, key: bytes, data: bytes) -> bool:
    match = SIGNATURE_RE.fullmatch(header or "")
    supplied = match.group(1) if match else "A" * 43
    expected = signature_for(key, data)[3:]
    return match is not None and hmac.compare_digest(supplied, expected)


def validate_event(event) -> str | None:
    if type(event) is not dict or set(event) != EVENT_FIELDS:
        return "invalid_event"
    if type(event["v"]) is not int or event["v"] != 1:
        return "invalid_event"
    if not is_canonical_id(event["event_id"]):
        return "invalid_event"
    if event["incident_id"] != event["event_id"]:
        return "invalid_event"
    if not is_canonical_id(event["device_id"]):
        return "invalid_event"
    if type(event["kind"]) is not str or event["kind"] != "test.triggered":
        return "invalid_event"
    if type(event["sequence"]) is not int or event["sequence"] != 0:
        return "invalid_event"
    if (
        type(event["created_at"]) is not int
        or not 0 <= event["created_at"] <= MAX_CREATED_AT
    ):
        return "invalid_event"
    if type(event["expires_at"]) is not int or event["expires_at"] != event["created_at"] + 900:
        return "invalid_event"
    if event["payload"] is not None:
        return "invalid_event"
    return None


def canonical_event(event: dict) -> bytes:
    return (
        "spb.test.submit.v1\n"
        "method=POST\n"
        "v=1\n"
        f"event_id={event['event_id']}\n"
        f"incident_id={event['incident_id']}\n"
        f"device_id={event['device_id']}\n"
        "kind=test.triggered\n"
        "sequence=0\n"
        f"created_at={event['created_at']}\n"
        f"expires_at={event['expires_at']}\n"
        "payload=null\n"
    ).encode("ascii")


def canonical_result(event_id: str) -> bytes:
    return (
        "spb.test.result.v1\n"
        "v=1\n"
        f"event_id={event_id}\n"
        "result=provider_accepted\n"
        "provider=pushover\n"
    ).encode("ascii")


def response(result: str, code: str | None = None, event_id: str | None = None, **extra):
    body = {"v": 1}
    if event_id is not None:
        body["event_id"] = event_id
    body["result"] = result
    if code is not None:
        body["code"] = code
    body.update(extra)
    return body


def accepted_response(key: bytes, event_id: str) -> dict:
    body = response("provider_accepted", event_id=event_id, provider="pushover")
    body["response_signature"] = signature_for(key, canonical_result(event_id))
    return body


class PushoverClient:
    URL = "https://api.pushover.net/1/messages.json"

    def __init__(self, app_token: str, user_key: str, timeout: float = 10, opener=None):
        if not TOKEN_RE.fullmatch(app_token or "") or not TOKEN_RE.fullmatch(user_key or ""):
            raise ValueError("Pushover token and user key must each be 30 alphanumeric characters")
        if not 1 <= timeout <= 60:
            raise ValueError("provider timeout must be between 1 and 60 seconds")
        self.app_token = app_token
        self.user_key = user_key
        self.timeout = timeout
        self.opener = opener or build_opener(_NoRedirects())

    def send(self, event_id: str) -> str:
        form = urlencode(
            {
                "token": self.app_token,
                "user": self.user_key,
                "title": f"Garmin TEST {event_id}",
                "message": TEST_MESSAGE,
            }
        ).encode("utf-8")
        request = Request(
            self.URL,
            data=form,
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        try:
            with self.opener.open(request, timeout=self.timeout) as provider_response:
                if provider_response.status != 200:
                    raise ProviderFailure("result_unknown", "provider_result_unknown")
                raw = provider_response.read(MAX_PROVIDER_RESPONSE_BYTES + 1)
        except HTTPError as exc:
            if 400 <= exc.code < 500:
                raise ProviderFailure("configuration_failure", "provider_rejected") from None
            raise ProviderFailure("result_unknown", "provider_result_unknown") from None
        except OSError:
            raise ProviderFailure("result_unknown", "provider_result_unknown") from None

        if len(raw) > MAX_PROVIDER_RESPONSE_BYTES:
            raise ProviderFailure("result_unknown", "provider_result_unknown")
        try:
            decoded = parse_json(raw)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            raise ProviderFailure("result_unknown", "provider_result_unknown") from None
        if type(decoded) is dict and type(decoded.get("status")) is int and decoded["status"] == 0:
            raise ProviderFailure("configuration_failure", "provider_rejected")
        if (
            type(decoded) is not dict
            or type(decoded.get("status")) is not int
            or decoded["status"] != 1
            or type(decoded.get("request")) is not str
            or not 1 <= len(decoded["request"]) <= 128
        ):
            raise ProviderFailure("result_unknown", "provider_result_unknown")
        return decoded["request"]


class Relay:
    def __init__(
        self,
        devices: dict[str, dict],
        provider,
        database: str = ":memory:",
        max_clock_skew: int = 300,
        clock=time.time,
    ):
        if not 0 <= max_clock_skew <= 3600:
            raise ValueError("max clock skew must be between 0 and 3600 seconds")
        if database != ":memory:":
            fd = os.open(database, os.O_CREAT | os.O_RDWR, 0o600)
            os.close(fd)
            if stat.S_IMODE(os.stat(database).st_mode) & 0o077:
                raise ValueError("relay database must have mode 0600")
        self.devices = devices
        self.provider = provider
        self.max_clock_skew = max_clock_skew
        self.clock = clock
        self.db = sqlite3.connect(database, timeout=5, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS test_events (
                device_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                canonical_sha256 BLOB NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('provider_call_started', 'provider_accepted', 'result_unknown')),
                provider_reference TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (device_id, event_id)
            )
            """
        )
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS relay_state (
                singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                clock_high_water INTEGER NOT NULL
            )
            """
        )
        self.db.execute(
            "INSERT OR IGNORE INTO relay_state (singleton, clock_high_water) VALUES (1, 0)"
        )
        self.db.commit()
        self.db_lock = threading.Lock()
        # ponytail: one provider slot fits the prototype and Pushover quota;
        # the phase-2 outbox owns concurrency when measured demand arrives.
        self.provider_gate = threading.Lock()

    def close(self):
        with self.db_lock:
            self.db.close()

    def _lookup(self, device_id: str, event_id: str):
        return self.db.execute(
            "SELECT canonical_sha256, state FROM test_events WHERE device_id = ? AND event_id = ?",
            (device_id, event_id),
        ).fetchone()

    def _rollback(self):
        try:
            self.db.rollback()
        except sqlite3.Error:
            pass

    def _clock_is_trusted(self, now: int) -> bool:
        high_water = self.db.execute(
            "SELECT clock_high_water FROM relay_state WHERE singleton = 1"
        ).fetchone()[0]
        return now + self.max_clock_skew >= high_water

    def purge_expired(self):
        with self.db_lock:
            try:
                now = int(self.clock())
                self.db.execute("BEGIN IMMEDIATE")
                if not self._clock_is_trusted(now):
                    self._rollback()
                    return
                self.db.execute(
                    "UPDATE relay_state SET clock_high_water = MAX(clock_high_water, ?) WHERE singleton = 1",
                    (now,),
                )
                self.db.execute(
                    "DELETE FROM test_events WHERE updated_at < ?",
                    (now - 86_400,),
                )
                self.db.commit()
            except sqlite3.Error:
                self._rollback()
                raise

    def _existing_result(self, row, digest: bytes, key: bytes, event_id: str):
        if not hmac.compare_digest(row[0], digest):
            return 409, response("configuration_failure", "event_id_conflict", event_id)
        if row[1] == "provider_accepted":
            return 200, accepted_response(key, event_id)
        return 504, response("result_unknown", "prior_attempt_unknown", event_id)

    def process(self, body: bytes, signature: str | None) -> tuple[int, dict]:
        try:
            event = parse_json(body)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return 400, response("configuration_failure", "invalid_json")
        if validate_event(event):
            return 422, response("configuration_failure", "invalid_event")

        canonical = canonical_event(event)
        device_id = event["device_id"]
        device = self.devices.get(device_id)
        signing_key = device["key"] if device else DUMMY_KEY
        authenticated = signature_matches(signature, signing_key, canonical)
        if device is None or not device["enabled"] or not authenticated:
            return 401, response("configuration_failure", "authentication_failed")

        event_id = event["event_id"]
        digest = hashlib.sha256(canonical).digest()
        now = int(self.clock())
        try:
            with self.db_lock:
                existing = self._lookup(device_id, event_id)
        except sqlite3.Error:
            with self.db_lock:
                self._rollback()
            return 503, response("retryable_failure", "persistence_unavailable", event_id)
        if existing:
            return self._existing_result(existing, digest, signing_key, event_id)

        if (
            event["created_at"] > now + self.max_clock_skew
            or now > event["expires_at"] + self.max_clock_skew
        ):
            return 422, response("configuration_failure", "timestamp_out_of_window", event_id)
        if not self.provider_gate.acquire(blocking=False):
            return 503, response("retryable_failure", "provider_busy", event_id)

        try:
            try:
                with self.db_lock:
                    self.db.execute("BEGIN IMMEDIATE")
                    if not self._clock_is_trusted(now):
                        self._rollback()
                        return 503, response("retryable_failure", "clock_untrusted", event_id)
                    existing = self._lookup(device_id, event_id)
                    if existing:
                        self.db.commit()
                        return self._existing_result(existing, digest, signing_key, event_id)
                    self.db.execute(
                        "INSERT INTO test_events VALUES (?, ?, ?, 'provider_call_started', NULL, ?, ?)",
                        (device_id, event_id, digest, event["created_at"], now),
                    )
                    self.db.commit()
            except sqlite3.IntegrityError:
                with self.db_lock:
                    self._rollback()
                    existing = self._lookup(device_id, event_id)
                if existing is None:
                    return 503, response("retryable_failure", "persistence_unavailable", event_id)
                return self._existing_result(existing, digest, signing_key, event_id)
            except sqlite3.Error:
                with self.db_lock:
                    self._rollback()
                return 503, response("retryable_failure", "persistence_unavailable", event_id)

            try:
                provider_reference = self.provider.send(event_id)
            except ProviderFailure as exc:
                try:
                    with self.db_lock:
                        if exc.result == "configuration_failure":
                            self.db.execute(
                                "DELETE FROM test_events WHERE device_id = ? AND event_id = ?",
                                (device_id, event_id),
                            )
                        else:
                            self.db.execute(
                                "UPDATE test_events SET state = 'result_unknown', updated_at = ? WHERE device_id = ? AND event_id = ?",
                                (now, device_id, event_id),
                            )
                        self.db.commit()
                except sqlite3.Error:
                    with self.db_lock:
                        self._rollback()
                    return 500, response("result_unknown", "persistence_result_unknown", event_id)
                status = 502 if exc.result == "configuration_failure" else 504
                return status, response(exc.result, exc.code, event_id)
            except Exception:
                LOGGER.error("provider submission failed for event %s", event_id)
                try:
                    with self.db_lock:
                        self.db.execute(
                            "UPDATE test_events SET state = 'result_unknown', updated_at = ? WHERE device_id = ? AND event_id = ?",
                            (now, device_id, event_id),
                        )
                        self.db.commit()
                except sqlite3.Error:
                    with self.db_lock:
                        self._rollback()
                return 500, response("result_unknown", "internal_error", event_id)

            try:
                with self.db_lock:
                    changed = self.db.execute(
                        "UPDATE test_events SET state = 'provider_accepted', provider_reference = ?, updated_at = ? WHERE device_id = ? AND event_id = ? AND state = 'provider_call_started'",
                        (provider_reference, now, device_id, event_id),
                    ).rowcount
                    if changed != 1:
                        raise sqlite3.DatabaseError("event state changed unexpectedly")
                    self.db.commit()
            except sqlite3.Error:
                with self.db_lock:
                    self._rollback()
                return 500, response("result_unknown", "persistence_result_unknown", event_id)
            return 200, accepted_response(signing_key, event_id)
        finally:
            self.provider_gate.release()


def make_server(host: str, port: int, relay: Relay) -> ThreadingHTTPServer:
    class Server(RelayHTTPServer):
        next_cleanup = 0.0

        def service_actions(self):
            now = time.monotonic()
            if now >= self.next_cleanup:
                try:
                    relay.purge_expired()
                except sqlite3.Error:
                    LOGGER.error("expired-event cleanup failed")
                self.next_cleanup = now + 60

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        timeout = 5

        def version_string(self):
            return "relay"

        def log_message(self, _format, *_args):
            return

        def _send(self, status: int, payload: dict):
            encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if status == 503:
                self.send_header("Retry-After", "5")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)
            self.close_connection = True

        def do_POST(self):
            if self.path != EVENT_PATH:
                self._send(404, response("configuration_failure", "unknown_endpoint"))
                return
            if self.headers.get("Transfer-Encoding") is not None:
                self._send(400, response("configuration_failure", "unsupported_transfer_encoding"))
                return
            encodings = self.headers.get_all("Content-Encoding") or []
            if len(encodings) > 1 or (encodings and encodings[0].lower() != "identity"):
                self._send(415, response("configuration_failure", "unsupported_content_encoding"))
                return
            content_types = self.headers.get_all("Content-Type") or []
            if len(content_types) != 1 or self.headers.get_content_type() != "application/json":
                self._send(415, response("configuration_failure", "invalid_content_type"))
                return
            lengths = self.headers.get_all("Content-Length") or []
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
                self._send(411, response("configuration_failure", "invalid_content_length"))
                return
            length = int(lengths[0])
            if length < 1:
                self._send(400, response("configuration_failure", "empty_body"))
                return
            if length > MAX_BODY_BYTES:
                self._send(413, response("configuration_failure", "body_too_large"))
                return
            signatures = self.headers.get_all("X-SPB-Signature") or []
            signature = signatures[0] if len(signatures) == 1 else None
            try:
                body = self.rfile.read(length)
            except OSError:
                self._send(408, response("retryable_failure", "request_incomplete"))
                return
            if len(body) != length:
                self._send(408, response("retryable_failure", "request_incomplete"))
                return
            status, payload = relay.process(body, signature)
            self._send(status, payload)

        def do_GET(self):
            self._send(405, response("configuration_failure", "method_not_allowed"))

    return Server((host, port), Handler)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Smart Panic Button TEST relay")
    parser.add_argument("--devices", required=True, help="path to the device JSON file")
    parser.add_argument("--database", required=True, help="path to the SQLite idempotency ledger")
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--max-clock-skew", type=int, default=300)
    parser.add_argument("--provider-timeout", type=float, default=10)
    args = parser.parse_args(argv)

    if args.database == ":memory:":
        parser.error("--database must name a persistent SQLite file")

    try:
        devices = load_devices(args.devices)
        provider = PushoverClient(
            os.environ.get("PUSHOVER_APP_TOKEN", ""),
            os.environ.get("PUSHOVER_USER_KEY", ""),
            args.provider_timeout,
        )
        relay = Relay(devices, provider, args.database, args.max_clock_skew)
        server = make_server(args.listen, args.port, relay)
    except (OSError, ValueError, sqlite3.Error) as exc:
        parser.error(str(exc))

    LOGGER.warning("relay listening on %s:%s", args.listen, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        relay.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

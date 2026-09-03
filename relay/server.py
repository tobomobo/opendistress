# SPDX-License-Identifier: MIT
"""Durable TEST-event intake and SQLite outbox relay."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import stat
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .mailbox import MAX_BODY_BYTES as MAX_MAILBOX_BODY_BYTES
from .mailbox import MailboxStore, load_mailboxes
from .transports import (
    MAX_PROVIDER_RESPONSE_BYTES,
    NTFY_TOKEN_RE,
    TEST_MESSAGE,
    NtfyClient,
    ProviderFailure,
    PushoverClient,
    ReceiptEvidence,
    Submission,
)

MAX_BODY_BYTES = 1024
MAX_CREATED_AT = 2_147_482_747
MAX_V2_INTEGER = 2_147_483_647
STATUS_MAX_SKEW = 300
EVENT_PATH = "/v1/events"
LIVE_EVENT_PATH = "/v2/events"
STATUS_PATH = "/v2/status"
MAILBOX_PATH_RE = re.compile(
    r"^/mailbox/v1/([A-Za-z0-9_-]{22})/(messages|acknowledgements)$"
)
SCHEMA_VERSION = 5
ID_RE = re.compile(r"^[A-Za-z0-9_-]{22}$")
SIGNATURE_RE = re.compile(r"^v1=([A-Za-z0-9_-]{43})$")
LIVE_SIGNATURE_RE = re.compile(r"^v2=([A-Za-z0-9_-]{43})$")
KEY_RE = re.compile(r"^[0-9a-f]{64}$")
INTEGER_TOKEN_RE = re.compile(r"^(?:0|[1-9][0-9]*)$")
CONFIG_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
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
PAYLOAD_FIELDS = {"key_version", "iv", "ciphertext", "tag"}
STATUS_FIELDS = {
    "v",
    "request_id",
    "incident_id",
    "device_id",
    "created_at",
    "expires_at",
}
DUMMY_KEY = bytes(32)
PUBLIC_VECTOR_KEY = bytes(range(32))
LOGGER = logging.getLogger("opendistress-relay")


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


def _parse_json_integer(value: str) -> int:
    if not INTEGER_TOKEN_RE.fullmatch(value):
        raise ValueError("invalid JSON integer")
    return int(value)


def parse_json(data: bytes):
    return json.loads(
        data.decode("utf-8"),
        object_pairs_hook=_reject_duplicate_keys,
        parse_int=_parse_json_integer,
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
        if type(record) is not dict or set(record) not in (
            {"key", "enabled"},
            {"key", "live_key", "enabled"},
        ):
            raise ValueError(
                f"device {device_id!r} must contain key, optional live_key, and enabled"
            )
        if type(record["enabled"]) is not bool:
            raise ValueError(f"device {device_id!r} enabled must be boolean")
        devices[device_id] = {
            "key": decode_key(record["key"]),
            "live_key": decode_key(record["live_key"])
            if "live_key" in record
            else None,
            "enabled": record["enabled"],
        }
        if record["enabled"] and (
            devices[device_id]["key"] == PUBLIC_VECTOR_KEY
            or devices[device_id]["live_key"] == PUBLIC_VECTOR_KEY
        ):
            raise ValueError("enabled device configuration uses the public fixture key")
    if any(device["enabled"] for device in devices.values()):
        mode = stat.S_IMODE(config_path.stat().st_mode)
        if mode & 0o077:
            raise ValueError("enabled device configuration must have mode 0600")
    return devices


def _validate_routes(raw) -> dict:
    if type(raw) is not dict or set(raw) != {
        "test_emergency",
        "device_groups",
        "groups",
        "recipients",
    }:
        raise ValueError(
            "route configuration must contain only test_emergency, device_groups, groups, and recipients"
        )
    if type(raw["test_emergency"]) is not bool:
        raise ValueError("test_emergency must be boolean")
    device_groups = raw["device_groups"]
    groups = raw["groups"]
    recipients = raw["recipients"]
    if type(device_groups) is not dict or len(device_groups) > 1000:
        raise ValueError("device_groups must be an object with at most 1000 entries")
    if type(groups) is not dict or not 1 <= len(groups) <= 100:
        raise ValueError("groups must contain 1-100 groups")
    if type(recipients) is not dict or not 1 <= len(recipients) <= 100:
        raise ValueError("recipients must contain 1-100 recipients")
    for device_id, group_id in device_groups.items():
        if (
            not is_canonical_id(device_id)
            or type(group_id) is not str
            or not CONFIG_ID_RE.fullmatch(group_id)
        ):
            raise ValueError("invalid device-to-group mapping")
    for group_id, members in groups.items():
        if not CONFIG_ID_RE.fullmatch(group_id or ""):
            raise ValueError("invalid group ID")
        if (
            type(members) is not list
            or not 1 <= len(members) <= 16
            or any(
                type(member) is not str or not CONFIG_ID_RE.fullmatch(member)
                for member in members
            )
            or len(set(members)) != len(members)
        ):
            raise ValueError(f"group {group_id!r} must contain 1-16 unique recipient IDs")
    for recipient_id, record in recipients.items():
        if not CONFIG_ID_RE.fullmatch(recipient_id or ""):
            raise ValueError("invalid recipient ID")
        if type(record) is not dict or set(record) != {"enabled", "routes"}:
            raise ValueError(
                f"recipient {recipient_id!r} must contain only enabled and routes"
            )
        routes = record["routes"]
        if type(record["enabled"]) is not bool or type(routes) is not list:
            raise ValueError(f"recipient {recipient_id!r} has invalid enabled/routes")
        if not 1 <= len(routes) <= 2:
            raise ValueError(f"recipient {recipient_id!r} must contain 1-2 routes")
        transports = []
        for route in routes:
            if type(route) is not dict or type(route.get("transport")) is not str:
                raise ValueError(f"recipient {recipient_id!r} has an invalid route")
            transport = route["transport"]
            transports.append(transport)
            if transport == "pushover":
                if (
                    set(route) != {"transport", "user_key"}
                    or type(route.get("user_key")) is not str
                    or not re.fullmatch(r"[A-Za-z0-9]{30}", route["user_key"])
                ):
                    raise ValueError(f"recipient {recipient_id!r} has invalid Pushover route")
            elif transport == "ntfy":
                if set(route) != {"transport", "url", "topic", "token"}:
                    raise ValueError(f"recipient {recipient_id!r} has invalid ntfy route")
                parsed = urlsplit(route["url"] if type(route["url"]) is str else "")
                try:
                    port = parsed.port
                except ValueError:
                    port = -1
                if (
                    parsed.scheme != "https"
                    or not parsed.hostname
                    or parsed.username is not None
                    or parsed.password is not None
                    or parsed.query
                    or parsed.fragment
                    or parsed.path not in ("", "/")
                    or port in (-1, 0)
                    or parsed.netloc.endswith(":")
                    or type(route["topic"]) is not str
                    or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", route["topic"])
                    or type(route["token"]) is not str
                    or not NTFY_TOKEN_RE.fullmatch(route["token"])
                ):
                    raise ValueError(f"recipient {recipient_id!r} has invalid ntfy route")
            else:
                raise ValueError(f"recipient {recipient_id!r} has unknown transport")
        if len(set(transports)) != len(transports):
            raise ValueError(f"recipient {recipient_id!r} repeats a transport")
    for group_id, members in groups.items():
        if any(member not in recipients for member in members):
            raise ValueError(f"group {group_id!r} references an unknown recipient")
        if not any(recipients[member]["enabled"] for member in members):
            raise ValueError(f"group {group_id!r} has no enabled recipients")
    if any(group_id not in groups for group_id in device_groups.values()):
        raise ValueError("device_groups references an unknown group")
    return raw


def load_routes(path: str | os.PathLike[str]) -> dict:
    config_path = Path(path)
    if stat.S_IMODE(config_path.stat().st_mode) & 0o077:
        raise ValueError("route configuration must have mode 0600")
    if config_path.stat().st_size > 1_048_576:
        raise ValueError("route configuration is too large")
    return _validate_routes(parse_json(config_path.read_bytes()))


def build_transports(
    routes: dict,
    app_token: str,
    timeout: float = 10,
    emergency_retry: int = 60,
) -> dict[tuple[str, str], object]:
    clients = {}
    for recipient_id, record in routes["recipients"].items():
        if not record["enabled"]:
            continue
        for route in record["routes"]:
            if route["transport"] == "pushover":
                client = PushoverClient(
                    app_token,
                    route["user_key"],
                    timeout,
                    emergency_retry=emergency_retry,
                )
            else:
                client = NtfyClient(
                    route["url"], route["topic"], route["token"], timeout
                )
            clients[(recipient_id, route["transport"])] = client
    return clients


def _default_routes(devices: dict[str, dict]) -> dict:
    return {
        "test_emergency": False,
        "device_groups": {device_id: "legacy" for device_id in devices},
        "groups": {"legacy": ["legacy"]},
        "recipients": {
            "legacy": {
                "enabled": True,
                "routes": [{"transport": "pushover", "user_key": "0" * 30}],
            }
        },
    }


def signature_for(key: bytes, data: bytes, version: int = 1) -> str:
    digest = hmac.digest(key, data, "sha256")
    return f"v{version}=" + base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def signature_matches(
    header: str | None, key: bytes, data: bytes, version: int = 1
) -> bool:
    pattern = SIGNATURE_RE if version == 1 else LIVE_SIGNATURE_RE
    match = pattern.fullmatch(header or "")
    supplied = match.group(1) if match else "A" * 43
    expected = signature_for(key, data, version)[3:]
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


def is_canonical_bytes(value, size: int) -> bool:
    if not isinstance(value, str):
        return False
    encoded_length = (size * 8 + 5) // 6
    if len(value) != encoded_length or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, TypeError):
        return False
    return (
        len(decoded) == size
        and base64.urlsafe_b64encode(decoded).rstrip(b"=").decode() == value
    )


def validate_v2_event(event) -> str | None:
    if type(event) is not dict or set(event) != EVENT_FIELDS:
        return "invalid_event"
    payload = event.get("payload")
    if type(payload) is not dict or set(payload) != PAYLOAD_FIELDS:
        return "invalid_event"
    if type(event["v"]) is not int or event["v"] != 2:
        return "invalid_event"
    if not is_canonical_id(event["event_id"]) or not is_canonical_id(
        event["incident_id"]
    ):
        return "invalid_event"
    if not is_canonical_id(event["device_id"]):
        return "invalid_event"
    if type(event["kind"]) is not str or event["kind"] not in {
        "live.triggered",
        "location.updated",
    }:
        return "invalid_event"
    for name in ("sequence", "created_at", "expires_at"):
        if type(event[name]) is not int or not 0 <= event[name] <= MAX_V2_INTEGER:
            return "invalid_event"
    if not 1 <= event["expires_at"] - event["created_at"] <= 86_400:
        return "invalid_event"
    if (
        type(payload["key_version"]) is not int
        or not 1 <= payload["key_version"] <= MAX_V2_INTEGER
        or not is_canonical_bytes(payload["iv"], 16)
        or not is_canonical_bytes(payload["ciphertext"], 16)
        or not is_canonical_bytes(payload["tag"], 32)
    ):
        return "invalid_event"
    if event["kind"] == "live.triggered":
        if event["sequence"] != 0 or event["incident_id"] != event["event_id"]:
            return "invalid_event"
    elif event["sequence"] < 1 or event["event_id"] == event["incident_id"]:
        return "invalid_event"
    return None


def validate_status_query(query) -> str | None:
    if type(query) is not dict or set(query) != STATUS_FIELDS:
        return "invalid_status_query"
    if type(query["v"]) is not int or query["v"] != 2:
        return "invalid_status_query"
    if not all(
        is_canonical_id(query[name])
        for name in ("request_id", "incident_id", "device_id")
    ):
        return "invalid_status_query"
    if any(
        type(query[name]) is not int or not 0 <= query[name] <= MAX_V2_INTEGER
        for name in ("created_at", "expires_at")
    ):
        return "invalid_status_query"
    if query["created_at"] >= query["expires_at"]:
        return "invalid_status_query"
    return None


def canonical_event(event: dict) -> bytes:
    return (
        "opendistress.test.submit.v1\n"
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


def canonical_v2_event(event: dict) -> bytes:
    payload = event["payload"]
    return (
        "opendistress.submit.v2\n"
        "method=POST\n"
        "v=2\n"
        f"event_id={event['event_id']}\n"
        f"incident_id={event['incident_id']}\n"
        f"device_id={event['device_id']}\n"
        f"kind={event['kind']}\n"
        f"sequence={event['sequence']}\n"
        f"created_at={event['created_at']}\n"
        f"expires_at={event['expires_at']}\n"
        f"payload.key_version={payload['key_version']}\n"
        f"payload.iv={payload['iv']}\n"
        f"payload.ciphertext={payload['ciphertext']}\n"
        f"payload.tag={payload['tag']}\n"
    ).encode("ascii")


def canonical_status_query(query: dict) -> bytes:
    return (
        "opendistress.status.query.v2\n"
        "method=POST\n"
        "v=2\n"
        f"request_id={query['request_id']}\n"
        f"incident_id={query['incident_id']}\n"
        f"device_id={query['device_id']}\n"
        f"created_at={query['created_at']}\n"
        f"expires_at={query['expires_at']}\n"
    ).encode("ascii")


def canonical_status_result(
    request_id: str,
    incident_id: str,
    device_id: str,
    state: str,
    checked_at: int,
) -> bytes:
    return (
        "opendistress.status.result.v2\n"
        "v=2\n"
        f"request_id={request_id}\n"
        f"incident_id={incident_id}\n"
        f"device_id={device_id}\n"
        f"state={state}\n"
        f"checked_at={checked_at}\n"
    ).encode("ascii")


def canonical_result(event_id: str) -> bytes:
    return (
        "opendistress.test.intake-result.v1\n"
        "v=1\n"
        f"event_id={event_id}\n"
        "result=durably_accepted\n"
    ).encode("ascii")


def canonical_v2_result(event_id: str) -> bytes:
    return (
        "opendistress.result.v2\n"
        "v=2\n"
        f"event_id={event_id}\n"
        "result=durably_accepted\n"
    ).encode("ascii")


def response(
    result: str,
    code: str | None = None,
    event_id: str | None = None,
    *,
    version: int = 1,
    **extra,
):
    body = {"v": version}
    if event_id is not None:
        body["event_id"] = event_id
    body["result"] = result
    if code is not None:
        body["code"] = code
    body.update(extra)
    return body


def accepted_response(key: bytes, event_id: str, version: int = 1) -> dict:
    body = response("durably_accepted", event_id=event_id, version=version)
    canonical = canonical_result(event_id) if version == 1 else canonical_v2_result(event_id)
    body["response_signature"] = signature_for(key, canonical, version)
    return body


def signed_status_response(
    key: bytes,
    query: dict,
    state: str,
    checked_at: int,
) -> dict:
    body = {
        "v": 2,
        "request_id": query["request_id"],
        "incident_id": query["incident_id"],
        "device_id": query["device_id"],
        "state": state,
        "checked_at": checked_at,
    }
    body["response_signature"] = signature_for(
        key,
        canonical_status_result(
            query["request_id"],
            query["incident_id"],
            query["device_id"],
            state,
            checked_at,
        ),
        2,
    )
    return body


def _resolve_incident_locked(
    db: sqlite3.Connection, device_id: str, incident_id: str, now: int
) -> bool:
    exists = db.execute(
        "SELECT 1 FROM incidents WHERE device_id = ? AND incident_id = ?",
        (device_id, incident_id),
    ).fetchone()
    if exists is None:
        return False
    db.execute(
        """
        UPDATE incidents
        SET lifecycle = 'resolved', resolved_at = COALESCE(resolved_at, ?)
        WHERE device_id = ? AND incident_id = ?
        """,
        (now, device_id, incident_id),
    )
    db.execute(
        """
        UPDATE deliveries
        SET state = CASE WHEN may_have_accepted = 1
                         THEN 'result_unknown' ELSE 'resolved' END,
            cancellation_state = CASE
                WHEN may_have_accepted = 1 AND emergency = 1
                     AND transport = 'pushover' THEN 'result_unknown'
                WHEN may_have_accepted = 1 AND emergency = 1
                     AND transport = 'ntfy' THEN 'unsupported'
                ELSE cancellation_state END,
            next_attempt_at = NULL, updated_at = ?
        WHERE device_id = ? AND state IN ('pending', 'retry_wait') AND EXISTS (
            SELECT 1 FROM events e
            WHERE e.device_id = deliveries.device_id
              AND e.event_id = deliveries.event_id
              AND e.incident_id = ?
        )
        """,
        (now, device_id, incident_id),
    )
    db.execute(
        """
        UPDATE deliveries
        SET cancellation_state = 'pending', evidence_next_at = ?, updated_at = ?
        WHERE device_id = ? AND state = 'provider_accepted'
          AND transport = 'pushover' AND emergency = 1
          AND provider_receipt IS NOT NULL
          AND provider_cancelled_at IS NULL
          AND provider_expired_at IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM acknowledgements a
            JOIN events acknowledged_event
              ON acknowledged_event.device_id = deliveries.device_id
             AND acknowledged_event.event_id = deliveries.event_id
            WHERE a.device_id = deliveries.device_id
              AND a.incident_id = acknowledged_event.incident_id
              AND a.recipient_id = deliveries.recipient_id
          )
          AND EXISTS (
            SELECT 1 FROM events e
            WHERE e.device_id = deliveries.device_id
              AND e.event_id = deliveries.event_id
              AND e.incident_id = ?
          )
        """,
        (now, now, device_id, incident_id),
    )
    db.execute(
        """
        UPDATE deliveries
        SET cancellation_state = 'unsupported', updated_at = ?
        WHERE device_id = ? AND state = 'provider_accepted'
          AND transport = 'ntfy' AND emergency = 1 AND EXISTS (
            SELECT 1 FROM events e
            WHERE e.device_id = deliveries.device_id
              AND e.event_id = deliveries.event_id
              AND e.incident_id = ?
        )
        """,
        (now, device_id, incident_id),
    )
    db.execute(
        """
        UPDATE events SET opaque_json = NULL
        WHERE device_id = ? AND incident_id = ? AND kind = 'location.updated'
        """,
        (device_id, incident_id),
    )
    return True


class Relay:
    def __init__(
        self,
        devices: dict[str, dict],
        provider,
        database: str = ":memory:",
        max_clock_skew: int = 300,
        clock=time.time,
        lease_seconds: int = 75,
        retry_base: int = 5,
        routes: dict | None = None,
        transports: dict[tuple[str, str], object] | None = None,
        receipt_interval: int = 30,
        mailboxes: dict[str, dict] | None = None,
    ):
        if not 0 <= max_clock_skew <= 3600:
            raise ValueError("max clock skew must be between 0 and 3600 seconds")
        if not 5 <= lease_seconds <= 300:
            raise ValueError("delivery lease must be between 5 and 300 seconds")
        if not 5 <= retry_base <= 300:
            raise ValueError("retry base must be between 5 and 300 seconds")
        if not 5 <= receipt_interval <= 3600:
            raise ValueError("receipt interval must be between 5 and 3600 seconds")
        if database != ":memory:":
            fd = os.open(database, os.O_CREAT | os.O_RDWR, 0o600)
            os.close(fd)
            if stat.S_IMODE(os.stat(database).st_mode) & 0o077:
                raise ValueError("relay database must have mode 0600")
        self.devices = devices
        self.provider = provider
        self.routes = _validate_routes(
            routes if routes is not None else _default_routes(devices)
        )
        self.transports = dict(transports or {})
        if provider is not None:
            self.transports[("legacy", "pushover")] = provider
        self.receipt_interval = receipt_interval
        self._validate_runtime_routes()
        self.max_clock_skew = max_clock_skew
        self.clock = clock
        self.lease_seconds = lease_seconds
        self.retry_base = retry_base
        self.db_lock = threading.Lock()
        self.db = sqlite3.connect(database, timeout=5, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.mailbox = MailboxStore(self.db, self.db_lock, mailboxes, clock)
        try:
            self._initialize_schema()
            self._validate_outstanding_routes()
        except Exception:
            self.db.close()
            raise

    def _validate_runtime_routes(self):
        for client in self.transports.values():
            self._client_fingerprint(client)
        for device_id, device in self.devices.items():
            if not device["enabled"]:
                continue
            group_id = self.routes["device_groups"].get(device_id)
            if group_id is None:
                raise ValueError(f"enabled device {device_id!r} has no recipient group")
            for recipient_id in self.routes["groups"][group_id]:
                record = self.routes["recipients"][recipient_id]
                if not record["enabled"]:
                    continue
                for route in record["routes"]:
                    key = (recipient_id, route["transport"])
                    if key not in self.transports:
                        raise ValueError(
                            f"recipient {recipient_id!r} has no configured {route['transport']} client"
                        )

    @staticmethod
    def _client_fingerprint(client) -> bytes:
        fingerprint = getattr(client, "configuration_fingerprint", None)
        if type(fingerprint) is not bytes or len(fingerprint) != 32:
            raise ValueError(
                "transport clients require a stable 32-byte configuration fingerprint"
            )
        return fingerprint

    def _matching_client(self, row):
        client = self.transports.get((row["recipient_id"], row["transport"]))
        stored = row["provider_fingerprint"]
        if client is None or type(stored) is not bytes or len(stored) != 32:
            return None
        if not hmac.compare_digest(stored, self._client_fingerprint(client)):
            return None
        return client

    def _validate_outstanding_routes(self):
        snapshots = self.db.execute(
            """
            SELECT ir.recipient_id, ir.transport, ir.provider_fingerprint
            FROM incident_routes ir JOIN incidents i
              ON i.device_id = ir.device_id AND i.incident_id = ir.incident_id
            WHERE i.lifecycle = 'active'
            """
        ).fetchall()
        outstanding = self.db.execute(
            """
            SELECT recipient_id, transport, provider_fingerprint FROM deliveries
            WHERE state IN ('pending', 'attempting', 'retry_wait')
               OR (state = 'provider_accepted' AND provider_receipt IS NOT NULL
                   AND evidence_next_at IS NOT NULL)
            """
        ).fetchall()
        for row in (*snapshots, *outstanding):
            if self._matching_client(row) is None:
                raise ValueError(
                    f"active delivery route {row['recipient_id']!r}/{row['transport']} "
                    "does not match the configured provider"
                )

    def _initialize_schema(self):
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                version = self.db.execute("PRAGMA user_version").fetchone()[0]
                if version not in (0, 2, 3, 4, SCHEMA_VERSION):
                    raise ValueError(f"unsupported relay database schema version {version}")
                for statement in (
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        device_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        canonical_sha256 BLOB NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        accepted_at INTEGER NOT NULL,
                        PRIMARY KEY (device_id, event_id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS deliveries (
                        delivery_id INTEGER PRIMARY KEY,
                        device_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (state IN (
                            'pending', 'attempting', 'retry_wait',
                            'provider_accepted', 'configuration_failure',
                            'result_unknown', 'expired'
                        )),
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at INTEGER,
                        lease_token TEXT,
                        lease_until INTEGER,
                        may_have_accepted INTEGER NOT NULL DEFAULT 0 CHECK (may_have_accepted IN (0, 1)),
                        provider_reference TEXT,
                        last_code TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        UNIQUE (device_id, event_id),
                        FOREIGN KEY (device_id, event_id)
                            REFERENCES events (device_id, event_id) ON DELETE CASCADE
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS delivery_attempts (
                        delivery_id INTEGER NOT NULL,
                        attempt_no INTEGER NOT NULL,
                        claim_token TEXT NOT NULL UNIQUE,
                        started_at INTEGER NOT NULL,
                        finished_at INTEGER,
                        outcome TEXT NOT NULL,
                        ambiguous INTEGER NOT NULL DEFAULT 0 CHECK (ambiguous IN (0, 1)),
                        code TEXT,
                        provider_reference TEXT,
                        PRIMARY KEY (delivery_id, attempt_no),
                        FOREIGN KEY (delivery_id) REFERENCES deliveries (delivery_id) ON DELETE CASCADE
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS relay_state (
                        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                        clock_high_water INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS deliveries_due ON deliveries (state, next_attempt_at)",
                ):
                    self.db.execute(statement)
                columns = {
                    row["name"] for row in self.db.execute("PRAGMA table_info(events)")
                }
                additions = {
                    "protocol_version": "INTEGER NOT NULL DEFAULT 1",
                    "incident_id": "TEXT",
                    "kind": "TEXT NOT NULL DEFAULT 'test.triggered'",
                    "sequence": "INTEGER NOT NULL DEFAULT 0",
                    "opaque_json": "BLOB",
                }
                missing = set(additions) - columns
                if missing and version == SCHEMA_VERSION:
                    raise sqlite3.DatabaseError("v3 event schema is incomplete")
                for name in additions:
                    if name in missing:
                        self.db.execute(
                            f"ALTER TABLE events ADD COLUMN {name} {additions[name]}"
                        )
                self.db.execute(
                    "UPDATE events SET incident_id = event_id WHERE incident_id IS NULL"
                )
                self.db.execute(
                    """
                    CREATE TABLE IF NOT EXISTS incidents (
                        device_id TEXT NOT NULL,
                        incident_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        next_sequence INTEGER NOT NULL,
                        lifecycle TEXT NOT NULL DEFAULT 'active'
                            CHECK (lifecycle IN ('active', 'resolved', 'expired')),
                        resolved_at INTEGER,
                        PRIMARY KEY (device_id, incident_id)
                    )
                    """
                )
                incident_columns = {
                    row["name"]
                    for row in self.db.execute("PRAGMA table_info(incidents)")
                }
                incident_additions = {
                    "protocol_version": "INTEGER NOT NULL DEFAULT 2",
                    "lifecycle": "TEXT NOT NULL DEFAULT 'active'",
                    "resolved_at": "INTEGER",
                }
                incident_missing = set(incident_additions) - incident_columns
                if incident_missing and version == SCHEMA_VERSION:
                    raise sqlite3.DatabaseError("v4 incident schema is incomplete")
                for name in incident_additions:
                    if name in incident_missing:
                        self.db.execute(
                            f"ALTER TABLE incidents ADD COLUMN {name} {incident_additions[name]}"
                        )
                self.db.execute(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS events_incident_sequence
                    ON events (device_id, incident_id, sequence)
                    WHERE protocol_version = 2
                    """
                )
                self.db.execute(
                    "INSERT OR IGNORE INTO relay_state (singleton, clock_high_water) VALUES (1, 0)"
                )
                if self.db.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='test_events'"
                ).fetchone():
                    self._migrate_phase1_locked()
                self._create_recipient_schema_locked()
                self.mailbox.initialize_schema_locked()
                route_columns = {
                    row["name"]
                    for row in self.db.execute("PRAGMA table_info(incident_routes)")
                }
                if "provider_fingerprint" not in route_columns:
                    if version == SCHEMA_VERSION:
                        raise sqlite3.DatabaseError("v5 incident route schema is incomplete")
                    self.db.execute(
                        "ALTER TABLE incident_routes ADD COLUMN provider_fingerprint BLOB"
                    )
                delivery_columns = {
                    row["name"]
                    for row in self.db.execute("PRAGMA table_info(deliveries)")
                }
                required_delivery_columns = {
                    "recipient_id",
                    "transport",
                    "emergency",
                    "provider_receipt",
                    "evidence_next_at",
                    "evidence_action",
                    "provider_last_delivered_at",
                    "provider_expired_at",
                    "provider_cancelled_at",
                    "cancellation_state",
                }
                if not required_delivery_columns <= delivery_columns:
                    if version == SCHEMA_VERSION:
                        raise sqlite3.DatabaseError("v4 delivery schema is incomplete")
                    if "recipient_id" in delivery_columns:
                        raise sqlite3.DatabaseError("unsupported partial v4 delivery schema")
                    self._migrate_deliveries_v4_locked()
                    delivery_columns = {
                        row["name"]
                        for row in self.db.execute("PRAGMA table_info(deliveries)")
                    }
                if "provider_fingerprint" not in delivery_columns:
                    if version == SCHEMA_VERSION:
                        raise sqlite3.DatabaseError("v5 delivery schema is incomplete")
                    self.db.execute(
                        "ALTER TABLE deliveries ADD COLUMN provider_fingerprint BLOB"
                    )
                self.db.execute(
                    """
                    INSERT OR IGNORE INTO incident_recipients
                        (device_id, incident_id, group_id, recipient_id)
                    SELECT i.device_id, i.incident_id, 'legacy', 'legacy'
                    FROM incidents i
                    WHERE NOT EXISTS (
                        SELECT 1 FROM incident_recipients ir
                        WHERE ir.device_id = i.device_id
                          AND ir.incident_id = i.incident_id
                    )
                    """
                )
                self.db.execute(
                    """
                    INSERT OR IGNORE INTO incident_routes
                        (device_id, incident_id, recipient_id, transport,
                         provider_fingerprint)
                    SELECT i.device_id, i.incident_id, 'legacy', 'pushover', NULL
                    FROM incidents i
                    WHERE NOT EXISTS (
                        SELECT 1 FROM incident_routes ir
                        WHERE ir.device_id = i.device_id
                          AND ir.incident_id = i.incident_id
                    )
                    """
                )
                schema_now = int(self.clock())
                if self._clock_is_trusted(schema_now):
                    self.db.execute(
                        """
                        UPDATE incidents SET lifecycle = 'expired'
                        WHERE lifecycle = 'active' AND expires_at <= ?
                        """,
                        (schema_now,),
                    )
                self.db.execute(
                    """
                    UPDATE incidents SET lifecycle = 'expired'
                    WHERE protocol_version = 2 AND lifecycle = 'active'
                      AND EXISTS (
                        SELECT 1 FROM incidents newer
                        WHERE newer.device_id = incidents.device_id
                          AND newer.protocol_version = 2
                          AND newer.lifecycle = 'active'
                          AND (
                            newer.created_at > incidents.created_at
                            OR (newer.created_at = incidents.created_at
                                AND newer.incident_id > incidents.incident_id)
                          )
                      )
                    """
                )
                if self._clock_is_trusted(schema_now):
                    self._clear_opaque_content_locked(schema_now)
                inactive_deliveries = self.db.execute(
                    """
                    SELECT delivery_id, recipient_id, transport,
                           provider_fingerprint, may_have_accepted
                    FROM deliveries
                    WHERE state IN ('pending', 'retry_wait') AND EXISTS (
                        SELECT 1 FROM events e JOIN incidents i
                          ON i.device_id = e.device_id
                         AND i.incident_id = e.incident_id
                        WHERE e.device_id = deliveries.device_id
                          AND e.event_id = deliveries.event_id
                          AND i.lifecycle = 'expired'
                    )
                    """
                ).fetchall()
                for row in inactive_deliveries:
                    if self._matching_client(row) is not None:
                        self.db.execute(
                            """
                            UPDATE deliveries
                            SET state = ?, next_attempt_at = NULL
                            WHERE delivery_id = ?
                              AND state IN ('pending', 'retry_wait')
                            """,
                            (
                                "result_unknown"
                                if row["may_have_accepted"]
                                else "expired",
                                row["delivery_id"],
                            ),
                        )
                if self._clock_is_trusted(schema_now):
                    self._purge_retained_rows_locked(schema_now)
                self.db.execute(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS one_active_v2_incident_per_device
                    ON incidents (device_id)
                    WHERE protocol_version = 2 AND lifecycle = 'active'
                    """
                )
                self.db.execute(
                    "CREATE INDEX IF NOT EXISTS deliveries_due ON deliveries (state, next_attempt_at)"
                )
                self.db.execute(
                    "CREATE INDEX IF NOT EXISTS receipt_evidence_due ON deliveries (evidence_next_at) WHERE provider_receipt IS NOT NULL"
                )
                self.db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
                self.db.commit()
            except Exception:
                self._rollback()
                raise

    def _create_recipient_schema_locked(self):
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS incident_recipients (
                device_id TEXT NOT NULL,
                incident_id TEXT NOT NULL,
                group_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                PRIMARY KEY (device_id, incident_id, recipient_id),
                FOREIGN KEY (device_id, incident_id)
                    REFERENCES incidents (device_id, incident_id) ON DELETE CASCADE
            )
            """
        )
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS incident_routes (
                device_id TEXT NOT NULL,
                incident_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                transport TEXT NOT NULL,
                provider_fingerprint BLOB,
                PRIMARY KEY (device_id, incident_id, recipient_id, transport),
                FOREIGN KEY (device_id, incident_id, recipient_id)
                    REFERENCES incident_recipients
                        (device_id, incident_id, recipient_id) ON DELETE CASCADE
            )
            """
        )
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS acknowledgements (
                device_id TEXT NOT NULL,
                incident_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                source TEXT NOT NULL,
                acknowledged_at INTEGER NOT NULL,
                observed_at INTEGER NOT NULL,
                provider_reference TEXT NOT NULL,
                PRIMARY KEY (
                    device_id, incident_id, recipient_id, source,
                    provider_reference
                ),
                FOREIGN KEY (device_id, incident_id, recipient_id)
                    REFERENCES incident_recipients
                        (device_id, incident_id, recipient_id) ON DELETE CASCADE
            )
            """
        )

    def _migrate_deliveries_v4_locked(self):
        self.db.execute(
            """
            CREATE TABLE deliveries_v4 (
                delivery_id INTEGER PRIMARY KEY,
                device_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                recipient_id TEXT NOT NULL,
                transport TEXT NOT NULL,
                provider_fingerprint BLOB,
                emergency INTEGER NOT NULL DEFAULT 0 CHECK (emergency IN (0, 1)),
                state TEXT NOT NULL CHECK (state IN (
                    'pending', 'attempting', 'retry_wait', 'provider_accepted',
                    'configuration_failure', 'result_unknown', 'expired', 'resolved'
                )),
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER,
                lease_token TEXT,
                lease_until INTEGER,
                may_have_accepted INTEGER NOT NULL DEFAULT 0 CHECK (may_have_accepted IN (0, 1)),
                provider_reference TEXT,
                provider_receipt TEXT,
                evidence_next_at INTEGER,
                evidence_action TEXT CHECK (evidence_action IN ('poll', 'cancel')),
                provider_last_delivered_at INTEGER,
                provider_expired_at INTEGER,
                provider_cancelled_at INTEGER,
                cancellation_state TEXT CHECK (cancellation_state IN (
                    'pending', 'cancelled', 'configuration_failure',
                    'result_unknown', 'unsupported'
                )),
                last_code TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (device_id, event_id, recipient_id, transport),
                FOREIGN KEY (device_id, event_id)
                    REFERENCES events (device_id, event_id) ON DELETE CASCADE
            )
            """
        )
        self.db.execute(
            """
            INSERT INTO deliveries_v4
                (delivery_id, device_id, event_id, recipient_id, transport,
                 provider_fingerprint, emergency, state, attempt_count,
                 next_attempt_at, lease_token,
                 lease_until, may_have_accepted, provider_reference, last_code,
                 created_at, updated_at)
            SELECT delivery_id, device_id, event_id, 'legacy', 'pushover', NULL,
                   0, state, attempt_count, next_attempt_at, lease_token,
                   lease_until, may_have_accepted, provider_reference, last_code,
                   created_at, updated_at
            FROM deliveries
            """
        )
        self.db.execute(
            """
            CREATE TABLE delivery_attempts_v4 (
                delivery_id INTEGER NOT NULL,
                attempt_no INTEGER NOT NULL,
                claim_token TEXT NOT NULL UNIQUE,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                outcome TEXT NOT NULL,
                ambiguous INTEGER NOT NULL DEFAULT 0 CHECK (ambiguous IN (0, 1)),
                code TEXT,
                provider_reference TEXT,
                PRIMARY KEY (delivery_id, attempt_no),
                FOREIGN KEY (delivery_id) REFERENCES deliveries_v4 (delivery_id) ON DELETE CASCADE
            )
            """
        )
        self.db.execute(
            "INSERT INTO delivery_attempts_v4 SELECT * FROM delivery_attempts"
        )
        self.db.execute("DROP TABLE delivery_attempts")
        self.db.execute("DROP TABLE deliveries")
        self.db.execute("ALTER TABLE deliveries_v4 RENAME TO deliveries")
        self.db.execute("ALTER TABLE delivery_attempts_v4 RENAME TO delivery_attempts")

    def _migrate_phase1_locked(self):
        """Preserve old claims without ever turning them into new outbound work."""
        rows = self.db.execute(
            "SELECT device_id, event_id, canonical_sha256, state, provider_reference, created_at, updated_at FROM test_events"
        ).fetchall()
        for row in rows:
            self.db.execute(
                """
                INSERT OR IGNORE INTO events
                    (device_id, event_id, canonical_sha256, created_at, expires_at,
                     accepted_at, protocol_version, incident_id, kind, sequence,
                     opaque_json)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, 'test.triggered', 0, NULL)
                """,
                (
                    row["device_id"],
                    row["event_id"],
                    row["canonical_sha256"],
                    row["created_at"],
                    row["created_at"] + 900,
                    row["updated_at"],
                    row["event_id"],
                ),
            )
            accepted = row["state"] == "provider_accepted"
            state = "provider_accepted" if accepted else "result_unknown"
            cursor = self.db.execute(
                """
                INSERT OR IGNORE INTO deliveries
                    (device_id, event_id, state, attempt_count, next_attempt_at,
                     may_have_accepted, provider_reference, last_code, created_at, updated_at)
                VALUES (?, ?, ?, 1, NULL, ?, ?, ?, ?, ?)
                """,
                (
                    row["device_id"],
                    row["event_id"],
                    state,
                    0 if accepted else 1,
                    row["provider_reference"],
                    None if accepted else "phase1_attempt_unknown",
                    row["created_at"],
                    row["updated_at"],
                ),
            )
            if cursor.rowcount:
                delivery_id = cursor.lastrowid
                self.db.execute(
                    "INSERT INTO delivery_attempts VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        delivery_id,
                        "migrated-" + secrets.token_hex(16),
                        row["created_at"],
                        row["updated_at"],
                        state,
                        0 if accepted else 1,
                        None if accepted else "phase1_attempt_unknown",
                        row["provider_reference"],
                    ),
                )
        copied = self.db.execute("SELECT COUNT(*) FROM events").fetchone()[0]
        if copied < len(rows):
            raise sqlite3.DatabaseError("phase-1 migration row-count mismatch")
        self.db.execute("DROP TABLE test_events")

    def close(self):
        with self.db_lock:
            self.db.close()

    def _lookup(self, device_id: str, event_id: str):
        return self.db.execute(
            "SELECT canonical_sha256 FROM events WHERE device_id = ? AND event_id = ?",
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

    def _clear_opaque_content_locked(self, now: int):
        self.db.execute(
            """
            UPDATE events SET opaque_json = NULL
            WHERE kind = 'location.updated' AND opaque_json IS NOT NULL
              AND (expires_at <= ? OR created_at <= ?)
            """,
            (now, now - 86_400),
        )
        self.db.execute(
            """
            UPDATE events SET opaque_json = NULL
            WHERE kind = 'live.triggered' AND opaque_json IS NOT NULL
              AND EXISTS (
                SELECT 1 FROM incidents i
                WHERE i.device_id = events.device_id
                  AND i.incident_id = events.incident_id
                  AND i.lifecycle = 'resolved' AND i.resolved_at <= ?
              )
            """,
            (now - 86_400,),
        )
        cleared = self.db.execute(
            """
            SELECT delivery_id, recipient_id, transport, provider_fingerprint
            FROM deliveries
            WHERE state IN ('pending', 'retry_wait') AND EXISTS (
                SELECT 1 FROM events e
                WHERE e.device_id = deliveries.device_id
                  AND e.event_id = deliveries.event_id
                  AND e.kind = 'location.updated' AND e.opaque_json IS NULL
            )
            """,
        ).fetchall()
        for row in cleared:
            if self._matching_client(row) is not None:
                self.db.execute(
                    """
                    UPDATE deliveries
                    SET state = CASE WHEN may_have_accepted = 1
                                     THEN 'result_unknown' ELSE 'expired' END,
                        next_attempt_at = NULL,
                        last_code = 'opaque_payload_cleared', updated_at = ?
                    WHERE delivery_id = ? AND state IN ('pending', 'retry_wait')
                    """,
                    (now, row["delivery_id"]),
                )

    def _purge_retained_rows_locked(self, now: int):
        self.db.execute(
            "DELETE FROM events WHERE expires_at <= ?", (now - 86_400,)
        )
        self.db.execute(
            "DELETE FROM incidents WHERE expires_at <= ?", (now - 86_400,)
        )

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
                    """
                    UPDATE incidents SET lifecycle = 'expired'
                    WHERE lifecycle = 'active' AND expires_at <= ?
                    """,
                    (now,),
                )
                self._clear_opaque_content_locked(now)
                self._purge_retained_rows_locked(now)
                self.mailbox.purge_locked(now)
                self.db.commit()
            except sqlite3.Error:
                self._rollback()
                raise

    def _existing_result(
        self, row, digest: bytes, key: bytes, event_id: str, version: int
    ):
        if not hmac.compare_digest(row["canonical_sha256"], digest):
            return 409, response(
                "configuration_failure",
                "event_id_conflict",
                event_id,
                version=version,
            )
        return 202, accepted_response(key, event_id, version)

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
        if (
            device is None
            or not device["enabled"]
            or not authenticated
        ):
            return 401, response("configuration_failure", "authentication_failed")

        return self._accept_event(event, canonical, signing_key, 1, None)

    def process_v2(self, body: bytes, signature: str | None) -> tuple[int, dict]:
        try:
            event = parse_json(body)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return 400, response(
                "configuration_failure", "invalid_json", version=2
            )
        if validate_v2_event(event):
            return 422, response(
                "configuration_failure", "invalid_event", version=2
            )
        canonical = canonical_v2_event(event)
        device = self.devices.get(event["device_id"])
        signing_key = device.get("live_key") if device else None
        verification_key = signing_key or DUMMY_KEY
        authenticated = signature_matches(signature, verification_key, canonical, 2)
        if (
            device is None
            or not device["enabled"]
            or signing_key is None
            or not authenticated
        ):
            return 401, response(
                "configuration_failure", "authentication_failed", version=2
            )
        opaque = json.dumps(event, separators=(",", ":")).encode("ascii")
        return self._accept_event(event, canonical, signing_key, 2, opaque)

    def process_status(self, body: bytes, signature: str | None) -> tuple[int, dict]:
        try:
            query = parse_json(body)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return 400, response(
                "configuration_failure", "invalid_json", version=2
            )
        if validate_status_query(query):
            return 422, response(
                "configuration_failure", "invalid_status_query", version=2
            )
        canonical = canonical_status_query(query)
        device = self.devices.get(query["device_id"])
        signing_key = device.get("live_key") if device else None
        verification_key = signing_key or DUMMY_KEY
        authenticated = signature_matches(
            signature, verification_key, canonical, 2
        )
        if (
            device is None
            or not device["enabled"]
            or signing_key is None
            or not authenticated
        ):
            return 401, response(
                "configuration_failure", "authentication_failed", version=2
            )

        now = int(self.clock())
        if not 0 <= now <= MAX_V2_INTEGER:
            return 503, response(
                "retryable_failure", "clock_untrusted", version=2
            )
        try:
            with self.db_lock:
                self.db.execute("BEGIN")
                if not self._clock_is_trusted(now):
                    self._rollback()
                    return 503, response(
                        "retryable_failure", "clock_untrusted", version=2
                    )
                if not (
                    query["created_at"] <= now + STATUS_MAX_SKEW
                    and now <= query["created_at"] + STATUS_MAX_SKEW
                ):
                    self._rollback()
                    return 422, response(
                        "configuration_failure",
                        "timestamp_out_of_window",
                        version=2,
                    )
                incident = self.db.execute(
                    """
                    SELECT i.expires_at, i.lifecycle, EXISTS (
                        SELECT 1 FROM acknowledgements a
                        WHERE a.device_id = i.device_id
                          AND a.incident_id = i.incident_id
                    ) AS acknowledged
                    FROM incidents i
                    WHERE i.device_id = ? AND i.incident_id = ?
                      AND i.protocol_version = 2
                    """,
                    (query["device_id"], query["incident_id"]),
                ).fetchone()
                self.db.commit()
        except sqlite3.Error:
            with self.db_lock:
                self._rollback()
            return 503, response(
                "retryable_failure", "persistence_unavailable", version=2
            )
        if incident is None:
            return 409, response(
                "configuration_failure", "incident_not_found", version=2
            )
        if query["expires_at"] != incident["expires_at"]:
            return 409, response(
                "configuration_failure", "incident_expiry_conflict", version=2
            )
        if incident["lifecycle"] == "resolved":
            state = "resolved"
        elif incident["lifecycle"] == "expired" or now >= incident["expires_at"]:
            state = "expired"
        elif incident["acknowledged"]:
            state = "acknowledged"
        else:
            state = "active"
        return 200, signed_status_response(signing_key, query, state, now)

    def _accept_event(
        self,
        event: dict,
        canonical: bytes,
        signing_key: bytes,
        version: int,
        opaque_json: bytes | None,
    ) -> tuple[int, dict]:
        device_id = event["device_id"]
        event_id = event["event_id"]
        digest = hashlib.sha256(canonical).digest()
        now = int(self.clock())
        try:
            with self.db_lock:
                self.db.execute("BEGIN IMMEDIATE")
                existing = self._lookup(device_id, event_id)
                if existing:
                    self.db.commit()
                    return self._existing_result(
                        existing, digest, signing_key, event_id, version
                    )
                if not self._clock_is_trusted(now):
                    self._rollback()
                    return 503, response(
                        "retryable_failure",
                        "clock_untrusted",
                        event_id,
                        version=version,
                    )
                if (
                    event["created_at"] > now + self.max_clock_skew
                    or now >= event["expires_at"]
                ):
                    self._rollback()
                    return 422, response(
                        "configuration_failure",
                        "timestamp_out_of_window",
                        event_id,
                        version=version,
                    )
                if event["kind"] in {"test.triggered", "live.triggered"}:
                    if event["kind"] == "live.triggered":
                        self.db.execute(
                            """
                            UPDATE incidents SET lifecycle = 'expired'
                            WHERE device_id = ? AND protocol_version = 2
                              AND lifecycle = 'active' AND expires_at <= ?
                            """,
                            (device_id, now),
                        )
                        active = self.db.execute(
                            """
                            SELECT 1 FROM incidents
                            WHERE device_id = ? AND protocol_version = 2
                              AND lifecycle = 'active'
                            """,
                            (device_id,),
                        ).fetchone()
                        if active is not None:
                            self._rollback()
                            return 409, response(
                                "configuration_failure",
                                "active_incident_exists",
                                event_id,
                                version=2,
                            )
                    self.db.execute(
                        """
                        INSERT INTO incidents
                            (device_id, incident_id, created_at, expires_at,
                             next_sequence, protocol_version, lifecycle)
                        VALUES (?, ?, ?, ?, 1, ?, 'active')
                        """,
                        (
                            device_id,
                            event["incident_id"],
                            event["created_at"],
                            event["expires_at"],
                            version,
                        ),
                    )
                    delivery_routes = self._snapshot_group_locked(event)
                else:
                    incident = self.db.execute(
                        """
                        SELECT created_at, expires_at, next_sequence, lifecycle,
                               protocol_version
                        FROM incidents WHERE device_id = ? AND incident_id = ?
                        """,
                        (device_id, event["incident_id"]),
                    ).fetchone()
                    if incident is None:
                        self._rollback()
                        return 409, response(
                            "configuration_failure",
                            "incident_not_found",
                            event_id,
                            version=2,
                        )
                    if incident["protocol_version"] != 2:
                        self._rollback()
                        return 409, response(
                            "configuration_failure",
                            "incident_version_conflict",
                            event_id,
                            version=2,
                        )
                    if (
                        incident["lifecycle"] == "active"
                        and incident["expires_at"] <= now
                    ):
                        self.db.execute(
                            """
                            UPDATE incidents SET lifecycle = 'expired'
                            WHERE device_id = ? AND incident_id = ?
                              AND lifecycle = 'active'
                            """,
                            (device_id, event["incident_id"]),
                        )
                        self.db.commit()
                        return 409, response(
                            "configuration_failure",
                            "incident_expired",
                            event_id,
                            version=2,
                        )
                    if incident["lifecycle"] != "active":
                        self._rollback()
                        return 409, response(
                            "configuration_failure",
                            (
                                "incident_resolved"
                                if incident["lifecycle"] == "resolved"
                                else "incident_expired"
                            ),
                            event_id,
                            version=2,
                        )
                    if event["expires_at"] != incident["expires_at"]:
                        self._rollback()
                        return 409, response(
                            "configuration_failure",
                            "incident_expiry_conflict",
                            event_id,
                            version=2,
                        )
                    if event["created_at"] < incident["created_at"]:
                        self._rollback()
                        return 409, response(
                            "configuration_failure",
                            "incident_chronology_conflict",
                            event_id,
                            version=2,
                        )
                    delivery_routes = self.db.execute(
                        """
                        SELECT recipient_id, transport, provider_fingerprint
                        FROM incident_routes
                        WHERE device_id = ? AND incident_id = ?
                        ORDER BY recipient_id, transport
                        """,
                        (device_id, event["incident_id"]),
                    ).fetchall()
                    if not delivery_routes:
                        raise sqlite3.DatabaseError("incident has no route snapshot")
                    if event["sequence"] != incident["next_sequence"]:
                        self._rollback()
                        return 409, response(
                            "configuration_failure",
                            "incident_sequence_conflict",
                            event_id,
                            version=2,
                        )
                self.db.execute(
                    """
                    INSERT INTO events
                        (device_id, event_id, canonical_sha256, created_at, expires_at,
                         accepted_at, protocol_version, incident_id, kind, sequence,
                         opaque_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        device_id,
                        event_id,
                        digest,
                        event["created_at"],
                        event["expires_at"],
                        now,
                        version,
                        event["incident_id"],
                        event["kind"],
                        event["sequence"],
                        opaque_json,
                    ),
                )
                if version == 2 and event["kind"] == "location.updated":
                    changed = self.db.execute(
                        """
                        UPDATE incidents
                        SET next_sequence = next_sequence + 1
                        WHERE device_id = ? AND incident_id = ? AND next_sequence = ?
                        """,
                        (
                            device_id,
                            event["incident_id"],
                            event["sequence"],
                        ),
                    ).rowcount
                    if changed != 1:
                        raise sqlite3.IntegrityError("incident sequence changed")
                emergency = int(
                    event["kind"] == "live.triggered"
                    or (
                        event["kind"] == "test.triggered"
                        and self.routes["test_emergency"]
                    )
                )
                for route in delivery_routes:
                    self.db.execute(
                        """
                        INSERT INTO deliveries
                            (device_id, event_id, recipient_id, transport,
                             provider_fingerprint, emergency, state,
                             next_attempt_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?, ?)
                        """,
                        (
                            device_id,
                            event_id,
                            route["recipient_id"],
                            route["transport"],
                            route["provider_fingerprint"],
                            emergency,
                            now,
                            now,
                            now,
                        ),
                    )
                self.db.commit()
        except sqlite3.IntegrityError:
            with self.db_lock:
                self._rollback()
                existing = self._lookup(device_id, event_id)
            if existing is None:
                return 503, response(
                    "retryable_failure",
                    "persistence_unavailable",
                    event_id,
                    version=version,
                )
            return self._existing_result(
                existing, digest, signing_key, event_id, version
            )
        except sqlite3.Error:
            with self.db_lock:
                self._rollback()
            return 503, response(
                "retryable_failure",
                "persistence_unavailable",
                event_id,
                version=version,
            )
        return 202, accepted_response(signing_key, event_id, version)

    def _snapshot_group_locked(self, event: dict):
        group_id = self.routes["device_groups"][event["device_id"]]
        routes = []
        for recipient_id in self.routes["groups"][group_id]:
            record = self.routes["recipients"][recipient_id]
            if not record["enabled"]:
                continue
            self.db.execute(
                """
                INSERT INTO incident_recipients
                    (device_id, incident_id, group_id, recipient_id)
                VALUES (?, ?, ?, ?)
                """,
                (
                    event["device_id"],
                    event["incident_id"],
                    group_id,
                    recipient_id,
                ),
            )
            for route in record["routes"]:
                transport = route["transport"]
                fingerprint = self._client_fingerprint(
                    self.transports[(recipient_id, transport)]
                )
                self.db.execute(
                    """
                    INSERT INTO incident_routes
                        (device_id, incident_id, recipient_id, transport,
                         provider_fingerprint)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        event["device_id"],
                        event["incident_id"],
                        recipient_id,
                        transport,
                        fingerprint,
                    ),
                )
                routes.append(
                    {
                        "recipient_id": recipient_id,
                        "transport": transport,
                        "provider_fingerprint": fingerprint,
                    }
                )
        if not routes:
            raise sqlite3.DatabaseError("recipient group has no active routes")
        return routes

    def resolve_incident(self, device_id: str, incident_id: str) -> bool:
        if not is_canonical_id(device_id) or not is_canonical_id(incident_id):
            raise ValueError("resolution requires canonical device and incident IDs")
        now = int(self.clock())
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                resolved = _resolve_incident_locked(
                    self.db, device_id, incident_id, now
                )
                self.db.commit()
                return resolved
            except sqlite3.Error:
                self._rollback()
                raise

    def _recover_and_expire_locked(self, now: int):
        # A lease can expire before or after the provider accepted the request.
        # Retrying is deliberately at-least-once and may create a duplicate TEST.
        self._clear_opaque_content_locked(now)
        expired_claims = self.db.execute(
            """
            SELECT d.delivery_id, d.recipient_id, d.transport,
                   d.provider_fingerprint, e.expires_at, e.kind, e.opaque_json,
                   i.lifecycle
            FROM deliveries d JOIN events e USING (device_id, event_id)
            LEFT JOIN incidents i
              ON i.device_id = e.device_id AND i.incident_id = e.incident_id
            WHERE d.state = 'attempting' AND d.lease_until <= ?
            """,
            (now,),
        ).fetchall()
        for row in expired_claims:
            if self._matching_client(row) is None:
                continue
            self.db.execute(
                """
                UPDATE delivery_attempts
                SET finished_at = ?, outcome = 'result_unknown', ambiguous = 1,
                    code = 'worker_interrupted'
                WHERE delivery_id = ? AND finished_at IS NULL
                """,
                (now, row["delivery_id"]),
            )
            if row["kind"] == "location.updated" and row["opaque_json"] is None:
                state = "result_unknown"
            elif row["lifecycle"] == "resolved":
                state = "result_unknown"
            elif row["lifecycle"] == "expired":
                state = "result_unknown"
            else:
                state = "retry_wait" if now < row["expires_at"] else "result_unknown"
            self.db.execute(
                """
                UPDATE deliveries
                SET state = ?, next_attempt_at = ?, lease_token = NULL,
                    lease_until = NULL, may_have_accepted = 1,
                    cancellation_state = CASE
                        WHEN emergency = 1 AND transport = 'pushover'
                        THEN 'result_unknown'
                        WHEN emergency = 1 AND transport = 'ntfy'
                        THEN 'unsupported' ELSE cancellation_state END,
                    last_code = 'worker_interrupted', updated_at = ?
                WHERE delivery_id = ?
                """,
                (
                    state,
                    now if state == "retry_wait" else None,
                    now,
                    row["delivery_id"],
                ),
            )
        expired = self.db.execute(
            """
            SELECT d.delivery_id, d.may_have_accepted, d.recipient_id,
                   d.transport, d.provider_fingerprint
            FROM deliveries d JOIN events e USING (device_id, event_id)
            WHERE d.state IN ('pending', 'retry_wait') AND e.expires_at <= ?
            """,
            (now,),
        ).fetchall()
        for row in expired:
            if self._matching_client(row) is None:
                continue
            self.db.execute(
                """
                UPDATE deliveries
                SET state = ?, next_attempt_at = NULL, updated_at = ?
                WHERE delivery_id = ?
                """,
                (
                    "result_unknown" if row["may_have_accepted"] else "expired",
                    now,
                    row["delivery_id"],
                ),
            )

    def _claim_delivery(self):
        now = int(self.clock())
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                if not self._clock_is_trusted(now):
                    self._rollback()
                    return None
                self._recover_and_expire_locked(now)
                rows = self.db.execute(
                    """
                    SELECT d.delivery_id, d.device_id, d.event_id, d.attempt_count,
                           d.recipient_id, d.transport, d.provider_fingerprint,
                           d.emergency,
                           e.expires_at, e.protocol_version, e.kind, e.opaque_json
                    FROM deliveries d JOIN events e USING (device_id, event_id)
                    LEFT JOIN incidents i
                      ON i.device_id = e.device_id AND i.incident_id = e.incident_id
                    WHERE d.state IN ('pending', 'retry_wait')
                      AND d.next_attempt_at <= ? AND e.expires_at > ?
                      AND (i.lifecycle IS NULL OR i.lifecycle = 'active')
                      AND (e.protocol_version = 1 OR e.opaque_json IS NOT NULL)
                    ORDER BY d.next_attempt_at, d.delivery_id
                    """,
                    (now, now),
                ).fetchall()
                row = None
                client = None
                for candidate in rows:
                    matched = self._matching_client(candidate)
                    if matched is not None:
                        row = candidate
                        client = matched
                        break
                if row is None:
                    self.db.commit()
                    return None
                token = secrets.token_hex(16)
                attempt_no = row["attempt_count"] + 1
                changed = self.db.execute(
                    """
                    UPDATE deliveries
                    SET state = 'attempting', attempt_count = ?, next_attempt_at = NULL,
                        lease_token = ?, lease_until = ?, updated_at = ?
                    WHERE delivery_id = ? AND state IN ('pending', 'retry_wait')
                    """,
                    (
                        attempt_no,
                        token,
                        now + self.lease_seconds,
                        now,
                        row["delivery_id"],
                    ),
                ).rowcount
                if changed != 1:
                    raise sqlite3.DatabaseError("delivery claim changed unexpectedly")
                self.db.execute(
                    """
                    INSERT INTO delivery_attempts
                        (delivery_id, attempt_no, claim_token, started_at, outcome)
                    VALUES (?, ?, ?, ?, 'started')
                    """,
                    (row["delivery_id"], attempt_no, token, now),
                )
                self.db.commit()
                return {
                    "delivery_id": row["delivery_id"],
                    "event_id": row["event_id"],
                    "expires_at": row["expires_at"],
                    "attempt_no": attempt_no,
                    "claim_token": token,
                    "protocol_version": row["protocol_version"],
                    "kind": row["kind"],
                    "opaque_json": row["opaque_json"],
                    "recipient_id": row["recipient_id"],
                    "transport": row["transport"],
                    "client": client,
                    "emergency": bool(row["emergency"]),
                }
            except sqlite3.Error:
                self._rollback()
                raise

    def _finish_delivery(
        self,
        claim_token: str,
        *,
        submission: Submission | None = None,
        failure: ProviderFailure | None = None,
    ) -> bool:
        if (submission is None) == (failure is None):
            raise ValueError("exactly one delivery result is required")
        now = int(self.clock())
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                row = self.db.execute(
                    """
                    SELECT d.delivery_id, d.attempt_count, d.may_have_accepted,
                           d.transport, d.emergency, e.expires_at,
                           e.incident_id, i.lifecycle
                    FROM deliveries d JOIN events e USING (device_id, event_id)
                    LEFT JOIN incidents i
                      ON i.device_id = e.device_id AND i.incident_id = e.incident_id
                    WHERE d.state = 'attempting' AND d.lease_token = ?
                    """,
                    (claim_token,),
                ).fetchone()
                if row is None:
                    self.db.commit()
                    return False
                if submission is not None:
                    state = "provider_accepted"
                    next_attempt_at = None
                    ambiguous = 0
                    code = None
                    reference = submission.reference
                    outcome = "provider_accepted"
                    may_have_accepted = row["may_have_accepted"]
                    receipt = submission.receipt
                    if receipt and row["lifecycle"] == "resolved":
                        evidence_next_at = now
                        cancellation_state = "pending"
                    elif receipt and now < row["expires_at"]:
                        evidence_next_at = now + self.receipt_interval
                        cancellation_state = None
                    else:
                        evidence_next_at = None
                        cancellation_state = (
                            "unsupported"
                            if row["transport"] == "ntfy" and row["emergency"]
                            else None
                        )
                else:
                    may_have_accepted = int(
                        bool(row["may_have_accepted"] or failure.ambiguous)
                    )
                    delay = min(
                        300, self.retry_base * (2 ** min(row["attempt_count"] - 1, 6))
                    )
                    if row["lifecycle"] == "resolved":
                        state = (
                            "result_unknown" if may_have_accepted else "resolved"
                        )
                        next_attempt_at = None
                    elif row["lifecycle"] == "expired":
                        state = "result_unknown" if may_have_accepted else "expired"
                        next_attempt_at = None
                    elif failure.retryable and now + delay < row["expires_at"]:
                        state = "retry_wait"
                        next_attempt_at = now + delay
                    elif failure.result == "configuration_failure":
                        state = "configuration_failure"
                        next_attempt_at = None
                    else:
                        state = "result_unknown" if may_have_accepted else "expired"
                        next_attempt_at = None
                    ambiguous = int(failure.ambiguous)
                    code = failure.code
                    reference = None
                    receipt = None
                    evidence_next_at = None
                    cancellation_state = (
                        (
                            "result_unknown"
                            if row["transport"] == "pushover"
                            else "unsupported"
                        )
                        if row["lifecycle"] == "resolved"
                        and may_have_accepted
                        and row["emergency"]
                        else None
                    )
                    outcome = failure.result
                changed = self.db.execute(
                    """
                    UPDATE deliveries
                    SET state = ?, next_attempt_at = ?, lease_token = NULL,
                        lease_until = NULL, may_have_accepted = ?,
                        provider_reference = COALESCE(?, provider_reference),
                        provider_receipt = COALESCE(?, provider_receipt),
                        evidence_next_at = COALESCE(?, evidence_next_at),
                        cancellation_state = COALESCE(?, cancellation_state),
                        last_code = ?, updated_at = ?
                    WHERE delivery_id = ? AND lease_token = ? AND state = 'attempting'
                    """,
                    (
                        state,
                        next_attempt_at,
                        may_have_accepted,
                        reference,
                        receipt,
                        evidence_next_at,
                        cancellation_state,
                        code,
                        now,
                        row["delivery_id"],
                        claim_token,
                    ),
                ).rowcount
                if changed != 1:
                    self._rollback()
                    return False
                self.db.execute(
                    """
                    UPDATE delivery_attempts
                    SET finished_at = ?, outcome = ?, ambiguous = ?, code = ?,
                        provider_reference = ?
                    WHERE claim_token = ? AND finished_at IS NULL
                    """,
                    (now, outcome, ambiguous, code, reference, claim_token),
                )
                self.db.commit()
                return True
            except sqlite3.Error:
                self._rollback()
                raise

    def run_worker_once(self) -> bool:
        claim = self._claim_delivery()
        if claim is None:
            return False
        try:
            client = claim["client"]
            submission = client.submit(
                claim["event_id"],
                kind=claim["kind"],
                opaque_event=claim["opaque_json"],
                emergency=claim["emergency"],
                expires_at=claim["expires_at"],
                now=int(self.clock()),
            )
            self._finish_delivery(claim["claim_token"], submission=submission)
        except ProviderFailure as exc:
            self._finish_delivery(claim["claim_token"], failure=exc)
        except Exception:
            LOGGER.error("provider submission failed")
            self._finish_delivery(
                claim["claim_token"],
                failure=ProviderFailure(
                    "result_unknown",
                    "internal_error",
                    retryable=True,
                    ambiguous=True,
                ),
            )
        return True

    def _claim_evidence(self):
        now = int(self.clock())
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                if not self._clock_is_trusted(now):
                    self._rollback()
                    return None
                interrupted_cancellations = self.db.execute(
                    """
                    SELECT delivery_id, recipient_id, transport,
                           provider_fingerprint FROM deliveries
                    WHERE state = 'provider_accepted'
                      AND evidence_action = 'cancel' AND lease_until <= ?
                    """,
                    (now,),
                ).fetchall()
                for row in interrupted_cancellations:
                    if self._matching_client(row) is not None:
                        self.db.execute(
                            """
                            UPDATE deliveries
                            SET cancellation_state = 'result_unknown',
                                evidence_next_at = ?, evidence_action = NULL,
                                lease_token = NULL, lease_until = NULL,
                                last_code = 'evidence_worker_interrupted',
                                updated_at = ?
                            WHERE delivery_id = ? AND evidence_action = 'cancel'
                            """,
                            (now, now, row["delivery_id"]),
                        )
                interrupted_polls = self.db.execute(
                    """
                    SELECT delivery_id, recipient_id, transport,
                           provider_fingerprint FROM deliveries
                    WHERE state = 'provider_accepted'
                      AND evidence_action = 'poll' AND lease_until <= ?
                    """,
                    (now,),
                ).fetchall()
                for row in interrupted_polls:
                    if self._matching_client(row) is not None:
                        self.db.execute(
                            """
                            UPDATE deliveries
                            SET evidence_action = NULL, lease_token = NULL,
                                lease_until = NULL, evidence_next_at = ?,
                                updated_at = ?
                            WHERE delivery_id = ? AND evidence_action = 'poll'
                            """,
                            (now, now, row["delivery_id"]),
                        )
                expired_evidence = self.db.execute(
                    """
                    SELECT delivery_id, recipient_id, transport,
                           provider_fingerprint FROM deliveries
                    WHERE state = 'provider_accepted'
                      AND evidence_next_at IS NOT NULL
                      AND lease_token IS NULL AND EXISTS (
                        SELECT 1 FROM events e
                        WHERE e.device_id = deliveries.device_id
                          AND e.event_id = deliveries.event_id
                          AND e.expires_at <= ?
                    )
                    """,
                    (now,),
                ).fetchall()
                for row in expired_evidence:
                    if self._matching_client(row) is not None:
                        self.db.execute(
                            """
                            UPDATE deliveries SET evidence_next_at = NULL,
                                updated_at = ?
                            WHERE delivery_id = ? AND lease_token IS NULL
                            """,
                            (now, row["delivery_id"]),
                        )
                rows = self.db.execute(
                    """
                    SELECT d.delivery_id, d.recipient_id, d.transport,
                           d.provider_fingerprint,
                           d.provider_receipt, d.cancellation_state,
                           e.expires_at, e.incident_id, i.lifecycle
                    FROM deliveries d JOIN events e USING (device_id, event_id)
                    JOIN incidents i
                      ON i.device_id = e.device_id AND i.incident_id = e.incident_id
                    WHERE d.state = 'provider_accepted'
                      AND d.provider_receipt IS NOT NULL
                      AND d.evidence_next_at <= ? AND d.lease_token IS NULL
                      AND e.expires_at > ?
                      AND (
                        (i.lifecycle = 'active' AND d.cancellation_state IS NULL)
                        OR
                        (i.lifecycle = 'resolved' AND d.cancellation_state IN
                            ('pending', 'result_unknown'))
                      )
                    ORDER BY CASE i.lifecycle WHEN 'resolved' THEN 0 ELSE 1 END,
                             d.evidence_next_at, d.delivery_id
                    """,
                    (now, now),
                ).fetchall()
                row = None
                client = None
                for candidate in rows:
                    matched = self._matching_client(candidate)
                    if matched is not None:
                        row = candidate
                        client = matched
                        break
                if row is None:
                    self.db.commit()
                    return None
                action = "cancel" if row["lifecycle"] == "resolved" else "poll"
                token = secrets.token_hex(16)
                changed = self.db.execute(
                    """
                    UPDATE deliveries
                    SET lease_token = ?, lease_until = ?, evidence_action = ?,
                        updated_at = ?
                    WHERE delivery_id = ? AND state = 'provider_accepted'
                      AND lease_token IS NULL
                    """,
                    (
                        token,
                        now + self.lease_seconds,
                        action,
                        now,
                        row["delivery_id"],
                    ),
                ).rowcount
                if changed != 1:
                    raise sqlite3.DatabaseError("evidence claim changed unexpectedly")
                self.db.commit()
                return {
                    "claim_token": token,
                    "recipient_id": row["recipient_id"],
                    "transport": row["transport"],
                    "receipt": row["provider_receipt"],
                    "action": action,
                    "client": client,
                }
            except sqlite3.Error:
                self._rollback()
                raise

    def _finish_evidence_poll(
        self,
        claim_token: str,
        *,
        evidence: ReceiptEvidence | None = None,
        failure: ProviderFailure | None = None,
    ) -> bool:
        if (evidence is None) == (failure is None):
            raise ValueError("exactly one receipt result is required")
        now = int(self.clock())
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                row = self.db.execute(
                    """
                    SELECT d.delivery_id, d.device_id, d.recipient_id,
                           d.provider_receipt, d.provider_last_delivered_at,
                           e.expires_at, e.incident_id, i.lifecycle
                    FROM deliveries d JOIN events e USING (device_id, event_id)
                    JOIN incidents i
                      ON i.device_id = e.device_id AND i.incident_id = e.incident_id
                    WHERE d.state = 'provider_accepted'
                      AND d.lease_token = ? AND d.evidence_action = 'poll'
                    """,
                    (claim_token,),
                ).fetchone()
                if row is None:
                    self.db.commit()
                    return False
                cancellation_state = None
                if evidence is not None:
                    last_delivered_at = row["provider_last_delivered_at"]
                    if evidence.last_delivered_at is not None:
                        last_delivered_at = max(
                            last_delivered_at or 0, evidence.last_delivered_at
                        )
                    expired_at = now if evidence.expired else None
                    if evidence.acknowledged:
                        self.db.execute(
                            """
                            INSERT OR IGNORE INTO acknowledgements
                                (device_id, incident_id, recipient_id, source,
                                 acknowledged_at, observed_at, provider_reference)
                            VALUES (?, ?, ?, 'pushover_receipt', ?, ?, ?)
                            """,
                            (
                                row["device_id"],
                                row["incident_id"],
                                row["recipient_id"],
                                evidence.acknowledged_at,
                                now,
                                row["provider_receipt"],
                            ),
                        )
                    if evidence.acknowledged or evidence.expired:
                        evidence_next_at = None
                    elif row["lifecycle"] == "resolved":
                        cancellation_state = "pending"
                        evidence_next_at = now
                    elif now + self.receipt_interval < row["expires_at"]:
                        evidence_next_at = now + self.receipt_interval
                    else:
                        evidence_next_at = None
                    code = None
                else:
                    last_delivered_at = row["provider_last_delivered_at"]
                    expired_at = None
                    code = failure.code
                    if row["lifecycle"] == "resolved":
                        cancellation_state = "pending"
                        evidence_next_at = now
                    elif failure.retryable and now + self.receipt_interval < row["expires_at"]:
                        evidence_next_at = now + self.receipt_interval
                    else:
                        evidence_next_at = None
                changed = self.db.execute(
                    """
                    UPDATE deliveries
                    SET lease_token = NULL, lease_until = NULL,
                        evidence_action = NULL, evidence_next_at = ?,
                        provider_last_delivered_at = ?,
                        provider_expired_at = COALESCE(provider_expired_at, ?),
                        cancellation_state = ?, last_code = ?, updated_at = ?
                    WHERE delivery_id = ? AND lease_token = ?
                      AND evidence_action = 'poll'
                    """,
                    (
                        evidence_next_at,
                        last_delivered_at,
                        expired_at,
                        cancellation_state,
                        code,
                        now,
                        row["delivery_id"],
                        claim_token,
                    ),
                ).rowcount
                if changed != 1:
                    self._rollback()
                    return False
                self.db.commit()
                return True
            except sqlite3.Error:
                self._rollback()
                raise

    def _finish_evidence_cancel(
        self,
        claim_token: str,
        failure: ProviderFailure | None = None,
    ) -> bool:
        now = int(self.clock())
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                row = self.db.execute(
                    """
                    SELECT d.delivery_id, e.expires_at
                    FROM deliveries d JOIN events e USING (device_id, event_id)
                    WHERE d.state = 'provider_accepted'
                      AND d.lease_token = ? AND d.evidence_action = 'cancel'
                    """,
                    (claim_token,),
                ).fetchone()
                if row is None:
                    self.db.commit()
                    return False
                if failure is None:
                    cancellation_state = "cancelled"
                    cancelled_at = now
                    evidence_next_at = None
                    code = None
                else:
                    cancelled_at = None
                    code = failure.code
                    if failure.result == "configuration_failure":
                        cancellation_state = "configuration_failure"
                        evidence_next_at = None
                    else:
                        cancellation_state = "result_unknown"
                        evidence_next_at = (
                            now + self.receipt_interval
                            if failure.retryable
                            and now + self.receipt_interval < row["expires_at"]
                            else None
                        )
                changed = self.db.execute(
                    """
                    UPDATE deliveries
                    SET lease_token = NULL, lease_until = NULL,
                        evidence_action = NULL, evidence_next_at = ?,
                        provider_cancelled_at = COALESCE(provider_cancelled_at, ?),
                        cancellation_state = ?, last_code = ?, updated_at = ?
                    WHERE delivery_id = ? AND lease_token = ?
                      AND evidence_action = 'cancel'
                    """,
                    (
                        evidence_next_at,
                        cancelled_at,
                        cancellation_state,
                        code,
                        now,
                        row["delivery_id"],
                        claim_token,
                    ),
                ).rowcount
                if changed != 1:
                    self._rollback()
                    return False
                self.db.commit()
                return True
            except sqlite3.Error:
                self._rollback()
                raise

    def run_evidence_once(self) -> bool:
        claim = self._claim_evidence()
        if claim is None:
            return False
        client = claim["client"]
        try:
            if claim["action"] == "cancel":
                client.cancel_receipt(claim["receipt"])
                self._finish_evidence_cancel(claim["claim_token"])
            else:
                evidence = client.poll_receipt(claim["receipt"])
                if not isinstance(evidence, ReceiptEvidence):
                    raise ProviderFailure(
                        "result_unknown", "provider_result_unknown", retryable=True
                    )
                self._finish_evidence_poll(
                    claim["claim_token"], evidence=evidence
                )
        except ProviderFailure as exc:
            if claim["action"] == "cancel":
                self._finish_evidence_cancel(claim["claim_token"], exc)
            else:
                self._finish_evidence_poll(claim["claim_token"], failure=exc)
        except Exception:
            LOGGER.error("provider evidence request failed")
            failure = ProviderFailure(
                "result_unknown",
                "internal_error",
                retryable=True,
                ambiguous=claim["action"] == "cancel",
            )
            if claim["action"] == "cancel":
                self._finish_evidence_cancel(claim["claim_token"], failure)
            else:
                self._finish_evidence_poll(
                    claim["claim_token"], failure=failure
                )
        return True


def make_server(host: str, port: int, relay: Relay) -> ThreadingHTTPServer:
    class Server(RelayHTTPServer):
        worker_stop = None
        worker_thread = None

        def server_close(self):
            if self.worker_stop is not None:
                self.worker_stop.set()
            if self.worker_thread is not None:
                self.worker_thread.join(61)
            super().server_close()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        timeout = 5

        def version_string(self):
            return "relay"

        def log_message(self, _format, *_args):
            return

        def _send(self, status: int, payload: dict, *, suppress_body: bool = False):
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
            if not suppress_body:
                self.wfile.write(encoded)
            self.close_connection = True

        def do_POST(self):
            mailbox_match = MAILBOX_PATH_RE.fullmatch(self.path)
            if mailbox_match is not None:
                mailbox_id, resource = mailbox_match.groups()
                if self.headers.get("Transfer-Encoding") is not None:
                    self._send(400, {"v": 1, "result": "configuration_failure", "code": "unsupported_transfer_encoding"})
                    return
                encodings = self.headers.get_all("Content-Encoding") or []
                if len(encodings) > 1 or (encodings and encodings[0].lower() != "identity"):
                    self._send(415, {"v": 1, "result": "configuration_failure", "code": "unsupported_content_encoding"})
                    return
                content_types = self.headers.get_all("Content-Type") or []
                if len(content_types) != 1 or self.headers.get_content_type() != "application/json":
                    self._send(415, {"v": 1, "result": "configuration_failure", "code": "invalid_content_type"})
                    return
                lengths = self.headers.get_all("Content-Length") or []
                if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
                    self._send(411, {"v": 1, "result": "configuration_failure", "code": "invalid_content_length"})
                    return
                length = int(lengths[0])
                if length < 1:
                    self._send(400, {"v": 1, "result": "configuration_failure", "code": "empty_body"})
                    return
                if length > MAX_MAILBOX_BODY_BYTES:
                    self._send(413, {"v": 1, "result": "configuration_failure", "code": "body_too_large"})
                    return
                authorizations = self.headers.get_all("Authorization") or []
                authorization = authorizations[0] if len(authorizations) == 1 else None
                try:
                    body = self.rfile.read(length)
                except OSError:
                    self._send(408, {"v": 1, "result": "retryable_failure", "code": "request_incomplete"})
                    return
                if len(body) != length:
                    self._send(408, {"v": 1, "result": "retryable_failure", "code": "request_incomplete"})
                    return
                if resource == "messages":
                    status, payload = relay.mailbox.append(mailbox_id, body, authorization)
                else:
                    status, payload = relay.mailbox.acknowledge(mailbox_id, body, authorization)
                self._send(status, payload)
                return
            if self.path not in {EVENT_PATH, LIVE_EVENT_PATH, STATUS_PATH}:
                self._send(404, response("configuration_failure", "unknown_endpoint"))
                return
            version = 1 if self.path == EVENT_PATH else 2
            if self.headers.get("Transfer-Encoding") is not None:
                self._send(
                    400,
                    response(
                        "configuration_failure",
                        "unsupported_transfer_encoding",
                        version=version,
                    ),
                )
                return
            encodings = self.headers.get_all("Content-Encoding") or []
            if len(encodings) > 1 or (encodings and encodings[0].lower() != "identity"):
                self._send(
                    415,
                    response(
                        "configuration_failure",
                        "unsupported_content_encoding",
                        version=version,
                    ),
                )
                return
            content_types = self.headers.get_all("Content-Type") or []
            if len(content_types) != 1 or self.headers.get_content_type() != "application/json":
                self._send(
                    415,
                    response(
                        "configuration_failure", "invalid_content_type", version=version
                    ),
                )
                return
            lengths = self.headers.get_all("Content-Length") or []
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
                self._send(
                    411,
                    response(
                        "configuration_failure",
                        "invalid_content_length",
                        version=version,
                    ),
                )
                return
            length = int(lengths[0])
            if length < 1:
                self._send(
                    400,
                    response("configuration_failure", "empty_body", version=version),
                )
                return
            if length > MAX_BODY_BYTES:
                self._send(
                    413,
                    response(
                        "configuration_failure", "body_too_large", version=version
                    ),
                )
                return
            signatures = self.headers.get_all("X-OpenDistress-Signature") or []
            signature = signatures[0] if len(signatures) == 1 else None
            try:
                body = self.rfile.read(length)
            except OSError:
                self._send(
                    408,
                    response(
                        "retryable_failure", "request_incomplete", version=version
                    ),
                )
                return
            if len(body) != length:
                self._send(
                    408,
                    response(
                        "retryable_failure", "request_incomplete", version=version
                    ),
                )
                return
            if self.path == STATUS_PATH:
                processor = relay.process_status
            elif version == 2:
                processor = relay.process_v2
            else:
                processor = relay.process
            status, payload = processor(body, signature)
            self._send(status, payload)

        def _method_not_allowed(self, *, suppress_body: bool = False):
            version = 2 if self.path in {LIVE_EVENT_PATH, STATUS_PATH} else 1
            self._send(
                405,
                response(
                    "configuration_failure", "method_not_allowed", version=version
                ),
                suppress_body=suppress_body,
            )

        def do_GET(self):
            mailbox_match = MAILBOX_PATH_RE.fullmatch(self.path)
            if mailbox_match is None:
                self._method_not_allowed()
                return
            mailbox_id, resource = mailbox_match.groups()
            authorizations = self.headers.get_all("Authorization") or []
            authorization = authorizations[0] if len(authorizations) == 1 else None
            if resource == "messages":
                status, payload = relay.mailbox.list_messages(mailbox_id, authorization)
            else:
                status, payload = relay.mailbox.list_acknowledgements(mailbox_id, authorization)
            self._send(status, payload)

        def do_PUT(self):
            self._method_not_allowed()

        def do_PATCH(self):
            self._method_not_allowed()

        def do_DELETE(self):
            self._method_not_allowed()

        def do_OPTIONS(self):
            self._method_not_allowed()

        def do_TRACE(self):
            self._method_not_allowed()

        def do_CONNECT(self):
            self._method_not_allowed()

        def do_HEAD(self):
            self._method_not_allowed(suppress_body=True)

    server = Server((host, port), Handler)
    server.worker_stop = threading.Event()

    def worker():
        next_cleanup = 0.0
        while not server.worker_stop.is_set():
            try:
                worked = relay.run_evidence_once()
                worked = relay.run_worker_once() or worked
                if time.monotonic() >= next_cleanup:
                    relay.purge_expired()
                    next_cleanup = time.monotonic() + 60
            except sqlite3.Error:
                LOGGER.error("outbox worker persistence failure")
                worked = False
            except Exception:
                LOGGER.error("outbox worker internal failure")
                worked = False
            if not worked:
                server.worker_stop.wait(0.25)

    server.worker_thread = threading.Thread(target=worker, daemon=True)
    server.worker_thread.start()
    return server


def resolve_database(database: str, target: str, clock=time.time) -> bool:
    device_id, separator, incident_id = target.partition(":")
    if (
        not separator
        or not is_canonical_id(device_id)
        or not is_canonical_id(incident_id)
    ):
        raise ValueError("resolution requires canonical device and incident IDs")
    fd = os.open(database, os.O_RDWR)
    try:
        details = os.fstat(fd)
        if not stat.S_ISREG(details.st_mode):
            raise ValueError("relay database must be a regular file")
        if stat.S_IMODE(details.st_mode) & 0o077:
            raise ValueError("relay database must have mode 0600")
    finally:
        os.close(fd)
    db = sqlite3.connect(database, timeout=5)
    try:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("BEGIN IMMEDIATE")
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version not in (4, SCHEMA_VERSION):
            raise ValueError(
                f"unsupported relay database schema version {version}"
            )
        resolved = _resolve_incident_locked(
            db, device_id, incident_id, int(clock())
        )
        db.commit()
        return resolved
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="OpenDistress relay")
    parser.add_argument("--devices", help="path to the device JSON file")
    parser.add_argument("--routes", help="path to the private route JSON file")
    parser.add_argument(
        "--mailboxes",
        help="path to the hashed capability mailbox JSON file",
    )
    parser.add_argument("--database", required=True, help="path to the SQLite idempotency ledger")
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--max-clock-skew", type=int, default=300)
    parser.add_argument("--provider-timeout", type=float, default=10)
    parser.add_argument("--emergency-retry", type=int, default=60)
    parser.add_argument("--receipt-interval", type=int, default=30)
    parser.add_argument(
        "--resolve-incident",
        metavar="DEVICE_ID:INCIDENT_ID",
        help="durably resolve an incident in the local database and exit",
    )
    args = parser.parse_args(argv)

    if args.database == ":memory:":
        parser.error("--database must name a persistent SQLite file")

    relay = None
    try:
        if args.resolve_incident:
            if not resolve_database(args.database, args.resolve_incident):
                raise ValueError("incident to resolve was not found")
            return 0
        if not args.devices or not args.routes:
            raise ValueError("--devices and --routes are required when serving")
        devices = load_devices(args.devices)
        routes = load_routes(args.routes)
        mailboxes = load_mailboxes(args.mailboxes) if args.mailboxes else {}
        transports = build_transports(
            routes,
            os.environ.get("PUSHOVER_APP_TOKEN", ""),
            args.provider_timeout,
            args.emergency_retry,
        )
        relay = Relay(
            devices,
            None,
            args.database,
            args.max_clock_skew,
            routes=routes,
            transports=transports,
            receipt_interval=args.receipt_interval,
            mailboxes=mailboxes,
        )
        server = make_server(args.listen, args.port, relay)
    except (OSError, ValueError, sqlite3.Error) as exc:
        if relay is not None:
            relay.close()
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

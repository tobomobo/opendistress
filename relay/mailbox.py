# SPDX-License-Identifier: MIT
"""Content-blind, capability-authenticated mailbox storage.

The mailbox relay deliberately does not understand the encrypted capsule.  It
only enforces a fixed outer shape, lifetime, immutable message IDs, quotas, and
an append-only acknowledgement envelope.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import sqlite3
import stat
import time
from pathlib import Path


MAILBOX_VERSION = 1
MAX_BODY_BYTES = 2048
MAX_ACTIVE_MESSAGES = 32
MAX_MESSAGES_PER_HOUR = 64
MAX_LIFETIME_SECONDS = 86_400
RETENTION_SECONDS = 86_400
MESSAGE_CIPHERTEXT_BYTES = 512
ACK_CIPHERTEXT_BYTES = 256
PUBLIC_CAPABILITY_HASHES = frozenset(
    hashlib.sha256(bytes([fill]) * 32).digest()
    for fill in (0xA1, 0xB2, 0xC3)
)
ID_RE = re.compile(r"^[A-Za-z0-9_-]{22}$")
CAPABILITY_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
HEX_32_RE = re.compile(r"^[0-9a-f]{64}$")
INTEGER_RE = re.compile(r"^(?:0|[1-9][0-9]*)$")
MESSAGE_FIELDS = {"v", "mailbox_id", "message_id", "expires_at", "payload"}
ACK_FIELDS = {"v", "message_id", "capsule_sha256", "payload"}
PAYLOAD_FIELDS = {"iv", "ciphertext", "tag"}
CONFIG_FIELDS = {
    "enabled",
    "append_cap_sha256",
    "read_cap_sha256",
    "ack_cap_sha256",
}


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _parse_integer(value: str) -> int:
    if not INTEGER_RE.fullmatch(value):
        raise ValueError("invalid JSON integer")
    return int(value)


def _reject_number(value):
    raise ValueError(f"invalid JSON number: {value}")


def parse_json(data: bytes):
    return json.loads(
        data.decode("utf-8"),
        object_pairs_hook=_reject_duplicate_keys,
        parse_int=_parse_integer,
        parse_constant=_reject_number,
    )


def _canonical_bytes(value, size: int) -> bool:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        return False
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (TypeError, ValueError):
        return False
    return (
        len(decoded) == size
        and base64.urlsafe_b64encode(decoded).rstrip(b"=").decode() == value
    )


def _canonical_id(value) -> bool:
    return isinstance(value, str) and ID_RE.fullmatch(value) is not None and _canonical_bytes(value, 16)


def validate_message(message) -> str | None:
    if type(message) is not dict or set(message) != MESSAGE_FIELDS:
        return "invalid_message"
    if type(message["v"]) is not int or message["v"] != MAILBOX_VERSION:
        return "invalid_message"
    if not _canonical_id(message["mailbox_id"]) or not _canonical_id(message["message_id"]):
        return "invalid_message"
    if type(message["expires_at"]) is not int or not 0 <= message["expires_at"] <= 2_147_483_647:
        return "invalid_message"
    payload = message["payload"]
    if type(payload) is not dict or set(payload) != PAYLOAD_FIELDS:
        return "invalid_message"
    if (
        not _canonical_bytes(payload["iv"], 16)
        or not _canonical_bytes(payload["ciphertext"], MESSAGE_CIPHERTEXT_BYTES)
        or not _canonical_bytes(payload["tag"], 32)
    ):
        return "invalid_message"
    return None


def validate_ack(ack) -> str | None:
    if type(ack) is not dict or set(ack) != ACK_FIELDS:
        return "invalid_ack"
    if type(ack["v"]) is not int or ack["v"] != MAILBOX_VERSION:
        return "invalid_ack"
    if not _canonical_id(ack["message_id"]):
        return "invalid_ack"
    if type(ack["capsule_sha256"]) is not str or not HEX_32_RE.fullmatch(ack["capsule_sha256"]):
        return "invalid_ack"
    payload = ack["payload"]
    if type(payload) is not dict or set(payload) != PAYLOAD_FIELDS:
        return "invalid_ack"
    if (
        not _canonical_bytes(payload["iv"], 16)
        or not _canonical_bytes(payload["ciphertext"], ACK_CIPHERTEXT_BYTES)
        or not _canonical_bytes(payload["tag"], 32)
    ):
        return "invalid_ack"
    return None


def canonical_message(message: dict) -> bytes:
    payload = message["payload"]
    return (
        "opendistress.mailbox.message.v1\n"
        "v=1\n"
        f"mailbox_id={message['mailbox_id']}\n"
        f"message_id={message['message_id']}\n"
        f"expires_at={message['expires_at']}\n"
        f"payload.iv={payload['iv']}\n"
        f"payload.ciphertext={payload['ciphertext']}\n"
        f"payload.tag={payload['tag']}\n"
    ).encode("ascii")


def canonical_ack(ack: dict) -> bytes:
    payload = ack["payload"]
    return (
        "opendistress.mailbox.ack.v1\n"
        "v=1\n"
        f"message_id={ack['message_id']}\n"
        f"capsule_sha256={ack['capsule_sha256']}\n"
        f"payload.iv={payload['iv']}\n"
        f"payload.ciphertext={payload['ciphertext']}\n"
        f"payload.tag={payload['tag']}\n"
    ).encode("ascii")


def capsule_sha256(message: dict) -> bytes:
    return hashlib.sha256(canonical_message(message)).digest()


def load_mailboxes(path: str | os.PathLike[str]) -> dict[str, dict]:
    config_path = Path(path)
    if config_path.stat().st_size > 1_048_576:
        raise ValueError("mailbox configuration is too large")
    raw = parse_json(config_path.read_bytes())
    if type(raw) is not dict or len(raw) > 10_000:
        raise ValueError("mailbox configuration must contain at most 10000 mailboxes")
    result = {}
    for mailbox_id, record in raw.items():
        if not _canonical_id(mailbox_id):
            raise ValueError("invalid mailbox ID in configuration")
        if type(record) is not dict or set(record) != CONFIG_FIELDS:
            raise ValueError(f"mailbox {mailbox_id!r} has missing or unknown fields")
        if type(record["enabled"]) is not bool:
            raise ValueError(f"mailbox {mailbox_id!r} enabled must be boolean")
        hashes = []
        for name in ("append_cap_sha256", "read_cap_sha256", "ack_cap_sha256"):
            value = record[name]
            if type(value) is not str or not HEX_32_RE.fullmatch(value):
                raise ValueError(f"mailbox {mailbox_id!r} has an invalid capability hash")
            hashes.append(bytes.fromhex(value))
        if len(set(hashes)) != 3:
            raise ValueError(f"mailbox {mailbox_id!r} must use distinct capabilities")
        if record["enabled"] and any(value in PUBLIC_CAPABILITY_HASHES for value in hashes):
            raise ValueError(
                f"mailbox {mailbox_id!r} uses a published example capability"
            )
        result[mailbox_id] = {
            "enabled": record["enabled"],
            "append": hashes[0],
            "read": hashes[1],
            "ack": hashes[2],
        }
    if any(record["enabled"] for record in result.values()):
        if stat.S_IMODE(config_path.stat().st_mode) & 0o077:
            raise ValueError("enabled mailbox configuration must have mode 0600")
    return result


def _decode_capability_header(header: str | None) -> bytes | None:
    if not isinstance(header, str) or not header.startswith("Bearer "):
        return None
    encoded = header[7:]
    if not CAPABILITY_RE.fullmatch(encoded) or not _canonical_bytes(encoded, 32):
        return None
    return base64.urlsafe_b64decode(encoded + "=")


def _result_mac(capability: bytes, message_id: str, result: str) -> str:
    canonical = (
        "opendistress.mailbox.result.v1\n"
        "v=1\n"
        f"message_id={message_id}\n"
        f"result={result}\n"
    ).encode("ascii")
    digest = hmac.digest(capability, canonical, "sha256")
    return "v1=" + base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


class MailboxStore:
    def __init__(self, db: sqlite3.Connection, db_lock, mailboxes=None, clock=time.time):
        self.db = db
        self.db_lock = db_lock
        self.mailboxes = dict(mailboxes or {})
        self.clock = clock

    def initialize_schema_locked(self):
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS mailbox_messages (
                mailbox_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                capsule_sha256 BLOB NOT NULL,
                capsule_json BLOB NOT NULL,
                expires_at INTEGER NOT NULL,
                accepted_at INTEGER NOT NULL,
                PRIMARY KEY (mailbox_id, message_id)
            )
            """
        )
        self.db.execute(
            """
            CREATE TABLE IF NOT EXISTS mailbox_acknowledgements (
                mailbox_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                ack_sha256 BLOB NOT NULL,
                ack_json BLOB NOT NULL,
                accepted_at INTEGER NOT NULL,
                PRIMARY KEY (mailbox_id, message_id),
                FOREIGN KEY (mailbox_id, message_id)
                    REFERENCES mailbox_messages (mailbox_id, message_id) ON DELETE CASCADE
            )
            """
        )
        self.db.execute(
            "CREATE INDEX IF NOT EXISTS mailbox_messages_expiry ON mailbox_messages (expires_at)"
        )

    def purge_locked(self, now: int):
        self.db.execute(
            "DELETE FROM mailbox_messages WHERE expires_at <= ?",
            (now - RETENTION_SECONDS,),
        )

    def _authenticate(self, mailbox_id: str, role: str, header: str | None) -> bytes | None:
        capability = _decode_capability_header(header)
        supplied = hashlib.sha256(capability or bytes(32)).digest()
        record = self.mailboxes.get(mailbox_id)
        expected = record[role] if record is not None else bytes(32)
        matches = hmac.compare_digest(supplied, expected)
        if capability is None or record is None or not record["enabled"] or not matches:
            return None
        return capability

    @staticmethod
    def _failure(code: str, *, status=400):
        return status, {"v": 1, "result": "configuration_failure", "code": code}

    def append(self, mailbox_id: str, body: bytes, authorization: str | None):
        try:
            message = parse_json(body)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return self._failure("invalid_json")
        if validate_message(message) is not None:
            return self._failure("invalid_message", status=422)
        capability = self._authenticate(mailbox_id, "append", authorization)
        if capability is None:
            return self._failure("authentication_failed", status=401)
        if message["mailbox_id"] != mailbox_id:
            return self._failure("mailbox_mismatch", status=422)
        now = int(self.clock())
        if not now < message["expires_at"] <= now + MAX_LIFETIME_SECONDS:
            return self._failure("expiry_out_of_window", status=422)
        digest = capsule_sha256(message)
        encoded = json.dumps(message, separators=(",", ":")).encode("ascii")
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                self.purge_locked(now)
                existing = self.db.execute(
                    "SELECT capsule_sha256 FROM mailbox_messages WHERE mailbox_id = ? AND message_id = ?",
                    (mailbox_id, message["message_id"]),
                ).fetchone()
                if existing is not None:
                    if not hmac.compare_digest(existing["capsule_sha256"], digest):
                        self.db.rollback()
                        return self._failure("message_id_conflict", status=409)
                    self.db.commit()
                    result = "durably_accepted"
                    return 202, {
                        "v": 1,
                        "message_id": message["message_id"],
                        "result": result,
                        "response_mac": _result_mac(capability, message["message_id"], result),
                    }
                active = self.db.execute(
                    "SELECT COUNT(*) FROM mailbox_messages WHERE mailbox_id = ? AND expires_at > ?",
                    (mailbox_id, now),
                ).fetchone()[0]
                recent = self.db.execute(
                    "SELECT COUNT(*) FROM mailbox_messages WHERE mailbox_id = ? AND accepted_at > ?",
                    (mailbox_id, now - 3600),
                ).fetchone()[0]
                if active >= MAX_ACTIVE_MESSAGES or recent >= MAX_MESSAGES_PER_HOUR:
                    self.db.rollback()
                    return 429, {"v": 1, "result": "retryable_failure", "code": "mailbox_quota_exceeded"}
                self.db.execute(
                    """
                    INSERT INTO mailbox_messages
                        (mailbox_id, message_id, capsule_sha256, capsule_json, expires_at, accepted_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (mailbox_id, message["message_id"], digest, encoded, message["expires_at"], now),
                )
                self.db.commit()
            except sqlite3.Error:
                self.db.rollback()
                raise
        result = "durably_accepted"
        return 202, {
            "v": 1,
            "message_id": message["message_id"],
            "result": result,
            "response_mac": _result_mac(capability, message["message_id"], result),
        }

    def list_messages(self, mailbox_id: str, authorization: str | None):
        if self._authenticate(mailbox_id, "read", authorization) is None:
            return self._failure("authentication_failed", status=401)
        now = int(self.clock())
        with self.db_lock:
            rows = self.db.execute(
                """
                SELECT capsule_json FROM mailbox_messages m
                WHERE mailbox_id = ? AND expires_at > ? AND NOT EXISTS (
                    SELECT 1 FROM mailbox_acknowledgements a
                    WHERE a.mailbox_id = m.mailbox_id AND a.message_id = m.message_id
                )
                ORDER BY accepted_at, message_id LIMIT ?
                """,
                (mailbox_id, now, MAX_ACTIVE_MESSAGES),
            ).fetchall()
        return 200, {"v": 1, "result": "ok", "messages": [parse_json(row[0]) for row in rows]}

    def acknowledge(self, mailbox_id: str, body: bytes, authorization: str | None):
        try:
            ack = parse_json(body)
        except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
            return self._failure("invalid_json")
        if validate_ack(ack) is not None:
            return self._failure("invalid_ack", status=422)
        capability = self._authenticate(mailbox_id, "ack", authorization)
        if capability is None:
            return self._failure("authentication_failed", status=401)
        now = int(self.clock())
        encoded = json.dumps(ack, separators=(",", ":")).encode("ascii")
        digest = hashlib.sha256(canonical_ack(ack)).digest()
        with self.db_lock:
            try:
                self.db.execute("BEGIN IMMEDIATE")
                message = self.db.execute(
                    "SELECT capsule_sha256, expires_at FROM mailbox_messages WHERE mailbox_id = ? AND message_id = ?",
                    (mailbox_id, ack["message_id"]),
                ).fetchone()
                if message is None:
                    self.db.rollback()
                    return self._failure("message_not_found", status=404)
                if not hmac.compare_digest(message["capsule_sha256"].hex(), ack["capsule_sha256"]):
                    self.db.rollback()
                    return self._failure("capsule_mismatch", status=409)
                if now > message["expires_at"] + RETENTION_SECONDS:
                    self.db.rollback()
                    return self._failure("message_expired", status=410)
                existing = self.db.execute(
                    "SELECT ack_sha256 FROM mailbox_acknowledgements WHERE mailbox_id = ? AND message_id = ?",
                    (mailbox_id, ack["message_id"]),
                ).fetchone()
                if existing is not None:
                    if not hmac.compare_digest(existing["ack_sha256"], digest):
                        self.db.rollback()
                        return self._failure("ack_conflict", status=409)
                    self.db.commit()
                else:
                    self.db.execute(
                        """
                        INSERT INTO mailbox_acknowledgements
                            (mailbox_id, message_id, ack_sha256, ack_json, accepted_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        (mailbox_id, ack["message_id"], digest, encoded, now),
                    )
                    self.db.commit()
            except sqlite3.Error:
                self.db.rollback()
                raise
        result = "durably_accepted"
        return 202, {
            "v": 1,
            "message_id": ack["message_id"],
            "result": result,
            "response_mac": _result_mac(capability, ack["message_id"], result),
        }

    def list_acknowledgements(self, mailbox_id: str, authorization: str | None):
        if self._authenticate(mailbox_id, "append", authorization) is None:
            return self._failure("authentication_failed", status=401)
        now = int(self.clock())
        with self.db_lock:
            rows = self.db.execute(
                """
                SELECT a.ack_json FROM mailbox_acknowledgements a
                JOIN mailbox_messages m
                  ON m.mailbox_id = a.mailbox_id AND m.message_id = a.message_id
                WHERE a.mailbox_id = ? AND m.expires_at > ?
                ORDER BY a.accepted_at, a.message_id LIMIT ?
                """,
                (mailbox_id, now - RETENTION_SECONDS, MAX_ACTIVE_MESSAGES),
            ).fetchall()
        return 200, {"v": 1, "result": "ok", "acknowledgements": [parse_json(row[0]) for row in rows]}

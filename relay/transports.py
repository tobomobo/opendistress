# SPDX-License-Identifier: MIT
"""The relay's concrete Pushover and ntfy transports."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

MAX_PROVIDER_RESPONSE_BYTES = 4096
TOKEN_RE = re.compile(r"^[A-Za-z0-9]{30}$")
REFERENCE_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
PUSHOVER_RECEIPT_RE = re.compile(r"^[A-Za-z0-9]{30}$")
TOPIC_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
NTFY_TOKEN_RE = re.compile(r"^tk_[A-Za-z0-9]{29}$")
TEST_MESSAGE = "TEST ONLY — Garmin alert transport check. No emergency action required."


def _configuration_fingerprint(transport: str, *values: str) -> bytes:
    digest = hashlib.sha256()
    digest.update(b"spb.provider-configuration.v1\0")
    for value in (transport, *values):
        encoded = value.encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    return digest.digest()


class ProviderFailure(Exception):
    """A classified provider outcome containing no response details."""

    def __init__(
        self,
        result: str,
        code: str,
        *,
        retryable: bool = False,
        ambiguous: bool = False,
    ):
        super().__init__(code)
        self.result = result
        self.code = code
        self.retryable = retryable
        self.ambiguous = ambiguous


@dataclass(frozen=True)
class Submission:
    reference: str
    receipt: str | None = None


@dataclass(frozen=True)
class ReceiptEvidence:
    acknowledged: bool
    acknowledged_at: int | None
    expired: bool
    last_delivered_at: int | None


class _NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _decode_json(raw: bytes):
    def reject_constant(value):
        raise ValueError(f"invalid JSON number: {value}")

    return json.loads(
        raw.decode("utf-8"),
        object_pairs_hook=_reject_duplicate_keys,
        parse_constant=reject_constant,
    )


def _pushover_failure(status: int) -> ProviderFailure:
    # Pushover documents every 4xx as a request that must be corrected.
    if 400 <= status < 500:
        return ProviderFailure("configuration_failure", "provider_rejected")
    return ProviderFailure(
        "result_unknown",
        "provider_result_unknown",
        retryable=True,
        ambiguous=True,
    )


def _ntfy_failure(status: int) -> ProviderFailure:
    if status == 429:
        return ProviderFailure(
            "retryable_failure", "provider_rate_limited", retryable=True
        )
    if 400 <= status < 500:
        return ProviderFailure("configuration_failure", "provider_rejected")
    return ProviderFailure(
        "result_unknown",
        "provider_result_unknown",
        retryable=True,
        ambiguous=True,
    )


def _read_json(opener, request: Request, timeout: float, classify):
    try:
        with opener.open(request, timeout=timeout) as provider_response:
            if provider_response.status != 200:
                raise classify(provider_response.status)
            raw = provider_response.read(MAX_PROVIDER_RESPONSE_BYTES + 1)
    except HTTPError as exc:
        raise classify(exc.code) from None
    except OSError:
        raise ProviderFailure(
            "result_unknown",
            "provider_result_unknown",
            retryable=True,
            ambiguous=True,
        ) from None
    if len(raw) > MAX_PROVIDER_RESPONSE_BYTES:
        raise ProviderFailure(
            "result_unknown",
            "provider_result_unknown",
            retryable=True,
            ambiguous=True,
        )
    try:
        return _decode_json(raw)
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
        raise ProviderFailure(
            "result_unknown",
            "provider_result_unknown",
            retryable=True,
            ambiguous=True,
        ) from None


def _notification(event_id: str, kind: str, opaque_event: bytes | None):
    if kind == "test.triggered" and opaque_event is None:
        return f"Garmin TEST {event_id}", TEST_MESSAGE
    if kind in {"live.triggered", "location.updated"} and opaque_event is not None:
        try:
            message = opaque_event.decode("ascii")
        except UnicodeDecodeError:
            raise ProviderFailure(
                "configuration_failure", "invalid_opaque_event"
            ) from None
        if not 1 <= len(message) <= 1024:
            raise ProviderFailure("configuration_failure", "invalid_opaque_event")
        label = "LIVE" if kind == "live.triggered" else "LOCATION"
        return f"Garmin {label} {event_id}", message
    raise ProviderFailure("configuration_failure", "invalid_opaque_event")


class PushoverClient:
    URL = "https://api.pushover.net/1/messages.json"
    RECEIPT_URL = "https://api.pushover.net/1/receipts/{receipt}.json"
    CANCEL_URL = "https://api.pushover.net/1/receipts/{receipt}/cancel.json"

    def __init__(
        self,
        app_token: str,
        user_key: str,
        timeout: float = 10,
        opener=None,
        emergency_retry: int = 60,
    ):
        if not TOKEN_RE.fullmatch(app_token or "") or not TOKEN_RE.fullmatch(
            user_key or ""
        ):
            raise ValueError(
                "Pushover token and user key must each be 30 alphanumeric characters"
            )
        if not 1 <= timeout <= 60:
            raise ValueError("provider timeout must be between 1 and 60 seconds")
        if not 30 <= emergency_retry <= 3600:
            raise ValueError("Pushover emergency retry must be between 30 and 3600 seconds")
        self.app_token = app_token
        self.user_key = user_key
        self.configuration_fingerprint = _configuration_fingerprint(
            "pushover", app_token, user_key
        )
        self.timeout = timeout
        self.emergency_retry = emergency_retry
        self.opener = opener or build_opener(_NoRedirects())

    def submit(
        self,
        event_id: str,
        *,
        kind: str = "test.triggered",
        opaque_event: bytes | None = None,
        emergency: bool = False,
        expires_at: int | None = None,
        now: int | None = None,
    ) -> Submission:
        title, message = _notification(event_id, kind, opaque_event)
        values = {
            "token": self.app_token,
            "user": self.user_key,
            "title": title,
            "message": message,
        }
        if emergency:
            if expires_at is None or now is None or expires_at <= now:
                raise ProviderFailure(
                    "configuration_failure", "invalid_emergency_expiry"
                )
            values.update(
                priority="2",
                retry=str(self.emergency_retry),
                expire=str(min(10_800, expires_at - now)),
            )
        request = Request(
            self.URL,
            data=urlencode(values).encode("utf-8"),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        decoded = _read_json(
            self.opener, request, self.timeout, _pushover_failure
        )
        if type(decoded) is dict and type(decoded.get("status")) is int:
            if decoded["status"] != 1:
                raise ProviderFailure("configuration_failure", "provider_rejected")
        if (
            type(decoded) is not dict
            or type(decoded.get("status")) is not int
            or decoded["status"] != 1
            or type(decoded.get("request")) is not str
            or not REFERENCE_RE.fullmatch(decoded["request"])
        ):
            raise ProviderFailure(
                "result_unknown",
                "provider_result_unknown",
                retryable=True,
                ambiguous=True,
            )
        receipt = decoded.get("receipt")
        if emergency and (
            type(receipt) is not str or not PUSHOVER_RECEIPT_RE.fullmatch(receipt)
        ):
            raise ProviderFailure(
                "result_unknown",
                "provider_result_unknown",
                retryable=True,
                ambiguous=True,
            )
        return Submission(decoded["request"], receipt if emergency else None)

    def poll_receipt(self, receipt: str) -> ReceiptEvidence:
        if type(receipt) is not str or not PUSHOVER_RECEIPT_RE.fullmatch(receipt):
            raise ProviderFailure("configuration_failure", "invalid_provider_receipt")
        request = Request(
            self.RECEIPT_URL.format(receipt=quote(receipt, safe=""))
            + "?"
            + urlencode({"token": self.app_token}),
            method="GET",
        )
        decoded = _read_json(
            self.opener, request, self.timeout, _pushover_failure
        )
        if type(decoded) is dict and type(decoded.get("status")) is int:
            if decoded["status"] != 1:
                raise ProviderFailure("configuration_failure", "provider_rejected")
        if (
            type(decoded) is not dict
            or type(decoded.get("status")) is not int
            or decoded["status"] != 1
            or type(decoded.get("acknowledged")) is not int
            or decoded["acknowledged"] not in (0, 1)
            or type(decoded.get("expired")) is not int
            or decoded["expired"] not in (0, 1)
        ):
            raise ProviderFailure(
                "result_unknown", "provider_result_unknown", retryable=True
            )

        def timestamp(name: str) -> int | None:
            value = decoded.get(name)
            if value in (None, 0):
                return None
            if type(value) is not int or not 0 < value <= 9_223_372_036_854_775_807:
                raise ProviderFailure(
                    "result_unknown", "provider_result_unknown", retryable=True
                )
            return value

        acknowledged_at = timestamp("acknowledged_at")
        if decoded["acknowledged"] and acknowledged_at is None:
            raise ProviderFailure(
                "result_unknown", "provider_result_unknown", retryable=True
            )
        return ReceiptEvidence(
            bool(decoded["acknowledged"]),
            acknowledged_at,
            bool(decoded["expired"]),
            timestamp("last_delivered_at"),
        )

    def cancel_receipt(self, receipt: str):
        if type(receipt) is not str or not PUSHOVER_RECEIPT_RE.fullmatch(receipt):
            raise ProviderFailure("configuration_failure", "invalid_provider_receipt")
        request = Request(
            self.CANCEL_URL.format(receipt=quote(receipt, safe="")),
            data=urlencode({"token": self.app_token}).encode("utf-8"),
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        decoded = _read_json(
            self.opener, request, self.timeout, _pushover_failure
        )
        if (
            type(decoded) is not dict
            or type(decoded.get("status")) is not int
            or decoded["status"] != 1
        ):
            if type(decoded) is dict and type(decoded.get("status")) is int:
                raise ProviderFailure("configuration_failure", "provider_rejected")
            raise ProviderFailure(
                "result_unknown", "provider_result_unknown", retryable=True
            )


class NtfyClient:
    def __init__(
        self,
        url: str,
        topic: str,
        token: str,
        timeout: float = 10,
        opener=None,
    ):
        parsed = urlsplit(url)
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
        ):
            raise ValueError(
                "ntfy URL must be an HTTPS instance root without credentials, query, or fragment"
            )
        if not TOPIC_RE.fullmatch(topic or ""):
            raise ValueError("ntfy topic must contain 1-64 URL-safe characters")
        if type(token) is not str or not NTFY_TOKEN_RE.fullmatch(token):
            raise ValueError("ntfy token must be a 32-character tk_ access token")
        if not 1 <= timeout <= 60:
            raise ValueError("provider timeout must be between 1 and 60 seconds")
        self.url = f"{parsed.scheme}://{parsed.netloc}/"
        self.topic = topic
        self.token = token
        self.configuration_fingerprint = _configuration_fingerprint(
            "ntfy", self.url, topic, token
        )
        self.timeout = timeout
        self.opener = opener or build_opener(_NoRedirects())

    def submit(
        self,
        event_id: str,
        *,
        kind: str = "test.triggered",
        opaque_event: bytes | None = None,
        emergency: bool = False,
        expires_at: int | None = None,
        now: int | None = None,
    ) -> Submission:
        del expires_at, now
        title, message = _notification(event_id, kind, opaque_event)
        body = json.dumps(
            {
                "topic": self.topic,
                "title": title,
                "message": message,
                "priority": 5 if emergency else 4,
            },
            separators=(",", ":"),
        ).encode("utf-8")
        request = Request(
            self.url,
            data=body,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "X-Sequence-ID": event_id,
            },
            method="POST",
        )
        decoded = _read_json(self.opener, request, self.timeout, _ntfy_failure)
        if (
            type(decoded) is not dict
            or type(decoded.get("id")) is not str
            or not REFERENCE_RE.fullmatch(decoded["id"])
        ):
            raise ProviderFailure(
                "result_unknown",
                "provider_result_unknown",
                retryable=True,
                ambiguous=True,
            )
        return Submission(decoded["id"])

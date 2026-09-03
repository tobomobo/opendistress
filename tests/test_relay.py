# SPDX-License-Identifier: MIT

import base64
import hashlib
import http.client
import io
import json
import os
import socket
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock
from contextlib import redirect_stderr
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs

from relay.server import (
    MAX_PROVIDER_RESPONSE_BYTES,
    MAX_CREATED_AT,
    NtfyClient,
    PushoverClient,
    ProviderFailure,
    ReceiptEvidence,
    Relay,
    Submission,
    TEST_MESSAGE,
    accepted_response,
    canonical_event,
    canonical_result,
    canonical_status_query,
    canonical_status_result,
    canonical_v2_event,
    canonical_v2_result,
    load_devices,
    load_routes,
    main,
    make_server,
    parse_json,
    signed_status_response,
    signature_for,
    validate_status_query,
    validate_event,
    validate_v2_event,
)

ROOT = Path(__file__).parents[1]
FIXTURE = ROOT / "protocol/fixtures/test-ping-v1.json"
REORDERED_FIXTURE = ROOT / "protocol/fixtures/test-ping-v1-reordered.json"
VECTOR = ROOT / "protocol/fixtures/signature-v1.txt"
LIVE_FIXTURE = ROOT / "protocol/fixtures/live-trigger-v2.json"
LOCATION_FIXTURE = ROOT / "protocol/fixtures/location-updated-v2.json"
V2_VECTOR = ROOT / "protocol/fixtures/encryption-v2.txt"
STATUS_FIXTURE = ROOT / "protocol/fixtures/status-query-v2.json"
STATUS_VECTOR = ROOT / "protocol/fixtures/status-v2.txt"
KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
KEY = bytes.fromhex(KEY_HEX)
NOW = 1_788_105_600


def vector():
    return dict(
        line.split("=", 1)
        for line in VECTOR.read_text().splitlines()
        if line and not line.startswith("#")
    )


def status_vector():
    return dict(
        line.split("=", 1)
        for line in STATUS_VECTOR.read_text().splitlines()
        if line and not line.startswith("#")
    )


def event_body(event):
    return json.dumps(event, separators=(",", ":")).encode("utf-8")


def devices(enabled=True):
    event = parse_json(FIXTURE.read_bytes())
    return {
        event["device_id"]: {"key": KEY, "live_key": KEY, "enabled": enabled}
    }


def route_config(*, test_emergency=True, two_recipients=False):
    event = parse_json(FIXTURE.read_bytes())
    members = ["primary", "backup"] if two_recipients else ["primary"]
    recipients = {
        "primary": {
            "enabled": True,
            "routes": [{"transport": "pushover", "user_key": "u" * 30}],
        }
    }
    if two_recipients:
        recipients["backup"] = {
            "enabled": True,
            "routes": [
                {
                    "transport": "ntfy",
                    "url": "https://ntfy.example/",
                    "topic": "private-topic",
                    "token": "tk_" + "n" * 29,
                }
            ],
        }
    return {
        "test_emergency": test_emergency,
        "device_groups": {event["device_id"]: "household"},
        "groups": {"household": members},
        "recipients": recipients,
    }


class FakeProvider:
    def __init__(self, failure=None, block=False, fingerprint=None):
        self.failure = failure
        self.configuration_fingerprint = fingerprint or hashlib.sha256(
            b"fake-provider"
        ).digest()
        self.event_ids = []
        self.calls = []
        self.lock = threading.Lock()
        self.started = threading.Event()
        self.release = threading.Event()
        if not block:
            self.release.set()

    def submit(self, event_id, **kwargs):
        with self.lock:
            self.event_ids.append(event_id)
            self.calls.append((event_id, kwargs))
        self.started.set()
        self.release.wait(2)
        if self.failure:
            raise self.failure
        return Submission("provider-request-id")


class FakeEmergencyProvider(FakeProvider):
    def __init__(self, *, evidence=None, cancel_failure=None, fingerprint=None):
        super().__init__(fingerprint=fingerprint)
        self.evidence = evidence or ReceiptEvidence(False, None, False, None)
        self.cancel_failure = cancel_failure
        self.receipt_calls = []
        self.cancel_calls = []

    def submit(self, event_id, **kwargs):
        super().submit(event_id, **kwargs)
        return Submission("provider-request-id", "r" * 30)

    def poll_receipt(self, receipt):
        self.receipt_calls.append(receipt)
        return self.evidence

    def cancel_receipt(self, receipt):
        self.cancel_calls.append(receipt)
        if self.cancel_failure:
            raise self.cancel_failure


class ProtocolTests(unittest.TestCase):
    def test_golden_request_and_response_vectors(self):
        values = vector()
        event = parse_json(FIXTURE.read_bytes())
        request = canonical_event(event)
        result = canonical_result(event["event_id"])

        self.assertEqual(len(request), int(values["canonical_request_length"]))
        self.assertEqual(request.hex(), values["canonical_request_hex"])
        self.assertEqual(hashlib.sha256(request).hexdigest(), values["canonical_request_sha256"])
        self.assertEqual(signature_for(KEY, request), values["request_signature"])
        self.assertEqual(len(result), int(values["canonical_response_length"]))
        self.assertEqual(result.hex(), values["canonical_response_hex"])
        self.assertEqual(hashlib.sha256(result).hexdigest(), values["canonical_response_sha256"])
        self.assertEqual(signature_for(KEY, result), values["response_signature"])

    def test_wire_order_and_whitespace_do_not_change_signed_semantics(self):
        compact = parse_json(FIXTURE.read_bytes())
        reordered = parse_json(REORDERED_FIXTURE.read_bytes())
        self.assertEqual(compact, reordered)
        self.assertEqual(canonical_event(compact), canonical_event(reordered))
        self.assertIsNone(validate_event(compact))

    def test_status_request_and_response_vectors(self):
        values = status_vector()
        query = parse_json(STATUS_FIXTURE.read_bytes())
        request = canonical_status_query(query)
        result = canonical_status_result(
            query["request_id"],
            query["incident_id"],
            query["device_id"],
            values["state"],
            int(values["checked_at"]),
        )
        self.assertIsNone(validate_status_query(query))
        self.assertEqual(len(request), int(values["canonical_request_length"]))
        self.assertEqual(request.hex(), values["canonical_request_hex"])
        self.assertEqual(signature_for(KEY, request, 2), values["request_signature"])
        self.assertEqual(len(result), int(values["canonical_response_length"]))
        self.assertEqual(result.hex(), values["canonical_response_hex"])
        self.assertEqual(signature_for(KEY, result, 2), values["response_signature"])
        self.assertEqual(
            signed_status_response(
                KEY, query, values["state"], int(values["checked_at"])
            ),
            json.loads(values["response_json"]),
        )

    def test_schema_and_runtime_validator_cover_the_same_fields(self):
        schema = json.loads((ROOT / "protocol/alert-v1.schema.json").read_text())
        self.assertEqual(set(schema["required"]), set(schema["properties"]))
        self.assertEqual(set(schema["required"]), set(parse_json(FIXTURE.read_bytes())))


class V2ProtocolTests(unittest.TestCase):
    def vectors(self):
        sections = {}
        current = None
        for line in V2_VECTOR.read_text().splitlines():
            if not line or line.startswith("#"):
                continue
            if line.startswith("["):
                current = line[1:-1]
                sections[current] = {}
            elif current is not None:
                key, value = line.split("=", 1)
                sections[current][key] = value
        return sections

    def test_published_request_and_response_vectors(self):
        for fixture, section in (
            (LIVE_FIXTURE, "live.triggered"),
            (LOCATION_FIXTURE, "location.updated"),
        ):
            with self.subTest(kind=section):
                event = parse_json(fixture.read_bytes())
                values = self.vectors()[section]
                request = canonical_v2_event(event)
                result = canonical_v2_result(event["event_id"])
                self.assertIsNone(validate_v2_event(event))
                self.assertEqual(len(request), int(values["canonical_request_length"]))
                self.assertEqual(request.hex(), values["canonical_request_hex"])
                self.assertEqual(
                    signature_for(KEY, request, 2), values["request_signature"]
                )
                self.assertEqual(len(result), int(values["canonical_result_length"]))
                self.assertEqual(result.hex(), values["canonical_result_hex"])
                self.assertEqual(
                    signature_for(KEY, result, 2), values["response_signature"]
                )

    def test_v2_validator_rejects_ambiguous_or_wrong_shapes(self):
        event = parse_json(LIVE_FIXTURE.read_bytes())
        changes = (
            lambda item: item.update(extra=True),
            lambda item: item.update(v=True),
            lambda item: item.update(sequence=0.0),
            lambda item: item.update(kind={}),
            lambda item: item.update(incident_id="AQEBAQEBAQEBAQEBAQEBAQ"),
            lambda item: item.update(expires_at=item["created_at"]),
            lambda item: item.update(expires_at=item["created_at"] + 86_401),
            lambda item: item["payload"].update(extra=True),
            lambda item: item["payload"].update(iv=item["payload"]["iv"][:-1] + "B"),
            lambda item: item["payload"].update(tag=item["payload"]["tag"] + "="),
        )
        for change in changes:
            with self.subTest(change=change):
                changed = json.loads(json.dumps(event))
                change(changed)
                self.assertEqual(validate_v2_event(changed), "invalid_event")


class RelayTests(unittest.TestCase):
    def setUp(self):
        self.event = parse_json(FIXTURE.read_bytes())
        self.body = FIXTURE.read_bytes()
        self.signature = signature_for(KEY, canonical_event(self.event))

    def relay(self, provider=None, **kwargs):
        relay = Relay(devices(), provider or FakeProvider(), clock=lambda: NOW, **kwargs)
        self.addCleanup(relay.close)
        return relay

    def test_accepts_once_and_resolves_semantic_duplicate(self):
        provider = FakeProvider()
        relay = self.relay(provider)
        first = relay.process(self.body, self.signature)
        duplicate = relay.process(REORDERED_FIXTURE.read_bytes(), self.signature)

        self.assertEqual(first, duplicate)
        self.assertEqual(first[0], 202)
        self.assertEqual(first[1], accepted_response(KEY, self.event["event_id"]))
        self.assertEqual(provider.event_ids, [])
        self.assertEqual(relay.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 1)
        self.assertEqual(
            relay.db.execute("SELECT COUNT(*) FROM deliveries").fetchone()[0], 1
        )
        self.assertTrue(relay.run_worker_once())
        self.assertFalse(relay.run_worker_once())
        self.assertEqual(provider.event_ids, [self.event["event_id"]])
        self.assertEqual(
            relay.db.execute("SELECT state FROM deliveries").fetchone()[0],
            "provider_accepted",
        )

    def test_rejects_reused_event_id_with_different_signed_semantics(self):
        relay = self.relay()
        self.assertEqual(relay.process(self.body, self.signature)[0], 202)
        changed = dict(self.event)
        changed["created_at"] += 1
        changed["expires_at"] += 1
        changed_body = event_body(changed)

        status, payload = relay.process(
            changed_body, signature_for(KEY, canonical_event(changed))
        )
        self.assertEqual(status, 409)
        self.assertEqual(payload["code"], "event_id_conflict")

    def test_changed_semantics_fail_with_original_signature(self):
        provider = FakeProvider()
        relay = self.relay(provider)
        changed = dict(self.event)
        changed["created_at"] += 1
        changed["expires_at"] += 1
        status, payload = relay.process(event_body(changed), self.signature)
        self.assertEqual(status, 401)
        self.assertEqual(payload["code"], "authentication_failed")
        self.assertEqual(provider.event_ids, [])

    def test_rejects_malformed_and_noncanonical_events_before_provider(self):
        provider = FakeProvider()
        relay = self.relay(provider)
        invalid_events = []
        for change in (
            lambda event: event.update(extra=True),
            lambda event: event.pop("payload"),
            lambda event: event.update(v=True),
            lambda event: event.update(sequence=0.0),
            lambda event: event.update(created_at=float(NOW)),
            lambda event: event.update(
                created_at=MAX_CREATED_AT + 1, expires_at=MAX_CREATED_AT + 901
            ),
            lambda event: event.update(incident_id="ICEiIyQlJicoKSorLC0uLw"),
            lambda event: event.update(event_id=event["event_id"][:-1] + "B"),
        ):
            changed = dict(self.event)
            change(changed)
            invalid_events.append(event_body(changed))

        malformed = [
            self.body.replace(b'{"v":1,', b'{"v":1,"v":1,', 1),
            self.body + b"{}",
            b'{"device_id":"\xff"}',
            b'{"v":NaN}',
            self.body.replace(b'"created_at":1788105600', b'"created_at":-0'),
        ]
        for body in invalid_events:
            with self.subTest(body=body[:40]):
                status, payload = relay.process(body, self.signature)
                self.assertEqual((status, payload["code"]), (422, "invalid_event"))
        for body in malformed:
            with self.subTest(body=body[:40]):
                status, payload = relay.process(body, self.signature)
                self.assertEqual((status, payload["code"]), (400, "invalid_json"))
        self.assertEqual(provider.event_ids, [])

    def test_rejects_missing_short_padded_and_wrong_signatures(self):
        relay = self.relay()
        for signature in (None, "v1=short", self.signature + "=", "v2=" + "A" * 43):
            with self.subTest(signature=signature):
                status, payload = relay.process(self.body, signature)
                self.assertEqual((status, payload["code"]), (401, "authentication_failed"))

    def test_unknown_disabled_and_bad_signature_are_indistinguishable(self):
        unknown_event = dict(self.event)
        unknown_event["device_id"] = "ICEiIyQlJicoKSorLC0uLw"
        unknown_body = event_body(unknown_event)
        unknown = self.relay().process(
            unknown_body, signature_for(KEY, canonical_event(unknown_event))
        )

        disabled = Relay(devices(False), FakeProvider(), clock=lambda: NOW)
        self.addCleanup(disabled.close)
        disabled_result = disabled.process(self.body, self.signature)
        bad_signature = self.relay().process(self.body, "v1=" + "A" * 43)

        self.assertEqual(unknown, disabled_result)
        self.assertEqual(disabled_result, bad_signature)
        self.assertEqual(unknown[0], 401)

    def test_future_clock_skew_is_inclusive_and_expiry_is_exclusive(self):
        for offset, expected in [(-301, 422), (-300, 202), (899, 202), (900, 422)]:
            with self.subTest(offset=offset):
                relay = Relay(devices(), FakeProvider(), clock=lambda o=offset: NOW + o)
                try:
                    self.assertEqual(relay.process(self.body, self.signature)[0], expected)
                finally:
                    relay.close()

    def test_recorded_acceptance_survives_restart_and_clock_window(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            first = Relay(devices(), provider, database, clock=lambda: NOW)
            self.assertEqual(first.process(self.body, self.signature)[0], 202)
            first.close()

            second_provider = FakeProvider()
            second = Relay(devices(), second_provider, database, clock=lambda: NOW + 600)
            try:
                status, payload = second.process(self.body, self.signature)
                self.assertEqual(status, 202)
                self.assertEqual(payload["result"], "durably_accepted")
                self.assertTrue(second.run_worker_once())
                self.assertEqual(second_provider.event_ids, [self.event["event_id"]])
            finally:
                second.close()

    def test_provider_fingerprint_binds_snapshot_delivery_and_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            fingerprint = hashlib.sha256(b"provider-a").digest()
            first = Relay(
                devices(),
                FakeProvider(fingerprint=fingerprint),
                database,
                clock=lambda: NOW,
            )
            self.assertEqual(first.process(self.body, self.signature)[0], 202)
            snapshot = first.db.execute(
                "SELECT provider_fingerprint FROM incident_routes"
            ).fetchone()[0]
            delivery = first.db.execute(
                "SELECT provider_fingerprint FROM deliveries"
            ).fetchone()[0]
            self.assertEqual((snapshot, delivery), (fingerprint, fingerprint))
            first.close()

            with self.assertRaisesRegex(ValueError, "does not match"):
                Relay(
                    devices(),
                    FakeProvider(fingerprint=hashlib.sha256(b"provider-b").digest()),
                    database,
                    clock=lambda: NOW,
                )
            correct = Relay(
                devices(),
                FakeProvider(fingerprint=fingerprint),
                database,
                clock=lambda: NOW,
            )
            self.addCleanup(correct.close)
            self.assertTrue(correct.run_worker_once())

    def test_named_route_snapshot_survives_active_incident_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            routes = route_config()
            first = Relay(
                devices(),
                None,
                database,
                clock=lambda: NOW,
                routes=routes,
                transports={("primary", "pushover"): provider},
            )
            self.assertEqual(first.process(self.body, self.signature)[0], 202)
            first.close()

            second = Relay(
                devices(),
                None,
                database,
                clock=lambda: NOW,
                routes=routes,
                transports={("primary", "pushover"): provider},
            )
            self.addCleanup(second.close)
            self.assertEqual(
                [
                    tuple(row)
                    for row in second.db.execute(
                        "SELECT recipient_id, transport FROM incident_routes"
                    )
                ],
                [("primary", "pushover")],
            )
            self.assertTrue(second.run_worker_once())

    def test_schema4_migration_does_not_bless_legacy_routes(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            relay = Relay(devices(), provider, database, clock=lambda: NOW)
            self.assertEqual(relay.process(self.body, self.signature)[0], 202)
            relay.close()
            connection = sqlite3.connect(database)
            connection.execute(
                "ALTER TABLE incident_routes DROP COLUMN provider_fingerprint"
            )
            connection.execute(
                "ALTER TABLE deliveries DROP COLUMN provider_fingerprint"
            )
            connection.execute("PRAGMA user_version=4")
            connection.commit()
            connection.close()

            with self.assertRaisesRegex(ValueError, "does not match"):
                Relay(devices(), provider, database, clock=lambda: NOW)
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 5)
                self.assertIn(
                    "provider_fingerprint",
                    {
                        row[1]
                        for row in connection.execute(
                            "PRAGMA table_info(incident_routes)"
                        )
                    },
                )
                self.assertIsNone(
                    connection.execute(
                        "SELECT provider_fingerprint FROM incident_routes"
                    ).fetchone()[0]
                )
            finally:
                connection.close()

    def test_concurrent_wrong_provider_cannot_claim_valid_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            wrong = FakeProvider(fingerprint=hashlib.sha256(b"wrong").digest())
            correct = FakeProvider(fingerprint=hashlib.sha256(b"correct").digest())
            wrong_relay = Relay(devices(), wrong, database, clock=lambda: NOW)
            correct_relay = Relay(devices(), correct, database, clock=lambda: NOW)
            self.addCleanup(wrong_relay.close)
            self.addCleanup(correct_relay.close)
            self.assertEqual(
                correct_relay.process(self.body, self.signature)[0], 202
            )

            self.assertIsNone(wrong_relay._claim_delivery())
            self.assertEqual(
                correct_relay.db.execute("SELECT state FROM deliveries").fetchone()[0],
                "pending",
            )
            self.assertTrue(correct_relay.run_worker_once())
            self.assertEqual(wrong.event_ids, [])
            self.assertEqual(correct.event_ids, [self.event["event_id"]])

    def test_wrong_provider_cannot_recover_a_foreign_lease(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            clock = [NOW]
            wrong = FakeProvider(fingerprint=hashlib.sha256(b"wrong").digest())
            correct = FakeProvider(fingerprint=hashlib.sha256(b"correct").digest())
            wrong_relay = Relay(
                devices(), wrong, database, clock=lambda: clock[0], lease_seconds=5
            )
            correct_relay = Relay(
                devices(), correct, database, clock=lambda: clock[0], lease_seconds=5
            )
            self.addCleanup(wrong_relay.close)
            self.addCleanup(correct_relay.close)
            self.assertEqual(
                correct_relay.process(self.body, self.signature)[0], 202
            )
            stale = correct_relay._claim_delivery()
            clock[0] += 6

            self.assertIsNone(wrong_relay._claim_delivery())
            still_leased = correct_relay.db.execute(
                "SELECT state, lease_token FROM deliveries"
            ).fetchone()
            self.assertEqual(tuple(still_leased), ("attempting", stale["claim_token"]))
            current = correct_relay._claim_delivery()
            self.assertIsNotNone(current)
            self.assertNotEqual(stale["claim_token"], current["claim_token"])
            self.assertFalse(
                correct_relay._finish_delivery(
                    stale["claim_token"], submission=Submission("stale")
                )
            )
            self.assertTrue(
                correct_relay._finish_delivery(
                    current["claim_token"], submission=Submission("current")
                )
            )

    def test_wrong_provider_cannot_expire_a_valid_pending_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            clock = [NOW]
            wrong = FakeProvider(fingerprint=hashlib.sha256(b"wrong").digest())
            correct = FakeProvider(fingerprint=hashlib.sha256(b"correct").digest())
            wrong_relay = Relay(devices(), wrong, database, clock=lambda: clock[0])
            correct_relay = Relay(
                devices(), correct, database, clock=lambda: clock[0]
            )
            self.addCleanup(wrong_relay.close)
            self.addCleanup(correct_relay.close)
            self.assertEqual(
                correct_relay.process(self.body, self.signature)[0], 202
            )
            clock[0] = self.event["expires_at"]

            self.assertIsNone(wrong_relay._claim_delivery())
            self.assertEqual(
                correct_relay.db.execute("SELECT state FROM deliveries").fetchone()[0],
                "pending",
            )
            self.assertIsNone(correct_relay._claim_delivery())
            self.assertEqual(
                correct_relay.db.execute("SELECT state FROM deliveries").fetchone()[0],
                "expired",
            )

    def test_two_connections_resolve_intake_and_claim_races(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            first = Relay(devices(), provider, database, clock=lambda: NOW)
            second = Relay(devices(), provider, database, clock=lambda: NOW)
            self.addCleanup(first.close)
            self.addCleanup(second.close)
            results = []
            threads = [
                threading.Thread(
                    target=lambda relay=relay: results.append(
                        relay.process(self.body, self.signature)
                    )
                )
                for relay in (first, second)
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(2)
            self.assertEqual([status for status, _ in results], [202, 202])

            claims = []
            threads = [
                threading.Thread(
                    target=lambda relay=relay: claims.append(relay._claim_delivery())
                )
                for relay in (first, second)
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(2)
            claim = next(item for item in claims if item is not None)
            self.assertEqual(sum(item is not None for item in claims), 1)
            self.assertTrue(
                first._finish_delivery(
                    claim["claim_token"], submission=Submission("provider-request-id")
                )
                or second._finish_delivery(
                    claim["claim_token"], submission=Submission("provider-request-id")
                )
            )
            self.assertEqual(provider.event_ids, [])

    def test_expired_lease_is_retried_and_stale_worker_is_fenced(self):
        current = [NOW]
        relay = Relay(
            devices(), FakeProvider(), clock=lambda: current[0], lease_seconds=5
        )
        self.addCleanup(relay.close)
        self.assertEqual(relay.process(self.body, self.signature)[0], 202)
        first = relay._claim_delivery()
        current[0] += 6
        second = relay._claim_delivery()

        self.assertIsNotNone(first)
        self.assertIsNotNone(second)
        self.assertNotEqual(first["claim_token"], second["claim_token"])
        self.assertFalse(
            relay._finish_delivery(
                first["claim_token"], submission=Submission("stale-reference")
            )
        )
        self.assertTrue(
            relay._finish_delivery(
                second["claim_token"], submission=Submission("current-reference")
            )
        )
        delivery = relay.db.execute(
            "SELECT state, attempt_count, may_have_accepted, provider_reference FROM deliveries"
        ).fetchone()
        self.assertEqual(tuple(delivery), ("provider_accepted", 2, 1, "current-reference"))
        attempts = relay.db.execute(
            "SELECT outcome, ambiguous FROM delivery_attempts ORDER BY attempt_no"
        ).fetchall()
        self.assertEqual([tuple(row) for row in attempts], [("result_unknown", 1), ("provider_accepted", 0)])

    def test_attempt_metadata_is_purged_after_24_hours(self):
        current = [NOW]
        relay = Relay(devices(), FakeProvider(), clock=lambda: current[0])
        self.addCleanup(relay.close)
        self.assertEqual(relay.process(self.body, self.signature)[0], 202)
        current[0] += 86_400 + 901
        relay.purge_expired()
        self.assertEqual(relay.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 0)
        self.assertEqual(
            relay.db.execute("SELECT COUNT(*) FROM deliveries").fetchone()[0], 0
        )
        self.assertEqual(
            relay.db.execute("SELECT COUNT(*) FROM delivery_attempts").fetchone()[0],
            0,
        )
        status, payload = relay.process(self.body, self.signature)
        self.assertEqual(
            (status, payload["code"]), (422, "timestamp_out_of_window")
        )

    def test_clock_rollback_after_cross_connection_cleanup_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            relay = Relay(devices(), provider, database, clock=lambda: NOW)
            self.addCleanup(relay.close)
            self.assertEqual(relay.process(self.body, self.signature)[0], 202)

            cleaner = Relay(
                devices(), FakeProvider(), database, clock=lambda: NOW + 86_400 + 901
            )
            cleaner.purge_expired()
            cleaner.close()

            new_event = dict(self.event)
            new_event["event_id"] = "AQEBAQEBAQEBAQEBAQEBAQ"
            new_event["incident_id"] = new_event["event_id"]
            status, payload = relay.process(
                event_body(new_event),
                signature_for(KEY, canonical_event(new_event)),
            )
            self.assertEqual((status, payload["code"]), (503, "clock_untrusted"))
            self.assertEqual(provider.event_ids, [])

    def test_ambiguous_failure_retries_but_configuration_failure_is_terminal(self):
        current = [NOW]
        ambiguous = ProviderFailure(
            "result_unknown",
            "provider_result_unknown",
            retryable=True,
            ambiguous=True,
        )
        provider = FakeProvider(ambiguous)
        relay = Relay(devices(), provider, clock=lambda: current[0])
        self.addCleanup(relay.close)
        self.assertEqual(relay.process(self.body, self.signature)[0], 202)
        self.assertTrue(relay.run_worker_once())
        self.assertEqual(
            tuple(relay.db.execute("SELECT state, may_have_accepted FROM deliveries").fetchone()),
            ("retry_wait", 1),
        )
        provider.failure = None
        current[0] += 5
        self.assertTrue(relay.run_worker_once())
        self.assertEqual(provider.event_ids, [self.event["event_id"]] * 2)

        terminal = FakeProvider(
            ProviderFailure("configuration_failure", "provider_rejected")
        )
        other_event = dict(self.event)
        other_event["event_id"] = "AQEBAQEBAQEBAQEBAQEBAQ"
        other_event["incident_id"] = other_event["event_id"]
        other = Relay(devices(), terminal, clock=lambda: NOW)
        self.addCleanup(other.close)
        body = event_body(other_event)
        self.assertEqual(
            other.process(body, signature_for(KEY, canonical_event(other_event)))[0], 202
        )
        self.assertTrue(other.run_worker_once())
        terminal.failure = None
        current[0] += 300
        self.assertFalse(other.run_worker_once())
        self.assertEqual(
            other.db.execute("SELECT state FROM deliveries").fetchone()[0],
            "configuration_failure",
        )

    def test_twenty_concurrent_duplicates_make_one_outbox_item(self):
        provider = FakeProvider()
        relay = self.relay(provider)
        results = []
        duplicates = [
            threading.Thread(target=lambda: results.append(relay.process(self.body, self.signature)))
            for _ in range(20)
        ]
        for thread in duplicates:
            thread.start()
        for thread in duplicates:
            thread.join(1)
        self.assertEqual(sum(status == 202 for status, _ in results), 20)
        self.assertEqual(relay.db.execute("SELECT COUNT(*) FROM deliveries").fetchone()[0], 1)
        self.assertTrue(relay.run_worker_once())
        self.assertEqual(provider.event_ids, [self.event["event_id"]])

    def test_unexpected_provider_exception_does_not_log_its_secret(self):
        class BrokenProvider:
            configuration_fingerprint = hashlib.sha256(b"broken-provider").digest()

            def submit(self, _event_id):
                raise RuntimeError("SENTINEL_PROVIDER_SECRET")

        relay = self.relay(BrokenProvider())
        self.assertEqual(relay.process(self.body, self.signature)[0], 202)
        with self.assertLogs("opendistress-relay", level="ERROR") as captured:
            self.assertTrue(relay.run_worker_once())
        self.assertNotIn("SENTINEL_PROVIDER_SECRET", "\n".join(captured.output))

    def test_phase1_started_and_unknown_rows_migrate_terminal_without_sends(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            connection = sqlite3.connect(database)
            connection.execute(
                """
                CREATE TABLE test_events (
                    device_id TEXT NOT NULL, event_id TEXT NOT NULL,
                    canonical_sha256 BLOB NOT NULL, state TEXT NOT NULL,
                    provider_reference TEXT, created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (device_id, event_id)
                )
                """
            )
            ids = [
                self.event["event_id"],
                "AQEBAQEBAQEBAQEBAQEBAQ",
                "AgICAgICAgICAgICAgICAg",
            ]
            states = ["provider_call_started", "result_unknown", "provider_accepted"]
            for event_id, state in zip(ids, states):
                connection.execute(
                    "INSERT INTO test_events VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (
                        self.event["device_id"],
                        event_id,
                        hashlib.sha256(canonical_event(self.event)).digest(),
                        state,
                        "old-reference" if state == "provider_accepted" else None,
                        NOW,
                        NOW,
                    ),
                )
            connection.commit()
            connection.close()
            os.chmod(database, 0o600)

            provider = FakeProvider()
            relay = Relay(devices(), provider, database, clock=lambda: NOW)
            self.addCleanup(relay.close)
            states = [
                row[0]
                for row in relay.db.execute(
                    "SELECT state FROM deliveries ORDER BY delivery_id"
                )
            ]
            self.assertEqual(states, ["result_unknown", "result_unknown", "provider_accepted"])
            self.assertEqual(
                relay.db.execute("PRAGMA user_version").fetchone()[0], 5
            )
            self.assertTrue(
                all(
                    row[0] is None
                    for row in relay.db.execute(
                        "SELECT provider_fingerprint FROM deliveries"
                    )
                )
            )
            self.assertFalse(relay.run_worker_once())
            self.assertEqual(provider.event_ids, [])
            self.assertEqual(relay.process(self.body, self.signature)[0], 202)


class V2RelayTests(unittest.TestCase):
    def setUp(self):
        self.live = parse_json(LIVE_FIXTURE.read_bytes())
        self.location = parse_json(LOCATION_FIXTURE.read_bytes())
        self.live_signature = signature_for(KEY, canonical_v2_event(self.live), 2)
        self.location_signature = signature_for(
            KEY, canonical_v2_event(self.location), 2
        )

    def relay(self, provider=None, **kwargs):
        relay = Relay(devices(), provider or FakeProvider(), clock=lambda: NOW, **kwargs)
        self.addCleanup(relay.close)
        return relay

    def submit(self, relay, event):
        return relay.process_v2(
            event_body(event), signature_for(KEY, canonical_v2_event(event), 2)
        )

    def test_trigger_is_durable_opaque_and_forwards_the_compact_envelope(self):
        provider = FakeProvider()
        relay = self.relay(provider)
        status, payload = relay.process_v2(
            LIVE_FIXTURE.read_bytes(), self.live_signature
        )
        compact = event_body(self.live)

        self.assertEqual(status, 202)
        self.assertEqual(payload, accepted_response(KEY, self.live["event_id"], 2))
        stored = relay.db.execute(
            "SELECT protocol_version, incident_id, kind, sequence, opaque_json FROM events"
        ).fetchone()
        self.assertEqual(tuple(stored[:4]), (2, self.live["incident_id"], "live.triggered", 0))
        self.assertEqual(stored[4], compact)
        self.assertNotIn(bytes.fromhex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"), stored[4])
        self.assertTrue(relay.run_worker_once())
        self.assertEqual(
            provider.calls,
            [
                (
                    self.live["event_id"],
                    {
                        "kind": "live.triggered",
                        "opaque_event": compact,
                        "emergency": True,
                        "expires_at": self.live["expires_at"],
                        "now": NOW,
                    },
                )
            ],
        )

    def test_location_requires_trigger_and_advances_exact_sequence(self):
        provider = FakeProvider()
        relay = self.relay(provider)
        status, payload = relay.process_v2(
            LOCATION_FIXTURE.read_bytes(), self.location_signature
        )
        self.assertEqual((status, payload["code"]), (409, "incident_not_found"))

        self.assertEqual(
            relay.process_v2(LIVE_FIXTURE.read_bytes(), self.live_signature)[0], 202
        )
        first = relay.process_v2(
            LOCATION_FIXTURE.read_bytes(), self.location_signature
        )
        duplicate = relay.process_v2(
            LOCATION_FIXTURE.read_bytes(), self.location_signature
        )
        self.assertEqual(first, duplicate)
        self.assertEqual(first[0], 202)
        self.assertEqual(relay.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 2)
        self.assertEqual(
            relay.db.execute("SELECT COUNT(*) FROM deliveries").fetchone()[0], 2
        )
        self.assertEqual(
            relay.db.execute("SELECT next_sequence FROM incidents").fetchone()[0], 2
        )
        stored = relay.db.execute(
            "SELECT kind, sequence, opaque_json FROM events ORDER BY sequence"
        ).fetchall()
        self.assertEqual(
            [tuple(row[:2]) for row in stored],
            [("live.triggered", 0), ("location.updated", 1)],
        )
        self.assertEqual(stored[1]["opaque_json"], event_body(self.location))
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(relay.run_worker_once())
        self.assertEqual(
            provider.calls[1],
            (
                self.location["event_id"],
                {
                    "kind": "location.updated",
                    "opaque_event": event_body(self.location),
                    "emergency": False,
                    "expires_at": self.location["expires_at"],
                    "now": NOW,
                },
            ),
        )

    def test_location_linkage_and_event_id_conflicts_fail_closed(self):
        relay = self.relay()
        self.assertEqual(self.submit(relay, self.live)[0], 202)

        wrong = dict(self.location)
        wrong["created_at"] = self.live["created_at"] - 1
        status, payload = self.submit(relay, wrong)
        self.assertEqual((status, payload["code"]), (409, "incident_chronology_conflict"))

        wrong = dict(self.location)
        wrong["sequence"] = 2
        status, payload = self.submit(relay, wrong)
        self.assertEqual((status, payload["code"]), (409, "incident_sequence_conflict"))

        wrong = dict(self.location)
        wrong["expires_at"] += 1
        status, payload = self.submit(relay, wrong)
        self.assertEqual((status, payload["code"]), (409, "incident_expiry_conflict"))

        self.assertEqual(self.submit(relay, self.location)[0], 202)
        changed = json.loads(json.dumps(self.location))
        changed["payload"]["ciphertext"] = "AQEBAQEBAQEBAQEBAQEBAQ"
        status, payload = self.submit(relay, changed)
        self.assertEqual((status, payload["code"]), (409, "event_id_conflict"))

    def test_live_auth_is_separate_and_tampering_is_not_stored(self):
        live_key = bytes(range(32, 64))
        configured = devices()
        configured[self.live["device_id"]]["live_key"] = live_key
        relay = Relay(configured, FakeProvider(), clock=lambda: NOW)
        self.addCleanup(relay.close)

        self.assertEqual(
            relay.process_v2(LIVE_FIXTURE.read_bytes(), self.live_signature)[0], 401
        )
        tampered = json.loads(json.dumps(self.live))
        tampered["payload"]["ciphertext"] = "AQEBAQEBAQEBAQEBAQEBAQ"
        self.assertEqual(
            relay.process_v2(
                event_body(tampered),
                signature_for(live_key, canonical_v2_event(self.live), 2),
            )[0],
            401,
        )
        self.assertEqual(relay.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 0)
        self.assertEqual(
            relay.process_v2(
                LIVE_FIXTURE.read_bytes(),
                signature_for(live_key, canonical_v2_event(self.live), 2),
            )[0],
            202,
        )

    def test_only_one_unexpired_live_incident_is_active_per_device(self):
        clock = [NOW]
        relay = Relay(devices(), FakeProvider(), clock=lambda: clock[0])
        self.addCleanup(relay.close)
        self.assertEqual(self.submit(relay, self.live)[0], 202)

        second = json.loads(json.dumps(self.live))
        second["event_id"] = "AQEBAQEBAQEBAQEBAQEBAQ"
        second["incident_id"] = second["event_id"]
        second["created_at"] += 1
        status, payload = self.submit(relay, second)
        self.assertEqual((status, payload["code"]), (409, "active_incident_exists"))

        self.assertTrue(
            relay.resolve_incident(self.live["device_id"], self.live["incident_id"])
        )
        self.assertEqual(self.submit(relay, second)[0], 202)

        clock[0] = second["expires_at"]
        third = json.loads(json.dumps(second))
        third["event_id"] = "AgICAgICAgICAgICAgICAg"
        third["incident_id"] = third["event_id"]
        third["created_at"] = clock[0]
        third["expires_at"] = clock[0] + 3600
        self.assertEqual(self.submit(relay, third)[0], 202)
        lifecycles = relay.db.execute(
            "SELECT lifecycle FROM incidents ORDER BY created_at"
        ).fetchall()
        self.assertEqual([row[0] for row in lifecycles], ["resolved", "expired", "active"])

    def test_v2_future_skew_and_exclusive_expiry_are_strict(self):
        for offset, expected in (
            (-301, 422),
            (-300, 202),
            (3599, 202),
            (3600, 422),
        ):
            with self.subTest(offset=offset):
                relay = Relay(
                    devices(), FakeProvider(), clock=lambda offset=offset: NOW + offset
                )
                try:
                    self.assertEqual(
                        relay.process_v2(
                            LIVE_FIXTURE.read_bytes(), self.live_signature
                        )[0],
                        expected,
                    )
                finally:
                    relay.close()
        negative_zero = LIVE_FIXTURE.read_bytes().replace(
            b'"sequence":0', b'"sequence":-0'
        )
        self.assertEqual(
            self.relay().process_v2(negative_zero, self.live_signature)[0], 400
        )

    def test_v2_schema_migrates_existing_v1_events_without_enqueuing(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            connection = sqlite3.connect(database)
            connection.execute(
                """
                CREATE TABLE events (
                    device_id TEXT NOT NULL, event_id TEXT NOT NULL,
                    canonical_sha256 BLOB NOT NULL, created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL, accepted_at INTEGER NOT NULL,
                    PRIMARY KEY (device_id, event_id)
                )
                """
            )
            connection.execute(
                "INSERT INTO events VALUES (?, ?, ?, ?, ?, ?)",
                (
                    self.live["device_id"],
                    self.live["event_id"],
                    b"x" * 32,
                    NOW,
                    NOW + 900,
                    NOW,
                ),
            )
            connection.execute("PRAGMA user_version=2")
            connection.commit()
            connection.close()
            os.chmod(database, 0o600)

            relay = Relay(devices(), FakeProvider(), database, clock=lambda: NOW)
            self.addCleanup(relay.close)
            row = relay.db.execute(
                "SELECT protocol_version, incident_id, kind, sequence, opaque_json FROM events"
            ).fetchone()
            self.assertEqual(
                tuple(row),
                (1, self.live["event_id"], "test.triggered", 0, None),
            )
            self.assertFalse(relay.run_worker_once())


class StatusRelayTests(unittest.TestCase):
    def setUp(self):
        self.live = parse_json(LIVE_FIXTURE.read_bytes())
        self.query = parse_json(STATUS_FIXTURE.read_bytes())

    def make_relay(self, clock, configured=None):
        relay = Relay(
            configured or devices(),
            FakeProvider(),
            clock=lambda: clock[0],
        )
        self.addCleanup(relay.close)
        return relay

    def submit_live(self, relay, event=None, key=KEY):
        event = event or self.live
        return relay.process_v2(
            event_body(event), signature_for(key, canonical_v2_event(event), 2)
        )

    def query_status(self, relay, query=None, key=KEY):
        query = query or self.query
        return relay.process_status(
            event_body(query), signature_for(key, canonical_status_query(query), 2)
        )

    def test_published_resolved_response_is_exact_and_read_only(self):
        clock = [NOW]
        relay = self.make_relay(clock)
        self.assertEqual(self.submit_live(relay)[0], 202)
        clock[0] = NOW + 101
        self.assertTrue(
            relay.resolve_incident(self.live["device_id"], self.live["incident_id"])
        )
        changes = relay.db.total_changes
        status, payload = relay.process_status(
            STATUS_FIXTURE.read_bytes(), status_vector()["request_signature"]
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload, json.loads(status_vector()["response_json"]))
        self.assertEqual(relay.db.total_changes, changes)
        self.assertEqual(
            relay.db.execute(
                "SELECT COUNT(*) FROM events WHERE event_id = ?",
                (self.query["request_id"],),
            ).fetchone()[0],
            0,
        )

    def test_clock_rollback_is_retryable_before_freshness_classification(self):
        clock = [NOW]
        relay = self.make_relay(clock)
        relay.purge_expired()
        clock[0] = NOW - 301

        status, payload = self.query_status(relay)

        self.assertEqual((status, payload["code"]), (503, "clock_untrusted"))

    def test_state_precedence_is_resolution_expiry_acknowledgement_active(self):
        clock = [NOW]
        relay = self.make_relay(clock)
        self.assertEqual(self.submit_live(relay)[0], 202)
        clock[0] = self.query["created_at"]
        self.assertEqual(self.query_status(relay)[1]["state"], "active")

        relay.db.execute(
            """
            INSERT INTO acknowledgements
                (device_id, incident_id, recipient_id, source,
                 acknowledged_at, observed_at, provider_reference)
            VALUES (?, ?, 'legacy', 'test', ?, ?, 'ack-reference')
            """,
            (
                self.live["device_id"],
                self.live["incident_id"],
                clock[0],
                clock[0],
            ),
        )
        relay.db.commit()
        self.assertEqual(self.query_status(relay)[1]["state"], "acknowledged")

        clock[0] = self.live["expires_at"]
        expired_query = dict(self.query)
        expired_query["created_at"] = clock[0] - 1
        self.assertEqual(
            self.query_status(relay, expired_query)[1]["state"], "expired"
        )
        location = parse_json(LOCATION_FIXTURE.read_bytes())
        status, payload = relay.process_v2(
            LOCATION_FIXTURE.read_bytes(),
            signature_for(KEY, canonical_v2_event(location), 2),
        )
        self.assertEqual((status, payload["code"]), (422, "timestamp_out_of_window"))
        self.assertTrue(
            relay.resolve_incident(self.live["device_id"], self.live["incident_id"])
        )
        self.assertEqual(
            self.query_status(relay, expired_query)[1]["state"], "resolved"
        )

    def test_strict_validation_auth_freshness_expiry_and_replay(self):
        clock = [NOW]
        relay = self.make_relay(clock)
        self.assertEqual(self.submit_live(relay)[0], 202)
        for change in (
            lambda query: query.update(extra=True),
            lambda query: query.update(v=True),
            lambda query: query.update(request_id="not-canonical"),
            lambda query: query.update(created_at=1.0),
            lambda query: query.update(created_at=query["expires_at"]),
        ):
            with self.subTest(change=change):
                changed = dict(self.query)
                change(changed)
                self.assertEqual(validate_status_query(changed), "invalid_status_query")

        negative_zero = STATUS_FIXTURE.read_bytes().replace(
            b'"created_at":1788105700', b'"created_at":-0'
        )
        self.assertEqual(
            relay.process_status(negative_zero, status_vector()["request_signature"])[0],
            400,
        )
        self.assertEqual(
            relay.process_status(STATUS_FIXTURE.read_bytes(), "v2=" + "A" * 43)[0],
            401,
        )

        mismatch = dict(self.query)
        mismatch["expires_at"] += 1
        clock[0] = self.query["created_at"]
        self.assertEqual(self.query_status(relay, mismatch)[0], 409)
        changes = relay.db.total_changes
        first = self.query_status(relay)
        second = self.query_status(relay)
        self.assertEqual(first, second)
        self.assertEqual(first[0], 200)
        self.assertEqual(relay.db.total_changes, changes)

        for now, expected in (
            (self.query["created_at"] - 300, 200),
            (self.query["created_at"] - 301, 422),
            (self.query["created_at"] + 300, 200),
            (self.query["created_at"] + 301, 422),
        ):
            with self.subTest(now=now):
                clock[0] = now
                self.assertEqual(self.query_status(relay)[0], expected)

    def test_same_incident_id_is_scoped_to_authenticated_device(self):
        second_device = base64.urlsafe_b64encode(b"\x22" * 16).rstrip(b"=").decode()
        second_key = b"\x33" * 32
        configured = devices()
        configured[second_device] = {
            "key": second_key,
            "live_key": second_key,
            "enabled": True,
        }
        clock = [NOW]
        relay = self.make_relay(clock, configured)
        self.assertEqual(self.submit_live(relay)[0], 202)
        other_event = json.loads(json.dumps(self.live))
        other_event["device_id"] = second_device
        self.assertEqual(self.submit_live(relay, other_event, second_key)[0], 202)
        clock[0] = self.query["created_at"]
        self.assertTrue(
            relay.resolve_incident(self.live["device_id"], self.live["incident_id"])
        )
        self.assertEqual(self.query_status(relay)[1]["state"], "resolved")

        other_query = dict(self.query)
        other_query["device_id"] = second_device
        status, payload = self.query_status(relay, other_query, second_key)
        self.assertEqual((status, payload["state"]), (200, "active"))
        self.assertEqual(self.query_status(relay, other_query, KEY)[0], 401)


class RoutingAndEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.test = parse_json(FIXTURE.read_bytes())
        self.live = parse_json(LIVE_FIXTURE.read_bytes())
        self.location = parse_json(LOCATION_FIXTURE.read_bytes())

    def test_group_routes_are_snapshotted_and_deliver_independently(self):
        routes = route_config(two_recipients=True)
        primary = FakeProvider()
        backup = FakeProvider()
        relay = Relay(
            devices(),
            None,
            clock=lambda: NOW,
            routes=routes,
            transports={
                ("primary", "pushover"): primary,
                ("backup", "ntfy"): backup,
            },
        )
        self.addCleanup(relay.close)
        self.assertEqual(
            relay.process_v2(
                LIVE_FIXTURE.read_bytes(),
                signature_for(KEY, canonical_v2_event(self.live), 2),
            )[0],
            202,
        )

        self.assertEqual(
            [
                tuple(row)
                for row in relay.db.execute(
                    "SELECT recipient_id, transport FROM incident_routes ORDER BY 1, 2"
                )
            ],
            [("backup", "ntfy"), ("primary", "pushover")],
        )

        routes["groups"]["household"] = ["primary"]
        self.assertEqual(
            relay.process_v2(
                LOCATION_FIXTURE.read_bytes(),
                signature_for(KEY, canonical_v2_event(self.location), 2),
            )[0],
            202,
        )
        deliveries = relay.db.execute(
            "SELECT event_id, recipient_id, transport, emergency FROM deliveries ORDER BY delivery_id"
        ).fetchall()
        self.assertEqual(
            [tuple(row[1:]) for row in deliveries],
            [
                ("primary", "pushover", 1),
                ("backup", "ntfy", 1),
                ("backup", "ntfy", 0),
                ("primary", "pushover", 0),
            ],
        )
        for _ in range(4):
            self.assertTrue(relay.run_worker_once())
        self.assertFalse(relay.run_worker_once())
        ntfy_live = relay.db.execute(
            """
            SELECT cancellation_state FROM deliveries
            WHERE recipient_id = 'backup' AND emergency = 1
            """
        ).fetchone()[0]
        self.assertEqual(ntfy_live, "unsupported")

    def test_resolution_preserves_ambiguous_emergency_outcome(self):
        provider = FakeEmergencyProvider()
        provider.failure = ProviderFailure(
            "result_unknown",
            "provider_result_unknown",
            retryable=True,
            ambiguous=True,
        )
        relay = Relay(
            devices(),
            None,
            clock=lambda: NOW,
            routes=route_config(),
            transports={("primary", "pushover"): provider},
        )
        self.addCleanup(relay.close)
        self.assertEqual(
            relay.process(
                FIXTURE.read_bytes(),
                signature_for(KEY, canonical_event(self.test)),
            )[0],
            202,
        )
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(
            relay.resolve_incident(self.test["device_id"], self.test["incident_id"])
        )
        self.assertEqual(
            tuple(
                relay.db.execute(
                    "SELECT state, may_have_accepted, cancellation_state FROM deliveries"
                ).fetchone()
            ),
            ("result_unknown", 1, "result_unknown"),
        )

    def test_emergency_test_ack_does_not_resolve_or_stop_other_retries(self):
        clock = [NOW]
        routes = route_config(two_recipients=True)
        primary = FakeEmergencyProvider(
            evidence=ReceiptEvidence(True, NOW + 10, False, NOW + 5)
        )
        backup = FakeProvider(
            ProviderFailure("retryable_failure", "temporary", retryable=True)
        )
        relay = Relay(
            devices(),
            None,
            clock=lambda: clock[0],
            routes=routes,
            transports={
                ("primary", "pushover"): primary,
                ("backup", "ntfy"): backup,
            },
            receipt_interval=30,
        )
        self.addCleanup(relay.close)
        signature = signature_for(KEY, canonical_event(self.test))
        self.assertEqual(relay.process(FIXTURE.read_bytes(), signature)[0], 202)
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(primary.calls[0][1]["emergency"])
        self.assertIsNone(primary.calls[0][1]["opaque_event"])

        clock[0] += 30
        self.assertTrue(relay.run_evidence_once())
        acknowledgement = relay.db.execute(
            "SELECT recipient_id, acknowledged_at, source FROM acknowledgements"
        ).fetchone()
        self.assertEqual(
            tuple(acknowledgement),
            ("primary", NOW + 10, "pushover_receipt"),
        )
        self.assertEqual(
            relay.db.execute("SELECT lifecycle FROM incidents").fetchone()[0],
            "active",
        )
        self.assertEqual(
            relay.db.execute(
                "SELECT state FROM deliveries WHERE recipient_id = 'backup'"
            ).fetchone()[0],
            "retry_wait",
        )

        self.assertTrue(
            relay.resolve_incident(self.test["device_id"], self.test["incident_id"])
        )
        self.assertEqual(
            tuple(
                relay.db.execute(
                    "SELECT lifecycle, resolved_at FROM incidents"
                ).fetchone()
            ),
            ("resolved", NOW + 30),
        )
        self.assertFalse(relay.run_evidence_once())
        self.assertEqual(primary.cancel_calls, [])
        states = relay.db.execute(
            "SELECT recipient_id, state FROM deliveries ORDER BY recipient_id"
        ).fetchall()
        self.assertEqual(
            [tuple(row) for row in states],
            [("backup", "resolved"), ("primary", "provider_accepted")],
        )

    def test_resolution_cancellation_retries_and_records_outcome(self):
        clock = [NOW]
        provider = FakeEmergencyProvider(
            cancel_failure=ProviderFailure(
                "result_unknown",
                "provider_result_unknown",
                retryable=True,
                ambiguous=True,
            )
        )
        routes = route_config()
        relay = Relay(
            devices(),
            None,
            clock=lambda: clock[0],
            routes=routes,
            transports={("primary", "pushover"): provider},
            receipt_interval=5,
        )
        self.addCleanup(relay.close)
        self.assertEqual(
            relay.process(
                FIXTURE.read_bytes(),
                signature_for(KEY, canonical_event(self.test)),
            )[0],
            202,
        )
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(
            relay.resolve_incident(self.test["device_id"], self.test["incident_id"])
        )
        self.assertTrue(relay.run_evidence_once())
        state = relay.db.execute(
            "SELECT cancellation_state, provider_cancelled_at FROM deliveries"
        ).fetchone()
        self.assertEqual(tuple(state), ("result_unknown", None))

        provider.cancel_failure = None
        clock[0] += 5
        self.assertTrue(relay.run_evidence_once())
        state = relay.db.execute(
            "SELECT cancellation_state, provider_cancelled_at FROM deliveries"
        ).fetchone()
        self.assertEqual(tuple(state), ("cancelled", NOW + 5))
        self.assertEqual(provider.cancel_calls, ["r" * 30] * 2)

    def test_wrong_provider_cannot_poll_or_cancel_receipt_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "relay.sqlite3")
            clock = [NOW]
            routes = route_config()
            correct = FakeEmergencyProvider(
                fingerprint=hashlib.sha256(b"correct-emergency").digest()
            )
            wrong = FakeEmergencyProvider(
                fingerprint=hashlib.sha256(b"wrong-emergency").digest()
            )
            wrong_relay = Relay(
                devices(),
                None,
                database,
                clock=lambda: clock[0],
                routes=routes,
                transports={("primary", "pushover"): wrong},
            )
            correct_relay = Relay(
                devices(),
                None,
                database,
                clock=lambda: clock[0],
                routes=routes,
                transports={("primary", "pushover"): correct},
            )
            self.addCleanup(wrong_relay.close)
            self.addCleanup(correct_relay.close)
            self.assertEqual(
                correct_relay.process(
                    FIXTURE.read_bytes(),
                    signature_for(KEY, canonical_event(self.test)),
                )[0],
                202,
            )
            self.assertTrue(correct_relay.run_worker_once())

            clock[0] += 30
            self.assertFalse(wrong_relay.run_evidence_once())
            self.assertTrue(correct_relay.run_evidence_once())
            self.assertEqual(wrong.receipt_calls, [])
            self.assertEqual(correct.receipt_calls, ["r" * 30])

            self.assertTrue(
                correct_relay.resolve_incident(
                    self.test["device_id"], self.test["incident_id"]
                )
            )
            self.assertFalse(wrong_relay.run_evidence_once())
            self.assertTrue(correct_relay.run_evidence_once())
            self.assertEqual(wrong.cancel_calls, [])
            self.assertEqual(correct.cancel_calls, ["r" * 30])

    def test_cancel_claim_is_recovered_and_stale_completion_is_fenced(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "relay.sqlite3")
            clock = [NOW]
            routes = route_config()
            provider = FakeEmergencyProvider()
            first = Relay(
                devices(),
                None,
                database,
                clock=lambda: clock[0],
                lease_seconds=5,
                routes=routes,
                transports={("primary", "pushover"): provider},
                receipt_interval=5,
            )
            self.assertEqual(
                first.process(
                    FIXTURE.read_bytes(),
                    signature_for(KEY, canonical_event(self.test)),
                )[0],
                202,
            )
            self.assertTrue(first.run_worker_once())
            self.assertTrue(
                first.resolve_incident(
                    self.test["device_id"], self.test["incident_id"]
                )
            )
            stale = first._claim_evidence()
            first.close()

            clock[0] += 6
            second = Relay(
                devices(),
                None,
                database,
                clock=lambda: clock[0],
                lease_seconds=5,
                routes=routes,
                transports={("primary", "pushover"): provider},
                receipt_interval=5,
            )
            self.addCleanup(second.close)
            current = second._claim_evidence()
            self.assertNotEqual(stale["claim_token"], current["claim_token"])
            self.assertFalse(second._finish_evidence_cancel(stale["claim_token"]))
            self.assertTrue(second._finish_evidence_cancel(current["claim_token"]))
            self.assertEqual(
                second.db.execute(
                    "SELECT cancellation_state FROM deliveries"
                ).fetchone()[0],
                "cancelled",
            )

    def test_resolution_is_scoped_by_device_even_when_incident_ids_match(self):
        second_device = base64.urlsafe_b64encode(b"\x22" * 16).rstrip(b"=").decode()
        second_key = b"\x33" * 32
        configured = devices()
        configured[second_device] = {
            "key": second_key,
            "live_key": second_key,
            "enabled": True,
        }
        relay = Relay(configured, FakeProvider(), clock=lambda: NOW)
        self.addCleanup(relay.close)
        self.assertEqual(
            relay.process_v2(
                LIVE_FIXTURE.read_bytes(),
                signature_for(KEY, canonical_v2_event(self.live), 2),
            )[0],
            202,
        )
        other = json.loads(json.dumps(self.live))
        other["device_id"] = second_device
        self.assertEqual(
            relay.process_v2(
                event_body(other),
                signature_for(second_key, canonical_v2_event(other), 2),
            )[0],
            202,
        )
        self.assertTrue(
            relay.resolve_incident(self.live["device_id"], self.live["incident_id"])
        )
        incidents = relay.db.execute(
            "SELECT device_id, lifecycle FROM incidents ORDER BY device_id"
        ).fetchall()
        self.assertEqual(
            {row["device_id"]: row["lifecycle"] for row in incidents},
            {self.live["device_id"]: "resolved", second_device: "active"},
        )
        deliveries = relay.db.execute(
            "SELECT device_id, state FROM deliveries ORDER BY device_id"
        ).fetchall()
        self.assertEqual(
            {row["device_id"]: row["state"] for row in deliveries},
            {self.live["device_id"]: "resolved", second_device: "pending"},
        )

    def test_resolution_drops_location_ciphertext_and_expiry_purges_incident(self):
        clock = [NOW]
        relay = Relay(devices(), FakeProvider(), clock=lambda: clock[0])
        self.addCleanup(relay.close)
        self.assertEqual(
            relay.process_v2(
                LIVE_FIXTURE.read_bytes(),
                signature_for(KEY, canonical_v2_event(self.live), 2),
            )[0],
            202,
        )
        self.assertEqual(
            relay.process_v2(
                LOCATION_FIXTURE.read_bytes(),
                signature_for(KEY, canonical_v2_event(self.location), 2),
            )[0],
            202,
        )
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(relay.run_worker_once())
        self.assertTrue(
            relay.resolve_incident(self.live["device_id"], self.live["incident_id"])
        )
        opaque = relay.db.execute(
            "SELECT kind, opaque_json FROM events ORDER BY sequence"
        ).fetchall()
        self.assertIsNotNone(opaque[0]["opaque_json"])
        self.assertIsNone(opaque[1]["opaque_json"])
        ciphertext = self.location["payload"]["ciphertext"]
        for table in ("deliveries", "delivery_attempts"):
            for row in relay.db.execute(f"SELECT * FROM {table}"):
                self.assertNotIn(ciphertext, " ".join(str(value) for value in row))

        clock[0] = NOW + 86_400
        relay.purge_expired()
        retained = relay.db.execute(
            "SELECT kind, opaque_json FROM events ORDER BY sequence"
        ).fetchall()
        self.assertEqual(len(retained), 2)
        self.assertTrue(all(row["opaque_json"] is None for row in retained))

        clock[0] = self.live["expires_at"] + 86_400
        relay.purge_expired()
        self.assertEqual(relay.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 0)
        self.assertEqual(
            relay.db.execute("SELECT COUNT(*) FROM incidents").fetchone()[0], 0
        )
        replay_status, replay = relay.process_v2(
            LOCATION_FIXTURE.read_bytes(),
            signature_for(KEY, canonical_v2_event(self.location), 2),
        )
        self.assertEqual(
            (replay_status, replay["code"]),
            (422, "timestamp_out_of_window"),
        )

    def test_location_ciphertext_is_cleared_at_event_expiry_before_delivery(self):
        clock = [NOW]
        provider = FakeProvider()
        relay = Relay(devices(), provider, clock=lambda: clock[0])
        self.addCleanup(relay.close)
        for fixture, event in (
            (LIVE_FIXTURE, self.live),
            (LOCATION_FIXTURE, self.location),
        ):
            self.assertEqual(
                relay.process_v2(
                    fixture.read_bytes(),
                    signature_for(KEY, canonical_v2_event(event), 2),
                )[0],
                202,
            )
        self.assertTrue(relay.run_worker_once())
        clock[0] = self.location["expires_at"]
        relay.purge_expired()

        location = relay.db.execute(
            "SELECT canonical_sha256, opaque_json FROM events WHERE kind = 'location.updated'"
        ).fetchone()
        self.assertEqual(len(location["canonical_sha256"]), 32)
        self.assertIsNone(location["opaque_json"])
        self.assertFalse(relay.run_worker_once())
        self.assertEqual(provider.event_ids, [self.live["event_id"]])

    def test_location_ciphertext_has_an_absolute_24_hour_ceiling(self):
        clock = [NOW]
        provider = FakeProvider()
        relay = Relay(devices(), provider, clock=lambda: clock[0])
        self.addCleanup(relay.close)
        for fixture, event in (
            (LIVE_FIXTURE, self.live),
            (LOCATION_FIXTURE, self.location),
        ):
            self.assertEqual(
                relay.process_v2(
                    fixture.read_bytes(),
                    signature_for(KEY, canonical_v2_event(event), 2),
                )[0],
                202,
            )
        self.assertTrue(relay.run_worker_once())
        far_expiry = self.location["created_at"] + 200_000
        relay.db.execute(
            "UPDATE events SET expires_at = ? WHERE kind = 'location.updated'",
            (far_expiry,),
        )
        relay.db.execute(
            "UPDATE incidents SET expires_at = ?", (far_expiry,)
        )
        relay.db.commit()
        clock[0] = self.location["created_at"] + 86_400
        relay.purge_expired()

        self.assertIsNone(
            relay.db.execute(
                "SELECT opaque_json FROM events WHERE kind = 'location.updated'"
            ).fetchone()[0]
        )
        self.assertEqual(
            relay.db.execute(
                """
                SELECT state FROM deliveries d JOIN events e USING (device_id, event_id)
                WHERE e.kind = 'location.updated'
                """
            ).fetchone()[0],
            "expired",
        )
        self.assertFalse(relay.run_worker_once())
        self.assertEqual(provider.event_ids, [self.live["event_id"]])
        self.assertEqual(
            relay.process_v2(
                LOCATION_FIXTURE.read_bytes(),
                signature_for(KEY, canonical_v2_event(self.location), 2),
            )[0],
            202,
        )


def provider_response(raw=b'{"status":1,"request":"provider-request-id"}', status=200):
    result = io.BytesIO(raw)
    result.status = status
    return result


class PushoverTests(unittest.TestCase):
    def client(self, outcome):
        opener = Mock()
        if isinstance(outcome, Exception):
            opener.open.side_effect = outcome
        else:
            opener.open.return_value = outcome
        return PushoverClient("a" * 30, "u" * 30, opener=opener), opener

    def test_sends_only_the_fixed_test_message(self):
        client, opener = self.client(provider_response())
        event_id = "AAECAwQFBgcICQoLDA0ODw"
        self.assertEqual(client.submit(event_id), Submission("provider-request-id"))
        request = opener.open.call_args.args[0]
        form = parse_qs(request.data.decode())
        self.assertEqual(request.full_url, PushoverClient.URL)
        self.assertEqual(form["message"], [TEST_MESSAGE])
        self.assertEqual(form["title"], [f"Garmin TEST {event_id}"])

    def test_configuration_fingerprint_covers_both_credentials(self):
        base = PushoverClient("a" * 30, "u" * 30, opener=Mock())
        same = PushoverClient("a" * 30, "u" * 30, opener=Mock())
        changed_app = PushoverClient("b" * 30, "u" * 30, opener=Mock())
        changed_user = PushoverClient("a" * 30, "v" * 30, opener=Mock())
        self.assertEqual(base.configuration_fingerprint, same.configuration_fingerprint)
        self.assertEqual(len(base.configuration_fingerprint), 32)
        self.assertNotEqual(
            base.configuration_fingerprint, changed_app.configuration_fingerprint
        )
        self.assertNotEqual(
            base.configuration_fingerprint, changed_user.configuration_fingerprint
        )

    def test_live_submission_forwards_only_the_opaque_envelope(self):
        client, opener = self.client(provider_response())
        event = parse_json(LIVE_FIXTURE.read_bytes())
        compact = event_body(event)
        self.assertEqual(
            client.submit(
                event["event_id"], kind=event["kind"], opaque_event=compact
            ),
            Submission("provider-request-id"),
        )
        form = parse_qs(opener.open.call_args.args[0].data.decode())
        self.assertEqual(form["title"], [f"Garmin LIVE {event['event_id']}"])
        self.assertEqual(form["message"], [compact.decode("ascii")])
        self.assertNotEqual(form["message"], [TEST_MESSAGE])

    def test_emergency_submission_is_bounded_and_requires_a_receipt(self):
        raw = (
            b'{"status":1,"request":"provider-request-id",'
            b'"receipt":"rrrrrrrrrrrrrrrrrrrrrrrrrrrrrr"}'
        )
        client, opener = self.client(provider_response(raw))
        event_id = "AAECAwQFBgcICQoLDA0ODw"
        self.assertEqual(
            client.submit(
                event_id,
                emergency=True,
                expires_at=NOW + 20_000,
                now=NOW,
            ),
            Submission("provider-request-id", "r" * 30),
        )
        form = parse_qs(opener.open.call_args.args[0].data.decode())
        self.assertEqual(form["priority"], ["2"])
        self.assertEqual(form["retry"], ["60"])
        self.assertEqual(form["expire"], ["10800"])
        self.assertEqual(form["message"], [TEST_MESSAGE])

        missing, _ = self.client(provider_response())
        with self.assertRaises(ProviderFailure) as raised:
            missing.submit(
                event_id,
                emergency=True,
                expires_at=NOW + 900,
                now=NOW,
            )
        self.assertEqual(raised.exception.result, "result_unknown")

    def test_receipt_poll_and_cancellation_are_strict(self):
        raw = (
            b'{"status":1,"acknowledged":1,"acknowledged_at":1788105610,'
            b'"expired":0,"last_delivered_at":1788105605}'
        )
        client, opener = self.client(provider_response(raw))
        self.assertEqual(
            client.poll_receipt("r" * 30),
            ReceiptEvidence(True, NOW + 10, False, NOW + 5),
        )
        request = opener.open.call_args.args[0]
        self.assertIn(f"/receipts/{'r' * 30}.json?", request.full_url)
        self.assertIn("token=", request.full_url)

        client, opener = self.client(provider_response(b'{"status":1}'))
        self.assertIsNone(client.cancel_receipt("r" * 30))
        request = opener.open.call_args.args[0]
        self.assertEqual(request.method, "POST")
        self.assertIn(f"/receipts/{'r' * 30}/cancel.json", request.full_url)
        self.assertEqual(parse_qs(request.data.decode())["token"], ["a" * 30])

    def test_classifies_provider_outcomes_without_exposing_response(self):
        cases = [
            (provider_response(b'{"status":0,"errors":["secret"]}'), "configuration_failure"),
            (HTTPError(PushoverClient.URL, 400, "secret", {}, None), "configuration_failure"),
            (HTTPError(PushoverClient.URL, 429, "secret", {}, None), "configuration_failure"),
            (HTTPError(PushoverClient.URL, 500, "secret", {}, None), "result_unknown"),
            (HTTPError(PushoverClient.URL, 302, "secret", {}, None), "result_unknown"),
            (URLError("secret network detail"), "result_unknown"),
            (provider_response(b"not-json"), "result_unknown"),
            (provider_response(b'{"status":1}'), "result_unknown"),
            (provider_response(b"x" * (MAX_PROVIDER_RESPONSE_BYTES + 2)), "result_unknown"),
        ]
        for outcome, expected in cases:
            with self.subTest(expected=expected):
                client, _ = self.client(outcome)
                with self.assertRaises(ProviderFailure) as raised:
                    client.submit("AAECAwQFBgcICQoLDA0ODw")
                self.assertEqual(raised.exception.result, expected)
                self.assertNotIn("secret", str(raised.exception))


class NtfyTests(unittest.TestCase):
    def client(self, outcome, url="https://ntfy.example/"):
        opener = Mock()
        if isinstance(outcome, Exception):
            opener.open.side_effect = outcome
        else:
            opener.open.return_value = outcome
        return (
            NtfyClient(
                url,
                "private-topic",
                "tk_" + "n" * 29,
                opener=opener,
            ),
            opener,
        )

    def test_posts_json_to_instance_root_with_private_authorization(self):
        client, opener = self.client(provider_response(b'{"id":"notification-id"}'))
        event_id = "AAECAwQFBgcICQoLDA0ODw"
        self.assertEqual(client.submit(event_id), Submission("notification-id"))
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://ntfy.example/")
        self.assertEqual(
            request.get_header("Authorization"), "Bearer " + "tk_" + "n" * 29
        )
        self.assertEqual(request.get_header("X-sequence-id"), event_id)
        body = json.loads(request.data)
        self.assertEqual(body["topic"], "private-topic")
        self.assertEqual(body["message"], TEST_MESSAGE)
        self.assertEqual(body["priority"], 4)

    def test_configuration_fingerprint_covers_url_topic_and_token(self):
        token = "tk_" + "n" * 29
        base = NtfyClient("https://ntfy.example/", "topic", token, opener=Mock())
        same = NtfyClient("https://ntfy.example/", "topic", token, opener=Mock())
        changes = (
            NtfyClient("https://other.example/", "topic", token, opener=Mock()),
            NtfyClient("https://ntfy.example/", "other", token, opener=Mock()),
            NtfyClient(
                "https://ntfy.example/", "topic", "tk_" + "o" * 29, opener=Mock()
            ),
        )
        self.assertEqual(base.configuration_fingerprint, same.configuration_fingerprint)
        self.assertEqual(len(base.configuration_fingerprint), 32)
        for changed in changes:
            self.assertNotEqual(
                base.configuration_fingerprint, changed.configuration_fingerprint
            )

        with self.assertRaisesRegex(ValueError, "32-character tk_"):
            NtfyClient("https://ntfy.example/", "topic", "weak", opener=Mock())

    def test_requires_instance_root_and_classifies_outcomes(self):
        with self.assertRaisesRegex(ValueError, "instance root"):
            self.client(provider_response(b'{}'), "https://ntfy.example/topic")
        cases = [
            (
                HTTPError("https://ntfy.example/", 429, "secret", {}, None),
                "retryable_failure",
            ),
            (
                HTTPError("https://ntfy.example/", 400, "secret", {}, None),
                "configuration_failure",
            ),
            (
                HTTPError("https://ntfy.example/", 500, "secret", {}, None),
                "result_unknown",
            ),
            (provider_response(b"not-json"), "result_unknown"),
        ]
        for outcome, expected in cases:
            with self.subTest(expected=expected):
                client, _ = self.client(outcome)
                with self.assertRaises(ProviderFailure) as raised:
                    client.submit("AAECAwQFBgcICQoLDA0ODw")
                self.assertEqual(raised.exception.result, expected)
                self.assertNotIn("secret", str(raised.exception))


class DeviceConfigTests(unittest.TestCase):
    def test_enabled_device_file_must_be_private(self):
        event = parse_json(FIXTURE.read_bytes())
        private_key = "10" * 32
        config = {
            event["device_id"]: {
                "key": private_key,
                "live_key": "20" * 32,
                "enabled": True,
            }
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "devices.json"
            path.write_text(json.dumps(config))
            os.chmod(path, 0o600)
            loaded = load_devices(path)[event["device_id"]]
            self.assertEqual(loaded["key"], bytes.fromhex(private_key))
            self.assertEqual(loaded["live_key"], bytes.fromhex("20" * 32))
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(ValueError, "mode 0600"):
                load_devices(path)

    def test_enabled_device_rejects_public_fixture_keys(self):
        event = parse_json(FIXTURE.read_bytes())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "devices.json"
            for field in ("key", "live_key"):
                config = {
                    event["device_id"]: {
                        "key": "10" * 32,
                        "live_key": "20" * 32,
                        "enabled": True,
                    }
                }
                config[event["device_id"]][field] = KEY_HEX
                path.write_text(json.dumps(config))
                os.chmod(path, 0o600)
                with self.subTest(field=field), self.assertRaisesRegex(
                    ValueError, "public fixture key"
                ):
                    load_devices(path)


class RouteConfigTests(unittest.TestCase):
    def test_route_file_is_private_and_strict(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "routes.json"
            config = route_config(two_recipients=True)
            path.write_text(json.dumps(config))
            os.chmod(path, 0o600)
            self.assertEqual(load_routes(path), config)

            os.chmod(path, 0o644)
            with self.assertRaisesRegex(ValueError, "mode 0600"):
                load_routes(path)

    def test_ntfy_route_requires_typed_secrets_and_an_instance_root(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "routes.json"
            route = route_config(two_recipients=True)["recipients"]["backup"][
                "routes"
            ][0]
            for field, invalid in (
                ("url", "https://ntfy.example/private-topic"),
                ("topic", ["private-topic"]),
                ("token", ["private-bearer-token"]),
            ):
                with self.subTest(field=field):
                    config = route_config(two_recipients=True)
                    config["recipients"]["backup"]["routes"][0][field] = invalid
                    path.write_text(json.dumps(config))
                    os.chmod(path, 0o600)
                    with self.assertRaisesRegex(ValueError, "invalid ntfy route"):
                        load_routes(path)
            self.assertEqual(route["url"], "https://ntfy.example/")

    def test_malformed_route_types_raise_validation_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "routes.json"
            malformed = []
            config = route_config()
            config["device_groups"][next(iter(config["device_groups"]))] = []
            malformed.append(config)
            config = route_config()
            config["groups"]["household"] = [["primary"]]
            malformed.append(config)
            config = route_config()
            config["recipients"]["primary"]["routes"][0]["user_key"] = []
            malformed.append(config)
            for config in malformed:
                path.write_text(json.dumps(config))
                os.chmod(path, 0o600)
                with self.subTest(config=config), self.assertRaises(ValueError):
                    load_routes(path)


class WorkerLoopTests(unittest.TestCase):
    def test_unexpected_failure_does_not_silently_kill_worker(self):
        class FlakyRelay:
            def __init__(self):
                self.calls = 0
                self.recovered = threading.Event()

            def run_evidence_once(self):
                self.calls += 1
                if self.calls == 1:
                    raise RuntimeError("synthetic worker failure")
                self.recovered.set()
                return False

            def run_worker_once(self):
                return False

            def purge_expired(self):
                return None

        relay = FlakyRelay()
        with self.assertLogs("opendistress-relay", level="ERROR") as logs:
            server = make_server("127.0.0.1", 0, relay)
            try:
                self.assertTrue(relay.recovered.wait(2))
                self.assertTrue(server.worker_thread.is_alive())
            finally:
                server.server_close()
        self.assertIn("outbox worker internal failure", "\n".join(logs.output))


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.event = parse_json(FIXTURE.read_bytes())
        self.body = FIXTURE.read_bytes()
        self.provider = FakeProvider()
        self.relay = Relay(devices(), self.provider, clock=lambda: NOW)
        self.server = make_server("127.0.0.1", 0, self.relay)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.relay.close()

    def request(self, body, headers, path="/v1/events"):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        connection.request("POST", path, body=body, headers=headers)
        reply = connection.getresponse()
        payload = json.loads(reply.read())
        connection.close()
        return reply.status, payload

    def test_http_endpoint_accepts_signed_fixture(self):
        status, payload = self.request(
            self.body,
            {
                "Content-Type": "application/json",
                "X-OpenDistress-Signature": signature_for(KEY, canonical_event(self.event)),
            },
        )
        self.assertEqual(status, 202)
        self.assertEqual(payload, accepted_response(KEY, self.event["event_id"]))

    def test_http_endpoint_accepts_signed_v2_fixture(self):
        event = parse_json(LIVE_FIXTURE.read_bytes())
        status, payload = self.request(
            LIVE_FIXTURE.read_bytes(),
            {
                "Content-Type": "application/json",
                "X-OpenDistress-Signature": signature_for(KEY, canonical_v2_event(event), 2),
            },
            "/v2/events",
        )
        self.assertEqual(status, 202)
        self.assertEqual(payload, accepted_response(KEY, event["event_id"], 2))

    def test_http_endpoint_returns_signed_v2_status(self):
        event = parse_json(LIVE_FIXTURE.read_bytes())
        self.assertEqual(
            self.request(
                LIVE_FIXTURE.read_bytes(),
                {
                    "Content-Type": "application/json",
                    "X-OpenDistress-Signature": signature_for(
                        KEY, canonical_v2_event(event), 2
                    ),
                },
                "/v2/events",
            )[0],
            202,
        )
        query = parse_json(STATUS_FIXTURE.read_bytes())
        status, payload = self.request(
            STATUS_FIXTURE.read_bytes(),
            {
                "Content-Type": "application/json",
                "X-OpenDistress-Signature": signature_for(
                    KEY, canonical_status_query(query), 2
                ),
            },
            "/v2/status",
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload, signed_status_response(KEY, query, "active", NOW))

    def test_http_boundaries_fail_before_provider_use(self):
        cases = [
            (b"x" * 1025, {"Content-Type": "application/json"}, 413, "body_too_large"),
            (self.body, {"Content-Type": "text/plain"}, 415, "invalid_content_type"),
            (
                self.body,
                {"Content-Type": "application/json", "Content-Encoding": "gzip"},
                415,
                "unsupported_content_encoding",
            ),
            (self.body, {"Content-Type": "application/json"}, 401, "authentication_failed"),
        ]
        for body, headers, expected_status, expected_code in cases:
            with self.subTest(code=expected_code):
                status, payload = self.request(body, headers)
                self.assertEqual(status, expected_status)
                self.assertEqual(payload["code"], expected_code)
        self.assertEqual(self.provider.event_ids, [])

    def test_unsupported_methods_use_bounded_json_and_head_has_no_body(self):
        for method in ("GET", "PUT", "PATCH", "DELETE", "OPTIONS"):
            with self.subTest(method=method):
                connection = http.client.HTTPConnection(
                    "127.0.0.1", self.server.server_port, timeout=2
                )
                connection.request(method, "/v2/status")
                reply = connection.getresponse()
                raw = reply.read()
                connection.close()
                self.assertEqual(reply.status, 405)
                self.assertEqual(reply.getheader("Content-Type"), "application/json")
                self.assertEqual(reply.getheader("Cache-Control"), "no-store")
                self.assertEqual(
                    json.loads(raw)["code"], "method_not_allowed"
                )

        connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=2
        )
        connection.request("HEAD", "/v2/status")
        reply = connection.getresponse()
        raw = reply.read()
        connection.close()
        self.assertEqual(reply.status, 405)
        self.assertEqual(reply.getheader("Content-Type"), "application/json")
        self.assertEqual(raw, b"")

    def test_partial_headers_time_out(self):
        self.server.RequestHandlerClass.timeout = 0.05
        with socket.create_connection(
            ("127.0.0.1", self.server.server_port), timeout=1
        ) as client:
            client.sendall(b"POST /v1/events HTTP/1.1\r\nHost: relay\r\nContent-Type:")
            self.assertEqual(client.recv(1), b"")


class CliTests(unittest.TestCase):
    def test_cli_rejects_in_memory_ledger(self):
        errors = io.StringIO()
        with redirect_stderr(errors), self.assertRaises(SystemExit):
            main(
                [
                    "--devices",
                    "unused",
                    "--routes",
                    "unused",
                    "--database",
                    ":memory:",
                ]
            )
        self.assertIn("persistent SQLite file", errors.getvalue())

    def test_cli_resolves_offline_and_defers_provider_cancellation(self):
        event = parse_json(FIXTURE.read_bytes())
        routes = route_config()
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "relay.sqlite3")
            provider = FakeEmergencyProvider()
            relay = Relay(
                devices(),
                None,
                database,
                clock=lambda: NOW,
                routes=routes,
                transports={("primary", "pushover"): provider},
            )
            self.assertEqual(
                relay.process(
                    FIXTURE.read_bytes(),
                    signature_for(KEY, canonical_event(event)),
                )[0],
                202,
            )
            self.assertTrue(relay.run_worker_once())
            future = int(time.time()) + 3600
            relay.db.execute("UPDATE events SET expires_at = ?", (future,))
            relay.db.execute("UPDATE incidents SET expires_at = ?", (future,))
            relay.db.commit()
            relay.close()

            self.assertEqual(
                main(
                    [
                        "--database",
                        database,
                        "--resolve-incident",
                        f"{event['device_id']}:{event['incident_id']}",
                    ]
                ),
                0,
            )
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(
                    connection.execute(
                        "SELECT lifecycle FROM incidents"
                    ).fetchone()[0],
                    "resolved",
                )
                self.assertEqual(
                    connection.execute(
                        "SELECT cancellation_state FROM deliveries"
                    ).fetchone()[0],
                    "pending",
                )
            finally:
                connection.close()

            worker = Relay(
                devices(),
                None,
                database,
                clock=lambda: int(time.time()),
                routes=routes,
                transports={("primary", "pushover"): provider},
            )
            self.addCleanup(worker.close)
            self.assertTrue(worker.run_evidence_once())
            self.assertEqual(provider.cancel_calls, ["r" * 30])

    def test_cli_resolves_schema4_without_migrating_or_provider_config(self):
        event = parse_json(FIXTURE.read_bytes())
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "relay.sqlite3")
            relay = Relay(devices(), FakeProvider(), database, clock=lambda: NOW)
            self.assertEqual(
                relay.process(
                    FIXTURE.read_bytes(),
                    signature_for(KEY, canonical_event(event)),
                )[0],
                202,
            )
            relay.close()
            connection = sqlite3.connect(database)
            connection.execute(
                "ALTER TABLE incident_routes DROP COLUMN provider_fingerprint"
            )
            connection.execute(
                "ALTER TABLE deliveries DROP COLUMN provider_fingerprint"
            )
            connection.execute("PRAGMA user_version=4")
            connection.commit()
            connection.close()

            self.assertEqual(
                main(
                    [
                        "--database",
                        database,
                        "--resolve-incident",
                        f"{event['device_id']}:{event['incident_id']}",
                    ]
                ),
                0,
            )
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 4)
                self.assertEqual(
                    connection.execute(
                        "SELECT lifecycle FROM incidents"
                    ).fetchone()[0],
                    "resolved",
                )
                self.assertEqual(
                    connection.execute("SELECT state FROM deliveries").fetchone()[0],
                    "resolved",
                )
            finally:
                connection.close()


if __name__ == "__main__":
    unittest.main()

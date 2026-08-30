# SPDX-License-Identifier: MIT

import base64
import hashlib
import http.client
import io
import json
import os
import socket
import tempfile
import threading
import unittest
from unittest.mock import Mock
from contextlib import redirect_stderr
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs

from relay.server import (
    MAX_PROVIDER_RESPONSE_BYTES,
    MAX_CREATED_AT,
    PushoverClient,
    ProviderFailure,
    Relay,
    TEST_MESSAGE,
    accepted_response,
    canonical_event,
    canonical_result,
    load_devices,
    main,
    make_server,
    parse_json,
    signature_for,
    validate_event,
)

ROOT = Path(__file__).parents[1]
FIXTURE = ROOT / "protocol/fixtures/test-ping-v1.json"
REORDERED_FIXTURE = ROOT / "protocol/fixtures/test-ping-v1-reordered.json"
VECTOR = ROOT / "protocol/fixtures/signature-v1.txt"
KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
KEY = bytes.fromhex(KEY_HEX)
NOW = 1_788_105_600


def vector():
    return dict(
        line.split("=", 1)
        for line in VECTOR.read_text().splitlines()
        if line and not line.startswith("#")
    )


def event_body(event):
    return json.dumps(event, separators=(",", ":")).encode("utf-8")


def devices(enabled=True):
    event = parse_json(FIXTURE.read_bytes())
    return {event["device_id"]: {"key": KEY, "enabled": enabled}}


class FakeProvider:
    def __init__(self, failure=None, block=False):
        self.failure = failure
        self.event_ids = []
        self.lock = threading.Lock()
        self.started = threading.Event()
        self.release = threading.Event()
        if not block:
            self.release.set()

    def send(self, event_id):
        with self.lock:
            self.event_ids.append(event_id)
        self.started.set()
        self.release.wait(2)
        if self.failure:
            raise self.failure
        return "provider-request-id"


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

    def test_schema_and_runtime_validator_cover_the_same_fields(self):
        schema = json.loads((ROOT / "protocol/alert-v1.schema.json").read_text())
        self.assertEqual(set(schema["required"]), set(schema["properties"]))
        self.assertEqual(set(schema["required"]), set(parse_json(FIXTURE.read_bytes())))


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
        self.assertEqual(first[1], accepted_response(KEY, self.event["event_id"]))
        self.assertEqual(provider.event_ids, [self.event["event_id"]])

    def test_rejects_reused_event_id_with_different_signed_semantics(self):
        relay = self.relay()
        self.assertEqual(relay.process(self.body, self.signature)[0], 200)
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

    def test_clock_window_is_inclusive_and_configurable(self):
        for offset, expected in [(-301, 422), (-300, 200), (1200, 200), (1201, 422)]:
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
            self.assertEqual(first.process(self.body, self.signature)[0], 200)
            first.close()

            second_provider = FakeProvider()
            second = Relay(devices(), second_provider, database, clock=lambda: NOW + 600)
            try:
                status, payload = second.process(self.body, self.signature)
                self.assertEqual(status, 200)
                self.assertEqual(payload["result"], "provider_accepted")
                self.assertEqual(second_provider.event_ids, [])
            finally:
                second.close()

    def test_two_connections_resolve_a_claim_race_without_a_second_send(self):
        ready = threading.Event()
        release = threading.Event()

        class PausedRelay(Relay):
            first_lookup = True

            def _lookup(self, device_id, event_id):
                row = super()._lookup(device_id, event_id)
                if self.first_lookup:
                    self.first_lookup = False
                    ready.set()
                    release.wait(1)
                return row

        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            winner = Relay(devices(), provider, database, clock=lambda: NOW)
            contender = PausedRelay(devices(), provider, database, clock=lambda: NOW)
            self.addCleanup(winner.close)
            self.addCleanup(contender.close)
            result = []
            thread = threading.Thread(
                target=lambda: result.append(contender.process(self.body, self.signature))
            )
            thread.start()
            self.assertTrue(ready.wait(1))
            self.assertEqual(winner.process(self.body, self.signature)[0], 200)
            release.set()
            thread.join(1)

            self.assertEqual(result[0][0], 200)
            self.assertFalse(contender.db.in_transaction)
            self.assertEqual(provider.event_ids, [self.event["event_id"]])

    def test_crashed_started_attempt_is_unknown_and_never_resubmitted(self):
        relay = self.relay()
        digest = hashlib.sha256(canonical_event(self.event)).digest()
        relay.db.execute(
            "INSERT INTO test_events VALUES (?, ?, ?, 'provider_call_started', NULL, ?, ?)",
            (self.event["device_id"], self.event["event_id"], digest, NOW, NOW),
        )
        relay.db.commit()
        status, payload = relay.process(self.body, self.signature)
        self.assertEqual(status, 504)
        self.assertEqual(payload["code"], "prior_attempt_unknown")
        self.assertEqual(relay.provider.event_ids, [])

    def test_attempt_metadata_is_purged_after_24_hours(self):
        current = [NOW]
        relay = Relay(devices(), FakeProvider(), clock=lambda: current[0])
        self.addCleanup(relay.close)
        self.assertEqual(relay.process(self.body, self.signature)[0], 200)
        current[0] += 86_401
        relay.purge_expired()
        count = relay.db.execute("SELECT COUNT(*) FROM test_events").fetchone()[0]
        self.assertEqual(count, 0)

    def test_clock_rollback_after_cross_connection_cleanup_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            database = str(Path(directory) / "events.sqlite3")
            provider = FakeProvider()
            relay = Relay(devices(), provider, database, clock=lambda: NOW)
            self.addCleanup(relay.close)
            self.assertEqual(relay.process(self.body, self.signature)[0], 200)

            cleaner = Relay(
                devices(), FakeProvider(), database, clock=lambda: NOW + 86_401
            )
            cleaner.purge_expired()
            cleaner.close()

            status, payload = relay.process(self.body, self.signature)
            self.assertEqual((status, payload["code"]), (503, "clock_untrusted"))
            self.assertEqual(provider.event_ids, [self.event["event_id"]])

    def test_definite_failure_can_retry_but_ambiguous_failure_cannot(self):
        cases = [
            (ProviderFailure("configuration_failure", "provider_rejected"), 502, 2),
            (ProviderFailure("result_unknown", "provider_result_unknown"), 504, 1),
        ]
        for failure, expected_status, expected_calls in cases:
            with self.subTest(result=failure.result):
                provider = FakeProvider(failure)
                relay = Relay(devices(), provider, clock=lambda: NOW)
                try:
                    self.assertEqual(relay.process(self.body, self.signature)[0], expected_status)
                    provider.failure = None
                    relay.process(self.body, self.signature)
                    self.assertEqual(len(provider.event_ids), expected_calls)
                finally:
                    relay.close()

    def test_twenty_concurrent_duplicates_make_one_provider_call(self):
        provider = FakeProvider(block=True)
        relay = self.relay(provider)
        results = []
        first = threading.Thread(
            target=lambda: results.append(relay.process(self.body, self.signature))
        )
        first.start()
        self.assertTrue(provider.started.wait(1))

        duplicates = [
            threading.Thread(target=lambda: results.append(relay.process(self.body, self.signature)))
            for _ in range(19)
        ]
        for thread in duplicates:
            thread.start()
        for thread in duplicates:
            thread.join(1)
        provider.release.set()
        first.join(1)

        self.assertEqual(provider.event_ids, [self.event["event_id"]])
        self.assertEqual(sum(status == 200 for status, _ in results), 1)
        self.assertEqual(sum(status == 504 for status, _ in results), 19)

    def test_unexpected_provider_exception_does_not_log_its_secret(self):
        class BrokenProvider:
            def send(self, _event_id):
                raise RuntimeError("SENTINEL_PROVIDER_SECRET")

        relay = self.relay(BrokenProvider())
        with self.assertLogs("smart-panic-relay", level="ERROR") as captured:
            status, payload = relay.process(self.body, self.signature)
        self.assertEqual(status, 500)
        self.assertEqual(payload["result"], "result_unknown")
        self.assertNotIn("SENTINEL_PROVIDER_SECRET", "\n".join(captured.output))


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
        self.assertEqual(client.send(event_id), "provider-request-id")
        request = opener.open.call_args.args[0]
        form = parse_qs(request.data.decode())
        self.assertEqual(request.full_url, PushoverClient.URL)
        self.assertEqual(form["message"], [TEST_MESSAGE])
        self.assertEqual(form["title"], [f"Garmin TEST {event_id}"])

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
                    client.send("AAECAwQFBgcICQoLDA0ODw")
                self.assertEqual(raised.exception.result, expected)
                self.assertNotIn("secret", str(raised.exception))


class DeviceConfigTests(unittest.TestCase):
    def test_enabled_device_file_must_be_private(self):
        event = parse_json(FIXTURE.read_bytes())
        config = {event["device_id"]: {"key": KEY_HEX, "enabled": True}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "devices.json"
            path.write_text(json.dumps(config))
            os.chmod(path, 0o600)
            self.assertEqual(load_devices(path)[event["device_id"]]["key"], KEY)
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(ValueError, "mode 0600"):
                load_devices(path)


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

    def request(self, body, headers):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        connection.request("POST", "/v1/events", body=body, headers=headers)
        reply = connection.getresponse()
        payload = json.loads(reply.read())
        connection.close()
        return reply.status, payload

    def test_http_endpoint_accepts_signed_fixture(self):
        status, payload = self.request(
            self.body,
            {
                "Content-Type": "application/json",
                "X-SPB-Signature": signature_for(KEY, canonical_event(self.event)),
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(payload, accepted_response(KEY, self.event["event_id"]))

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
            main(["--devices", "unused", "--database", ":memory:"])
        self.assertIn("persistent SQLite file", errors.getvalue())


if __name__ == "__main__":
    unittest.main()

# SPDX-License-Identifier: MIT

import base64
import hashlib
import http.client
import json
import os
import tempfile
import threading
import unittest
from pathlib import Path

from relay.mailbox import (
    ACK_CIPHERTEXT_BYTES,
    MAX_ACTIVE_MESSAGES,
    MESSAGE_CIPHERTEXT_BYTES,
    MailboxStore,
    capsule_sha256,
    load_mailboxes,
)
from relay.server import Relay, make_server, parse_json
from scripts.mailbox_enroll import main as enroll_main


ROOT = Path(__file__).parents[1]
FIXTURE = ROOT / "protocol/fixtures/test-ping-v1.json"
NOW = 1_788_105_600
MAILBOX_ID = base64.urlsafe_b64encode(bytes(range(16))).rstrip(b"=").decode()
APPEND_CAP = bytes([0xA1]) * 32
READ_CAP = bytes([0xB2]) * 32
ACK_CAP = bytes([0xC3]) * 32


def bearer(capability):
    return "Bearer " + base64.urlsafe_b64encode(capability).rstrip(b"=").decode()


def mailbox_config(enabled=True):
    return {
        MAILBOX_ID: {
            "enabled": enabled,
            "append": hashlib.sha256(APPEND_CAP).digest(),
            "read": hashlib.sha256(READ_CAP).digest(),
            "ack": hashlib.sha256(ACK_CAP).digest(),
        }
    }


def message(message_id=None, expires_at=NOW + 3600, fill=1):
    return {
        "v": 1,
        "mailbox_id": MAILBOX_ID,
        "message_id": message_id or base64.urlsafe_b64encode(bytes(range(16, 32))).rstrip(b"=").decode(),
        "expires_at": expires_at,
        "payload": {
            "iv": base64.urlsafe_b64encode(bytes(16)).rstrip(b"=").decode(),
            "ciphertext": base64.urlsafe_b64encode(bytes([fill]) * MESSAGE_CIPHERTEXT_BYTES).rstrip(b"=").decode(),
            "tag": base64.urlsafe_b64encode(bytes([fill + 1]) * 32).rstrip(b"=").decode(),
        },
    }


def acknowledgement(item, fill=3):
    return {
        "v": 1,
        "message_id": item["message_id"],
        "capsule_sha256": capsule_sha256(item).hex(),
        "payload": {
            "iv": base64.urlsafe_b64encode(bytes([fill]) * 16).rstrip(b"=").decode(),
            "ciphertext": base64.urlsafe_b64encode(bytes([fill + 1]) * ACK_CIPHERTEXT_BYTES).rstrip(b"=").decode(),
            "tag": base64.urlsafe_b64encode(bytes([fill + 2]) * 32).rstrip(b"=").decode(),
        },
    }


class MailboxStoreTests(unittest.TestCase):
    def setUp(self):
        self.clock = [NOW]
        self.relay = Relay(
            {},
            None,
            clock=lambda: self.clock[0],
            mailboxes=mailbox_config(),
        )

    def tearDown(self):
        self.relay.close()

    def append(self, item, capability=APPEND_CAP):
        return self.relay.mailbox.append(
            MAILBOX_ID,
            json.dumps(item, separators=(",", ":")).encode(),
            bearer(capability),
        )

    def test_append_read_ack_and_sender_ack_read_are_capability_separated(self):
        item = message()
        status, accepted = self.append(item)
        self.assertEqual(status, 202)
        self.assertEqual(accepted["message_id"], item["message_id"])
        self.assertEqual(accepted["result"], "durably_accepted")
        self.assertRegex(accepted["response_mac"], r"^v1=[A-Za-z0-9_-]{43}$")

        self.assertEqual(
            self.relay.mailbox.list_messages(MAILBOX_ID, bearer(APPEND_CAP))[0],
            401,
        )
        status, pending = self.relay.mailbox.list_messages(
            MAILBOX_ID, bearer(READ_CAP)
        )
        self.assertEqual(status, 200)
        self.assertEqual(pending["messages"], [item])

        ack = acknowledgement(item)
        status, accepted_ack = self.relay.mailbox.acknowledge(
            MAILBOX_ID,
            json.dumps(ack, separators=(",", ":")).encode(),
            bearer(ACK_CAP),
        )
        self.assertEqual(status, 202)
        self.assertEqual(accepted_ack["message_id"], item["message_id"])
        self.assertEqual(
            self.relay.mailbox.list_messages(MAILBOX_ID, bearer(READ_CAP))[1]["messages"],
            [],
        )
        status, evidence = self.relay.mailbox.list_acknowledgements(
            MAILBOX_ID, bearer(APPEND_CAP)
        )
        self.assertEqual(status, 200)
        self.assertEqual(evidence["acknowledgements"], [ack])

    def test_immutable_retry_and_conflict(self):
        item = message()
        first = self.append(item)
        second = self.append(item)
        self.assertEqual(first, second)
        changed = message(fill=7)
        self.assertEqual(self.append(changed)[0], 409)

    def test_node_reference_capsule_has_the_same_semantic_digest(self):
        fixture = json.loads(
            (ROOT / "protocol/fixtures/mailbox-message-v1.json").read_text()
        )
        self.assertEqual(
            capsule_sha256(fixture).hex(),
            "bae4682120b8ed891c0fc7e3a5aeab673ac171a6f8c6015c4d0d86942b6d5f15",
        )

    def test_unknown_disabled_and_wrong_capability_are_indistinguishable(self):
        item = message()
        wrong = self.relay.mailbox.append(
            MAILBOX_ID,
            json.dumps(item).encode(),
            bearer(bytes([0xDD]) * 32),
        )
        unknown = self.relay.mailbox.append(
            base64.urlsafe_b64encode(bytes([0xEE]) * 16).rstrip(b"=").decode(),
            json.dumps({**item, "mailbox_id": base64.urlsafe_b64encode(bytes([0xEE]) * 16).rstrip(b"=").decode()}).encode(),
            bearer(APPEND_CAP),
        )
        self.relay.mailbox.mailboxes = mailbox_config(enabled=False)
        disabled = self.append(item)
        self.assertEqual(wrong, unknown)
        self.assertEqual(unknown, disabled)
        self.assertEqual(wrong[0], 401)

    def test_expiry_quota_and_retention_are_bounded(self):
        self.assertEqual(self.append(message(expires_at=NOW))[0], 422)
        self.assertEqual(self.append(message(expires_at=NOW + 86_401))[0], 422)
        for index in range(MAX_ACTIVE_MESSAGES):
            message_id = base64.urlsafe_b64encode(index.to_bytes(16, "big")).rstrip(b"=").decode()
            self.assertEqual(self.append(message(message_id=message_id))[0], 202)
        overflow_id = base64.urlsafe_b64encode((MAX_ACTIVE_MESSAGES + 1).to_bytes(16, "big")).rstrip(b"=").decode()
        self.assertEqual(self.append(message(message_id=overflow_id))[0], 429)
        self.clock[0] = NOW + 3600 + 86_400
        self.relay.purge_expired()
        self.assertEqual(
            self.relay.db.execute("SELECT COUNT(*) FROM mailbox_messages").fetchone()[0],
            0,
        )


class MailboxConfigTests(unittest.TestCase):
    def test_config_contains_only_capability_hashes_and_requires_private_mode(self):
        raw = {
            MAILBOX_ID: {
                "enabled": True,
                "append_cap_sha256": hashlib.sha256(APPEND_CAP).hexdigest(),
                "read_cap_sha256": hashlib.sha256(READ_CAP).hexdigest(),
                "ack_cap_sha256": hashlib.sha256(ACK_CAP).hexdigest(),
            }
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mailboxes.json"
            path.write_text(json.dumps(raw))
            os.chmod(path, 0o644)
            with self.assertRaisesRegex(ValueError, "mode 0600"):
                load_mailboxes(path)
            os.chmod(path, 0o600)
            loaded = load_mailboxes(path)
        self.assertEqual(loaded, mailbox_config())
        self.assertNotIn(bearer(APPEND_CAP)[7:], json.dumps(raw))

    def test_enrollment_generator_separates_server_hashes_from_private_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            server_path = Path(directory) / "server.json"
            enrollment_path = Path(directory) / "enrollment.json"
            self.assertEqual(
                enroll_main([
                    "--server-record",
                    str(server_path),
                    "--enrollment",
                    str(enrollment_path),
                ]),
                0,
            )
            self.assertEqual(server_path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(enrollment_path.stat().st_mode & 0o777, 0o600)
            server = json.loads(server_path.read_text())
            enrollment = json.loads(enrollment_path.read_text())
            record = server[enrollment["mailbox_id"]]
            for role in ("append", "read", "ack"):
                capability = base64.urlsafe_b64decode(enrollment[f"{role}_cap"] + "=")
                self.assertEqual(
                    record[f"{role}_cap_sha256"],
                    hashlib.sha256(capability).hexdigest(),
                )
                self.assertNotIn(enrollment[f"{role}_cap"], server_path.read_text())
            with self.assertRaises(FileExistsError):
                enroll_main([
                    "--server-record",
                    str(server_path),
                    "--enrollment",
                    str(enrollment_path),
                ])


class MailboxHttpTests(unittest.TestCase):
    def setUp(self):
        self.relay = Relay({}, None, clock=lambda: NOW, mailboxes=mailbox_config())
        self.server = make_server("127.0.0.1", 0, self.relay)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.relay.close()

    def request(self, method, resource, body=None, capability=None):
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=2
        )
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if capability is not None:
            headers["Authorization"] = bearer(capability)
        connection.request(
            method,
            f"/mailbox/v1/{MAILBOX_ID}/{resource}",
            body=body,
            headers=headers,
        )
        reply = connection.getresponse()
        payload = json.loads(reply.read())
        connection.close()
        return reply.status, payload

    def test_http_round_trip(self):
        item = message()
        body = json.dumps(item, separators=(",", ":")).encode()
        self.assertEqual(self.request("POST", "messages", body, APPEND_CAP)[0], 202)
        status, pending = self.request("GET", "messages", capability=READ_CAP)
        self.assertEqual(status, 200)
        self.assertEqual(pending["messages"], [item])
        ack = acknowledgement(item)
        ack_body = json.dumps(ack, separators=(",", ":")).encode()
        self.assertEqual(
            self.request("POST", "acknowledgements", ack_body, ACK_CAP)[0],
            202,
        )
        self.assertEqual(
            self.request("GET", "acknowledgements", capability=APPEND_CAP)[1]["acknowledgements"],
            [ack],
        )


if __name__ == "__main__":
    unittest.main()

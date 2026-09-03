# SPDX-License-Identifier: MIT

import base64
import hashlib
import hmac
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]


class StatusVectorTests(unittest.TestCase):
    def test_status_query_and_response_vectors(self):
        values = dict(
            line.split("=", 1)
            for line in (ROOT / "protocol/fixtures/status-v2.txt").read_text().splitlines()
            if line and not line.startswith("#")
        )
        query = json.loads((ROOT / "protocol/fixtures/status-query-v2.json").read_text())
        self.assertEqual(
            set(query),
            {"v", "request_id", "incident_id", "device_id", "created_at", "expires_at"},
        )

        request = (
            "opendistress.status.query.v2\n"
            "method=POST\n"
            "v=2\n"
            f"request_id={query['request_id']}\n"
            f"incident_id={query['incident_id']}\n"
            f"device_id={query['device_id']}\n"
            f"created_at={query['created_at']}\n"
            f"expires_at={query['expires_at']}\n"
        ).encode("ascii")
        response = (
            "opendistress.status.result.v2\n"
            "v=2\n"
            f"request_id={query['request_id']}\n"
            f"incident_id={query['incident_id']}\n"
            f"device_id={query['device_id']}\n"
            f"state={values['state']}\n"
            f"checked_at={values['checked_at']}\n"
        ).encode("ascii")
        key = bytes.fromhex(values["auth_key_hex"])

        for name, canonical in (("request", request), ("response", response)):
            digest = hmac.new(key, canonical, hashlib.sha256).digest()
            signature = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
            self.assertEqual(len(canonical), int(values[f"canonical_{name}_length"]))
            self.assertEqual(canonical.hex(), values[f"canonical_{name}_hex"])
            self.assertEqual(digest.hex(), values[f"{name}_hmac_hex"])
            self.assertEqual(f"v2={signature}", values[f"{name}_signature"])

        expected_response = {
            "v": 2,
            "request_id": query["request_id"],
            "incident_id": query["incident_id"],
            "device_id": query["device_id"],
            "state": values["state"],
            "checked_at": int(values["checked_at"]),
            "response_signature": values["response_signature"],
        }
        self.assertEqual(json.loads(values["response_json"]), expected_response)


if __name__ == "__main__":
    unittest.main()

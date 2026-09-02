# SPDX-License-Identifier: MIT
"""Create one blind-mailbox server record and its private enrollment bundle."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
from pathlib import Path


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def write_private_json(path: Path, value: dict):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, indent=2)
            output.write("\n")
    except Exception:
        try:
            path.unlink()
        except OSError:
            pass
        raise


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="generate a content-blind mailbox enrollment"
    )
    parser.add_argument("--server-record", required=True, type=Path)
    parser.add_argument("--enrollment", required=True, type=Path)
    args = parser.parse_args(argv)
    if args.server_record == args.enrollment:
        parser.error("server record and enrollment must use different paths")

    mailbox_id = b64url(secrets.token_bytes(16))
    append_cap = secrets.token_bytes(32)
    read_cap = secrets.token_bytes(32)
    ack_cap = secrets.token_bytes(32)
    server_record = {
        mailbox_id: {
            "enabled": True,
            "append_cap_sha256": hashlib.sha256(append_cap).hexdigest(),
            "read_cap_sha256": hashlib.sha256(read_cap).hexdigest(),
            "ack_cap_sha256": hashlib.sha256(ack_cap).hexdigest(),
        }
    }
    enrollment = {
        "v": 1,
        "mailbox_id": mailbox_id,
        "append_cap": b64url(append_cap),
        "read_cap": b64url(read_cap),
        "ack_cap": b64url(ack_cap),
        "send_enc_key_hex": secrets.token_hex(32),
        "send_mac_key_hex": secrets.token_hex(32),
        "ack_enc_key_hex": secrets.token_hex(32),
        "ack_mac_key_hex": secrets.token_hex(32),
    }
    write_private_json(args.server_record, server_record)
    try:
        write_private_json(args.enrollment, enrollment)
    except Exception:
        args.server_record.unlink()
        raise
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Write a small SPDX 2.3 SBOM for the files in a Git source release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def build_document(root: Path, files: list[str], version: str) -> dict:
    entries = []
    verification_hashes = []
    relationships = [
        {
            "spdxElementId": "SPDXRef-DOCUMENT",
            "relationshipType": "DESCRIBES",
            "relatedSpdxElement": "SPDXRef-Package",
        }
    ]
    for name in sorted(files):
        data = (root / name).read_bytes()
        sha1 = hashlib.sha1(data).hexdigest()
        verification_hashes.append(sha1)
        file_id = "SPDXRef-File-" + hashlib.sha1(name.encode()).hexdigest()
        entries.append(
            {
                "SPDXID": file_id,
                "fileName": "./" + name,
                "checksums": [
                    {"algorithm": "SHA1", "checksumValue": sha1},
                    {"algorithm": "SHA256", "checksumValue": hashlib.sha256(data).hexdigest()},
                ],
                "licenseConcluded": "NOASSERTION",
                "copyrightText": "NOASSERTION",
            }
        )
        relationships.append(
            {
                "spdxElementId": "SPDXRef-Package",
                "relationshipType": "CONTAINS",
                "relatedSpdxElement": file_id,
            }
        )

    verification = hashlib.sha1("".join(sorted(verification_hashes)).encode()).hexdigest()
    repository = "https://github.com/tobomobo/opendistress"
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    created = (
        datetime.fromtimestamp(int(epoch), timezone.utc)
        if epoch is not None
        else datetime.now(timezone.utc)
    )
    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": f"opendistress-{version}",
        "documentNamespace": f"{repository}/releases/download/v{version}/source-sbom.spdx.json",
        "creationInfo": {
            "created": created.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "creators": ["Tool: scripts/source_sbom.py"],
        },
        "packages": [
            {
                "name": "opendistress",
                "SPDXID": "SPDXRef-Package",
                "versionInfo": version,
                "downloadLocation": (
                    f"{repository}/releases/download/v{version}/"
                    f"opendistress-{version}.tar.gz"
                ),
                "filesAnalyzed": True,
                "packageVerificationCode": {"packageVerificationCodeValue": verification},
                "licenseConcluded": "MIT",
                "licenseDeclared": "MIT",
                "copyrightText": "NOASSERTION",
            }
        ],
        "files": entries,
        "relationships": relationships,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    tracked = subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=root
    ).decode().rstrip("\0").split("\0")
    args.output.write_text(
        json.dumps(build_document(root, tracked, args.version), indent=2) + "\n"
    )


if __name__ == "__main__":
    main()

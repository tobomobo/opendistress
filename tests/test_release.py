# SPDX-License-Identifier: MIT

import hashlib
import os
import tempfile
import unittest
from pathlib import Path

from scripts.source_sbom import build_document


ROOT = Path(__file__).resolve().parents[1]


class SourceSbomTests(unittest.TestCase):
    def test_document_contains_each_file_and_verifiable_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "a.txt").write_text("alpha")
            (root / "b.txt").write_text("beta")
            previous = os.environ.get("SOURCE_DATE_EPOCH")
            os.environ["SOURCE_DATE_EPOCH"] = "0"
            try:
                document = build_document(root, ["b.txt", "a.txt"], "1.2.3")
            finally:
                if previous is None:
                    os.environ.pop("SOURCE_DATE_EPOCH", None)
                else:
                    os.environ["SOURCE_DATE_EPOCH"] = previous

        self.assertEqual(document["spdxVersion"], "SPDX-2.3")
        self.assertEqual(document["packages"][0]["versionInfo"], "1.2.3")
        self.assertEqual(
            document["packages"][0]["downloadLocation"],
            "https://github.com/tobomobo/smart-panic-button/releases/download/"
            "v1.2.3/smart-panic-button-1.2.3.tar.gz",
        )
        self.assertEqual(document["creationInfo"]["created"], "1970-01-01T00:00:00Z")
        self.assertEqual([entry["fileName"] for entry in document["files"]], ["./a.txt", "./b.txt"])
        self.assertEqual(
            document["files"][0]["checksums"][1]["checksumValue"],
            hashlib.sha256(b"alpha").hexdigest(),
        )

    def test_release_binds_tag_object_and_publishes_portable_checksums(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()

        self.assertIn("--jq .tag)\" = \"$GITHUB_REF_NAME\"", workflow)
        self.assertIn("--jq .object.type)\" = commit", workflow)
        self.assertIn("--jq .object.sha)\" = \"$(git rev-parse HEAD)\"", workflow)
        self.assertIn("checks: read", workflow)
        self.assertIn("git merge-base --is-ancestor HEAD origin/main", workflow)
        self.assertIn('for check in "test (3.11)" "test (3.13)" wearos watchos', workflow)
        self.assertIn(".app.id == 15368", workflow)
        self.assertIn(".conclusion == \"success\"", workflow)
        self.assertIn("-f filter=latest", workflow)
        self.assertIn("cd dist", workflow)
        self.assertIn(
            'sha256sum "smart-panic-button-${version}.tar.gz" '
            "source-sbom.spdx.json > SHA256SUMS",
            workflow,
        )
        self.assertNotIn('sha256sum "dist/', workflow)

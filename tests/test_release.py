# SPDX-License-Identifier: MIT

import hashlib
import os
import subprocess
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
            "https://github.com/tobomobo/opendistress/releases/download/"
            "v1.2.3/opendistress-1.2.3.tar.gz",
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
            'sha256sum "opendistress-${version}.tar.gz" '
            "source-sbom.spdx.json > SHA256SUMS",
            workflow,
        )
        self.assertNotIn('sha256sum "dist/', workflow)
        self.assertIn('release_flags+=(--prerelease)', workflow)
        self.assertIn('--verify-tag --generate-notes --draft', workflow)

    def test_garmin_beta_release_is_signed_gated_and_manual(self):
        workflow = (ROOT / ".github/workflows/garmin-beta-release.yml").read_text()

        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("pull_request:", workflow)
        self.assertIn("runs-on: [self-hosted, macOS, ARM64, connect-iq-release]", workflow)
        self.assertIn("environment: garmin-beta", workflow)
        self.assertIn("secrets.GARMIN_DEVELOPER_KEY_DER_BASE64", workflow)
        self.assertNotIn("GARMIN_USERNAME", workflow)
        self.assertNotIn("GARMIN_PASSWORD", workflow)
        self.assertIn("command -v openssl", workflow)
        self.assertIn("command -v gh", workflow)
        self.assertIn('--jq .verification.verified)" = true', workflow)
        self.assertIn("git merge-base --is-ancestor HEAD origin/main", workflow)
        self.assertIn('for check in "test (3.11)" "test (3.13)" wearos watchos', workflow)
        self.assertIn('--json isPrerelease --jq .isPrerelease)" = true', workflow)
        self.assertEqual(workflow.count('--json isDraft --jq .isDraft)" = true'), 3)
        self.assertIn("commit: ${{ steps.release.outputs.commit }}", workflow)
        self.assertIn("tag_object: ${{ steps.release.outputs.tag_object }}", workflow)
        self.assertIn("ref: ${{ needs.validate.outputs.commit }}", workflow)
        self.assertNotIn("ref: ${{ needs.validate.outputs.tag }}", workflow)
        self.assertIn("EXPECTED_COMMIT: ${{ needs.validate.outputs.commit }}", workflow)
        self.assertIn(
            "EXPECTED_TAG_OBJECT: ${{ needs.validate.outputs.tag_object }}", workflow
        )
        self.assertGreaterEqual(workflow.count("= \"$EXPECTED_TAG_OBJECT\""), 2)
        self.assertIn("scripts/build_garmin_beta_release.sh", workflow)
        self.assertIn('gh release upload "$RELEASE_TAG" dist/garmin/* --clobber', workflow)
        self.assertIn("if: always()", workflow)
        self.assertIn('run: rm -f "$GARMIN_KEY_PATH"', workflow)
        self.assertIn("persist-credentials: false", workflow)

    def test_garmin_beta_builder_pins_sdk_and_packages_beta_manifest(self):
        script = (ROOT / "scripts/build_garmin_beta_release.sh").read_text()

        self.assertIn('compiler_version="$(monkeyc --version 2>&1)"', script)
        self.assertIn("SDK 9.2.0 is required", script)
        self.assertIn("monkeyc -e -f beta.jungle", script)
        self.assertIn("-l 1 -w", script)
        self.assertIn('OpenDistress-TEST-${version}.iq', script)
        self.assertIn("sha256sum", script)
        self.assertIn("shasum -a 256", script)

    def test_garmin_beta_builder_uses_absolute_key_and_portable_checksum(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            temporary = Path(directory)
            fake_bin = temporary / "bin"
            fake_bin.mkdir()
            fake_monkeyc = fake_bin / "monkeyc"
            fake_monkeyc.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "--version" ]]; then
  echo "Connect IQ Compiler version 9.2.0"
  exit 0
fi
output=""
key=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) output="$2"; shift 2 ;;
    -y) key="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[[ "$key" == /* ]]
[[ -s "$key" ]]
printf 'test Connect IQ package\n' > "$output"
"""
            )
            fake_monkeyc.chmod(0o755)
            key = temporary / "developer-key.der"
            key.write_bytes(b"test signing key")
            output = temporary / "output"
            relative_key = key.relative_to(ROOT)

            subprocess.run(
                [
                    str(ROOT / "scripts/build_garmin_beta_release.sh"),
                    "0.2.0-test.1",
                    str(relative_key),
                    str(output),
                ],
                cwd=ROOT,
                env={**os.environ, "PATH": f"{fake_bin}:{os.environ['PATH']}"},
                check=True,
                capture_output=True,
                text=True,
            )

            artifact = output / "OpenDistress-TEST-0.2.0-test.1.iq"
            checksum = artifact.with_suffix(".iq.sha256")
            checksum_fields = checksum.read_text().split()
            self.assertEqual(
                checksum_fields[0], hashlib.sha256(artifact.read_bytes()).hexdigest()
            )
            self.assertEqual(checksum_fields[1], artifact.name)

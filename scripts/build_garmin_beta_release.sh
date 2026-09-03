#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 VERSION DEVELOPER_KEY_DER OUTPUT_DIRECTORY" >&2
  exit 2
fi

version="$1"
key_path="$2"
output_directory="$3"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-(beta|test)\.[0-9]+$ ]]; then
  echo "VERSION must be MAJOR.MINOR.PATCH-beta.N or -test.N" >&2
  exit 2
fi
if [[ ! -s "$key_path" ]]; then
  echo "developer key does not exist or is empty" >&2
  exit 2
fi
key_directory="$(cd "$(dirname "$key_path")" && pwd)"
key_path="$key_directory/$(basename "$key_path")"
if ! command -v monkeyc >/dev/null 2>&1; then
  echo "monkeyc is not on PATH" >&2
  exit 2
fi

compiler_version="$(monkeyc --version 2>&1)"
if [[ "$compiler_version" != *"9.2.0"* ]]; then
  echo "Connect IQ SDK 9.2.0 is required; got: $compiler_version" >&2
  exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$output_directory"
output_directory="$(cd "$output_directory" && pwd)"
artifact="$output_directory/OpenDistress-TEST-${version}.iq"
checksum="$artifact.sha256"

(
  cd "$repository_root/apps/garmin"
  monkeyc -e -f beta.jungle -o "$artifact" -y "$key_path" -l 1 -w
)
test -s "$artifact"

if command -v sha256sum >/dev/null 2>&1; then
  (
    cd "$output_directory"
    sha256sum "$(basename "$artifact")" > "$(basename "$checksum")"
  )
else
  (
    cd "$output_directory"
    shasum -a 256 "$(basename "$artifact")" > "$(basename "$checksum")"
  )
fi

echo "Built $artifact"
echo "Checksum $checksum"

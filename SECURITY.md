# Security policy

Please report vulnerabilities through [GitHub private vulnerability
reporting](https://github.com/tobomobo/smart-panic-button/security/advisories/new).
Do not include secrets, real contacts, coordinates, or production incident data
in a report. If private reporting is unavailable, open a public issue containing
no sensitive details and ask the maintainer for a private contact path.

Only the current default branch is supported during the prototype stage.

## Invariants

- Each watch has a unique random 32-byte HMAC key, provisioned as 64 lowercase
  hexadecimal characters; provider credentials stay on the relay.
- Request signatures cover the protocol's canonical semantic text, not JSON
  wire bytes, and use constant-time comparison.
- TEST IDs are canonical 22-character base64url values. `expires_at` is
  exactly 900 seconds after `created_at` and both values remain unchanged on
  retry.
- Unknown devices, disabled devices, and invalid signatures share one external
  authentication failure.
- The phase-1 attempt ledger is committed before provider submission. A
  `started` record left by interruption, or an ambiguous provider outcome, is
  never automatically submitted again.
- The watch treats a response as provider acceptance only when its event ID
  and canonical response signature verify.
- Raw request bodies, authentication headers, secrets, locations, and client
  IP addresses are never logged.
- TEST payloads are non-sensitive. LIVE identity, instructions, and location
  must be encrypted on the watch before Garmin Connect can observe them.
- A provider acceptance is never described as recipient delivery or human
  acknowledgement.
- The phase-1 Pushover account has exactly one active Android device; provider
  device-name targeting is not treated as fail-closed.

The current risks and trust boundaries are detailed in
[`docs/threat-model.md`](docs/threat-model.md).

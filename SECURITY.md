# Security policy

Report vulnerabilities through [GitHub private vulnerability
reporting](https://github.com/tobomobo/smart-panic-button/security/advisories/new).
Do not include credentials, real contacts, coordinates, acknowledgement links,
or production incident data. If private reporting is unavailable, open a public
issue with no sensitive detail and ask for a private contact path.

Only the current default branch is supported.

## Security invariants

- Every sender has an independent random 32-byte request-HMAC key. V2 uses two
  additional independent 32-byte content keys for AES-256 and HMAC-SHA256.
- Requests and results authenticate the exact versioned semantic grammar, not
  JSON serialization. Comparisons are constant-time after strict decoding.
- Public fixture keys are rejected by enabled runtime configurations.
- Events are immutable. The relay stores their canonical digest under a unique
  device/event key and enforces v2 incident expiry and sequence in one write
  transaction.
- Signed HTTP 202 proves only durable relay intake. Provider acceptance,
  transport delivery, recipient acknowledgement, and resolution remain
  independent evidence.
- Provider work is claimed durably before I/O and completion is lease-fenced.
  Recovery is at-least-once and can create a duplicate after an ambiguous call;
  the project makes no exactly-once claim.
- Recipient membership and a domain-separated provider-configuration
  fingerprint are snapshotted with intake. A worker with changed credentials or
  destination cannot claim old delivery, receipt, or cancellation work.
- V2 content is authenticated before decryption. The relay never receives
  encryption or content-MAC keys and never logs or decrypts ciphertext.
- Device, route, and SQLite files containing active configuration use mode 0600.
  Provider credentials never appear in logs or watch-facing URLs. Pushover's
  receipt API requires its application token in an outbound HTTPS query, so
  egress URL logging must also be disabled.
- Unknown, disabled, missing-key, and invalid-signature devices share one
  external authentication failure.
- Acknowledgement is per recipient and append-only. It never silently resolves
  an incident or erases later acknowledgement evidence.
- Bodies, auth/signature headers, credentials, coordinates, and
  client IP addresses are never logged by the reference relay.
- Event rows and their cascading delivery evidence are deleted by event expiry
  plus 24 hours; encrypted location has the stricter privacy window documented
  in [`docs/privacy.md`](docs/privacy.md).
- No compiler, provider, simulator, or physical-device result is claimed unless
  its evidence is recorded.

Current residual risks and production blockers are in
[`docs/threat-model.md`](docs/threat-model.md).

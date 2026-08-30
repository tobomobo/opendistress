# Decisions

## 2026-08-30 — Share a protocol, not application code

Garmin remains Monkey C. Future Wear OS and watchOS clients remain native. The
versioned incident envelope, fixtures, and state semantics are the shared seam.

## 2026-08-30 — Prove one non-sensitive synchronous path first

Phase 1 sends only `test.triggered` through one relay endpoint to one fixed
Pushover recipient. It includes only the SQLite attempt ledger required to
make replay and restart behavior honest; the retrying outbox, acknowledgement,
location, more transports, and platform abstractions wait for later gates.

## 2026-08-30 — Sign canonical event semantics

Each watch uses its own 256-bit HMAC-SHA256 key. Signing the exact transmitted
JSON is not a stable contract because Garmin controls serialization of the
`Dictionary`. Instead, both runtimes build one fixed, line-oriented ASCII
signing input from strictly validated fields. The public golden vector catches
encoding drift, while a reordered JSON fixture proves wire order is irrelevant.

## 2026-08-30 — Make phase-1 ambiguity terminal

The relay commits `started` before its single Pushover attempt and commits
`accepted` before returning success. A crash, timeout, or ambiguous provider
outcome remains `result_unknown`; the relay never automatically resubmits it.
This is smaller and more honest than pretending the provider boundary supports
exactly-once delivery.

## 2026-08-30 — Require one-device Pushover isolation

Pushover can deliver to every active account device when a requested device
name is absent or stale. The first milestone therefore uses a dedicated
Pushover account with exactly one active Android device.

## 2026-08-30 — Block sensitive data until application encryption

TLS protects network hops but does not make Garmin Connect a trusted plaintext
processor. LIVE identity, instructions, and location wait for encryption on
the watch.

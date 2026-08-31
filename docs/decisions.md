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

## 2026-08-30 — Use each platform's memory-safe supported language

Connect IQ only runs Monkey C applications, so Rust cannot replace the Garmin
client. Garmin release builds must pass the compiler's strictest type-check
level and use only Garmin cryptographic primitives. SDK 9.2.0 now compiles the
source at `-l 1` and passes its protocol simulator test, but it has not passed
the strict `-l 3` gate. Wear OS uses Kotlin, watchOS uses Swift, and the relay
and recipient tools use Python and JavaScript. A Rust relay would be viable,
but would not remove Monkey C from the watch and is not justified by the
current small, standard-library implementation.

Swift's standard CryptoKit does not expose the AES-CBC profile required for
Garmin interoperability, so watchOS uses one narrowly bounded CommonCrypto call
over validated 16-byte blocks and 32-byte keys. Hosted compilation and sanitizer
review remain gates for that native boundary; replacing it with handwritten
crypto would be less safe.

## 2026-08-30 — Encrypt fixed records with Garmin-supported primitives

Connect IQ exposes AES-CBC and HMAC-SHA256 but no authenticated-encryption
mode. Version 2 therefore encrypts one fixed 16-byte block with AES-256-CBC and
a fresh authenticated IV, then authenticates the complete semantic envelope
with a separate HMAC key. Content and device-authentication keys are distinct.

## 2026-08-30 — Prefer durable intake over synchronous provider evidence

The relay now commits the event and its delivery rows before returning a signed
`202 durably_accepted`. Workers reclaim expired leases and retry transient or
ambiguous outcomes until incident expiry. This is deliberately at-least-once:
a provider may accept twice if the relay loses the result after submission.

## 2026-08-30 — Implement ntfy as the second transport

Direct Android SMS needs a provisioned companion app, runtime permission,
phone/SIM hardware, and carrier testing that are absent here. A direct HTTPS
ntfy implementation provides a real second delivery path
without pretending SMS was validated. The only shared transport shape is the
one demonstrated by Pushover and ntfy.

## 2026-08-30 — Do not invent the conditional launcher face

The Garmin app-list, glance, and published complication surfaces are present.
The separate launcher face remains absent because the plan requires physical
measurements to show those stock surfaces are inadequate first.

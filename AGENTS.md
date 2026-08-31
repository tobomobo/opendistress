# Agent guide

This repository implements a Garmin-first panic notification system. Read
[`README.md`](README.md), [`docs/architecture.md`](docs/architecture.md), and
the normative [`protocol/README.md`](protocol/README.md) before changing a
cross-component flow.

## Map

- `protocol/` owns the v1 TEST and encrypted v2 wire contracts and vectors.
- `relay/` authenticates events, commits the SQLite outbox, and runs delivery
  workers.
- `recipient/` authenticates and decrypts v2 envelopes outside the relay.
- `apps/garmin/`, `apps/wearos/`, and `apps/watchos/` are independent native
  clients of the protocol.
- `tests/end-to-end/` records physical evidence; `NOT_RUN` never means pass.

## Quality gates

Run `make ci` before handoff. Wear OS and watchOS also have hosted native
builds. Garmin compilation and every physical-device row remain separate gates
because the Connect IQ SDK and hardware are not installed by this repository.

## Invariants

- Garmin serializes a `Dictionary` into wire JSON. Sign the protocol's
  canonical semantic text, never raw JSON bytes or key order.
- An immutable retry keeps the same ID, timestamps, ciphertext, and signature.
- A signed `202 durably_accepted` proves only the relay transaction committed.
  It is not provider acceptance, device delivery, acknowledgment, or resolution.
- A verified terminal status may archive pending v2 entries for that incident as
  unroutable; it never turns them into accepted events or removes unrelated work.
- Delivery workers use durable leases and fencing. A provider call interrupted
  after submission may be repeated after lease expiry; at-least-once attempts
  can produce duplicate notifications and must never be described as exactly once.
- An accepted route is bound to its persisted provider-configuration
  fingerprint. A process with different credentials or destination must never
  claim its delivery or evidence work.
- Keep watch recognition, relay intake, provider acceptance, transport delivery,
  per-recipient acknowledgment, and incident resolution as separate facts.
- TEST contains no sensitive content. V2 LIVE and location content is encrypted
  before platform networking with independent authentication, encryption, and
  content-MAC keys. The relay never receives content keys.
- Validate and authenticate before decrypting or persisting untrusted content.
- Never log bodies, signature or authorization headers, credentials, locations,
  acknowledgment capabilities, or client IP addresses.
- Purge event rows by expiry plus 24 hours. Location ciphertext is removed on
  resolution or expiry and no later than creation plus 24 hours.
- Acknowledgment is append-only recipient evidence; it does not resolve an
  incident or erase later acknowledgments.
- Add platform or transport code directly. Extract shared code only when two
  implementations expose the same necessary seam.
- Never claim compiler, simulator, provider, or hardware success without its
  recorded evidence.

## Hard-won gotchas

- `Toybox.Communications.makeWebRequest()` controls JSON serialization, so raw
  request-byte HMACs cannot be reproduced reliably across Garmin and the relay.
- Pushover has no idempotency key. Lease recovery deliberately chooses eventual
  retry over an impossible exactly-once claim.
- Pushover device-name targeting may fan out when stale; route individual
  recipient keys and keep recipient evidence separate.
- Connect IQ requires Monkey C. Rust is not an on-watch target; SDK type checking
  and physical tests are mandatory even though the VM manages memory.
- Connect IQ 9.2 simulator execution showed that runtime-created `String`
  values can fail `==` even when their text matches. Use `String.equals()`
  after a type check for semantic values; keep the constant-work comparator for
  signatures.

Add a gotcha only after observing a real failure, and include the verified
workaround.

## References

- [`SECURITY.md`](SECURITY.md) — reporting and security invariants
- [`docs/reliability.md`](docs/reliability.md) — evidence and physical tests
- [`docs/platform-limitations.md`](docs/platform-limitations.md) — platform limits
- [`docs/roadmap.md`](docs/roadmap.md) — implemented slices and remaining gates

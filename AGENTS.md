# Agent guide

This repository implements a Garmin-first panic notification system. Read
[`README.md`](README.md) for setup and [`docs/architecture.md`](docs/architecture.md)
before changing a cross-component flow.

## Map

- `protocol/` is the shared wire contract and golden vectors.
- `relay/` authenticates TEST events and submits the fixed notification.
- `apps/garmin/` is the foreground Connect IQ sender.
- `tests/` contains host-side conformance and end-to-end checks.

Do not create future platform directories until their roadmap gate is met.

## Quality gate

Run `make ci` before handing off a change. Garmin compilation is a separate
gate because the Connect IQ SDK is not installed by the project.

## Invariants

- Garmin serializes a `Dictionary` into the wire JSON. Sign and verify the
  protocol's canonical semantic text, never the raw JSON bytes or a
  relay-specific reserialization.
- Treat event IDs as immutable idempotency keys. A retry keeps the same event
  values, ID, creation time, and expiry; cancellation is a new event.
- Persist the phase-1 provider-attempt claim before calling Pushover. A
  `started` record left by interruption, or an ambiguous provider outcome, is
  `result_unknown` and is never submitted again automatically.
- A watch accepts `provider_accepted` only for its pending event and a valid
  canonical response signature.
- Keep watch recognition, durable relay acceptance, provider acceptance,
  device delivery, human acknowledgement, and resolution as distinct facts.
- Never log request bodies, signature/authorization headers, credentials,
  location, or client IP addresses.
- TEST events never contain identity, instructions, contacts, or location.
  Do not add LIVE data until application-layer encryption has landed.
- Add a second platform or transport directly; extract shared code only after
  two real implementations expose the same seam.
- Do not claim a hardware path works without recording a physical test.

## Hard-won gotchas

- `Toybox.Communications.makeWebRequest()` accepts a `Dictionary` body and
  controls its JSON serialization. Raw-body HMACs are therefore not a stable
  cross-runtime contract; the fixed canonical semantic grammar is.

Add further entries only for traps observed in development or hardware
testing, including the symptom and verified workaround.

## References

- [`SECURITY.md`](SECURITY.md) — reporting and security invariants
- [`docs/reliability.md`](docs/reliability.md) — evidence and physical tests
- [`docs/platform-limitations.md`](docs/platform-limitations.md) — Garmin limits
- [`docs/roadmap.md`](docs/roadmap.md) — phase gates

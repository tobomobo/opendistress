# Architecture

```text
foreground Garmin app
    -> direct event JSON + canonical semantic HMAC
reference relay
    -> fixed Pushover request
trusted contact
```

The Garmin app persists an event before transmission, signs the protocol's
canonical semantic text, and passes the event `Dictionary` to Garmin for JSON
serialization. The relay bounds and strictly parses that JSON, reconstructs
the same canonical text, authenticates the device, and checks the TEST-v1
clock and expiry rules.

Before contacting Pushover, the relay commits a minimal SQLite attempt record
keyed by device and event. An accepted result survives restart. An attempt left
in `started` by interruption, or one with an ambiguous provider outcome,
becomes `result_unknown` and is never automatically resubmitted. A definite
provider rejection releases the claim for a deliberate retry. Phase 1 has no
delivery worker or transactional outbox.

The protocol is the only shared platform seam. A future Wear OS app remains
Kotlin and a future watchOS app remains Swift; neither shares UI, lifecycle,
permissions, or networking code with Monkey C.

## Incident model

An incident is an append-only stream of authenticated events:

```text
test.triggered / live.triggered
location.updated
responder.acknowledged
wearer.cancelled
incident.resolved
```

`event_id` identifies one immutable logical event, `incident_id` groups its
updates, and `sequence` orders them. Retransmission keeps all original semantic
values; JSON member order and insignificant whitespace are not identity.
Cancellation and resolution append events rather than rewriting history. The
phase-1 TEST event expires exactly 900 seconds after creation, and every LIVE
incident will also have an explicit expiry.

## Evidence

These states are never collapsed into a `delivered` boolean:

```text
watch recognized
relay durably accepted
provider accepted
recipient device delivered
human acknowledged
incident resolved
```

Phase 1 durably records provider-attempt state for idempotency, but does not
claim durable acceptance for eventual delivery. That evidence state begins
with the transactional-outbox phase. A success response is tied to the pending
event with a canonical response HMAC before the watch displays provider
acceptance.

## Trust boundaries

- The watch holds a per-device HMAC key and, later, separate content keys.
- Phase 1 trusts Garmin Connect, Connect IQ, and Garmin Express app settings
  with the TEST HMAC key and therefore with TEST result evidence. The paired
  network path does not receive future LIVE plaintext, and LIVE requires an
  authentication key provisioned independently of that settings channel.
- The relay authenticates devices and holds provider credentials.
- Pushover is trusted only to report its own acceptance accurately. Phase 1
  uses a dedicated account with exactly one active Android device because
  Pushover device-name targeting can fan out when the name is invalid.

See [`threat-model.md`](threat-model.md) and [`privacy.md`](privacy.md).

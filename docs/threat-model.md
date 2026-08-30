# Threat model

## Assets and boundaries

Phase 1 protects the ability to send notifications, the per-watch HMAC key,
Pushover credentials, event integrity, and honest evidence. It intentionally
contains no identity, instructions, contacts, or location. The watch-to-relay,
relay-to-provider, configuration-file, and operator/logging boundaries are in
scope; physical compromise of an unlocked watch or relay host is not solved by
the protocol.

## Threats and controls

| Threat | Current control | Residual risk |
|---|---|---|
| Forged event | Unique 256-bit HMAC key and constant-time verification | A compromised watch key authorizes that device until disabled |
| Compromised Garmin settings or paired-phone path | Explicitly trusted for non-sensitive phase-1 TEST provisioning only | It can learn/replace the TEST HMAC key and forge TEST events or results; LIVE requires independent authentication-key provisioning |
| Body tampering/canonicalization | HMAC covers every accepted field in one fixed semantic grammar; extra fields are rejected | JSON order and whitespace are intentionally unauthenticated because they have no semantics |
| Replay or duplicate provider send | Timestamp/expiry checks plus a durable device/event attempt ledger | A compromised device key can create fresh events until disabled or rate-limited |
| Event-ID reuse with different semantics | Persist and compare SHA-256 of the canonical request bytes under the unique device/event key | The relay retains this detection only for the 24-hour TEST window |
| Server clock rollback after retention cleanup | Persist a clock high-water mark and fail closed for unseen events after a large rollback | Operators must restore trustworthy time before new TEST events resume |
| Parser/resource abuse | Fixed route, five-second connection timeout, content type, content length, 1 KiB body limit, exact schema | Aggregate connection limits belong at the HTTPS proxy |
| Device enumeration | Unknown, disabled, and bad-signature requests return the same result | Timing differences are reduced, not formally eliminated |
| Credential leakage | Environment/file configuration, ignore rules, redacted logs | Host administrators can read process configuration |
| False success or delivery claim | Event-bound response HMAC and evidence-specific result names | Pushover acceptance still does not prove device delivery |
| Provider ambiguity | Commit `started` before the call; ambiguity is terminal `result_unknown` with no automatic resubmit | At-most-one relay attempt can still be accepted without the relay learning the result |
| Pushover device-name fan-out | Dedicated account with exactly one active Android device | Account configuration drift must be checked operationally |

Before LIVE events, application-layer encryption and separate content keys are
mandatory. The phase-1 ledger prevents duplicate provider attempts; automatic
durable retries remain blocked until the transactional outbox phase.

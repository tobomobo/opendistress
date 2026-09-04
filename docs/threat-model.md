# Threat model

## Assets and boundaries

The system protects notification authority, honest evidence, per-device
authentication keys, v2 content keys, provider credentials, encrypted incident
content, and location. Watch-to-relay,
relay-to-provider, recipient decryption, local configuration, persistence, and
logging boundaries are in scope. A physically compromised unlocked device or
relay host is not solved here.

## Threats and controls

| Threat | Control | Residual risk |
|---|---|---|
| Forged event or intake result | Per-device HMAC-SHA256, domain-separated request/result grammars, constant-time comparison | A stolen key authorizes that device until disabled |
| Forged or stale incident status | Fresh request ID, exact incident/device/expiry binding, checked-time window, separate request/result HMAC domains | A stolen live authentication key can forge a stop result |
| Garmin/paired-phone plaintext access | TEST only through app settings; v2 content encrypted before networking with separate AES and MAC keys | Timing, outer metadata, size, and ciphertext remain visible |
| Body ambiguity or tampering | Strict duplicate-key JSON parser, exact key sets/types, canonical base64url, semantic HMAC, published cross-runtime vectors | Wire whitespace/order is intentionally unauthenticated |
| Replay or ID reuse | Clock/expiry checks, durable `(device_id,event_id)` digest, incident sequence constraint, one active incident per device | A compromised device can mint fresh events; edge rate limits are still operational |
| Lost provider response or relay crash | Committed lease/attempt and fenced recovery | Provider has no idempotency key; a recovered attempt can create a duplicate notification |
| Clock rollback after cleanup | SQLite clock high-water mark checked inside the write transaction | New intake fails closed until trustworthy time catches up or is repaired |
| Parser, slow-client, or response abuse | 1 KiB body, strict media/length/encoding, five-second socket timeout, bounded provider responses, no redirects | Aggregate connection/rate limits belong at the HTTPS edge |
| Device enumeration | Unknown, disabled, missing-key, and bad-signature paths use dummy-key work and one external 401 | Timing is reduced, not formally constant across the entire HTTP stack |
| Secret/config leakage | 0600 device/route/database files, environment provider token, ignored local build config, no secret logging | Host administrators and locally built client binaries can recover provisioned keys |
| Direct-provider URL leakage | Grafana URL is password-type, restricted to HTTPS `*.grafana.net` formatted webhooks, and never logged by app code | Garmin settings sync, the watch, and anyone seeing the full URL gain authority to inject TEST alerts until it is rotated |
| Forged Garmin companion setup/location | Exact versioned keys, canonical SHA-256 setup digest, revision checks, incident/config binding, and post-acceptance time/coordinate validation | Garmin Connect/device transport is trusted for TEST; this channel has no independent E2E authentication and must not provision LIVE keys |
| Provider URL logging | Inbound URLs carry no credentials; relay logging omits outbound requests | Pushover requires its application token in the HTTPS receipt-query URL, so an egress proxy must also suppress URL logs |
| Public fixture used in production | Enabled loaders reject every published role key | Operators can still modify the source and remove the check |
| Ciphertext malleability or oracle | Encrypt-then-MAC; recipient verifies full tag before no-padding AES-CBC decrypt | Compromise of recipient content keys reveals retained envelopes for that key version |
| False delivery claim | Evidence-specific states and signed intake response | Provider acceptance is not device delivery or human acknowledgement |
| False Grafana ACK claim | Watch labels webhook 2xx only as provider acceptance; receiver drill records Important Push and in-app ACK separately | Current watch code cannot query Grafana ACK or escalation state |
| Forged acknowledgement | Per-recipient Pushover emergency receipt tied to its snapshotted route | Pushover can manufacture the evidence it originates; ntfy acknowledgement is unsupported |
| Group/config drift | Recipient membership and a provider destination/credential fingerprint are snapshotted transactionally; mismatched workers cannot claim old work | Provider-account changes behind unchanged credentials can still reroute a recipient |
| Emergency repeats after resolution | Resolution is separate from acknowledgement; durable provider cancellation where supported | Cancellation can itself be ambiguous until provider evidence is recorded |
| Location before consent/trigger | Trigger persisted and submission started before location permission/API; only foreground follow-ups | Firmware/lifecycle behavior still requires physical verification |
| Mailbox operator reads event semantics | Entire v2 event wrapped in fixed-size encrypt-then-MAC capsule with independent keys | Operator still sees IP at ingress, timing, mailbox/message pseudonyms, expiry, fixed size, polls, and ACK timing |
| Mailbox capability abuse | Separate 256-bit append/read/ACK capabilities, hash-only server config, per-mailbox active/hourly quotas | Stolen append capability can send false capsules and consume quota until rotation |
| Forged E2E mailbox ACK | Independent ACK encryption/MAC plus exact capsule hash, incident, sequence, and message binding | A stolen recipient enrollment can forge ACKs; relay can accept opaque garbage that the sender must reject |
| Recipient removal | One mailbox per recipient and capability/key rotation | Old queued capsules remain readable until expiry; companion enrollment and revocation UX are not implemented |

## Explicit production gates

- Garmin Connect IQ compilation/type checking and every hardware matrix row.
- Wear OS/watchOS hosted builds, simulator tests, and device tests.
- Hardware-backed Keystore/Keychain enrollment for distributable native builds;
  current personal builds embed locally supplied keys.
- Physical verification that signed incident-status polling stops each watch's
  foreground location cadence after relay-side resolution.
- HTTPS edge connection/rate limits and log-retention verification.
- Companion enrollment with out-of-band verification, platform keystore storage,
  capability rotation, and recipient removal.
- Garmin mailbox-capsule implementation, compiler/device evidence, and physical
  receiver ACK feedback testing. The Node codec is reference evidence only.

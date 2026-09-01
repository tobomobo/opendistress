# Architecture

```text
Garmin / Wear OS / watchOS
    -> canonical HMAC + immutable event JSON
reference relay
    -> SQLite event + snapshotted recipient-route outbox
    -> Pushover and ntfy workers
trusted recipients
    -> authenticate/decrypt v2 content locally
    -> deliberate acknowledgement

optional blind mailbox path
    -> fixed-size encrypted v2 capsule per recipient mailbox
    -> capability-authenticated poll and encrypted E2E acknowledgement
```

The wire protocol is the shared seam. UI, lifecycle, permissions, persistence,
location, and networking remain native to Monkey C, Kotlin, and Swift.

## Events and intake

V1 carries only `test.triggered` with opaque IDs. V2 carries encrypted
`live.triggered` and `location.updated`; the relay can authenticate the outer
envelope but never receives content keys. Garmin supplies a `Dictionary` and
controls JSON serialization, so every runtime signs the protocol's fixed
semantic grammar rather than raw wire bytes.

Each client persists the complete immutable event before its first network
attempt. In one `BEGIN IMMEDIATE` SQLite transaction, the relay authenticates
the event, enforces ID/expiry/sequence rules, stores the canonical digest and
opaque envelope, snapshots the configured recipient routes and their
domain-separated provider-configuration fingerprints, inserts delivery rows,
and commits. Only then does it return an event-bound signed HTTP 202
`durably_accepted` response.

## Delivery and evidence

Workers claim due rows with committed random leases, call one concrete
transport, and fence completion with the lease token. Transient and ambiguous
outcomes retry with bounded backoff until expiry. This is at-least-once across
an external provider boundary: a crash after provider acceptance can produce a
duplicate notification.

Pushover and ntfy are implemented directly. Pushover emergency receipts record
per-recipient acknowledgement; ntfy supplies provider acceptance but no
acknowledgement or cancellation evidence in this implementation. Group
membership and concrete provider configuration are snapshotted when the trigger
is accepted; location updates inherit that route snapshot. A process whose
destination or credentials no longer match cannot claim its work.
Acknowledgement is append-only coordination evidence, not
incident resolution. Resolution is a separate deliberate state change and
cancels remaining work where a provider supports cancellation.

These facts are never collapsed into `delivered`:

```text
watch recognized
relay durably accepted
provider accepted
transport delivered (when reported)
recipient acknowledged
incident resolved
```

## Location

The trigger is persisted and submission starts before any location request.
Clients append encrypted cached, fresh, and materially changed foreground fixes
under the same incident ID and monotonically increasing sequence. No fix is a
valid result. Acquisition stops at local expiry or after an exact signed
`/v2/status` result reports relay-side resolution or expiry; acknowledgement
alone does not stop it.

The relay-free Garmin beta has a deliberately separate direct-GPS drill. It
starts only after a direct emergency TEST has received and durably stored valid
Pushover acceptance. For up to one foreground hour it uses the real watch
position API and sends a first fix plus materially changed later fixes as
Pushover messages with map URLs. This is not a v1 payload and does not alter the
normative rule that TEST v1 is non-sensitive. It is also not v2: Pushover and
the map provider see the exact coordinate, and there is no relay resolution or
receipt polling. Pending fixes are persisted before submission; provider-call
ambiguity may therefore produce a duplicate retry.

## Trust boundaries

- Each sender has a per-device request HMAC key. V2 additionally uses separate
  encryption and content-MAC keys shared only with trusted recipients.
- Garmin's ordinary settings path is trusted only for non-sensitive TEST.
  The current beta stores a Pushover destination key and application token
  there and sends a fixed emergency-priority TEST directly to Pushover. Those
  values are not LIVE content/authentication keys. LIVE credentials are
  supplied only in a private personal build.
- The relay sees timing, opaque device/incident/route identifiers, event kind,
  sequence, expiry, ciphertext size, and provider metadata, but not v2 content.
- Pushover and ntfy see the notification timing and opaque encrypted envelope.
  They are trusted only for evidence they originate; neither is evidence that a
  person is safe.
- In the explicitly privacy-relaxed direct-GPS drill, Pushover and the map-link
  provider additionally see exact coordinates. This exception is limited to
  the personal beta and is not inherited by LIVE or the normative protocol.
- Public source builds contain unusable placeholder credentials. Personal
  native builds currently embed their locally supplied keys; hardware-backed
  enrollment is a production gate.

The direct Garmin-to-Pushover TEST path intentionally sits beside, not inside,
the normative event protocol. It is a low-setup transport proof: no relay is
required, the emergency TEST itself contains no location or LIVE content, and
a validated Pushover emergency receipt is persisted before the analog
acceptance cover appears or direct GPS begins. Pushover acceptance still does
not collapse transport delivery, recipient acknowledgement, or incident
resolution into one fact.

See [`threat-model.md`](threat-model.md), [`privacy.md`](privacy.md), and the
normative [`../protocol/README.md`](../protocol/README.md).

## Blind mailbox boundary

The optional mailbox path wraps the entire v2 event in another fixed-size,
encrypt-then-MAC capsule. This is separate from the frozen v2 wire endpoint:
existing Garmin/Pushover tests continue unchanged while a future companion or
native receiver can use a relay that cannot distinguish LIVE from location,
recover incident/sequence metadata, or infer a recipient group from configured
routes. Each recipient uses an independent random mailbox.

The relay retains only hashed append/read/ACK capabilities, opaque capsules,
their exact semantic digest, expiry, server acceptance time, and opaque ACK
capsules. It still observes transport metadata such as source IP in memory,
timing, size, mailbox pseudonyms, and polling/ACK activity. It is therefore
content-blind, not anonymous or zero-knowledge.

Recipient ACKs are encrypted with direction-specific keys and bind the exact
capsule hash plus inner incident and sequence. Only that verified E2E evidence
could justify stronger sender feedback. Mailbox persistence or HTTP 202 cannot.

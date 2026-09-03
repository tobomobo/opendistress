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
starts only after a direct TEST has received and durably stored valid Grafana
Cloud IRM or Pushover acceptance. For up to 24 foreground hours it uses the real
watch position API and sends a best-available initial location plus materially changed
later fixes to every provider whose current configuration matches the one-way
fingerprint stored with its trigger acceptance. Its immediate fallback may be a
pre-acceptance last-known fix, but that source and its exact reported age are
prominent; live callbacks still must be post-acceptance. If a Garmin sport is
already recording, the beta can use its current location without controlling
that recording. Changing provider settings pauses rather than retargets GPS. A
changed route or an exhausted per-target retry budget cannot retain the shared
fix slot and starve another still-bound provider.
Grafana updates share the trigger's
alert UID; Pushover uses separate messages with map URLs. This is not a v1
payload and does not alter the normative rule that TEST v1 is non-sensitive.
It is also not v2: each selected direct provider and the map provider see the
exact coordinate, and there is no relay resolution or direct-provider ACK
polling. Pending fixes and remaining provider targets are persisted before
submission; provider-call ambiguity may therefore produce a duplicate retry.

## Trust boundaries

- Each sender has a per-device request HMAC key. V2 additionally uses separate
  encryption and content-MAC keys shared only with trusted recipients.
- Garmin's ordinary settings path is trusted only for TEST configuration.
  The current beta stores a secret Grafana Cloud IRM webhook URL, Pushover
  destination/application keys, or both there and sends a TEST directly to
  those routes. It may also store an optional protected-person display name and
  a prepared alert message plus a provider-neutral emergency card containing
  home/family/person/background, responder-instruction, and photo-link fields. The name is intentionally
  included in provider-visible TEST titles. A shared profile module feeds
  concrete adapters: Grafana receives structured fields while Pushover receives
  prioritized, per-field-clipped profile text and a supplementary photo link.
  Grafana's configured mobile template uses the short title plus the prepared
  message; other profile values remain structured detail fields. Pushover has no
  equivalent detail-template boundary and may expose message text on the lock
  screen. Garmin and each selected provider process their mapped fields. Those
  values are not LIVE content/authentication keys. LIVE
  credentials are supplied only in a private personal build.
- The relay sees timing, opaque device/incident/route identifiers, event kind,
  sequence, expiry, ciphertext size, and provider metadata, but not v2 content.
- Pushover and ntfy see the notification timing and opaque encrypted envelope.
  They are trusted only for evidence they originate; neither is evidence that a
  person is safe.
- In the explicitly privacy-relaxed direct-GPS drill, Grafana and/or Pushover
  plus the map-link provider additionally see exact coordinates. This exception
  is limited to the personal beta and is not inherited by LIVE or the normative
  protocol.
- Public source builds contain unusable placeholder credentials. Personal
  native builds currently embed their locally supplied keys; hardware-backed
  enrollment is a production gate.

The direct Garmin-to-provider TEST path intentionally sits beside, not inside,
the normative event protocol. It is a low-setup transport proof: no relay is
required, its optional provider-neutral emergency card is not a v1 TEST field, the TEST
contains no location or LIVE content, and at least
one validated provider acceptance is persisted before the analog acceptance
cover appears or direct GPS begins. Grafana `2xx` proves webhook ingestion;
Pushover requires its emergency receipt. Neither collapses transport delivery,
recipient acknowledgement, or incident resolution into one fact.

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

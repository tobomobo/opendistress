# Incident protocol

## TEST v1

Version 1 accepts one non-sensitive event kind at `POST /v1/events` with content type
`application/json`. The body is at most 1024 bytes and must match
[`alert-v1.schema.json`](alert-v1.schema.json). For v1,
`incident_id == event_id`, `sequence == 0`, `payload == null`, and
`expires_at == created_at + 900`.

The Garmin application passes the event as a `Dictionary` to
`Toybox.Communications.makeWebRequest()`, which serializes it directly to JSON.
Every ID is the canonical, unpadded base64url encoding of exactly 16 bytes and
is therefore exactly 22 characters. Validation strictly decodes and re-encodes
IDs to reject non-canonical padding bits. Parsers reject duplicate members,
unknown members, coercion, and trailing JSON. Every numeric field must use an
integer JSON token; decimal-point and exponent forms such as `1.0` and `1e0`
are rejected even when mathematically integral. Parsed timestamps are
non-negative, and v1 caps `created_at` at `2147482747` so its 900-second expiry
fits Garmin's signed 32-bit `Number`.

### Authentication

Each device is provisioned with a unique 32-byte key encoded as exactly 64
lowercase hexadecimal characters. Send exactly one signature header containing
the full HMAC-SHA256 digest as unpadded base64url:

```text
X-SPB-Signature: v1=<43-character unpadded-base64url digest>
```

Signature values are strictly decoded to 32 bytes and re-encoded identically;
padding and alternative encodings are rejected.

The signed request bytes are exactly the UTF-8/ASCII bytes below, with LF
(`0a`) after every line, including the last. Labels and order are literal;
angle-bracketed values come from the strictly validated event:

```text
spb.test.submit.v1
method=POST
v=1
event_id=<event_id>
incident_id=<incident_id>
device_id=<device_id>
kind=test.triggered
sequence=0
created_at=<created_at>
expires_at=<expires_at>
payload=null
```

The raw JSON bytes are not signed. JSON member order and insignificant
whitespace may change without changing the signature because neither affects
the accepted event semantics. A relay parses once, rejects anything outside
the exact schema, constructs the text above, and compares the HMAC in constant
time.

With the default 300-second skew, an unseen event requires
`created_at <= server_now + 300` and `server_now < expires_at`. The expiry is
exclusive: the relay never signs durable acceptance for a new event that its
worker would already refuse to deliver. An authenticated, already-recorded
duplicate is resolved from the ledger before those time checks, allowing a
delayed retry to read its existing state without another provider call.

[`fixtures/signature-v1.txt`](fixtures/signature-v1.txt) publishes the public
test key, canonical request/response hex, SHA-256 digests, raw HMAC digests, and
encoded signatures. [`fixtures/test-ping-v1.json`](fixtures/test-ping-v1.json)
and [`fixtures/test-ping-v1-reordered.json`](fixtures/test-ping-v1-reordered.json)
encode the same event in different wire orders and must verify with the same
request signature.

### Durable intake and delivery

The relay commits the event and one delivery row per snapshotted recipient route
in the same SQLite transaction before responding. The unique
`(device_id, event_id)` key stores SHA-256 of the canonical request; an exact
duplicate returns the existing durable result, while the same ID with different
semantics conflicts.

A worker claims due delivery rows with a committed random lease token before
network I/O. Transient or ambiguous outcomes are retried with bounded backoff
until event expiry. Lease recovery is fenced so a stale worker cannot overwrite
a newer attempt. This is deliberately at-least-once: if a provider accepted a
request but the relay lost the response or crashed, a retry may create a
duplicate notification. Provider references, receipt evidence, and classified
attempts are committed separately from intake evidence.

### Durable-acceptance evidence

Successful intake is HTTP 202 with exactly:

```json
{"v":1,"event_id":"AAECAwQFBgcICQoLDA0ODw","result":"durably_accepted","response_signature":"v1=6eCuAfV44rtvISQNtNPfUXpt50fm_U5sUj4POwx42UM"}
```

`response_signature` is HMAC-SHA256 with the same device key over these exact
UTF-8/ASCII bytes, again with a final LF:

```text
spb.test.intake-result.v1
v=1
event_id=<event_id>
result=durably_accepted
```

Its JSON value is exactly `v1=<43-character unpadded-base64url digest>` and is
strictly decoded to 32 bytes and re-encoded identically, just like the request
signature.

The response uses a different domain label so a request signature cannot be
reused as intake evidence. The watch accepts it only for HTTP 202, the pending
event ID, the exact response fields, and a valid response signature.
`durably_accepted` means only that relay persistence committed. It says nothing
about provider acceptance, transport delivery, human acknowledgment, or
incident resolution.

Failures return JSON with `v`, a `result` of `retryable_failure`,
`configuration_failure`, or `result_unknown`, and a stable non-secret `code`.
They include `event_id` only after it has been safely parsed. HTTP status is
transport metadata; clients present the `result` and never infer success from
status alone.

Unknown/disabled devices and invalid signatures deliberately share the same
`401 authentication_failed` response.

### TEST delivery

The fixed message remains `TEST ONLY — Garmin alert transport check. No
emergency action required.` Recipient groups and routes are relay configuration,
never event fields. The normal quick start uses one Pushover recipient. A
separate explicit `test_emergency` route setting enables Pushover emergency
priority for acknowledgment testing without changing TEST content into LIVE.

Pushover acceptance requires an HTTPS response with HTTP status 200 and a
bounded JSON object containing integer `status` equal to `1` plus a non-empty
string `request` identifier of at most 128 characters. A documented Pushover
4xx response or JSON `status: 0` is a definite rejection. A timeout, reset,
redirect, 5xx, oversized/malformed response, or `status: 1` without a valid
request identifier is ambiguous because acceptance may have occurred. Emergency
receipt polling records each recipient acknowledgment independently; one
acknowledgment does not resolve the incident or erase later evidence.

## Encrypted incidents v2

Version 2 accepts encrypted `live.triggered` and `location.updated` events at
`POST /v2/events` with content type `application/json`. The request must match
[`incident-v2.schema.json`](incident-v2.schema.json) and contain exactly these
nine outer members:

```json
{
  "v": 2,
  "event_id": "AAECAwQFBgcICQoLDA0ODw",
  "incident_id": "AAECAwQFBgcICQoLDA0ODw",
  "device_id": "EBESExQVFhcYGRobHB0eHw",
  "kind": "live.triggered",
  "sequence": 0,
  "created_at": 1788105600,
  "expires_at": 1788109200,
  "payload": {
    "key_version": 1,
    "iv": "YGFiY2RlZmdoaWprbG1ubw",
    "ciphertext": "8eRa_JOzxdPOO3l494xv5Q",
    "tag": "QOA-t_kexwtJrWsQaj8FZuEb9TdOhPAcHCDMbgkrCB8"
  }
}
```

`payload` contains exactly the four shown members. Every ID, IV, and
ciphertext is the canonical unpadded base64url encoding of exactly 16 bytes
and is therefore 22 characters. A tag is the canonical unpadded base64url
encoding of the full 32-byte HMAC-SHA256 digest and is therefore 43
characters. Strict validation decodes and re-encodes every value identically,
rejecting padding and non-canonical padding bits.

Parsers reject duplicate or unknown members, coercion, trailing JSON, and any
numeric token containing a decimal point or exponent. Every JSON number is a
non-negative signed 32-bit integer token, so its maximum is `2147483647`;
`key_version` starts at 1. Validation also requires
`created_at <= expires_at`. For `live.triggered`, `incident_id == event_id`
and `sequence == 0`. A `location.updated` event uses the persisted incident ID
and expiry, a fresh event ID, and a sequence from 1 through `2147483647`.
The relay also requires its `created_at` to be no earlier than the accepted
`live.triggered` event's `created_at`.
The signed lifetime must also satisfy
`1 <= expires_at - created_at <= 86400`; this bounds an authenticated device's
ability to pin active state and retries. These token and cross-member rules
remain mandatory even where JSON Schema cannot express them.

An unseen v2 event uses the same admission boundary as v1: its creation time
may be at most the configured skew in the future, and relay time must remain
strictly earlier than `expires_at`. Exact recorded duplicates are still
resolved from the ledger after expiry.

An event is immutable. A retry reuses the same event values, ID, timestamps,
IV, ciphertext, tag, and request signature. A cancellation or later location
sample is a new event.

### Content encryption

Each device has three independent 32-byte keys. `K_auth` is provisioned only
to that device and the relay. `K_enc[key_version]` and
`K_mac[key_version]` are provisioned only to the device and its trusted
recipient group; the relay never receives content keys.

`key_version` selects only the content-encryption and content-MAC pair. V2 has
no authentication-key identifier, so `K_auth` must not rotate until the
device's immutable queue is empty and its active incident is expired or
resolved. Content-key rotation retains older recipient keys for the maximum
incident lifetime so queued envelopes remain decryptable.

For a new event, generate a fresh 16-byte IV and encrypt exactly one 16-byte
plaintext block with AES-256-CBC under `K_enc[key_version]`, with no padding.
The ciphertext is consequently exactly 16 bytes. Compute the full
HMAC-SHA256 tag under `K_mac[key_version]` over the following exact
UTF-8/ASCII bytes, with LF (`0a`) after every line, including the last:

```text
spb.content.v2
v=2
event_id=<event_id>
incident_id=<incident_id>
device_id=<device_id>
kind=<live.triggered|location.updated>
sequence=<sequence>
created_at=<created_at>
expires_at=<expires_at>
payload.key_version=<key_version>
payload.iv=<iv>
payload.ciphertext=<ciphertext>
```

This is encrypt-then-MAC. A recipient looks up the requested key version,
reconstructs the content text, and compares the tag in constant time before
decrypting. It never decrypts unauthenticated ciphertext.

The `live.triggered` plaintext block is exactly a random 16-byte
`template_id`. Its meaning is resolved only by the trusted recipient group;
identity, contacts, and instructions are not outer event fields.

A location plaintext block has this fixed binary layout:

| Bytes | Type | Meaning |
| --- | --- | --- |
| 0 | `uint8` | record version `0x01` |
| 1 | `uint8` | record type `0x02` |
| 2-5 | `uint32` big-endian | capture epoch seconds |
| 6-9 | `int32` big-endian | latitude times 10^7, truncated toward zero |
| 10-13 | `int32` big-endian | longitude times 10^7, truncated toward zero |
| 14 | `uint8` | quality: unavailable 0, last-known 1, poor 2, usable 3, good 4 |
| 15 | `uint8` | path: cached snapshot 0, position callback 1 |

For unavailable location, capture time, latitude, and longitude are all zero;
recipients must not render this as coordinates `(0, 0)`. The path byte remains
meaningful. Location age is derived as `created_at - capture_at`; a capture
time later than event creation is a clock inconsistency, not a negative age.

### Request authentication

Send exactly one header containing the full HMAC-SHA256 digest under
`K_auth`, encoded as canonical unpadded base64url:

```text
X-SPB-Signature: v2=<43-character unpadded-base64url digest>
```

The authenticated bytes are exactly the following UTF-8/ASCII text, again
with a final LF:

```text
spb.submit.v2
method=POST
v=2
event_id=<event_id>
incident_id=<incident_id>
device_id=<device_id>
kind=<live.triggered|location.updated>
sequence=<sequence>
created_at=<created_at>
expires_at=<expires_at>
payload.key_version=<key_version>
payload.iv=<iv>
payload.ciphertext=<ciphertext>
payload.tag=<tag>
```

As in v1, sign the validated semantic text, not raw JSON. JSON member order
and insignificant whitespace do not affect the signature.

### Durable-acceptance evidence

After durably recording an authenticated event, the relay returns HTTP 202
with exactly:

```json
{"v":2,"event_id":"AAECAwQFBgcICQoLDA0ODw","result":"durably_accepted","response_signature":"v2=Z40vnSWhJ7rbDRz6kO8nAh8-Qen5RGpl20xiiQ6kCpI"}
```

`response_signature` is the full HMAC-SHA256 under `K_auth` over these exact
UTF-8/ASCII bytes, including the final LF:

```text
spb.result.v2
v=2
event_id=<event_id>
result=durably_accepted
```

A client accepts this evidence only for its pending event ID and a valid
canonical signature. `durably_accepted` means relay persistence only; it does
not assert provider acceptance, device delivery, human acknowledgement, or
incident resolution.

[`fixtures/live-trigger-v2.json`](fixtures/live-trigger-v2.json) and
[`fixtures/location-updated-v2.json`](fixtures/location-updated-v2.json) are
wire examples. [`fixtures/encryption-v2.txt`](fixtures/encryption-v2.txt)
publishes the corresponding deterministic encryption, content-tag, request,
and result vectors. Every key and identifier in those fixtures is public test
material and must never be provisioned in a real device.

## Incident status v2

An activated foreground client queries `POST /v2/status` at its existing
location cadence so relay-side resolution can stop location acquisition even
when no materially changed fix is submitted. The content type is
`application/json`, the body is at most 1024 bytes, and it must match
[`status-v2.schema.json`](status-v2.schema.json) with exactly these six members:

```json
{"v":2,"request_id":"ICEiIyQlJicoKSorLC0uLw","incident_id":"AAECAwQFBgcICQoLDA0ODw","device_id":"EBESExQVFhcYGRobHB0eHw","created_at":1788105700,"expires_at":1788109200}
```

Every ID is a canonical 16-byte base64url value. Both timestamps are
non-negative signed 32-bit integer tokens, `created_at < expires_at`, and
`expires_at` must exactly match the stored incident. `request_id` is freshly
random for each query and is never reused as an event ID. The relay applies the
same strict JSON and canonical-encoding rules as event intake and accepts only
when `created_at <= server_now + 300` and `server_now <= created_at + 300`,
inclusively.

Authenticate with `K_auth` and exactly one `X-SPB-Signature` header. The signed
UTF-8/ASCII text is below, with LF after every line including the last:

```text
spb.status.query.v2
method=POST
v=2
request_id=<request_id>
incident_id=<incident_id>
device_id=<device_id>
created_at=<created_at>
expires_at=<expires_at>
```

An authenticated query for an incident owned by that device returns HTTP 200
with exactly these seven members:

```json
{"v":2,"request_id":"ICEiIyQlJicoKSorLC0uLw","incident_id":"AAECAwQFBgcICQoLDA0ODw","device_id":"EBESExQVFhcYGRobHB0eHw","state":"resolved","checked_at":1788105701,"response_signature":"v2=1PKgg7-Pz7Ko7_jtlrQaJoWxOLwI16D6FGCt4YnnzIM"}
```

`state` is exactly `active`, `acknowledged`, `resolved`, or `expired`.
Resolution takes precedence, followed by expiry, acknowledgement, and active.
Thus acknowledgement remains evidence and never becomes resolution.
`checked_at` is the relay's non-negative signed 32-bit epoch time. The response
signature is HMAC-SHA256 under `K_auth` over:

```text
spb.status.result.v2
v=2
request_id=<request_id>
incident_id=<incident_id>
device_id=<device_id>
state=<active|acknowledged|resolved|expired>
checked_at=<checked_at>
```

A client accepts status only for HTTP 200, the exact outstanding request ID,
incident, device, key, and response fields. It also requires
`query.created_at - 300 <= checked_at <= client_receive_time + 300` and discards
the response after `client_receive_time > query.created_at + 300`. Only a
verified `resolved` or `expired` response stops acquisition; failure, timeout,
`active`, or `acknowledged` does not. Local expiry remains an independent stop.
A verified terminal response also permits the client to remove pending v2
retransmission entries for that same incident as terminally unroutable. It must
preserve other incidents and must not describe any such entry as durably
accepted unless its own signed 202 was received.

The relay must also reject and not route a location update once the incident is
resolved or expired, closing the race between a status response and the next
event transaction. A signed rejection may reduce stop latency but cannot replace
the query because unchanged locations intentionally produce no event.

The query is read-only, so the relay does not persist request IDs. Replaying an
authenticated request within the clock window can only repeat a status read;
the fresh request ID prevents a response from satisfying a later query. Edge
rate limits bound replay load. Status exposes no content beyond incident state
and metadata already held by the relay, and request IDs must not be logged.

[`fixtures/status-query-v2.json`](fixtures/status-query-v2.json) and
[`fixtures/status-v2.txt`](fixtures/status-v2.txt) publish the public query and
request/result HMAC vectors.

## Content-blind mailbox transport v1

Mailbox transport v1 is a separate privacy transport around an immutable v2
event. It does not replace or reinterpret encrypted incident v2. The mailbox
relay never parses the inner event and therefore does not learn its device,
incident, kind, sequence, or creation time. It still sees a random mailbox ID,
a random message ID, expiry, fixed ciphertext size, request timing, source
network metadata, and acknowledgement timing.

Each one-way sender-to-recipient mailbox has three independent random 32-byte
capabilities: append, read, and acknowledge. Send them only in exactly one
`Authorization: Bearer <43-character canonical base64url>` header over HTTPS.
The relay stores only SHA-256 of each capability. Use one mailbox per recipient
so the relay does not receive a recipient-group graph. Capabilities authorize
mailbox operations; they are not content keys.

### Message capsule

`POST /mailbox/v1/<mailbox_id>/messages` accepts at most 2048 bytes matching
[`mailbox-message-v1.schema.json`](mailbox-message-v1.schema.json). IDs and
binary members are strictly decoded and re-encoded. Parsers reject duplicates,
unknown members, coercion, non-integer number tokens, and trailing content.

Serialize the strictly validated v2 event in its fixed normative member order.
Create an exact 512-byte plaintext containing ASCII `SPBM` at bytes 0-3, an
unsigned big-endian inner JSON byte length at bytes 4-5, the UTF-8 v2 event from
byte 6, and cryptographically random padding through the end. Encrypt the whole
block with AES-256-CBC and no padding under the mailbox send-encryption key and
a fresh random 16-byte IV. Authenticate with a distinct send-MAC key over this
exact text, including the final LF:

```text
spb.mailbox.content.v1
v=1
mailbox_id=<mailbox_id>
message_id=<message_id>
expires_at=<expires_at>
payload.iv=<iv>
payload.ciphertext=<ciphertext>
```

The full HMAC-SHA256 is the canonical base64url `payload.tag`. The outer expiry
must equal the encrypted inner event expiry. A recipient verifies the tag in
constant time before decrypting, validates the packing and inner v2 event, and
checks that expiry equality. Fixed ciphertext size hides LIVE versus location
and inner event size; it does not hide traffic timing or outer expiry.

The capsule digest is SHA-256 over the following text, including its final LF:

```text
spb.mailbox.message.v1
v=1
mailbox_id=<mailbox_id>
message_id=<message_id>
expires_at=<expires_at>
payload.iv=<iv>
payload.ciphertext=<ciphertext>
payload.tag=<tag>
```

Persist the complete capsule before submission. Every retry keeps the same ID,
expiry, IV, ciphertext, tag, and capability. An exact retry returns the prior
result; the same message ID with another digest conflicts. A mailbox accepts at
most 32 simultaneously active capsules and 64 new capsules per hour.
Proof-of-work is deliberately absent from the emergency path.

A successful append is HTTP 202 with `v`, `message_id`,
`result: durably_accepted`, and `response_mac`. The response MAC is HMAC-SHA256
under the append capability over:

```text
spb.mailbox.result.v1
v=1
message_id=<message_id>
result=durably_accepted
```

This proves relay persistence only. It is not recipient delivery or
acknowledgement.

### Recipient acknowledgement

The read capability lists active, not-yet-acknowledged capsules with
`GET /mailbox/v1/<mailbox_id>/messages`. After authenticating, opening, and
deduplicating the inner event by `(incident_id, sequence)`, the recipient may
submit [`mailbox-ack-v1.schema.json`](mailbox-ack-v1.schema.json) to
`POST /mailbox/v1/<mailbox_id>/acknowledgements` with the ACK capability.

The encrypted inner acknowledgement contains exactly `v`, `incident_id`,
`sequence`, `message_id`, `capsule_sha256`, and `acknowledged_at`. Pack it into
an exact 256-byte block using ASCII `SPBA`, the same two-byte length, and random
padding. Encrypt with the independent ACK-encryption key. Its outer HMAC uses
the independent ACK-MAC key over:

```text
spb.mailbox.ack-content.v1
v=1
message_id=<message_id>
capsule_sha256=<capsule_sha256>
payload.iv=<iv>
payload.ciphertext=<ciphertext>
```

The relay checks that the clear capsule digest names its exact stored capsule,
but cannot validate or read the encrypted acknowledgement. The sender lists
ACKs with its append capability, verifies and decrypts them, and accepts one
only when every inner incident, sequence, message, and digest binding matches.
Relay ACK storage is append-only and never means incident resolution. Messages
and ACKs are removed after expiry plus 24 hours.

[`fixtures/mailbox-message-v1.json`](fixtures/mailbox-message-v1.json) is the
deterministic Node reference capsule. Python and Node tests require its semantic
SHA-256 to remain
`bae4682120b8ed891c0fc7e3a5aeab673ac171a6f8c6015c4d0d86942b6d5f15`.

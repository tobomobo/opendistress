# Incident protocol v1

Phase 1 accepts one event kind at `POST /v1/events` with content type
`application/json`. The body is at most 1024 bytes and must match
[`alert-v1.schema.json`](alert-v1.schema.json). For phase 1,
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

## Authentication

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

With the default 300-second skew, an unseen event requires both
`created_at <= server_now + 300` and
`server_now <= expires_at + 300`, inclusively. An authenticated,
already-recorded duplicate is resolved from the ledger before those time
checks, allowing a delayed retry to read its existing state without another
provider call.

[`fixtures/signature-v1.txt`](fixtures/signature-v1.txt) publishes the public
test key, canonical request/response hex, SHA-256 digests, raw HMAC digests, and
encoded signatures. [`fixtures/test-ping-v1.json`](fixtures/test-ping-v1.json)
and [`fixtures/test-ping-v1-reordered.json`](fixtures/test-ping-v1-reordered.json)
encode the same event in different wire orders and must verify with the same
request signature.

## Phase-1 attempt ledger

Before calling Pushover, the relay commits a SQLite row keyed by
`(device_id, event_id)` containing SHA-256 of the canonical request bytes and
`started` state. It commits `accepted` and the required provider request
identifier before returning success. Exact duplicates with retained rows return
the stored state, including after a restart; a different canonical SHA-256
digest conflicts.
A `started` row left by interruption and every ambiguous provider result become
terminal `result_unknown` and are never automatically submitted again. A
definite provider rejection removes the claim, so a deliberate retry may use
the same immutable event while it remains unexpired. No retry is automatic,
and a retrying outbox is deliberately not part of phase 1.

## Response evidence

A provider acceptance is:

```json
{"v":1,"event_id":"AAECAwQFBgcICQoLDA0ODw","result":"provider_accepted","provider":"pushover","response_signature":"v1=K26Mm9HN9QqOm2BixauMET2vDwdSzIdLBE1ha9EAaEo"}
```

`response_signature` is HMAC-SHA256 with the same device key over these exact
UTF-8/ASCII bytes, again with a final LF:

```text
spb.test.result.v1
v=1
event_id=<event_id>
result=provider_accepted
provider=pushover
```

Its JSON value is exactly `v1=<43-character unpadded-base64url digest>` and is
strictly decoded to 32 bytes and re-encoded identically, just like the request
signature.

The response uses a different domain label so a request signature cannot be
reused as success evidence. The watch accepts success only for HTTP 200, the
pending event ID, the exact response fields, and a valid response signature.

Failures return JSON with `v`, a `result` of `retryable_failure`,
`configuration_failure`, or `result_unknown`, and a stable non-secret `code`.
They include `event_id` only after it has been safely parsed. HTTP status is
transport metadata; clients present the `result` and never infer success from
status alone.

Unknown/disabled devices and invalid signatures deliberately share the same
`401 authentication_failed` response. No response asserts recipient delivery
or acknowledgement.

## Fixed Pushover route

Phase 1 uses a dedicated Pushover account with exactly one active Android
device. A device-name parameter is not a fail-closed targeting mechanism:
Pushover may fan out to every active account device when the name is absent,
stale, or invalid. The provider URL, account, recipient, title, and message are
relay configuration and never event fields.

Provider acceptance requires an HTTPS response with HTTP status 200 and a
bounded JSON object containing integer `status` equal to `1` plus a non-empty
string `request` identifier of at most 128 characters. A documented Pushover
4xx response or JSON `status: 0` is a definite rejection. A timeout, reset,
redirect, 5xx, oversized/malformed response, or `status: 1` without a valid
request identifier is `result_unknown` because acceptance may have occurred.

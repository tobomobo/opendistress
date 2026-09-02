# Reliability and evidence

A client records an event as durably accepted only after HTTP 202, an exact
matching event ID and result, and a valid response HMAC. Timeouts, malformed
bodies, redirects, signature failures, stale callbacks, and exceptions remain
pending or unknown; none becomes success.

`durably_accepted` proves the relay transaction committed. Delivery then has
independent per-recipient states:

```text
pending -> attempting -> provider_accepted
                    \-> retry_wait -> ...
                    \-> configuration_failure | result_unknown | expired
provider_accepted -> transport evidence -> human_acknowledged
incident: active -> acknowledged (evidence only) -> resolved | expired
```

## Retry boundary

An immutable retry retains every semantic value, including ID, timestamps,
ciphertext, content tag, and request signature. Exact duplicates return the
stored intake result. Reusing an ID for different semantics is a conflict.

The blind-mailbox path applies the same rule to the complete fixed-size capsule:
message ID, outer expiry, IV, ciphertext, content tag, and append capability are
persisted before the first request and reused unchanged. The relay's response
MAC proves only mailbox persistence. A stronger sender signal requires an ACK
that the sender has decrypted and verified against the exact capsule hash and
inner `(incident_id, sequence)`; merely storing an opaque ACK at the relay is
insufficient.

The relay commits a lease and attempt row before provider I/O. A transient
failure retries with bounded exponential backoff. If a lease expires after an
interruption, a new worker may retry while the incident remains active. That
preserves eventual delivery but cannot provide exactly once: the provider may
have accepted the interrupted attempt. Attempt rows preserve that ambiguity and
stale workers cannot overwrite the newer claim.

Resolution stops pending work but never rewrites a possibly accepted delivery
as resolved. Such a row remains `result_unknown`; an ambiguous Pushover
emergency with no receipt also retains unknown cancellation state because the
relay has no provider handle with which to stop repeats. ntfy cancellation
remains explicitly unsupported.

Recipient membership and the concrete provider destination/credentials are
snapshotted as a one-way fingerprint at trigger intake. Changing a route does
not redirect accepted work: mismatched workers skip it, and the original
configuration must drain or the incident must be resolved/expire.

Pushover acceptance requires HTTP 200, numeric JSON `status: 1`, and a bounded
provider request ID. Every Pushover 4xx or valid `status: 0` is a definite
configuration rejection. Network errors, 5xx, malformed success, and a missing
request ID are ambiguous. Emergency receipts are polled no faster than the
provider permits and each recipient's acknowledgement is retained. ntfy uses a
stable sequence ID to reduce duplicate visible notifications, but relay retries
remain at-least-once.

## Client recovery

The watch queue is bounded and persisted before networking. Automatic retries
are bounded; a manual retry reuses the queue head. An unexpired LIVE event cannot
be abandoned. After signed expiry, the user may explicitly archive it as
`result unknown` and start a new incident; expiry is never silently treated as
failure or success. Changing authentication keys while an immutable queue or
active incident exists is unsupported.

While an incident is active in the foreground, the client uses the existing
location cadence to issue a signed, read-only status query. Only a strictly
verified matching `resolved` or `expired` response stops acquisition;
`acknowledged`, timeouts, malformed data, and invalid signatures do not.
That terminal evidence may archive same-incident v2 retransmission entries as
unroutable, but never relabels them as accepted and never removes unrelated
pending work.

## Physical acceptance record

All physical rows live in [`../tests/end-to-end/physical-matrix.csv`](../tests/end-to-end/physical-matrix.csv).
They are currently `NOT_RUN` unless that file says otherwise. Required evidence
includes:

- one physical Garmin-to-phone-to-relay-to-provider TEST with matching ID;
- 100 recorded failure-matrix attempts;
- 200 deliberate activations per Garmin route plus seven days of false-trigger
  wear;
- encrypted LIVE/decryption, group acknowledgement, location, phone-off Wi-Fi,
  ntfy, Wear OS, and watchOS rows.

Re-run affected rows after Garmin SDK, firmware, radio, provider, or networking
changes. Host tests and simulators cannot convert a physical row to PASS.

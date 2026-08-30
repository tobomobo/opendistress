# Reliability and evidence

The watch displays exactly one of four phase-1 outcomes:

| Result | Meaning | Safe next action |
|---|---|---|
| `provider_accepted` | Pushover accepted the submission | Do not call it delivered or acknowledged |
| `retryable_failure` | The relay proved no notification was accepted | Retry the same semantic event and ID while unexpired |
| `configuration_failure` | Local, authentication, schema, or provider configuration is invalid | Fix configuration, then retry the same event while unexpired |
| `result_unknown` | A provider attempt may have occurred but its outcome is ambiguous | Show ambiguity; repeating the event only reads this terminal state |

No response, timeout, parse error, stale callback, mismatched event ID, invalid
response signature, or exception becomes success. `provider_accepted` means
Pushover received and queued the request; it does not mean device delivery.

## Phase-1 retry boundary

The TEST event uses canonical 22-character base64url IDs and
`expires_at == created_at + 900`. Retries retain every semantic value. The
relay commits `started` before its one Pushover call and `accepted` before its
success response. Accepted duplicates return the stored result after restart;
started or ambiguous duplicates return `result_unknown` and never invoke the
provider again. When the provider definitively rejects a request, the relay
removes the attempt claim; a deliberate retry may use the same immutable event
through its signed expiry. MENU can explicitly abandon a pending TEST to create
a new ID; this never reclassifies the old outcome. No retry is automatic. A full transactional outbox
waits for phase 2.

The fixed recipient is a dedicated Pushover account with exactly one active
Android device. Device-name targeting alone is not accepted as evidence of a
single-device route because Pushover can fan out when that name becomes stale.

## Physical acceptance record

First record one successful physical fēnix 8-to-phone-to-relay-to-Pushover
notification with matching event IDs. Then record 100 deliberate attempts in
`tests/end-to-end/results/` using a CSV with timestamp, conditions, watch
result, provider observation, latency, and notes.

The attempts must cover Android locked/screen off, Garmin Connect backgrounded,
watch Wi-Fi disabled, phone and watch reboots, temporary Bluetooth and mobile
data loss, relay timeout, and provider error. Do not summarize an unrecorded
attempt as passing.

Activation-route experiments require 200 deliberate activations per route and
seven days of normal wear for false triggers. Re-run relevant physical tests
after Garmin SDK, firmware, or networking changes.

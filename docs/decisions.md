# Decisions

## 2026-08-30 — Share a protocol, not application code

Garmin remains Monkey C. Future Wear OS and watchOS clients remain native. The
versioned incident envelope, fixtures, and state semantics are the shared seam.

## 2026-08-30 — Prove one non-sensitive synchronous path first

Phase 1 sends only `test.triggered` through one relay endpoint to one fixed
Pushover recipient. It includes only the SQLite attempt ledger required to
make replay and restart behavior honest; the retrying outbox, acknowledgement,
location, more transports, and platform abstractions wait for later gates.

## 2026-08-30 — Sign canonical event semantics

Each watch uses its own 256-bit HMAC-SHA256 key. Signing the exact transmitted
JSON is not a stable contract because Garmin controls serialization of the
`Dictionary`. Instead, both runtimes build one fixed, line-oriented ASCII
signing input from strictly validated fields. The public golden vector catches
encoding drift, while a reordered JSON fixture proves wire order is irrelevant.

## 2026-08-30 — Make phase-1 ambiguity terminal

The relay commits `started` before its single Pushover attempt and commits
`accepted` before returning success. A crash, timeout, or ambiguous provider
outcome remains `result_unknown`; the relay never automatically resubmits it.
This is smaller and more honest than pretending the provider boundary supports
exactly-once delivery.

## 2026-08-30 — Require one-device Pushover isolation

Pushover can deliver to every active account device when a requested device
name is absent or stale. The first milestone therefore uses a dedicated
Pushover account with exactly one active Android device.

## 2026-08-30 — Block sensitive data until application encryption

TLS protects network hops but does not make Garmin Connect a trusted plaintext
processor. LIVE identity, instructions, and location wait for encryption on
the watch.

## 2026-08-30 — Use each platform's memory-safe supported language

Connect IQ only runs Monkey C applications, so Rust cannot replace the Garmin
client. Garmin release builds must pass the compiler's strictest type-check
level and use only Garmin cryptographic primitives. SDK 9.2.0 now compiles the
source at `-l 1` and passes its protocol simulator test, but it has not passed
the strict `-l 3` gate. Wear OS uses Kotlin, watchOS uses Swift, and the relay
and recipient tools use Python and JavaScript. A Rust relay would be viable,
but would not remove Monkey C from the watch and is not justified by the
current small, standard-library implementation.

Swift's standard CryptoKit does not expose the AES-CBC profile required for
Garmin interoperability, so watchOS uses one narrowly bounded CommonCrypto call
over validated 16-byte blocks and 32-byte keys. Hosted compilation and sanitizer
review remain gates for that native boundary; replacing it with handwritten
crypto would be less safe.

## 2026-08-30 — Encrypt fixed records with Garmin-supported primitives

Connect IQ exposes AES-CBC and HMAC-SHA256 but no authenticated-encryption
mode. Version 2 therefore encrypts one fixed 16-byte block with AES-256-CBC and
a fresh authenticated IV, then authenticates the complete semantic envelope
with a separate HMAC key. Content and device-authentication keys are distinct.

## 2026-08-30 — Prefer durable intake over synchronous provider evidence

The relay now commits the event and its delivery rows before returning a signed
`202 durably_accepted`. Workers reclaim expired leases and retry transient or
ambiguous outcomes until incident expiry. This is deliberately at-least-once:
a provider may accept twice if the relay loses the result after submission.

## 2026-08-30 — Implement ntfy as the second transport

Direct Android SMS needs a provisioned companion app, runtime permission,
phone/SIM hardware, and carrier testing that are absent here. A direct HTTPS
ntfy implementation provides a real second delivery path
without pretending SMS was validated. The only shared transport shape is the
one demonstrated by Pushover and ntfy.

## 2026-08-30 — Do not invent the conditional launcher face

The Garmin app-list, glance, and published complication surfaces are present.
The separate launcher face remains absent because the plan requires physical
measurements to show those stock surfaces are inadequate first.

## 2026-08-31 — Require a covert hold inside the LIVE app

A fully provisioned personal Garmin build is LIVE-only and renders a neutral
analog cover immediately, but foreground launch alone never triggers. The top
hardware key (`START`/`ENTER`) must remain pressed for 1.5 seconds; releasing
sooner cancels, DOWN is inert, and no visible countdown is shown. At the threshold the encrypted event is
persisted before a short best-effort haptic and immediate submission. This
reuses the existing retry timer because fēnix permits only three concurrent
timers by default.

The public/unprovisioned build remains TEST-only. Connect IQ still cannot
register a global arrow-button listener, so launcher availability remains a
firmware/device fact. The analog cover and haptic are evidence only of local
UI and persistence behavior, never relay acceptance, provider delivery,
acknowledgement, or resolution.

## 2026-09-02 — Unify TEST and LIVE on an exact hardware hold

The earlier 1.5-second LIVE-only hold and immediate TEST press are superseded.
Every foreground alert now requires the top hardware key (`START`/`ENTER`) to
remain pressed for 2.5 seconds; releasing sooner creates no event. There is no
numeric countdown. Instead, an elapsed-time ring starts at six o'clock, grows
symmetrically in both directions, and closes at the exact trigger threshold.
Releasing early removes it immediately. The analog acceptance cover still
appears only after a direct provider accepts the TEST.

Touchscreen tap and hold behaviors are consumed without triggering. Connect IQ
reports a touch `onHold()` only after firmware decides that a hold occurred and
does not expose the initial touch-down timestamp needed to enforce the same
exact 2.5-second threshold. The tactile hardware path therefore remains the
blind-operable and testable primary gesture instead of introducing a faster,
device-dependent touchscreen trigger.

The progress redraw reuses the existing retry timer rather than consuming a
fourth timer slot. Frames are visual only: `System.getTimer()` elapsed time is
the trigger authority, and a timer rollover fails closed instead of creating
an event.

## 2026-08-31 — Treat receiver interruption as an enrollment gate

LIVE Pushover routes use emergency priority 2 and require a receipt, but that
provider request is not by itself proof of an audible Critical Alert. Each
recipient must explicitly enable Pushover's iOS Critical Alerts or Android
alarm/DND override behavior, and must pass a supervised locked-device drill.
ntfy remains a secondary transport without an equivalent Critical Alert or
human-acknowledgement claim in this implementation.

## 2026-09-01 — Retry a phone-path failure over saved Wi-Fi without delaying activation

The Garmin client persists a LIVE trigger and makes its first web request
immediately, before GPS and without a Wi-Fi preflight. Only when Garmin reports
that the BLE phone path is unavailable or its host timed out does the app ask
the platform once for an internet-capable saved Wi-Fi connection and retry the
same immutable queue head. The check has a ten-second watchdog, and reopening
the foreground app resumes any durable pending event automatically. Neither
path creates a new incident.

Connect IQ exposes neither the SSID/BSSID nor a nearby-network list through
this API, and a reported LTE connection is not proof that arbitrary Connect IQ
HTTPS can use LTE. Those values therefore remain diagnostics rather than
location or delivery evidence.

The same foreground client now targets the current fēnix 8 AMOLED/Solar/Pro,
fēnix E, Forerunner 970, Instinct 3 AMOLED/Solar, Venu 4, and Venu X1 SDK
profiles. Display-relative layouts avoid the Instinct Solar subdisplay and
cover round AMOLED, round MIP, and rectangular screens. A profile build proves
only SDK compatibility; physical buttons, readability, haptics, network paths,
GPS, and foreground lifetime remain device-specific gates.

## 2026-09-01 — Add a phone-configured direct Pushover TEST before the relay

The private beta accepts a Pushover user/group key and application token through
Garmin's password-type app settings and sends a clearly marked TEST request
directly from the foreground watch app. Its fixed core text is non-sensitive;
the later phone-configured emergency card is an explicit, opt-in direct-provider
exception outside normative TEST v1. This deliberately avoids requiring an
operator-run relay for the first end-to-end physical proof. A distinct beta app
ID is required so Garmin Connect/Connect IQ can deliver settings without
publishing the production listing.

In this mode one `START`/`ENTER` press triggers immediately; DOWN remains inert.
Only an HTTP 200 response containing status `1`, a valid provider request
reference, and the required emergency receipt is persisted as acceptance. The
app then gives a double haptic and draws the neutral analog cover. This
supersedes the earlier pre-trigger-cover decision for the direct TEST path: the
cover now means provider acceptance only, never device delivery, recipient
acknowledgement, incident resolution, or that help is coming. MENU resets the
accepted TEST. Direct LIVE, receipt polling, and production secret enrollment
remain out of scope; the later decision below adds only the bounded personal
direct-GPS drill.

## 2026-09-01 — Permit a bounded plaintext GPS drill only after direct acceptance

For the personal beta POC, the operator explicitly accepts that Pushover and
the Google Maps link target see exact watch coordinates. This exception is
separate from normative TEST v1, whose fixed alert remains non-sensitive, and
does not weaken encrypted LIVE/v2. The direct emergency request is attempted
and valid provider acceptance is durably stored before any position API call.
Only then does real watch GPS run in the foreground for at most one hour.

The first valid fix is persisted before submission and sent with Pushover
priority 1; materially changed later fixes use priority 0 and the existing
30-second/two-minute/five-minute battery-aware cadence. Pending location state
survives reopen, provider ambiguity may duplicate a message, and local
coordinate records are scrubbed at expiry or explicit MENU reset. Mock or
simulator positions can exercise code but never satisfy a physical GPS gate.

## 2026-09-01 — Add a separate content-blind mailbox transport

The frozen v2 event endpoint remains unchanged for current watch/provider
testing. A new mailbox transport wraps the complete v2 JSON in a fixed-size,
independently encrypted capsule so the mailbox operator cannot distinguish LIVE
from location or read device, incident, sequence, and creation metadata. This
is a transport around v2, not a v2 migration.

Each recipient gets a separate random mailbox with independent append, read,
and ACK capabilities. The server stores only their hashes and enforces 32
active messages, 64 new messages per hour, a 24-hour maximum event lifetime,
and expiry-plus-24-hour deletion. It does not use proof-of-work or collect
LIVE/TEST analytics.

Recipient acknowledgements are independently encrypted and bind the exact
capsule digest, inner incident, sequence, and message ID. Relay acceptance is
still not E2E acknowledgement. The Node codec and relay endpoints establish the
contract; companion enrollment, Android receiver integration, Garmin compiler
work, deployment, and physical drills remain separate gates.

## 2026-09-02 — Add Grafana Cloud IRM as a relay-free receiver route

The Garmin beta accepts one secret Grafana Cloud IRM formatted-webhook URL via
Garmin's password-type phone settings. It sends the same clearly marked TEST
core used by the Pushover proof, plus any opt-in emergency-card fields, with the
event ID as Grafana `alert_uid`.
Grafana can be the only route or can coexist with Pushover. The watch keeps its
single in-flight request boundary: it attempts preferred Grafana first and uses
Pushover as an independent fallback when Grafana is not definitely accepted.
A Pushover fallback acceptance preserves Grafana as a separately retryable
provider. Acceptance by either provider starts the analog cover, double haptic,
and bounded foreground GPS drill.

Grafana HTTP 2xx is recorded only as provider ingestion acceptance. Important
Push, OS interruption, in-app ACK, escalation, and human response remain
separate receiver evidence; the watch does not query or display Grafana ACKs.
GPS updates reuse the same alert UID and are sent only to accepted provider
configurations whose current credential/destination fingerprint still matches.
Pre-acceptance cached fixes are rejected. Remaining targets are persisted with
the fix before submission. This adds no Grafana route to encrypted relay LIVE and
does not change the frozen v1/v2 wire contracts. Grafana OSS OnCall is excluded;
the implemented target is Grafana Cloud IRM.

## 2026-09-02 — Make direct GPS notifications update-first

Direct Grafana and Pushover GPS notifications use one shared rendering instead
of provider-specific dense sentences. The title begins with the sequence-aware
`GPS-UPDATE`, followed by the explicit TEST marker. The body separates TEST
status, GPS status, watch-reported signal age, and map URL with blank lines.
Stale and last-known warnings remain in the GPS-status section.

Grafana continues to reuse the incident's `alert_uid` and repeat structured
profile fields because its newest alert item must retain responder context.
Those detail fields do not precede the update in the mobile `title`/`message`.
Pushover keeps its separate notification and map action but receives the same
formatted body. Provider acceptance, delivery, and human acknowledgement remain
separate facts.

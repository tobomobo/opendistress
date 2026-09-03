# OpenDistress

**Prepare quietly. Signal deliberately.**

An MIT-licensed, Garmin-first discreet safety-signalling prototype with
independent Wear OS and watchOS clients. OpenDistress is intended as a
preconfigured security measure for people exposed to elevated personal risk,
not as a general-purpose alarm or a replacement for emergency services.

> **Not an emergency-ready product.** The source slices are implemented, but
> Garmin strict/device validation, provider trials, native enrollment hardening,
> and the physical reliability matrix are still explicit release gates.

```text
Garmin / Wear OS / watchOS
    -> authenticated TEST or encrypted LIVE/location event
Python relay -> durable SQLite outbox -> Pushover + ntfy
trusted recipient CLI -> authenticate and decrypt v2 content locally
```

The relay never receives v2 content keys. A signed HTTP 202 proves only that an
event was durably recorded; provider acceptance, device delivery, recipient
acknowledgement, and incident resolution remain separate evidence.

Product copy, component names, colours, and Store metadata are defined in
[`docs/branding.md`](docs/branding.md). This beta deliberately adopts new
OpenDistress wire identifiers, application IDs, package IDs, and bundle IDs;
there is no compatibility promise for pre-release builds.

## What is here

- A Garmin Connect IQ app in Monkey C with durable TEST/LIVE queues, encrypted
  foreground location updates, app-list/glance/complication launch surfaces,
  responsive layouts for current fēnix, Forerunner, Instinct, and Venu
  profiles, phone-configured direct Grafana Cloud IRM and optional Pushover
  emergency TEST delivery, plus an immediate phone-path request and bounded
  saved-Wi-Fi fallback that never delays the first attempt.
- A stdlib-only Python relay with strict HMAC intake, SQLite leases and retry,
  recipient routes bound to their provider configuration, Pushover emergency
  receipts/cancellation, and authenticated ntfy publishing.
- A Node 22 trusted-recipient CLI that authenticates and decrypts v2 events.
- An optional content-blind mailbox transport with fixed-size encrypted v2
  capsules, hashed per-mailbox capabilities, bounded storage, and encrypted
  exact-capsule acknowledgements. The Node reference codec is implemented;
  companion, Android receiver, and Garmin integration remain explicit gates.
- Standalone Kotlin Wear OS and Swift watchOS apps using their native crypto,
  persistence, HTTPS, location, feedback, and accessibility APIs.
- Frozen v1/v2 schemas and cross-runtime public vectors in
  [`protocol/`](protocol/).

The conditional Garmin launcher face was not added because the stock launch
routes have not yet been physically measured. Direct SMS/voice is also
unclaimed: it needs a companion or provider plus real permission, SIM, carrier,
and hardware testing.

The relay-free Garmin path is deliberately a bounded TEST proof of concept. It
sends a clearly marked TEST alert directly to a phone-configured Grafana Cloud
IRM formatted webhook, Pushover, or both. Phone-editable settings can add an
optional display name, a prepared alert message, and a provider-neutral
emergency card containing a response plan, home address, children/family
information, person description, background, and an HTTPS photo URL. Separate
adapters render that model into Grafana's notification/detail fields or
Pushover's bounded message plus supplementary photo link. The prepared message
is intentionally part of Grafana's push body; the other structured details can
remain in the opened alert. Pushover may show its message content on the lock
screen. These details sync through Garmin
and are plaintext to the selected providers, so they are outside the privacy
guarantees of the normative protocol. With both providers configured, the watch
uses one network request at a time and attempts the preferred Grafana route
first; Pushover is the independent fallback when Grafana is not definitely
accepted. The first HTTP-level
provider acceptance changes the foreground app to its neutral analog cover and
triggers a double haptic. This proves neither phone delivery, Important/Critical
Push behavior, human acknowledgement, nor that help is coming. The current TEST
cover remains visually clean; the lower-left button or a tap reveals a separate
detail page without clearing anything. That page names the accepting provider,
marks phone delivery as unconfirmed, and places two short arc indicators beside
the physical middle-left and lower-left buttons. Pressing an action immediately
widens its arc and briefly reveals `DIAL` or `RESET TEST`; the latter performs
the explicit TEST reset required before another drill.

Only after the first stored acceptance, the foreground beta starts the watch's
real position API for up to 24 hours. It requests the most accurate available
Garmin mode in this order: multi-GNSS multi-band L1+L5, multi-GNSS L1, SatIQ,
then GPS/legacy continuous positioning. Indoors, it can immediately send the
watch's last-known fix with its age and warning; during an already-running
Garmin sport it also reads that activity's current location without starting or
altering the recording. Garmin does not expose a fix timestamp on that activity
surface, so the update says that its age is unknown. Each provider that accepted the trigger receives that
best available initial location, the first fresh fix, and meaningful later
movement, but only while its current credentials match
the fingerprint stored at acceptance; Grafana updates reuse the
same alert UID, while Pushover uses separate map-link messages. Both render
location notifications update-first, with blank-line-separated TEST status,
GPS status, signal age, and map sections instead of one dense sentence. These
GPS drill messages are outside v1 TEST and plaintext to Grafana and/or Pushover
plus the map-link provider. Use them only with the owner's explicit consent.
Simulator or mock coordinates never count as physical GPS evidence. Grafana's in-app ACK
is useful receiver evidence but is not yet returned to or displayed by the
watch.

## Run host checks

Python 3.11+ and Node 22 are the host requirements:

```sh
make ci
```

Native build commands and their remaining gates are documented in
[`apps/garmin/README.md`](apps/garmin/README.md),
[`apps/wearos/README.md`](apps/wearos/README.md), and
[`apps/watchos/README.md`](apps/watchos/README.md).
The Garmin guide also documents the protected GitHub Actions beta-packaging
workflow and the remaining manual Connect IQ Store upload.

## Run the relay locally

Copy both private configuration files outside the repository, replace every
public/example value, and restrict their permissions:

```sh
cp relay/devices.example.json /tmp/opendistress-devices.json
cp relay/routes.example.json /tmp/opendistress-routes.json
chmod 600 /tmp/opendistress-devices.json /tmp/opendistress-routes.json
python3 -c 'import base64,secrets; print("device_id="+base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")); print("test_key="+secrets.token_hex(32)); print("live_key="+secrets.token_hex(32))'
```

Set each device's private keys, group, recipients, and routes. Pushover user
keys belong in the route file; its application token stays in the environment:

```sh
export PUSHOVER_APP_TOKEN='replace-me'
python3 -m relay \
  --devices /tmp/opendistress-devices.json \
  --routes /tmp/opendistress-routes.json \
  --mailboxes /tmp/opendistress-mailboxes.json \
  --database /tmp/opendistress-relay.sqlite3
```

`--mailboxes` is optional. Create one private enrollment bundle and a server
record containing only capability hashes with:

```sh
python3 scripts/mailbox_enroll.py \
  --server-record /tmp/opendistress-mailboxes.json \
  --enrollment /private/path/recipient-mailbox.json
```

Both files are created with mode 0600 and existing files are never overwritten.
The enrollment contains capabilities and content keys and must never be placed
on the relay. The mailbox API and remaining metadata are documented in
[`protocol/README.md`](protocol/README.md) and [`docs/privacy.md`](docs/privacy.md).

The development listener is loopback plain HTTP. Put it behind one trusted HTTPS
terminator, disable request/header/IP logging there, and never expose the Python
listener directly. Provider recovery is intentionally at-least-once; a crash
after an external provider accepts can produce a duplicate.

Resolve an incident directly in its database, even if route files or provider
credentials are unavailable or have rotated:

```sh
python3 -m relay \
  --database /tmp/opendistress-relay.sqlite3 \
  --resolve-incident DEVICE_ID:INCIDENT_ID
```

This command performs no provider I/O. It durably stops unsent work and queues
any required Pushover emergency cancellation for a later worker running with
the exact provider configuration that originally accepted the alert.
Resolution also works against the previous v4 schema without migrating it.
Because v4 did not record provider fingerprints, any already-accepted v4
emergency must be cancelled manually at Pushover or allowed to expire; the
relay will not guess which credentials created it.

The offline recipient command is documented in
[`recipient/README.md`](recipient/README.md). Security constraints, architecture,
receiver Critical Alert enrollment, and the still-`NOT_RUN` physical rows are
in [`SECURITY.md`](SECURITY.md),
[`docs/receiver-enrollment.md`](docs/receiver-enrollment.md),
[`docs/`](docs/), and
[`tests/end-to-end/physical-matrix.csv`](tests/end-to-end/physical-matrix.csv).

## License

[MIT](LICENSE)

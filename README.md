# Smart Panic Button

An MIT-licensed, Garmin-first panic-notification prototype with independent
Wear OS and watchOS clients.

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
IRM formatted webhook, Pushover, or both. An optional phone-configured display
name personalizes the TEST title and is therefore visible to Garmin and each
selected provider. With both configured, the watch uses
one network request at a time but records each provider independently; Pushover
is attempted first and Grafana remains a separate route. The first HTTP-level
provider acceptance changes the foreground app to its neutral analog cover and
triggers a double haptic. This proves neither phone delivery, Important/Critical
Push behavior, human acknowledgement, nor that help is coming.

Only after the first stored acceptance, the foreground beta starts the watch's
real position API for up to one hour. Each provider that accepted the trigger
receives the first fix and meaningful later movement; Grafana updates reuse the
same alert UID, while Pushover uses separate map-link messages. These GPS drill
messages are outside v1 TEST and plaintext to Grafana and/or Pushover plus the
map-link provider. Use them only with the owner's explicit consent. Simulator
or mock coordinates never count as physical GPS evidence. Grafana's in-app ACK
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

## Run the relay locally

Copy both private configuration files outside the repository, replace every
public/example value, and restrict their permissions:

```sh
cp relay/devices.example.json /tmp/smart-panic-devices.json
cp relay/routes.example.json /tmp/smart-panic-routes.json
chmod 600 /tmp/smart-panic-devices.json /tmp/smart-panic-routes.json
python3 -c 'import base64,secrets; print("device_id="+base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")); print("test_key="+secrets.token_hex(32)); print("live_key="+secrets.token_hex(32))'
```

Set each device's private keys, group, recipients, and routes. Pushover user
keys belong in the route file; its application token stays in the environment:

```sh
export PUSHOVER_APP_TOKEN='replace-me'
python3 -m relay \
  --devices /tmp/smart-panic-devices.json \
  --routes /tmp/smart-panic-routes.json \
  --mailboxes /tmp/smart-panic-mailboxes.json \
  --database /tmp/smart-panic-relay.sqlite3
```

`--mailboxes` is optional. Create one private enrollment bundle and a server
record containing only capability hashes with:

```sh
python3 scripts/mailbox_enroll.py \
  --server-record /tmp/smart-panic-mailboxes.json \
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
  --database /tmp/smart-panic-relay.sqlite3 \
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

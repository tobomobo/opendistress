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
  and Wi-Fi/phone diagnostics.
- A stdlib-only Python relay with strict HMAC intake, SQLite leases and retry,
  recipient routes bound to their provider configuration, Pushover emergency
  receipts/cancellation, and authenticated ntfy publishing.
- A Node 22 trusted-recipient CLI that authenticates and decrypts v2 events.
- Standalone Kotlin Wear OS and Swift watchOS apps using their native crypto,
  persistence, HTTPS, location, feedback, and accessibility APIs.
- Frozen v1/v2 schemas and cross-runtime public vectors in
  [`protocol/`](protocol/).

The conditional Garmin launcher face was not added because the stock launch
routes have not yet been physically measured. Direct SMS/voice is also
unclaimed: it needs a companion or provider plus real permission, SIM, carrier,
and hardware testing.

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
  --database /tmp/smart-panic-relay.sqlite3
```

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
and the still-`NOT_RUN` physical rows are in [`SECURITY.md`](SECURITY.md),
[`docs/`](docs/), and
[`tests/end-to-end/physical-matrix.csv`](tests/end-to-end/physical-matrix.csv).

## License

[MIT](LICENSE)

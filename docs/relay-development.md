# Relay development

This optional developer path is separate from the direct TEST beta. Start with
the [project README](../README.md) to try Grafana/Pushover without a server.
Run these commands from the repository root with Python 3.11+.

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
  --database /tmp/opendistress-relay.sqlite3
```

Add `--mailboxes /tmp/opendistress-mailboxes.json` only after creating that file.
Create one private enrollment bundle and a server
record containing only capability hashes with:

```sh
python3 scripts/mailbox_enroll.py \
  --server-record /tmp/opendistress-mailboxes.json \
  --enrollment /private/path/recipient-mailbox.json
```

Both files are created with mode 0600 and existing files are never overwritten.
The enrollment contains capabilities and content keys and must never be placed
on the relay. The mailbox API and remaining metadata are documented in
[`protocol/README.md`](../protocol/README.md) and [`docs/privacy.md`](privacy.md).

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

See the [recipient guide](../recipient/README.md), [security policy](../SECURITY.md)
and [architecture](architecture.md) before using the encrypted stack.

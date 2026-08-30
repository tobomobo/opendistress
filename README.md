# Smart Panic Button

An MIT-licensed, Garmin-first panic notification system. Its first milestone is
deliberately narrow: one foreground fēnix 8 action sends one authenticated,
non-sensitive TEST notification through the paired Android phone and Garmin
Connect to a small relay and Pushover.

> **Status:** software prototype, not a proven emergency path. The repository
> has not passed the required physical-watch reliability matrix yet.

```text
Garmin app -> signed incident event -> reference relay -> Pushover -> contact
```

The stable seam is the event protocol, not cross-platform application code.
No data is sent before a deliberate activation. TEST events contain opaque IDs
only; LIVE identity, instructions, and location are blocked until
application-layer encryption is implemented.

## Run the relay locally

Python 3.11+ and `make` are the only host requirements.

```sh
cp relay/devices.example.json /tmp/smart-panic-devices.json
# Replace the public disabled fixture entry with a fresh device ID/key, enable it,
# and restrict the file before use.
python3 -c 'import base64,secrets; print(base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")); print(secrets.token_hex(32))'
chmod 600 /tmp/smart-panic-devices.json
export PUSHOVER_APP_TOKEN='replace-me'
export PUSHOVER_USER_KEY='replace-me'
python3 -m relay --devices /tmp/smart-panic-devices.json \
  --database /tmp/smart-panic-relay.sqlite3
```

The development listener is plain HTTP. Put it behind a trusted HTTPS reverse
proxy for a watch-facing deployment and ensure that proxy does not retain
bodies, headers, or client IP addresses.

Use a dedicated Pushover account with exactly one active Android device for
this milestone. Pushover may fan out to every active account device when a
configured device name is missing, stale, or invalid.

Phase 1 also trusts Garmin's app-settings channel with the TEST HMAC key. A
compromised settings or paired-phone path can therefore forge TEST evidence;
independent authentication-key provisioning is a gate for LIVE alerts.

Run all host-side checks with:

```sh
make ci
```

The Garmin project and SDK instructions are in
[`apps/garmin/README.md`](apps/garmin/README.md). Protocol details and a public
HMAC test key live in [`protocol/README.md`](protocol/README.md).

## Scope

The current milestone ends when one physical watch produces one unmistakable
TEST notification with a matching event ID and an honest result. Durable
retry/outbox delivery, acknowledgement, encrypted LIVE alerts, location,
additional transports, Wear OS, and watchOS are gated in
[`docs/roadmap.md`](docs/roadmap.md).

Phase 1 does use a minimal SQLite attempt ledger: it prevents a retry or relay
restart from submitting the same TEST event twice, but it does not provide a
delivery worker or automatic retry.

See [`CONTRIBUTING.md`](CONTRIBUTING.md), [`SECURITY.md`](SECURITY.md), and the
[`docs/`](docs/) directory for implementation and test constraints.

## License

[MIT](LICENSE)

# Garmin app

This foreground Connect IQ app implements the Garmin slices of the protocol:

- non-sensitive TEST v1;
- durable relay acceptance and immutable retry;
- encrypted LIVE and encrypted cached/fresh/later location events;
- an app-list entry, static glance, and published complication;
- reported Wi-Fi/phone connection diagnostics without pretending to choose a
  transport.

The default mode is TEST. MENU toggles between TEST and LIVE only while no
event is queued; START deliberately creates or retries an event. LIVE events
cannot be abandoned. A repeated LIVE START while the incident is active keeps
the same incident rather than creating another logical alarm.

The complete immutable queue and active-incident state are stored before the
first request. The app removes only the queue head after an HTTP 202 response
with the matching event ID and a valid response HMAC. `RELAY ACCEPTED` means
relay persistence—not provider acceptance, device delivery, human
acknowledgement, or resolution.

## Memory and type safety

Connect IQ requires Monkey C; it does not offer a Rust target. Monkey C runs as
managed bytecode and has no raw-pointer or manual-free interface, but runtime
type errors remain possible. This source still contains dynamically typed
dictionary boundaries. SDK 9.2.0 compiles it with gradual checking (`-l 1`),
but the strict `-l 3` build still fails and remains a release gate. Runtime
memory is bounded with an at-most-three-event queue, exact dictionary shapes,
fixed 16-byte plaintext/ciphertext blocks, 32-bit timestamp checks, and one
shared in-flight event-or-status request.

Protocol, persisted-state, callback-context, mode, and configuration-key
comparisons use value equality. Connect IQ 9.2 simulator execution proved that
runtime-created strings cannot safely be compared with `==` for those checks.
Response signatures retain a constant-work character comparison.

## Configure TEST

These three ordinary app settings may be entered through Garmin Connect,
Connect IQ, Garmin Express, or the simulator:

- `Relay HTTPS origin`: an origin such as `https://alerts.example`, with no
  path, query, credentials, fragment, or trailing slash;
- `Device ID`: the canonical 22-character base64url encoding of 16 random
  bytes;
- `Device HMAC key`: 32 random bytes as 64 lowercase hexadecimal characters.

The app posts to `/v1/events` or `/v2/events` itself. The TEST setting channel
and paired phone are trusted only for TEST; never reuse that HMAC key for LIVE.

## Provision LIVE

LIVE uses five build-time properties which deliberately have invalid defaults
and are absent from app settings:

```text
liveAuthKeyHex       32 random bytes, watch + relay
liveEncKeyHex        32 independent random bytes, watch + recipients
liveMacKeyHex        32 independent random bytes, watch + recipients
liveTemplateIdHex    16 random bytes mapped locally by recipients
liveKeyVersion       positive 32-bit integer
```

All three keys must differ. The app also rejects the public conformance keys.
Put the real property overrides in the ignored
`private-resources/properties/properties.xml`, and create the ignored
`private.jungle` beside `monkey.jungle` with:

```text
base.resourcePath = $(base.resourcePath);private-resources
```

Pass `monkey.jungle:private.jungle` to `-f` on Linux so the private resource is
last and has override precedence. USB sideload the resulting binary. Rebuild
with a higher key version to rotate content keys. Do not rotate
`liveAuthKeyHex` while an event is queued or an unexpired incident remains
active: v2 has no authentication-key version with which to retry the immutable
request under its original key.

The simulator run did not verify private resource-path precedence; confirm that
gate before putting real keys into a build. If the SDK does not override the
invalid defaults, stop: this source tree has no alternate secret-injection
seam. Never move LIVE keys into app settings.

LIVE encrypts before any communication call with the fixed v2 profile:
AES-256-CBC over exactly one block with no padding, then HMAC-SHA256 over the
authenticated envelope using a separate content-MAC key. The relay never gets
the content keys.

## Location and activation surfaces

The LIVE trigger is persisted and its request started before any position API
is called. The app then appends one encrypted snapshot and starts continuous
positioning for the fresh stage; the first callback is recorded as the distinct
fresh record and later callbacks use the cadence below. Pending acquisition
stage is persisted with each location event, so opening an already-activated,
unexpired incident resumes an unfinished position-callback acquisition after
restart. A missing one-shot callback therefore cannot gate later acquisition.
It never waits for GPS, starts GPS for an unactivated incident, sends plaintext
coordinates, or invents a radius.

After the initial callback, continuous positioning runs only while this view is
foreground and the incident is unexpired. A quality improvement queues
immediately. Otherwise a move must exceed `0.0005` degrees in latitude or
longitude and the minimum interval is 30 seconds for the first five minutes,
two minutes through minute 30, and five minutes later. At 20% battery or below
while not charging, those intervals double. The same foreground-only cadence
queries signed `/v2/status` even when a position callback is unchanged. An
unaccepted TEST or LIVE trigger has priority and shares the single in-flight
request gate with status queries. Only a queued `location.updated` for the same
active incident may be bypassed: verified `active` or `acknowledged` status
drains it before scheduling the next poll, while verified terminal status
archives it. Unrelated, malformed, and trigger events are never bypassed. Only
an exact, fresh, signed `resolved` or `expired` result for the outstanding
request and active incident stops acquisition and durably disarms LIVE;
`active`, `acknowledged`, failures, and timeouts continue. Local expiry remains
an independent stop: it atomically removes the active plaintext location
comparison state while retaining encrypted pending events for explicit MENU
archive. The three-entry durable queue is a hard backpressure bound, and view
hide disables both positioning and status polling.

The `:glance`-scoped glance and published complication contain only a static
launcher label.
On products and watch faces that expose selection as a launcher, selecting one
should open the foreground app; neither surface itself sends, reads storage, or
requests location. This remains a physical-test gate. A separate watch-face
project is intentionally absent until measurements show the stock complication
is inadequate.

Before each request the UI reports whether the system currently reports Wi-Fi
or phone connectivity. This is diagnostic evidence only. Connect IQ chooses
the actual path, and the app neither gates the request on Wi-Fi nor claims it
can force Wi-Fi over Bluetooth.

## Hardware target and build

The manifest currently targets `fenix847mm`. Confirm the physical watch's
exact SDK profile—43 mm, Solar, and Pro variants can use different IDs—before
compiling or sideloading.

The Connect IQ SDK is not vendored. With its `bin` directory on `PATH`, from
this directory:

```sh
mkdir -p bin
monkeyc -f monkey.jungle -d fenix847mm -o bin/SmartPanicButton.prg \
  -y /absolute/path/to/developer_key.der -l 1 -w
```

For a provisioned personal LIVE build, replace `-f monkey.jungle` with
`-f monkey.jungle:private.jungle`. Both private paths are gitignored.

Run the protocol vectors:

```sh
monkeyc -f monkey.jungle -d fenix847mm -o bin/SmartPanicButton-tests.prg \
  -y /absolute/path/to/developer_key.der -l 1 -t -w
monkeydo bin/SmartPanicButton-tests.prg fenix847mm -t
```

Keep signing keys and private resources outside the repository. Simulator and
physical validation still must cover settings, a successful strict `-l 3`
build, queue restart, tampered results, app-list/glance/complication launch,
indoor/no-fix location, Bluetooth loss, phone-off Wi-Fi, firmware/reboot
behavior, and the recorded activation/failure matrices in `tests/end-to-end/`.

## Simulator evidence

On 2026-08-31, official Connect IQ SDK 9.2.0 compiled the complete
`fenix847mm` app at `-l 1`. Four tests passed in the Garmin simulator:
`protocolConformance`, `protocolRejectsMalformedEvents`,
`protocolRejectsUnsafeConfiguration`, and `protocolRejectsTamperedResults`.
Together they execute the published v1 TEST, encrypted v2 LIVE, encrypted
location, durable-result, and signed-status vectors, plus canonical-ID,
timestamp/lifetime, exact-key, key-separation, relay-origin, response-tamper,
request-binding, status-freshness, and fail-closed failure-result checks. The
device profile came from the pinned test image
`ghcr.io/zetxek/connectiq-tester@sha256:c215a5ea9ae89b69a89aaaec6b5df3b8019578ea56b0d892c98251feefbf08cc`;
the simulator test ran without network access. Garmin's runner returns process
status 1 even when its structured result says
`PASSED (passed=4, failed=0, errors=0)`, so automation must parse that result.

An interactive foreground smoke launch is still unverified in this headless
environment. The simulator process exited with status 139 while loading both
this app and Garmin's bundled `Menu2Sample`. Its last log lines included
`SetLayout`, but successful unit execution also emits that line, so it is not
itself the failing operation. This is consistent with a shared headless
simulator/environment failure and does not prove an app-specific crash.
Settings, START/MENU input, persistence/restart, GPS, BLE, Wi-Fi, battery,
timers, glance, complication, and network callbacks therefore remain
unverified interactively. Strict `-l 3` currently fails with 295 compiler
errors and remains a release gate. See the recorded
[`simulator-matrix.csv`](../../tests/end-to-end/simulator-matrix.csv); every
physical row remains open.

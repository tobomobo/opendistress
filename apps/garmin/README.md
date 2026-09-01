# Garmin app

This foreground Connect IQ app implements the Garmin slices of the protocol
and a bounded direct-Pushover TEST path:

- non-sensitive TEST v1;
- durable relay acceptance and immutable retry;
- encrypted LIVE and encrypted cached/fresh/later location events;
- an app-list entry, static glance, and published complication;
- immediate phone/default-path submission plus one best-effort Wi-Fi check and
  immutable retry when Garmin reports the phone path unavailable.
- direct Pushover emergency TEST submission using phone-editable app settings;
- a bounded, explicitly privacy-relaxed direct-GPS drill after provider
  acceptance.

When valid Pushover settings are present, the top hardware button
(`START`/`ENTER`) immediately creates and sends a fixed non-sensitive TEST.
DOWN is consumed without triggering. A fully provisioned personal build with
no direct TEST settings remains LIVE-only: LIVE is committed only after the top
button remains pressed for 1.5 seconds, and releasing sooner cancels without
creating an event. There is no visible countdown or on-watch TEST/LIVE switch.

At the hold threshold, the personal build persists the encrypted LIVE event,
attempts one short haptic confirmation, and starts submission immediately.
The haptic proves only that local persistence completed; it does not prove the
relay or a recipient received anything. LIVE events cannot be abandoned, and
reopening an active incident keeps the same incident rather than creating
another logical alarm.

Connect IQ cannot install a global third-party button listener. On fēnix 8,
DOWN reaches the app only after Garmin has opened an allowed foreground
surface. Pinning the app's glance or complication can shorten that route, and a
firmware shortcut may be used only if that watch offers the installed app as a
target. Neither the source nor the simulator proves a one-press global launch.

The complete immutable queue and active-incident state are stored before the
first request. The app removes only the queue head after an HTTP 202 response
with the matching event ID and a valid response HMAC. `RELAY ACCEPTED` means
relay persistence—not provider acceptance, device delivery, human
acknowledgement, or resolution.

The neutral analog cover is shown only after the direct Pushover API returns a
valid emergency receipt and request reference and that evidence is stored. A
double haptic accompanies that transition. In this direct TEST path the cover
means only **Pushover accepted the request**; it does not mean a receiver phone
displayed or sounded it, a person acknowledged it, or help is coming. The
cover is an ordinary foreground app view, not a replacement system watch face.
MENU clears accepted TEST evidence and returns to readiness.

After that stored acceptance, and never before it, the beta requests a real
watch position for up to one hour. The first valid fix is sent as a separate
high-priority Pushover message with a Google Maps link; meaningful later moves
use normal priority. Position acquisition and updates run only while the app is
foreground. No synthetic unavailable record is sent, and simulator/mock fixes
are not evidence that real GPS works. A pending fix is stored before its
network call, retried a bounded number of times, and resumed when the app is
reopened. MENU or the one-hour expiry stops positioning and scrubs the locally
stored coordinate records while retaining the provider-acceptance cover until
MENU resets it.

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

The end-user TEST path needs only two 30-character Pushover values:

- `Pushover user/group key`: the receiver or receiver-group destination;
- `Pushover application API token`: an application registered by the tester.

Install the Beta/App-Store artifact, then edit those password fields in the
Connect IQ Store app, Garmin Connect, or Garmin Express. The watch receives the
updated values through Garmin app settings and refreshes an idle setup screen
without a reinstall. If a TEST is already pending after a rejected
configuration, saving corrected valid values retries that same durable event
automatically. A USB-sideloaded PRG does not provide this normal phone
settings workflow, which is why `beta.jungle` and the separate beta application
ID exist. Build the store artifact with:

```sh
monkeyc -e -f beta.jungle \
  -o bin/PanicButton-TEST.iq \
  -y private-resources/developer_key.der -l 1
```

The beta sends a form-encoded HTTPS POST directly to Pushover with priority
`2`, a 30-second retry interval, and the remaining TEST lifetime as expiry. It
contains no location or LIVE payload. After its acceptance, a separate
direct-GPS drill may send exact coordinates in a Google Maps URL. The first
location uses Pushover priority `1`; later locations use priority `0`, so they
do not create more emergency receipts. Both Pushover and Google can observe
the exact coordinates. Pushover has no idempotency key, so an ambiguous retry
can duplicate either the emergency TEST or a location update. The token,
destination, and direct coordinates are private and are stored or processed by
Garmin/Pushover/the map provider; this is acceptable only for the bounded
personal POC, not production LIVE enrollment.

The older relay-backed v1 TEST path remains available to build-time/private
configurations, but its relay URL, device ID, and HMAC key are no longer exposed
as end-user phone settings.

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
base.resourcePath = $(base.resourcePath);private-resources/properties
```

Point at the properties directory rather than the private-resources root. This
keeps an optional simulator-only private manifest from being parsed as an app
resource.

Pass `monkey.jungle:private.jungle` to `-f` on Linux so the private resource is
last and has override precedence. USB sideload the resulting binary. Rebuild
with a higher key version to rotate content keys. Do not rotate
`liveAuthKeyHex` while an event is queued or an unexpired incident remains
active: v2 has no authentication-key version with which to retry the immutable
request under its original key.

SDK 9.2.0 on macOS verified that a later private resource path overrides the
invalid LIVE defaults on a fresh simulator application. Application properties
persist across rebuilds, however: installing a private binary over an existing
unprovisioned app can retain the old blank values. Provision the personal build
as a fresh install (or a distinct personal application ID), and never clear app
data while a TEST is queued or a LIVE incident is active. Never move LIVE keys
into app settings.

LIVE encrypts before any communication call with the fixed v2 profile:
AES-256-CBC over exactly one block with no padding, then HMAC-SHA256 over the
authenticated envelope using a separate content-MAC key. The relay never gets
the content keys.

Before treating a personal build as usable, complete the receiver enrollment
and locked/silent/Focus or DND drills in
[`../../docs/receiver-enrollment.md`](../../docs/receiver-enrollment.md).

## Location and activation surfaces

The LIVE trigger is persisted and its request started before any position API
is called. The app then appends one encrypted snapshot and starts continuous
positioning for the fresh stage; the first callback is recorded as the distinct
fresh record and later callbacks use the cadence below. Pending acquisition
stage is persisted with each location event, so opening an already-activated,
unexpired incident resumes an unfinished position-callback acquisition after
restart. A missing one-shot callback therefore cannot gate later acquisition.
It never waits for GPS, starts GPS for an unactivated incident, sends plaintext
coordinates, or invents a radius. That sentence describes the encrypted LIVE
path. The explicitly separate direct-GPS drill described above relaxes the
plaintext boundary only after direct Pushover TEST acceptance.

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

Before each request the TEST UI reports whether the system currently reports
Wi-Fi, LTE, or phone connectivity. This is diagnostic evidence only. Connect
IQ chooses the actual web-request path; an LTE state is not treated as evidence
that an arbitrary Connect IQ HTTPS request can use Garmin LTE.

The persisted trigger always gets its first request immediately. It never
waits for GPS or a Wi-Fi scan. If that request fails specifically because the
BLE phone connection is unavailable or its Garmin host times out, the app calls
`Communications.checkWifiConnection()` once for that queue head. When Garmin
reports that an internet-enabled saved access point can be connected, the app
retries the exact same immutable event. A failed or unavailable Wi-Fi check
leaves the event durably queued and falls back to the existing bounded retries.
The Wi-Fi callback has a ten-second watchdog, so a missing platform callback
cannot block the queue indefinitely. Reopening the app automatically resumes a
durably pending event, but opening an app with no pending event never creates
one.
The app never receives an SSID/BSSID, never scans arbitrary nearby networks,
and never describes this best-effort retry as delivery evidence.

## Hardware targets and build

The manifest targets these current SDK 9.2 profiles. One profile may represent
multiple retail variants, as named by Garmin's device reference:

| Family | Connect IQ profiles |
| --- | --- |
| fēnix 8 / tactix 8 / quatix 8 | `fenix843mm`, `fenix847mm` |
| fēnix 8 Solar | `fenix8solar47mm`, `fenix8solar51mm` |
| fēnix 8 Pro / quatix 8 Pro | `fenix8pro47mm` |
| fēnix E | `fenixe` |
| Forerunner 970 | `fr970` |
| Instinct 3 AMOLED / Solar | `instinct3amoled45mm`, `instinct3amoled50mm`, `instinct3solar45mm` |
| Venu 4 / D2 Air X15 | `venu441mm`, `venu445mm` |
| Venu X1 | `venux1` |

The status layout, glance, and analog cover use display-relative bounds. The
matrix therefore includes small and large round AMOLED, round MIP, and the
rectangular Venu X1 profile. A successful profile build is compatibility
evidence only; button mapping, haptic behavior, foreground lifetime, Wi-Fi,
GPS, and physical readability still require that exact hardware.

The Connect IQ SDK is not vendored. With its `bin` directory on `PATH`, from
this directory:

```sh
mkdir -p bin
monkeyc -f monkey.jungle -d fenix847mm -o bin/SmartPanicButton.prg \
  -y /absolute/path/to/developer_key.der -l 1 -w
```

For a provisioned personal LIVE build, replace `-f monkey.jungle` with
`-f monkey.jungle:private.jungle`. A reproducible local build can keep its
developer key at the gitignored `private-resources/developer_key.der` with file
mode `0600`; pass that path to `-y`. Both private paths and all `*.der` files
are gitignored.

Run the protocol vectors:

```sh
monkeyc -f monkey.jungle -d fenix847mm -o bin/SmartPanicButton-tests.prg \
  -y /absolute/path/to/developer_key.der -l 1 -t -w
monkeydo bin/SmartPanicButton-tests.prg fenix847mm -t
```

Keep signing keys and private resources outside tracked repository content.
Simulator and
physical validation still must cover settings, a successful strict `-l 3`
build, queue restart, tampered results, app-list/glance/complication launch,
indoor/no-fix location, Bluetooth loss, phone-off Wi-Fi, firmware/reboot
behavior, and the recorded activation/failure matrices in `tests/end-to-end/`.

## Simulator evidence

On 2026-09-01, official Connect IQ SDK 9.2.0 compiled the complete app at
`-l 1` for every profile in the hardware table above. Four tests passed in the
Garmin simulator on `fenix847mm`:
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

Earlier native macOS simulator runs verified the public setup state and the
former pre-trigger cover behavior. That cover behavior was intentionally
superseded by the direct-TEST acceptance UX above. The current fēnix 8 47 mm
simulator shows readable `READY — TEST` copy with no cover, and one short
upper-right START press immediately reached Pushover and displayed its expected
configuration rejection when run with deliberately invalid 30-character test
tokens. This proves the SDK input/callback path and visible error state, not
provider acceptance or receiver delivery. The valid-receipt cover and every
real-GPS behavior still need the supervised physical Pushover drill.

The separate beta manifest also exported successfully at `-l 1` for all 17
expanded device configurations. The automation API still cannot hold a
simulated button for 1.5 seconds, so the positive private-LIVE hold remains
unexecuted rather than inferred. Earlier simulator work also reproduced and
removed a fourth-timer regression. All personal simulations used
non-production keys; none proved relay acceptance or delivery.

The earlier Linux headless process still exits 139 for both this app and
Garmin's bundled `Menu2Sample`, so that remains a shared environment
limitation. GPS, BLE, Wi-Fi, battery, haptic, glance, complication, long-hold,
and real network callbacks remain unverified interactively. Mock simulator GPS
must not be promoted to a PASS for any physical location row. Strict `-l 3`
still fails on the broadly untyped legacy Monkey C boundaries and remains a
release gate. See the
recorded
[`simulator-matrix.csv`](../../tests/end-to-end/simulator-matrix.csv); every
physical row remains open.

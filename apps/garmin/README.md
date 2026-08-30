# Garmin phase-1 TEST app

This is the smallest Garmin Connect IQ watch app for the phase-1 TEST path. It
sends only after the wearer presses START, persists the exact event before the
request, and checks that same event after any uncertain outcome. A screen may
say `PROVIDER ACCEPTED` only after an HTTP 200 response with the exact expected
fields and a valid response HMAC.

While an event is pending, in flight, or accepted, its full 22-character event
ID remains visible on the watch so it can be matched to the Pushover title.

## Hardware target: confirm the SKU

The manifest targets the Connect IQ device profile `fenix847mm`. **Confirm the
physical watch's exact SKU against the installed Garmin SDK device profiles
before compiling or sideloading.** Fenix 8 variants such as 43 mm, Solar, and
Pro models can use different Connect IQ target IDs. This target choice has not
yet been validated on physical hardware.

## Configure

Set these app settings in Garmin Connect, the Connect IQ app, Garmin Express,
or the simulator's Application Properties editor:

- `Relay event URL`: the full HTTPS event submission URL.
- `Device ID`: exactly 22 canonical unpadded base64url characters (a 16-byte ID).
- `Device HMAC key`: exactly 64 lowercase hex characters (32 bytes). Provision a
  distinct secret per device and never commit it.

These phase-1 values pass through Garmin's app-settings channel. Treat that
channel and the paired phone as trusted for TEST evidence; do not reuse this
provisioning path or key for LIVE alerts.

The app validates all three settings locally. It performs no web request on
launch, while rendering, or after a settings update. Pressing START is the only
activation path.

MENU explicitly abandons a pending TEST when no request is in flight; the next
START creates a fresh event. Abandoning does not prove that the prior event
failed, so match its displayed ID against Pushover first. This TEST-only escape
hatch must not become a LIVE-alert retry policy.

## Build and run

The Garmin Connect IQ SDK is not installed in this workspace, so these commands
are documented but have not been executed here. From `apps/garmin`, with the SDK
`bin` directory on `PATH`:

```sh
mkdir -p bin
monkeyc -f monkey.jungle -d fenix847mm -o bin/SmartPanicButton.prg -y /absolute/path/to/developer_key.der -w
```

Launch the Garmin simulator, then load the app:

```sh
connectiq
monkeydo bin/SmartPanicButton.prg fenix847mm
```

The SDK test build includes the single fixed HMAC/signing conformance vector in
`source/PanicProtocol.mc`:

```sh
monkeyc -f monkey.jungle -d fenix847mm -o bin/SmartPanicButton-tests.prg -y /absolute/path/to/developer_key.der -t -w
monkeydo bin/SmartPanicButton-tests.prg fenix847mm -t
```

Use a developer signing key kept outside this repository. On SDK releases whose
simulator executable is named `ciq-sim`, use that command in place of
`connectiq`.

## Wire contract

The POST body is the direct JSON event dictionary with only these fields:

```text
v,event_id,incident_id,device_id,kind,sequence,created_at,expires_at,payload
```

The request header is `X-SPB-Signature: v1=<base64url-unpadded HMAC-SHA256>`.
The HMAC input is UTF-8 and uses this exact LF-delimited grammar, including its
final LF:

```text
spb.test.submit.v1
method=POST
v=1
event_id=<id>
incident_id=<id>
device_id=<id>
kind=test.triggered
sequence=0
created_at=<seconds>
expires_at=<seconds+900>
payload=null
```

The only accepted success body has exactly `v`, `event_id`, `result`,
`provider`, and `response_signature`, with `v=1`, the matching event ID,
`result=provider_accepted`, and `provider=pushover`. Its signature covers this
exact UTF-8 input, also with a final LF:

```text
spb.test.result.v1
v=1
event_id=<id>
result=provider_accepted
provider=pushover
```

## Validation still required

- Compile and run the conformance test with an installed current Connect IQ SDK.
- Exercise settings entry, Bluetooth-phone proxying, Wi-Fi proxying, time-not-set,
  app restart with a pending event, timeout/drop, and signed-response tampering in
  the simulator where possible.
- Confirm button behavior, legibility, storage persistence, and network behavior
  on the exact physical watch SKU.

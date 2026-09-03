# OpenDistress brand

## Positioning

**Prepare quietly. Signal deliberately.**

OpenDistress is a discreet, watch-first security measure for people exposed to
elevated personal risk. It is configured and tested in calm conditions, then
deliberately activated when reaching a phone or speaking may not be possible.

OpenDistress is not an emergency service and must not imply that provider
acceptance means phone delivery, human acknowledgement, or that help is coming.

## Names

- Product: **OpenDistress**
- Garmin beta: **OpenDistress TEST**
- Platform clients: **OpenDistress for Garmin**, **OpenDistress for Wear OS**,
  and **OpenDistress for watchOS**
- Shared wire contract: **OpenDistress Protocol**
- Reference service: **OpenDistress Relay**
- Trusted-recipient implementation: **OpenDistress Recipient**

Use `OpenDistress` as one word with both capitals. Do not shorten the public
name to `Panic`, `Distress`, or `OD`.

The beta uses OpenDistress names throughout: `X-OpenDistress-Signature`,
`opendistress.*` canonical domains, fresh Garmin application IDs,
`dev.opendistress.*` Android and Apple identifiers, OpenDistress source and
target names, OpenDistress schema IDs, and OpenDistress release filenames.
Pre-release Smart Panic Button builds are intentionally incompatible and are
not migrated.

## Language

Describe activation as a **deliberate signal**, not panic. Describe the product
as a **security measure** or **preparation tool**, not protection, rescue, or
monitoring unless a concrete configured service provides that function.

Evidence language stays exact:

- `Signal stored` means local persistence completed.
- `Relay accepted` means the relay transaction committed.
- `Provider accepted` means a configured provider accepted its request.
- `Acknowledged` requires verified recipient evidence.
- Never replace any of those with `Delivered` or `Help is coming`.

TEST notifications must lead with `TESTNOTRUF` and `KEIN ECHTER NOTFALL` so the
brand never makes a drill resemble a real emergency.

## Mark and colour

The mark is an open readiness ring with a single signal point at six o'clock.
It reuses the Garmin hold interaction: the ring closes from the bottom only
during deliberate activation.

- Graphite `#101820`: normal background
- Warm white `#f4f0e6`: ring and primary information
- Signal amber `#f5a623`: readiness point and deliberate progress
- Red is reserved for an unmistakable active live state or blocking error; it
  is not the identity colour.

## Native app icons

Apple Watch uses an opaque 1024 x 1024 master in its `AppIcon` asset catalog;
watchOS applies the final device mask, so the source artwork has square edges
and no alpha channel. Regenerate it on macOS with `make native-icons`.

Wear OS uses the same mark as an adaptive icon: graphite is the background
layer, the warm-white ring and amber signal point form the foreground, and an
all-white monochrome layer supports Android themed icons. API 26 resources omit
the newer monochrome element; API 33 resources add it explicitly.

## Garmin Connect IQ Store metadata

**Name**

`OpenDistress TEST`

**Tagline / short description**

`A discreet Garmin safety signal, configured before it is needed.`

**Description**

> OpenDistress is a watch-first preparation tool for people exposed to elevated
> personal risk. Configure a Grafana Cloud IRM or Pushover TEST route in calm
> conditions, then deliberately hold the watch's START button for 2.5 seconds
> to send a clearly marked test signal. After provider acceptance, the watch
> confirms with haptics, displays a neutral analog cover, and attempts
> best-available location updates while the app remains open.
>
> This beta does not contact emergency services. Provider acceptance does not
> prove phone delivery, human acknowledgement, or response. Do not rely on this
> build as an emergency-ready system.

**Release note**

`Renamed to OpenDistress with a calmer readiness-ring identity. Keeps TEST alerts unmistakable and adds resilient watch-location acquisition.`

**Website**

`https://github.com/tobomobo/opendistress`

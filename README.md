<img src="docs/store-assets/opendistress-store-icon.svg" alt="OpenDistress readiness ring" width="88" height="88">

# OpenDistress

**Prepare quietly. Signal deliberately.**

Open-source distress signalling from your wrist, configured before you need it.
OpenDistress is for people who want to prepare a deliberate signal for situations
where reaching a phone or speaking may not be possible. Agree on a response plan
with trusted people, prepare your information, and rehearse the whole route.

Garmin-first, with a Pixel Watch / Wear OS TEST beta and a shared Android setup
app. The direct-provider beta uses **Grafana Cloud IRM or Pushover**: you do not
need to host an OpenDistress server or use a decentralised messaging network.
Recipients use the selected provider's app, not a separate OpenDistress app.

> **Experimental TEST beta, not an emergency-ready system.** Direct-provider
> notifications are deliberately marked as tests. This is not a replacement
> for emergency services and does not contact them automatically. Do not rely
> on this build as your only way to ask for help.

[Try the beta](#try-the-test-beta) · [Preparation guide](docs/preparation.md) ·
[Platform limits](docs/platform-limitations.md) ·
[Releases](https://github.com/tobomobo/opendistress/releases)

## Prepare, signal, verify

1. **Prepare on your phone.** Configure a provider and an optional emergency
   profile: your name and description, response instructions, home address,
   dependants, relevant background, and a photo link. Home address is labelled
   separately from current location. The Android app includes a saved-profile
   preview and guided physical drills.
2. **Open the watch app and hold deliberately.** On Garmin, hold the top
   `START`/`ENTER` button for 2.5 seconds; on Wear OS, hold the on-screen control.
   The ring fills from the bottom in both directions. Releasing early cancels.
   This is not a global hardware shortcut; Garmin touchscreen taps do not send.
3. **Watch for provider acceptance.** Once acceptance is stored, the foreground
   app shows a neutral analog cover and gives haptic feedback. It then attempts
   location updates, with source and age information when available. The cover
   is an app view, not a replacement system watch face.
4. **Verify with your recipients.** Check the actual message, locked-phone
   behaviour, location and acknowledgement in the receiving app. Reset the TEST
   explicitly before the next drill. Recorded drills are your observations,
   not automatic delivery telemetry.

**Provider accepted ≠ phone received ≠ person acknowledged ≠ help is coming.**
The analog cover and haptics confirm only provider acceptance. Full-screen,
silent-mode and Do Not Disturb behaviour depend on the provider, permissions
and receiving phone. They must be tested, not inferred from the watch.

## Choose your platform

| Platform | Current path | Setup and important limits |
| --- | --- | --- |
| **Garmin Connect IQ** | Garmin-first direct TEST beta | Configure through Connect IQ Store app settings, or the optional Android companion through Garmin Connect. Foreground app required; Wi-Fi fallback is best effort, not a Garmin LTE guarantee. [Garmin guide](apps/garmin/README.md) |
| **Pixel Watch / Wear OS** | Direct TEST beta | Install both Android Setup and watch APKs. Configure on Android, then sync to the watch. A Tile opens the app; it does not trigger an alert. [Android & Wear OS guide](apps/wearos/README.md) |
| **Apple Watch / watchOS** | Separate encrypted-v2 developer prototype | Not feature-equivalent to the direct beta. Requires relay provisioning and Apple signing; no iPhone setup app. An unsigned archive is not an installable release. [watchOS guide](apps/watchos/README.md) |

The shared OpenDistress companion is **Android-only**. Garmin's Connect IQ
settings remain a separate route; an iPhone is not supported by the shared
companion. Supported Garmin build targets are listed in the
[beta manifest](apps/garmin/manifest-beta.xml). A compiled target is not a
physically verified device.

## Try the TEST beta

1. Choose a platform above and follow its installation guide. Check
   [published releases](https://github.com/tobomobo/opendistress/releases) or
   [native CI artifacts](https://github.com/tobomobo/opendistress/actions/workflows/native.yml)
   for available builds. A draft release is not a public download, and the
   latest repository code may be newer than a Store build.
2. Set up Grafana Cloud IRM or Pushover and its receiving app. Configure the
   webhook or keys through the supported phone setup route; the direct beta
   does not require credentials to be hardcoded into the app.
3. Save and sync. Confirm `READY TEST` on the Garmin watch or the matching setup
   acknowledgement for Wear OS. Saved on phone, sent to watch and confirmed on
   watch are different states.
4. Warn every intended recipient, agree what the test means, and follow the
   [physical drill](docs/preparation.md). With both providers configured, Grafana
   is preferred and Pushover is a fallback; retries can reach both. Test each
   route rather than assuming one working provider proves the other works.

For downloads: Garmin `.iq` files are Store-upload packages; device-specific
`.prg` files are sideload builds and do not provide the Store settings workflow
by themselves. Android `.apk` files are installable test builds; `.aab` files
are developer bundles requiring release signing and Store distribution.
Check each release's notes and checksums. Beta updates may be incompatible;
rehearse again after changing a device, configuration or receiving phone.

## Location and privacy

The direct beta sends the initial TEST without waiting for GPS. Location
acquisition starts **after provider acceptance**. Garmin requests its best
available positioning mode, can report a last-known fix with an age warning,
and attempts updates while the app remains open, for up to 24 hours. Wear OS
uses a visible location foreground service with the same maximum duration;
reboot and background-start restrictions still apply.

Optional Android phone-location assistance for Garmin is best effort, not a
prerequisite for the alert or watch GPS. Garmin Connect message delivery and
Android background restrictions can prevent it or the setup ACK from returning.
No GPS fix, an old fix, or an unknown timestamp must not be presented as a
fresh, accurate location. Indoor accuracy and phone-independent delivery are
physical test cases, not guarantees.

**The direct beta is not end-to-end encrypted to your recipients.** Android
Setup encrypts its local configuration. Wear OS provisioning is encrypted to
the watch, but Garmin provisioning passes through Garmin Connect. Selected
notification providers receive readable profile and location data; opening map
or photo links also involves their hosts. Include only information you intend
to share. See [privacy](docs/privacy.md) and the
[threat model](docs/threat-model.md).

## For developers

The repository also contains an **optional, separate encrypted protocol stack**.
It is not required for the direct TEST beta and does not make that beta E2E
encrypted. The v2 relay does not receive content keys, but still observes
metadata. Its signed HTTP 202 confirms durable intake only.

| Component | Responsibility |
| --- | --- |
| [`apps/garmin/`](apps/garmin/) | Native Monkey C watch client |
| [`apps/wearos/`](apps/wearos/) | Native Kotlin watch client and shared Android setup |
| [`apps/watchos/`](apps/watchos/) | SwiftUI encrypted-v2 watch prototype |
| [`protocol/`](protocol/) | Normative schemas, canonical signing grammar and public test vectors |
| [`relay/`](relay/) | Python/SQLite intake, durable outbox, Pushover and ntfy workers |
| [`recipient/`](recipient/) | Node reference decryption and acknowledgement tools |

The optional blind-mailbox transport has reference code, but no completed
watch-to-native-recipient enrollment flow. See [architecture](docs/architecture.md),
[roadmap](docs/roadmap.md), [receiver enrollment](docs/receiver-enrollment.md)
and [design decisions](docs/decisions.md) for the boundaries and remaining work.

### Run host checks

Python 3.11+ and Node 22:

```sh
make ci
```

Native build and signing instructions live in each platform guide. CI,
compiler, simulator, provider and physical-device results are separate evidence;
`NOT_RUN` never means pass. See [reliability](docs/reliability.md), the
[simulator matrix](tests/end-to-end/simulator-matrix.csv) and
[physical matrix](tests/end-to-end/physical-matrix.csv). Garmin strict type
checking and physical reliability remain release gates.

### Run the relay locally

Use the [relay development guide](docs/relay-development.md) for configuration,
local startup, optional mailboxes and incident resolution. This is an advanced
developer path, not a prerequisite for trying Grafana/Pushover on the watch.

## Contribute

Useful contributions include reproducible device tests, accessibility checks,
setup improvements and narrowly scoped reliability fixes. For bug reports,
include app version, device/OS version, expected behaviour and reproduction
steps. **Do not post webhook URLs, API keys, locations or emergency profiles.**
Report security issues through [SECURITY.md](SECURITY.md); contributors should
read [AGENTS.md](AGENTS.md) and the [protocol](protocol/README.md) before changing
cross-component behaviour. Naming and copy guidance live in
[branding](docs/branding.md).

## License

[MIT](LICENSE)

# Garmin platform limitations

- Connect IQ applications are written in Garmin's Monkey C and run in its
  managed virtual machine. Connect IQ exposes no Rust, native-library, WASM,
  or foreign-function target, so Rust cannot replace the on-watch client.
  Monkey C avoids raw-pointer/manual-allocation bugs, but its gradual type
  system is not Rust's borrow checker and runtime type errors remain possible.
  SDK 9.2.0 compiles the source at `-l 1` and its protocol vectors pass in the
  simulator; strict `-l 3` remains an unmet release gate.
- A public Connect IQ application cannot install a global third-party hardware
  button listener. The user must first enter an allowed foreground surface.
- After it is foregrounded, TEST mode sends on one top-button
  (`START`/`ENTER`) press. The private LIVE mode still requires a 1.5-second
  hold; release cancels and DOWN is inert. A Garmin shortcut can help only when the
  device firmware itself offers the installed app as a target; the application
  cannot create or claim that shortcut.
- The device app supports the app list, a glance, and a published complication.
  Those are activation experiments, not proof of globally available buttons.
- A watch face, if hardware testing eventually earns one, launches the device
  app; it does not send the incident itself.
- A device app cannot replace the selected system watch face. Its analog cover
  is an ordinary foreground view. It appears only after direct Grafana or
  Pushover TEST acceptance and therefore cannot be used as evidence of phone
  delivery, human acknowledgement, or response.
- Garmin's normal phone-editable app settings require a Connect IQ Store
  installation. The separate private beta application ID lets the owner test
  those settings without releasing the app publicly; a bare USB PRG sideload
  is not the end-user configuration path. The settings UI supports text and URL
  values but not a photo-file upload. The beta therefore accepts only an
  optional HTTPS photo URL for its shared emergency card. Grafana can render
  that URL as an image; Pushover can only expose it as a supplementary link
  unless the sender uploads image bytes. Garmin, each provider, and the image
  host remain in that privacy boundary.
- The public communications API does not let an app select BLE/phone versus
  watch Wi-Fi for a normal web request. The app submits immediately, then on a
  specific unavailable/timeout phone result asks Garmin once whether a saved
  internet-enabled Wi-Fi can be connected and retries the same event. Garmin
  still owns the route. A ten-second watchdog prevents a missing Wi-Fi callback
  from blocking the durable queue, and reopening the app resumes an existing
  pending event without creating a new one. Wi-Fi remains opportunistic until
  physical tests prove it on each supported watch.
- Connect IQ exposes only Bluetooth/Wi-Fi/LTE connection type and state. It
  does not expose SSID, BSSID, or a nearby-network list, so network names cannot
  be used as automatic Home/Office location hints.
- An exposed LTE connection state does not establish arbitrary Connect IQ HTTPS
  access over Garmin LTE. The app records it only as diagnostics and makes no
  LTE delivery claim.
- `Toybox.Communications.makeWebRequest()` accepts a `Dictionary` and controls
  its JSON serialization. Authentication therefore covers the fixed semantic
  signing grammar, not raw wire JSON member order or whitespace.
- Connect IQ v1 timestamps stop at `2147482747` so the 900-second expiry fits
  Garmin's signed 32-bit `Number`. Version 2 keeps the same signed 32-bit bound;
  a future protocol must change representation before 2038.
- Foreground lifecycle, activity interactions, firmware, radio state, phone
  power management, and Garmin Connect all affect latency and availability.
- GPS can be slow or unavailable indoors. A LIVE event is persisted and its
  submission is started before location is requested; unavailable fixes remain
  valid encrypted updates.
- The personal direct-GPS drill starts only after at least one direct provider
  accepts the alert, runs only while the app remains foreground, and expires
  after one hour. It sends no synthetic no-fix record. Its exact coordinates
  are plaintext to every direct provider that accepted the trigger and exposed
  in a Google Maps URL. Garmin's synchronous `Position.getInfo()` value is a
  last-known snapshot, not proof of a current fix, so it is always marked as
  possibly stale and sent with its reported age. Continuous callbacks older
  than 30 seconds receive the same warning. A small reported age still does not
  independently prove spatial freshness. Neither simulator nor mock GPS is
  accepted as physical reliability evidence.
- Foreground cadence stops at local expiry or a verified signed relay status of
  `resolved`/`expired`. Polling occurs only while the client is foregrounded, so
  lifecycle and network delays still postpone that stop.
- Pushover emergency priority creates repeated provider notifications and a
  receipt, not an automatic OS-level bypass. iOS Critical Alerts and Android
  alarm/DND behavior depend on recipient-side app and OS permissions and remain
  unproven until the locked/silent physical rows pass. ntfy priority has no
  equivalent Critical Alert guarantee here.
- Pushover has no independent rich-detail template. The beta maps the shared
  emergency card into its bounded message body and photo link, so the receiver
  OS may expose sensitive profile text on the lock screen. Grafana can keep its
  mobile push short and show structured detail only after opening the alert.
- The direct settings store either a secret Grafana formatted-webhook URL,
  private Pushover keys, or both in Garmin app properties and on the watch;
  Garmin services carry the settings sync. Grafana Cloud IRM can generate
  Important Push and let a receiver ACK in its mobile app, but a webhook `2xx`
  proves ingestion only. The watch does not query Grafana's ACK state. Grafana
  OSS OnCall is not a supported target. This beta also cannot send Grafana's
  optional service-account bearer token, so that integration switch must remain
  disabled. The separate post-acceptance direct-GPS drill sends exact
  coordinates. Neither route has an end-to-end idempotency contract here, so
  ambiguous recovery may duplicate an alert or location.

Wear OS and watchOS support native Kotlin and Swift respectively. Their source
projects are not build or device evidence: this workspace has no Android SDK,
Xcode, simulators, or watches, so hosted compilation and physical rows remain
separate gates. Local configuration is compiled into personal artifacts;
hardware-backed Keystore/Keychain enrollment is required before distributing
provisioned binaries.

These are test constraints, not promises that more software can remove them.

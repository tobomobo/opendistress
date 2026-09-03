# OpenDistress for watchOS

This is a watch-only SwiftUI application with no iPhone companion, complication,
or background location service. It sends encrypted v2 events directly over an
ephemeral HTTPS session that rejects redirects. Each immutable ciphertext
envelope and request signature is written atomically before transmission.
Location access begins only after a persisted live trigger: the app queues the
cached snapshot first, then requests one fresh fix. While the app remains in the
foreground it persists a follow-up schedule: material movement or better quality
may queue another fix at the 30-second, two-minute, then five-minute cadence,
doubled at low battery. There is no background-execution claim. Before each due
follow-up, the foreground app posts a freshly signed `/v2/status` query. Only
an exact, timely, signed `resolved` or `expired` result stops acquisition and
atomically archives that incident's queued retransmissions as unroutable;
`acknowledged`, `active`, and failures continue it. Those archived entries are
never labeled accepted. A signed HTTP
202 response proves relay durable acceptance only, never delivery or
acknowledgement. Expired pending state is never silently deleted; the button
becomes `ARCHIVE EXPIRED — RESULT UNKNOWN`, and that explicit action persists a
minimal archive marker before allowing another incident.
The exact expiry second is terminal for new location and status work and makes
the explicit archive action available. Intake retry of an already-sealed event
at that instant is a separate relay rule. Any plaintext coordinates retained
only for material-change comparison are atomically scrubbed at local expiry;
the encrypted queue and result-unknown recovery remain.

Copy `Config/Local.xcconfig.example` to `Config/Local.xcconfig` and replace all
invalid values. The local file is ignored. Authentication, encryption, and
content-MAC keys must be three distinct 32-byte hex values;
`OPENDISTRESS_TEMPLATE_ID_HEX` is the provisioned 16-byte recipient template identifier.
The endpoint must be HTTPS and end at `/v2/events`. The production loader
rejects all three published protocol-vector keys.

The personal prototype build embeds those locally supplied values in the watch
app's `Info.plist`; anyone who obtains that bundle can extract them. Do not
distribute a provisioned artifact. Keychain-backed enrollment and key rotation
are production gates, not implemented by this build-time provisioning path.

```sh
xcodebuild -project apps/watchos/OpenDistressWatch.xcodeproj \
  -scheme OpenDistressWatch \
  -destination 'platform=watchOS Simulator,name=Apple Watch Series 11 (46mm),OS=26.5' \
  test
```

The project targets watchOS 10 and uses only SwiftUI, Foundation, CryptoKit,
CommonCrypto, CoreLocation, Security, and WatchKit. Source and public-vector
tests are present, but Xcode, a simulator, and physical watch hardware were not
available in the development environment.

# Wear OS client

This is a standalone Wear OS application: it sends encrypted v2 events
directly over HTTPS and has no phone companion, tile, or complication. The app
persists each immutable ciphertext envelope before transmission. It requests
location only after a live trigger, queues a cached snapshot, then requests one
fresh fix. While the activity remains in the foreground it persists a follow-up
schedule: material movement or better quality may queue another fix at the
30-second, two-minute, then five-minute cadence, doubled at low battery. There
is no background-execution claim. Before each due follow-up, the foreground app
posts a freshly signed `/v2/status` query. Only an exact, timely, signed
`resolved` or `expired` result stops acquisition and atomically archives that
incident's queued retransmissions as unroutable; `acknowledged`, `active`, and
failures continue it. Those archived entries are never labeled accepted. A signed HTTP 202 response proves only relay
durable acceptance. Expired pending state is never silently deleted; the button
becomes `ARCHIVE EXPIRED — RESULT UNKNOWN`, and that explicit action persists a
minimal archive marker before allowing another incident.
The exact expiry second is terminal for new location and status work and makes
the explicit archive action available. Intake retry of an already-sealed event
at that instant is a separate relay rule. Any plaintext coordinates retained
only for material-change comparison are atomically scrubbed at local expiry;
the encrypted queue and result-unknown recovery remain.

The project pins Android Gradle Plugin 8.9.1, Kotlin 2.1.10, Gradle 8.11.1 in
CI, compile/target SDK 35, and Play Services Location 21.3.0. There is no
checked-in wrapper because no local Gradle installation was available to
generate the official wrapper artifacts.

Copy `panic.local.properties.example` to `panic.local.properties` and replace
every invalid value. The local file is ignored. Keys are lowercase or uppercase
hex: the authentication, encryption, and content-MAC keys are three different
32-byte values; `templateIdHex` is the provisioned 16-byte recipient template
identifier. The endpoint must be HTTPS and end at `/v2/events`. The production
loader rejects all three published protocol-vector keys.

The personal prototype build embeds those locally supplied values in Android
`BuildConfig`; anyone who obtains that APK can extract them. Do not distribute a
provisioned artifact. Hardware-backed Keystore enrollment and key rotation are
production gates, not implemented by this build-time provisioning path.

```sh
gradle --no-daemon -p apps/wearos :app:testDebugUnitTest :app:assembleDebug
```

The source and public-vector tests are present, but no Android SDK, JDK, Gradle,
emulator, or physical watch was available in the development environment.

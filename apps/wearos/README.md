# OpenDistress for Wear OS

The Android project ships two APKs under the same application ID:

- `:app` is the Wear OS / Pixel Watch app.
- `:mobile` is the Android setup app.
- `:shared` owns the canonical direct-TEST configuration and encrypted
  provisioning records.

The phone is required for initial direct-TEST setup, so the watch manifest
honestly declares `com.google.android.wearable.standalone=false`. After the
watch confirms a configuration revision, alert transmission does not depend on
the phone: the watch uses Wear OS networking over the paired-phone proxy,
Wi-Fi, or LTE as available.

## Calm-time setup on Android

This is conceptually similar to Garmin Connect IQ settings, but Wear OS has no
equivalent vendor-owned settings form for third-party apps. OpenDistress
therefore ships its own ordinary Android companion, **OpenDistress Setup**. The
generic Pixel Watch or Wear OS companion app pairs and manages the watch; it
does not store OpenDistress provider credentials.

The Android app accepts either a Grafana Cloud IRM formatted-webhook URL,
Pushover credentials, or both. It also stores the optional protected-person
profile: name, prepared message, home address, children/family information,
person description, relevant background, responder instructions, and an HTTPS
photo URL. The longer profile fields use multiline inputs.

The phone encrypts its local configuration with Android Keystore. For transfer,
the watch creates an RSA keypair with a non-exportable private key in Android
Keystore and announces only its public key through the Wearable Data Layer. The phone wraps a random
AES-256-GCM configuration key with RSA-OAEP-SHA256/MGF1-SHA1 and publishes the
encrypted envelope as an urgent persistent DataItem. The watch validates and
atomically stores it before publishing an ACK containing the exact revision and
digest. The three user-visible states remain separate: saved on phone, sent to
the Data Layer queue, and confirmed on watch. A missing DataItem never deletes the committed
watch configuration; only explicit reset changes incident state.

The intended user flow is:

1. Install OpenDistress on both the Android phone and Pixel Watch.
2. Keep the paired watch connected, then open **OpenDistress Setup** on the
   phone.
3. Enter Grafana, Pushover, or both, plus any optional emergency-card fields.
4. Choose **Save and send to watch**. This never sends an alert.
5. Wait for **Confirmed on watch … TEST route ready**. A merely saved or queued
   state is not ready.
6. Open OpenDistress on the watch and run a deliberate TEST drill.

The Data Layer setup route works with an Android phone, not an iPhone. No
provider credential is hardcoded in either APK. When no configuration exists,
the watch offers a `PHONE SETUP` action that opens this package's Play listing
on the paired Android phone; unpublished debug builds still require sideloading
both APKs.

## Watch interaction

The Tile only opens the app. It never creates or sends an event. Wear OS does
not let a third-party app globally intercept Pixel Watch crown or power buttons.

The watch surfaces follow Material 3 Expressive principles without coupling the
critical hold recognizer to a UI framework: OLED-black backgrounds, proportional
round-screen margins, semantic container colors, tabular countdown numerals,
48 dp minimum action targets, and edge-hugging progress and action shapes. The
same layouts are visually checked at the 192 dp and 227 dp Pixel Watch classes.
The Android setup app uses a Material 3 DayNight theme, dynamic color when the
phone supports it, grouped provider and emergency-card containers, outlined
secret inputs, and multiline profile fields.

The foreground control requires one uninterrupted 2.5-second hold. A symmetric
ring grows from six o'clock, the text changes while pressed, and release,
movement outside the control, or cancellation aborts. A short tap never sends.
TalkBack uses a delayed two-step confirmation, so a single accessibility click
also cannot send.

The direct TEST request is encrypted at rest and committed before networking;
the foreground service owns provider retries even after the app screen closes.
Grafana is attempted first. Pushover is tried as the independent fallback when
Grafana does not return acceptance. As soon as either route accepts, the other
pending trigger is skipped so GPS acquisition cannot be blocked by a failing
fallback.
The first successful provider response changes the watch to the analog
`TEST ACCEPTED` screen and produces a distinct haptic pattern. This wording
does not claim device delivery, human acknowledgement, or incident resolution.
Details and TEST reset are separate controls. Reset itself requires another
uninterrupted 2.5-second hold. If Pushover accepted an emergency-priority TEST,
the watch durably queues and confirms cancellation of its receipt before
clearing local state; the provider-side repeat deadline is measured from the
actual acceptance time, not incident creation. Otherwise it retains the TEST
until the retries expire.
LIVE-v2 state can never be reset through this direct-TEST path.

## Location after acceptance

Location acquisition starts only after provider acceptance. The foreground
location service first checks the fused last-known location, clearly marks its
source, device-reported age, accuracy, quality, and possible staleness, and then
requests a zero-cache high-accuracy current fix. Wear OS' fused provider may
select the watch or paired Android phone; the app cannot force or manually
merge both sources.

While the accepted TEST remains active, the service sends best-effort updates
for up to 24 hours:

- every 30 seconds for the first 5 minutes;
- every 2 minutes until 30 minutes;
- every 5 minutes afterwards;
- twice those intervals at 20% battery or below.

The foreground notification and Wear OS Ongoing Activity stay visible while
the service runs when notification permission is granted. The deliberately
bounded 15-minute provider-send/cancel phase holds a partial wake lock, while
the 24-hour GPS phase does not. A WorkManager safety net persists already
committed provider requests across process death and reboot; high-rate GPS after
a reboot resumes when the user reopens the app because Android restricts
background creation of location foreground services without all-time location
permission. Android may still stop work under exceptional system conditions,
and emulator battery/radio behavior is not physical-watch evidence.

## Existing encrypted LIVE v2

The previous build-configured encrypted v2 relay client remains available when
no phone-provisioned direct configuration exists. It keeps immutable signed
ciphertext, durable retry, signed status checks, expiry recovery, and encrypted
location semantics unchanged. Copy `opendistress.local.properties.example` to
`opendistress.local.properties` only for that developer-only path. Never
distribute a locally provisioned legacy APK because BuildConfig values can be
extracted.

## Build and test

Use JDK 17 or newer and Android SDK 36. The checked-in wrapper pins and verifies
Gradle 9.5.0:

```sh
apps/wearos/gradlew --no-daemon -p apps/wearos \
  :shared:testDebugUnitTest \
  :mobile:testDebugUnitTest \
  :app:testDebugUnitTest \
  :shared:lintDebug \
  :mobile:lintDebug \
  :app:lintDebug \
  :mobile:assembleDebug \
  :app:assembleDebug
```

The watch APK is
`apps/wearos/app/build/outputs/apk/debug/app-debug.apk`; the phone APK is
`apps/wearos/mobile/build/outputs/apk/debug/mobile-debug.apk`.

### Install a CI debug pair

Every pull request and `main` build publishes a 14-day GitHub Actions artifact
named `opendistress-pixel-watch-install-pair-<commit>`. Download and unzip that
single artifact so the phone and watch packages definitely come from the same
build and signing key. It contains:

- `OpenDistress-Android-Setup-debug.apk` for the Android phone;
- `OpenDistress-Pixel-Watch-debug.apk` for the Pixel Watch; and
- `SHA256SUMS.txt` for integrity checking.

Install the phone APK with Android Studio or `adb install -r`. Enable Developer
options and Wireless debugging on the Pixel Watch, pair/connect it with `adb`,
then install the watch APK with `adb -s <watch-address> install -r`. Debug APKs
from different CI runs must not be mixed: Wear OS Data Layer communication
requires matching package names and signing certificates on phone and watch.

CI also publishes separately named `unsigned-bundles` containing the two
release AAB compile outputs. They are evidence that release bundles compile;
they are deliberately not presented as Play-upload-ready until a protected
upload-key signing workflow is configured.

For Play Store distribution, both form-factor bundles use
`dev.opendistress.wear`, distinct version codes, and one Play listing. The user
installs the phone companion and selects the Pixel Watch as an additional
device. Google Play then serves the correct bundle to each device.

For Apple Silicon emulator testing, use the signed Wear OS 6 image
`system-images;android-36;android-wear-signed;arm64-v8a` and the
`wearos_large_round` and `wearos_small_round` profiles. Run the Keystore and
hold-interaction device tests on a watch AVD:

```sh
ANDROID_SERIAL=emulator-5554 apps/wearos/gradlew --no-daemon -p apps/wearos \
  :app:connectedDebugAndroidTest
```

The debug APK contains a shell-permission-protected state seeder for automated
round-screen interaction tests. It is absent from release builds and contains
only visibly fake TEST data. Compiler, unit, lint, instrumentation, emulator,
provider, and physical-device evidence remain separate gates.

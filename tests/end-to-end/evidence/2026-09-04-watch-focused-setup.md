# Watch-focused setup UX — local verification

Base: `6406d47b83219b4d2ee305d614e5858c1e244e48`, plus the uncommitted
`codex/watch-focused-setup` changes. No provider alert was deliberately sent.

## Passed

- `make ci`: 131 Python tests, both Node test suites and schema/syntax checks.
- Android shared/mobile/watch unit tests, debug APK assembly and mobile/watch
  debug lint, using the existing offline Gradle cache and Android Studio JDK.
- Connect IQ 9.2.0 strict-level-1 beta compilation and local `.iq` export.
  Compiler container-type warnings remain; a successful export is not a device test.
- All 10 fēnix 8 47mm runtime tests: includes legacy default-on companion
  configuration, digest-bound haptics-off storage and rejection of tampering.
  Garmin's runner exits 1 even with its explicit `passed=10, failed=0, errors=0`.
- Pixel phone emulator: first-run platform choice, Garmin selection retained
  on reopening, Garmin-only status, missing Connect service not shown synced,
  unclipped header and filled provider inputs visually inspected. Only public
  dummy Pushover values existed in this emulator.
- Separate Garmin simulator-only application ID and public dummy settings:
  Ready screen, START edge indicator and round-screen text visually inspected
  through computer use. A short click did not advance the visible Ready state.
  This temporary preview is not the exported `.iq`.

## Still unverified

- Real Garmin Connect/BLE settings delivery and returned ACK. This work adds
  honest connection/sync UI; it does not claim to fix the reported physical sync failure.
- Haptic strength, audibility and perception on a worn physical watch.
- Full-duration hardware hold and mid-hold animation capture in this UI pass.
- Final Android accordion/toggle interaction pass: Android Studio computer-use
  attachment timed out. Compilation and state/digest unit tests do not replace it.
- Provider, recipient, GPS, iOS and store-distribution checks were not run.

## Local artifacts

- `apps/wearos/mobile/build/outputs/apk/debug/mobile-debug.apk`
- `apps/wearos/app/build/outputs/apk/debug/app-debug.apk`
- `apps/garmin/bin/ux-preview/OpenDistress-TEST-0.2.0-beta.2.iq`

Both phone and watch must be updated before testing haptics-off. Old default-on
configurations remain supported. An IQ file is a Store-upload package, not a USB
sideload PRG. Nothing was uploaded, merged or released by this verification.

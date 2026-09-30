# Garmin activation UX, pending retry and offline GPS — 2026-09-29

Worktree based on `9ff355d`. No push, merge, Store upload, tag or release was
performed. No provider request was sent by any test or fixture.

## Scope

- Hold and reset: amber elapsed-time ring (warm white for reset), three
  accelerating beats at 40/68/88% with a light haptic tick each, one longer
  "stored" cue after TEST persistence (the cue LIVE already used), a closed-ring
  flash and orbiting request indicator while sending, and a TEST RESET
  confirmation. Practice mirrors the cue order and simulates acceptance later.
- Pending TEST: retries continue while the app is open (5, 5, 15, 30, then 60 s)
  with a countdown; short START resends the same immutable event. Never-sent
  requests repeat until expiry; ambiguous direct-provider results stop after
  five automatic retries.
- Post-acceptance GPS: never-sent fixes back off without spending the per-route
  budget, position callbacks no longer bypass a scheduled retry, continuous
  positioning is re-requested only after a failed start or a silent minute, and
  a pending Grafana alert is retried on the cover refresh (bounded).
- Android Setup: device events for every paired Garmin, re-send of a setup saved
  while the watch was away, "Sent · confirm on watch" state, draft errors kept
  out of watch status, precise-location and permanently-denied permission
  handling, corrected delivery and location-assist copy, and a send/reset card.

## Automated evidence

- `make ci`: PASS, 137 Python tests (42 Garmin contract tests), 8 recipient and
  4 mailbox tests, plus syntax and schema checks.
- Connect IQ SDK 9.2.0 from the pinned `connectiq-tester` image, `-l 1`: app,
  unit-test and preview builds compiled; `beta.jungle -e` built 17 of 17 device
  configurations with a throwaway key.
- Headless Linux simulator under Xvfb (`+extension GLX`,
  `LIBGL_ALWAYS_SOFTWARE=1`), structured tests: `PASSED (passed=23, failed=0,
  errors=0)` on `fenix847mm`, `instinct3solar45mm` and `venux1`. `monkeydo`
  exits 1 despite the passing result.
- Android: `:shared:testDebugUnitTest :mobile:testDebugUnitTest
  :app:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug` BUILD
  SUCCESSFUL with SDK platform 36/37 and build-tools 36.0.0. Lint: 0 errors,
  35 warnings (same count as the 2026-09-05 baseline).

## Not run

- Interactive rendering: the pinned image lacks Garmin device fonts, so the
  simulator stayed on its loading icon for this preview, the unmodified base
  preview and Garmin's `Timer` sample. No screenshot of the new screens exists.
- Instrumented Android tests and any paired Garmin Connect drill.
- Physical hold, haptic perceptibility and noise, blind hold, pending retry with
  Bluetooth loss, offline GPS delivery on reconnect, TEST RESET, provider
  delivery and real GPS. See the new NOT_RUN rows in `physical-matrix.csv`.

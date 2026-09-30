# Android companion redesign — 2026-09-29

Brand-led rework of OpenDistress Setup's programmatic UI. No provisioning,
wire, storage, permission or provider behaviour changed.

- Brand palette from `docs/branding.md` replaces Android dynamic colour: warm
  paper and graphite, signal amber for progress and readiness, red only for
  blocking warnings. Dark mode uses graphite surfaces with amber actions.
- Shared kit (`CompanionUi`, `ReadinessRingView`, `StepProgressView`): one type
  scale, hairline cards, numbered step cards, callouts, primary/tonal/text
  buttons and chevron rows. Home shows setup state as the brand ring; it is
  saved/sync state, never delivery.
- Outlined text fields from the theme fix the floating label that overlapped
  entered values. Wizard steps use a segmented progress bar; conversation words
  are shown large enough to read aloud.

## Evidence

Emulator `od_pixel` (Pixel 7 profile, Android 16 / API 36, google_apis x86_64),
synthetic values only, Garmin Connect absent:

- `CompanionScreenshotTour` renders every screen by drawing the app's own views,
  so `FLAG_SECURE` stays enabled: [before](2026-09-29-companion-before.png),
  [after, light](2026-09-29-companion-after-light.png),
  [after, dark](2026-09-29-companion-after-dark.png).
- `:mobile:connectedDebugAndroidTest`: 3 tests, 0 failures (existing setup
  wizard and preparation tests plus the tour).
- `:mobile:testDebugUnitTest :mobile:lintDebug`: pass; lint 0 errors.

Not run: a physical phone, TalkBack review, large font scale, and a paired
Garmin Connect session (the connection card shows "Garmin link unavailable").

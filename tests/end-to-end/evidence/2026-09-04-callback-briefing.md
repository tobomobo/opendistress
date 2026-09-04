# Automatic callback briefing — local verification

User explicitly requested that expected conversation words travel with the alert
and accepted provider visibility. No out-of-band recipient memory requirement.
This supersedes the phone-only word policy from the earlier wizard pass.

## Implemented boundary

- The owner generates and learns two stable words during setup, reviews the
  complete briefing, and saves/syncs. The watch sends the saved briefing
  automatically; words are not regenerated per alert.
- The response plan and expected words compile into the existing 180-character
  `responseInstructions` field. Oversized briefings are rejected, not shortened.
- Both native direct providers retain all 180 characters. Pushover previously
  clipped this field to 170; the updated format fits within its 1024-character
  overall message limit even with a maximum-size profile.
- The callback template contains the call, open word question, no-answer/wrong-
  words/doubt escalation, last-known-location qualification and no-safety-proof
  warning. The quiet alternative is also self-contained.
- TEST messages explicitly say not to contact police because of this exercise.
  No automatic calling, police dispatch, word validation or incident resolution.
- Draft v2 preserves old v1 words but resets the old out-of-band agreement flag.
  New learning and sharing confirmation are required before publishing them.

## Passed

- `make ci`: Python 131 tests; recipient 8 and mailbox 4; schema/syntax checks.
- Android shared/mobile/watch unit tests, mobile/watch debug assembly and lint.
- Pixel 10 Pro API 37 emulator: full wizard and restart using dummy credentials,
  expected words present in preview and saved Garmin message only after review,
  unpublished drafts remain separate; one test, zero failures/errors/skips.
- Native provider tests verify complete maximum-length response text, including
  expected words at the end, and the explicit TEST notice in Grafana/Pushover.
- Connect IQ 9.2 level-1 compilation and fēnix 8 47mm runtime suite:
  `passed=10, failed=0, errors=0`. The runner still exits 1 despite that result.
  Includes a 180-character briefing-tail check and exercise-warning assertion.
- Garmin export: 17/17 devices built. Existing container-type and launcher-icon
  scaling warnings remain; this is not hardware validation.

## Local builds and remaining gates

- Phone: `apps/wearos/mobile/build/outputs/apk/debug/mobile-debug.apk`
- Wear OS: `apps/wearos/app/build/outputs/apk/debug/app-debug.apk`
- Garmin: `apps/garmin/bin/briefing-preview/OpenDistress-TEST-0.2.0-beta.3.iq`

Update the watch too to remove its old Pushover truncation. The IQ is a Store
upload package, not a USB sideload PRG. Nothing was uploaded or merged.
No real provider calls, recipient notifications, physical BLE/GPS/haptic tests
or emergency calls occurred. External source review remains unapproved after
the prior safety rejection; it was not retried or claimed clean.

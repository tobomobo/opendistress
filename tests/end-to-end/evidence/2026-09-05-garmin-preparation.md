# Garmin preparation and protected clock — 2026-09-05

Follow-up to [the initial watch-feedback pass](2026-09-05-garmin-watch-feedback.md).
Local branch: `codex/garmin-watch-feedback`, based on
`afaae9cf66135c7868dd6ea8451f2969203e42b5`. No push, merge, Store upload or
versioned release was performed for this follow-up.

## Scope and source review

Frozen scope: Garmin presentation/input, independent send-free practice, optional
Android preparation guidance and evidence display, and their tests/docs. No
provider, protocol, GPS-policy, credential-storage or provisioning-wire changes.
The real trigger remains a 2.5-second hardware hold. The companion reuses the
existing saved revision, bound ACK and owner-reported drill records.

Local source review checked practice isolation, hidden/released hold cancellation,
covered navigation, shared-timer ownership, reset races, and platform/provider
evidence separation. Fixes retained the event if a request begins during reset
feedback and allowed deliberate status inspection during a GPS request.
No broader flow was added. The design pass used restrained local feedback rather
than continuous animation on the cover.

The required `/Users/dev/.agents/skills/autoreview/scripts/autoreview --mode local`
closeout remains BLOCKED. A fresh invocation was denied before process creation:
the current source diff would be sent to OpenAI's Codex review service and the
user has not explicitly approved that payload/destination. An explicit approval
question is outstanding. The denial was not bypassed, and no independent findings
or clean review result exist. The helper's dry run checked invocation metadata
only. Local checks below are not a substitute for independent review.

## Automated evidence

- `make ci`: PASS — 134 Python, 8 recipient and 4 mailbox tests, plus syntax and
  schema checks. Existing Python HTTPError cleanup ResourceWarnings remain.
- Connect IQ SDK 9.2.0 with Android Studio's bundled JDK, `-l 1`: fēnix 8
  47 mm, Venu X1 and Instinct 3 Solar 45 mm test builds compiled; each reported
  `PASSED (passed=16, failed=0, errors=0)`. As before, the runner exits 1 despite
  the passing structured result. Strict `-l 3` is not claimed.
- New runtime cases: practice needs separate holds and cannot finish hidden;
  pending offline identity survives reopening and blocks practice; covered
  navigation cannot uncover/reset; no-GPS status remains explicit; timeout
  re-covers without resetting; an in-flight request blocks reset, including one
  starting during the brief completion feedback. Hold probes move their timer
  timestamps, not physical buttons or real provider calls.
- The final BACK markers were included in the runtime suites. After the compact
  layout changes, the Instinct and fēnix suites were rerun and passed; Venu's
  earlier 16-test run predates that compact-only rendering change.
- `beta.jungle -e`: **17 OUT OF 17 DEVICES BUILT**, `BUILD SUCCESSFUL`.
  `7z t` passed for `apps/garmin/bin/OpenDistress-preparation.iq`.
- Actual fēnix TEST build `apps/garmin/bin/OpenDistress-preparation.prg`: PASS.
  An initial attempt failed from disk exhaustion while processing bitmap
  resources; retry after releasing the inactive build cache succeeded.
- Android final `:mobile:testDebugUnitTest :mobile:assembleDebug
  :mobile:assembleDebugAndroidTest :mobile:lintDebug`: BUILD SUCCESSFUL. All 21
  unit tests passed. Lint completed with zero errors and 35 warnings (including
  existing target-SDK/resource warnings and hardcoded-text/KTX suggestions).
- Final `:mobile:connectedDebugAndroidTest`: BUILD SUCCESSFUL on the existing
  Pixel 10 Pro AVD, Android 17 / API 37, arm64. Both setup-wizard and preparation
  tests passed, with zero failures/errors/skips. The latter rendered all five
  control-diagram families and traversed controls, blind practice, sport access,
  failure guidance and overview while asserting saved setup stayed unchanged.
  This is instrumented view interaction, not computer-use pointer input or a
  Garmin-phone sync/delivery test. The test refuses physical phones and an
  emulator with Garmin Connect installed.
- Earlier Android disk failures were resolved by deleting only the inactive,
  regenerable 4 GB `Pixel_10_Pro.avd/snapshots/default_boot` Quick Boot cache.
  Emulator apps/userdata were preserved. The AVD runs with `-no-snapshot`.

Final local artifact SHA-256:

```text
4a8fe62b77e161faccae72aadf8ee6954524c664f467de88acb244ca645f4b5f  OpenDistress-preparation.iq
b43429c1799ab9bf348f0b1ec9c95deb47902da0bea6457c86aed67c26202257  OpenDistress-preparation.prg
dcad0a2368148eaff5a5c9cf2f73fcaea2b712b5081f96ba665c1b43107ca7a5  mobile-debug.apk
```

## Computer-use evidence

All checks use the separate offline `simulator/preview.jungle` application,
synthetic accepted state and disabled provider/GPS entry points. No alert was
sent. Captures were inspected directly through computer use.

| Check | Observation |
| --- | --- |
| fēnix 8 47 mm / practice entry | Idle screen tap opened PRACTICE ONLY, with no-send and BACK instructions. |
| fēnix / practice sequence | Short START entered the short-press task; another short press advanced to the full-hold task. An early release remained on that task. BACK returned to ordinary Ready. |
| Venu X1 / reset options | Revealed synthetic status had a right-edge START indicator and explicit Reset options target, with no fictitious middle-left MENU key. Touch opened RESET TEST? and the warning that provider alarms may continue. |
| Venu X1 / short reset press | Short upper-right START left the confirmation intact; no reset occurred. |
| Venu X1 / cancel | Lower-right BACK returned to status, retaining the synthetic accepted event. |
| Venu X1 / protected clock | START returned status to the clean digital clock; an ordinary clock tap kept details hidden. |
| Storage interruption | Simulator initially reported it could not open its configuration. Reload after freeing only the unused regenerable Gradle API cache restored the correct Venu fixture. The stale pre-reload display is not accepted evidence. |
| Positive 2.5-second hold / touch hold | NOT_RUN with computer use: its supported input API has no timed key-down/up hold. Timer/state probes above are separate evidence. |
| Instinct Solar / ready and hold | TEST, hardware START hold duration, release-to-cancel and practice hints are readable. The symmetric hold ring follows the outside edge without crossing text. Progress state is synthetic, not a timed hardware-hold result. |
| Instinct Solar / sending | SENDING TEST, waiting-for-provider and keep-app-open instructions fit below the hardware sub-window. |
| Instinct Solar / accepted and reset | Both single- and dual-provider fixtures keep acceptance, GPS tracking ended and delivery unconfirmed visible. RESET? displays the full provider-alarm warning, separate START hold and BACK cancellation instruction. |
| Instinct Solar / clock and practice | Clean seven-segment clock/date; no emergency labels on the cover. Practice shows its no-sending warning. Short inputs advance to short-press then hold exercise; early releases do not finish the hold. |
| Android preparation screens | Three emulator-rendered captures were inspected for layout and legibility; see links below. They capture the app's own view hierarchy without disabling FLAG_SECURE. Scrollable content continues below the viewport. |
| Physical GPS, receiver delivery, haptic quietness, activity recording continuity | NOT_RUN; none is established by compilation or synthetic fixtures. |

Reset stops local TEST tracking only. Provider acceptance is not recipient
delivery, acknowledgement, incident resolution or help on the way. The practice
view cannot create any of those facts. Local packages are not a Store release,
and no new hosted CI run, push, merge or upload was performed.

## Compact-screen diagnosis and design review

The Instinct's smallest native font measured 23 pixels high. Percentage-based
TextAreas smaller than that silently omitted text. Other text crossed its
upper-right hardware sub-window. Fixed, single-line native-font slots below
that window and an outer hold ring corrected the observed render failures.

| Before | After | Why |
| --- | --- | --- |
| Missing TEST/action hints on 176 px Instinct | Explicit native-font slots and short, complete instructions | Fixed font metrics cannot be scaled by shrinking the TextArea. |
| Status text under the hardware window; delivery uncertainty omitted | Reserved top-right area, separate provider/GPS/delivery lines | Readable status must not imply recipient delivery. |
| Compact hold ring cut through text | Progress stays on the outside edge | Progress must not obscure cancellation guidance. |

An initial Instinct fixture load produced `Symbol Not Found`. A minimal render,
then the original unmodified fixture, ran successfully after recompilation and
reload. The initial loader failure was not reproducible; no root cause or
app-side crash fix is claimed.

A suspected practice release-dispatch bug was disproved using matched inputs
with the original handler: press, select, release all arrived and the short task
advanced. Further event-only probes showed automation-generated short presses,
not a reliably sustained hardware hold. The speculative handler change and all
temporary diagnostics were removed. Long-hold claims remain limited to the
native state/timestamp tests, not computer-use or physical proof.

Android captures:

- [Garmin controls](2026-09-05-android-garmin-controls.png)
- [Blind practice](2026-09-05-android-blind-practice.png)
- [Failure checks](2026-09-05-android-failure-checks.png)

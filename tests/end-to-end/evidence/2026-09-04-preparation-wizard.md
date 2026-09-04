# Android preparation wizard — local verification

Historical first pass: the phone-only words policy below was superseded by the
explicitly requested [automatic callback briefing](2026-09-04-callback-briefing.md).

Branch: `codex/watch-focused-setup`, uncommitted changes over
`6406d47b83219b4d2ee305d614e5858c1e244e48`. This extends the earlier
[watch-focused setup pass](2026-09-04-watch-focused-setup.md), not a released build.

## Scope and behavior

- Six steps replace the long settings screen: provider, response plan, optional
  profile, optional private conversation words, watch behavior, review/sync.
- Material 3 typography, fixed navigation and progress separate decisions.
- Offline two-word generation uses the public BIP39 vocabulary, not wallet
  derivation. No actual pair is automatically added to any watch/provider payload.
- Encrypted draft v3 migrates v1/v2; background provisioning updates and draft
  saves now mutate the latest state under the same lock.
- No alert transmission, provider calls, store uploads, release or merge was
  requested or performed during these checks.

## Passed

- `make ci`: 131 Python tests, 8 recipient tests, 4 mailbox tests, schema/syntax
  checks. Existing Python fixture ResourceWarnings remain non-fatal.
- Android mobile unit tests, debug assembly and lint; shared/watch unit tests,
  watch debug assembly and lint. Android Studio JDK, Gradle 9.5.0.
- `SetupDraftTest`: vocabulary checksum, distinct random pairs, field bounds,
  codec roundtrip, legacy migration, unchanged watch bytes, template limits.
- `SetupWizardInstrumentedTest`: Pixel 10 Pro API 37 emulator, no Garmin Connect,
  only dummy provider credentials. Traversed all six steps, generated words,
  entered a response plan/profile, reopened the activity, confirmed draft-only
  persistence and fresh review checkbox, saved and verified config exclusions.
  Store publish/ACK operations preserve the draft; stale revision is rejected.
- Final emulator test report: one executed test, no failures or skips. An earlier
  run skipped because the emulator guard was too narrow; fixed to check the
  observed `ranchu` hardware before claiming execution.
- Delivery and response-plan view renders inspected for readable layout, inputs,
  step progress and navigation. Dummy-only renders were made by instrumentation;
  this is not a successful Computer Use walkthrough.

## Not proved

- Android Studio Computer Use attachment failed with an AppleEvent timeout.
- The autoreview helper was blocked before execution by the approval reviewer:
  exporting the source bundle to another model service needs explicit approval.
  Local source review found and fixed the draft/provisioning lost-update seam;
  no external clean-review claim is made.
- Physical Garmin Connect/BLE sync, physical vibration, recipient interruption,
  GPS quality and real emergency response are **NOT_RUN** in this pass.
- Conversation words do not prove safety, verify identity cryptographically,
  or automatically acknowledge/resolve an incident.

## Test build

`apps/wearos/mobile/build/outputs/apk/debug/mobile-debug.apk` is the updated
Android companion. Existing watch-side fields/protocols are reused by the wizard.
The phone-only agreement is intentionally not configurable through Garmin Store
settings and does not require a new watch field or backend service.

# Garmin send replay and layout — 2026-09-05

SDK 9.2.0, `fenix847mm` simulator (CIQ 6.0.2), type checking `-l 1`.
This supplements, not replaces, the physical crash evidence in
`2026-09-05-garmin-startup-crash.md`.

## Reproduction

`simulator/replay/replay.jungle` runs the production delegate, elapsed-time hold,
event creation, queue storage, Grafana payload construction, response handler,
accepted-state persistence, clock, and reset. Only Grafana submission is replaced
with a delayed synthetic HTTP 200. Phone messaging and GPS acquisition are disabled.
The separate simulator app ID has no user configuration or real credentials.
Profile text fields are filled to their configured maximum lengths using ASCII x.

The first fixture incorrectly prepared and installed the entire profile in one
callback; that itself exhausted the watchdog. Preparation was moved into a
separate callback **before** comparing the code paths below.

Controlled counterfactual: copy current sources into a temporary directory and
replace only `DirectAlertSettings.storedConfig()` with its former uncached form
(`Storage.getValue`, then `validConfig`, on every call). The identical replay
crashes with `Watchdog Tripped Error - Code Executed Too Long`, exit 2:

`installFixture -> settingsChanged -> refreshConfiguredMode ->
hasDirectAlertConfiguration -> isConfigured -> value -> storedConfig ->
validConfig -> configDigest -> base64Url`.

With the cache enabled, that same full configuration installs successfully.
This establishes the repeated-validation watchdog fault, not the cause of every
possible IQ! error, nor the exact latest crash on the user's watch.

## Actual simulator results

Computer use opened the app, drove the fixture via the middle-left key, and
visually inspected ready, covered clock, status, reset confirmation, and reset
progress. The fixture supplies delegate press/release events 300 ms and 2800 ms
apart using real timers, without backdating `System.getTimer()`.
This is **not** a physical sustained button press or hardware haptic test.

```
REPLAY PASS: short press cancelled
REPLAY: payload built; simulated response in 4 seconds
REPLAY PASS: sustained press queued request
REPLAY PASS: persisted acceptance and empty queue
REPLAY PASS: reset returned to ready
```

The short/long/send checks passed again after reset. A simulator app reload
restored the accepted clock; status remained readable. No real provider request
or recipient alert was sent.

## Layout review

| Before | After | Why |
| --- | --- | --- |
| Side `Practice` overlaps the clipped hold instruction | Centered instruction and separate practice footer | Distinct non-overlapping reading slots |
| START and MENU labels scattered around the display | Unlabelled edge arcs; action instructions in the center | Retains physical key cues without competing text |
| Percentage-height ready TextAreas cut glyphs | Width-fitted native single-line drawing | No glyph clipping from undersized text containers |
| GPS/provider text shares space with side action labels | Provider, GPS, delivery evidence, and reset hint in separate rows | Clear hierarchy; acceptance remains distinct from delivery |

## Gates and limits

- `make ci`: PASS (134 repository tests, 8 recipient tests, 4 mailbox tests,
  contract/schema and remaining Makefile checks). Initial sandbox run could not
  open local test sockets; rerun with permission passed.
- Garmin native tests: `PASSED (passed=17, failed=0, errors=0)`. As previously
  observed, `monkeydo -t` exits 1 despite its structured PASS report.
- Maximum-profile cache test, corrupt-storage rejection, protocol vectors,
  accepted/pending restart, and input tests pass.
- Production `beta.jungle` export: 17/17 devices built; archive integrity PASS.
  Artifact: `apps/garmin/bin/OpenDistress-TEST-watch-fix.iq`.
  SHA-256: `e5a2333a1d0a594e2d1a2a2f51dbbad83a9bb64d1b7c540c78f4370a3d8f77ad`.
- Physical watch after installing this artifact: NOT_RUN.
- Actual Grafana delivery, current physical crash-log correlation, real GPS,
  phone callback replay and haptic intensity: NOT_RUN.
- No Store submission, GitHub release, push or merge performed in this follow-up.

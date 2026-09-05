# Offline send-path replay

Simulator only; never distribute this PRG. Unlike the visual preview, this
fixture retains the production send/response/persistence flow and intercepts
only the HTTP submission. It installs synthetic maximum-length profile fields
under its own application ID. GPS and companion messages are disabled.

From `apps/garmin` with the SDK/JDK on PATH:

```sh
monkeyc -f simulator/replay/replay.jungle -d fenix847mm \
  -o bin/SendReplay.prg -y private-resources/developer_key.der -l 1
monkeydo bin/SendReplay.prg fenix847mm
```

Click the middle-left UP button to run short-press cancellation followed by a
real-timer 2.8-second delegate hold. The response is a simulated HTTP 200 after
four seconds. Look for `REPLAY PASS`/`REPLAY FAIL` in the console.

After acceptance, UP reveals status, another UP opens reset confirmation, and
another runs the timed reset hold. UP can then repeat the send test. These UP
shortcuts belong **only** to this fixture; production MENU remains a long press.
The production clock/state survives stopping and rerunning the fixture.

Neither the simulated response nor its clock is evidence of actual delivery,
GPS or hardware button behavior. The release jungles do not include this folder.

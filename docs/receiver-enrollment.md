# Receiver enrollment and interruption gate

Pushover is the only implemented LIVE route with emergency repeats and a
provider receipt. The relay already sends LIVE as priority `2`, supplies
bounded `retry`/`expire` values, rejects emergency acceptance without a valid
receipt, and polls that receipt for per-recipient acknowledgement.

That is not enough to call the receiver a Critical Alert. Provider acceptance,
device transport, audible OS interruption, and human acknowledgement are
separate facts. A recipient is not enrolled for field use until all steps and
the applicable physical rows below pass on the exact phone.

## Enroll one recipient

1. Give the person an individual Pushover account/user key. Keep exactly one
   active phone on that account; this relay deliberately does not target a
   mutable Pushover device name.
2. Add that user key as its own recipient route in the private relay route
   file. Never share keys in the repository or screenshots.
3. Install and register the current Pushover app on the exact receiver phone.
4. On iPhone, enable Pushover's **Critical Alerts for high-priority** option,
   approve Apple's Critical Alerts permission, choose an intentional critical
   volume, and verify Pushover notifications are otherwise allowed.
5. On Android, enable Pushover's alarm/override behavior for high-priority
   notifications and grant the OS notification, sound, and DND/alarm access it
   requests. Exact labels vary by Android vendor and release.
6. Record phone model, OS version, Pushover version, recipient ID, route
   fingerprint, and settings state in private test evidence. Do not record the
   user key.

## Supervised emergency drill

Tell every participant this is a TEST before starting. Temporarily set
`test_emergency` to `true` in the private route configuration, restart the relay
with the same provider credentials/destination, and send a TEST event. Never
use a LIVE payload for enrollment.

For iPhone, lock the phone, turn on the mute switch, enable the recipient's
normal Focus mode, and place the app in the background. For Android, lock the
phone, enable DND, and background Pushover. In both cases record separately:

- watch recognition and relay durable acceptance;
- Pushover provider request ID and emergency receipt;
- first audible/vibration interruption time on the locked phone;
- one repeat without acknowledgement;
- deliberate in-app acknowledgement and the matching receipt evidence;
- behavior with the critical/alarm permission revoked as a negative control.

Restore `test_emergency` to its intended operational value after the drill.
Repeat the drill after replacing a phone or changing OS, Pushover, Focus/DND,
notification, sound, or battery-management settings.

For the relay-free Garmin beta POC, enter the Pushover user/group key and a
dedicated test application token in the Garmin app settings instead of changing
the relay route. One top-button press sends the same explicitly non-sensitive
emergency TEST. The watch's double haptic and analog cover record provider API
acceptance only; this beta does not poll the receipt, so acknowledgement must be
verified directly on the receiver and in Pushover during the supervised drill.
Only after that acceptance the foreground beta may send separate real-GPS
updates for up to one hour. Verify that the first high-priority location and a
later normal-priority moved location open the expected map position. This
personal POC exposes the exact coordinates to Pushover and Google Maps; use a
dedicated test account and obtain the watch owner's explicit consent. Simulator
or mock locations do not satisfy this drill.

The required rows are in
[`../tests/end-to-end/physical-matrix.csv`](../tests/end-to-end/physical-matrix.csv).
Until they say `PASS`, the product remains not emergency-ready. ntfy may remain
a secondary route, but priority `5` is not treated here as Critical Alert proof
or human acknowledgement evidence.

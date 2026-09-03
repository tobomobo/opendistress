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
the relay route. A 2.5-second top-button hold sends the same explicitly
non-sensitive emergency TEST plus any optional prepared alert message, shared
emergency-card text, and photo link. Write the prepared message and response
plan in calm conditions, using the setting prompts to specify first contact,
first action, unsafe actions to avoid, and all-clear verification. Run a TEST
with every intended responder after each material change. The card is outside
normative v1 TEST and visible to Garmin and
Pushover. Confirm
with the receiver locked that the device's preview policy is acceptable; unlike
Grafana, Pushover has no separate rich-detail template that guarantees the card
appears only after opening the app. The watch's double haptic and analog cover record provider API
acceptance only; this beta does not poll the receipt, so acknowledgement must be
verified directly on the receiver and in Pushover during the supervised drill.
Only after that acceptance the foreground beta may send separate real-GPS
updates for up to 24 hours. Verify that the first high-priority location and a
later normal-priority moved location begin with `GPS-UPDATE`, preserve the
blank-line-separated status/source/quality/age/map sections, and open the expected map
position. This personal POC exposes the exact coordinates to Pushover and
Google Maps; use a dedicated test account and obtain the watch owner's explicit consent. Simulator
or mock locations do not satisfy this drill.

Alternatively, create a **Grafana Cloud IRM** custom integration with a
formatted incoming webhook, configure its escalation chain, and paste the
secret webhook URL into the Garmin app's password-type setting. Install the
Grafana mobile app on each intended responder, enable Important Push, and test
the exact locked/DND/Focus state. Keep the mobile notification template limited
to the payload's short `title` and `message`. If the optional Garmin emergency
card is used, render its address, children/family, person-description,
background, responder-instruction, and photo-link fields only in Grafana's
opened web/detail view. Confirm on the exact receiver phone that no sensitive
card text appears on the lock screen, then confirm every intended field and the
photo render correctly after opening the alert. Record separately: webhook HTTP acceptance,
first audible/vibration interruption, deliberate in-app ACK, and the alert
timeline. The watch's cover and haptic prove only webhook ingestion and do not
show the Grafana ACK. Repeat with notification permission or Important Push
disabled as a negative control. If both Grafana and Pushover are configured,
verify each independently and verify that GPS reaches every provider that
accepted the trigger. Grafana OSS OnCall is not the supported mobile path.
The beta does not provision Grafana's optional service-account bearer token;
leave that integration toggle disabled or the webhook correctly returns 403.

The required rows are in
[`../tests/end-to-end/physical-matrix.csv`](../tests/end-to-end/physical-matrix.csv).
Until they say `PASS`, the product remains not emergency-ready. ntfy may remain
a secondary route, but priority `5` is not treated here as Critical Alert proof
or human acknowledgement evidence.

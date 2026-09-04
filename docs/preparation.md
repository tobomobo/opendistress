# Preparation and physical drills

OpenDistress uses direct Grafana Cloud IRM / Pushover delivery for the current
watch beta. Users do not need to host a relay. Recipients use the provider's
app; there is no OpenDistress recipient enrollment or QR invitation in this
flow. The direct route remains explicitly TEST-only.

In the Android companion, save the provider credentials and optional emergency
profile. Include the person wearing the watch, a response plan agreed with
recipients, home address including floor/door, dependants, and relevant medical,
language or access information in the background field. Avoid a universal
callback instruction or a fixed waiting time: agree these with your recipients
for your circumstances. The photo is an optional HTTPS link, not an upload.

Setup is encrypted locally using Android Keystore and copied to the watches.
Garmin provisioning passes through Garmin Connect. On a drill the selected
providers can read the profile and location; this is not the encrypted v2
relay protocol. Home address is explicitly distinguished from current GPS.

Open **Preparation & physical drill** to preview the saved profile and rehearse
each watch/provider route. Unsaved edits are not part of that preview. Arrange
the drill with every intended recipient, confirm the saved configuration on the
watch, trigger the 2.5-second hold there, and verify the actual receiving app:
locked-screen sound/DND behavior, profile/plan, acknowledgement, and a fresh
GPS update with a correct map. Reset the TEST and verify repetitions stop.
This preparation screen cannot transmit an alert.

All six observations are required before recording success. This is explicitly
owner-reported evidence, not automatic provider, delivery or recipient ACK
telemetry. One latest record is retained per platform/provider in the encrypted
setup store. It is bound to the saved revision; edits require a repeat. Records
older than 30 days or later than the phone's current clock require a repeat.
Device replacement or changed recipients also require a new drill; these are
not automatically detectable. There is no periodic reminder service.

With both routes configured the current watch prefers Grafana and uses Pushover
as fallback. To verify the second route, save and sync it alone, and record only
the route actually observed. Never interpret a successful Grafana drill as proof
that Pushover works.

Watch recognition, provider acceptance, recipient delivery, acknowledgement and
resolution remain separate facts. The analog cover and double haptic indicate
provider acceptance only. OpenDistress does not contact police automatically.
Simulator drills prove UI behavior, never physical GPS or receiver interruption.

Native provider ACK return, recipient QR enrollment, direct LIVE enablement,
and government integration are not implemented by the preparation screen.

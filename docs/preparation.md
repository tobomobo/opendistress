# Preparation and physical drills

OpenDistress uses direct Grafana Cloud IRM / Pushover delivery for the current
watch beta. Users do not need to host a relay. Recipients use the provider's
app; there is no OpenDistress recipient enrollment or QR invitation in this
flow. The direct route remains explicitly TEST-only.

The Android companion starts with a Garmin / Pixel Watch selection, then guides
you through six steps: delivery, response plan, optional personal information,
optional conversation words, watch behavior, and review/sync. Back/Continue keeps
an encrypted phone-only draft; only **Save and sync to watch** commits the settings
used for provisioning. Retry sync uses the last saved settings, never the draft.
An incomplete draft can be resumed after closing the app. The review confirmation
must be given again on reopening or changing the configuration.

Agree a response plan with recipients: who takes the lead, whether a callback is
safe, what happens if there is no response, and when they contact emergency
services. The quiet-response and call-first/two-word templates are editable
starting points, not universal safety advice or automated dispatch. Replacing an
existing plan requires confirmation. The existing 180-character responder field
is retained for compatibility with both watch platforms.

Profile fields are optional. Describe the person **wearing the watch**, not an
attacker. Include a home address only if useful (it is not current location),
essential dependant-care details rather than birth dates/school routines, and
relevant medical, language or access information. The photo is an optional HTTPS
link, not an upload. The final preview separates sections with blank lines and
names providers; provider-specific layout and length limits may shorten it.

## Optional conversation words

Two distinct words are generated offline with `SecureRandom` from the public
[BIP39 English vocabulary](https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt).
This reuses only its word list: two words are **not a BIP39 wallet mnemonic**,
password, or cryptographic authentication. Never use words from an actual wallet.

Learn the pair during setup, not during an incident. After explicit review/save,
the companion compiles the expected words and response instructions into the
existing `DirectConfig.responseInstructions` field. The watch stores that
briefing and automatically includes it in its initial Grafana/Pushover TEST
alert. Recipients receive the expected words and instructions together; no
out-of-band lookup or recipient memorization is required. The callback template
says to call, ask without reading the words aloud, and contact police with the
last known location if there is no answer, wrong words or doubt. It is not
automatic calling or dispatch. TEST messages explicitly say this is an exercise
and not to contact police because of the test signal.

Garmin Connect and selected providers can read the briefing, including words;
receiver notifications may expose it on a lock screen. This is intentional
privacy-relaxed direct-provider content, not a private key or encrypted v2 field.
The entire briefing must fit 180 characters; the editor shows the space left
after reserving the words, and refuses oversized input rather than truncating.
Both current watch adapters preserve all 180 characters. Older Pushover watch
builds clipped at 170; update the watch before rehearsing this setup.

Generation/replacement clears the "I can recall both words" checkbox and review
approval. Legacy phone-only agreements retain their words but do not grant this
new approval. Removal/replacement needs review and sync: until confirmed, a watch
can still send its previously saved briefing. Provider copies are not revoked by
local removal. Words are hidden on returning to their step/backgrounding; ordinary
screen capture/recents snapshots are disabled on setup, but an unlocked phone can
reveal them. The full review deliberately displays exactly what will be shared.

Correct words are **not proof of safety or absence of coercion**. Neither words
nor the local learning checkbox acknowledge, resolve or stop an incident.
There is no automatic callback, word verification, or recipient enrollment.
These are a human planning aid, not an alternative to rehearsing the response.

## Share and rehearse

Saved delivery/profile setup is encrypted locally using Android Keystore and copied to the selected watch.
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

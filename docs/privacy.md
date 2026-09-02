# Privacy

No client requests location or performs a network request before deliberate
activation. V1 TEST contains only opaque IDs, kind, sequence, creation, and
expiry. V2 encrypts its fixed LIVE template or binary location record before
Garmin, Google/Apple networking, the relay, or a notification provider sees it.

The relay necessarily observes timing, source endpoint, opaque device and route
IDs, kind, sequence, expiry, ciphertext size, provider-configuration
fingerprints, provider references, and delivery state. Pushover and ntfy receive
the compact encrypted envelope and notification timing. A trusted recipient
resolves a provisioned template or decrypts location only after verifying the
content MAC.

The relay has no analytics. It must not log bodies, signature or authorization
headers, credentials, location, or client IP addresses. The public HTTPS proxy
must be configured the same way. ntfy must use a private authenticated topic and
receives its bearer credential in an Authorization header, never a URL.
Pushover's receipt API requires its application token in an outbound HTTPS query;
the relay does not log that URL, and any egress proxy must suppress it too.
Provider-held notification copies follow the configured provider's own retention;
for example, ntfy commonly caches messages for offline subscribers. Use a private
self-hosted instance when that boundary is unacceptable.

The personal relay-free Garmin beta has one explicit, non-production exception:
its optional protected-person display name and emergency-card fields synchronize
through Garmin and are stored on the watch. The display name appears in TEST
titles sent to each selected provider. The home address, children/family
information, person description, background, responder instructions, and
optional HTTPS photo URL are sent only to Grafana in the webhook payload. A
short Grafana mobile template can keep those fields off the lock screen, but
Grafana still receives them and an image host learns when the photo URL is
retrieved.
After the clearly marked TEST alert receives valid Grafana Cloud IRM or
Pushover acceptance, a one-hour foreground GPS drill can send exact coordinates
to every direct provider that accepted the trigger and in Google Maps URLs.
This is outside TEST v1 and v2, and therefore outside the encrypted location
guarantee above. Grafana, Pushover, Garmin's settings/network path, and Google
can observe or retain data within their respective roles and policies. The
display name, emergency card, Grafana webhook URL, and Pushover credentials
also synchronize through Garmin and are stored on the watch. Local last/pending coordinate
records are scrubbed at expiry or MENU reset. Do not use this exception for
production LIVE or with anyone who has not explicitly consented.

Maximum defaults are:

| Data | Maximum retention |
|---|---:|
| TEST incident | expiry + 24 hours |
| Encrypted LIVE body | earlier of resolution + 24 hours or expiry + 24 hours |
| Encrypted location body | resolution or event expiry, and always creation + 24 hours |
| Event, classified delivery, and attempt metadata | event expiry + 24 hours |
| Blind mailbox capsule and encrypted ACK | event expiry + 24 hours |
| Raw bodies in logs | never |
| Provider response content beyond required IDs/evidence | not retained |
| IP addresses and analytics | not persisted / none |
| Backups | 7 days |

Except for the expressly consented personal direct-GPS drill and emergency card
above, contacts and coordinates never belong in URLs, logs, acknowledgements,
or bug reports. Do not put a secret, token, private image-host credential, or
live location in the card's photo URL.
Public fixtures contain only generated test material. Longer retention requires
an explicit local export rather than changing server defaults.

The optional blind-mailbox transport reduces relay-visible application
metadata further: event kind, device, incident, sequence, creation time, and
the complete v2 event are inside a fixed-size encrypted capsule. It does not
hide source IP from the ingress stack, traffic timing, fixed packet size,
random mailbox/message identifiers, outer expiry, or polling and ACK timing.
Mailbox capabilities and content keys remain outside logs and are never part of
aggregate usage statistics. Server configuration contains capability hashes
only. Central LIVE/TEST counters are intentionally absent because they would
require revealing the very event semantics this transport hides.

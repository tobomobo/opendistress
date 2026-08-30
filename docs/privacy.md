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

Maximum defaults are:

| Data | Maximum retention |
|---|---:|
| TEST incident | expiry + 24 hours |
| Encrypted LIVE body | earlier of resolution + 24 hours or expiry + 24 hours |
| Encrypted location body | resolution or event expiry, and always creation + 24 hours |
| Event, classified delivery, and attempt metadata | event expiry + 24 hours |
| Raw bodies in logs | never |
| Provider response content beyond required IDs/evidence | not retained |
| IP addresses and analytics | not persisted / none |
| Backups | 7 days |

Contacts and coordinates never belong in URLs, logs, acknowledgements, or bug
reports. Public fixtures contain only generated test material. Longer retention
requires an explicit local export rather than changing server defaults.

# Privacy

The application performs no network request and collects no data before a
deliberate trigger. Phase-1 TEST events contain only opaque IDs, an event kind,
sequence, creation time, and expiry. The relay has no analytics and persists
only the minimal attempt ledger needed to prevent duplicate provider submits:
opaque IDs, SHA-256 of the canonical request bytes, classified state,
timestamps, and the required provider request identifier. TEST ledger rows
expire after 24 hours.

The relay must not log raw bodies, signatures, credentials, location, or client
IP addresses. A deployment's HTTPS proxy must be configured to match; its
defaults are outside this repository's control. Pushover necessarily receives
the fixed TEST message, event ID, recipient key, and request timing. Phase 1
uses a dedicated Pushover account with exactly one active Android device;
provider and device retention remain outside the relay's retention promise.

Future persistence uses these maximum defaults:

| Data | Default retention |
|---|---:|
| TEST incidents | 24 hours |
| Active encrypted alert | expiry/resolution + 24 hours |
| Encrypted location | one hour after resolution, at most 24 hours |
| Delivery metadata and classified attempts | 7 days |
| Raw request bodies | never logged |
| Provider responses beyond required identifiers | not retained |
| IP addresses and analytics | not persisted / none |
| Backups | 7 days |

Contacts and coordinates never belong in URLs. Longer retention, if added,
must be an explicit local export rather than a changed server default.

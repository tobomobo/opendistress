# Companion overview and one-time setup

Scope: Android phone UI only. No provider, watch protocol or transport changes.
The separate uncommitted Garmin startup fix is not part of this UI change.

| Before | After | Why |
| --- | --- | --- |
| Reopen into setup pages | Saved setup opens Home | Setup is a one-time task |
| Setup, status and drill evidence on review page | Home, My plan, Settings and connection details | Less information at once |
| Reveal/hide and learned checkbox | Visible, editable conversation words | User can inspect their own plan normally |
| Repeat the entire wizard to edit | Section editing with review; explicit restart in Settings | Preserve configuration and unfinished drafts |

Call-first and quiet response remain explicit choices. Choosing call-first fills
the existing editable template and generates words if absent. No GART text or PDF
was copied. The existing 180-character wire limit and explicit sharing review remain.
My plan displays the saved configuration, not unsent draft fields. Generated
briefing prefixes are split only when they match our exact format, preventing
duplicate words when editing a saved configuration without a draft.

Validation: phone unit tests, debug APK, test APK and lint passed. Two emulator
instrumentation tests passed (no skips); direct instrumentation repeated with
`OK (2 tests)`. Covers draft restore, first completion, Home on reopen, visible
words, saved-vs-draft distinction, section editing and restarting setup with values
retained. Tests use synthetic credentials and refuse a physical device or an
emulator with Garmin Connect installed. No alert was sent.

Home and saved-plan views were rendered from the instrumented app and visually
inspected at Pixel 10 Pro resolution. FLAG_SECURE remains enabled; credentials
remain masked. No physical Garmin sync or recipient delivery is claimed.

External autoreview remains unperformed because of the earlier review-egress
restriction; it was not bypassed. Local source and behavioral checks are separate
from independent review and physical-device validation. No merge or publication
was performed for this UI change.

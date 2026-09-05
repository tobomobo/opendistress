# Garmin startup crash follow-up

Physical fēnix 8 47 mm, firmware 22.44, CIQ 6.0.2: user reported immediate
IQ! on opening OpenDistress TEST. Locally inspected CIQ_LOG.YML identifies
Store version 3. No raw user configuration or crashlog is committed here.

The latest out-of-bounds stack addresses map against the beta.2 export to
onPhoneMessage called from listener registration in App.initialize. Registration
now happens in onStart, after initialization; messages missing routing keys are
ignored. Exact physical callback replay is NOT_RUN; this is a targeted mitigation,
not proof of the underlying VM cause.

The watchdog stack maps through onShow -> sendPending -> Grafana initialPayload
-> profile fields -> storedConfig -> validConfig -> configDigest -> base64Url.
Previously each field read revalidated and encoded the entire configuration.
Validated storage is now cached in memory until app start, settings changes or
an incoming configuration installation. Each installation reloads durable state
before comparing revisions and validates its readback before caching it.

SDK 9.2.0, fenix847mm, beta.jungle, -l 1 -t: structured simulator result
PASSED (passed=17, failed=0, errors=0). monkeydo exits 1 despite this report.
New send-free test repeatedly builds profile payloads, checks cached identity,
then invalidates and rejects corrupted storage. Existing companion vectors and
pending-reopen tests pass. Initial run found stale cache after test storage
replacement; installation now invalidates before reading the current revision.

Physical startup after update, queued phone-message replay, delivery, GPS and
watchdog timing on hardware: NOT_RUN. No alert was intentionally sent by tests.
Old pre-rename app removal remains unperformed: MTP did not expose identifiable
installed program files. Existing watch state and logs have not been deleted.

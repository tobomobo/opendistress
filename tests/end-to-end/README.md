# Physical evidence

No row in [`physical-matrix.csv`](physical-matrix.csv) has been executed in
this workspace. `NOT_RUN` is a release blocker for the hardware behavior named
by that row; host tests cannot turn it into PASS.

For every deliberate attempt, record at least:

```text
UTC time, phase, route, firmware, SDK/app commit, watch SKU, phone/app state,
event ID, recognition time, relay-acceptance time, provider evidence,
acknowledgement evidence, final classification, notes/evidence path
```

Allowed final classifications are `relay_accepted`, `provider_accepted`,
`retryable_failure`, `configuration_failure`, `result_unknown`,
`acknowledged`, `expired`, and `not_applicable`. Never rewrite an unknown as a
failure or success. Keep screenshots/provider exports outside the repository
if they contain contact or location data; record only a redacted evidence
reference here.

Phase 1 needs 100 completed attempts across the listed phone/watch/network
conditions. Each activation route needs 200 deliberate activations plus seven
days of false-trigger observation. Wi-Fi needs a phone-powered-off run on an
already configured access point, both initially connected and initially idle.
Repeat activation and Wi-Fi rows after relevant firmware, SDK, or app-network
changes.

Do not aggregate away individual event IDs. A summary is acceptable only when
the underlying per-attempt log remains available for review.

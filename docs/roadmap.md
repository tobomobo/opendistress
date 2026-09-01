# Roadmap and gates

All ten planned software slices are represented in this tree. That does not
waive their evidence gates: source-complete, compiler-tested, simulator-tested,
provider-tested, and physically proven are different states.

| Phase | Implemented slice | Unmet evidence gate |
|---|---|---|
| 1. Foreground TEST | Garmin v1 sender plus phone-configured direct Pushover emergency TEST, fixed alert content, post-acceptance personal GPS drill, explicit outcomes | Private-beta install, physical Garmin ping/GPS, receiver drill, and 100-attempt matrix |
| 2. Durable delivery | SQLite event/outbox transaction, leases, fencing, retry/expiry, migrations | Restart/provider fault matrix on deployed relay |
| 3. Activation paths | App-list app, glance, published complication; no speculative launcher face | 200 activations per route and seven-day false-trigger wear |
| 4. Groups and acknowledgement | Recipients/routes plus provider configuration snapshotted, emergency TEST/LIVE, per-recipient Pushover receipt evidence, separate resolution | Multi-recipient provider/device tests |
| 5. Encrypted LIVE | Frozen v2 encrypt-then-MAC profile, vectors, Garmin/native senders, relay opaque forwarding, trusted recipient decoder | Personal-key provisioning and physical end-to-end decryption |
| 6. Location | Post-trigger encrypted LIVE snapshot/fresh/cadence plus bounded direct-Pushover GPS drill, persisted foreground cadence, quality/movement/battery backoff, signed LIVE incident-status stop | Real indoor/outdoor direct and LIVE GPS, lifecycle, battery, restart, and resolution-stop tests; mocks do not pass |
| 7. Wi-Fi | Garmin reports current Wi-Fi/phone availability without selecting a route | Phone-off saved-network matrix; Wi-Fi remains opportunistic |
| 8. More transports | Relay Pushover/ntfy plus bounded direct Garmin-to-Pushover TEST | Direct-TEST receipt polling and duplicate matrix; ntfy device/deduplication matrix; SMS/voice remain unclaimed without companion/SIM/provider hardware |
| 9. Native watches | Independent Wear OS Kotlin and watchOS Swift clients with durable queues, v2 crypto, location, feedback, and passing hosted builds/simulator tests | Enrollment hardening and physical watches |
| 10. Blind mailbox | Fixed-size encrypted v2 wrapper, hashed append/read/ACK capabilities, quotas, immutable dedupe, encrypted exact-capsule ACK, Node reference codec | Companion enrollment, Android receiver integration, Garmin feasibility/compiler work, deployment and physical E2E drills |

The conditional Garmin launcher face remains intentionally absent: the plan
requires stock-route measurements to justify it. Direct Android SMS likewise
is not represented by a fake adapter; it needs a companion app, runtime
permission, phone/SIM/carrier hardware, and its own physical evidence.

Exact rows and current `NOT_RUN` status are in
[`../tests/end-to-end/physical-matrix.csv`](../tests/end-to-end/physical-matrix.csv).

# Roadmap and gates

All nine planned software slices are represented in this tree. That does not
waive their evidence gates: source-complete, compiler-tested, simulator-tested,
provider-tested, and physically proven are different states.

| Phase | Implemented slice | Unmet evidence gate |
|---|---|---|
| 1. Foreground TEST | Garmin v1 sender, signed durable intake, fixed TEST notification, explicit outcomes | Physical Garmin ping and 100-attempt matrix |
| 2. Durable delivery | SQLite event/outbox transaction, leases, fencing, retry/expiry, migrations | Restart/provider fault matrix on deployed relay |
| 3. Activation paths | App-list app, glance, published complication; no speculative launcher face | 200 activations per route and seven-day false-trigger wear |
| 4. Groups and acknowledgement | Recipients/routes plus provider configuration snapshotted, emergency TEST/LIVE, per-recipient Pushover receipt evidence, separate resolution | Multi-recipient provider/device tests |
| 5. Encrypted LIVE | Frozen v2 encrypt-then-MAC profile, vectors, Garmin/native senders, relay opaque forwarding, trusted recipient decoder | Personal-key provisioning and physical end-to-end decryption |
| 6. Location | Post-trigger encrypted snapshot, fresh callback, persisted foreground cadence, quality/movement/battery backoff, signed incident-status stop | Indoor/outdoor, lifecycle, battery, and resolution-stop tests |
| 7. Wi-Fi | Garmin reports current Wi-Fi/phone availability without selecting a route | Phone-off saved-network matrix; Wi-Fi remains opportunistic |
| 8. More transports | Direct Pushover and authenticated private-topic ntfy implementations | ntfy device/deduplication matrix; SMS/voice remain unclaimed without companion/SIM/provider hardware |
| 9. Native watches | Independent Wear OS Kotlin and watchOS Swift clients with durable queues, v2 crypto, location, feedback, and native CI | Hosted builds, simulators, enrollment hardening, and physical watches |

The conditional Garmin launcher face remains intentionally absent: the plan
requires stock-route measurements to justify it. Direct Android SMS likewise
is not represented by a fake adapter; it needs a companion app, runtime
permission, phone/SIM/carrier hardware, and its own physical evidence.

Exact rows and current `NOT_RUN` status are in
[`../tests/end-to-end/physical-matrix.csv`](../tests/end-to-end/physical-matrix.csv).

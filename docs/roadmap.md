# Roadmap and gates

Work stops at the first unmet evidence gate.

1. **Foreground TEST ping (current):** canonical signed event with a 15-minute
   expiry, minimal SQLite provider-attempt ledger, one synchronous Pushover
   submission, signed success evidence, and an honest watch result. Gate: one
   physical end-to-end ping, then the recorded 100-attempt failure matrix.
2. **Durable delivery and retry:** extend the SQL state into a transactional
   outbox with restart-safe claims, retry, and expiry. No queue or cache
   service.
3. **Activation paths:** app-list, glance, and stock complication experiments.
   Create a launcher face only if those measurements justify it.
4. **Groups and acknowledgement:** recipient state remains separate from
   incident state; acknowledgement is a deliberate human action.
5. **Encrypted LIVE:** content encryption on the watch, separate rotating
   content keys, published vectors. No sensitive plaintext through Garmin.
6. **Location:** append cached/fresh fixes after the alert; never wait for GPS
   and never request it before activation.
7. **Watch Wi-Fi:** treat as opportunistic until phone-off hardware tests pass.
8. **Additional transports:** implement a second transport directly, then
   extract only the interface both implementations actually share.
9. **Wear OS and watchOS:** native apps consuming the same wire protocol.

The immediate backlog contains no empty future-platform projects, generic
watch layer, transport registry, ORM, message broker, generated SDK, or policy
DSL.

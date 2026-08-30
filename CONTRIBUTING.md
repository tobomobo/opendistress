# Contributing

Keep changes within the next unmet gate in [`docs/roadmap.md`](docs/roadmap.md).
Do not add framework, platform, transport, ORM, outbox-worker, or generic
abstraction scaffolding for a later phase. The current phase needs only its
small SQLite provider-attempt ledger.

Before opening a pull request:

1. Run `make ci`.
2. Confirm every changed runtime verifies the published canonical request and
   response bytes, and that reordered wire JSON produces the same signature.
3. Add no credentials, developer keys, production data, or hardware claims.
4. Update the relevant limitation or physical-test record when behavior changes.

Source files use SPDX identifier `MIT`. Security issues must use the private
path in [`SECURITY.md`](SECURITY.md), not a public issue.

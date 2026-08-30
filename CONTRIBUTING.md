# Contributing

Keep the protocol and evidence semantics boring. Reuse the standard library or
native platform API, implement a transport/platform directly, and extract a
shared abstraction only after two implementations prove the same seam.

Before opening a pull request:

1. Run `make ci`.
2. Run the affected native workflow or state exactly why its SDK is unavailable.
3. Verify every changed runtime against the published canonical vectors; JSON
   order/whitespace changes must not alter semantic signatures.
4. Add one focused regression for every parser, persistence, retry, crypto, or
   evidence-state change.
5. Run `git diff --check` and confirm no credentials, private config, production
   data, generated binaries, or false hardware claims were added.
6. Update the relevant limitation and physical evidence row when behavior or a
   test result changes.

Source files use SPDX identifier `MIT`. Protocol changes require synchronized
fixtures and conformance checks in every affected runtime. Security issues use
the private path in [`SECURITY.md`](SECURITY.md), not a public issue.

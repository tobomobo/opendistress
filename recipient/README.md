<!-- SPDX-License-Identifier: MIT -->

# Trusted recipient CLI

This Node 22 command verifies and decrypts one encrypted incident v2 event. It
does not make network requests. It reads content keys and LIVE template text
only from a named local configuration file and writes only the rendered LIVE
message or decoded location record to standard output.

Create the private configuration outside this repository:

```sh
cp recipient/config.example.json /private/path/recipient.json
chmod 600 /private/path/recipient.json
```

Replace every placeholder. `device_id` is one canonical 16-byte base64url ID.
Each key version contains distinct random 32-byte AES and HMAC keys in lowercase
hex. Template-map keys are the lowercase hex encoding of decrypted 16-byte
template IDs. The CLI rejects every published protocol-vector key in either
content-key role. Keep older key versions until their incidents have expired.

Decrypt an event from a file or standard input:

```sh
node recipient/recipient.js --config /private/path/recipient.json event.json
node recipient/recipient.js --config /private/path/recipient.json - < event.json
```

The command rejects a config that is not a regular `chmod 0600` file. It
strictly validates the v2 envelope, verifies the full HMAC-SHA256 tag in
constant time, and only then decrypts the single AES-256-CBC block with padding
disabled. It never accepts keys on the command line. Do not redirect decrypted
LIVE or location output to an unprotected log.

Run its stdlib-only tests with:

```sh
node --test recipient/recipient.test.js
```

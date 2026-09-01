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

## Blind mailbox reference codec

[`mailbox.js`](mailbox.js) wraps a complete validated v2 event in a fixed-size
encrypted capsule and creates/verifies an independently encrypted recipient ACK
bound to the exact capsule hash, incident, and sequence. It is the shared
reference for the future companion and Android receiver; it is not yet a
networked receiver application. Its tests prove fixed outer sizes, semantic
hiding, tamper rejection, round-trip decoding, and ACK binding:

```sh
node --test recipient/mailbox.test.js
```

Mailbox transport keys are independent of v2 content keys. The relay gets only
SHA-256 capability hashes; the private enrollment carries the raw append/read/
ACK capabilities plus separate send-encryption, send-MAC, ACK-encryption, and
ACK-MAC keys. Do not provision that enrollment through ordinary Garmin app
settings or store it on the relay.

// SPDX-License-Identifier: MIT

"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");

const MAX_SIGNED_32 = 2147483647;
const MAX_EVENT_BYTES = 16 * 1024;
const MAX_CONFIG_BYTES = 1024 * 1024;
const OUTER_KEYS = [
    "v",
    "event_id",
    "incident_id",
    "device_id",
    "kind",
    "sequence",
    "created_at",
    "expires_at",
    "payload"
];
const PAYLOAD_KEYS = ["key_version", "iv", "ciphertext", "tag"];
const CONFIG_KEYS = ["device_id", "keys", "templates"];
const CONTENT_KEY_KEYS = ["enc_key_hex", "mac_key_hex"];
const PUBLIC_VECTOR_KEY_HEX = new Set([
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
    "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
    "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
]);
const QUALITY_NAMES = ["unavailable", "last-known", "poor", "usable", "good"];
const SOURCE_NAMES = ["snapshot", "position callback"];

function parseStrictJson(text, label = "JSON") {
    let offset = 0;

    function fail(message) {
        throw new Error(`invalid ${label}: ${message}`);
    }

    function skipWhitespace() {
        while (/[ \t\r\n]/.test(text[offset] || "")) {
            offset += 1;
        }
    }

    function parseString() {
        const start = offset;
        offset += 1;
        while (offset < text.length) {
            const character = text[offset];
            if (character === "\"") {
                offset += 1;
                try {
                    return JSON.parse(text.slice(start, offset));
                } catch {
                    fail("invalid string");
                }
            }
            if (character === "\\") {
                offset += 2;
            } else {
                offset += 1;
            }
        }
        fail("unterminated string");
    }

    function parseInteger() {
        const start = offset;
        if (text[offset] === "0") {
            offset += 1;
        } else if (/[1-9]/.test(text[offset] || "")) {
            while (/[0-9]/.test(text[offset] || "")) {
                offset += 1;
            }
        } else {
            fail("numbers must be non-negative integer tokens");
        }
        if (/[.eE+\-]/.test(text[offset] || "")) {
            fail("numbers must be non-negative integer tokens");
        }
        const value = Number(text.slice(start, offset));
        if (!Number.isSafeInteger(value)) {
            fail("integer is outside the safe range");
        }
        return value;
    }

    function parseArray() {
        const value = [];
        offset += 1;
        skipWhitespace();
        if (text[offset] === "]") {
            offset += 1;
            return value;
        }
        while (true) {
            value.push(parseValue());
            skipWhitespace();
            if (text[offset] === "]") {
                offset += 1;
                return value;
            }
            if (text[offset] !== ",") {
                fail("expected ',' or ']'");
            }
            offset += 1;
            skipWhitespace();
        }
    }

    function parseObject() {
        const value = Object.create(null);
        offset += 1;
        skipWhitespace();
        if (text[offset] === "}") {
            offset += 1;
            return value;
        }
        while (true) {
            if (text[offset] !== "\"") {
                fail("object key must be a string");
            }
            const key = parseString();
            if (Object.hasOwn(value, key)) {
                fail("duplicate object member");
            }
            skipWhitespace();
            if (text[offset] !== ":") {
                fail("expected ':'");
            }
            offset += 1;
            value[key] = parseValue();
            skipWhitespace();
            if (text[offset] === "}") {
                offset += 1;
                return value;
            }
            if (text[offset] !== ",") {
                fail("expected ',' or '}'");
            }
            offset += 1;
            skipWhitespace();
        }
    }

    function parseLiteral(token, value) {
        if (text.slice(offset, offset + token.length) !== token) {
            fail("invalid literal");
        }
        offset += token.length;
        return value;
    }

    function parseValue() {
        skipWhitespace();
        const character = text[offset];
        if (character === "{") {
            return parseObject();
        }
        if (character === "[") {
            return parseArray();
        }
        if (character === "\"") {
            return parseString();
        }
        if (character === "t") {
            return parseLiteral("true", true);
        }
        if (character === "f") {
            return parseLiteral("false", false);
        }
        if (character === "n") {
            return parseLiteral("null", null);
        }
        return parseInteger();
    }

    if (typeof text !== "string") {
        fail("input is not text");
    }
    const value = parseValue();
    skipWhitespace();
    if (offset !== text.length) {
        fail("trailing data or unsupported number token");
    }
    return value;
}

function assertObject(value, label) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(`${label} must be an object`);
    }
}

function assertExactKeys(value, expected, label) {
    assertObject(value, label);
    const actual = Object.keys(value).sort();
    const wanted = [...expected].sort();
    if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
        throw new Error(`${label} has missing or unknown members`);
    }
}

function assertInteger(value, minimum, label) {
    if (!Number.isInteger(value) || value < minimum || value > MAX_SIGNED_32) {
        throw new Error(`${label} must be a signed 32-bit integer`);
    }
}

function decodeBase64Url(value, byteLength, label) {
    if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
        throw new Error(`${label} is not canonical base64url`);
    }
    const decoded = Buffer.from(value, "base64url");
    if (decoded.length !== byteLength || decoded.toString("base64url") !== value) {
        throw new Error(`${label} is not canonical base64url`);
    }
    return decoded;
}

function decodeHex(value, byteLength, label) {
    if (typeof value !== "string"
        || value.length !== byteLength * 2
        || !/^[0-9a-f]+$/.test(value)) {
        throw new Error(`${label} must be lowercase hexadecimal`);
    }
    return Buffer.from(value, "hex");
}

function validateEvent(event) {
    assertExactKeys(event, OUTER_KEYS, "event");
    if (event.v !== 2) {
        throw new Error("event.v must be 2");
    }
    decodeBase64Url(event.event_id, 16, "event.event_id");
    decodeBase64Url(event.incident_id, 16, "event.incident_id");
    decodeBase64Url(event.device_id, 16, "event.device_id");
    if (event.kind !== "live.triggered" && event.kind !== "location.updated") {
        throw new Error("event.kind is unsupported");
    }
    assertInteger(event.sequence, 0, "event.sequence");
    assertInteger(event.created_at, 0, "event.created_at");
    assertInteger(event.expires_at, 0, "event.expires_at");
    const lifetime = event.expires_at - event.created_at;
    if (lifetime < 1 || lifetime > 86400) {
        throw new Error("event lifetime must be from 1 through 86400 seconds");
    }
    if (event.kind === "live.triggered"
        && (event.sequence !== 0 || event.incident_id !== event.event_id)) {
        throw new Error("live trigger incident identity or sequence is invalid");
    }
    if (event.kind === "location.updated"
        && (event.sequence < 1 || event.incident_id === event.event_id)) {
        throw new Error("location event identity or sequence is invalid");
    }

    assertExactKeys(event.payload, PAYLOAD_KEYS, "event.payload");
    assertInteger(event.payload.key_version, 1, "event.payload.key_version");
    decodeBase64Url(event.payload.iv, 16, "event.payload.iv");
    decodeBase64Url(event.payload.ciphertext, 16, "event.payload.ciphertext");
    decodeBase64Url(event.payload.tag, 32, "event.payload.tag");
    return event;
}

function canonicalContent(event) {
    return [
        "opendistress.content.v2",
        "v=2",
        `event_id=${event.event_id}`,
        `incident_id=${event.incident_id}`,
        `device_id=${event.device_id}`,
        `kind=${event.kind}`,
        `sequence=${event.sequence}`,
        `created_at=${event.created_at}`,
        `expires_at=${event.expires_at}`,
        `payload.key_version=${event.payload.key_version}`,
        `payload.iv=${event.payload.iv}`,
        `payload.ciphertext=${event.payload.ciphertext}`,
        ""
    ].join("\n");
}

function validateConfig(raw) {
    assertExactKeys(raw, CONFIG_KEYS, "config");
    decodeBase64Url(raw.device_id, 16, "config.device_id");
    assertObject(raw.keys, "config.keys");
    assertObject(raw.templates, "config.templates");

    const keys = new Map();
    for (const [versionText, bundle] of Object.entries(raw.keys)) {
        if (!/^[1-9][0-9]*$/.test(versionText)) {
            throw new Error("config key version is invalid");
        }
        const version = Number(versionText);
        assertInteger(version, 1, "config key version");
        assertExactKeys(bundle, CONTENT_KEY_KEYS, "config content key bundle");
        const encKey = decodeHex(bundle.enc_key_hex, 32, "config encryption key");
        const macKey = decodeHex(bundle.mac_key_hex, 32, "config MAC key");
        if (encKey.equals(macKey)) {
            throw new Error("config encryption and MAC keys must differ");
        }
        keys.set(version, { encKey, macKey });
    }
    if (keys.size === 0) {
        throw new Error("config must contain a content key bundle");
    }

    const templates = new Map();
    for (const [templateId, message] of Object.entries(raw.templates)) {
        decodeHex(templateId, 16, "config template ID");
        if (typeof message !== "string" || message.length === 0 || message.length > 4096) {
            throw new Error("config template text is invalid");
        }
        templates.set(templateId, message);
    }
    return { deviceId: raw.device_id, keys, templates };
}

function readLimited(source, maximum, label) {
    const ownsDescriptor = typeof source !== "number";
    const descriptor = ownsDescriptor
        ? fs.openSync(source, fs.constants.O_RDONLY)
        : source;
    const chunks = [];
    const buffer = Buffer.alloc(Math.min(8192, maximum + 1));
    let total = 0;
    try {
        while (total <= maximum) {
            const count = fs.readSync(
                descriptor,
                buffer,
                0,
                Math.min(buffer.length, maximum + 1 - total),
                null
            );
            if (count === 0) {
                break;
            }
            chunks.push(Buffer.from(buffer.subarray(0, count)));
            total += count;
        }
        if (total > maximum) {
            throw new Error(`${label} is too large`);
        }
        return Buffer.concat(chunks, total).toString("utf8");
    } finally {
        if (ownsDescriptor) {
            fs.closeSync(descriptor);
        }
    }
}

function loadConfig(path) {
    if (!path || path === "-") {
        throw new Error("config must be a named local file");
    }
    const flags = fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW || 0);
    const descriptor = fs.openSync(path, flags);
    try {
        const status = fs.fstatSync(descriptor);
        if (!status.isFile() || (status.mode & 0o777) !== 0o600) {
            throw new Error("config must be a regular chmod-0600 file");
        }
        const config = validateConfig(parseStrictJson(
            readLimited(descriptor, MAX_CONFIG_BYTES, "config"),
            "config JSON"
        ));
        for (const { encKey, macKey } of config.keys.values()) {
            if (PUBLIC_VECTOR_KEY_HEX.has(encKey.toString("hex"))
                || PUBLIC_VECTOR_KEY_HEX.has(macKey.toString("hex"))) {
                throw new Error("published protocol fixture keys are not valid provisioning");
            }
        }
        return config;
    } finally {
        fs.closeSync(descriptor);
    }
}

function decryptEvent(event, config) {
    validateEvent(event);
    if (event.device_id !== config.deviceId) {
        throw new Error("event device is not configured");
    }
    const bundle = config.keys.get(event.payload.key_version);
    if (!bundle) {
        throw new Error("event content key version is not configured");
    }

    const actualTag = decodeBase64Url(event.payload.tag, 32, "event.payload.tag");
    const expectedTag = crypto.createHmac("sha256", bundle.macKey)
        .update(canonicalContent(event), "utf8")
        .digest();
    if (!crypto.timingSafeEqual(actualTag, expectedTag)) {
        throw new Error("event content authentication failed");
    }

    const decipher = crypto.createDecipheriv(
        "aes-256-cbc",
        bundle.encKey,
        decodeBase64Url(event.payload.iv, 16, "event.payload.iv")
    );
    decipher.setAutoPadding(false);
    const plaintext = Buffer.concat([
        decipher.update(decodeBase64Url(
            event.payload.ciphertext,
            16,
            "event.payload.ciphertext"
        )),
        decipher.final()
    ]);
    if (plaintext.length !== 16) {
        throw new Error("decrypted content has the wrong size");
    }
    return plaintext;
}

function formatE7(value) {
    const sign = value < 0 ? "-" : "";
    const absolute = Math.abs(value);
    return `${sign}${Math.floor(absolute / 10000000)}.${String(absolute % 10000000).padStart(7, "0")}`;
}

function renderLocation(event, plaintext) {
    if (plaintext[0] !== 1 || plaintext[1] !== 2) {
        throw new Error("location record version or type is invalid");
    }
    const captureAt = plaintext.readUInt32BE(2);
    const latitudeE7 = plaintext.readInt32BE(6);
    const longitudeE7 = plaintext.readInt32BE(10);
    const quality = plaintext[14];
    const source = plaintext[15];
    if (quality > 4 || source > 1) {
        throw new Error("location quality or source is invalid");
    }
    if (quality === 0) {
        if (captureAt !== 0 || latitudeE7 !== 0 || longitudeE7 !== 0) {
            throw new Error("unavailable location contains coordinates or time");
        }
        return [
            "Location: unavailable",
            `Quality: ${QUALITY_NAMES[quality]}`,
            `Source: ${SOURCE_NAMES[source]}`
        ].join("\n");
    }
    if (captureAt === 0
        || captureAt > event.created_at
        || latitudeE7 < -900000000
        || latitudeE7 > 900000000
        || longitudeE7 < -1800000000
        || longitudeE7 > 1800000000) {
        throw new Error("location coordinates or capture time are invalid");
    }
    return [
        `Location: ${formatE7(latitudeE7)}, ${formatE7(longitudeE7)}`,
        `Captured: ${captureAt}`,
        `Age: ${event.created_at - captureAt} seconds`,
        `Quality: ${QUALITY_NAMES[quality]}`,
        `Source: ${SOURCE_NAMES[source]}`
    ].join("\n");
}

function renderEvent(event, config) {
    const plaintext = decryptEvent(event, config);
    if (event.kind === "live.triggered") {
        const message = config.templates.get(plaintext.toString("hex"));
        if (!message) {
            throw new Error("LIVE template is not configured");
        }
        return message;
    }
    return renderLocation(event, plaintext);
}

function usage() {
    return "Usage: node recipient/recipient.js --config CONFIG.json [EVENT.json|-]";
}

function main(argv = process.argv.slice(2), write = process.stdout.write.bind(process.stdout)) {
    if (argv.length === 1 && argv[0] === "--help") {
        write(`${usage()}\n`);
        return;
    }
    if (argv.length < 2 || argv.length > 3 || argv[0] !== "--config") {
        throw new Error(usage());
    }
    const config = loadConfig(argv[1]);
    const eventSource = argv[2] && argv[2] !== "-" ? argv[2] : 0;
    const event = parseStrictJson(
        readLimited(eventSource, MAX_EVENT_BYTES, "event"),
        "event JSON"
    );
    write(`${renderEvent(event, config)}\n`);
}

if (require.main === module) {
    try {
        main();
    } catch (error) {
        process.stderr.write(`recipient error: ${error.message}\n`);
        process.exitCode = 1;
    }
}

module.exports = {
    canonicalContent,
    decryptEvent,
    loadConfig,
    main,
    parseStrictJson,
    renderEvent,
    validateConfig,
    validateEvent
};

// SPDX-License-Identifier: MIT

"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
    canonicalContent,
    loadConfig,
    main,
    parseStrictJson,
    renderEvent,
    validateConfig,
    validateEvent
} = require("./recipient.js");

const ROOT = path.resolve(__dirname, "..");
const LIVE_PATH = path.join(ROOT, "protocol", "fixtures", "live-trigger-v2.json");
const LOCATION_PATH = path.join(ROOT, "protocol", "fixtures", "location-updated-v2.json");
const VECTOR_PATH = path.join(ROOT, "protocol", "fixtures", "encryption-v2.txt");

function parseVectors() {
    const result = { global: Object.create(null) };
    let section = result.global;
    for (const line of fs.readFileSync(VECTOR_PATH, "utf8").split("\n")) {
        if (!line || line.startsWith("#")) {
            continue;
        }
        if (line.startsWith("[") && line.endsWith("]")) {
            const name = line.slice(1, -1);
            section = Object.create(null);
            result[name] = section;
            continue;
        }
        const separator = line.indexOf("=");
        section[line.slice(0, separator)] = line.slice(separator + 1);
    }
    return result;
}

const VECTORS = parseVectors();
const LIVE_TEXT = "Call the trusted contacts and follow the private response plan.";

function rawConfig() {
    return {
        device_id: VECTORS.global.device_id,
        keys: {
            [VECTORS.global.key_version]: {
                enc_key_hex: VECTORS.global.enc_key_hex,
                mac_key_hex: VECTORS.global.mac_key_hex
            }
        },
        templates: {
            [VECTORS["live.triggered"].template_id_hex]: LIVE_TEXT
        }
    };
}

function fixture(file) {
    return parseStrictJson(fs.readFileSync(file, "utf8"), "fixture JSON");
}

function clone(value) {
    return structuredClone(value);
}

function reseal(event, plaintext, encKeyHex = VECTORS.global.enc_key_hex,
    macKeyHex = VECTORS.global.mac_key_hex) {
    const encKey = Buffer.from(encKeyHex, "hex");
    const macKey = Buffer.from(macKeyHex, "hex");
    const iv = Buffer.from(event.payload.iv, "base64url");
    const cipher = crypto.createCipheriv("aes-256-cbc", encKey, iv);
    cipher.setAutoPadding(false);
    event.payload.ciphertext = Buffer.concat([
        cipher.update(plaintext),
        cipher.final()
    ]).toString("base64url");
    event.payload.tag = crypto.createHmac("sha256", macKey)
        .update(canonicalContent(event), "utf8")
        .digest("base64url");
    return event;
}

test("published content bytes decrypt and render in both v2 event kinds", () => {
    const config = validateConfig(rawConfig());
    const live = fixture(LIVE_PATH);
    const location = fixture(LOCATION_PATH);

    assert.equal(
        Buffer.from(canonicalContent(live), "utf8").toString("hex"),
        VECTORS["live.triggered"].canonical_content_hex
    );
    assert.equal(
        Buffer.from(canonicalContent(location), "utf8").toString("hex"),
        VECTORS["location.updated"].canonical_content_hex
    );
    assert.equal(renderEvent(live, config), LIVE_TEXT);
    assert.equal(renderEvent(location, config), [
        "Location: 12.3456789, -45.6789012",
        "Captured: 1788105650",
        "Age: 10 seconds",
        "Quality: good",
        "Source: position callback"
    ].join("\n"));
});

test("content tampering and the wrong MAC key fail before decryption", () => {
    const config = validateConfig(rawConfig());
    const changedCiphertext = fixture(LIVE_PATH);
    changedCiphertext.payload.ciphertext = `9${changedCiphertext.payload.ciphertext.slice(1)}`;
    assert.throws(
        () => renderEvent(changedCiphertext, config),
        /content authentication failed/
    );

    const changedMetadata = fixture(LOCATION_PATH);
    changedMetadata.expires_at += 1;
    assert.throws(
        () => renderEvent(changedMetadata, config),
        /content authentication failed/
    );

    const wrong = rawConfig();
    wrong.keys["1"].mac_key_hex = "60".repeat(32);
    assert.throws(
        () => renderEvent(fixture(LIVE_PATH), validateConfig(wrong)),
        /content authentication failed/
    );
});

test("strict JSON and envelope validation reject ambiguous v2 input", () => {
    assert.throws(
        () => parseStrictJson('{"v":2,"v":2}', "event JSON"),
        /duplicate object member/
    );
    assert.throws(
        () => parseStrictJson('{"v":2e0}', "event JSON"),
        /non-negative integer tokens/
    );

    const unknown = fixture(LIVE_PATH);
    unknown.extra = true;
    assert.throws(() => validateEvent(unknown), /missing or unknown members/);

    const noncanonical = fixture(LIVE_PATH);
    noncanonical.event_id += "=";
    assert.throws(() => validateEvent(noncanonical), /not canonical base64url/);

    const wrongSequence = fixture(LOCATION_PATH);
    wrongSequence.sequence = 0;
    assert.throws(() => validateEvent(wrongSequence), /identity or sequence/);

    for (const lifetime of [0, 86401]) {
        const invalidLifetime = fixture(LIVE_PATH);
        invalidLifetime.expires_at = invalidLifetime.created_at + lifetime;
        assert.throws(() => validateEvent(invalidLifetime), /event lifetime/);
    }
});

test("authenticated malformed location records are never rendered", () => {
    const config = validateConfig(rawConfig());
    const original = Buffer.from(VECTORS["location.updated"].plaintext_hex, "hex");
    const invalid = [];

    const badVersion = Buffer.from(original);
    badVersion[0] = 2;
    invalid.push(badVersion);

    const badQuality = Buffer.from(original);
    badQuality[14] = 5;
    invalid.push(badQuality);

    const falseUnavailable = Buffer.from(original);
    falseUnavailable[14] = 0;
    invalid.push(falseUnavailable);

    const future = Buffer.from(original);
    future.writeUInt32BE(Number(VECTORS["location.updated"].created_at) + 1, 2);
    invalid.push(future);

    for (const plaintext of invalid) {
        const event = reseal(fixture(LOCATION_PATH), plaintext);
        assert.throws(() => renderEvent(event, config), /location/i);
    }
});

test("authenticated unavailable location never renders zero coordinates", () => {
    const plaintext = Buffer.alloc(16);
    plaintext[0] = 1;
    plaintext[1] = 2;
    const event = reseal(fixture(LOCATION_PATH), plaintext);
    assert.equal(renderEvent(event, validateConfig(rawConfig())), [
        "Location: unavailable",
        "Quality: unavailable",
        "Source: snapshot"
    ].join("\n"));
});

test("an authenticated but unknown LIVE template fails closed", () => {
    const raw = rawConfig();
    raw.templates = {};
    assert.throws(
        () => renderEvent(fixture(LIVE_PATH), validateConfig(raw)),
        /template is not configured/
    );
});

test("CLI reads named files, rejects public keys, and enforces chmod 0600 config", (context) => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "opendistress-recipient-"));
    context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const configPath = path.join(directory, "recipient.json");
    const eventPath = path.join(directory, "event.json");
    const publicConfig = rawConfig();
    fs.writeFileSync(configPath, JSON.stringify(publicConfig), { mode: 0o600 });
    fs.chmodSync(configPath, 0o600);
    assert.throws(() => loadConfig(configPath), /published protocol fixture keys/);

    const privateConfig = rawConfig();
    privateConfig.keys["1"].enc_key_hex = "61".repeat(32);
    privateConfig.keys["1"].mac_key_hex = "62".repeat(32);
    const event = reseal(
        fixture(LIVE_PATH),
        Buffer.from(VECTORS["live.triggered"].plaintext_hex, "hex"),
        privateConfig.keys["1"].enc_key_hex,
        privateConfig.keys["1"].mac_key_hex
    );
    fs.writeFileSync(configPath, JSON.stringify(privateConfig), { mode: 0o600 });
    fs.writeFileSync(eventPath, JSON.stringify(event));

    assert.equal(loadConfig(configPath).deviceId, VECTORS.global.device_id);
    let output = "";
    main(["--config", configPath, eventPath], (text) => {
        output += text;
    });
    assert.equal(output, `${LIVE_TEXT}\n`);

    fs.chmodSync(configPath, 0o644);
    assert.throws(() => loadConfig(configPath), /chmod-0600/);
});

test("CLI rejects an oversized event before reading beyond its bound", (context) => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "opendistress-recipient-size-"));
    context.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    const configPath = path.join(directory, "recipient.json");
    const eventPath = path.join(directory, "oversized.json");
    const config = rawConfig();
    config.keys["1"].enc_key_hex = "61".repeat(32);
    config.keys["1"].mac_key_hex = "62".repeat(32);
    fs.writeFileSync(configPath, JSON.stringify(config), { mode: 0o600 });
    fs.chmodSync(configPath, 0o600);
    fs.writeFileSync(eventPath, Buffer.alloc(16 * 1024 + 1, 0x20));

    assert.throws(
        () => main(["--config", configPath, eventPath], () => {}),
        /event is too large/
    );
});

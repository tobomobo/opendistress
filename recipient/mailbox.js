// SPDX-License-Identifier: MIT

"use strict";

const crypto = require("node:crypto");
const {
    parseStrictJson,
    validateEvent
} = require("./recipient.js");

const MESSAGE_PLAINTEXT_BYTES = 512;
const ACK_PLAINTEXT_BYTES = 256;
const MESSAGE_MAGIC = Buffer.from("SPBM", "ascii");
const ACK_MAGIC = Buffer.from("SPBA", "ascii");
const OUTER_KEYS = ["v", "mailbox_id", "message_id", "expires_at", "payload"];
const ACK_OUTER_KEYS = ["v", "message_id", "capsule_sha256", "payload"];
const PAYLOAD_KEYS = ["iv", "ciphertext", "tag"];
const ACK_INNER_KEYS = [
    "v",
    "incident_id",
    "sequence",
    "message_id",
    "capsule_sha256",
    "acknowledged_at"
];
const CRYPTO_CONFIG_KEYS = [
    "mailbox_id",
    "send_enc_key_hex",
    "send_mac_key_hex",
    "ack_enc_key_hex",
    "ack_mac_key_hex"
];
const ENROLLMENT_KEYS = [
    "v",
    "mailbox_id",
    "append_cap",
    "read_cap",
    "ack_cap",
    "send_enc_key_hex",
    "send_mac_key_hex",
    "ack_enc_key_hex",
    "ack_mac_key_hex"
];

function assertObject(value, label) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(`${label} must be an object`);
    }
}

function assertExactKeys(value, expected, label) {
    assertObject(value, label);
    const actual = Object.keys(value).sort();
    const wanted = [...expected].sort();
    if (actual.length !== wanted.length
        || actual.some((key, index) => key !== wanted[index])) {
        throw new Error(`${label} has missing or unknown members`);
    }
}

function decodeBase64Url(value, size, label) {
    if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) {
        throw new Error(`${label} is not canonical base64url`);
    }
    const decoded = Buffer.from(value, "base64url");
    if (decoded.length !== size || decoded.toString("base64url") !== value) {
        throw new Error(`${label} is not canonical base64url`);
    }
    return decoded;
}

function decodeHex(value, size, label) {
    if (typeof value !== "string"
        || value.length !== size * 2
        || !/^[0-9a-f]+$/.test(value)) {
        throw new Error(`${label} must be lowercase hexadecimal`);
    }
    return Buffer.from(value, "hex");
}

function keys(config) {
    assertObject(config, "mailbox crypto config");
    const isEnrollment = Object.hasOwn(config, "v");
    assertExactKeys(
        config,
        isEnrollment ? ENROLLMENT_KEYS : CRYPTO_CONFIG_KEYS,
        "mailbox crypto config"
    );
    if (isEnrollment) {
        if (config.v !== 1) {
            throw new Error("mailbox enrollment version is invalid");
        }
        decodeBase64Url(config.append_cap, 32, "mailbox append capability");
        decodeBase64Url(config.read_cap, 32, "mailbox read capability");
        decodeBase64Url(config.ack_cap, 32, "mailbox ACK capability");
    }
    decodeBase64Url(config.mailbox_id, 16, "mailbox crypto config mailbox_id");
    const result = {
        mailboxId: config.mailbox_id,
        sendEnc: decodeHex(config.send_enc_key_hex, 32, "mailbox send encryption key"),
        sendMac: decodeHex(config.send_mac_key_hex, 32, "mailbox send MAC key"),
        ackEnc: decodeHex(config.ack_enc_key_hex, 32, "mailbox ACK encryption key"),
        ackMac: decodeHex(config.ack_mac_key_hex, 32, "mailbox ACK MAC key")
    };
    const unique = new Set([
        result.sendEnc.toString("hex"),
        result.sendMac.toString("hex"),
        result.ackEnc.toString("hex"),
        result.ackMac.toString("hex")
    ]);
    if (unique.size !== 4) {
        throw new Error("mailbox encryption and MAC keys must be distinct");
    }
    return result;
}

function canonicalInnerEvent(event) {
    validateEvent(event);
    return JSON.stringify({
        v: event.v,
        event_id: event.event_id,
        incident_id: event.incident_id,
        device_id: event.device_id,
        kind: event.kind,
        sequence: event.sequence,
        created_at: event.created_at,
        expires_at: event.expires_at,
        payload: {
            key_version: event.payload.key_version,
            iv: event.payload.iv,
            ciphertext: event.payload.ciphertext,
            tag: event.payload.tag
        }
    });
}

function canonicalMessageMac(message) {
    return [
        "spb.mailbox.content.v1",
        "v=1",
        `mailbox_id=${message.mailbox_id}`,
        `message_id=${message.message_id}`,
        `expires_at=${message.expires_at}`,
        `payload.iv=${message.payload.iv}`,
        `payload.ciphertext=${message.payload.ciphertext}`,
        ""
    ].join("\n");
}

function canonicalMessage(message) {
    return [
        "spb.mailbox.message.v1",
        "v=1",
        `mailbox_id=${message.mailbox_id}`,
        `message_id=${message.message_id}`,
        `expires_at=${message.expires_at}`,
        `payload.iv=${message.payload.iv}`,
        `payload.ciphertext=${message.payload.ciphertext}`,
        `payload.tag=${message.payload.tag}`,
        ""
    ].join("\n");
}

function canonicalAckMac(ack) {
    return [
        "spb.mailbox.ack-content.v1",
        "v=1",
        `message_id=${ack.message_id}`,
        `capsule_sha256=${ack.capsule_sha256}`,
        `payload.iv=${ack.payload.iv}`,
        `payload.ciphertext=${ack.payload.ciphertext}`,
        ""
    ].join("\n");
}

function canonicalAck(ack) {
    return [
        "spb.mailbox.ack.v1",
        "v=1",
        `message_id=${ack.message_id}`,
        `capsule_sha256=${ack.capsule_sha256}`,
        `payload.iv=${ack.payload.iv}`,
        `payload.ciphertext=${ack.payload.ciphertext}`,
        `payload.tag=${ack.payload.tag}`,
        ""
    ].join("\n");
}

function messageDigest(message) {
    validateMessage(message);
    return crypto.createHash("sha256").update(canonicalMessage(message), "ascii").digest("hex");
}

function pack(magic, bytes, size, randomBytes) {
    if (bytes.length > size - 6) {
        throw new Error("mailbox inner payload is too large");
    }
    const plaintext = randomBytes(size);
    if (!Buffer.isBuffer(plaintext) || plaintext.length !== size) {
        throw new Error("mailbox randomness source returned the wrong size");
    }
    magic.copy(plaintext, 0);
    plaintext.writeUInt16BE(bytes.length, 4);
    bytes.copy(plaintext, 6);
    return plaintext;
}

function unpack(magic, plaintext, label) {
    if (plaintext.length < 6 || !plaintext.subarray(0, 4).equals(magic)) {
        throw new Error(`${label} magic is invalid`);
    }
    const length = plaintext.readUInt16BE(4);
    if (length > plaintext.length - 6) {
        throw new Error(`${label} length is invalid`);
    }
    return plaintext.subarray(6, 6 + length);
}

function encryptFixed(plaintext, key, iv) {
    const cipher = crypto.createCipheriv("aes-256-cbc", key, iv);
    cipher.setAutoPadding(false);
    return Buffer.concat([cipher.update(plaintext), cipher.final()]);
}

function decryptFixed(ciphertext, key, iv) {
    const decipher = crypto.createDecipheriv("aes-256-cbc", key, iv);
    decipher.setAutoPadding(false);
    return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
}

function validateMessage(message) {
    assertExactKeys(message, OUTER_KEYS, "mailbox message");
    if (message.v !== 1) {
        throw new Error("mailbox message version is invalid");
    }
    decodeBase64Url(message.mailbox_id, 16, "mailbox message mailbox_id");
    decodeBase64Url(message.message_id, 16, "mailbox message message_id");
    if (!Number.isInteger(message.expires_at)
        || message.expires_at < 0
        || message.expires_at > 2147483647) {
        throw new Error("mailbox message expiry is invalid");
    }
    assertExactKeys(message.payload, PAYLOAD_KEYS, "mailbox message payload");
    decodeBase64Url(message.payload.iv, 16, "mailbox message IV");
    decodeBase64Url(message.payload.ciphertext, MESSAGE_PLAINTEXT_BYTES, "mailbox message ciphertext");
    decodeBase64Url(message.payload.tag, 32, "mailbox message tag");
    return message;
}

function validateAck(ack) {
    assertExactKeys(ack, ACK_OUTER_KEYS, "mailbox ACK");
    if (ack.v !== 1) {
        throw new Error("mailbox ACK version is invalid");
    }
    decodeBase64Url(ack.message_id, 16, "mailbox ACK message_id");
    decodeHex(ack.capsule_sha256, 32, "mailbox ACK capsule hash");
    assertExactKeys(ack.payload, PAYLOAD_KEYS, "mailbox ACK payload");
    decodeBase64Url(ack.payload.iv, 16, "mailbox ACK IV");
    decodeBase64Url(ack.payload.ciphertext, ACK_PLAINTEXT_BYTES, "mailbox ACK ciphertext");
    decodeBase64Url(ack.payload.tag, 32, "mailbox ACK tag");
    return ack;
}

function createMessage(event, config, options = {}) {
    const bundle = keys(config);
    const randomBytes = options.randomBytes || crypto.randomBytes;
    const messageId = options.messageId
        || randomBytes(16).toString("base64url");
    decodeBase64Url(messageId, 16, "message ID");
    const inner = Buffer.from(canonicalInnerEvent(event), "utf8");
    const plaintext = pack(MESSAGE_MAGIC, inner, MESSAGE_PLAINTEXT_BYTES, randomBytes);
    const iv = options.iv || randomBytes(16);
    if (!Buffer.isBuffer(iv) || iv.length !== 16) {
        throw new Error("mailbox message IV is invalid");
    }
    const message = {
        v: 1,
        mailbox_id: bundle.mailboxId,
        message_id: messageId,
        expires_at: event.expires_at,
        payload: {
            iv: iv.toString("base64url"),
            ciphertext: encryptFixed(plaintext, bundle.sendEnc, iv).toString("base64url"),
            tag: ""
        }
    };
    message.payload.tag = crypto.createHmac("sha256", bundle.sendMac)
        .update(canonicalMessageMac(message), "ascii")
        .digest("base64url");
    return validateMessage(message);
}

function openMessage(message, config) {
    validateMessage(message);
    const bundle = keys(config);
    if (message.mailbox_id !== bundle.mailboxId) {
        throw new Error("mailbox message belongs to another mailbox");
    }
    const supplied = decodeBase64Url(message.payload.tag, 32, "mailbox message tag");
    const expected = crypto.createHmac("sha256", bundle.sendMac)
        .update(canonicalMessageMac(message), "ascii")
        .digest();
    if (!crypto.timingSafeEqual(supplied, expected)) {
        throw new Error("mailbox message authentication failed");
    }
    const plaintext = decryptFixed(
        decodeBase64Url(message.payload.ciphertext, MESSAGE_PLAINTEXT_BYTES, "mailbox message ciphertext"),
        bundle.sendEnc,
        decodeBase64Url(message.payload.iv, 16, "mailbox message IV")
    );
    const event = parseStrictJson(unpack(MESSAGE_MAGIC, plaintext, "mailbox message").toString("utf8"), "mailbox inner event");
    validateEvent(event);
    if (event.expires_at !== message.expires_at) {
        throw new Error("mailbox message expiry does not match the inner event");
    }
    return event;
}

function createAcknowledgement(message, event, acknowledgedAt, config, options = {}) {
    validateMessage(message);
    validateEvent(event);
    const recoveredEvent = openMessage(message, config);
    if (canonicalInnerEvent(recoveredEvent) !== canonicalInnerEvent(event)) {
        throw new Error("acknowledged event does not match the authenticated capsule");
    }
    if (!Number.isInteger(acknowledgedAt)
        || acknowledgedAt < 0
        || acknowledgedAt > 2147483647) {
        throw new Error("acknowledgement time is invalid");
    }
    const bundle = keys(config);
    const randomBytes = options.randomBytes || crypto.randomBytes;
    const capsuleHash = messageDigest(message);
    const inner = Buffer.from(JSON.stringify({
        v: 1,
        incident_id: event.incident_id,
        sequence: event.sequence,
        message_id: message.message_id,
        capsule_sha256: capsuleHash,
        acknowledged_at: acknowledgedAt
    }), "utf8");
    const plaintext = pack(ACK_MAGIC, inner, ACK_PLAINTEXT_BYTES, randomBytes);
    const iv = options.iv || randomBytes(16);
    if (!Buffer.isBuffer(iv) || iv.length !== 16) {
        throw new Error("mailbox ACK IV is invalid");
    }
    const ack = {
        v: 1,
        message_id: message.message_id,
        capsule_sha256: capsuleHash,
        payload: {
            iv: iv.toString("base64url"),
            ciphertext: encryptFixed(plaintext, bundle.ackEnc, iv).toString("base64url"),
            tag: ""
        }
    };
    ack.payload.tag = crypto.createHmac("sha256", bundle.ackMac)
        .update(canonicalAckMac(ack), "ascii")
        .digest("base64url");
    return validateAck(ack);
}

function openAcknowledgement(ack, message, event, config) {
    validateAck(ack);
    validateMessage(message);
    validateEvent(event);
    const bundle = keys(config);
    if (ack.message_id !== message.message_id
        || ack.capsule_sha256 !== messageDigest(message)) {
        throw new Error("mailbox ACK is not bound to this exact capsule");
    }
    const supplied = decodeBase64Url(ack.payload.tag, 32, "mailbox ACK tag");
    const expected = crypto.createHmac("sha256", bundle.ackMac)
        .update(canonicalAckMac(ack), "ascii")
        .digest();
    if (!crypto.timingSafeEqual(supplied, expected)) {
        throw new Error("mailbox ACK authentication failed");
    }
    const plaintext = decryptFixed(
        decodeBase64Url(ack.payload.ciphertext, ACK_PLAINTEXT_BYTES, "mailbox ACK ciphertext"),
        bundle.ackEnc,
        decodeBase64Url(ack.payload.iv, 16, "mailbox ACK IV")
    );
    const inner = parseStrictJson(unpack(ACK_MAGIC, plaintext, "mailbox ACK").toString("utf8"), "mailbox inner ACK");
    assertExactKeys(inner, ACK_INNER_KEYS, "mailbox inner ACK");
    if (inner.v !== 1
        || inner.message_id !== message.message_id
        || inner.capsule_sha256 !== ack.capsule_sha256
        || inner.incident_id !== event.incident_id
        || inner.sequence !== event.sequence
        || !Number.isInteger(inner.acknowledged_at)
        || inner.acknowledged_at < 0
        || inner.acknowledged_at > 2147483647) {
        throw new Error("mailbox inner ACK binding is invalid");
    }
    return inner;
}

module.exports = {
    ACK_PLAINTEXT_BYTES,
    MESSAGE_PLAINTEXT_BYTES,
    canonicalAck,
    canonicalMessage,
    createAcknowledgement,
    createMessage,
    messageDigest,
    openAcknowledgement,
    openMessage,
    validateAck,
    validateMessage
};

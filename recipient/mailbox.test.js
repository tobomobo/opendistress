// SPDX-License-Identifier: MIT

"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const {
    createAcknowledgement,
    createMessage,
    messageDigest,
    openAcknowledgement,
    openMessage
} = require("./mailbox.js");

const ROOT = path.join(__dirname, "..");
const live = JSON.parse(fs.readFileSync(
    path.join(ROOT, "protocol", "fixtures", "live-trigger-v2.json"),
    "utf8"
));
const location = JSON.parse(fs.readFileSync(
    path.join(ROOT, "protocol", "fixtures", "location-updated-v2.json"),
    "utf8"
));
const config = {
    mailbox_id: Buffer.from(Array.from({ length: 16 }, (_, index) => 0xa0 + index)).toString("base64url"),
    send_enc_key_hex: "11".repeat(32),
    send_mac_key_hex: "22".repeat(32),
    ack_enc_key_hex: "33".repeat(32),
    ack_mac_key_hex: "44".repeat(32)
};

function deterministic(fill) {
    return (size) => Buffer.alloc(size, fill);
}

test("fixed-size mailbox capsules hide v2 kind and round-trip both event types", () => {
    const liveMessage = createMessage(live, config, {
        messageId: "ICEiIyQlJicoKSorLC0uLw",
        iv: Buffer.alloc(16, 0x55),
        randomBytes: deterministic(0x66)
    });
    assert.equal(
        messageDigest(liveMessage),
        "bae4682120b8ed891c0fc7e3a5aeab673ac171a6f8c6015c4d0d86942b6d5f15"
    );
    const locationMessage = createMessage(location, config, {
        messageId: "MDEyMzQ1Njc4OTo7PD0-Pw",
        iv: Buffer.alloc(16, 0x77),
        randomBytes: deterministic(0x88)
    });

    assert.equal(liveMessage.payload.ciphertext.length, locationMessage.payload.ciphertext.length);
    assert.equal(JSON.stringify(liveMessage).includes("live.triggered"), false);
    assert.equal(JSON.stringify(locationMessage).includes("location.updated"), false);
    assert.deepEqual(JSON.parse(JSON.stringify(openMessage(liveMessage, config))), live);
    assert.deepEqual(JSON.parse(JSON.stringify(openMessage(locationMessage, config))), location);
});

test("generated enrollment shape is accepted without exposing capabilities in capsules", () => {
    const enrollment = {
        v: 1,
        mailbox_id: config.mailbox_id,
        append_cap: Buffer.alloc(32, 0xa1).toString("base64url"),
        read_cap: Buffer.alloc(32, 0xb2).toString("base64url"),
        ack_cap: Buffer.alloc(32, 0xc3).toString("base64url"),
        send_enc_key_hex: config.send_enc_key_hex,
        send_mac_key_hex: config.send_mac_key_hex,
        ack_enc_key_hex: config.ack_enc_key_hex,
        ack_mac_key_hex: config.ack_mac_key_hex
    };
    const message = createMessage(live, enrollment, {
        messageId: "ICEiIyQlJicoKSorLC0uLw",
        iv: Buffer.alloc(16, 0x55),
        randomBytes: deterministic(0x66)
    });
    const wire = JSON.stringify(message);
    assert.equal(wire.includes(enrollment.append_cap), false);
    assert.equal(wire.includes(enrollment.read_cap), false);
    assert.equal(wire.includes(enrollment.ack_cap), false);
    assert.equal(openMessage(message, enrollment).incident_id, live.incident_id);
});

test("message tampering fails before inner event parsing", () => {
    const message = createMessage(live, config, {
        messageId: "ICEiIyQlJicoKSorLC0uLw",
        iv: Buffer.alloc(16, 0x55),
        randomBytes: deterministic(0x66)
    });
    const changed = structuredClone(message);
    changed.payload.ciphertext = `${changed.payload.ciphertext[0] === "A" ? "B" : "A"}${changed.payload.ciphertext.slice(1)}`;
    assert.throws(() => openMessage(changed, config), /authentication failed/);
});

test("ACK is encrypted and bound to incident, sequence, message, and exact capsule", () => {
    const message = createMessage(live, config, {
        messageId: "ICEiIyQlJicoKSorLC0uLw",
        iv: Buffer.alloc(16, 0x55),
        randomBytes: deterministic(0x66)
    });
    const ack = createAcknowledgement(message, live, live.created_at + 30, config, {
        iv: Buffer.alloc(16, 0x99),
        randomBytes: deterministic(0xaa)
    });
    assert.equal(JSON.stringify(ack).includes(live.incident_id), false);
    assert.deepEqual(JSON.parse(JSON.stringify(
        openAcknowledgement(ack, message, live, config)
    )), {
        v: 1,
        incident_id: live.incident_id,
        sequence: live.sequence,
        message_id: message.message_id,
        capsule_sha256: messageDigest(message),
        acknowledged_at: live.created_at + 30
    });

    const other = structuredClone(message);
    other.payload.tag = `${other.payload.tag[0] === "A" ? "B" : "A"}${other.payload.tag.slice(1)}`;
    assert.throws(
        () => openAcknowledgement(ack, other, live, config),
        /not bound to this exact capsule/
    );
});

// SPDX-License-Identifier: MIT

"use strict";

const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { createMessage, messageDigest } = require("../recipient/mailbox.js");

const ROOT = path.join(__dirname, "..");
const FIXTURES = path.join(ROOT, "protocol", "fixtures");

const AUTH_KEY_HEX = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
const ENC_KEY_HEX = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";
const MAC_KEY_HEX = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f";
const EVENT_ID = "AAECAwQFBgcICQoLDA0ODw";
const LOCATION_EVENT_ID = "sLGys7S1tre4ubq7vL2-vw";
const DEVICE_ID = "EBESExQVFhcYGRobHB0eHw";

function digest(data) {
    return crypto.createHash("sha256").update(data, "ascii").digest();
}

function hmac(keyHex, data) {
    return crypto.createHmac("sha256", Buffer.from(keyHex, "hex"))
        .update(data, "ascii")
        .digest();
}

function signature(version, bytes) {
    return `v${version}=${bytes.toString("base64url")}`;
}

function canonicalV1Request(event) {
    return [
        "opendistress.test.submit.v1",
        "method=POST",
        "v=1",
        `event_id=${event.event_id}`,
        `incident_id=${event.incident_id}`,
        `device_id=${event.device_id}`,
        "kind=test.triggered",
        "sequence=0",
        `created_at=${event.created_at}`,
        `expires_at=${event.expires_at}`,
        "payload=null",
        ""
    ].join("\n");
}

function canonicalV1Response(eventId) {
    return [
        "opendistress.test.intake-result.v1",
        "v=1",
        `event_id=${eventId}`,
        "result=durably_accepted",
        ""
    ].join("\n");
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

function canonicalV2Request(event) {
    return [
        "opendistress.submit.v2",
        "method=POST",
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
        `payload.tag=${event.payload.tag}`,
        ""
    ].join("\n");
}

function canonicalV2Response(eventId) {
    return [
        "opendistress.result.v2",
        "v=2",
        `event_id=${eventId}`,
        "result=durably_accepted",
        ""
    ].join("\n");
}

function canonicalStatusRequest(query) {
    return [
        "opendistress.status.query.v2",
        "method=POST",
        "v=2",
        `request_id=${query.request_id}`,
        `incident_id=${query.incident_id}`,
        `device_id=${query.device_id}`,
        `created_at=${query.created_at}`,
        `expires_at=${query.expires_at}`,
        ""
    ].join("\n");
}

function canonicalStatusResponse(query, state, checkedAt) {
    return [
        "opendistress.status.result.v2",
        "v=2",
        `request_id=${query.request_id}`,
        `incident_id=${query.incident_id}`,
        `device_id=${query.device_id}`,
        `state=${state}`,
        `checked_at=${checkedAt}`,
        ""
    ].join("\n");
}

function canonicalFields(prefix, canonical, mac) {
    return [
        `canonical_${prefix}_length=${Buffer.byteLength(canonical, "ascii")}`,
        `canonical_${prefix}_hex=${Buffer.from(canonical, "ascii").toString("hex")}`,
        `${prefix}_hmac_hex=${mac.toString("hex")}`,
        `${prefix}_signature=${signature(prefix === "request" ? 2 : 2, mac)}`
    ];
}

function write(name, content) {
    fs.writeFileSync(path.join(FIXTURES, name), `${content}\n`, "utf8");
}

const testEvent = {
    v: 1,
    event_id: EVENT_ID,
    incident_id: EVENT_ID,
    device_id: DEVICE_ID,
    kind: "test.triggered",
    sequence: 0,
    created_at: 1788105600,
    expires_at: 1788106500,
    payload: null
};

const live = {
    v: 2,
    event_id: EVENT_ID,
    incident_id: EVENT_ID,
    device_id: DEVICE_ID,
    kind: "live.triggered",
    sequence: 0,
    created_at: 1788105600,
    expires_at: 1788109200,
    payload: {
        key_version: 1,
        iv: "YGFiY2RlZmdoaWprbG1ubw",
        ciphertext: "8eRa_JOzxdPOO3l494xv5Q",
        tag: ""
    }
};

const location = {
    v: 2,
    event_id: LOCATION_EVENT_ID,
    incident_id: EVENT_ID,
    device_id: DEVICE_ID,
    kind: "location.updated",
    sequence: 1,
    created_at: 1788105660,
    expires_at: 1788109200,
    payload: {
        key_version: 1,
        iv: "wMHCw8TFxsfIycrLzM3Ozw",
        ciphertext: "Ni1HgpKbRi0gcHT2Ms7Xkw",
        tag: ""
    }
};

for (const event of [live, location]) {
    event.payload.tag = hmac(MAC_KEY_HEX, canonicalContent(event)).toString("base64url");
}

const v1Request = canonicalV1Request(testEvent);
const v1Response = canonicalV1Response(testEvent.event_id);
const v1RequestMac = hmac(AUTH_KEY_HEX, v1Request);
const v1ResponseMac = hmac(AUTH_KEY_HEX, v1Response);
write("signature-v1.txt", [
    "# Public conformance vector only. Raw JSON fixture bytes are not signed.",
    "algorithm=HMAC-SHA256",
    "key_encoding=hex-lowercase",
    `key=${AUTH_KEY_HEX}`,
    `event_id=${testEvent.event_id}`,
    `incident_id=${testEvent.incident_id}`,
    `device_id=${testEvent.device_id}`,
    `created_at=${testEvent.created_at}`,
    `expires_at=${testEvent.expires_at}`,
    `canonical_request_length=${Buffer.byteLength(v1Request, "ascii")}`,
    `canonical_request_hex=${Buffer.from(v1Request, "ascii").toString("hex")}`,
    `canonical_request_sha256=${digest(v1Request).toString("hex")}`,
    `request_hmac_hex=${v1RequestMac.toString("hex")}`,
    "signature_encoding=base64url-unpadded",
    `request_signature=${signature(1, v1RequestMac)}`,
    `canonical_response_length=${Buffer.byteLength(v1Response, "ascii")}`,
    `canonical_response_hex=${Buffer.from(v1Response, "ascii").toString("hex")}`,
    `canonical_response_sha256=${digest(v1Response).toString("hex")}`,
    `response_hmac_hex=${v1ResponseMac.toString("hex")}`,
    `response_signature=${signature(1, v1ResponseMac)}`
].join("\n"));

function encryptedSection(event, plaintext) {
    const content = canonicalContent(event);
    const contentMac = hmac(MAC_KEY_HEX, content);
    const request = canonicalV2Request(event);
    const requestMac = hmac(AUTH_KEY_HEX, request);
    const response = canonicalV2Response(event.event_id);
    const responseMac = hmac(AUTH_KEY_HEX, response);
    const common = [
        `event_id=${event.event_id}`,
        `incident_id=${event.incident_id}`,
        `sequence=${event.sequence}`,
        `created_at=${event.created_at}`,
        `expires_at=${event.expires_at}`,
        `iv_hex=${Buffer.from(event.payload.iv, "base64url").toString("hex")}`,
        `iv=${event.payload.iv}`
    ];
    const body = event.kind === "live.triggered"
        ? [
            "template_id_hex=a0a1a2a3a4a5a6a7a8a9aaabacadaeaf",
            `plaintext_hex=${plaintext}`
        ]
        : [
            "location_record_version=1",
            "location_record_type=2",
            "capture_at=1788105650",
            "latitude_e7=123456789",
            "longitude_e7=-456789012",
            "quality=4",
            "path=1",
            `plaintext_hex=${plaintext}`
        ];
    return [
        ...common,
        ...body,
        `ciphertext_hex=${Buffer.from(event.payload.ciphertext, "base64url").toString("hex")}`,
        `ciphertext=${event.payload.ciphertext}`,
        `canonical_content_length=${Buffer.byteLength(content, "ascii")}`,
        `canonical_content_hex=${Buffer.from(content, "ascii").toString("hex")}`,
        `tag_hmac_hex=${contentMac.toString("hex")}`,
        `tag=${event.payload.tag}`,
        `canonical_request_length=${Buffer.byteLength(request, "ascii")}`,
        `canonical_request_hex=${Buffer.from(request, "ascii").toString("hex")}`,
        `request_hmac_hex=${requestMac.toString("hex")}`,
        `request_signature=${signature(2, requestMac)}`,
        `canonical_result_length=${Buffer.byteLength(response, "ascii")}`,
        `canonical_result_hex=${Buffer.from(response, "ascii").toString("hex")}`,
        `result_hmac_hex=${responseMac.toString("hex")}`,
        `response_signature=${signature(2, responseMac)}`,
        `response_json=${JSON.stringify({
            v: 2,
            event_id: event.event_id,
            result: "durably_accepted",
            response_signature: signature(2, responseMac)
        })}`
    ].join("\n");
}

write("live-trigger-v2.json", JSON.stringify(live));
write("location-updated-v2.json", JSON.stringify(location));
write("encryption-v2.txt", [
    "# Public conformance vectors only. Never provision these keys or identifiers.",
    "encryption_algorithm=AES-256-CBC",
    "padding=none",
    "tag_algorithm=HMAC-SHA256",
    "signature_algorithm=HMAC-SHA256",
    "encoding=base64url-unpadded",
    `auth_key_hex=${AUTH_KEY_HEX}`,
    `enc_key_hex=${ENC_KEY_HEX}`,
    `mac_key_hex=${MAC_KEY_HEX}`,
    "key_version=1",
    `device_id=${DEVICE_ID}`,
    "",
    "[live.triggered]",
    encryptedSection(live, "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"),
    "",
    "[location.updated]",
    encryptedSection(location, "01026a9453b2075bcd15e4c5f3ec0401")
].join("\n"));

const statusQuery = {
    v: 2,
    request_id: "ICEiIyQlJicoKSorLC0uLw",
    incident_id: EVENT_ID,
    device_id: DEVICE_ID,
    created_at: 1788105700,
    expires_at: 1788109200
};
const statusState = "resolved";
const checkedAt = 1788105701;
const statusRequest = canonicalStatusRequest(statusQuery);
const statusResponse = canonicalStatusResponse(statusQuery, statusState, checkedAt);
const statusRequestMac = hmac(AUTH_KEY_HEX, statusRequest);
const statusResponseMac = hmac(AUTH_KEY_HEX, statusResponse);
write("status-v2.txt", [
    "# Public conformance vector only. Never provision this key or these identifiers.",
    "algorithm=HMAC-SHA256",
    "encoding=base64url-unpadded",
    `auth_key_hex=${AUTH_KEY_HEX}`,
    `request_id=${statusQuery.request_id}`,
    `incident_id=${statusQuery.incident_id}`,
    `device_id=${statusQuery.device_id}`,
    `created_at=${statusQuery.created_at}`,
    `expires_at=${statusQuery.expires_at}`,
    ...canonicalFields("request", statusRequest, statusRequestMac),
    `state=${statusState}`,
    `checked_at=${checkedAt}`,
    ...canonicalFields("response", statusResponse, statusResponseMac),
    `response_json=${JSON.stringify({
        v: 2,
        request_id: statusQuery.request_id,
        incident_id: statusQuery.incident_id,
        device_id: statusQuery.device_id,
        state: statusState,
        checked_at: checkedAt,
        response_signature: signature(2, statusResponseMac)
    })}`
].join("\n"));

const mailboxConfig = {
    mailbox_id: Buffer.from(Array.from({ length: 16 }, (_, index) => 0xa0 + index)).toString("base64url"),
    send_enc_key_hex: "11".repeat(32),
    send_mac_key_hex: "22".repeat(32),
    ack_enc_key_hex: "33".repeat(32),
    ack_mac_key_hex: "44".repeat(32)
};
const mailboxMessage = createMessage(live, mailboxConfig, {
    messageId: "ICEiIyQlJicoKSorLC0uLw",
    iv: Buffer.alloc(16, 0x55),
    randomBytes: (size) => Buffer.alloc(size, 0x66)
});
write("mailbox-message-v1.json", JSON.stringify(mailboxMessage, null, 2));

process.stdout.write(`mailbox_capsule_sha256=${messageDigest(mailboxMessage)}\n`);

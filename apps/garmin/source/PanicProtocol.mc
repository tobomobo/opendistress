// SPDX-License-Identifier: MIT

import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.Math;
import Toybox.Position;
import Toybox.StringUtil;

module PanicProtocol {
    const V1_KIND = "test.triggered";
    const V2_LIVE_KIND = "live.triggered";
    const V2_LOCATION_KIND = "location.updated";

    const V1_SUBMIT_DOMAIN = "spb.test.submit.v1";
    const V1_RESULT_DOMAIN = "spb.test.intake-result.v1";
    const V2_SUBMIT_DOMAIN = "spb.submit.v2";
    const V2_CONTENT_DOMAIN = "spb.content.v2";
    const V2_RESULT_DOMAIN = "spb.result.v2";
    const V2_STATUS_QUERY_DOMAIN = "spb.status.query.v2";
    const V2_STATUS_RESULT_DOMAIN = "spb.status.result.v2";

    const LOWER_HEX = "0123456789abcdef";
    const BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    const BLOCK_FINAL = "AQgw";
    const DIGEST_FINAL = "AEIMQUYcgkosw048";
    const MAX_V1_CREATED_AT = 2147482747;
    const MAX_TIME = 2147483647;
    const MAX_V2_LIFETIME = 86400;

    const PUBLIC_AUTH_KEY = "000102030405060708090a0b0c0d0e0f"
        + "101112131415161718191a1b1c1d1e1f";
    const PUBLIC_ENC_KEY = "202122232425262728292a2b2c2d2e2f"
        + "303132333435363738393a3b3c3d3e3f";
    const PUBLIC_MAC_KEY = "404142434445464748494a4b4c4d4e4f"
        + "505152535455565758595a5b5c5d5e5f";

    const EVENT_KEYS = [
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
    const RESULT_KEYS = ["v", "event_id", "result", "response_signature"];
    const STATUS_QUERY_KEYS = [
        "v",
        "request_id",
        "incident_id",
        "device_id",
        "created_at",
        "expires_at"
    ];
    const STATUS_RESULT_KEYS = [
        "v",
        "request_id",
        "incident_id",
        "device_id",
        "state",
        "checked_at",
        "response_signature"
    ];

    function hasExactKeys(value, expectedKeys) {
        if (!(value instanceof Lang.Dictionary) || value.size() != expectedKeys.size()) {
            return false;
        }
        var actualKeys = value.keys();
        for (var i = 0; i < expectedKeys.size(); i += 1) {
            if (actualKeys.indexOf(expectedKeys[i]) < 0) {
                return false;
            }
        }
        return true;
    }

    function isCanonicalBase64(value, length, finalCharacters) {
        if (!(value instanceof Lang.String) || value.length() != length) {
            return false;
        }
        var chars = value.toCharArray();
        for (var i = 0; i < chars.size(); i += 1) {
            if (BASE64URL.find(chars[i].toString()) == null) {
                return false;
            }
        }
        return finalCharacters.find(chars[length - 1].toString()) != null;
    }

    function isCanonicalId(value) {
        return isCanonicalBase64(value, 22, BLOCK_FINAL);
    }

    function isCanonicalDigest(value) {
        return isCanonicalBase64(value, 43, DIGEST_FINAL);
    }

    function isLowerHex(value, length) {
        if (!(value instanceof Lang.String) || value.length() != length) {
            return false;
        }
        var chars = value.toCharArray();
        for (var i = 0; i < chars.size(); i += 1) {
            if (LOWER_HEX.find(chars[i].toString()) == null) {
                return false;
            }
        }
        return true;
    }

    function isLowerHexKey(value) {
        return isLowerHex(value, 64);
    }

    function stringEquals(left, right) {
        return left instanceof Lang.String
            && right instanceof Lang.String
            && left.equals(right);
    }

    function isPublicFixtureKey(value) {
        return stringEquals(value, PUBLIC_AUTH_KEY)
            || stringEquals(value, PUBLIC_ENC_KEY)
            || stringEquals(value, PUBLIC_MAC_KEY);
    }

    function isSafeAuthKey(value) {
        return isLowerHexKey(value) && !isPublicFixtureKey(value);
    }

    function isHttpsBaseUrl(value) {
        if (!(value instanceof Lang.String)
            || value.length() <= 8
            || value.find("https://") != 0
            || value.find("?") != null
            || value.find("#") != null
            || value.find("@") != null
            || value.toCharArray()[value.length() - 1] == '/') {
            return false;
        }
        return value.substring(8, value.length()).find("/") == null;
    }

    function isGrafanaWebhookUrl(value) {
        if (!(value instanceof Lang.String)
            || value.length() < 80
            || value.length() > 512
            || value.find("https://") != 0
            || value.find("?") != null
            || value.find("#") != null
            || value.find("@") != null
            || value.toCharArray()[value.length() - 1] != '/') {
            return false;
        }
        var marker = "/integrations/v1/formatted_webhook/";
        var markerAt = value.find(marker);
        if (markerAt == null || markerAt <= 8) {
            return false;
        }
        var authorityEndRelative = value.substring(8, value.length()).find("/");
        if (authorityEndRelative == null) {
            return false;
        }
        var authorityEnd = 8 + authorityEndRelative;
        if (markerAt < authorityEnd) {
            return false;
        }
        var host = value.substring(8, authorityEnd);
        if (host.length() <= 12
            || !stringEquals(
                host.substring(host.length() - 12, host.length()),
                ".grafana.net"
            )) {
            return false;
        }
        var token = value.substring(
            markerAt + marker.length(),
            value.length() - 1
        );
        if (token.length() < 16 || token.length() > 128 || token.find("/") != null) {
            return false;
        }
        var allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
        var characters = token.toCharArray();
        for (var i = 0; i < characters.size(); i += 1) {
            if (allowed.find(characters[i].toString()) == null) {
                return false;
            }
        }
        return true;
    }

    function randomId() {
        return base64Url(Cryptography.randomBytes(16));
    }

    function newTestEvent(eventId, deviceId, createdAt) {
        var expiresAt = null;
        if (createdAt instanceof Lang.Number
            && createdAt >= 0
            && createdAt <= MAX_V1_CREATED_AT) {
            expiresAt = createdAt + 900;
        }
        return {
            "v" => 1,
            "event_id" => eventId,
            "incident_id" => eventId,
            "device_id" => deviceId,
            "kind" => V1_KIND,
            "sequence" => 0,
            "created_at" => createdAt,
            "expires_at" => expiresAt,
            "payload" => null
        };
    }

    function newEncryptedEvent(
        kind,
        eventId,
        incidentId,
        deviceId,
        sequence,
        createdAt,
        expiresAt,
        keyVersion,
        plaintext,
        encryptionKeyHex,
        macKeyHex
    ) {
        return newEncryptedEventWithIv(
            kind,
            eventId,
            incidentId,
            deviceId,
            sequence,
            createdAt,
            expiresAt,
            keyVersion,
            plaintext,
            encryptionKeyHex,
            macKeyHex,
            Cryptography.randomBytes(16)
        );
    }

    function newEncryptedEventWithIv(
        kind,
        eventId,
        incidentId,
        deviceId,
        sequence,
        createdAt,
        expiresAt,
        keyVersion,
        plaintext,
        encryptionKeyHex,
        macKeyHex,
        iv
    ) {
        if (!(plaintext instanceof Lang.ByteArray)
            || plaintext.size() != 16
            || !(iv instanceof Lang.ByteArray)
            || iv.size() != 16) {
            return null;
        }
        var cipher = new Cryptography.Cipher({
            :algorithm => Cryptography.CIPHER_AES256,
            :mode => Cryptography.MODE_CBC,
            :key => hexBytes(encryptionKeyHex),
            :iv => iv
        });
        var payload = {
            "key_version" => keyVersion,
            "iv" => base64Url(iv),
            "ciphertext" => base64Url(cipher.encrypt(plaintext)),
            "tag" => ""
        };
        var event = {
            "v" => 2,
            "event_id" => eventId,
            "incident_id" => incidentId,
            "device_id" => deviceId,
            "kind" => kind,
            "sequence" => sequence,
            "created_at" => createdAt,
            "expires_at" => expiresAt,
            "payload" => payload
        };
        payload["tag"] = base64Url(hmacDigest(macKeyHex, contentSigningInput(event)));
        return event;
    }

    function isTestEvent(value) {
        return hasExactKeys(value, EVENT_KEYS)
            && value["v"] == 1
            && isCanonicalId(value["event_id"])
            && stringEquals(value["incident_id"], value["event_id"])
            && isCanonicalId(value["device_id"])
            && stringEquals(value["kind"], V1_KIND)
            && value["sequence"] == 0
            && value["created_at"] instanceof Lang.Number
            && value["created_at"] >= 0
            && value["created_at"] <= MAX_V1_CREATED_AT
            && value["expires_at"] instanceof Lang.Number
            && value["expires_at"] == value["created_at"] + 900
            && value["payload"] == null;
    }

    function isEncryptedEvent(value) {
        if (!hasExactKeys(value, EVENT_KEYS)
            || value["v"] != 2
            || !isCanonicalId(value["event_id"])
            || !isCanonicalId(value["incident_id"])
            || !isCanonicalId(value["device_id"])
            || !(value["sequence"] instanceof Lang.Number)
            || value["sequence"] < 0
            || !(value["created_at"] instanceof Lang.Number)
            || value["created_at"] < 0
            || value["created_at"] > MAX_TIME
            || !(value["expires_at"] instanceof Lang.Number)
            || value["expires_at"] <= value["created_at"]
            || value["expires_at"] > MAX_TIME
            || value["expires_at"] - value["created_at"] > MAX_V2_LIFETIME
            || !hasExactKeys(value["payload"], PAYLOAD_KEYS)) {
            return false;
        }
        if (stringEquals(value["kind"], V2_LIVE_KIND)) {
            if (value["sequence"] != 0
                || !stringEquals(value["incident_id"], value["event_id"])) {
                return false;
            }
        } else if (stringEquals(value["kind"], V2_LOCATION_KIND)) {
            if (value["sequence"] < 1
                || stringEquals(value["incident_id"], value["event_id"])) {
                return false;
            }
        } else {
            return false;
        }
        var payload = value["payload"];
        return payload["key_version"] instanceof Lang.Number
            && payload["key_version"] >= 1
            && isCanonicalId(payload["iv"])
            && isCanonicalId(payload["ciphertext"])
            && isCanonicalDigest(payload["tag"]);
    }

    function isEvent(value) {
        return isTestEvent(value) || isEncryptedEvent(value);
    }

    function newStatusQuery(requestId, incidentId, deviceId, createdAt, expiresAt) {
        return {
            "v" => 2,
            "request_id" => requestId,
            "incident_id" => incidentId,
            "device_id" => deviceId,
            "created_at" => createdAt,
            "expires_at" => expiresAt
        };
    }

    function isStatusQuery(value) {
        return hasExactKeys(value, STATUS_QUERY_KEYS)
            && value["v"] == 2
            && isCanonicalId(value["request_id"])
            && isCanonicalId(value["incident_id"])
            && isCanonicalId(value["device_id"])
            && value["created_at"] instanceof Lang.Number
            && value["created_at"] >= 0
            && value["created_at"] <= MAX_TIME
            && value["expires_at"] instanceof Lang.Number
            && value["expires_at"] > value["created_at"]
            && value["expires_at"] <= MAX_TIME;
    }

    function submitSigningInput(event) {
        if (event["v"] == 1) {
            return V1_SUBMIT_DOMAIN + "\n"
                + "method=POST\n"
                + "v=1\n"
                + "event_id=" + event["event_id"] + "\n"
                + "incident_id=" + event["incident_id"] + "\n"
                + "device_id=" + event["device_id"] + "\n"
                + "kind=" + V1_KIND + "\n"
                + "sequence=0\n"
                + "created_at=" + event["created_at"].format("%d") + "\n"
                + "expires_at=" + event["expires_at"].format("%d") + "\n"
                + "payload=null\n";
        }
        var payload = event["payload"];
        return V2_SUBMIT_DOMAIN + "\n"
            + "method=POST\n"
            + "v=2\n"
            + "event_id=" + event["event_id"] + "\n"
            + "incident_id=" + event["incident_id"] + "\n"
            + "device_id=" + event["device_id"] + "\n"
            + "kind=" + event["kind"] + "\n"
            + "sequence=" + event["sequence"].format("%d") + "\n"
            + "created_at=" + event["created_at"].format("%d") + "\n"
            + "expires_at=" + event["expires_at"].format("%d") + "\n"
            + "payload.key_version=" + payload["key_version"].format("%d") + "\n"
            + "payload.iv=" + payload["iv"] + "\n"
            + "payload.ciphertext=" + payload["ciphertext"] + "\n"
            + "payload.tag=" + payload["tag"] + "\n";
    }

    function contentSigningInput(event) {
        var payload = event["payload"];
        return V2_CONTENT_DOMAIN + "\n"
            + "v=2\n"
            + "event_id=" + event["event_id"] + "\n"
            + "incident_id=" + event["incident_id"] + "\n"
            + "device_id=" + event["device_id"] + "\n"
            + "kind=" + event["kind"] + "\n"
            + "sequence=" + event["sequence"].format("%d") + "\n"
            + "created_at=" + event["created_at"].format("%d") + "\n"
            + "expires_at=" + event["expires_at"].format("%d") + "\n"
            + "payload.key_version=" + payload["key_version"].format("%d") + "\n"
            + "payload.iv=" + payload["iv"] + "\n"
            + "payload.ciphertext=" + payload["ciphertext"] + "\n";
    }

    function resultSigningInput(version, eventId) {
        if (version == 1) {
            return V1_RESULT_DOMAIN + "\n"
                + "v=1\n"
                + "event_id=" + eventId + "\n"
                + "result=durably_accepted\n";
        }
        return V2_RESULT_DOMAIN + "\n"
            + "v=2\n"
            + "event_id=" + eventId + "\n"
            + "result=durably_accepted\n";
    }

    function requestSignature(keyHex, event) {
        return "v" + event["v"].format("%d") + "="
            + base64Url(hmacDigest(keyHex, submitSigningInput(event)));
    }

    function responseSignature(keyHex, version, eventId) {
        return "v" + version.format("%d") + "="
            + base64Url(hmacDigest(keyHex, resultSigningInput(version, eventId)));
    }

    function statusQuerySigningInput(query) {
        return V2_STATUS_QUERY_DOMAIN + "\n"
            + "method=POST\n"
            + "v=2\n"
            + "request_id=" + query["request_id"] + "\n"
            + "incident_id=" + query["incident_id"] + "\n"
            + "device_id=" + query["device_id"] + "\n"
            + "created_at=" + query["created_at"].format("%d") + "\n"
            + "expires_at=" + query["expires_at"].format("%d") + "\n";
    }

    function statusRequestSignature(keyHex, query) {
        return "v2=" + base64Url(hmacDigest(keyHex, statusQuerySigningInput(query)));
    }

    function statusResultSigningInput(result) {
        return V2_STATUS_RESULT_DOMAIN + "\n"
            + "v=2\n"
            + "request_id=" + result["request_id"] + "\n"
            + "incident_id=" + result["incident_id"] + "\n"
            + "device_id=" + result["device_id"] + "\n"
            + "state=" + result["state"] + "\n"
            + "checked_at=" + result["checked_at"].format("%d") + "\n";
    }

    function verifyStatusResult(data, query, keyHex, receiveAt) {
        if (!hasExactKeys(data, STATUS_RESULT_KEYS)
            || data["v"] != 2
            || !stringEquals(data["request_id"], query["request_id"])
            || !stringEquals(data["incident_id"], query["incident_id"])
            || !stringEquals(data["device_id"], query["device_id"])
            || !(stringEquals(data["state"], "active")
                || stringEquals(data["state"], "acknowledged")
                || stringEquals(data["state"], "resolved")
                || stringEquals(data["state"], "expired"))
            || !(data["checked_at"] instanceof Lang.Number)
            || data["checked_at"] < 0
            || data["checked_at"] > MAX_TIME
            || !(data["response_signature"] instanceof Lang.String)
            || !(receiveAt instanceof Lang.Number)
            || receiveAt < 0
            || receiveAt > MAX_TIME) {
            return false;
        }
        if ((data["checked_at"] < query["created_at"]
                && query["created_at"] - data["checked_at"] > 300)
            || (data["checked_at"] > receiveAt
                && data["checked_at"] - receiveAt > 300)
            || (receiveAt > query["created_at"]
                && receiveAt - query["created_at"] > 300)) {
            return false;
        }
        return secureEquals(
            data["response_signature"],
            "v2=" + base64Url(hmacDigest(keyHex, statusResultSigningInput(data)))
        );
    }

    function hmacDigest(keyHex, message) {
        var bytes = StringUtil.convertEncodedString(message, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        var hmac = new Cryptography.HashBasedMessageAuthenticationCode({
            :algorithm => Cryptography.HASH_SHA256,
            :key => hexBytes(keyHex)
        });
        hmac.update(bytes);
        return hmac.digest();
    }

    function hexBytes(value) {
        return StringUtil.convertEncodedString(value, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_HEX,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY
        });
    }

    function bytesHex(value) {
        return StringUtil.convertEncodedString(value, {
            :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :toRepresentation => StringUtil.REPRESENTATION_STRING_HEX
        });
    }

    function base64Url(bytes) {
        var encoded = StringUtil.convertEncodedString(bytes, {
            :fromRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :toRepresentation => StringUtil.REPRESENTATION_STRING_BASE64
        });
        var source = encoded.toCharArray();
        var output = [];
        for (var i = 0; i < source.size(); i += 1) {
            if (source[i] == '=') {
                continue;
            } else if (source[i] == '+') {
                output.add('-');
            } else if (source[i] == '/') {
                output.add('_');
            } else {
                output.add(source[i]);
            }
        }
        return StringUtil.charArrayToString(output);
    }

    function secureEquals(left, right) {
        if (!(left instanceof Lang.String)
            || !(right instanceof Lang.String)
            || left.length() != right.length()) {
            return false;
        }
        var leftChars = left.toCharArray();
        var rightChars = right.toCharArray();
        var differences = 0;
        for (var i = 0; i < leftChars.size(); i += 1) {
            if (leftChars[i] != rightChars[i]) {
                differences += 1;
            }
        }
        return differences == 0;
    }

    function verifyDurablyAccepted(data, event, keyHex) {
        if (!hasExactKeys(data, RESULT_KEYS)
            || data["v"] != event["v"]
            || !stringEquals(data["event_id"], event["event_id"])
            || !stringEquals(data["result"], "durably_accepted")
            || !(data["response_signature"] instanceof Lang.String)) {
            return false;
        }
        return secureEquals(
            data["response_signature"],
            responseSignature(keyHex, event["v"], event["event_id"])
        );
    }

    function failureResult(data) {
        if (data instanceof Lang.Dictionary
            && data["result"] instanceof Lang.String
            && (stringEquals(data["result"], "retryable_failure")
                || stringEquals(data["result"], "configuration_failure")
                || stringEquals(data["result"], "result_unknown"))) {
            return data["result"];
        }
        return "result_unknown";
    }

    function isSafeLiveConfiguration(authKey, encryptionKey, macKey, templateId, keyVersion) {
        return isSafeAuthKey(authKey)
            && isLowerHexKey(encryptionKey)
            && isLowerHexKey(macKey)
            && isLowerHex(templateId, 32)
            && keyVersion instanceof Lang.Number
            && keyVersion >= 1
            && !stringEquals(authKey, encryptionKey)
            && !stringEquals(authKey, macKey)
            && !stringEquals(encryptionKey, macKey)
            && !isPublicFixtureKey(encryptionKey)
            && !isPublicFixtureKey(macKey);
    }

    function truncateE7(value) {
        var scaled = value * 10000000.0d;
        return (scaled < 0 ? Math.ceil(scaled) : Math.floor(scaled)).toNumber();
    }

    function locationRecord(info, path) {
        var record = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]b;
        record[0] = 1;
        record[1] = 2;
        record[15] = path;
        if (info == null
            || info.position == null
            || info.when == null
            || info.accuracy == Position.QUALITY_NOT_AVAILABLE) {
            return record;
        }
        var captureAt = info.when.value();
        var coordinates = info.position.toDegrees();
        var latitude = truncateE7(coordinates[0]);
        var longitude = truncateE7(coordinates[1]);
        if (captureAt < 0
            || captureAt > MAX_TIME
            || latitude < -900000000
            || latitude > 900000000
            || longitude < -1800000000
            || longitude > 1800000000
            || info.accuracy < 0
            || info.accuracy > 4) {
            return record;
        }
        record.encodeNumber(captureAt, Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        record.encodeNumber(latitude, Lang.NUMBER_FORMAT_SINT32, {
            :offset => 6,
            :endianness => Lang.ENDIAN_BIG
        });
        record.encodeNumber(longitude, Lang.NUMBER_FORMAT_SINT32, {
            :offset => 10,
            :endianness => Lang.ENDIAN_BIG
        });
        record[14] = info.accuracy;
        return record;
    }
}

(:test)
function protocolConformance(logger) {
    var eventId = "AAECAwQFBgcICQoLDA0ODw";
    var deviceId = "EBESExQVFhcYGRobHB0eHw";
    var testEvent = PanicProtocol.newTestEvent(eventId, deviceId, 1788105600);
    var liveEvent = PanicProtocol.newEncryptedEventWithIv(
        PanicProtocol.V2_LIVE_KIND,
        eventId,
        eventId,
        deviceId,
        0,
        1788105600,
        1788109200,
        1,
        PanicProtocol.hexBytes("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"),
        PanicProtocol.PUBLIC_ENC_KEY,
        PanicProtocol.PUBLIC_MAC_KEY,
        PanicProtocol.hexBytes("606162636465666768696a6b6c6d6e6f")
    );
    var locationEvent = PanicProtocol.newEncryptedEventWithIv(
        PanicProtocol.V2_LOCATION_KIND,
        "sLGys7S1tre4ubq7vL2-vw",
        eventId,
        deviceId,
        1,
        1788105660,
        1788109200,
        1,
        PanicProtocol.hexBytes("01026a9453b2075bcd15e4c5f3ec0401"),
        PanicProtocol.PUBLIC_ENC_KEY,
        PanicProtocol.PUBLIC_MAC_KEY,
        PanicProtocol.hexBytes("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf")
    );
    var statusQuery = PanicProtocol.newStatusQuery(
        "ICEiIyQlJicoKSorLC0uLw",
        eventId,
        deviceId,
        1788105700,
        1788109200
    );
    var statusResult = {
        "v" => 2,
        "request_id" => "ICEiIyQlJicoKSorLC0uLw",
        "incident_id" => eventId,
        "device_id" => deviceId,
        "state" => "resolved",
        "checked_at" => 1788105701,
        "response_signature" => "v2=1PKgg7-Pz7Ko7_jtlrQaJoWxOLwI16D6FGCt4YnnzIM"
    };

    if (!PanicProtocol.isTestEvent(testEvent)) {
        if (!PanicProtocol.hasExactKeys(testEvent, PanicProtocol.EVENT_KEYS)) {
            logger.error("V1 event keys failed");
        } else if (!PanicProtocol.isCanonicalId(testEvent["event_id"])
            || !PanicProtocol.isCanonicalId(testEvent["device_id"])) {
            logger.error("V1 event identifiers failed");
        } else if (!(testEvent["created_at"] instanceof Lang.Number)
            || !(testEvent["expires_at"] instanceof Lang.Number)) {
            logger.error("V1 event timestamp types failed");
        } else if (testEvent["v"] != 1) {
            logger.error("V1 event version failed");
        } else if (!PanicProtocol.stringEquals(
            testEvent["incident_id"],
            testEvent["event_id"]
        )) {
            logger.error("V1 event incident failed");
        } else if (!PanicProtocol.stringEquals(testEvent["kind"], PanicProtocol.V1_KIND)) {
            logger.error("V1 event kind failed");
        } else if (testEvent["sequence"] != 0) {
            logger.error("V1 event sequence failed");
        } else if (testEvent["payload"] != null) {
            logger.error("V1 event payload failed");
        } else {
            logger.error(
                "V1 event timestamps failed: "
                + testEvent["created_at"].format("%d")
                + "/"
                + testEvent["expires_at"].format("%d")
            );
        }
        return false;
    }
    var v1RequestSignature = PanicProtocol.requestSignature(
        PanicProtocol.PUBLIC_AUTH_KEY,
        testEvent
    );
    if (!PanicProtocol.stringEquals(
        v1RequestSignature,
        "v1=8k8O8CI4Qixqv4CbzsfUo5kPAxekGoYsyssb7IeAZRs"
    )) {
        logger.error("V1 request signature failed: " + v1RequestSignature);
        return false;
    }
    var v1ResponseSignature = PanicProtocol.responseSignature(
        PanicProtocol.PUBLIC_AUTH_KEY,
        1,
        eventId
    );
    if (!PanicProtocol.stringEquals(
        v1ResponseSignature,
        "v1=6eCuAfV44rtvISQNtNPfUXpt50fm_U5sUj4POwx42UM"
    )) {
        logger.error("V1 response signature failed: " + v1ResponseSignature);
        return false;
    }
    if (!(PanicProtocol.isEncryptedEvent(liveEvent)
        && PanicProtocol.stringEquals(
            liveEvent["payload"]["ciphertext"],
            "8eRa_JOzxdPOO3l494xv5Q"
        )
        && PanicProtocol.stringEquals(
            liveEvent["payload"]["tag"],
            "QOA-t_kexwtJrWsQaj8FZuEb9TdOhPAcHCDMbgkrCB8"
        )
        && PanicProtocol.stringEquals(
            PanicProtocol.requestSignature(PanicProtocol.PUBLIC_AUTH_KEY, liveEvent),
            "v2=vkHWr3fYtcYij4GqeJJ49dJhDn38m26ifCTJAU3SknY"
        )
        && PanicProtocol.stringEquals(
            PanicProtocol.responseSignature(PanicProtocol.PUBLIC_AUTH_KEY, 2, eventId),
            "v2=Z40vnSWhJ7rbDRz6kO8nAh8-Qen5RGpl20xiiQ6kCpI"
        ))) {
        logger.error("LIVE v2 conformance vector failed");
        return false;
    }
    if (!(PanicProtocol.isEncryptedEvent(locationEvent)
        && PanicProtocol.stringEquals(
            locationEvent["payload"]["ciphertext"],
            "Ni1HgpKbRi0gcHT2Ms7Xkw"
        )
        && PanicProtocol.stringEquals(
            locationEvent["payload"]["tag"],
            "nMEC__4q1F-LpD8k3ISQ1i1zdYK2aO8LOzvDGlfEj-Y"
        )
        && PanicProtocol.stringEquals(
            PanicProtocol.requestSignature(PanicProtocol.PUBLIC_AUTH_KEY, locationEvent),
            "v2=uGLHdOkt0pA1daHA313hWEMUI2pdB5mQNuwQOB_uTM8"
        )
        && PanicProtocol.stringEquals(
            PanicProtocol.responseSignature(
                PanicProtocol.PUBLIC_AUTH_KEY,
                2,
                locationEvent["event_id"]
            ),
            "v2=fsU52lMaXLa4DAu00Awg8-uFZgePLPLim_P4OzRMTiQ"
        ))) {
        logger.error("Location v2 conformance vector failed");
        return false;
    }
    if (!(PanicProtocol.isStatusQuery(statusQuery)
        && PanicProtocol.stringEquals(
            PanicProtocol.statusRequestSignature(PanicProtocol.PUBLIC_AUTH_KEY, statusQuery),
            "v2=O7ik82fJBgz3-OwXGZeUALuSlufZvQT2Gr9rkFVnGdw"
        )
        && PanicProtocol.verifyStatusResult(
            statusResult,
            statusQuery,
            PanicProtocol.PUBLIC_AUTH_KEY,
            1788105701
        ))) {
        logger.error("Status v2 conformance vector failed");
        return false;
    }
    return true;
}

(:test)
function protocolRejectsMalformedEvents(logger) {
    var eventId = "AAECAwQFBgcICQoLDA0ODw";
    var deviceId = "EBESExQVFhcYGRobHB0eHw";
    var testEvent = PanicProtocol.newTestEvent(eventId, deviceId, 1788105600);

    if (!PanicProtocol.isTestEvent(testEvent)
        || PanicProtocol.isTestEvent(
            PanicProtocol.newTestEvent(eventId, deviceId, -1)
        )
        || PanicProtocol.isTestEvent(
            PanicProtocol.newTestEvent(
                eventId,
                deviceId,
                PanicProtocol.MAX_V1_CREATED_AT + 1
            )
        )
        || PanicProtocol.isCanonicalId("AAECAwQFBgcICQoLDA0ODx")
        || PanicProtocol.isCanonicalId("AAECAwQFBgcICQoLDA0ODw=")
        || PanicProtocol.isCanonicalDigest(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        )) {
        logger.error("Canonical identifier or V1 timestamp boundary failed");
        return false;
    }

    testEvent["extra"] = 1;
    if (PanicProtocol.isTestEvent(testEvent)) {
        logger.error("V1 event accepted an extra key");
        return false;
    }

    var liveEvent = PanicProtocol.newEncryptedEventWithIv(
        PanicProtocol.V2_LIVE_KIND,
        eventId,
        eventId,
        deviceId,
        0,
        1788105600,
        1788192000,
        1,
        PanicProtocol.hexBytes("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"),
        PanicProtocol.PUBLIC_ENC_KEY,
        PanicProtocol.PUBLIC_MAC_KEY,
        PanicProtocol.hexBytes("606162636465666768696a6b6c6d6e6f")
    );
    if (!PanicProtocol.isEncryptedEvent(liveEvent)) {
        logger.error("V2 maximum lifetime boundary failed");
        return false;
    }
    liveEvent["expires_at"] = 1788192001;
    if (PanicProtocol.isEncryptedEvent(liveEvent)) {
        logger.error("V2 event exceeded maximum lifetime");
        return false;
    }
    liveEvent["expires_at"] = 1788105600;
    if (PanicProtocol.isEncryptedEvent(liveEvent)) {
        logger.error("V2 event accepted a zero lifetime");
        return false;
    }
    liveEvent["expires_at"] = 1788109200;
    liveEvent["kind"] = PanicProtocol.V2_LOCATION_KIND;
    liveEvent["sequence"] = 1;
    if (PanicProtocol.isEncryptedEvent(liveEvent)) {
        logger.error("Location event reused its incident identifier");
        return false;
    }
    liveEvent["event_id"] = "sLGys7S1tre4ubq7vL2-vw";
    if (!PanicProtocol.isEncryptedEvent(liveEvent)) {
        logger.error("Valid location event was rejected");
        return false;
    }
    liveEvent["payload"]["tag"] = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAx";
    if (PanicProtocol.isEncryptedEvent(liveEvent)) {
        logger.error("V2 event accepted a non-canonical digest");
        return false;
    }
    return true;
}

(:test)
function protocolRejectsUnsafeConfiguration(logger) {
    var authKey = "11111111111111111111111111111111"
        + "11111111111111111111111111111111";
    var encryptionKey = "22222222222222222222222222222222"
        + "22222222222222222222222222222222";
    var macKey = "33333333333333333333333333333333"
        + "33333333333333333333333333333333";
    var templateId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    if (!PanicProtocol.isSafeAuthKey(authKey)
        || PanicProtocol.isSafeAuthKey(PanicProtocol.PUBLIC_AUTH_KEY)
        || PanicProtocol.isSafeAuthKey(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        )
        || !PanicProtocol.isSafeLiveConfiguration(
            authKey,
            encryptionKey,
            macKey,
            templateId,
            1
        )
        || PanicProtocol.isSafeLiveConfiguration(
            authKey,
            authKey,
            macKey,
            templateId,
            1
        )
        || PanicProtocol.isSafeLiveConfiguration(
            authKey,
            encryptionKey,
            encryptionKey,
            templateId,
            1
        )
        || PanicProtocol.isSafeLiveConfiguration(
            authKey,
            PanicProtocol.PUBLIC_MAC_KEY,
            macKey,
            templateId,
            1
        )
        || PanicProtocol.isSafeLiveConfiguration(
            authKey,
            encryptionKey,
            PanicProtocol.PUBLIC_ENC_KEY,
            templateId,
            1
        )
        || PanicProtocol.isSafeLiveConfiguration(
            authKey,
            encryptionKey,
            macKey,
            templateId,
            0
        )) {
        logger.error("LIVE key separation or fixture-key rejection failed");
        return false;
    }
    if (!PanicProtocol.isHttpsBaseUrl("https://alerts.example")
        || PanicProtocol.isHttpsBaseUrl("http://alerts.example")
        || PanicProtocol.isHttpsBaseUrl("https://alerts.example/")
        || PanicProtocol.isHttpsBaseUrl("https://alerts.example/v2")
        || PanicProtocol.isHttpsBaseUrl("https://user@alerts.example")
        || PanicProtocol.isHttpsBaseUrl("https://alerts.example?test=1")
        || PanicProtocol.isHttpsBaseUrl("https://alerts.example#fragment")) {
        logger.error("Relay origin validation failed");
        return false;
    }
    if (!PanicProtocol.isGrafanaWebhookUrl(
            "https://oncall-prod-eu-west-0.grafana.net/oncall/"
            + "integrations/v1/formatted_webhook/"
            + "AbCdEfGhIjKlMnOpQrStUvWxYz012345/"
        )
        || PanicProtocol.isGrafanaWebhookUrl(
            "http://oncall-prod-eu-west-0.grafana.net/oncall/"
            + "integrations/v1/formatted_webhook/"
            + "AbCdEfGhIjKlMnOpQrStUvWxYz012345/"
        )
        || PanicProtocol.isGrafanaWebhookUrl(
            "https://evil.example/oncall/integrations/v1/formatted_webhook/"
            + "AbCdEfGhIjKlMnOpQrStUvWxYz012345/"
        )
        || PanicProtocol.isGrafanaWebhookUrl(
            "https://oncall-prod-eu-west-0.grafana.net/oncall/"
            + "integrations/v1/formatted_webhook/short/"
        )
        || PanicProtocol.isGrafanaWebhookUrl(
            "https://oncall-prod-eu-west-0.grafana.net/oncall/"
            + "integrations/v1/formatted_webhook/"
            + "AbCdEfGhIjKlMnOpQrStUvWxYz012345/?leak=1"
        )) {
        logger.error("Grafana formatted-webhook URL validation failed");
        return false;
    }
    var dynamicValue = StringUtil.charArrayToString("durably_accepted".toCharArray());
    if (!PanicProtocol.stringEquals(dynamicValue, "durably_accepted")) {
        logger.error("String value equality failed");
        return false;
    }
    return true;
}

(:test)
function protocolRejectsTamperedResults(logger) {
    var eventId = "AAECAwQFBgcICQoLDA0ODw";
    var deviceId = "EBESExQVFhcYGRobHB0eHw";
    var testEvent = PanicProtocol.newTestEvent(eventId, deviceId, 1788105600);
    var accepted = {
        "v" => 1,
        "event_id" => eventId,
        "result" => "durably_accepted",
        "response_signature" => PanicProtocol.responseSignature(
            PanicProtocol.PUBLIC_AUTH_KEY,
            1,
            eventId
        )
    };
    if (!PanicProtocol.verifyDurablyAccepted(
        accepted,
        testEvent,
        PanicProtocol.PUBLIC_AUTH_KEY
    )) {
        logger.error("Valid durable result was rejected");
        return false;
    }
    accepted["response_signature"] =
        "v1=6eCuAfV44rtvISQNtNPfUXpt50fm_U5sUj4POwx42UA";
    if (PanicProtocol.verifyDurablyAccepted(
        accepted,
        testEvent,
        PanicProtocol.PUBLIC_AUTH_KEY
    )) {
        logger.error("Tampered durable signature was accepted");
        return false;
    }
    accepted["response_signature"] = PanicProtocol.responseSignature(
        PanicProtocol.PUBLIC_AUTH_KEY,
        1,
        eventId
    );
    accepted["extra"] = null;
    if (PanicProtocol.verifyDurablyAccepted(
        accepted,
        testEvent,
        PanicProtocol.PUBLIC_AUTH_KEY
    )) {
        logger.error("Durable result accepted an extra key");
        return false;
    }

    var query = PanicProtocol.newStatusQuery(
        "ICEiIyQlJicoKSorLC0uLw",
        eventId,
        deviceId,
        1788105700,
        1788109200
    );
    var status = {
        "v" => 2,
        "request_id" => "ICEiIyQlJicoKSorLC0uLw",
        "incident_id" => eventId,
        "device_id" => deviceId,
        "state" => "resolved",
        "checked_at" => 1788105701,
        "response_signature" => "v2=1PKgg7-Pz7Ko7_jtlrQaJoWxOLwI16D6FGCt4YnnzIM"
    };
    if (!PanicProtocol.verifyStatusResult(
        status,
        query,
        PanicProtocol.PUBLIC_AUTH_KEY,
        1788105701
    )) {
        logger.error("Valid status result was rejected");
        return false;
    }
    status["state"] = "closed";
    if (PanicProtocol.verifyStatusResult(
        status,
        query,
        PanicProtocol.PUBLIC_AUTH_KEY,
        1788105701
    )) {
        logger.error("Unknown status state was accepted");
        return false;
    }
    status["state"] = "resolved";
    status["request_id"] = "sLGys7S1tre4ubq7vL2-vw";
    if (PanicProtocol.verifyStatusResult(
        status,
        query,
        PanicProtocol.PUBLIC_AUTH_KEY,
        1788105701
    )) {
        logger.error("Mismatched status request was accepted");
        return false;
    }
    status["request_id"] = "ICEiIyQlJicoKSorLC0uLw";
    if (PanicProtocol.verifyStatusResult(
        status,
        query,
        PanicProtocol.PUBLIC_AUTH_KEY,
        1788106001
    )) {
        logger.error("Stale status result was accepted");
        return false;
    }
    if (!PanicProtocol.stringEquals(
        PanicProtocol.failureResult({"result" => "retryable_failure"}),
        "retryable_failure"
    ) || !PanicProtocol.stringEquals(
        PanicProtocol.failureResult({"result" => "untrusted"}),
        "result_unknown"
    )) {
        logger.error("Failure result classification failed closed");
        return false;
    }
    return true;
}

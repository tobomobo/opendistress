// SPDX-License-Identifier: MIT

import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.StringUtil;

module PanicProtocol {
    const KIND = "test.triggered";
    const SIGNATURE_HEADER_PREFIX = "v1=";

    const SUBMIT_DOMAIN = "spb.test.submit.v1";
    const RESULT_DOMAIN = "spb.test.result.v1";

    const LOWER_HEX = "0123456789abcdef";
    const BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    const ID_FINAL = "AQgw";
    const MAX_CREATED_AT = 2147482747;

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

    const ACCEPTED_KEYS = [
        "v",
        "event_id",
        "result",
        "provider",
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

    function isCanonicalId(value) {
        if (!(value instanceof Lang.String) || value.length() != 22) {
            return false;
        }

        var chars = value.toCharArray();
        for (var i = 0; i < chars.size(); i += 1) {
            if (BASE64URL.find(chars[i].toString()) == null) {
                return false;
            }
        }

        // Sixteen bytes encode to 22 unpadded base64url characters. The final
        // character has four zero pad bits, so only A, Q, g, and w are canonical.
        return ID_FINAL.find(chars[21].toString()) != null;
    }

    function isLowerHexKey(value) {
        if (!(value instanceof Lang.String) || value.length() != 64) {
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

    function isHttpsUrl(value) {
        return value instanceof Lang.String
            && value.length() > 8
            && value.find("https://") == 0;
    }

    function randomId() {
        return base64Url(Cryptography.randomBytes(16));
    }

    function newEvent(eventId, deviceId, createdAt) {
        var expiresAt = null;
        if (createdAt instanceof Lang.Number
            && createdAt >= 0
            && createdAt <= MAX_CREATED_AT) {
            expiresAt = createdAt + 900;
        }
        return {
            "v" => 1,
            "event_id" => eventId,
            "incident_id" => eventId,
            "device_id" => deviceId,
            "kind" => KIND,
            "sequence" => 0,
            "created_at" => createdAt,
            "expires_at" => expiresAt,
            "payload" => null
        };
    }

    function isEvent(value) {
        if (!hasExactKeys(value, EVENT_KEYS)) {
            return false;
        }

        return value["v"] == 1
            && isCanonicalId(value["event_id"])
            && value["incident_id"] == value["event_id"]
            && isCanonicalId(value["device_id"])
            && value["kind"] == KIND
            && value["sequence"] == 0
            && value["created_at"] instanceof Lang.Number
            && value["created_at"] >= 0
            && value["created_at"] <= MAX_CREATED_AT
            && value["expires_at"] instanceof Lang.Number
            && value["expires_at"] == value["created_at"] + 900
            && value["payload"] == null;
    }

    function submitSigningInput(event) {
        return SUBMIT_DOMAIN + "\n"
            + "method=POST\n"
            + "v=1\n"
            + "event_id=" + event["event_id"] + "\n"
            + "incident_id=" + event["incident_id"] + "\n"
            + "device_id=" + event["device_id"] + "\n"
            + "kind=" + KIND + "\n"
            + "sequence=0\n"
            + "created_at=" + event["created_at"].format("%d") + "\n"
            + "expires_at=" + event["expires_at"].format("%d") + "\n"
            + "payload=null\n";
    }

    function resultSigningInput(eventId) {
        return RESULT_DOMAIN + "\n"
            + "v=1\n"
            + "event_id=" + eventId + "\n"
            + "result=provider_accepted\n"
            + "provider=pushover\n";
    }

    function signature(keyHex, message) {
        var key = StringUtil.convertEncodedString(keyHex, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_HEX,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY
        });
        var bytes = StringUtil.convertEncodedString(message, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        var hmac = new Cryptography.HashBasedMessageAuthenticationCode({
            :algorithm => Cryptography.HASH_SHA256,
            :key => key
        });
        hmac.update(bytes);
        return SIGNATURE_HEADER_PREFIX + base64Url(hmac.digest());
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

    function verifyAccepted(data, eventId, keyHex) {
        if (!hasExactKeys(data, ACCEPTED_KEYS)
            || data["v"] != 1
            || data["event_id"] != eventId
            || data["result"] != "provider_accepted"
            || data["provider"] != "pushover"
            || !(data["response_signature"] instanceof Lang.String)) {
            return false;
        }

        var expected = signature(keyHex, resultSigningInput(eventId));
        return secureEquals(data["response_signature"], expected);
    }

    function failureResult(data) {
        if (data instanceof Lang.Dictionary
            && data["result"] instanceof Lang.String
            && (data["result"] == "retryable_failure"
                || data["result"] == "configuration_failure"
                || data["result"] == "result_unknown")) {
            return data["result"];
        }
        return "result_unknown";
    }
}

(:test)
function protocolConformance(logger) {
    var key = "000102030405060708090a0b0c0d0e0f"
        + "101112131415161718191a1b1c1d1e1f";
    var eventId = "AAECAwQFBgcICQoLDA0ODw";
    var deviceId = "EBESExQVFhcYGRobHB0eHw";
    var event = PanicProtocol.newEvent(eventId, deviceId, 1788105600);

    var submitInput = "spb.test.submit.v1\n"
        + "method=POST\n"
        + "v=1\n"
        + "event_id=AAECAwQFBgcICQoLDA0ODw\n"
        + "incident_id=AAECAwQFBgcICQoLDA0ODw\n"
        + "device_id=EBESExQVFhcYGRobHB0eHw\n"
        + "kind=test.triggered\n"
        + "sequence=0\n"
        + "created_at=1788105600\n"
        + "expires_at=1788106500\n"
        + "payload=null\n";
    var submitSignature = "v1=8k8O8CI4Qixqv4CbzsfUo5kPAxekGoYsyssb7IeAZRs";
    var resultSignature = "v1=K26Mm9HN9QqOm2BixauMET2vDwdSzIdLBE1ha9EAaEo";
    var accepted = {
        "v" => 1,
        "event_id" => eventId,
        "result" => "provider_accepted",
        "provider" => "pushover",
        "response_signature" => resultSignature
    };
    var ambiguous = { "result" => "result_unknown" };

    var passed = PanicProtocol.isEvent(event)
        && PanicProtocol.submitSigningInput(event) == submitInput
        && PanicProtocol.signature(key, submitInput) == submitSignature
        && PanicProtocol.verifyAccepted(accepted, eventId, key)
        && PanicProtocol.failureResult(ambiguous) == "result_unknown";
    if (!passed) {
        logger.error("Protocol conformance vector failed");
    }
    return passed;
}

// SPDX-License-Identifier: MIT

import Toybox.Application.Properties;
import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.StringUtil;

module DirectAlertSafety {
    function fingerprint(
        domain as Lang.String,
        keyText as Lang.String,
        valueText as Lang.String
    ) as Lang.String {
        if (domain.length() < 1
            || keyText.length() < 1) {
            return "";
        }
        var keyBytes = StringUtil.convertEncodedString(keyText, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        var messageBytes = StringUtil.convertEncodedString(
            domain + "\n" + valueText,
            {
                :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
                :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
                :encoding => StringUtil.CHAR_ENCODING_UTF8
            }
        );
        var hmac = new Cryptography.HashBasedMessageAuthenticationCode({
            :algorithm => Cryptography.HASH_SHA256,
            :key => keyBytes
        });
        hmac.update(messageBytes);
        return PanicProtocol.base64Url(hmac.digest());
    }

    function isBound(storedFingerprint, currentFingerprint) {
        if (!PanicProtocol.isCanonicalDigest(storedFingerprint)) {
            return false;
        }
        if (!PanicProtocol.isCanonicalDigest(currentFingerprint)) {
            return false;
        }
        return PanicProtocol.secureEquals(storedFingerprint, currentFingerprint);
    }

    function isActiveRoute(
        accepted,
        storedFingerprint,
        currentFingerprint
    ) {
        if (!(accepted instanceof Lang.Boolean) || !accepted) {
            return false;
        }
        return isBound(storedFingerprint, currentFingerprint);
    }

    function isFreshCapture(acceptedAt, captureAt, now) {
        if (!(acceptedAt instanceof Lang.Number)
            || !(captureAt instanceof Lang.Number)
            || !(now instanceof Lang.Number)) {
            return false;
        }
        if (acceptedAt <= 0 || captureAt < acceptedAt) {
            return false;
        }
        return captureAt <= now;
    }
}

module DirectAlertProfile {
    const TEST_MESSAGE =
        "KEIN ECHTER NOTFALL. Garmin Testausloesung; keine Hilfeleistung erforderlich.";
    const LOCATION_MESSAGE =
        "KEIN ECHTER NOTFALL. Aktueller Garmin GPS-Teststandort.";
    const TEST_TITLE = "TESTNOTRUF";
    const LOCATION_TITLE = "TESTNOTRUF — GPS";
    const PUSHOVER_MAX_MESSAGE_CHARACTERS = 1024;

    function optionalText(propertyKey, maxLength) {
        var value = Properties.getValue(propertyKey);
        if (!(value instanceof Lang.String)
            || value.length() < 1
            || value.length() > maxLength) {
            return "";
        }
        return value as Lang.String;
    }

    function personName() {
        return optionalText("protectedPersonName", 40);
    }

    function photoUrl() {
        var value = optionalText("profilePhotoUrl", 512);
        if (value.length() < 9
            || value.find("https://") != 0
            || value.find("@") != null
            || value.find("#") != null) {
            return "";
        }
        return value;
    }

    function fields() {
        return {
            "person_name" => personName(),
            "home_address" => optionalText("homeAddress", 120),
            "children_info" => optionalText("childrenInfo", 150),
            "person_description" => optionalText("personDescription", 150),
            "background_info" => optionalText("backgroundInfo", 180),
            "response_instructions" => optionalText("responseInstructions", 180),
            "profile_photo_url" => photoUrl()
        };
    }

    function personalizedTitle(baseTitle) {
        var name = personName();
        return name.length() > 0 ? baseTitle + " — " + name : baseTitle;
    }

    function appendSection(message, label, value) {
        return value.length() > 0 ? message + "\n\n" + label + "\n" + value : message;
    }

    function pushoverMessage() {
        var profile = fields();
        var message = TEST_MESSAGE;
        message = appendSection(message, "PERSON", profile["person_name"]);
        message = appendSection(message, "HEIMADRESSE", profile["home_address"]);
        message = appendSection(message, "KINDER / FAMILIE", profile["children_info"]);
        message = appendSection(
            message,
            "PERSONENBESCHREIBUNG",
            profile["person_description"]
        );
        message = appendSection(message, "HINTERGRUND", profile["background_info"]);
        message = appendSection(
            message,
            "HINWEISE FUER HELFER",
            profile["response_instructions"]
        );
        return message.length() <= PUSHOVER_MAX_MESSAGE_CHARACTERS
            ? message
            : TEST_MESSAGE;
    }
}

module DirectPushoverAdapter {
    const ENDPOINT = "https://api.pushover.net/1/messages.json";
    const TOKEN_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    function isConfigured() {
        return isToken(Properties.getValue("pushoverUserKey"))
            && isToken(Properties.getValue("pushoverApiToken"));
    }

    function isToken(value) {
        if (!(value instanceof Lang.String) || value.length() != 30) {
            return false;
        }
        var characters = value.toCharArray();
        for (var i = 0; i < characters.size(); i += 1) {
            if (TOKEN_ALPHABET.find(characters[i].toString()) == null) {
                return false;
            }
        }
        return true;
    }

    function configurationFingerprint() as Lang.String {
        return configurationFingerprintFor(
            Properties.getValue("pushoverUserKey"),
            Properties.getValue("pushoverApiToken")
        );
    }

    function configurationFingerprintFor(userKey, apiToken) as Lang.String {
        if (!isToken(userKey) || !isToken(apiToken)) {
            return "";
        }
        var validatedUserKey = userKey as Lang.String;
        var validatedApiToken = apiToken as Lang.String;
        return DirectAlertSafety.fingerprint(
            "spb.direct.pushover.config.v1",
            validatedApiToken,
            "user=" + validatedUserKey + "\ntoken=" + validatedApiToken + "\n"
        );
    }

    function initialParameters(event, now) {
        var parameters = {
            "token" => Properties.getValue("pushoverApiToken"),
            "user" => Properties.getValue("pushoverUserKey"),
            "title" => DirectAlertProfile.personalizedTitle(DirectAlertProfile.TEST_TITLE),
            "message" => DirectAlertProfile.pushoverMessage(),
            "priority" => "2",
            "retry" => "30",
            "expire" => (event["expires_at"] - now).toString()
        };
        var photoUrl = DirectAlertProfile.photoUrl();
        if (photoUrl.length() > 0) {
            parameters["url"] = photoUrl;
            parameters["url_title"] = "Open profile photo";
        }
        return parameters;
    }

    function locationParameters(sequence, captureAt, mapUrl) {
        return {
            "token" => Properties.getValue("pushoverApiToken"),
            "user" => Properties.getValue("pushoverUserKey"),
            "title" => DirectAlertProfile.personalizedTitle(
                DirectAlertProfile.LOCATION_TITLE
            ),
            "message" => DirectAlertProfile.LOCATION_MESSAGE
                + " Update " + sequence.format("%d") + ".",
            "priority" => sequence == 1 ? "1" : "0",
            "timestamp" => captureAt.format("%d"),
            "url" => mapUrl,
            "url_title" => "Open current location"
        };
    }
}

module DirectGrafanaAdapter {
    function isConfigured() {
        return PanicProtocol.isGrafanaWebhookUrl(
            Properties.getValue("grafanaWebhookUrl")
        );
    }

    function endpoint() {
        return Properties.getValue("grafanaWebhookUrl");
    }

    function configurationFingerprint() as Lang.String {
        return configurationFingerprintFor(endpoint());
    }

    function configurationFingerprintFor(webhookUrl) as Lang.String {
        if (!PanicProtocol.isGrafanaWebhookUrl(webhookUrl)) {
            return "";
        }
        var validatedWebhookUrl = webhookUrl as Lang.String;
        return DirectAlertSafety.fingerprint(
            "spb.direct.grafana.config.v1",
            validatedWebhookUrl,
            "webhook=" + validatedWebhookUrl + "\n"
        );
    }

    function initialPayload(eventId) {
        var profile = DirectAlertProfile.fields();
        return profilePayload(
            eventId,
            DirectAlertProfile.personalizedTitle(DirectAlertProfile.TEST_TITLE),
            DirectAlertProfile.TEST_MESSAGE,
            "",
            profile
        );
    }

    function locationPayload(eventId, sequence, mapUrl) {
        var profile = DirectAlertProfile.fields();
        return profilePayload(
            eventId,
            DirectAlertProfile.personalizedTitle(DirectAlertProfile.LOCATION_TITLE),
            DirectAlertProfile.LOCATION_MESSAGE
                + " Update " + sequence.format("%d") + ". " + mapUrl,
            mapUrl,
            profile
        );
    }

    function profilePayload(eventId, title, message, sourceLink, profile) {
        var payload = {
            "alert_uid" => eventId,
            "title" => title,
            "state" => "alerting",
            "message" => message,
            "person_name" => profile["person_name"],
            "home_address" => profile["home_address"],
            "children_info" => profile["children_info"],
            "person_description" => profile["person_description"],
            "background_info" => profile["background_info"],
            "response_instructions" => profile["response_instructions"],
            "profile_photo_url" => profile["profile_photo_url"]
        };
        if (sourceLink.length() > 0) {
            payload["link_to_upstream_details"] = sourceLink;
        }
        return payload;
    }
}

(:test)
function directProviderSafetyTransitions(logger) {
    var userKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    var firstToken = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    var secondToken = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
    var firstPushover = DirectPushoverAdapter.configurationFingerprintFor(
        userKey,
        firstToken
    );
    var secondPushover = DirectPushoverAdapter.configurationFingerprintFor(
        userKey,
        secondToken
    );
    var firstGrafana = DirectGrafanaAdapter.configurationFingerprintFor(
        "https://oncall-prod-eu-west-0.grafana.net/oncall/"
        + "integrations/v1/formatted_webhook/"
        + "AbCdEfGhIjKlMnOpQrStUvWxYz012345/"
    );
    var secondGrafana = DirectGrafanaAdapter.configurationFingerprintFor(
        "https://oncall-prod-eu-west-0.grafana.net/oncall/"
        + "integrations/v1/formatted_webhook/"
        + "ZyXwVuTsRqPoNmLkJiHgFeDcBa543210/"
    );
    if (!DirectAlertSafety.isBound(firstPushover, firstPushover)) {
        logger.error("Pushover self-binding failed");
        return false;
    }
    if (DirectAlertSafety.isBound(firstPushover, secondPushover)) {
        logger.error("Pushover destination change remained bound");
        return false;
    }
    if (!DirectAlertSafety.isBound(firstGrafana, firstGrafana)) {
        logger.error("Grafana self-binding failed");
        return false;
    }
    if (DirectAlertSafety.isBound(firstGrafana, secondGrafana)) {
        logger.error("Grafana destination change remained bound");
        return false;
    }
    if (!DirectAlertSafety.isActiveRoute(true, firstGrafana, firstGrafana)) {
        logger.error("Accepted matching route was not active");
        return false;
    }
    if (DirectAlertSafety.isActiveRoute(true, firstGrafana, secondGrafana)) {
        logger.error("Changed route remained active");
        return false;
    }
    if (DirectAlertSafety.isActiveRoute(false, firstGrafana, firstGrafana)) {
        logger.error("Direct-provider destination binding failed");
        return false;
    }
    if (DirectAlertSafety.isFreshCapture(100, 99, 101)) {
        logger.error("Pre-acceptance position was treated as fresh");
        return false;
    }
    if (!DirectAlertSafety.isFreshCapture(100, 100, 101)) {
        logger.error("Acceptance-time position was rejected");
        return false;
    }
    if (!DirectAlertSafety.isFreshCapture(100, 101, 101)) {
        logger.error("Current position was rejected");
        return false;
    }
    if (DirectAlertSafety.isFreshCapture(100, 102, 101)) {
        logger.error("Direct-provider fresh-position boundary failed");
        return false;
    }
    return true;
}

// SPDX-License-Identifier: MIT

import Toybox.Application.Properties;
import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.StringUtil;

module DirectAlertSafety {
    const GPS_STALE_AFTER_SECONDS = 30;

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

    function captureAgeSeconds(captureAt, now) {
        if (!(captureAt instanceof Lang.Number)
            || !(now instanceof Lang.Number)
            || captureAt <= 0
            || captureAt > now) {
            return -1;
        }
        return now - captureAt;
    }

    function isPossiblyStaleLocation(path, ageSeconds) {
        return !(path instanceof Lang.Number)
            || path != 1
            || !(ageSeconds instanceof Lang.Number)
            || ageSeconds < 0
            || ageSeconds > GPS_STALE_AFTER_SECONDS;
    }
}

module DirectAlertProfile {
    const TEST_MESSAGE =
        "KEIN ECHTER NOTFALL. Garmin Testausloesung; keine Hilfeleistung erforderlich.";
    const TEST_TITLE = "TESTNOTRUF";
    const PUSHOVER_MAX_MESSAGE_CHARACTERS = 1024;
    const PUSHOVER_ALERT_MESSAGE_CHARACTERS = 160;
    const PUSHOVER_RESPONSE_CHARACTERS = 170;
    const PUSHOVER_NAME_CHARACTERS = 40;
    const PUSHOVER_DESCRIPTION_CHARACTERS = 100;
    const PUSHOVER_CHILDREN_CHARACTERS = 100;
    const PUSHOVER_ADDRESS_CHARACTERS = 100;
    const PUSHOVER_BACKGROUND_CHARACTERS = 90;

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

    function alertMessage() {
        return optionalText("customAlertMessage", 240);
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
            "alert_message" => alertMessage(),
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

    function initialMessage() {
        return appendSection(TEST_MESSAGE, "VORBEREITETE NACHRICHT", alertMessage());
    }

    function locationTitle(sequence) {
        return "GPS-UPDATE " + sequence.format("%d") + " — TESTNOTRUF";
    }

    function locationMessage(sequence, path, ageSeconds, mapUrl) {
        var status;
        if (path != 1) {
            status = "WARNUNG: letzter bekannter "
                + "Garmin-GPS-Teststandort; moeglicherweise veraltet.";
        } else if (DirectAlertSafety.isPossiblyStaleLocation(path, ageSeconds)) {
            status = "WARNUNG: Garmin-GPS-Teststandort "
                + "ist moeglicherweise veraltet.";
        } else {
            status = "Aktueller Garmin-GPS-Teststandort.";
        }
        return "GPS-UPDATE " + sequence.format("%d") + "\n\n"
            + "TESTMODUS — KEIN ECHTER NOTFALL\n\n"
            + "GPS-STATUS\n" + status
            + "\n\nGPS-ALTER LAUT UHR\n" + ageSeconds.format("%d") + " s"
            + "\n\nKARTE\n" + mapUrl;
    }

    function appendSection(message, label, value) {
        return value.length() > 0 ? message + "\n\n" + label + "\n" + value : message;
    }

    function clippedText(value, maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    function appendClippedSection(message, label, value, maxLength) {
        return appendSection(message, label, clippedText(value, maxLength));
    }

    function pushoverMessage() {
        var profile = fields();
        var message = TEST_MESSAGE;
        message = appendClippedSection(message, "VORBEREITETE NACHRICHT",
            profile["alert_message"], PUSHOVER_ALERT_MESSAGE_CHARACTERS);
        message = appendClippedSection(message, "HINWEISE FUER HELFER",
            profile["response_instructions"], PUSHOVER_RESPONSE_CHARACTERS);
        message = appendClippedSection(message, "PERSON MIT DER UHR",
            profile["person_name"], PUSHOVER_NAME_CHARACTERS);
        message = appendClippedSection(
            message,
            "BESCHREIBUNG DIESER PERSON",
            profile["person_description"],
            PUSHOVER_DESCRIPTION_CHARACTERS
        );
        message = appendClippedSection(message, "KINDER / FAMILIE",
            profile["children_info"], PUSHOVER_CHILDREN_CHARACTERS);
        message = appendClippedSection(message, "HEIMADRESSE",
            profile["home_address"], PUSHOVER_ADDRESS_CHARACTERS);
        message = appendClippedSection(message, "HINTERGRUND",
            profile["background_info"], PUSHOVER_BACKGROUND_CHARACTERS);
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

    function locationParameters(sequence, captureAt, path, ageSeconds, mapUrl) {
        var possiblyStale = DirectAlertSafety.isPossiblyStaleLocation(
            path,
            ageSeconds
        );
        return {
            "token" => Properties.getValue("pushoverApiToken"),
            "user" => Properties.getValue("pushoverUserKey"),
            "title" => DirectAlertProfile.personalizedTitle(
                DirectAlertProfile.locationTitle(sequence)
            ),
            "message" => DirectAlertProfile.locationMessage(
                sequence,
                path,
                ageSeconds,
                mapUrl
            ),
            "priority" => sequence == 1 ? "1" : "0",
            "timestamp" => captureAt.format("%d"),
            "url" => mapUrl,
            "url_title" => possiblyStale
                ? "Open possibly stale location"
                : "Open current location"
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
            DirectAlertProfile.initialMessage(),
            "",
            profile
        );
    }

    function locationPayload(
        eventId,
        sequence,
        captureAt,
        path,
        ageSeconds,
        mapUrl
    ) {
        var profile = DirectAlertProfile.fields();
        var payload = profilePayload(
            eventId,
            DirectAlertProfile.personalizedTitle(
                DirectAlertProfile.locationTitle(sequence)
            ),
            DirectAlertProfile.locationMessage(sequence, path, ageSeconds, mapUrl),
            mapUrl,
            profile
        );
        payload["gps_capture_time"] = captureAt;
        payload["gps_age_seconds"] = ageSeconds;
        payload["gps_fix_kind"] = path == 1 ? "live_callback" : "last_known";
        payload["gps_may_be_stale"] = DirectAlertSafety.isPossiblyStaleLocation(
            path,
            ageSeconds
        );
        return payload;
    }

    function profilePayload(eventId, title, message, sourceLink, profile) {
        var payload = {
            "alert_uid" => eventId,
            "title" => title,
            "state" => "alerting",
            "message" => message,
            "alert_message" => profile["alert_message"],
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
    if (DirectAlertSafety.captureAgeSeconds(100, 130) != 30
        || DirectAlertSafety.captureAgeSeconds(131, 130) != -1) {
        logger.error("GPS capture age boundary failed");
        return false;
    }
    if (!DirectAlertSafety.isPossiblyStaleLocation(0, 0)) {
        logger.error("Last-known GPS snapshot was presented as current");
        return false;
    }
    if (DirectAlertSafety.isPossiblyStaleLocation(1, 30)) {
        logger.error("Fresh continuous GPS callback was marked stale");
        return false;
    }
    if (!DirectAlertSafety.isPossiblyStaleLocation(1, 31)) {
        logger.error("Old continuous GPS callback was presented as current");
        return false;
    }
    if (!PanicProtocol.stringEquals(
            DirectAlertProfile.locationTitle(2),
            "GPS-UPDATE 2 — TESTNOTRUF"
        )) {
        logger.error("GPS update title is not update-first");
        return false;
    }
    var expectedLocationMessage = "GPS-UPDATE 2\n\n"
        + "TESTMODUS — KEIN ECHTER NOTFALL\n\n"
        + "GPS-STATUS\nAktueller Garmin-GPS-Teststandort.\n\n"
        + "GPS-ALTER LAUT UHR\n5 s\n\n"
        + "KARTE\nhttps://maps.google.com/?q=1,2";
    if (!PanicProtocol.stringEquals(
            DirectAlertProfile.locationMessage(
                2,
                1,
                5,
                "https://maps.google.com/?q=1,2"
            ),
            expectedLocationMessage
        )) {
        logger.error("GPS update message lost its section formatting");
        return false;
    }
    if (!PanicProtocol.stringEquals(
            DirectAlertProfile.clippedText("123456", 5),
            "12..."
        )
        || !PanicProtocol.stringEquals(
            DirectAlertProfile.clippedText("12345", 5),
            "12345"
        )) {
        logger.error("Pushover profile clipping boundary failed");
        return false;
    }
    return true;
}

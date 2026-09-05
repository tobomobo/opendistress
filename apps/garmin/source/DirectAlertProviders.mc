// SPDX-License-Identifier: MIT

import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.Position;
import Toybox.StringUtil;

module DirectAlertSettings {
    var _cachedConfig = null;
    var _cacheLoaded = false;

    function invalidateCache() {
        _cachedConfig = null;
        _cacheLoaded = false;
    }
    const STORAGE_KEY = "companion_direct_config_v1";
    const PROTOCOL = "opendistress.companion.v1";
    const CONFIG_KEYS = [
        "protocol", "type", "revision", "grafanaWebhookUrl",
        "pushoverUserKey", "pushoverApiToken", "protectedPersonName",
        "customAlertMessage", "homeAddress", "childrenInfo",
        "personDescription", "backgroundInfo", "responseInstructions",
        "profilePhotoUrl", "config_digest"
    ];
    const VALUE_KEYS = [
        "grafanaWebhookUrl", "pushoverUserKey", "pushoverApiToken",
        "protectedPersonName", "customAlertMessage", "homeAddress",
        "childrenInfo", "personDescription", "backgroundInfo",
        "responseInstructions", "profilePhotoUrl"
    ];
    const VALUE_LIMITS = [512, 30, 30, 40, 240, 120, 150, 150, 180, 180, 512];

    function value(key) {
        var stored = storedConfig();
        return stored != null ? stored[key] : Properties.getValue(key);
    }

    function companionDigest() {
        var stored = storedConfig();
        return stored == null ? "" : stored["config_digest"];
    }

    function hapticsEnabled() {
        var stored = storedConfig();
        return stored == null || !stored.hasKey("hapticFeedback");
    }

    function storedConfig() {
        // Profile rendering reads many fields in one callback. Validate once,
        // not once per field, to stay within the physical watch watchdog budget.
        if (_cacheLoaded) {
            return _cachedConfig;
        }
        var stored = Storage.getValue(STORAGE_KEY);
        _cachedConfig = validConfig(stored) ? stored : null;
        _cacheLoaded = true;
        return _cachedConfig;
    }

    function install(message) {
        // Re-read durable state for each incoming revision; never compare an
        // incoming update against a cached snapshot from an earlier callback.
        invalidateCache();
        if (!validConfig(message)) {
            return false;
        }
        var current = storedConfig();
        if (current != null) {
            var currentRevision = current["revision"].toLong();
            var nextRevision = message["revision"].toLong();
            if (currentRevision == null || nextRevision == null
                || nextRevision < currentRevision
                || (nextRevision == currentRevision
                    && !OpenDistressProtocol.stringEquals(
                        current["config_digest"], message["config_digest"]
                    ))) {
                return false;
            }
        }
        invalidateCache();
        Storage.setValue(STORAGE_KEY, message);
        var reloaded = Storage.getValue(STORAGE_KEY);
        if (!validConfig(reloaded)) {
            return false;
        }
        var reloadedConfig = reloaded as Lang.Dictionary;
        var matches = OpenDistressProtocol.secureEquals(
            message["config_digest"], reloadedConfig["config_digest"]
        );
        if (matches) {
            _cachedConfig = reloadedConfig;
            _cacheLoaded = true;
        }
        return matches;
    }

    function validConfig(value) {
        var keys = CONFIG_KEYS;
        if (value instanceof Lang.Dictionary && value.hasKey("hapticFeedback")) {
            if (!OpenDistressProtocol.stringEquals(value["hapticFeedback"], "false")) {
                return false;
            }
            keys = CONFIG_KEYS.slice(0, CONFIG_KEYS.size());
            keys.add("hapticFeedback");
        }
        if (!OpenDistressProtocol.hasExactKeys(value, keys)
            || !OpenDistressProtocol.stringEquals(value["protocol"], PROTOCOL)
            || !OpenDistressProtocol.stringEquals(value["type"], "config")
            || !validPositiveDecimal(value["revision"])) {
            return false;
        }
        for (var i = 0; i < VALUE_KEYS.size(); i += 1) {
            var field = value[VALUE_KEYS[i]];
            if (!(field instanceof Lang.String) || field.length() > VALUE_LIMITS[i]) {
                return false;
            }
        }
        if (!OpenDistressProtocol.isGrafanaWebhookUrl(value["grafanaWebhookUrl"])
            && value["grafanaWebhookUrl"].length() > 0) {
            return false;
        }
        var hasPushover = DirectPushoverAdapter.isToken(value["pushoverUserKey"])
            && DirectPushoverAdapter.isToken(value["pushoverApiToken"]);
        if ((value["pushoverUserKey"].length() > 0
                || value["pushoverApiToken"].length() > 0) && !hasPushover) {
            return false;
        }
        if (value["grafanaWebhookUrl"].length() == 0 && !hasPushover) {
            return false;
        }
        return OpenDistressProtocol.isCanonicalDigest(value["config_digest"])
            && OpenDistressProtocol.secureEquals(
                value["config_digest"], configDigest(value)
            );
    }

    function validPositiveDecimal(value) {
        if (!(value instanceof Lang.String)
            || value.length() < 1 || value.length() > 19
            || (value.find("0") == 0 && value.length() > 1)) {
            return false;
        }
        var chars = value.toCharArray();
        for (var i = 0; i < chars.size(); i += 1) {
            if ("0123456789".find(chars[i].toString()) == null) {
                return false;
            }
        }
        var parsed = value.toLong();
        return parsed != null && parsed > 0;
    }

    function configDigest(value) {
        var canonical = PROTOCOL + "\nrevision=" + value["revision"] + "\n";
        for (var i = 0; i < VALUE_KEYS.size(); i += 1) {
            canonical += VALUE_KEYS[i] + "=" + encodedText(value[VALUE_KEYS[i]]) + "\n";
        }
        if (value.hasKey("hapticFeedback")) {
            canonical += "hapticFeedback=" + encodedText(value["hapticFeedback"]) + "\n";
        }
        var bytes = StringUtil.convertEncodedString(canonical, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        var hash = new Cryptography.Hash({ :algorithm => Cryptography.HASH_SHA256 });
        hash.update(bytes);
        return OpenDistressProtocol.base64Url(hash.digest());
    }

    function encodedText(value) {
        var bytes = StringUtil.convertEncodedString(value, {
            :fromRepresentation => StringUtil.REPRESENTATION_STRING_PLAIN_TEXT,
            :toRepresentation => StringUtil.REPRESENTATION_BYTE_ARRAY,
            :encoding => StringUtil.CHAR_ENCODING_UTF8
        });
        return OpenDistressProtocol.base64Url(bytes);
    }
}

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
        return OpenDistressProtocol.base64Url(hmac.digest());
    }

    function isBound(storedFingerprint, currentFingerprint) {
        if (!OpenDistressProtocol.isCanonicalDigest(storedFingerprint)) {
            return false;
        }
        if (!OpenDistressProtocol.isCanonicalDigest(currentFingerprint)) {
            return false;
        }
        return OpenDistressProtocol.secureEquals(storedFingerprint, currentFingerprint);
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

    function isUsableLastKnownCapture(captureAt, now) {
        return captureAt instanceof Lang.Number
            && now instanceof Lang.Number
            && captureAt > 0
            && captureAt <= now;
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
            || (path != 1 && path != 3)
            || !(ageSeconds instanceof Lang.Number)
            || ageSeconds < 0
            || ageSeconds > GPS_STALE_AFTER_SECONDS;
    }
}

module DirectAlertProfile {
    const TEST_MESSAGE =
        "KEIN ECHTER NOTFALL. NUR UEBUNG: keine Polizei verstaendigen. OpenDistress Testausloesung.";
    const TEST_TITLE = "TESTNOTRUF — OPENDISTRESS";
    const PUSHOVER_MAX_MESSAGE_CHARACTERS = 1024;
    const PUSHOVER_ALERT_MESSAGE_CHARACTERS = 160;
    const PUSHOVER_RESPONSE_CHARACTERS = 180;
    const PUSHOVER_NAME_CHARACTERS = 40;
    const PUSHOVER_DESCRIPTION_CHARACTERS = 100;
    const PUSHOVER_CHILDREN_CHARACTERS = 100;
    const PUSHOVER_ADDRESS_CHARACTERS = 100;
    const PUSHOVER_BACKGROUND_CHARACTERS = 90;

    function optionalText(propertyKey, maxLength) {
        var value = DirectAlertSettings.value(propertyKey);
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
        var message = appendSection(TEST_MESSAGE, "REAKTIONSPLAN (NUR UEBUNG)",
            optionalText("responseInstructions", 180));
        return appendSection(message, "VORBEREITETE NACHRICHT", alertMessage());
    }

    function locationTitle(sequence) {
        return "GPS-UPDATE " + sequence.format("%d")
            + " — TESTNOTRUF — OPENDISTRESS";
    }

    function locationSource(path) {
        if (path == 3) {
            return "Fused-Standort des Android-Handys";
        }
        if (path == 2) {
            return "Laufende Garmin-Sportaufzeichnung";
        }
        return path == 1 ? "Live-GPS der Uhr" : "Letzter bekannter Uhrenstandort";
    }

    function locationQuality(path, quality) {
        if (path == 3) {
            return quality >= 255
                ? "255 m oder ungenauer (vom Handy gemeldet)"
                : quality.format("%d") + " m (vom Handy gemeldet)";
        }
        if (quality == Position.QUALITY_GOOD) {
            return "gut";
        }
        if (quality == Position.QUALITY_USABLE) {
            return "verwendbar";
        }
        if (quality == Position.QUALITY_POOR) {
            return "schwach / nur 2D";
        }
        if (quality == Position.QUALITY_LAST_KNOWN) {
            return "nur letzter bekannter Fix";
        }
        return "unbekannt";
    }

    function locationMessage(sequence, path, quality, ageSeconds, mapUrl) {
        var status;
        if (path == 3) {
            status = DirectAlertSafety.isPossiblyStaleLocation(path, ageSeconds)
                ? "WARNUNG: Android-Fused-Teststandort ist moeglicherweise veraltet."
                : "Aktueller Android-Fused-Teststandort.";
        } else if (path != 1) {
            status = path == 2
                ? "WARNUNG: Garmin-Sport-GPS hat keinen Fix-Zeitstempel; "
                    + "moeglicherweise veraltet."
                : "WARNUNG: letzter bekannter Garmin-GPS-Teststandort; "
                    + "moeglicherweise veraltet.";
        } else if (DirectAlertSafety.isPossiblyStaleLocation(path, ageSeconds)) {
            status = "WARNUNG: Garmin-GPS-Teststandort "
                + "ist moeglicherweise veraltet.";
        } else {
            status = "Aktueller Garmin-GPS-Teststandort.";
        }
        return "GPS-UPDATE " + sequence.format("%d") + "\n\n"
            + "TESTMODUS — KEIN ECHTER NOTFALL\n\n"
            + "GPS-STATUS\n" + status
            + "\nQuelle: " + locationSource(path)
            + "\nGenauigkeit: " + locationQuality(path, quality)
            + "\n\nGPS-ALTER LAUT UHR\n"
            + (path == 2
                ? "Nicht verfuegbar; vor " + ageSeconds.format("%d") + " s ausgelesen"
                : ageSeconds.format("%d") + " s")
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
        message = appendClippedSection(message, "REAKTIONSPLAN (NUR UEBUNG)",
            profile["response_instructions"], PUSHOVER_RESPONSE_CHARACTERS);
        message = appendClippedSection(message, "VORBEREITETE NACHRICHT",
            profile["alert_message"], PUSHOVER_ALERT_MESSAGE_CHARACTERS);
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
        message = appendClippedSection(message, "HEIMADRESSE (NICHT GPS)",
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
        return isToken(DirectAlertSettings.value("pushoverUserKey"))
            && isToken(DirectAlertSettings.value("pushoverApiToken"));
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
            DirectAlertSettings.value("pushoverUserKey"),
            DirectAlertSettings.value("pushoverApiToken")
        );
    }

    function configurationFingerprintFor(userKey, apiToken) as Lang.String {
        if (!isToken(userKey) || !isToken(apiToken)) {
            return "";
        }
        var validatedUserKey = userKey as Lang.String;
        var validatedApiToken = apiToken as Lang.String;
        return DirectAlertSafety.fingerprint(
            "opendistress.direct.pushover.config.v1",
            validatedApiToken,
            "user=" + validatedUserKey + "\ntoken=" + validatedApiToken + "\n"
        );
    }

    function initialParameters(event, now) {
        var parameters = {
            "token" => DirectAlertSettings.value("pushoverApiToken"),
            "user" => DirectAlertSettings.value("pushoverUserKey"),
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

    function locationParameters(sequence, sentAt, path, quality, ageSeconds, mapUrl) {
        var possiblyStale = DirectAlertSafety.isPossiblyStaleLocation(
            path,
            ageSeconds
        );
        return {
            "token" => DirectAlertSettings.value("pushoverApiToken"),
            "user" => DirectAlertSettings.value("pushoverUserKey"),
            "title" => DirectAlertProfile.personalizedTitle(
                DirectAlertProfile.locationTitle(sequence)
            ),
            "message" => DirectAlertProfile.locationMessage(
                sequence,
                path,
                quality,
                ageSeconds,
                mapUrl
            ),
            "priority" => sequence == 1 ? "1" : "0",
            // Keep the notification current even when its clearly labeled GPS fix is old.
            "timestamp" => sentAt.format("%d"),
            "url" => mapUrl,
            "url_title" => possiblyStale
                ? "Open possibly stale location"
                : "Open current location"
        };
    }
}

module DirectGrafanaAdapter {
    function isConfigured() {
        return OpenDistressProtocol.isGrafanaWebhookUrl(
            DirectAlertSettings.value("grafanaWebhookUrl")
        );
    }

    function endpoint() {
        return DirectAlertSettings.value("grafanaWebhookUrl");
    }

    function configurationFingerprint() as Lang.String {
        return configurationFingerprintFor(endpoint());
    }

    function configurationFingerprintFor(webhookUrl) as Lang.String {
        if (!OpenDistressProtocol.isGrafanaWebhookUrl(webhookUrl)) {
            return "";
        }
        var validatedWebhookUrl = webhookUrl as Lang.String;
        return DirectAlertSafety.fingerprint(
            "opendistress.direct.grafana.config.v1",
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
        quality,
        ageSeconds,
        mapUrl
    ) {
        var profile = DirectAlertProfile.fields();
        var payload = profilePayload(
            eventId,
            DirectAlertProfile.personalizedTitle(
                DirectAlertProfile.locationTitle(sequence)
            ),
            DirectAlertProfile.locationMessage(
                sequence,
                path,
                quality,
                ageSeconds,
                mapUrl
            ),
            mapUrl,
            profile
        );
        if (path == 2) {
            payload["gps_observed_at"] = captureAt;
            payload["gps_capture_age_unknown"] = true;
        } else {
            payload["gps_capture_time"] = captureAt;
            payload["gps_age_seconds"] = ageSeconds;
            payload["gps_capture_age_unknown"] = false;
        }
        payload["gps_fix_kind"] = path == 2
            ? "active_activity"
            : (path == 3 ? "phone_fused" : (path == 1 ? "live_callback" : "last_known"));
        payload["gps_quality"] = DirectAlertProfile.locationQuality(path, quality);
        if (path == 3) {
            payload["gps_accuracy_meters"] = quality;
        }
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
    if (!DirectAlertSafety.isUsableLastKnownCapture(99, 101)
        || DirectAlertSafety.isUsableLastKnownCapture(102, 101)) {
        logger.error("Last-known GPS boundary failed");
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
    if (!DirectAlertSafety.isPossiblyStaleLocation(2, 5)) {
        logger.error("Untimestamped activity GPS was presented as fresh");
        return false;
    }
    if (!OpenDistressProtocol.stringEquals(
            DirectAlertProfile.locationTitle(2),
            "GPS-UPDATE 2 — TESTNOTRUF — OPENDISTRESS"
        )) {
        logger.error("GPS update title is not update-first");
        return false;
    }
    var expectedLocationMessage = "GPS-UPDATE 2\n\n"
        + "TESTMODUS — KEIN ECHTER NOTFALL\n\n"
        + "GPS-STATUS\nAktueller Garmin-GPS-Teststandort."
        + "\nQuelle: Live-GPS der Uhr"
        + "\nGenauigkeit: gut\n\n"
        + "GPS-ALTER LAUT UHR\n5 s\n\n"
        + "KARTE\nhttps://maps.google.com/?q=1,2";
    if (!OpenDistressProtocol.stringEquals(
            DirectAlertProfile.locationMessage(
                2,
                1,
                Position.QUALITY_GOOD,
                5,
                "https://maps.google.com/?q=1,2"
            ),
            expectedLocationMessage
        )) {
        logger.error("GPS update message lost its section formatting");
        return false;
    }
    if (!OpenDistressProtocol.stringEquals(
            DirectAlertProfile.clippedText("123456", 5),
            "12..."
        )
        || !OpenDistressProtocol.stringEquals(
            DirectAlertProfile.clippedText("12345", 5),
            "12345"
        )) {
        logger.error("Pushover profile clipping boundary failed");
        return false;
    }
    // Even words at the end of a maximum-length briefing must survive intact.
    var callbackWords = "Expected: abstract strategy";
    var briefing = "";
    while (briefing.length() < 180 - callbackWords.length() - 2) {
        briefing += "R";
    }
    briefing += "\n\n" + callbackWords;
    var rendered = DirectAlertProfile.appendClippedSection(
        DirectAlertProfile.TEST_MESSAGE, "REAKTIONSPLAN (NUR UEBUNG)",
        briefing, DirectAlertProfile.PUSHOVER_RESPONSE_CHARACTERS
    );
    if (rendered.find(briefing) == null
        || DirectAlertProfile.TEST_MESSAGE.find("keine Polizei verstaendigen") == null) {
        logger.error("Callback briefing was shortened or lost its TEST warning");
        return false;
    }
    return true;
}

(:test)
function companionConfigAndPhoneLocationVectors(logger) {
    var config = {
        "protocol" => "opendistress.companion.v1",
        "type" => "config",
        "revision" => "42",
        "grafanaWebhookUrl" => "https://tenant.grafana.net/oncall/"
            + "integrations/v1/formatted_webhook/"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/",
        "pushoverUserKey" => "",
        "pushoverApiToken" => "",
        "protectedPersonName" => "Alex",
        "customAlertMessage" => "Line one\nLine two",
        "homeAddress" => "Example 1",
        "childrenInfo" => "",
        "personDescription" => "Blue coat",
        "backgroundInfo" => "",
        "responseInstructions" => "Call trusted contact",
        "profilePhotoUrl" => "",
        "config_digest" => "PtnZRIA3HR75P-8pDXDc6jOBDDG2q9kd_kTMUU6qjAs"
    };
    Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
    if (!DirectAlertSettings.validConfig(config)
        || !DirectAlertSettings.install(config)
        || !OpenDistressProtocol.stringEquals(
            DirectAlertSettings.value("protectedPersonName"), "Alex"
        )) {
        logger.error("Companion configuration vector was not stored canonically");
        Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
        return false;
    }
    config["hapticFeedback"] = "false";
    if (DirectAlertSettings.validConfig(config)) {
        logger.error("Haptic preference was not digest-bound");
        Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
        return false;
    }
    config["config_digest"] = "bCo0Z7jWvlwdkWXW0RTtkcwRZlIRW5OWktIWmFUAgbs";
    // A new setting requires a new revision when installed over existing setup.
    Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
    if (!DirectAlertSettings.install(config) || DirectAlertSettings.hapticsEnabled()) {
        logger.error("Haptic off vector was not stored and applied");
        Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
        return false;
    }
    config["protectedPersonName"] = "Mallory";
    if (DirectAlertSettings.validConfig(config)) {
        logger.error("Companion configuration digest did not bind the profile");
        Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
        return false;
    }
    var record = OpenDistressProtocol.directPhoneLocationRecord(
        101, 482081740, 163738190, 4
    );
    if (record[15] != 3 || record[14] != 4
        || record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2, :endianness => Lang.ENDIAN_BIG
        }) != 101
        || !OpenDistressProtocol.stringEquals(
            DirectAlertProfile.locationSource(3),
            "Fused-Standort des Android-Handys"
        )
        || DirectAlertSafety.isPossiblyStaleLocation(3, 30)
        || !DirectAlertSafety.isPossiblyStaleLocation(3, 31)) {
        logger.error("Phone location direct-TEST record was not source-bound");
        Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
        return false;
    }
    Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
    return true;
}

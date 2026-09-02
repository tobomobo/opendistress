// SPDX-License-Identifier: MIT

import Toybox.Application.Properties;
import Toybox.Lang;

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

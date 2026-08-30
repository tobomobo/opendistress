# SPDX-License-Identifier: MIT

import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parents[1]
GARMIN = ROOT / "apps/garmin"


class GarminContractTests(unittest.TestCase):
    def test_resources_parse_and_permissions_match_implemented_phases(self):
        for path in GARMIN.glob("resources/*/*.xml"):
            ET.parse(path)
        manifest = ET.parse(GARMIN / "manifest.xml").getroot()
        namespace = {"iq": "http://www.garmin.com/xml/connectiq"}
        permissions = {
            item.attrib["id"] for item in manifest.findall(".//iq:uses-permission", namespace)
        }
        products = {item.attrib["id"] for item in manifest.findall(".//iq:product", namespace)}
        settings = ET.parse(GARMIN / "resources/settings/settings.xml").getroot()
        setting_keys = {item.attrib["propertyKey"] for item in settings.findall("./setting")}

        self.assertEqual(
            permissions,
            {"Communications", "ComplicationPublisher", "Cryptography", "Positioning"},
        )
        self.assertEqual(products, {"fenix847mm"})
        self.assertEqual(
            setting_keys,
            {"@Properties.relayBaseUrl", "@Properties.deviceId", "@Properties.hmacKeyHex"},
        )
        self.assertTrue(
            all("live" not in key.lower() for key in setting_keys),
            "LIVE content/authentication keys must not pass through Garmin app settings",
        )

    def test_monkey_c_self_test_embeds_v1_and_v2_public_vectors(self):
        v1 = dict(
            line.split("=", 1)
            for line in (ROOT / "protocol/fixtures/signature-v1.txt").read_text().splitlines()
            if line and not line.startswith("#")
        )
        v2 = dict(
            line.split("=", 1)
            for line in (ROOT / "protocol/fixtures/encryption-v2.txt").read_text().splitlines()
            if line and not line.startswith(("#", "["))
        )
        source = (GARMIN / "source/PanicProtocol.mc").read_text()

        self.assertIn(v1["request_signature"], source)
        self.assertIn("v1=6eCuAfV44rtvISQNtNPfUXpt50fm_U5sUj4POwx42UM", source)
        for value in (
            "v2=vkHWr3fYtcYij4GqeJJ49dJhDn38m26ifCTJAU3SknY",
            "v2=Z40vnSWhJ7rbDRz6kO8nAh8-Qen5RGpl20xiiQ6kCpI",
            "v2=uGLHdOkt0pA1daHA313hWEMUI2pdB5mQNuwQOB_uTM8",
            "v2=fsU52lMaXLa4DAu00Awg8-uFZgePLPLim_P4OzRMTiQ",
            "v2=O7ik82fJBgz3-OwXGZeUALuSlufZvQT2Gr9rkFVnGdw",
            "v2=1PKgg7-Pz7Ko7_jtlrQaJoWxOLwI16D6FGCt4YnnzIM",
        ):
            self.assertIn(value, source)
        for key_name in ("auth_key_hex", "enc_key_hex", "mac_key_hex"):
            key = v2[key_name]
            self.assertIn(key[:32], source)
            self.assertIn(key[32:], source)

    def test_public_vector_key_cannot_authenticate_configured_test_events(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        protocol = (GARMIN / "source/PanicProtocol.mc").read_text()

        self.assertEqual(app.count("PanicProtocol.isSafeAuthKey(keyHex)"), 2)
        self.assertIn("return isLowerHexKey(value) && !isPublicFixtureKey(value)", protocol)
        self.assertIn("requestSignature(PanicProtocol.PUBLIC_AUTH_KEY, testEvent)", protocol)

    def test_live_rejects_public_fixture_keys_in_every_key_role(self):
        protocol = (GARMIN / "source/PanicProtocol.mc").read_text()
        safety = protocol[
            protocol.index("function isSafeLiveConfiguration(")
            : protocol.index("function truncateE7(")
        ]

        self.assertIn("isSafeAuthKey(authKey)", safety)
        self.assertEqual(safety.count("!isPublicFixtureKey("), 2)
        for fixture in ("PUBLIC_AUTH_KEY", "PUBLIC_ENC_KEY", "PUBLIC_MAC_KEY"):
            self.assertIn(f"value == {fixture}", protocol)

    def test_trigger_is_durable_and_submission_starts_before_positioning(self):
        source = (GARMIN / "source/PanicApp.mc").read_text()
        live = source[source.index("function activateLive()") : source.index("function captureLocations()")]

        self.assertEqual(source.count("Communications.makeWebRequest("), 2)
        self.assertLess(live.index("!persistState([event], active)"), live.index("sendPending();"))
        self.assertLess(live.index("sendPending();"), live.index("captureLocations();"))
        self.assertIn("Position.getInfo()", source)
        self.assertIn("Position.enableLocationEvents(", source)
        self.assertIn("MAX_INITIAL_RETRIES = 2", source)
        self.assertIn("Repeated START keeps the same incident", source)

    def test_restart_preserves_active_live_semantics_and_retry_timer_cannot_go_stale(self):
        source = (GARMIN / "source/PanicApp.mc").read_text()
        activate = source[source.index("function activate()") : source.index("function activateTest()")]
        send = source[source.index("function sendPending()") : source.index("function onResponse(")]

        self.assertLess(activate.index("_activeIncident != null"), activate.index('_mode == "LIVE"'))
        self.assertIn('now < _activeIncident["expires_at"]', activate)
        self.assertIn("Repeated START keeps the same incident", activate)
        self.assertLess(activate.index("expireLocations();"), activate.index('_mode == "LIVE"'))
        self.assertLess(send.index("_retryTimer.stop();"), send.index("Communications.makeWebRequest("))

    def test_only_expired_live_events_can_be_explicitly_removed(self):
        source = (GARMIN / "source/PanicApp.mc").read_text()
        menu = source[source.index("function menuAction()") : source.index("function setState(")]

        self.assertIn('now < _queue[j]["expires_at"]', menu)
        self.assertIn('if (_queue[k]["v"] == 1)', menu)
        self.assertIn('nextActive["incident_id"] == incidentId', menu)
        self.assertIn('setState("RESULT UNKNOWN — EXPIRED"', menu)
        self.assertIn('setState("LIVE RETAINED"', menu)
        self.assertIn('_mode = "TEST"', menu)
        send = source[source.index("function sendPending()") : source.index("function onResponse(")]
        self.assertIn('now >= event["expires_at"]', send)
        self.assertLess(send.index('now >= event["expires_at"]'), send.index("makeWebRequest("))

        expiry = source[source.index("function expireLocations()") : source.index("function scheduleLocationExpiry(")]
        self.assertIn('_mode = "TEST"', expiry)
        self.assertIn("persistState(_queue, null)", expiry)
        self.assertIn('setState("LOCAL DISARM UNSAVED"', expiry)
        self.assertIn("Encrypted pending events retained; MENU archives", expiry)
        self.assertIn('"START defaults to TEST"', expiry)

        validation = source[
            source.index("function validStoredState(") : source.index("function validActive(")
        ]
        self.assertIn('var archivedIncidentId = ""', validation)
        self.assertIn('var archivedExpiresAt = -1', validation)
        self.assertIn('queue[i]["incident_id"] != archivedIncidentId', validation)
        self.assertIn('queue[i]["expires_at"] != archivedExpiresAt', validation)

    def test_queue_clears_only_for_exact_signed_durable_acceptance(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        protocol = (GARMIN / "source/PanicProtocol.mc").read_text()

        self.assertIn("responseCode == 202", app)
        self.assertIn("PanicProtocol.verifyDurablyAccepted(data, event, keyHex)", app)
        self.assertIn("result=durably_accepted", protocol)
        self.assertIn("Provider evidence remains separate", app)
        self.assertNotIn("PROVIDER ACCEPTED", app)
        self.assertIn("LIVE events cannot be abandoned", app)

    def test_location_is_fixed_size_encrypted_and_never_sent_as_plain_json(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        protocol = (GARMIN / "source/PanicProtocol.mc").read_text()
        schema = json.loads((ROOT / "protocol/incident-v2.schema.json").read_text())

        self.assertIn("new Cryptography.Cipher", protocol)
        self.assertIn(":algorithm => Cryptography.CIPHER_AES256", protocol)
        self.assertIn(":mode => Cryptography.MODE_CBC", protocol)
        self.assertIn("plaintext.size() != 16", protocol)
        self.assertIn("contentSigningInput(event)", protocol)
        self.assertIn("locationRecord(info, path)", protocol)
        self.assertIn("record.encodeNumber", protocol)
        self.assertIn("info.accuracy == Position.QUALITY_NOT_AVAILABLE", protocol)
        self.assertIn(
            'value["expires_at"] - value["created_at"] > MAX_V2_LIFETIME',
            protocol,
        )
        self.assertNotIn('"latitude" =>', app)
        self.assertNotIn('"longitude" =>', app)
        self.assertEqual(schema["properties"]["kind"]["enum"], ["live.triggered", "location.updated"])

    def test_location_acquisition_stage_is_durable_and_resumed(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        capture = app[app.index("function captureLocations()") : app.index("function liveConfiguration()")]
        append = app[app.index("function appendLocation(") : app.index("function liveConfiguration()")]

        self.assertIn('"capture_stage" => 0', app)
        self.assertIn("resumeLocations();", app)
        self.assertIn("appendLocation(snapshot, 0, 1)", capture)
        self.assertIn("appendLocation(null, 1, 2)", capture)
        self.assertIn("appendLocation(info, 1, 2)", capture)
        self.assertNotIn("Position.LOCATION_ONE_SHOT", app)
        stage_one_capture = capture[
            capture.index('if (_activeIncident != null && _activeIncident["capture_stage"] == 1)')
            : capture.index('} else if (_activeIncident != null && _activeIncident["capture_stage"] == 2)')
        ]
        self.assertIn("Position.LOCATION_CONTINUOUS", stage_one_capture)
        first_callback = capture[
            capture.index('if (_activeIncident["capture_stage"] == 1)', capture.index("function onPosition("))
            : capture.index('} else if (_activeIncident["capture_stage"] == 2', capture.index("function onPosition("))
        ]
        self.assertIn("appendLocation(info, 1, 2)", first_callback)
        self.assertNotIn("startContinuousLocations", first_callback)
        self.assertIn('"capture_stage" => nextCaptureStage', append)
        self.assertIn("persistState(nextQueue, nextActive)", append)
        self.assertNotRegex(app, r'_activeIncident\["capture_stage"\]\s*=(?!=)')

    def test_later_location_cadence_is_foreground_bounded(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        readme = (GARMIN / "README.md").read_text()
        normalized_readme = " ".join(readme.split())

        for value in (
            "FIRST_CADENCE_SECONDS = 30",
            "MIDDLE_CADENCE_SECONDS = 120",
            "LATE_CADENCE_SECONDS = 300",
            "MATERIAL_MOVE_E7 = 5000",
            "LOW_BATTERY_PERCENT = 20",
            "Position.LOCATION_CONTINUOUS",
            "Position.LOCATION_DISABLE",
            '"last_location_queued_at" => now',
        ):
            self.assertIn(value, app)
        self.assertIn("record[14] > previous[14]", app)
        self.assertIn("(current.toDouble() - previous.toDouble()).abs()", app)
        self.assertIn("var startedAt = expiresAt >= LIVE_EXPIRY_SECONDS", app)
        self.assertIn('setState("CLOCK INCONSISTENT", "Future GPS fix was not queued")', app)
        self.assertIn("System.getSystemStats()", app)
        self.assertIn("scheduleLocationExpiry(now)", app)
        self.assertNotIn(": remaining + 1", app)
        self.assertIn("_visible = false", app)
        self.assertIn("same foreground-only cadence queries signed `/v2/status`", normalized_readme)
        self.assertIn("does not yet claim strict `-l 3` conformance", normalized_readme)
        self.assertIn("-l 1 -w", readme)

    def test_signed_status_poll_shares_foreground_cadence_and_request_gate(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        protocol = (GARMIN / "source/PanicProtocol.mc").read_text()
        poll = app[app.index("function pollStatus()") : app.index("function sendStatusQuery(")]
        send = app[app.index("function sendStatusQuery(") : app.index("function onStatusResponse(")]
        response = app[
            app.index("function onStatusResponse(") : app.index("function statusFailure(")
        ]
        failure = app[
            app.index("function statusFailure(") : app.index("function continueAfterStatus(")
        ]
        terminal = app[
            app.index("function finishIncidentFromStatus(") : app.index("function appendLocation(")
        ]
        bypass = app[app.index("function canPollStatus(") : app.index("function sendStatusQuery(")]
        continuation = app[
            app.index("function continueAfterStatus(") : app.index("function finishIncidentFromStatus(")
        ]

        self.assertIn("scheduleStatusPoll(now);", app)
        self.assertIn("cadenceSeconds(now)", app)
        self.assertIn("_statusTimer.stop();", app)
        self.assertIn("if (_inFlight)", poll)
        self.assertIn("if (!canPollStatus())", poll)
        self.assertIn("sendPending();", poll)
        self.assertIn("_queue.size() == 0", bypass)
        self.assertIn("_activeIncident == null", bypass)
        self.assertIn("PanicProtocol.isEncryptedEvent(head)", bypass)
        self.assertIn('head["kind"] == PanicProtocol.V2_LOCATION_KIND', bypass)
        self.assertIn('head["incident_id"] == _activeIncident["incident_id"]', bypass)
        self.assertIn('head["expires_at"] == _activeIncident["expires_at"]', bypass)
        self.assertIn("!canPollStatus()", send)
        self.assertIn("PanicProtocol.randomId()", send)
        self.assertIn('config["base_url"] + "/v2/status"', send)
        self.assertIn(':context => query["request_id"]', send)
        self.assertIn("method(:onStatusResponse)", send)
        self.assertIn("responseCode == 200", response)
        self.assertIn("PanicProtocol.verifyStatusResult(data, query, keyHex, receiveAt)", response)
        self.assertLess(response.index("verifyStatusResult"), response.index("finishIncidentFromStatus"))
        self.assertIn('data["state"] == "resolved" || data["state"] == "expired"', response)
        self.assertIn('data["state"] == "acknowledged"', response)
        self.assertLess(continuation.index("sendPending();"), continuation.index("scheduleStatusPoll(now);"))
        self.assertNotIn("persistState(", failure)
        self.assertIn("persistState(remaining, null)", terminal)
        self.assertIn('_queue[i]["incident_id"] != query["incident_id"]', terminal)
        self.assertIn("_mode = \"TEST\"", terminal)
        self.assertNotIn("ACCEPTED", terminal)

        for domain in ("spb.status.query.v2", "spb.status.result.v2"):
            self.assertIn(domain, protocol)
        self.assertIn("hasExactKeys(data, STATUS_RESULT_KEYS)", protocol)
        self.assertIn('data["request_id"] != query["request_id"]', protocol)
        self.assertIn('data["incident_id"] != query["incident_id"]', protocol)
        self.assertIn('data["device_id"] != query["device_id"]', protocol)
        self.assertIn('receiveAt - query["created_at"] > 300', protocol)
        self.assertIn('query["created_at"] - data["checked_at"] > 300', protocol)
        self.assertIn('data["checked_at"] - receiveAt > 300', protocol)

        event_response = app[app.index("function onResponse(") : app.index("function handleFailure(")]
        self.assertIn("eventId != _requestEventId", event_response)
        self.assertIn('requestId != _statusQuery["request_id"]', response)

    def test_glance_and_complication_only_launch_or_label_the_foreground_app(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()
        complication = ET.parse(
            GARMIN / "resources/complications/complications.xml"
        ).getroot()

        self.assertIn("(:glance)\n    function getGlanceView()", app)
        self.assertIn("(:glance)\nclass PanicGlanceView", app)
        self.assertEqual(len(complication.findall("./complication")), 1)
        glance_class = app[app.index("class PanicGlanceView") : app.index("class PanicView")]
        self.assertNotIn("makeWebRequest", glance_class)
        self.assertNotIn("Position.", glance_class)

    def test_wifi_is_observed_but_never_selected_or_gated(self):
        app = (GARMIN / "source/PanicApp.mc").read_text()

        self.assertIn("connections[:wifi]", app)
        self.assertIn("route not forced", app)
        self.assertNotIn("checkWifiConnection", app)


if __name__ == "__main__":
    unittest.main()

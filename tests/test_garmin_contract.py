# SPDX-License-Identifier: MIT

import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parents[1]
GARMIN = ROOT / "apps/garmin"


class GarminContractTests(unittest.TestCase):
    def test_jungle_paths_exclude_nested_sdk_sources(self):
        jungle = (GARMIN / "monkey.jungle").read_text().splitlines()

        self.assertIn("project.manifest = manifest.xml", jungle)
        self.assertIn("base.sourcePath = source", jungle)
        self.assertIn("base.resourcePath = resources", jungle)

    def test_resources_parse_and_permissions_match_implemented_phases(self):
        for path in GARMIN.glob("resources/*/*.xml"):
            ET.parse(path)
        manifest = ET.parse(GARMIN / "manifest.xml").getroot()
        beta_manifest = ET.parse(GARMIN / "manifest-beta.xml").getroot()
        namespace = {"iq": "http://www.garmin.com/xml/connectiq"}
        permissions = {
            item.attrib["id"] for item in manifest.findall(".//iq:uses-permission", namespace)
        }
        products = {item.attrib["id"] for item in manifest.findall(".//iq:product", namespace)}
        beta_products = {
            item.attrib["id"] for item in beta_manifest.findall(".//iq:product", namespace)
        }
        settings = ET.parse(GARMIN / "resources/settings/settings.xml").getroot()
        setting_keys = {item.attrib["propertyKey"] for item in settings.findall(".//setting")}
        drawables = ET.parse(GARMIN / "resources/drawables/drawables.xml").getroot()
        drawable_files = {
            item.attrib["id"]: item.attrib["filename"]
            for item in drawables.findall("./bitmap")
        }

        self.assertEqual(
            permissions,
            {"Communications", "ComplicationPublisher", "Positioning"},
        )
        self.assertEqual(
            products,
            {
                "fenix843mm",
                "fenix847mm",
                "fenix8solar47mm",
                "fenix8solar51mm",
                "fenix8pro47mm",
                "fenixe",
                "fr970",
                "instinct3amoled45mm",
                "instinct3amoled50mm",
                "instinct3solar45mm",
                "venu441mm",
                "venu445mm",
                "venux1",
            },
        )
        self.assertEqual(beta_products, products)
        production_app = manifest.find("./iq:application", namespace)
        beta_app = beta_manifest.find("./iq:application", namespace)
        self.assertEqual(
            production_app.attrib["id"],
            "eab2248e-a772-48c6-9036-f1ec97cf3c24",
        )
        self.assertEqual(
            beta_app.attrib["id"],
            "b9eb9236-66c4-4119-94c5-ba11d891deb0",
        )
        self.assertEqual(production_app.attrib["entry"], "OpenDistressApp")
        self.assertEqual(beta_app.attrib["entry"], "OpenDistressApp")
        self.assertEqual(beta_app.attrib["name"], "@Strings.BetaAppName")
        beta_jungle = (GARMIN / "beta.jungle").read_text()
        self.assertIn("project.manifest = manifest-beta.xml", beta_jungle)
        self.assertIn("base.sourcePath = source", beta_jungle)
        self.assertIn("base.resourcePath = resources", beta_jungle)
        self.assertEqual(
            drawable_files,
            {"LauncherIcon": "launcher.svg", "ComplicationIcon": "complication.svg"},
        )
        self.assertIn('width="65" height="65"', (GARMIN / "resources/drawables/launcher.svg").read_text())
        self.assertIn(
            'width="45" height="45"',
            (GARMIN / "resources/drawables/complication.svg").read_text(),
        )
        self.assertEqual(
            setting_keys,
            {
                "@Properties.pushoverUserKey",
                "@Properties.pushoverApiToken",
                "@Properties.grafanaWebhookUrl",
                "@Properties.protectedPersonName",
                "@Properties.customAlertMessage",
                "@Properties.homeAddress",
                "@Properties.childrenInfo",
                "@Properties.personDescription",
                "@Properties.backgroundInfo",
                "@Properties.responseInstructions",
                "@Properties.profilePhotoUrl",
            },
        )
        setting_types = {
            item.attrib["propertyKey"]: item.find("./settingConfig").attrib["type"]
            for item in settings.findall(".//setting")
        }
        self.assertEqual(
            setting_types,
            {
                "@Properties.pushoverUserKey": "password",
                "@Properties.pushoverApiToken": "password",
                "@Properties.grafanaWebhookUrl": "password",
                "@Properties.protectedPersonName": "alphaNumeric",
                "@Properties.customAlertMessage": "alphaNumeric",
                "@Properties.homeAddress": "alphaNumeric",
                "@Properties.childrenInfo": "alphaNumeric",
                "@Properties.personDescription": "alphaNumeric",
                "@Properties.backgroundInfo": "alphaNumeric",
                "@Properties.responseInstructions": "alphaNumeric",
                "@Properties.profilePhotoUrl": "url",
            },
        )
        self.assertTrue(
            all("live" not in key.lower() for key in setting_keys),
            "LIVE content/authentication keys must not pass through Garmin app settings",
        )

    def test_status_and_cover_scale_across_round_and_rectangular_displays(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        update = source[
            source.index("function onUpdate(dc)", source.index("class OpenDistressView"))
            : source.index("function activate()")
        ]
        strings = ET.parse(GARMIN / "resources/strings/strings.xml").getroot()
        app_name = next(item.text for item in strings.findall("./string") if item.attrib["id"] == "AppName")
        launcher = (GARMIN / "resources/drawables/launcher.svg").read_text()

        self.assertEqual(app_name, "OpenDistress")
        self.assertIn("var isRound = width == height", update)
        self.assertIn("var compactRound = isRound && width < 220", update)
        self.assertIn("compactRound ? 62 : (isRound ? 76 : 88)", update)
        self.assertIn("compactRound ? 14 : 18", update)
        self.assertIn("compactRound ? 50 : 47", update)
        self.assertIn("compactRound ? 86 : 80", update)
        self.assertEqual(update.count("new WatchUi.TextArea"), 4)
        self.assertIn("drawReadyScreen(dc)", update)
        self.assertIn('"Release to cancel"', update)
        self.assertIn("Graphics.FONT_LARGE, Graphics.FONT_MEDIUM", update)
        self.assertIn("Graphics.FONT_SMALL, Graphics.FONT_TINY", update)
        self.assertIn("compactDisplayId(_displayEventId)", update)
        self.assertIn('id.substring(0, 4) + "..."', source)
        self.assertEqual(launcher.count("<circle"), 2)
        self.assertEqual(launcher.count("<path"), 1)
        self.assertNotIn("<polygon", launcher)
        self.assertNotIn("<rect", launcher)

        cover = source[source.index("function drawAnalogCover(") : source.index("function drawHand(")]
        self.assertIn("var minSize = width < height ? width : height", cover)
        self.assertIn("var compactRound = width == height && minSize < 220", cover)
        self.assertIn("(width * 43) / 100", cover)
        self.assertIn("(height * 57) / 100", cover)
        self.assertIn("var edgePadding = minSize / 15", cover)
        self.assertIn("if (minSize >= 220)", cover)

    def test_failed_test_event_uses_clearable_non_live_language(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        failure = source[
            source.index("function handleFailure(") : source.index("function retryPending()")
        ]

        self.assertIn('_queue[0]["v"] == 1', failure)
        self.assertIn('setState("TEST PENDING", detail)', failure)
        self.assertIn('testPending ? "TEST CONFIG ERROR"', failure)

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
        source = (GARMIN / "source/OpenDistressProtocol.mc").read_text()

        self.assertIn(v1["request_signature"], source)
        self.assertIn("v1=IGVfaGn9w07jtO7OSgKsqMxvzU513EH9ByEFi6hTNhk", source)
        for value in (
            "v2=wKW2UM7B1hOF59JfjpW0ol4YB8sFWccUNT6d_7S28IQ",
            "v2=gtYwKUt7qrWFjCrtDJq4yns_1My1J0b67e9cgF7YOKw",
            "v2=s84IhlhENf3_q170hFyPLj9g5XKQhYIgfqC-LLc_QXk",
            "v2=8gGf-GSWjBKTLFZmsv9HaAynRz-fLJTNAh39_mKjehw",
            "v2=wZis26a3WNgkwkoulKYMWOptlpAWPJarVkJzSAKFlCU",
            "v2=7CcJC9UNljfOlMkrh1J0-pbyF_PTNRPRpw_xEvGJ1Vc",
        ):
            self.assertIn(value, source)
        for key_name in ("auth_key_hex", "enc_key_hex", "mac_key_hex"):
            key = v2[key_name]
            self.assertIn(key[:32], source)
            self.assertIn(key[32:], source)

    def test_monkey_c_self_tests_cover_adversarial_protocol_paths(self):
        source = (GARMIN / "source/OpenDistressProtocol.mc").read_text()

        for test_name in (
            "protocolConformance",
            "protocolRejectsMalformedEvents",
            "protocolRejectsUnsafeConfiguration",
            "protocolRejectsTamperedResults",
        ):
            self.assertIn(f"(:test)\nfunction {test_name}(logger)", source)
        for required_probe in (
            'testEvent["extra"] = 1',
            "OpenDistressProtocol.MAX_V1_CREATED_AT + 1",
            'liveEvent["expires_at"] = 1788192001',
            'liveEvent["expires_at"] = 1788105600',
            "OpenDistressProtocol.PUBLIC_MAC_KEY,\n            macKey",
            "OpenDistressProtocol.PUBLIC_ENC_KEY",
            'accepted["extra"] = null',
            'status["state"] = "closed"',
            'status["request_id"] = "sLGys7S1tre4ubq7vL2-vw"',
            "1788106001",
            'OpenDistressProtocol.failureResult({"result" => "untrusted"})',
        ):
            self.assertIn(required_probe, source)

    def test_public_vector_key_cannot_authenticate_configured_test_events(self):
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        protocol = (GARMIN / "source/OpenDistressProtocol.mc").read_text()

        self.assertEqual(app.count("OpenDistressProtocol.isSafeAuthKey(keyHex)"), 2)
        self.assertIn("return isLowerHexKey(value) && !isPublicFixtureKey(value)", protocol)
        self.assertIn("OpenDistressProtocol.PUBLIC_AUTH_KEY,\n        testEvent", protocol)

    def test_live_rejects_public_fixture_keys_in_every_key_role(self):
        protocol = (GARMIN / "source/OpenDistressProtocol.mc").read_text()
        safety = protocol[
            protocol.index("function isSafeLiveConfiguration(")
            : protocol.index("function truncateE7(")
        ]

        self.assertIn("isSafeAuthKey(authKey)", safety)
        self.assertEqual(safety.count("!isPublicFixtureKey("), 2)
        self.assertIn("left.equals(right)", protocol)
        for fixture in ("PUBLIC_AUTH_KEY", "PUBLIC_ENC_KEY", "PUBLIC_MAC_KEY"):
            self.assertIn(f"stringEquals(value, {fixture})", protocol)

    def test_trigger_is_durable_and_submission_starts_before_positioning(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        live = source[source.index("function activateLive()") : source.index("function captureLocations()")]

        self.assertEqual(source.count("Communications.makeWebRequest("), 6)
        self.assertLess(live.index("!persistState([event], active)"), live.index("sendPending();"))
        self.assertLess(live.index("sendPending();"), live.index("captureLocations();"))
        self.assertIn("Position.getInfo()", source)
        self.assertIn("Position.enableLocationEvents(", source)
        self.assertIn("MAX_INITIAL_RETRIES = 2", source)
        self.assertIn("Repeated press keeps the same incident", source)

    def test_all_alert_modes_require_exact_hardware_hold_and_touch_is_inert(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        on_show = source[source.index("function onShow()") : source.index("function onHide()")]
        delegate = source[source.index("class OpenDistressDelegate") :]
        pressed = source[
            source.index("function startActionPressed()")
            : source.index("function startActionReleased()")
        ]
        released = source[
            source.index("function startActionReleased()")
            : source.index("function commitArmedAlert()")
        ]
        commit = source[
            source.index("function commitArmedAlert()")
            : source.index("function selectAction()")
        ]

        self.assertIn("hasProvisionedLiveConfiguration()", source)
        self.assertNotIn("activateLive();", on_show)
        self.assertIn("ALERT_ARM_HOLD_MS = 2500", source)
        self.assertIn(
            "_retryTimer.start(method(:advanceAlertArm), ALERT_ARM_FRAME_MS, true)",
            pressed,
        )
        self.assertNotIn('OpenDistressProtocol.stringEquals(_mode, "LIVE")', pressed)
        self.assertNotIn("activate();", pressed)
        self.assertIn("cancelAlertArm();", released)
        self.assertIn("activate();", commit)
        self.assertNotIn("activateLive();", commit)
        self.assertIn("function onKeyPressed(event)", delegate)
        self.assertIn("function onKeyReleased(event)", delegate)
        self.assertIn("key == WatchUi.KEY_START || key == WatchUi.KEY_ENTER", delegate)
        self.assertIn("function onHold(event)", delegate)
        self.assertIn("function onRelease(event)", delegate)
        self.assertIn("function onNextPage()", delegate)
        next_page = delegate[delegate.index("function onNextPage()") : delegate.index("function onKeyPressed")]
        self.assertIn("_view.downAction();", next_page)
        self.assertNotIn("activate", next_page)
        self.assertIn(
            'setState("SETUP REQUIRED", "Connect IQ Store: add webhook or keys")',
            source,
        )

    def test_alert_hold_draws_elapsed_symmetric_progress_from_six_oclock(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        update = source[
            source.index("function onUpdate(dc)", source.index("class OpenDistressView"))
            : source.index("function compactDisplayId")
        ]
        progress = source[
            source.index("function drawAlertArmProgress(dc)")
            : source.index("function activate()")
        ]
        advance = source[
            source.index("function advanceAlertArm()")
            : source.index("function cancelAlertArm()")
        ]

        self.assertIn("ALERT_ARM_FRAME_MS = 50", source)
        self.assertIn("drawAlertArmProgress(dc);", update)
        elapsed = source[
            source.index("function alertArmElapsedMs()")
            : source.index("function drawAlertArmProgress(dc)")
        ]
        self.assertIn("System.getTimer()", elapsed)
        self.assertIn("ALERT_ARM_START_DEGREES = 270", source)
        self.assertIn("Graphics.ARC_CLOCKWISE", progress)
        self.assertIn("Graphics.ARC_COUNTER_CLOCKWISE", progress)
        self.assertEqual(progress.count("dc.drawArc("), 2)
        self.assertIn("elapsed >= ALERT_ARM_HOLD_MS", advance)
        self.assertIn("commitArmedAlert();", advance)

    def test_test_mode_select_and_short_press_do_not_trigger(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        pressed = source[
            source.index("function startActionPressed()")
            : source.index("function startActionReleased()")
        ]
        select = source[
            source.index("function selectAction()") : source.index("function downAction()")
        ]

        self.assertNotIn("activate();", pressed)
        self.assertNotIn("activate();", select)
        self.assertIn("return true;", select)

        startup = source[
            source.index("function selectStartupMode()")
            : source.index("function settingsChanged()")
        ]
        self.assertIn('OpenDistressProtocol.stringEquals(_mode, "DIRECT_TEST")', startup)
        self.assertIn("hasDirectAlertConfiguration()", startup)
        self.assertIn("refreshConfiguredMode();", startup)

    def test_phone_settings_changes_refresh_idle_test_mode(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        app = source[source.index("class OpenDistressApp") : source.index("(:glance)")]
        changed = source[
            source.index("function settingsChanged()")
            : source.index("function hasRelayTestConfiguration()")
        ]

        self.assertIn("function onSettingsChanged()", app)
        self.assertIn("_view.settingsChanged();", app)
        self.assertIn("selectStartupMode();", changed)
        self.assertIn("WatchUi.requestUpdate();", changed)
        self.assertIn("_queue.size() > 0", changed)
        self.assertIn('_queue[0]["v"] == 1', changed)
        self.assertIn("sendPending();", changed)
        self.assertIn("_directResult != null", changed)

    def test_analog_cover_is_only_provider_acceptance_feedback(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        update = source[source.index("function onUpdate(dc)", source.index("class OpenDistressView")) :]
        cover = source[source.index("function shouldShowCover()") : source.index("function selectStartupMode()")]

        self.assertIn("return _directResult != null", cover)
        self.assertNotIn("return _personalLive", cover)
        self.assertNotIn("_activeIncident != null", cover)
        self.assertIn("drawAnalogCover(dc);", update)
        self.assertIn("System.getClockTime()", cover)
        self.assertIn("COVER_REFRESH_MS = 60000", source)
        self.assertIn("LOCATION_ACQUIRE_REFRESH_MS = 10000", source)
        self.assertIn("LOCATION_ACQUIRE_FAST_SECONDS = 300", source)
        self.assertIn('_directResult["last_location_hex"].length() == 0', cover)
        self.assertIn("refreshMs = LOCATION_ACQUIRE_REFRESH_MS", cover)
        self.assertIn("_statusTimer.start(method(:refreshIdleCover)", cover)
        self.assertIn("scheduleIdleCoverRefresh();", cover)
        poll = source[source.index("function pollStatus()") : source.index("function canPollStatus()")]
        self.assertIn("WatchUi.requestUpdate();", poll)
        self.assertNotIn("_coverTimer", source)
        self.assertIn('"LOCATION SCRUB UNSAVED"', cover)
        self.assertNotIn('"READY', cover)

    def test_accepted_test_cover_reveals_details_before_explicit_reset(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        update = source[
            source.index("function onUpdate(dc)", source.index("class OpenDistressView"))
            : source.index("function compactDisplayId")
        ]
        cover = source[
            source.index("function shouldShowCover()")
            : source.index("function scheduleIdleCoverRefresh()")
        ]
        select = source[
            source.index("function selectAction()")
            : source.index("function activateTest()")
        ]
        reset = source[
            source.index("function resetAcceptedTest()")
            : source.index("function menuAction()")
        ]
        menu = source[source.index("function menuAction()") : source.index("function setState(")]

        self.assertIn("var _acceptedStatusVisible = false", source)
        self.assertIn("&& !_acceptedStatusVisible", cover)
        cover_start = update.index("if (shouldShowCover())")
        cover_branch = update[cover_start : update.index("dc.setColor(", cover_start)]
        self.assertIn("drawAnalogCover(dc);", cover_branch)
        self.assertNotIn("drawAcceptedCoverHint", cover_branch)
        self.assertNotIn("function drawAcceptedCoverHint", source)
        self.assertIn("if (shouldShowAcceptedStatus())", update)
        self.assertIn("drawAcceptedStatus(dc);", update)
        accepted_gate = source[
            source.index("function shouldShowAcceptedStatus()")
            : source.index("function scheduleIdleCoverRefresh()")
        ]
        self.assertIn('stringEquals(_state, "PROVIDER ACCEPTED")', accepted_gate)
        self.assertIn('"LOCATION SCRUB UNSAVED"', accepted_gate)
        self.assertIn('"LOCATION STATE UNSAVED"', accepted_gate)
        self.assertIn('"ROUTE CHANGED"', accepted_gate)
        accepted_ui = source[
            source.index("function drawAcceptedStatus(dc)")
            : source.index("function selectStartupMode()")
        ]
        self.assertIn('"ALERT STATUS"', accepted_ui)
        self.assertIn(":text => acceptedProviderSummary()", accepted_ui)
        self.assertIn("acceptedLocationSummary()", accepted_ui)
        location_summary = source[
            source.index("function acceptedLocationSummary()")
            : source.index("function toggleAcceptedStatus()")
        ]
        self.assertIn('"GPS searching"', location_summary)
        self.assertIn('return "GPS update pending"', location_summary)
        self.assertIn('return "GPS update sent"', location_summary)
        self.assertIn("Delivery not confirmed", accepted_ui)
        self.assertIn("drawAcceptedButtonIndicators(dc);", accepted_ui)
        self.assertIn("function drawAcceptedButtonIndicator(", accepted_ui)
        self.assertIn("Graphics.ARC_CLOCKWISE", accepted_ui)
        self.assertIn("var halfSweep = active ? 18 : 7", accepted_ui)
        self.assertIn('"RESET TEST"', accepted_ui)
        self.assertIn('"DIAL"', accepted_ui)
        self.assertNotIn("LOWER-LEFT", accepted_ui)
        self.assertNotIn("MID-LEFT", accepted_ui)
        self.assertNotIn("_displayEventId", accepted_ui)
        self.assertEqual(select.count("toggleAcceptedStatus();"), 2)
        self.assertNotIn("persistStateWithDirect", select)
        self.assertNotIn("activate", select)
        self.assertIn('beginAcceptedActionFeedback("DIAL")', select)
        self.assertIn("function acceptedProviderSummary()", select)
        self.assertIn('"Grafana + Pushover accepted"', select)
        self.assertIn('"Grafana accepted"', select)
        self.assertIn('"Pushover accepted"', select)
        self.assertNotIn("Recipient unknown", source)
        self.assertIn("ACCEPTED_ACTION_FEEDBACK_MS = 180", source)
        self.assertIn('beginAcceptedActionFeedback("RESET")', menu)
        self.assertIn("persistStateWithDirect([], _activeIncident, null)", reset)
        self.assertIn("_acceptedStatusVisible = false", reset)
        self.assertIn("_acceptedActionFeedback = null", reset)

        pushover = source[
            source.index("function onPushoverResponse(")
            : source.index("function isPushoverAcceptance(")
        ]
        acceptance = source[
            source.index("function beginAcceptedDirectTracking(")
            : source.index("function onPushoverResponse(")
        ]
        self.assertIn('responseCode == 200 && isPushoverAcceptance(data)', pushover)
        self.assertIn('"pushover_accepted" => pushoverAccepted', acceptance)
        self.assertIn('"grafana_accepted" => grafanaAccepted', acceptance)
        self.assertLess(
            acceptance.index("persistStateWithDirect("),
            acceptance.index('setState("PROVIDER ACCEPTED"'),
        )
        self.assertLess(
            acceptance.index('setState("PROVIDER ACCEPTED"'),
            acceptance.index("confirmProviderAcceptance();"),
        )

    def test_direct_pushover_test_is_emergency_and_contains_no_live_payload(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        providers = (GARMIN / "source/DirectAlertProviders.mc").read_text()
        direct = source[
            source.index("function sendDirectPushover(")
            : source.index("function grafanaAlertPayload(")
        ]

        self.assertIn(
            'const ENDPOINT = "https://api.pushover.net/1/messages.json"',
            providers,
        )
        self.assertIn("DirectPushoverAdapter.ENDPOINT", direct)
        self.assertIn("DirectPushoverAdapter.initialParameters(event, now)", direct)
        self.assertIn("TEST_MESSAGE", providers)
        self.assertIn('"priority" => "2"', providers)
        self.assertIn('"retry" => "30"', providers)
        self.assertIn("REQUEST_CONTENT_TYPE_URL_ENCODED", direct)
        self.assertIn("method(:onPushoverResponse)", direct)
        self.assertNotIn("location", direct.lower())
        self.assertNotIn("live", direct.lower())

    def test_direct_test_identity_is_phone_editable_optional_and_test_only(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        providers = (GARMIN / "source/DirectAlertProviders.mc").read_text()
        properties = ET.parse(GARMIN / "resources/properties/properties.xml").getroot()
        settings = ET.parse(GARMIN / "resources/settings/settings.xml").getroot()
        property_ids = {item.attrib["id"] for item in properties.findall("./property")}
        identity_setting = next(
            item
            for item in settings.findall(".//setting")
            if item.attrib["propertyKey"] == "@Properties.protectedPersonName"
        )

        self.assertIn("protectedPersonName", property_ids)
        config = identity_setting.find("./settingConfig")
        self.assertEqual(config.attrib["required"], "false")
        self.assertEqual(config.attrib["maxLength"], "40")
        strings = ET.parse(GARMIN / "resources/strings/strings.xml").getroot()
        string_values = {item.attrib["id"]: item.text for item in strings.findall("./string")}
        self.assertIn("person wearing the watch", string_values["ProtectedPersonNameTitle"])
        self.assertIn("person sending the alert", string_values["PersonDescriptionPrompt"])
        self.assertIn('const TEST_TITLE = "TESTNOTRUF — OPENDISTRESS"', providers)
        self.assertIn("function locationTitle(sequence)", providers)
        self.assertIn("KEIN ECHTER NOTFALL", providers)
        self.assertIn('optionalText("protectedPersonName", 40)', providers)
        self.assertIn("function personalizedTitle(baseTitle)", providers)
        self.assertEqual(
            providers.count("DirectAlertProfile.TEST_TITLE"),
            2,
        )

    def test_optional_emergency_profile_is_shared_by_provider_adapters(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        providers = (GARMIN / "source/DirectAlertProviders.mc").read_text()
        settings = ET.parse(GARMIN / "resources/settings/settings.xml").getroot()
        profile_limits = {
            "@Properties.customAlertMessage": ("alphaNumeric", "240"),
            "@Properties.homeAddress": ("alphaNumeric", "120"),
            "@Properties.childrenInfo": ("alphaNumeric", "150"),
            "@Properties.personDescription": ("alphaNumeric", "150"),
            "@Properties.backgroundInfo": ("alphaNumeric", "180"),
            "@Properties.responseInstructions": ("alphaNumeric", "180"),
            "@Properties.profilePhotoUrl": ("url", None),
        }
        configs = {
            item.attrib["propertyKey"]: item.find("./settingConfig").attrib
            for item in settings.findall(".//setting")
        }

        for key, (setting_type, max_length) in profile_limits.items():
            self.assertIn(key, configs)
            self.assertEqual(configs[key]["type"], setting_type)
            self.assertEqual(configs[key]["required"], "false")
            if max_length is None:
                self.assertNotIn("maxLength", configs[key])
            else:
                self.assertEqual(configs[key]["maxLength"], max_length)

        profile = providers[
            providers.index("module DirectAlertProfile")
            : providers.index("module DirectPushoverAdapter")
        ]
        pushover = providers[
            providers.index("module DirectPushoverAdapter")
            : providers.index("module DirectGrafanaAdapter")
        ]
        grafana = providers[providers.index("module DirectGrafanaAdapter") :]
        profile_fields = (
            "alert_message",
            "person_name",
            "home_address",
            "children_info",
            "person_description",
            "background_info",
            "response_instructions",
            "profile_photo_url",
        )

        for field in profile_fields:
            self.assertIn(f'"{field}"', profile)
            self.assertIn(f'profile["{field}"]', grafana)
        self.assertIn("DirectAlertProfile.pushoverMessage()", pushover)
        self.assertIn("DirectAlertProfile.initialMessage()", grafana)
        self.assertIn('parameters["url"] = photoUrl', pushover)
        self.assertIn('parameters["url_title"] = "Open profile photo"', pushover)
        self.assertIn("PUSHOVER_MAX_MESSAGE_CHARACTERS = 1024", profile)
        self.assertIn("message.length() <= PUSHOVER_MAX_MESSAGE_CHARACTERS", profile)
        self.assertIn("function clippedText(value, maxLength)", profile)
        for constant, value in {
            "PUSHOVER_ALERT_MESSAGE_CHARACTERS": 160,
            "PUSHOVER_RESPONSE_CHARACTERS": 180,
            "PUSHOVER_NAME_CHARACTERS": 40,
            "PUSHOVER_DESCRIPTION_CHARACTERS": 100,
            "PUSHOVER_CHILDREN_CHARACTERS": 100,
            "PUSHOVER_ADDRESS_CHARACTERS": 100,
            "PUSHOVER_BACKGROUND_CHARACTERS": 90,
        }.items():
            self.assertIn(f"const {constant} = {value};", profile)
        self.assertIn('appendClippedSection(message, "VORBEREITETE NACHRICHT"', profile)
        self.assertIn('appendClippedSection(message, "REAKTIONSPLAN (NUR UEBUNG)"', profile)
        self.assertLess(
            profile.index('appendClippedSection(message, "REAKTIONSPLAN (NUR UEBUNG)"'),
            profile.index('appendClippedSection(message, "HINTERGRUND"'),
        )
        maximum_profile_message = (
            len("KEIN ECHTER NOTFALL. NUR UEBUNG: keine Polizei verstaendigen. OpenDistress Testausloesung.")
            + sum(
                len(label)
                for label in (
                    "\n\nVORBEREITETE NACHRICHT\n",
                    "\n\nPERSON MIT DER UHR\n",
                    "\n\nHEIMADRESSE (NICHT GPS)\n",
                    "\n\nKINDER / FAMILIE\n",
                    "\n\nBESCHREIBUNG DIESER PERSON\n",
                    "\n\nHINTERGRUND\n",
                    "\n\nREAKTIONSPLAN (NUR UEBUNG)\n",
                )
            )
            + sum((160, 180, 40, 100, 100, 100, 90))
        )
        self.assertLessEqual(maximum_profile_message, 1024)

        properties = ET.parse(GARMIN / "resources/properties/properties.xml").getroot()
        custom_default = next(
            item
            for item in properties.findall("./property")
            if item.attrib["id"] == "customAlertMessage"
        )
        self.assertFalse(custom_default.text, "Example alert text must never be sent as data")

        strings = ET.parse(GARMIN / "resources/strings/strings.xml").getroot()
        string_values = {item.attrib["id"]: item.text for item in strings.findall("./string")}
        self.assertIn("Write this now", string_values["CustomAlertMessagePrompt"])
        self.assertIn("1) Contact", string_values["ResponseInstructionsPrompt"])
        self.assertIn("how to verify", string_values["ResponseInstructionsPrompt"])
        self.assertIn("known threat", string_values["BackgroundInfoPrompt"])
        self.assertIn('value.find("https://") != 0', providers)
        self.assertIn('value.find("@") != null', providers)
        self.assertIn('value.find("#") != null', providers)
        self.assertIn("DirectPushoverAdapter.initialParameters(event, now)", source)
        self.assertIn("DirectGrafanaAdapter.initialPayload(eventId)", source)

    def test_direct_gps_starts_only_after_a_provider_acceptance(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        pushover_request = source[
            source.index("function sendDirectPushover(")
            : source.index("function grafanaAlertPayload(")
        ]
        grafana_request = source[
            source.index("function sendDirectGrafanaRequest(")
            : source.index("function onGrafanaAlertResponse(")
        ]
        acceptance = source[
            source.index("function beginAcceptedDirectTracking(")
            : source.index("function onPushoverResponse(")
        ]

        self.assertNotIn("Position.", pushover_request)
        self.assertNotIn("Position.", grafana_request)
        self.assertIn('"tracking_expires_at" => trackingExpiresAt', acceptance)
        self.assertIn('"accepted_at" => acceptedAt == null ? 0 : acceptedAt', acceptance)
        self.assertIn('"pushover_fingerprint" => pushoverFingerprint', acceptance)
        self.assertIn('"grafana_fingerprint" => grafanaFingerprint', acceptance)
        self.assertIn('"capture_stage" => captureStage', acceptance)
        self.assertLess(
            acceptance.index("persistStateWithDirect("),
            acceptance.index("confirmProviderAcceptance();"),
        )
        self.assertLess(
            acceptance.index("confirmProviderAcceptance();"),
            acceptance.index("captureDirectLocations();"),
        )
        binding_validation = source[
            source.index("function validDirectProviderBindings(")
            : source.index("function validDirectTrackingWindow(")
        ]
        self.assertEqual(binding_validation.count("isCanonicalDigest"), 2)
        self.assertNotIn("trackingDisabled", binding_validation)

    def test_grafana_formatted_webhook_is_validated_and_acceptance_is_not_ack(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        providers = (GARMIN / "source/DirectAlertProviders.mc").read_text()
        protocol = (GARMIN / "source/OpenDistressProtocol.mc").read_text()
        request = source[
            source.index("function grafanaAlertPayload(")
            : source.index("function onGrafanaAlertResponse(")
        ]
        response = source[
            source.index("function onGrafanaAlertResponse(")
            : source.index("function beginAcceptedDirectTracking(")
        ]

        self.assertIn("var authorityEndRelative", protocol)
        self.assertIn('host.substring(host.length() - 12, host.length())', protocol)
        self.assertIn('".grafana.net"', protocol)
        self.assertIn('"/integrations/v1/formatted_webhook/"', protocol)
        validator = protocol[
            protocol.index("function isGrafanaWebhookUrl(")
            : protocol.index("function randomId()")
        ]
        self.assertIn('value.find("@") != null', validator)
        self.assertIn("DirectGrafanaAdapter.initialPayload(eventId)", request)
        self.assertIn('"alert_uid" => eventId', providers)
        self.assertIn('"state" => "alerting"', providers)
        self.assertIn("REQUEST_CONTENT_TYPE_JSON", request)
        self.assertIn("responseCode >= 200 && responseCode < 300", response)
        self.assertIn("responseCode != 429", response)
        self.assertIn('responseCode == 429 ? "retryable_failure"', response)
        self.assertIn("Human acknowledgement remains separate", response)
        self.assertNotIn("acknowledged", response.lower())

    def test_pushover_and_grafana_are_independent_direct_alert_routes(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        send = source[source.index("function sendPending()") : source.index("function onResponse(")]
        pushover_send = source[
            source.index("function sendDirectPushover(")
            : source.index("function grafanaAlertPayload(")
        ]
        pushover = source[
            source.index("function onPushoverResponse(")
            : source.index("function isPushoverAcceptance(")
        ]

        self.assertIn("hasDirectAlertConfiguration()", send)
        self.assertIn("hasDirectPushoverConfiguration()", send)
        self.assertIn("sendDirectGrafanaInitial(event);", send)
        direct_choice = send[send.index('if (event["v"] == 1') :]
        self.assertLess(
            direct_choice.index("hasDirectGrafanaConfiguration()"),
            direct_choice.index("sendDirectPushover(event, now)"),
        )
        self.assertIn("hasDirectGrafanaConfiguration()", pushover_send)
        self.assertIn("sendDirectGrafanaInitial(event);", pushover_send)
        self.assertIn("hasDirectGrafanaConfiguration()", pushover)
        self.assertIn("sendDirectGrafanaInitial(event);", pushover)

    def test_direct_gps_uses_real_position_and_persists_before_sending(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        manifest = (GARMIN / "manifest.xml").read_text()
        direct = source[
            source.index("function resumeDirectLocations()")
            : source.index("function scheduleLocationExpiry(")
        ]
        queue = direct[
            direct.index("function queueDirectLocation(")
            : direct.index("function shouldQueueDirectCadenceLocation(")
        ]
        fallback_poll = direct[
            direct.index("function pollDirectFallbackLocation()")
            : direct.index("function startDirectContinuousLocations()")
        ]

        self.assertIn("Position.getInfo()", direct)
        self.assertIn("Activity.getActivityInfo()", direct)
        self.assertIn("Activity.TIMER_STATE_ON", direct)
        self.assertIn("activity.currentLocation", direct)
        self.assertIn("activity.currentLocationAccuracy", direct)
        self.assertIn("OpenDistressProtocol.locationRecordFromValues(", direct)
        self.assertIn("pollDirectFallbackLocation()", source)
        self.assertIn("Position.getInfo()", fallback_poll)
        self.assertNotIn(
            'if (_directResult["capture_stage"] != 2)',
            fallback_poll,
            "A missing initial fix must not disable later last-known polling",
        )
        self.assertIn(
            "startDirectContinuousLocations();",
            fallback_poll,
            "A transient positioning-start failure must be retried while acquiring",
        )
        self.assertIn(
            'queueDirectLocation(snapshot, 0, _directResult["capture_stage"])',
            " ".join(fallback_poll.split()),
            "A polled stale fix must not suppress the first fresh callback",
        )
        self.assertIn("enableBestContinuousLocation()", direct)
        self.assertIn("Position.QUALITY_NOT_AVAILABLE", direct)
        self.assertIn("DirectAlertSafety.isFreshCapture(", direct)
        self.assertIn("DirectAlertSafety.isUsableLastKnownCapture(", direct)
        self.assertIn('info.when.value()', direct)
        self.assertIn('_directResult["accepted_at"]', direct)
        self.assertNotIn("LOCATION_ONE_SHOT", direct)
        self.assertNotIn("mock", direct.lower())
        self.assertNotIn("fixture", direct.lower())
        self.assertNotIn("ActivityRecording", source)
        self.assertNotIn('uses-permission id="Fit"', manifest)
        self.assertLess(queue.index("persistDirectTracking("), queue.index("sendDirectLocation();"))
        self.assertNotIn("locationRecord(null", direct)

    def test_gps_prefers_best_supported_configuration_with_legacy_fallback(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        helper = source[
            source.index("function enableBestContinuousLocation()")
            : source.index("function stopLocations()")
        ]
        configurations = (
            "CONFIGURATION_GPS_GLONASS_GALILEO_BEIDOU_L1_L5",
            "CONFIGURATION_GPS_GLONASS_GALILEO_BEIDOU_L1",
            "CONFIGURATION_SAT_IQ",
            "CONFIGURATION_GPS",
        )

        self.assertIn(":acquisitionType => Position.LOCATION_CONTINUOUS", helper)
        self.assertIn(":configuration => configuration", helper)
        self.assertIn("Position.hasConfigurationSupport(", helper)
        self.assertIn(
            "Position.enableLocationEvents(Position.LOCATION_CONTINUOUS",
            helper,
        )
        for configuration in configurations:
            self.assertIn(configuration, helper)
        for preferred, fallback in zip(configurations, configurations[1:]):
            self.assertLess(
                helper.index(f"Position has :{preferred})"),
                helper.index(f"Position has :{fallback})"),
            )

    def test_direct_gps_sends_real_map_link_and_requires_provider_acceptance(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        providers = (GARMIN / "source/DirectAlertProviders.mc").read_text()
        send = source[
            source.index("function sendDirectLocation()")
            : source.index("function coordinateE7Text(")
        ]
        response = source[
            source.index("function onDirectLocationResponse(")
            : source.index("function scheduleDirectLocationRetry(")
        ]

        self.assertIn("Lang.NUMBER_FORMAT_SINT32", send)
        self.assertIn("var path = record[15]", send)
        self.assertIn("DirectAlertSafety.captureAgeSeconds(captureAt, now)", send)
        self.assertIn('"https://maps.google.com/?q="', send)
        self.assertIn("DirectPushoverAdapter.locationParameters(", send)
        self.assertIn("DirectGrafanaAdapter.locationPayload(", send)
        self.assertIn("hasBoundDirectPushover()", send)
        self.assertIn("hasBoundDirectGrafana()", send)
        self.assertIn('setState("ROUTE CHANGED"', send)
        queue = source[
            source.index("function queueDirectLocation(")
            : source.index("function shouldQueueDirectCadenceLocation(")
        ]
        completion = source[
            source.index("function completeDirectLocationProvider(")
            : source.index("function rejectDirectLocationProvider(")
        ]
        self.assertIn("var pendingPushover = hasBoundDirectPushover();", queue)
        self.assertIn("var pendingGrafana = hasBoundDirectGrafana();", queue)
        self.assertNotIn('_directResult["pushover_accepted"],', queue)
        self.assertNotIn('_directResult["grafana_accepted"]', queue)
        self.assertIn("pendingPushover && hasBoundDirectPushover()", completion)
        self.assertIn("pendingGrafana && hasBoundDirectGrafana()", completion)
        self.assertNotIn("LOCATION_TITLE", providers)
        self.assertEqual(providers.count("DirectAlertProfile.locationTitle(sequence)"), 2)
        self.assertEqual(providers.count("DirectAlertProfile.locationMessage("), 3)
        self.assertIn('return "GPS-UPDATE " + sequence.format("%d")', providers)
        self.assertIn('return "GPS-UPDATE " + sequence.format("%d") + "\\n\\n"', providers)
        self.assertIn('"TESTMODUS — KEIN ECHTER NOTFALL\\n\\n"', providers)
        self.assertIn('"GPS-STATUS\\n" + status', providers)
        self.assertIn('"\\n\\nGPS-ALTER LAUT UHR\\n"', providers)
        self.assertIn('"\\n\\nKARTE\\n" + mapUrl', providers)
        self.assertIn("WARNUNG: letzter bekannter", providers)
        self.assertNotIn('+ " " + mapUrl', providers)
        self.assertIn('payload["gps_capture_time"] = captureAt', providers)
        self.assertIn('payload["gps_age_seconds"] = ageSeconds', providers)
        self.assertIn('payload["gps_fix_kind"]', providers)
        self.assertIn('payload["gps_quality"]', providers)
        self.assertIn('"active_activity"', providers)
        self.assertIn('payload["gps_may_be_stale"]', providers)
        self.assertIn('"priority" => sequence == 1 ? "1" : "0"', providers)
        self.assertIn('"timestamp" => sentAt.format("%d")', providers)
        self.assertIn("sequence,\n                now,", send)
        self.assertIn("method(:onDirectLocationResponse)", send)
        self.assertIn('"alert_uid" => eventId', providers)
        self.assertIn('"state" => "alerting"', providers)
        self.assertIn('payload["link_to_upstream_details"] = sourceLink', providers)
        self.assertIn("method(:onGrafanaLocationResponse)", send)
        self.assertIn("responseCode == 200 && isPushoverMessageAcceptance(data)", response)
        self.assertIn("responseCode >= 200 && responseCode < 300", response)
        self.assertIn("responseCode == 429", response)
        self.assertIn("completeDirectLocationProvider(true)", response)
        self.assertIn("completeDirectLocationProvider(false)", response)
        acceptance = response[
            response.index("function isPushoverMessageAcceptance(") :
        ]
        self.assertIn('data["status"] == 1', acceptance)
        self.assertIn('isProviderReference(data["request"])', acceptance)
        self.assertNotIn("receipt", acceptance)

    def test_invalid_direct_test_state_recovers_without_clearing_live_or_queue(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        load = source[
            source.index("function loadState()")
            : source.index("function migrateLegacyTest()")
        ]
        recovery = load[load.index("function isRecoverableInvalidDirectTestState(") :]

        self.assertIn("if (isRecoverableInvalidDirectTestState(stored))", load)
        self.assertIn("clearInvalidDirectTestState();", load)
        self.assertIn("OpenDistressProtocol.hasExactKeys(value, STATE_KEYS)", recovery)
        self.assertIn('value["queue"].size() == 0', recovery)
        self.assertIn('value["active"] == null', recovery)
        self.assertIn('value["direct_result"] != null', recovery)
        self.assertIn("Storage.deleteValue(STATE_KEY)", recovery)

    def test_direct_gps_resumes_retries_and_scrubs_location_state(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        on_show = source[source.index("function onShow()") : source.index("function onHide()")]
        expiry = source[
            source.index("function expireDirectLocations()")
            : source.index("function queueDirectLocation(")
        ]
        reset = source[
            source.index("function resetAcceptedTest()")
            : source.index("function menuAction()")
        ]

        self.assertIn("resumeDirectLocations();", on_show)
        self.assertIn('if (_directResult["pending_location_hex"].length() > 0)', source)
        self.assertIn("scheduleDirectLocationRetry(true);", source)
        self.assertIn("scheduleDirectLocationRetry(false);", source)
        self.assertIn("rejectDirectLocationProvider(pushover);", source)
        self.assertIn("method(:retryDirectLocation)", source)
        self.assertIn("_directLocationRetryBlocked = true", source)
        self.assertIn('setState("LOCATION SCRUB UNSAVED"', expiry)
        self.assertIn('"LOCATION STATE UNSAVED"', source)
        self.assertIn('"ROUTE CHANGED"', source)
        cover = source[
            source.index("function shouldShowCover()")
            : source.index("function scheduleIdleCoverRefresh()")
        ]
        self.assertIn('"LOCATION STATE UNSAVED"', cover)
        self.assertIn('"ROUTE CHANGED"', cover)
        self.assertIn("method(:expireDirectLocations)", expiry)
        self.assertIn("_directLocationRetryBlocked = false", on_show)
        self.assertIn("stopLocations();", expiry)
        self.assertIn('persistDirectTracking(', expiry)
        self.assertIn('"",\n            0,\n            3,\n            ""', expiry)
        self.assertIn("stopLocations();", reset)
        self.assertIn("LEGACY_DIRECT_RESULT_KEYS", source)
        for key in (
            "tracking_expires_at",
            "next_location_sequence",
            "last_location_hex",
            "last_location_queued_at",
            "capture_stage",
            "pending_location_hex",
            "pushover_accepted",
            "grafana_accepted",
            "grafana_alert_pending",
            "pending_location_pushover",
            "pending_location_grafana",
        ):
            self.assertIn(f'"{key}"', source)

    def test_live_confirmation_haptic_follows_durable_commit(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        live = source[source.index("function activateLive()") : source.index("function captureLocations()")]

        self.assertLess(live.index("!persistState([event], active)"), live.index("confirmDurableTrigger();"))
        self.assertLess(live.index("confirmDurableTrigger();"), live.index("sendPending();"))
        self.assertIn("Attention.vibrate([new Attention.VibeProfile(25, 120)])", live)

    def test_restart_preserves_active_live_semantics_and_retry_timer_cannot_go_stale(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        activate = source[source.index("function activate()") : source.index("function activateTest()")]
        send = source[source.index("function sendPending()") : source.index("function onResponse(")]

        mode_check = 'OpenDistressProtocol.stringEquals(_mode, "LIVE")'
        self.assertLess(activate.index("_activeIncident != null"), activate.index(mode_check))
        self.assertIn('now < _activeIncident["expires_at"]', activate)
        self.assertIn("Repeated press keeps the same incident", activate)
        self.assertLess(activate.index("expireLocations();"), activate.index(mode_check))
        self.assertLess(send.index("_retryTimer.stop();"), send.index("Communications.makeWebRequest("))

    def test_only_expired_live_events_can_be_explicitly_removed(self):
        source = (GARMIN / "source/OpenDistressApp.mc").read_text()
        menu = source[source.index("function menuAction()") : source.index("function setState(")]

        self.assertIn('now < _queue[j]["expires_at"]', menu)
        self.assertIn('if (_queue[k]["v"] == 1)', menu)
        self.assertIn('OpenDistressProtocol.stringEquals(nextActive["incident_id"], incidentId)', menu)
        self.assertIn('setState("RESULT UNKNOWN — EXPIRED"', menu)
        self.assertIn('setState("LIVE RETAINED"', menu)
        self.assertIn("refreshConfiguredMode();", menu)
        self.assertNotIn('OpenDistressProtocol.stringEquals(_mode, "TEST") ? "LIVE" : "TEST"', menu)
        send = source[source.index("function sendPending()") : source.index("function onResponse(")]
        self.assertIn('now >= event["expires_at"]', send)
        self.assertLess(send.index('now >= event["expires_at"]'), send.index("makeWebRequest("))

        expiry = source[source.index("function expireLocations()") : source.index("function scheduleLocationExpiry(")]
        self.assertIn("refreshConfiguredMode();", expiry)
        self.assertIn("persistState(_queue, null)", expiry)
        self.assertIn('setState("LOCAL DISARM UNSAVED"', expiry)
        self.assertIn("Encrypted pending events retained; MENU archives", expiry)
        self.assertIn('"Hold top button 2.5 seconds for a new incident"', expiry)

        validation = source[
            source.index("function validStoredState(") : source.index("function validActive(")
        ]
        self.assertIn('var archivedIncidentId = ""', validation)
        self.assertIn('var archivedExpiresAt = -1', validation)
        self.assertIn('queue[i]["incident_id"],', validation)
        self.assertIn("archivedIncidentId", validation)
        self.assertIn('queue[i]["expires_at"] != archivedExpiresAt', validation)

    def test_queue_clears_only_for_exact_signed_durable_acceptance(self):
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        protocol = (GARMIN / "source/OpenDistressProtocol.mc").read_text()

        self.assertIn("responseCode == 202", app)
        self.assertIn("OpenDistressProtocol.verifyDurablyAccepted(data, event, keyHex)", app)
        self.assertIn("result=durably_accepted", protocol)
        relay_response = app[
            app.index("function onResponse(") : app.index("function beginWifiFallback(")
        ]
        self.assertIn("Provider evidence remains separate", relay_response)
        self.assertNotIn("PROVIDER ACCEPTED", relay_response)
        self.assertIn("LIVE events cannot be abandoned", app)

    def test_relay_live_location_is_fixed_size_encrypted_and_never_sent_as_plain_json(self):
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        protocol = (GARMIN / "source/OpenDistressProtocol.mc").read_text()
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
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
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
        self.assertIn("enableBestContinuousLocation()", stage_one_capture)
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
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        readme = (GARMIN / "README.md").read_text()
        normalized_readme = " ".join(readme.split())

        for value in (
            "FIRST_CADENCE_SECONDS = 30",
            "MIDDLE_CADENCE_SECONDS = 120",
            "LATE_CADENCE_SECONDS = 300",
            "EXTENDED_CADENCE_SECONDS = 900",
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
        self.assertIn("LIVE_EXPIRY_SECONDS = 86400", app)
        self.assertIn("activeFor < 21600", app)
        self.assertIn('setState("CLOCK INCONSISTENT", "Future GPS fix was not queued")', app)
        self.assertIn("System.getSystemStats()", app)
        self.assertIn("scheduleLocationExpiry(now)", app)
        self.assertNotIn(": remaining + 1", app)
        self.assertIn("_visible = false", app)
        self.assertIn("same foreground-only cadence queries signed `/v2/status`", normalized_readme)
        self.assertIn("strict `-l 3` build still fails and remains a release gate", normalized_readme)
        self.assertIn("-l 1 -w", readme)

    def test_signed_status_poll_shares_foreground_cadence_and_request_gate(self):
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        protocol = (GARMIN / "source/OpenDistressProtocol.mc").read_text()
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
        self.assertIn("OpenDistressProtocol.isEncryptedEvent(head)", bypass)
        self.assertIn(
            'OpenDistressProtocol.stringEquals(head["kind"], OpenDistressProtocol.V2_LOCATION_KIND)',
            bypass,
        )
        self.assertIn('head["incident_id"],', bypass)
        self.assertIn('_activeIncident["incident_id"]', bypass)
        self.assertIn('head["expires_at"] == _activeIncident["expires_at"]', bypass)
        self.assertIn("!canPollStatus()", send)
        self.assertIn("OpenDistressProtocol.randomId()", send)
        self.assertIn('config["base_url"] + "/v2/status"', send)
        self.assertIn(':context => query["request_id"]', send)
        self.assertIn("method(:onStatusResponse)", send)
        self.assertIn("responseCode == 200", response)
        self.assertIn("OpenDistressProtocol.verifyStatusResult(data, query, keyHex, receiveAt)", response)
        self.assertLess(response.index("verifyStatusResult"), response.index("finishIncidentFromStatus"))
        self.assertIn('OpenDistressProtocol.stringEquals(data["state"], "resolved")', response)
        self.assertIn('OpenDistressProtocol.stringEquals(data["state"], "expired")', response)
        self.assertIn('OpenDistressProtocol.stringEquals(data["state"], "acknowledged")', response)
        self.assertLess(continuation.index("sendPending();"), continuation.index("scheduleStatusPoll(now);"))
        self.assertNotIn("persistState(", failure)
        self.assertIn("persistState(remaining, null)", terminal)
        self.assertIn('!OpenDistressProtocol.stringEquals(', terminal)
        self.assertIn('_queue[i]["incident_id"],', terminal)
        self.assertIn("refreshConfiguredMode();", terminal)
        self.assertNotIn("ACCEPTED", terminal)

        for domain in ("opendistress.status.query.v2", "opendistress.status.result.v2"):
            self.assertIn(domain, protocol)
        self.assertIn("hasExactKeys(data, STATUS_RESULT_KEYS)", protocol)
        self.assertIn('!stringEquals(data["request_id"], query["request_id"])', protocol)
        self.assertIn('!stringEquals(data["incident_id"], query["incident_id"])', protocol)
        self.assertIn('!stringEquals(data["device_id"], query["device_id"])', protocol)
        self.assertIn('receiveAt - query["created_at"] > 300', protocol)
        self.assertIn('query["created_at"] - data["checked_at"] > 300', protocol)
        self.assertIn('data["checked_at"] - receiveAt > 300', protocol)

        event_response = app[app.index("function onResponse(") : app.index("function handleFailure(")]
        self.assertIn("!OpenDistressProtocol.stringEquals(eventId, _requestEventId)", event_response)
        self.assertIn(
            '!OpenDistressProtocol.stringEquals(requestId, _statusQuery["request_id"])',
            response,
        )

    def test_glance_and_complication_only_launch_or_label_the_foreground_app(self):
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        complication = ET.parse(
            GARMIN / "resources/complications/complications.xml"
        ).getroot()

        self.assertIn("(:glance)\n    function getGlanceView()", app)
        self.assertIn("(:glance)\nclass OpenDistressGlanceView", app)
        self.assertEqual(len(complication.findall("./complication")), 1)
        glance_class = app[app.index("class OpenDistressGlanceView") : app.index("class OpenDistressView")]
        self.assertNotIn("makeWebRequest", glance_class)
        self.assertNotIn("Position.", glance_class)

    def test_wifi_fallback_never_delays_first_attempt_or_discards_pending_event(self):
        app = (GARMIN / "source/OpenDistressApp.mc").read_text()
        send = app[app.index("function sendPending(") : app.index("function onResponse(")]
        response = app[app.index("function onResponse(") : app.index("function handleFailure(")]
        fallback = app[
            app.index("function beginWifiFallback(") : app.index("function handleFailure(")
        ]

        self.assertIn("connections[:wifi]", app)
        self.assertIn("route not forced", app)
        self.assertIn("connections[:lte]", app)
        self.assertIn("CIQ web route unclaimed", app)
        self.assertNotIn("checkWifiConnection", send)
        self.assertIn("Communications.makeWebRequest(", send)
        self.assertIn("beginWifiFallback(event, responseCode)", response)
        self.assertIn("Communications.BLE_CONNECTION_UNAVAILABLE", fallback)
        self.assertIn("Communications.BLE_HOST_TIMEOUT", fallback)
        self.assertIn("Communications.checkWifiConnection", fallback)
        self.assertIn("WIFI_CHECK_TIMEOUT_MS", fallback)
        self.assertIn("method(:wifiCheckTimedOut)", fallback)
        self.assertIn("function wifiCheckTimedOut()", fallback)
        self.assertIn("Wi-Fi check timed out; pending event retained", fallback)
        self.assertIn("Phone unavailable; pending event retained", fallback)
        self.assertIn("Pending until provider acceptance", fallback)
        self.assertIn("Pending until signed relay acceptance", fallback)
        self.assertIn("sendPending();", fallback)
        self.assertNotIn("persistState([],", fallback)

        on_show = app[app.index("function onShow(") : app.index("function onHide(")]
        self.assertIn("if (_queue.size() > 0)", on_show)
        self.assertIn("sendPending();", on_show)


if __name__ == "__main__":
    unittest.main()

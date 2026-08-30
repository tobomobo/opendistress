# SPDX-License-Identifier: MIT

import plistlib
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).parents[1]
WEAR = ROOT / "apps/wearos"
WATCH = ROOT / "apps/watchos"


class NativeContractTests(unittest.TestCase):
    def test_android_metadata_and_public_defaults_are_safe(self):
        manifest = ET.parse(WEAR / "app/src/main/AndroidManifest.xml").getroot()
        ET.parse(WEAR / "app/src/main/res/values/styles.xml")
        android = "{http://schemas.android.com/apk/res/android}"
        permissions = {
            item.attrib[android + "name"] for item in manifest.findall("uses-permission")
        }
        self.assertEqual(
            permissions,
            {
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.INTERNET",
                "android.permission.VIBRATE",
            },
        )
        feature = manifest.find("uses-feature")
        self.assertEqual(feature.attrib[android + "name"], "android.hardware.type.watch")
        self.assertEqual(feature.attrib[android + "required"], "true")
        application = manifest.find("application")
        self.assertEqual(application.attrib[android + "usesCleartextTraffic"], "false")
        standalone = application.find("meta-data")
        self.assertEqual(standalone.attrib[android + "name"], "com.google.android.wearable.standalone")
        self.assertEqual(standalone.attrib[android + "value"], "true")

        build = (WEAR / "app/build.gradle.kts").read_text()
        self.assertIn('"https://invalid.example/v2/events"', build)
        self.assertGreaterEqual(build.count('"INVALID"'), 5)
        self.assertIn('setting("keyVersion", "0")', build)
        self.assertIn("/panic.local.properties", (WEAR / ".gitignore").read_text().splitlines())
        example = (WEAR / "panic.local.properties.example").read_text()
        self.assertNotRegex(example, r"(?i)\b[0-9a-f]{64}\b")

    def test_watch_metadata_project_and_public_defaults_are_safe(self):
        with (WATCH / "PanicWatch/Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        self.assertIs(info["WKApplication"], True)
        self.assertIs(info["WKWatchOnly"], True)
        self.assertEqual(info["SPBEndpoint"], "$(SPB_ENDPOINT)")
        for key in ("SPBDeviceId", "SPBAuthKeyHex", "SPBEncKeyHex", "SPBMacKeyHex"):
            self.assertRegex(info[key], r"^\$\(SPB_[A-Z_]+\)$")

        ET.parse(WATCH / "PanicWatch.xcodeproj/project.xcworkspace/contents.xcworkspacedata")
        scheme = ET.parse(
            WATCH / "PanicWatch.xcodeproj/xcshareddata/xcschemes/PanicWatch.xcscheme"
        ).getroot()
        self.assertIsNotNone(scheme.find("BuildAction"))
        self.assertIsNotNone(scheme.find("TestAction"))
        self.assertIsNotNone(scheme.find("ArchiveAction"))

        project = (WATCH / "PanicWatch.xcodeproj/project.pbxproj").read_text()
        self.assertIn("SKIP_INSTALL = NO;", project)
        self.assertIn("path = ../../protocol/fixtures;", project)
        for fixture in (
            "live-trigger-v2.json",
            "location-updated-v2.json",
            "status-query-v2.json",
            "status-v2.txt",
        ):
            self.assertGreaterEqual(project.count(fixture), 3)
            self.assertTrue((ROOT / "protocol/fixtures" / fixture).is_file())

        defaults = (WATCH / "Config/PublicDefaults.xcconfig").read_text()
        self.assertIn("invalid.example/v2/events", defaults)
        self.assertGreaterEqual(defaults.count("= INVALID"), 5)
        self.assertIn("SPB_KEY_VERSION = 0", defaults)
        self.assertIn('#include? "Local.xcconfig"', defaults)
        self.assertIn("/Config/Local.xcconfig", (WATCH / ".gitignore").read_text().splitlines())
        example = (WATCH / "Config/Local.xcconfig.example").read_text()
        self.assertNotRegex(example, r"(?i)\b[0-9a-f]{64}\b")

    def test_native_workflow_is_reproducible_and_actions_are_sha_pinned(self):
        workflow = (ROOT / ".github/workflows/native.yml").read_text()
        actions = re.findall(r"^\s*- uses:\s*(\S+)", workflow, flags=re.MULTILINE)
        self.assertTrue(actions)
        for action in actions:
            self.assertRegex(action, r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")
        for expected in (
            "runs-on: ubuntu-24.04",
            "runs-on: macos-26",
            "gradle-version: \"8.11.1\"",
            "DEVELOPER_DIR: /Applications/Xcode_26.6.app/Contents/Developer",
            'runtime.endswith("watchOS-26-5")',
            'device["name"] == "Apple Watch Series 11 (46mm)"',
            '-destination "platform=watchOS Simulator,id=${watch_id}"',
            "-enableAddressSanitizer YES",
            "CODE_SIGNING_ALLOWED=NO",
            "archive",
        ):
            self.assertIn(expected, workflow)
        self.assertNotIn("OS=latest", workflow)

    def test_both_native_test_targets_resolve_shared_fixtures(self):
        android_test = (
            WEAR / "app/src/test/java/dev/smartpanic/wear/ProtocolTest.kt"
        ).read_text()
        self.assertIn('root.resolve("protocol/fixtures/$name")', android_test)
        self.assertIn('systemProperty("spb.repo.root"', (WEAR / "app/build.gradle.kts").read_text())

        swift_test = (WATCH / "PanicWatchTests/ProtocolTests.swift").read_text()
        self.assertIn("Bundle(for: ProtocolTests.self).url(forResource: name", swift_test)
        project = (WATCH / "PanicWatch.xcodeproj/project.pbxproj").read_text()
        for fixture in (
            "live-trigger-v2.json in Resources",
            "location-updated-v2.json in Resources",
            "status-query-v2.json in Resources",
            "status-v2.txt in Resources",
        ):
            self.assertIn(fixture, project)

    def test_status_polling_is_signed_strict_and_foreground_only(self):
        kotlin_protocol = (
            WEAR / "app/src/main/java/dev/smartpanic/wear/Protocol.kt"
        ).read_text()
        swift_protocol = (WATCH / "PanicWatch/Protocol.swift").read_text()
        for source in (kotlin_protocol, swift_protocol):
            self.assertIn("spb.status.query.v2", source)
            self.assertIn("spb.status.result.v2", source)
            for state in ("active", "acknowledged", "resolved", "expired"):
                self.assertIn(state, source)

        kotlin_transport = (
            WEAR / "app/src/main/java/dev/smartpanic/wear/Transport.kt"
        ).read_text()
        swift_transport = (WATCH / "PanicWatch/Transport.swift").read_text()
        self.assertIn('"/v2/status"', kotlin_transport)
        self.assertIn("active.responseCode != 200", kotlin_transport)
        self.assertIn('appendingPathComponent("status")', swift_transport)
        self.assertIn("http.statusCode == 200", swift_transport)
        self.assertIn("instanceFollowRedirects = false", kotlin_transport)
        self.assertIn("delegate: redirects", swift_transport)

        kotlin_controller = (
            WEAR / "app/src/main/java/dev/smartpanic/wear/MainActivity.kt"
        ).read_text()
        swift_controller = (WATCH / "PanicWatch/PanicController.swift").read_text()
        self.assertIn("!isForeground", kotlin_controller)
        self.assertIn("pendingStatusRequestId != query.requestId", kotlin_controller)
        self.assertIn("if (now >= event.expiresAt)", kotlin_controller)
        self.assertIn('verified?.state == "resolved" || verified?.state == "expired"', kotlin_controller)
        self.assertIn("isSceneActive", swift_controller)
        self.assertIn("self.pendingStatusRequestId == query.requestId", swift_controller)
        self.assertIn("guard now < event.expiresAt else", swift_controller)
        self.assertIn('verified.state == "resolved" || verified.state == "expired"', swift_controller)
        swift_poll = swift_controller[
            swift_controller.index("private func pollStatus")
            : swift_controller.index("private func scheduleRetry")
        ]
        self.assertLess(
            swift_poll.index("self.statusInProgress = false"),
            swift_poll.index("guard store.state.capturePlan == expectedPlan"),
        )
        self.assertNotIn("WorkManager", kotlin_controller)
        self.assertNotIn("WKExtendedRuntimeSession", swift_controller)

        kotlin_store = (
            WEAR / "app/src/main/java/dev/smartpanic/wear/EventStore.kt"
        ).read_text()
        swift_store = (WATCH / "PanicWatch/EventStore.swift").read_text()
        self.assertIn("queue.filterNot { it.incidentId == incidentId }", kotlin_store)
        self.assertIn("next.queue.removeAll { $0.incidentId == incidentId }", swift_store)
        self.assertIn("plan.expiresAt - plan.startedAt in 1..MAX_EVENT_LIFETIME_SECONDS", kotlin_store)
        self.assertIn("stateAfterExpiredLocationScrub", kotlin_store)
        self.assertIn("lastLatitudeE7 = null", kotlin_store)
        self.assertIn("archive.archivedAt in archive.expiresAt..PROTOCOL_MAX", kotlin_store)
        self.assertIn("now >= it", kotlin_store)
        self.assertIn("plan.expiresAt - plan.startedAt", swift_store)
        self.assertIn("func scrubExpiredLocation", swift_store)
        self.assertIn("lastLatitudeE7: nil", swift_store)
        self.assertIn("archive.expiresAt...protocolMaximum", swift_store)
        self.assertIn("now >= $0", swift_store)
        self.assertIn(
            "staleTerminalStatusCannotArchiveANewIncident",
            (WEAR / "app/src/test/java/dev/smartpanic/wear/ProtocolTest.kt").read_text(),
        )
        self.assertIn(
            "testStaleTerminalStatusCannotArchiveANewIncident",
            (WATCH / "PanicWatchTests/ProtocolTests.swift").read_text(),
        )
        self.assertIn(
            "loadedExpiredStateScrubsCoordinatesButPreservesRecoveryEvidence",
            (WEAR / "app/src/test/java/dev/smartpanic/wear/ProtocolTest.kt").read_text(),
        )
        self.assertIn(
            "testRestartAfterExpiryScrubsCoordinatesButPreservesRecoveryEvidence",
            (WATCH / "PanicWatchTests/ProtocolTests.swift").read_text(),
        )


if __name__ == "__main__":
    unittest.main()

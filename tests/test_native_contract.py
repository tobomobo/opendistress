# SPDX-License-Identifier: MIT

import json
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
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.FOREGROUND_SERVICE_LOCATION",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.VIBRATE",
                "android.permission.WAKE_LOCK",
            },
        )
        feature = manifest.find("uses-feature")
        self.assertEqual(feature.attrib[android + "name"], "android.hardware.type.watch")
        self.assertEqual(feature.attrib[android + "required"], "true")
        application = manifest.find("application")
        self.assertEqual(application.attrib[android + "usesCleartextTraffic"], "false")
        self.assertEqual(application.attrib[android + "icon"], "@mipmap/ic_launcher")
        self.assertEqual(application.attrib[android + "roundIcon"], "@mipmap/ic_launcher_round")
        for resource in (
            "drawable/ic_launcher_foreground.xml",
            "drawable/ic_launcher_monochrome.xml",
            "mipmap-anydpi-v26/ic_launcher.xml",
            "mipmap-anydpi-v26/ic_launcher_round.xml",
            "mipmap-anydpi-v33/ic_launcher.xml",
            "mipmap-anydpi-v33/ic_launcher_round.xml",
            "values/colors.xml",
        ):
            ET.parse(WEAR / "app/src/main/res" / resource)
        standalone = application.find("meta-data")
        self.assertEqual(standalone.attrib[android + "name"], "com.google.android.wearable.standalone")
        self.assertEqual(standalone.attrib[android + "value"], "false")

        build = (WEAR / "app/build.gradle.kts").read_text()
        settings = (WEAR / "settings.gradle.kts").read_text()
        self.assertIn('rootProject.name = "OpenDistressWear"', settings)
        self.assertIn('namespace = "dev.opendistress.wear"', build)
        self.assertIn('applicationId = "dev.opendistress.wear"', build)
        self.assertIn('"https://invalid.example/v2/events"', build)
        self.assertGreaterEqual(build.count('"INVALID"'), 5)
        self.assertIn('setting("keyVersion", "0")', build)
        self.assertIn('androidx.work:work-runtime:2.11.2', build)
        self.assertIn('androidx.wear:wear-remote-interactions:1.2.0', build)
        self.assertIn(
            "/opendistress.local.properties",
            (WEAR / ".gitignore").read_text().splitlines(),
        )
        mobile_build = (WEAR / "mobile/build.gradle.kts").read_text()
        self.assertIn('namespace = "dev.opendistress.mobile"', mobile_build)
        self.assertIn('applicationId = "dev.opendistress.wear"', mobile_build)
        watch_version = re.search(r"versionCode = ([0-9_]+)", build)
        mobile_version = re.search(r"versionCode = ([0-9_]+)", mobile_build)
        self.assertIsNotNone(watch_version)
        self.assertIsNotNone(mobile_version)
        self.assertNotEqual(watch_version.group(1), mobile_version.group(1))
        example = (WEAR / "opendistress.local.properties.example").read_text()
        self.assertNotRegex(example, r"(?i)\b[0-9a-f]{64}\b")
        wear_capabilities = ET.parse(
            WEAR / "mobile/src/main/res/values/wear.xml"
        ).getroot()
        self.assertEqual(
            wear_capabilities.find("string-array/item").text,
            "opendistress_phone_setup",
        )

    def test_watch_metadata_project_and_public_defaults_are_safe(self):
        with (WATCH / "OpenDistressWatch/Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        self.assertIs(info["WKApplication"], True)
        self.assertIs(info["WKWatchOnly"], True)
        self.assertEqual(info["OpenDistressEndpoint"], "$(OPENDISTRESS_ENDPOINT)")
        for key in (
            "OpenDistressDeviceId",
            "OpenDistressAuthKeyHex",
            "OpenDistressEncKeyHex",
            "OpenDistressMacKeyHex",
        ):
            self.assertRegex(info[key], r"^\$\(OPENDISTRESS_[A-Z_]+\)$")

        ET.parse(WATCH / "OpenDistressWatch.xcodeproj/project.xcworkspace/contents.xcworkspacedata")
        scheme = ET.parse(
            WATCH / "OpenDistressWatch.xcodeproj/xcshareddata/xcschemes/OpenDistressWatch.xcscheme"
        ).getroot()
        self.assertIsNotNone(scheme.find("BuildAction"))
        self.assertIsNotNone(scheme.find("TestAction"))
        self.assertIsNotNone(scheme.find("ArchiveAction"))

        project = (WATCH / "OpenDistressWatch.xcodeproj/project.pbxproj").read_text()
        self.assertEqual(project.count("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"), 2)
        self.assertGreaterEqual(project.count("Assets.xcassets"), 3)
        app_icon = WATCH / "OpenDistressWatch/Assets.xcassets/AppIcon.appiconset"
        contents = json.loads((app_icon / "Contents.json").read_text())
        self.assertEqual(contents["images"][0]["platform"], "watchos")
        self.assertEqual(contents["images"][0]["size"], "1024x1024")
        self.assertTrue((app_icon / contents["images"][0]["filename"]).is_file())
        self.assertEqual(project.count("PRODUCT_BUNDLE_IDENTIFIER = dev.opendistress.watch;"), 2)
        self.assertEqual(
            project.count("PRODUCT_BUNDLE_IDENTIFIER = dev.opendistress.watch.tests;"),
            2,
        )
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
        self.assertIn("OPENDISTRESS_KEY_VERSION = 0", defaults)
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
            "apps/wearos/gradlew --no-daemon -p apps/wearos",
            "DEVELOPER_DIR: /Applications/Xcode_26.6.app/Contents/Developer",
            'runtime.endswith("watchOS-26-5")',
            'device["name"] == "Apple Watch Series 11 (46mm)"',
            '-destination "platform=watchOS Simulator,id=${watch_id}"',
            "-enableAddressSanitizer YES",
            "CODE_SIGNING_ALLOWED=NO",
            "OpenDistress-Android-Setup-debug.apk",
            "OpenDistress-Pixel-Watch-debug.apk",
            "opendistress-pixel-watch-install-pair-${{ github.sha }}",
            "OpenDistress-Android-Setup-release-unsigned.aab",
            "OpenDistress-Pixel-Watch-release-unsigned.aab",
            "opendistress-pixel-watch-unsigned-bundles-${{ github.sha }}",
            "sha256sum *.apk > SHA256SUMS.txt",
            "sha256sum *.aab > SHA256SUMS.txt",
            "if-no-files-found: error",
            "archive",
        ):
            self.assertIn(expected, workflow)
        self.assertNotIn("OS=latest", workflow)
        wrapper = (WEAR / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("gradle-9.5.0-bin.zip", wrapper)
        self.assertIn(
            "distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746",
            wrapper,
        )

    def test_both_native_test_targets_resolve_shared_fixtures(self):
        android_test = (
            WEAR / "app/src/test/java/dev/opendistress/wear/ProtocolTest.kt"
        ).read_text()
        self.assertIn('root.resolve("protocol/fixtures/$name")', android_test)
        self.assertIn('systemProperty("opendistress.repo.root"', (WEAR / "app/build.gradle.kts").read_text())

        swift_test = (WATCH / "OpenDistressWatchTests/ProtocolTests.swift").read_text()
        self.assertIn("Bundle(for: ProtocolTests.self).url(forResource: name", swift_test)
        project = (WATCH / "OpenDistressWatch.xcodeproj/project.pbxproj").read_text()
        for fixture in (
            "live-trigger-v2.json in Resources",
            "location-updated-v2.json in Resources",
            "status-query-v2.json in Resources",
            "status-v2.txt in Resources",
        ):
            self.assertIn(fixture, project)

    def test_status_polling_is_signed_strict_and_foreground_only(self):
        kotlin_protocol = (
            WEAR / "app/src/main/java/dev/opendistress/wear/Protocol.kt"
        ).read_text()
        swift_protocol = (WATCH / "OpenDistressWatch/Protocol.swift").read_text()
        for source in (kotlin_protocol, swift_protocol):
            self.assertIn("opendistress.status.query.v2", source)
            self.assertIn("opendistress.status.result.v2", source)
            for state in ("active", "acknowledged", "resolved", "expired"):
                self.assertIn(state, source)

        kotlin_transport = (
            WEAR / "app/src/main/java/dev/opendistress/wear/Transport.kt"
        ).read_text()
        swift_transport = (WATCH / "OpenDistressWatch/Transport.swift").read_text()
        self.assertIn('"/v2/status"', kotlin_transport)
        self.assertIn("active.responseCode != 200", kotlin_transport)
        self.assertIn('appendingPathComponent("status")', swift_transport)
        self.assertIn("http.statusCode == 200", swift_transport)
        self.assertIn("instanceFollowRedirects = false", kotlin_transport)
        self.assertIn("delegate: redirects", swift_transport)

        kotlin_controller = (
            WEAR / "app/src/main/java/dev/opendistress/wear/MainActivity.kt"
        ).read_text()
        swift_controller = (WATCH / "OpenDistressWatch/OpenDistressController.swift").read_text()
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
            WEAR / "app/src/main/java/dev/opendistress/wear/EventStore.kt"
        ).read_text()
        swift_store = (WATCH / "OpenDistressWatch/EventStore.swift").read_text()
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
            (WEAR / "app/src/test/java/dev/opendistress/wear/ProtocolTest.kt").read_text(),
        )
        self.assertIn(
            "testStaleTerminalStatusCannotArchiveANewIncident",
            (WATCH / "OpenDistressWatchTests/ProtocolTests.swift").read_text(),
        )
        self.assertIn(
            "loadedExpiredStateScrubsCoordinatesButPreservesRecoveryEvidence",
            (WEAR / "app/src/test/java/dev/opendistress/wear/ProtocolTest.kt").read_text(),
        )
        self.assertIn(
            "testRestartAfterExpiryScrubsCoordinatesButPreservesRecoveryEvidence",
            (WATCH / "OpenDistressWatchTests/ProtocolTests.swift").read_text(),
        )


if __name__ == "__main__":
    unittest.main()

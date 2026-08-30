# SPDX-License-Identifier: MIT

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parents[1]
GARMIN = ROOT / "apps/garmin"


class GarminContractTests(unittest.TestCase):
    def test_resources_parse_and_permissions_stay_minimal(self):
        for path in GARMIN.glob("resources/*/*.xml"):
            ET.parse(path)
        manifest = ET.parse(GARMIN / "manifest.xml").getroot()
        namespace = {"iq": "http://www.garmin.com/xml/connectiq"}
        permissions = {
            item.attrib["id"] for item in manifest.findall(".//iq:uses-permission", namespace)
        }
        products = {item.attrib["id"] for item in manifest.findall(".//iq:product", namespace)}
        settings = ET.parse(GARMIN / "resources/settings/settings.xml").getroot()
        device_id_editor = settings.find("./setting[@propertyKey='@Properties.deviceId']/settingConfig")
        self.assertEqual(permissions, {"Communications", "Cryptography"})
        self.assertEqual(products, {"fenix847mm"})
        self.assertEqual(device_id_editor.attrib["type"], "password")

    def test_monkey_c_self_test_uses_the_published_vectors(self):
        values = dict(
            line.split("=", 1)
            for line in (ROOT / "protocol/fixtures/signature-v1.txt").read_text().splitlines()
            if line and not line.startswith("#")
        )
        protocol_source = (GARMIN / "source/PanicProtocol.mc").read_text()
        self.assertIn(values["request_signature"], protocol_source)
        self.assertIn(values["response_signature"], protocol_source)

    def test_event_is_persisted_before_the_only_network_call(self):
        app_source = (GARMIN / "source/PanicApp.mc").read_text()
        self.assertEqual(app_source.count("Communications.makeWebRequest("), 1)
        self.assertLess(
            app_source.index('Storage.setValue(PENDING_KEY, event)'),
            app_source.index("Communications.makeWebRequest("),
        )
        self.assertNotIn("Positioning", app_source)

    def test_failures_follow_body_result_and_pending_test_can_be_abandoned(self):
        app_source = (GARMIN / "source/PanicApp.mc").read_text()
        protocol_source = (GARMIN / "source/PanicProtocol.mc").read_text()
        self.assertIn("PanicProtocol.failureResult(data)", app_source)
        self.assertIn("function onMenu()", app_source)
        self.assertIn("Storage.deleteValue(PENDING_KEY)", app_source)
        self.assertIn('value["created_at"] >= 0', protocol_source)
        self.assertIn("MAX_CREATED_AT", protocol_source)


if __name__ == "__main__":
    unittest.main()

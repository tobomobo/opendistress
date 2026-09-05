// SPDX-License-Identifier: MIT
import Toybox.Application.Storage;

(:test)
function companionProfileCacheRegression(logger) {
    // Synthetic configuration only. This test builds payloads but never sends.
    var previous = Storage.getValue(DirectAlertSettings.STORAGE_KEY);
    var config = {
        "protocol" => DirectAlertSettings.PROTOCOL,
        "type" => "config", "revision" => "1"
    };
    for (var i = 0; i < DirectAlertSettings.VALUE_KEYS.size(); i += 1) {
        config[DirectAlertSettings.VALUE_KEYS[i]] = "";
    }
    config["pushoverUserKey"] = "abcdefghijklmnopqrstuvwxyz1234";
    config["pushoverApiToken"] = "abcdefghijklmnopqrstuvwxyz1234";
    // Fully filled profile reproduces the physical watchdog risk that the
    // previous one-field fixture missed. ASCII fixture, not user information.
    for (var field = 3; field < 10; field += 1) {
        var text = "";
        for (var n = 0; n < DirectAlertSettings.VALUE_LIMITS[field]; n += 1) { text += "x"; }
        config[DirectAlertSettings.VALUE_KEYS[field]] = text;
    }
    config["config_digest"] = DirectAlertSettings.configDigest(config);
    Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
    DirectAlertSettings.invalidateCache();
    var ok = DirectAlertSettings.install(config);
    var first = DirectAlertSettings.storedConfig();
    for (var j = 0; j < 2; j += 1) {
        var payload = DirectGrafanaAdapter.initialPayload("AAECAwQFBgcICQoLDA0ODw");
        ok = ok && payload != null && DirectAlertSettings.storedConfig() == first;
    }
    // Invalidation must reject a corrupt persisted replacement.
    Storage.setValue(DirectAlertSettings.STORAGE_KEY, {"type" => "config"});
    DirectAlertSettings.invalidateCache();
    ok = ok && DirectAlertSettings.storedConfig() == null;
    if (previous == null) {
        Storage.deleteValue(DirectAlertSettings.STORAGE_KEY);
    } else {
        Storage.setValue(DirectAlertSettings.STORAGE_KEY, previous);
    }
    DirectAlertSettings.invalidateCache();
    return ok;
}

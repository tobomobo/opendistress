# Garmin platform limitations

- Connect IQ applications are written in Garmin's Monkey C and run in its
  managed virtual machine. Connect IQ exposes no Rust, native-library, WASM,
  or foreign-function target, so Rust cannot replace the on-watch client.
  Monkey C avoids raw-pointer/manual-allocation bugs, but its gradual type
  system is not Rust's borrow checker and runtime type errors remain possible.
  SDK 9.2.0 compiles the source at `-l 1` and its protocol vectors pass in the
  simulator; strict `-l 3` remains an unmet release gate.
- A public Connect IQ application cannot install a global third-party hardware
  button listener. The user must first enter an allowed foreground surface.
- The device app supports the app list, a glance, and a published complication.
  Those are activation experiments, not proof of globally available buttons.
- A watch face, if hardware testing eventually earns one, launches the device
  app; it does not send the incident itself.
- The public communications API does not let an app force BLE/phone transport
  versus watch Wi-Fi. Wi-Fi is opportunistic until physical tests prove it.
- `Toybox.Communications.makeWebRequest()` accepts a `Dictionary` and controls
  its JSON serialization. Authentication therefore covers the fixed semantic
  signing grammar, not raw wire JSON member order or whitespace.
- Connect IQ v1 timestamps stop at `2147482747` so the 900-second expiry fits
  Garmin's signed 32-bit `Number`. Version 2 keeps the same signed 32-bit bound;
  a future protocol must change representation before 2038.
- Foreground lifecycle, activity interactions, firmware, radio state, phone
  power management, and Garmin Connect all affect latency and availability.
- GPS can be slow or unavailable indoors. A LIVE event is persisted and its
  submission is started before location is requested; unavailable fixes remain
  valid encrypted updates.
- Foreground cadence stops at local expiry or a verified signed relay status of
  `resolved`/`expired`. Polling occurs only while the client is foregrounded, so
  lifecycle and network delays still postpone that stop.

Wear OS and watchOS support native Kotlin and Swift respectively. Their source
projects are not build or device evidence: this workspace has no Android SDK,
Xcode, simulators, or watches, so hosted compilation and physical rows remain
separate gates. Local configuration is compiled into personal artifacts;
hardware-backed Keystore/Keychain enrollment is required before distributing
provisioned binaries.

These are test constraints, not promises that more software can remove them.

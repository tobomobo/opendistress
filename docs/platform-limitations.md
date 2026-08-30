# Garmin platform limitations

- A public Connect IQ application cannot install a global third-party hardware
  button listener. The user must first enter an allowed foreground surface.
- Phase 1 therefore starts as the first app in the app list. Glance and stock
  complication launch paths are experiments, not assumed capabilities.
- A watch face, if hardware testing eventually earns one, launches the device
  app; it does not send the incident itself.
- The public communications API does not let an app force BLE/phone transport
  versus watch Wi-Fi. Wi-Fi is opportunistic until physical tests prove it.
- `Toybox.Communications.makeWebRequest()` accepts a `Dictionary` and controls
  its JSON serialization. Authentication therefore covers the fixed semantic
  signing grammar, not raw wire JSON member order or whitespace.
- Connect IQ v1 timestamps stop at `2147482747` so the 900-second expiry fits
  Garmin's signed 32-bit `Number`; a future protocol version must replace this
  representation before 2038.
- Foreground lifecycle, activity interactions, firmware, radio state, phone
  power management, and Garmin Connect all affect latency and availability.
- GPS can be slow or unavailable indoors. A future LIVE alert is sent before
  requesting location and remains valid without a fix.

These are test constraints, not promises that more software can remove them.

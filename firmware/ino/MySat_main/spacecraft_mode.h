// SHAHAR-SAT 1 — spacecraft mode state machine.
//
// Only BOOT, NOMINAL and DESKTOP carry real behaviour in v1; SAFE gets a
// minimal, conservative action (stop logging, retract panels); LOW_POWER
// and PAYLOAD are declared so the structure is ready for later milestones
// but have no behaviour yet. See docs/ARCHITECTURE.md §2.6.
//
// setMode() here never touches Wi-Fi. The original firmware's boot-time
// Wi-Fi bring-up (MySat_main.ino setup(), gated on /config.txt) is left
// completely untouched — a device with stored "auto-connect" credentials
// keeps auto-connecting regardless of spacecraft mode. "Wi-Fi on demand"
// for the desktop workflow instead means: a fresh/unconfigured device
// never starts Wi-Fi (already true today), and WIFI_ON / WIFI_OFF give
// explicit BLE control on top of that. This avoids silently overriding a
// Wi-Fi setup the user configured via the original SetWIFI serial flow.
#pragma once

extern String ssid;
extern String password;

enum SpacecraftMode {
  MODE_BOOT,
  MODE_NOMINAL,
  MODE_SAFE,
  MODE_LOW_POWER,
  MODE_PAYLOAD,
  MODE_DESKTOP
};

SpacecraftMode spacecraftMode = MODE_BOOT;

const char* spacecraftModeName() {
  switch (spacecraftMode) {
    case MODE_BOOT: return "BOOT";
    case MODE_NOMINAL: return "NOMINAL";
    case MODE_SAFE: return "SAFE";
    case MODE_LOW_POWER: return "LOW_POWER";
    case MODE_PAYLOAD: return "PAYLOAD";
    case MODE_DESKTOP: return "DESKTOP";
  }
  return "UNKNOWN";
}

// Defined in ble_service.h (included later); forward-declared here so
// setMode() can announce a transition without ble_service.h depending on
// this file being included after it. Same forward-declare-then-define
// pattern already used across this codebase (e.g. console.h's
// `void setWiFi();` ahead of its real definition in MySat_main.ino).
void bleEmitEvent(const String& type, const String& detail);

void setMode(SpacecraftMode newMode) {
  if (newMode == spacecraftMode) return;
  String from = spacecraftModeName();
  spacecraftMode = newMode;
  logDebug("[MODE] " + from + " -> " + String(spacecraftModeName()));
  bleEmitEvent("MODE_CHANGED", from + "->" + String(spacecraftModeName()));
}

// Bounded Wi-Fi connect for BLE-triggered WIFI_ON. Unlike tryConnectWiFi()
// (MySat_main.ino), this never blocks on Serial and never recurses into
// setWiFi() on failure — it either connects within timeoutMs or gives up
// and returns false. Still a blocking call while it runs (loop() pauses
// for up to timeoutMs), same class of blocking as the original connect
// path, just bounded and BLE-safe.
bool connectWiFiBounded(unsigned long timeoutMs) {
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), password.c_str());

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED) {
    if (millis() - start >= timeoutMs) {
      WiFi.disconnect(true);
      return false;
    }
    delay(100);
  }
  return true;
}

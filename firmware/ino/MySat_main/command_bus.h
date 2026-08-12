// SHAHAR-SAT 1 — transport-neutral command execution.
//
// One implementation per action, called from both the serial console
// (console.h) and BLE (ble_service.h), so the two transports stop
// duplicating logic. See docs/ARCHITECTURE.md §2.4 and docs/COMMANDS.md.
//
// executeCommand() only calls primitives that are safe to run from any
// transport: they don't touch Serial and don't touch the WebServer
// (`server.send`). That ruled out reusing light_on()/motor_on() directly
// — see the note on CMD_TOGGLE_LED below for the bug this avoids.
//
// executeCommand() itself must only ever be called from loop() (directly,
// or via ble_service.h's command queue drain) — never from a BLE GATT
// callback. Several cases here touch I2C (setStateMotor, camera capture)
// and none of that is safe to run concurrently with the sensor sweep.
#pragma once

// Forward declarations for symbols defined later in the same translation
// unit (MySat_main.ino, after all #include lines) — same pattern already
// used by console.h (`extern String useWiFi;` etc.) and by MySat_main.ino
// itself for its own functions.
extern String ssid;
extern String password;
extern String useWiFi;
bool loadWiFiConfig(String& ssid, String& password, String& useWiFi);

enum CommandId {
  CMD_UNKNOWN = 0,
  CMD_PING,
  CMD_GET_STATUS,
  CMD_DEPLOY_SOLAR,
  CMD_RETRACT_SOLAR,
  CMD_TOGGLE_SOLAR,   // console-internal (SolarMove) — not exposed over BLE
  CMD_BLINK_LED,
  CMD_LED_ON,
  CMD_LED_OFF,
  CMD_TOGGLE_LED,     // console-internal (TurnLed) — not exposed over BLE
  CMD_TAKE_PHOTO,
  CMD_ZERO_ATTITUDE,
  CMD_CALIBRATE_IMU,
  CMD_SAFE_MODE,
  CMD_NOMINAL_MODE,
  CMD_DESKTOP_MODE,
  CMD_WIFI_ON,
  CMD_WIFI_OFF,
  CMD_STOP_LOGGING,
  CMD_REBOOT
};

struct CommandResult {
  bool ok = false;
  const char* err = nullptr;  // one of the ERR_* codes in docs/COMMANDS.md §2, nullptr if ok
  String msg;                 // human-readable, <=64 chars
  String dataJson;            // optional extra JSON object body, e.g. {"id":7,"size":123}
};

struct CommandDef {
  const char* name;
  CommandId id;
  bool requiresAuth;
};

// The BLE-visible command set. CMD_TOGGLE_SOLAR and CMD_TOGGLE_LED are
// deliberately absent — they exist only for console.h's SolarMove/TurnLed
// branches to call directly by enum value.
const CommandDef COMMAND_TABLE[] = {
  { "PING", CMD_PING, false },
  { "GET_STATUS", CMD_GET_STATUS, false },
  { "DEPLOY_SOLAR", CMD_DEPLOY_SOLAR, true },
  { "RETRACT_SOLAR", CMD_RETRACT_SOLAR, true },
  { "BLINK_LED", CMD_BLINK_LED, true },
  { "LED_ON", CMD_LED_ON, true },
  { "LED_OFF", CMD_LED_OFF, true },
  { "TAKE_PHOTO", CMD_TAKE_PHOTO, true },
  { "ZERO_ATTITUDE", CMD_ZERO_ATTITUDE, true },
  { "CALIBRATE_IMU", CMD_CALIBRATE_IMU, true },
  { "SAFE_MODE", CMD_SAFE_MODE, true },
  { "NOMINAL_MODE", CMD_NOMINAL_MODE, true },
  { "DESKTOP_MODE", CMD_DESKTOP_MODE, true },
  { "WIFI_ON", CMD_WIFI_ON, true },
  { "WIFI_OFF", CMD_WIFI_OFF, true },
  { "STOP_LOGGING", CMD_STOP_LOGGING, true },
  { "REBOOT", CMD_REBOOT, true },
};
const size_t COMMAND_TABLE_LEN = sizeof(COMMAND_TABLE) / sizeof(COMMAND_TABLE[0]);

CommandId lookupCommand(const String& name, bool* requiresAuthOut) {
  for (size_t i = 0; i < COMMAND_TABLE_LEN; i++) {
    if (name.equalsIgnoreCase(COMMAND_TABLE[i].name)) {
      if (requiresAuthOut) *requiresAuthOut = COMMAND_TABLE[i].requiresAuth;
      return COMMAND_TABLE[i].id;
    }
  }
  return CMD_UNKNOWN;
}

CommandResult executeCommand(CommandId id) {
  CommandResult result;

  switch (id) {

    case CMD_PING: {
      result.ok = true;
      result.msg = "pong";
      char buf[32];
      snprintf(buf, sizeof(buf), "{\"uptime_ms\":%lu}", millis());
      result.dataJson = String(buf);
      break;
    }

    case CMD_GET_STATUS: {
      // The actual forced notify on all 4 telemetry characteristics is a
      // BLE-object concern handled by ble_service.h's drain loop; this
      // just confirms the request was valid.
      result.ok = true;
      result.msg = "Status refreshed";
      break;
    }

    case CMD_DEPLOY_SOLAR:
    case CMD_RETRACT_SOLAR:
    case CMD_TOGGLE_SOLAR: {
      bool target = (id == CMD_DEPLOY_SOLAR) ? true
                  : (id == CMD_RETRACT_SOLAR) ? false
                  : !stateMotor;
      if (stateMotor == target) {
        result.ok = true;
        result.msg = target ? "Panels already deployed" : "Panels already retracted";
        break;
      }
      // setStateMotor() enforces its own 2200 ms hardware rate limit
      // (control.h) matching the Nano's servo power-gating window. Every
      // caller in the original firmware discards its return value; here
      // we honour it so the app can distinguish "rejected" from "done".
      bool accepted = setStateMotor(target);
      if (!accepted) {
        result.ok = false;
        result.err = "ERR_RATE_LIMITED";
        result.msg = "Too soon since last panel move";
      } else {
        result.ok = true;
        result.msg = target ? "Deploying solar panels" : "Retracting solar panels";
        bleEmitEvent("PANEL_MOVED", target ? "deployed" : "retracted");
      }
      break;
    }

    case CMD_BLINK_LED: {
      startBlink(stateLight);
      result.ok = true;
      result.msg = "Blinking";
      break;
    }

    case CMD_LED_ON: {
      control_light(true);
      stateLight = true;
      result.ok = true;
      result.msg = "LED on";
      break;
    }

    case CMD_LED_OFF: {
      control_light(false);
      stateLight = false;
      result.ok = true;
      result.msg = "LED off";
      break;
    }

    case CMD_TOGGLE_LED: {
      // Replicates light_on() (server.h) minus the stray server.send() —
      // the original TurnLed console command called light_on(), the HTTP
      // handler, with no HTTP client in flight. See docs/COMMANDS.md §4.
      stateLight = !stateLight;
      control_light(stateLight);
      result.ok = true;
      result.msg = stateLight ? "LED on" : "LED off";
      break;
    }

    case CMD_TAKE_PHOTO: {
      if (!init_status.camera_) {
        result.err = "ERR_NO_CAMERA";
        result.msg = "Camera not available";
        break;
      }
      char timestamp[25];
      if (init_status.rtc_) {
        rtc_struct* t = get_rtc();
        snprintf(timestamp, sizeof(timestamp), "%04d-%02d-%02dT%02d:%02d:%02d",
                 t->year_, t->month_, t->day_, t->hour_, t->minute_, t->second_);
      } else {
        strcpy(timestamp, "unknown");
      }

      PhotoCaptureResult capture = capturePhotoToStorage(timestamp);
      if (!capture.ok) {
        result.err = (capture.error == PHOTO_ERR_CAPTURE) ? "ERR_CAPTURE_FAILED" : "ERR_FS_FULL";
        result.msg = (capture.error == PHOTO_ERR_CAPTURE) ? "Camera capture failed" : "Failed to save photo";
        break;
      }

      size_t size = capture.fb->len;
      esp_camera_fb_return(capture.fb);
      writeEventLog("PHOTO " + String(capture.id));
      bleEmitEvent("PHOTO_TAKEN", String(capture.id));

      result.ok = true;
      result.msg = "Photo captured";
      char buf[48];
      snprintf(buf, sizeof(buf), "{\"id\":%d,\"size\":%u}", capture.id, (unsigned)size);
      result.dataJson = String(buf);
      break;
    }

    case CMD_ZERO_ATTITUDE: {
      if (!init_status.mpu_) {
        result.err = "ERR_UNAVAILABLE";
        result.msg = "IMU not available";
        break;
      }
      // Matches the offsets calibrateMPU() sets at the end of a full
      // calibration (position_sensor.h) — not persisted across reboot,
      // same as the original. See docs/COMMANDS.md's ZERO_ATTITUDE note.
      offset_roll = mpu_data.roll;
      offset_pitch = mpu_data.pitch;
      offset_yaw = mpu_data.yaw;
      result.ok = true;
      result.msg = "Attitude zeroed";
      break;
    }

    case CMD_CALIBRATE_IMU: {
      if (!init_status.mpu_) {
        result.err = "ERR_UNAVAILABLE";
        result.msg = "IMU not available";
        break;
      }
      // Blocks ~7s (position_sensor.h) — ble_service.h sends an
      // immediate "starting" response before calling this, so the app
      // can show a blocking progress state rather than a silent stall.
      calibrateMPU();
      result.ok = true;
      result.msg = "Calibration complete";
      break;
    }

    case CMD_SAFE_MODE: {
      stopLogging();
      setStateMotor(false);
      setMode(MODE_SAFE);
      result.ok = true;
      result.msg = "Safe mode engaged";
      break;
    }

    case CMD_NOMINAL_MODE: {
      setMode(MODE_NOMINAL);
      result.ok = true;
      result.msg = "Nominal mode";
      break;
    }

    case CMD_DESKTOP_MODE: {
      setMode(MODE_DESKTOP);
      result.ok = true;
      result.msg = "Desktop mode";
      break;
    }

    case CMD_WIFI_ON: {
      if (!loadWiFiConfig(ssid, password, useWiFi)) {
        result.err = "ERR_NO_WIFI_CONFIG";
        result.msg = "No stored WiFi credentials — use SetWIFI over serial first";
        break;
      }
      useWiFi = "Yes";
      if (connectWiFiBounded(15000)) {
        initServer();
        result.ok = true;
        result.msg = "WiFi connected";
        bleEmitEvent("WIFI_UP", ssid);
      } else {
        result.err = "ERR_WIFI_TIMEOUT";
        result.msg = "WiFi connection timed out";
      }
      break;
    }

    case CMD_WIFI_OFF: {
      WiFi.disconnect(true);
      WiFi.mode(WIFI_OFF);
      useWiFi = "No";
      result.ok = true;
      result.msg = "WiFi disabled";
      bleEmitEvent("WIFI_DOWN", "");
      break;
    }

    case CMD_STOP_LOGGING: {
      stopLogging();
      result.ok = true;
      result.msg = "Logging stopped";
      break;
    }

    case CMD_REBOOT: {
      // The actual ESP.restart() happens in ble_service.h's drain loop,
      // after this response has been sent and had time to flush.
      result.ok = true;
      result.msg = "Rebooting";
      break;
    }

    default:
      result.err = "ERR_UNKNOWN_CMD";
      result.msg = "Unknown command";
      break;
  }

  return result;
}

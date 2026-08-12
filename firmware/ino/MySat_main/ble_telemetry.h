// SHAHAR-SAT 1 — BLE telemetry frame builders.
//
// Formats the current sensor state into the JSON frames defined in
// docs/BLE_PROTOCOL.md §6. These functions read directly from the same
// sensor globals the serial console and web server already read
// (mpu_data, ina_data, bme_data, rtc_data, init_status) and are only ever
// called from loop() — via ble_service.h's bleTelemetryTick(), which is
// the same task that writes those globals through get_sensors_data().
// There is a single writer and no cross-task access here, so no lock is
// needed. The cross-task boundary in this design is the *command* path
// (see command_bus.h / ble_service.h's FreeRTOS queue), not telemetry —
// see docs/ARCHITECTURE.md §2.1.
#pragma once

struct BleTelemetryBuffers {
  char attitude[BLE_MAX_FRAME_LEN];
  char power[BLE_MAX_FRAME_LEN];
  char environment[BLE_MAX_FRAME_LEN];
  char system[BLE_MAX_FRAME_LEN];
  size_t attitudeLen = 0, powerLen = 0, environmentLen = 0, systemLen = 0;
};

BleTelemetryBuffers bleFrames;

// snprintf returns the length it *would* have written; if that's >= the
// buffer size the frame was truncated. Per docs/BLE_PROTOCOL.md §5.1 we
// never send a truncated frame onto the air — drop it and log instead.
static void bleStoreFrameLen(size_t* destLen, int written) {
  if (written < 0 || written >= BLE_MAX_FRAME_LEN) {
    logDebug("[BLE] Frame exceeds " + String(BLE_MAX_FRAME_LEN) + "B budget, dropped");
    *destLen = 0;
    return;
  }
  *destLen = (size_t)written;
}

void bleBuildAttitudeFrame() {
  int written;
  // Offset-corrected, matching the web JSON convention (server.h), not
  // the CSV logger which ships raw. See docs/TELEMETRY.md §2.1.
  if (init_status.mpu_ && calibration.valid) {
    written = snprintf(bleFrames.attitude, BLE_MAX_FRAME_LEN,
      "{\"v\":%d,\"t\":%lu,\"r\":%.2f,\"p\":%.2f,\"y\":%.2f,\"cal\":1}",
      BLE_PROTOCOL_VERSION, millis(),
      mpu_data.roll - offset_roll, mpu_data.pitch - offset_pitch, mpu_data.yaw - offset_yaw);
  } else {
    // cal:0 rather than sending zeros — a genuine 0 must stay
    // distinguishable from "uncalibrated/absent". See TELEMETRY.md §2.1.
    written = snprintf(bleFrames.attitude, BLE_MAX_FRAME_LEN,
      "{\"v\":%d,\"t\":%lu,\"cal\":0}", BLE_PROTOCOL_VERSION, millis());
  }
  bleStoreFrameLen(&bleFrames.attitudeLen, written);
}

void bleBuildPowerFrame() {
  int written;
  if (init_status.ina_) {
    float solarPowerMw = ina_data.SolarPanelVoltage *
                          (ina_data.leftSolarPanelCurrent + ina_data.rightSolarPanelCurrent);
    written = snprintf(bleFrames.power, BLE_MAX_FRAME_LEN,
      "{\"v\":%d,\"t\":%lu,\"bv\":%.2f,\"bi\":%.1f,\"sv\":%.2f,\"sil\":%.1f,\"sir\":%.1f,\"sp\":%.1f}",
      BLE_PROTOCOL_VERSION, millis(),
      ina_data.batteryVoltage, ina_data.batteryCurrent, ina_data.SolarPanelVoltage,
      ina_data.leftSolarPanelCurrent, ina_data.rightSolarPanelCurrent, solarPowerMw);
  } else {
    written = snprintf(bleFrames.power, BLE_MAX_FRAME_LEN,
      "{\"v\":%d,\"t\":%lu}", BLE_PROTOCOL_VERSION, millis());
  }
  bleStoreFrameLen(&bleFrames.powerLen, written);
}

void bleBuildEnvironmentFrame() {
  int written;
  // BSEC needs ~5 minutes of warm-up (console.h); data_available stays
  // false until the first valid sample. See TELEMETRY.md §2.3.
  if (init_status.bme_ && bme_data.data_available) {
    written = snprintf(bleFrames.environment, BLE_MAX_FRAME_LEN,
      "{\"v\":%d,\"t\":%lu,\"tc\":%.2f,\"rh\":%.1f,\"hpa\":%.1f,\"gas\":%.1f,\"iaq\":%.1f,\"iaqa\":%d}",
      BLE_PROTOCOL_VERSION, millis(),
      bme_data.temperature, bme_data.humidity, bme_data.pressure,
      bme_data.gas_resistance, bme_data.iaq, bme_data.iaq_accuracy);
  } else {
    written = snprintf(bleFrames.environment, BLE_MAX_FRAME_LEN,
      "{\"v\":%d,\"t\":%lu}", BLE_PROTOCOL_VERSION, millis());
  }
  bleStoreFrameLen(&bleFrames.environmentLen, written);
}

void bleBuildSystemFrame() {
  char rtcBuf[24] = "unknown";
  if (init_status.rtc_) {
    snprintf(rtcBuf, sizeof(rtcBuf), "%04d-%02d-%02d %02d:%02d:%02d",
             rtc_data.year_, rtc_data.month_, rtc_data.day_,
             rtc_data.hour_, rtc_data.minute_, rtc_data.second_);
  }
  int written = snprintf(bleFrames.system, BLE_MAX_FRAME_LEN,
    "{\"v\":%d,\"t\":%lu,\"cs\":\"%s\",\"fw\":\"%s\",\"up\":%lu,\"mode\":\"%s\","
    "\"mot\":%d,\"led\":%d,\"log\":%d,\"heap\":%u,\"rtc\":\"%s\","
    "\"hw\":{\"bme\":%d,\"mpu\":%d,\"ads\":%d,\"ina\":%d,\"rtc\":%d,\"cam\":%d},\"fc\":%lu}",
    BLE_PROTOCOL_VERSION, millis(), callSign.c_str(), FIRMWARE_VERSION,
    millis() / 1000, spacecraftModeName(),
    stateMotor ? 1 : 0, stateLight ? 1 : 0, getLoggingState(),
    (unsigned)ESP.getFreeHeap(), rtcBuf,
    init_status.bme_ ? 1 : 0, init_status.mpu_ ? 1 : 0, init_status.ads_ ? 1 : 0,
    init_status.ina_ ? 1 : 0, init_status.rtc_ ? 1 : 0, init_status.camera_ ? 1 : 0,
    (unsigned long)frameCounter);
  bleStoreFrameLen(&bleFrames.systemLen, written);
}

// Compact signature of the fields that represent a genuine state change
// (mode, panels, LED, logging, sensor presence) — deliberately excludes
// the always-changing t/up/heap/fc/rtc fields, so "on change" detection
// in ble_service.h::bleTelemetryTick() doesn't fire every tick.
uint32_t bleSystemSignature() {
  uint32_t sig = 0;
  sig |= ((uint32_t)spacecraftMode & 0xF) << 0;
  sig |= (stateMotor ? 1u : 0u) << 4;
  sig |= (stateLight ? 1u : 0u) << 5;
  sig |= ((uint32_t)getLoggingState() & 0x3) << 6;
  sig |= (init_status.bme_ ? 1u : 0u) << 8;
  sig |= (init_status.mpu_ ? 1u : 0u) << 9;
  sig |= (init_status.ads_ ? 1u : 0u) << 10;
  sig |= (init_status.ina_ ? 1u : 0u) << 11;
  sig |= (init_status.rtc_ ? 1u : 0u) << 12;
  sig |= (init_status.camera_ ? 1u : 0u) << 13;
  return sig;
}

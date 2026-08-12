// SHAHAR-SAT 1 — BLE GATT server (NimBLE-Arduino).
//
// Owns advertising, the GATT service/characteristics, bonding/security,
// and the command queue that keeps the NimBLE host task from ever
// touching sensor globals or actuators directly. See
// docs/ARCHITECTURE.md §2.1 for the concurrency model and
// docs/BLE_PROTOCOL.md for the wire format this implements.
//
// Dependency: NimBLE-Arduino (h2zero/NimBLE-Arduino) 2.x — pin to 2.2.3 or
// later. 1.x has a different callback API (plain BLECharacteristic*, no
// NimBLEConnInfo&) and will not compile against this file. Not bundled
// in libraries.zip — see firmware/README.md for how it was added.
//
// Build-verified: compiles clean (zero warnings) via `arduino-cli compile
// --fqbn esp32:esp32:esp32cam:PartitionScheme=no_ota` against
// esp32:esp32@2.0.9 + NimBLE-Arduino 2.2.3. Not hardware-verified — see
// docs/TEST_PLAN.md §1 for what that still requires (real advertising,
// pairing, notification timing).
#pragma once

#include <NimBLEDevice.h>
#include <ArduinoJson.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

NimBLEServer* bleServer = nullptr;
NimBLECharacteristic* bleCharAttitude = nullptr;
NimBLECharacteristic* bleCharPower = nullptr;
NimBLECharacteristic* bleCharEnvironment = nullptr;
NimBLECharacteristic* bleCharSystem = nullptr;
NimBLECharacteristic* bleCharCommand = nullptr;
NimBLECharacteristic* bleCharResponse = nullptr;
NimBLECharacteristic* bleCharEvent = nullptr;

bool bleClientConnected = false;

struct BleCommandMsg {
  CommandId id;
  char cmdName[24];
  uint16_t seq;
};

QueueHandle_t bleCommandQueue = nullptr;

// Definition of the function spacecraft_mode.h forward-declares, and
// command_bus.h calls directly by name (both are included before this
// file, relying on that earlier forward declaration).
void bleEmitEvent(const String& type, const String& detail) {
  if (!bleCharEvent || !bleClientConnected) return;
  char buf[160];
  int len = snprintf(buf, sizeof(buf), "{\"t\":%lu,\"ev\":\"%s\",\"detail\":\"%s\"}",
                      millis(), type.c_str(), detail.c_str());
  if (len > 0 && len < (int)sizeof(buf)) {
    bleCharEvent->setValue((uint8_t*)buf, len);
    bleCharEvent->notify();
  }
}

static void bleSendResponse(uint16_t seq, const char* cmdName, const CommandResult& r) {
  if (!bleCharResponse) return;
  char buf[BLE_MAX_FRAME_LEN];
  int len;
  if (r.dataJson.length() > 0) {
    len = snprintf(buf, sizeof(buf), "{\"seq\":%u,\"cmd\":\"%s\",\"ok\":%s,\"msg\":\"%s\",\"data\":%s}",
                    seq, cmdName, r.ok ? "true" : "false", r.msg.c_str(), r.dataJson.c_str());
  } else if (!r.ok && r.err) {
    len = snprintf(buf, sizeof(buf), "{\"seq\":%u,\"cmd\":\"%s\",\"ok\":false,\"err\":\"%s\",\"msg\":\"%s\"}",
                    seq, cmdName, r.err, r.msg.c_str());
  } else {
    len = snprintf(buf, sizeof(buf), "{\"seq\":%u,\"cmd\":\"%s\",\"ok\":%s,\"msg\":\"%s\"}",
                    seq, cmdName, r.ok ? "true" : "false", r.msg.c_str());
  }
  if (len > 0 && len < (int)sizeof(buf)) {
    bleCharResponse->setValue((uint8_t*)buf, len);
    bleCharResponse->notify();
  }
}

// Used for protocol-level rejections that never reach executeCommand()
// (bad JSON, unknown command, duplicate seq, missing auth, busy queue).
static void bleSendRawResponse(uint16_t seq, const char* cmdName, bool ok, const char* err, const char* msg) {
  if (!bleCharResponse) return;
  char buf[BLE_MAX_FRAME_LEN];
  int len = err
    ? snprintf(buf, sizeof(buf), "{\"seq\":%u,\"cmd\":\"%s\",\"ok\":false,\"err\":\"%s\",\"msg\":\"%s\"}",
               seq, cmdName, err, msg ? msg : "")
    : snprintf(buf, sizeof(buf), "{\"seq\":%u,\"cmd\":\"%s\",\"ok\":%s,\"msg\":\"%s\"}",
               seq, cmdName, ok ? "true" : "false", msg ? msg : "");
  if (len > 0 && len < (int)sizeof(buf)) {
    bleCharResponse->setValue((uint8_t*)buf, len);
    bleCharResponse->notify();
  }
}

class BleServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    bleClientConnected = true;
    logDebug("[BLE] Client connected");
  }
  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    bleClientConnected = false;
    logDebug("[BLE] Client disconnected, reason " + String(reason));
    NimBLEDevice::startAdvertising();
  }
  void onAuthenticationComplete(NimBLEConnInfo& connInfo) override {
    logDebug(connInfo.isEncrypted() ? "[BLE] Link authenticated" : "[BLE] Authentication failed");
  }
};

// Replay protection (docs/BLE_PROTOCOL.md §7.3) is per-connection: reset
// on every new connection, one accepted seq remembered at a time.
class BleCommandCallbacks : public NimBLECharacteristicCallbacks {
  uint16_t lastSeq = 0;
  bool haveLastSeq = false;

public:
  void onWrite(NimBLECharacteristic* chr, NimBLEConnInfo& connInfo) override {
    std::string value = chr->getValue();
    if (value.length() == 0 || value.length() > BLE_MAX_CMD_LEN) {
      bleSendRawResponse(0, "", false, "ERR_BAD_JSON", "Payload empty or too large");
      return;
    }

    DynamicJsonDocument doc(BLE_MAX_CMD_LEN + 64);
    if (deserializeJson(doc, value.c_str(), value.length())) {
      bleSendRawResponse(0, "", false, "ERR_BAD_JSON", "Malformed JSON");
      return;
    }

    if (!doc.containsKey("cmd") || !doc.containsKey("seq")) {
      bleSendRawResponse(0, "", false, "ERR_MISSING_SEQ", "Missing cmd or seq");
      return;
    }

    String cmdName = doc["cmd"].as<String>();
    uint16_t seq = doc["seq"].as<uint16_t>();

    if (haveLastSeq && seq == lastSeq) {
      bleSendRawResponse(seq, cmdName.c_str(), false, "ERR_DUPLICATE_SEQ", "Replayed sequence number");
      return;
    }

    bool requiresAuth = true;
    CommandId id = lookupCommand(cmdName, &requiresAuth);
    if (id == CMD_UNKNOWN) {
      bleSendRawResponse(seq, cmdName.c_str(), false, "ERR_UNKNOWN_CMD", "Unrecognized command");
      return;
    }

    if (requiresAuth && !connInfo.isEncrypted()) {
      bleSendRawResponse(seq, cmdName.c_str(), false, "ERR_NOT_AUTHENTICATED",
                          "Command requires a bonded, encrypted link");
      return;
    }

    lastSeq = seq;
    haveLastSeq = true;

    BleCommandMsg msg;
    msg.id = id;
    msg.seq = seq;
    strncpy(msg.cmdName, cmdName.c_str(), sizeof(msg.cmdName) - 1);
    msg.cmdName[sizeof(msg.cmdName) - 1] = '\0';

    if (xQueueSend(bleCommandQueue, &msg, 0) != pdTRUE) {
      bleSendRawResponse(seq, cmdName.c_str(), false, "ERR_BUSY", "Command queue full");
    }
  }
};

// Drains commands enqueued by BleCommandCallbacks::onWrite(). Runs on the
// Arduino task (called from loop()) — this is the only place command_bus.h
// primitives are ever invoked from the BLE path, so I2C/actuator access
// never happens concurrently with the sensor sweep.
void bleDrainCommandQueue() {
  BleCommandMsg msg;
  while (xQueueReceive(bleCommandQueue, &msg, 0) == pdTRUE) {

    if (msg.id == CMD_GET_STATUS) {
      bleBuildAttitudeFrame();
      bleBuildPowerFrame();
      bleBuildEnvironmentFrame();
      bleBuildSystemFrame();
      if (bleClientConnected) {
        if (bleFrames.attitudeLen) { bleCharAttitude->setValue((uint8_t*)bleFrames.attitude, bleFrames.attitudeLen); bleCharAttitude->notify(); }
        if (bleFrames.powerLen) { bleCharPower->setValue((uint8_t*)bleFrames.power, bleFrames.powerLen); bleCharPower->notify(); }
        if (bleFrames.environmentLen) { bleCharEnvironment->setValue((uint8_t*)bleFrames.environment, bleFrames.environmentLen); bleCharEnvironment->notify(); }
        if (bleFrames.systemLen) { bleCharSystem->setValue((uint8_t*)bleFrames.system, bleFrames.systemLen); bleCharSystem->notify(); }
      }
      CommandResult r = executeCommand(msg.id);
      bleSendResponse(msg.seq, msg.cmdName, r);

    } else if (msg.id == CMD_CALIBRATE_IMU) {
      // Calibration blocks ~7s (position_sensor.h) — warn before it
      // starts so the app can show a blocking progress state instead of
      // a silent stall. See docs/COMMANDS.md.
      bleSendRawResponse(msg.seq, msg.cmdName, true, nullptr, "Calibrating, ~7s");
      CommandResult r = executeCommand(msg.id);
      bleSendResponse(msg.seq, msg.cmdName, r);

    } else if (msg.id == CMD_REBOOT) {
      CommandResult r = executeCommand(msg.id);
      bleSendResponse(msg.seq, msg.cmdName, r);
      delay(150);  // let the notification flush before the link drops
      ESP.restart();

    } else {
      CommandResult r = executeCommand(msg.id);
      bleSendResponse(msg.seq, msg.cmdName, r);
    }
  }
}

static void bleNotifyChar(NimBLECharacteristic* chr, const char* buf, size_t len) {
  if (!chr || !bleClientConnected || len == 0) return;
  chr->setValue((uint8_t*)buf, len);
  chr->notify();
}

uint32_t bleLastSystemSignature = 0xFFFFFFFF;
unsigned long bleLastAttitudeNotify = 0;
unsigned long bleLastPowerNotify = 0;
unsigned long bleLastEnvironmentNotify = 0;
unsigned long bleLastSystemNotify = 0;

// Called every loop() iteration. Each characteristic is rate-limited
// independently per docs/BLE_PROTOCOL.md §6; System additionally fires on
// any change to the fields bleSystemSignature() tracks, not just on the
// 5 s floor.
void bleTelemetryTick() {
  if (!bleClientConnected) return;
  unsigned long now = millis();

  if (now - bleLastAttitudeNotify >= BLE_ATTITUDE_INTERVAL_MS) {
    bleBuildAttitudeFrame();
    bleNotifyChar(bleCharAttitude, bleFrames.attitude, bleFrames.attitudeLen);
    bleLastAttitudeNotify = now;
  }

  if (now - bleLastPowerNotify >= BLE_POWER_INTERVAL_MS) {
    bleBuildPowerFrame();
    bleNotifyChar(bleCharPower, bleFrames.power, bleFrames.powerLen);
    bleLastPowerNotify = now;
  }

  if (now - bleLastEnvironmentNotify >= BLE_ENVIRONMENT_INTERVAL_MS) {
    bleBuildEnvironmentFrame();
    bleNotifyChar(bleCharEnvironment, bleFrames.environment, bleFrames.environmentLen);
    bleLastEnvironmentNotify = now;
  }

  uint32_t sig = bleSystemSignature();
  bool changed = (sig != bleLastSystemSignature);
  bool keepAliveDue = (now - bleLastSystemNotify >= BLE_SYSTEM_MIN_INTERVAL_MS);
  if (changed || keepAliveDue) {
    bleBuildSystemFrame();
    bleNotifyChar(bleCharSystem, bleFrames.system, bleFrames.systemLen);
    bleLastSystemSignature = sig;
    bleLastSystemNotify = now;
  }
}

void bleInit() {
  bleCommandQueue = xQueueCreate(BLE_COMMAND_QUEUE_LEN, sizeof(BleCommandMsg));

  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setMTU(BLE_PREFERRED_MTU);
  // Bonding + MITM + Secure Connections, static passkey — see
  // docs/BLE_PROTOCOL.md §8 for why (commands need pairing, telemetry
  // doesn't).
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEDevice::setSecurityPasskey(BLE_STATIC_PASSKEY);

  bleServer = NimBLEDevice::createServer();
  bleServer->setCallbacks(new BleServerCallbacks());

  NimBLEService* svc = bleServer->createService(BLE_SERVICE_UUID);

  bleCharAttitude = svc->createCharacteristic(BLE_CHAR_ATTITUDE_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  bleCharPower = svc->createCharacteristic(BLE_CHAR_POWER_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  bleCharEnvironment = svc->createCharacteristic(BLE_CHAR_ENVIRONMENT_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  bleCharSystem = svc->createCharacteristic(BLE_CHAR_SYSTEM_UUID,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  // Only the command channel requires the bonded, encrypted link.
  bleCharCommand = svc->createCharacteristic(BLE_CHAR_COMMAND_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN);
  bleCharResponse = svc->createCharacteristic(BLE_CHAR_RESPONSE_UUID, NIMBLE_PROPERTY::NOTIFY);
  bleCharEvent = svc->createCharacteristic(BLE_CHAR_EVENT_UUID, NIMBLE_PROPERTY::NOTIFY);

  bleCharAttitude->setValue("{}");
  bleCharPower->setValue("{}");
  bleCharEnvironment->setValue("{}");
  bleCharSystem->setValue("{}");

  bleCharCommand->setCallbacks(new BleCommandCallbacks());

  svc->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  // Name in the advertisement, service UUID in the scan response — a
  // 128-bit UUID plus the name plus flags exceeds the 31-byte
  // advertisement limit. See docs/BLE_PROTOCOL.md §3.
  adv->setName(BLE_DEVICE_NAME);
  adv->addServiceUUID(BLE_SERVICE_UUID);
  adv->enableScanResponse(true);
  adv->setMinInterval(160);  // 160 * 0.625ms = 100 ms
  adv->setMaxInterval(400);  // 400 * 0.625ms = 250 ms
  NimBLEDevice::startAdvertising();

  logDebug("[BLE] Advertising as " BLE_DEVICE_NAME);
}

// Called once per loop() iteration from MySat_main.ino.
void bleLoop() {
  bleDrainCommandQueue();
  bleTelemetryTick();
}

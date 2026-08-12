# SHAHAR-SAT 1 — Architecture

How the existing MySat firmware is built, where the BLE layer plugs in, and why it is
shaped the way it is.

Everything in the "Existing firmware" sections below was read out of the source at
commit `a92925a` (MySatKit-Firmware v1.4) and is cited by `file:line`. Nothing here is
assumed. Where the hardware cannot be determined from source, it says so and the item is
listed in [HARDWARE_NOTES.md](HARDWARE_NOTES.md).

---

## 1. Existing firmware — the shape of it

### 1.1 Two microcontrollers

| Board | Sketch | Role |
|---|---|---|
| ESP32-CAM (AI Thinker) | `firmware/ino/MySat_main/` | Main OBC — sensors, Wi-Fi, web GUI, camera, logging, console |
| Arduino Nano (ATmega328P) | `firmware/ino/MySat_Nano_ATmega328p/` | Co-processor — solar panel servo, HC-12 power |

They talk over **I²C only, one direction**. The ESP32 is master; the Nano is a slave at
address `0x08` and registers `Wire.onReceive` but **never `Wire.onRequest`**
(`MySat_Nano_ATmega328p.ino:64-65`). A single command byte is written:

| Byte | Opcode | Effect |
|---|---|---|
| `0` | `MOTOR_OPEN` | Deploy panels |
| `1` | `MOTOR_CLOSE` | Retract panels |
| `2` | `RF_TURN` | **Empty case — does nothing** (`MySat_Nano_ATmega328p.ino:99-101`) |
| `3` | `RF_SET` | **Empty case — does nothing** |

The consequence that matters: **the ESP32 can never ask the Nano what the panels are
actually doing.** Deployment state is open-loop.

### 1.2 The main loop

`MySat_main.ino:72-91` — single-threaded, cooperative, no RTOS:

```cpp
void loop() {
  if (useWiFi.equalsIgnoreCase("Yes")) {
    server.handleClient();
  }
  handleCommands();
  checkSystemState();
  updateBlinkStarLed();

  unsigned long now = millis();
  if (now - lastSensorUpdate >= SENSOR_INTERVAL) {   // 500 ms
     lastSensorUpdate = now;
     finalizeSystemStartup();
     pointer_of_sensors* data = get_sensors_data();
     outputData(data);
  }

  updateSystemHeartbeat();
  saveBsecState();
}
```

A repo-wide search for `xTaskCreate`, `SemaphoreHandle`, `portMUX`, `vTaskDelay` and
`mutex` returns **zero hits**. There is no application task, no queue, no lock anywhere.
The firmware is thread-safe today purely by construction — everything runs on the Arduino
`loopTask`.

**This is the single most important fact for the BLE design.** BLE callbacks do not run on
`loopTask`.

### 1.3 Timing budget

| Activity | Interval | Where |
|---|---|---|
| Sensor sweep | 500 ms | `MySat_main.ino:69-70` |
| Serial telemetry print | 1500 ms | `console.h:8` |
| Web GUI poll → `/get_data` | 2000 ms | `server.h:1101` |
| Signal LED / system state | 5000 ms | `control.h:93` |
| Heartbeat → NVS | 5000 ms | `event_log.h:6` |
| BSEC state → NVS | 1 h | `environment_sensor.h:10` |

**`get_mpu_data()` blocks for ~100 ms of every 500 ms sweep** — 20% of wall-clock. It takes
20 samples paced by `delayMicroseconds` at 200 Hz (`position_sensor.h:93-94, 146-150`).
Nothing else runs during that window, including `server.handleClient()`.

### 1.4 Module layout

Every module is a **header-only `.h` with definitions inline**, guarded by `#pragma once`,
included in dependency order. There are no `.cpp` files. New modules must match this — it
is the project's convention, not an accident.

```
MySat_main.ino
├── server.h ──────── WiFi.h, WebServer.h, base64, 34 KB HTML literal
│   ├── sensors_data.h ─── the aggregator
│   │   ├── environment_sensor.h ── BME680 via Bosch BSEC
│   │   ├── position_sensor.h ───── MPU9250/6500, hand-written I²C
│   │   ├── ADC.h ───────────────── ADS1015 sun sensors
│   │   ├── RTC.h ───────────────── DS3231
│   │   ├── camera.h ────────────── OV2640 + LittleFS photo store
│   │   └── power_measure.h ─────── INA3221
│   ├── control.h ───── motor (I²C→Nano), STAR LED, NeoPixel
│   ├── event_log.h ─── /event_log.txt, 100-line ring
│   └── data_logger.h ─ mission CSV
└── console.h ──────── serial command chain + telemetry formatting
```

### 1.5 Sensor data model

`sensors_data.h:31-46` is **not** a flat telemetry struct. It is a struct of pointers into
five per-driver globals:

```cpp
struct pointer_of_sensors{
  bme_struct * bme_;
  mpu * mpu_;
  ads_struct * ads_;
  ina_struct * ina_;
  rtc_struct * rtc_;
} pointers;
```

Each getter returns the address of a fixed file-scope global (`bme_data`, `mpu_data`,
`ads_data`, `ina_data`, `rtc_data`), so **the last-read value of every sensor is always
resident in RAM**. Nothing needs to be re-polled to read it.

`get_sensors_data()` (`sensors_data.h:57-88`) re-polls the whole I²C bus and costs ~100 ms.
The existing HTTP handler calls it on *every request* (`server.h:1208-1215`) — that pattern
must not be copied.

`init_status` (`sensors_data.h:39-46`) holds per-sensor presence flags, **set once at boot
and never re-evaluated**. A sensor that dies later still reports present. Worse,
`initINA()` unconditionally returns `true` without probing the chip
(`power_measure.h:18-22`), so the INA3221 flag is not a real presence check.

### 1.6 Command handling — there isn't a layer

`handleCommands()` (`console.h:131`) reads bytes from `Serial`, accumulates into a
function-static `String`, and dispatches through a flat `else if` chain of
`equalsIgnoreCase`. 21 commands. No table, no handler struct, no return values, no
argument parsing.

```cpp
void handleCommands() {
  static String inputBuffer = "";
  while (Serial.available() > 0) {
    char inChar = Serial.read();
    ...
      if (inputBuffer.equalsIgnoreCase("ChangeTime")) {
```

Input source, parsing, dispatch and output are fused into one function. **There is no
`execCommand()` to call from another transport.**

Three specific hazards:

1. **`TurnLed` calls the HTTP handler.** `console.h:154` calls `light_on()`
   (`server.h:1424`), which ends with `server.send(200, "text/plain", "OK")` — executed
   with no HTTP client in flight. Reusing `light_on()` from BLE would inherit that.
2. **Six commands block forever on Serial.** `ChangeTime`, `SetWIFI`, `SetCallSign`,
   `StartLogging`, `SwitchTelemetry`→plotter and `SelectPlotterMode` all spin on
   `while (!Serial.available()) {}` with no timeout (`console.h:37,68`, `RTC.h:46-71`,
   `data_logger.h:309`). Triggering one from BLE hangs the satellite until someone types
   into the USB port.
3. **Every recognized command costs 2 s.** `reactToCommand()` does `delay(2000)`
   (`console.h:83`); `pauseToRead()` adds 3 s more (`RTC.h:29-37`).

The web endpoints bypass the console entirely and call `control.h` directly — so the two
existing transports already duplicate intent, and the console is the one that borrowed
from the web rather than the reverse.

### 1.7 The 433 MHz radio

**There is no radio firmware.** No TX, no RX, no framing, no parsing — searches for
`433`, `HC-?12`, `Serial1`, `Serial2`, `SoftwareSerial`, `LoRa`, `packet` find only pin
definitions and comments.

The Nano powers the HC-12 on at boot and leaves it on (`MySat_Nano_ATmega328p.ino:61-62`,
commented *"allows HC-12 to always work by default"*). `RF_SET_PIN` is never even
`pinMode`d. Both MCUs use `Serial` at 115200.

So **the HC-12 is a transparent UART bridge on the same UART0 as the USB console.**
Whatever arrives over the air is fed into `handleCommands()`; the downlink is the
human-readable block printed by `outputDataText()`. The "radio protocol" is
newline-terminated ASCII with no checksum, addressing or sequence numbers.

For SHAHAR-SAT this means: the radio needs no preservation work beyond **not touching
`Serial` and not adding chatter to it**. BLE does not compete with it — HC-12 is on the
Nano's UART, while BLE shares the ESP32's 2.4 GHz radio with Wi-Fi only.

### 1.8 Storage

**LittleFS on internal flash, exclusively.** No SD (`SD.h`/`SD_MMC.h` appear nowhere), so
the classic ESP32-CAM SD-vs-camera pin conflict is moot. `LittleFS.begin(true)`
(`MySat_main.ino:46`) — the `true` means *format on mount failure*, so a partition change
silently wipes the filesystem.

Undocumented in the original source but worth recording: I²C is on **SDA=15, SCL=13**
(`MySat_main.ino:39-40`), STAR LED on **GPIO14** (`control.h:17`), NeoPixel on **GPIO2**
(`control.h:18`). Those are exactly the SD_MMC lines — SD is permanently unavailable on
this build, and GPIO2/15 are strapping pins now driven at boot.

Filesystem budget, worst case:

| Consumer | Bytes |
|---|---:|
| `data/` web assets (10 files) | 417,426 |
| 10 photos @ ~60 KB (`camera.h:14`) | ~600,000 |
| Mission CSV, 3600 rows (`data_logger.h:5`) | ~430,000 |
| Index/state/config/calibration files | ~15,000 |
| **Total** | **≈1.46 MB** |

The web GUI is a **hybrid**: `/` is a 34 KB string literal compiled into the binary
(`server.h:16-1111`), while `bootstrap.css`, `bootstrap.js` and the PNGs are uploaded to
LittleFS by the Arduino "ESP32 Sketch Data Upload" tool and served by the catch-all
handler (`server.h:1452-1470`).

`firmware/ino/MySat_main/index.html` is **dead code** — not in `data/`, never uploaded,
never referenced, still loading Bootstrap from a CDN. It is a pre-v1.2 artifact. Do not
treat it as a spec; notably it renders a magnetometer block for fields the firmware never
emits.

### 1.9 Resource pressure

The image already links Wi-Fi + LwIP + WebServer + esp32-camera + the BSEC precompiled
blob (185 KB `.a`) + ArduinoJson + NeoPixel + five I²C drivers.

**No `partitions.csv` exists, and the README names no partition scheme** — searches for
`partition`, `Huge APP`, `Minimal SPIFFS` return nothing repo-wide. The README defers all
setup to an external Google Doc.

Two latent issues found while sizing this:

- **`camera_config_t config;` is an uninitialized stack struct** (`camera.h:27`). Only 20
  fields are assigned; `fb_location`, `grab_mode` and `sccb_i2c_port` are indeterminate
  garbage. Whether the framebuffer lands in PSRAM is currently down to whatever was on the
  stack. RAM headroom cannot be reasoned about until this is fixed.
- **`handleGetPhoto()` amplifies ~3.4×** (`server.h:1318-1329`): framebuffer + base64
  `String` + `DynamicJsonDocument` + response `String` are all live simultaneously —
  roughly 250-300 KB of heap for one 60 KB photo. It also contains an unbounded
  `while (!fb)` retry (`server.h:1289-1294`) that hangs `loop()` forever on persistent
  capture failure.

Only two heap probes exist, both debug-gated (`camera.h:154`, `server.h:1210`). There is
no `getMinFreeHeap()`, no low-heap guard, no watchdog, no brownout handling anywhere.

---

## 2. Where BLE plugs in

### 2.1 The concurrency model

BLE callbacks run on the NimBLE host task — a different task, often a different core, from
`loopTask`. Since the existing code has **no locks at all**, the design avoids cross-task
access rather than retrofitting mutexes into working sensor code:

```
loop()  [Arduino task, core 1]              NimBLE host task
   │                                              │
   ├─ get_sensors_data()      (unchanged)         │
   ├─ bleSnapshotUpdate()  ──portMUX──►  [snapshot buffer]  ──► GATT read callback
   ├─ bleNotifyTick()      ──────────────────────►  notify()
   │                                              │
   └─ bleDrainCommandQueue() ◄──FreeRTOS queue────┘  write callback enqueues only
```

**Telemetry — `loop()` is the sole writer.** At the end of each 500 ms sweep it formats
frames into a BLE-owned buffer under a `portMUX_TYPE` spinlock and calls `notify()` from
`loop()` itself. GATT read callbacks only `memcpy` *out* of that buffer under the same
lock. No BLE code ever touches `pointers`, `mpu_data`, `ina_data` or any sensor global.

**Commands — the write callback only enqueues.** It validates the payload, pushes a
fixed-size struct to a FreeRTOS queue and returns immediately. `loop()` drains and
executes on the Arduino task, exactly where the existing code already runs. FreeRTOS
queues are thread-safe by construction.

What this buys:

| Hazard found in exploration | How the model avoids it |
|---|---|
| Torn reads of `mpu_data` / `ina_data` (written field-by-field) | BLE never reads them; it reads a snapshot copied under a lock |
| Concurrent `Wire` transactions corrupting I²C | All I²C stays on `loopTask` |
| Non-reentrant `json_string` / `csv_line` / `inputBuffer` | BLE uses its own buffers |
| `esp_camera_fb_get()` / LittleFS races | Commands execute on `loopTask` |
| 100 ms MPU stall inside a GATT callback | GATT callbacks never poll sensors |

**No mutexes are added to existing modules, and no existing file's behaviour changes.**

### 2.2 New modules

| File | Responsibility |
|---|---|
| `firmware/ino/MySat_main/ble_config.h` | UUIDs, device name, passkey, rates, feature flags |
| `firmware/ino/MySat_main/command_bus.h` | Transport-neutral `executeCommand()` |
| `firmware/ino/MySat_main/ble_telemetry.h` | Snapshot buffer, frame builders, notify scheduler |
| `firmware/ino/MySat_main/ble_service.h` | NimBLE GATT server, advertising, security, queue |
| `firmware/ino/MySat_main/spacecraft_mode.h` | Mode enum, `setMode()`, DESKTOP policy |
| `firmware/ino/MySat_main/partitions.csv` | Custom no-OTA layout |

All header-only with `#pragma once`, matching the existing convention.

### 2.3 Files that change, and how much

| File | Change | Size |
|---|---|---|
| `MySat_main.ino` | 2 includes, `bleInit()` in `setup()`, 3 calls in `loop()` | ~8 lines |
| `console.h` | 5 command branches routed through `command_bus.h` | ~20 lines |
| `server.h` | `capturePhotoToStorage()` factored out of `handleGetPhoto()` | ~30 lines |
| `camera.h` | Initialize `camera_config_t` explicitly (bug fix) | ~3 lines |

**`server.h` and `console.h` are not rewritten.** `sensors_data.h`, `position_sensor.h`,
`environment_sensor.h`, `power_measure.h`, `ADC.h`, `RTC.h`, `control.h`, `event_log.h`
and `data_logger.h` are **not modified at all**.

### 2.4 Avoiding duplication in the command path

`command_bus.h` provides one implementation per action, calling only the primitives that
are safe to reuse — the ones that neither touch `Serial` nor `server`:

```cpp
enum CommandId { CMD_PING, CMD_DEPLOY_SOLAR, CMD_RETRACT_SOLAR,
                 CMD_BLINK_LED, CMD_LED_ON, CMD_LED_OFF, ... };

CommandResult executeCommand(CommandId id, const char* args, Print& out);
```

Backed by `setStateMotor()`, `control_light()`, `startBlink()`, `stopLogging()`,
`getLoggingState()` and `writeEventLog()`.

Then the shared console branches — `SolarDeploy`, `SolarRetract`, `SolarMove`, `BlinkLed`,
`TurnLed` — are rewritten to call `executeCommand()`. One implementation, two transports.
This also removes the `TurnLed` → `light_on()` → `server.send()` bug as a side effect.

`setStateMotor()` returns a `bool` meaning *"command accepted"* (it rejects changes within
2200 ms, `control.h:176-187`). Every current caller discards it. The command bus honours
it, so the app can show *"rejected — too soon"* instead of silently doing nothing.

**The 17 Serial-interactive commands are deliberately left alone.** They are rejected over
BLE with `ERR_SERIAL_ONLY` rather than being made to hang on a spin loop. Making them
transport-neutral would mean rewriting their parameter acquisition — out of scope, and a
regression risk to a console that works today.

### 2.5 Why the existing telemetry serializers are not reused

Three independent serializers already exist and none is suitable:

| Serializer | Why not |
|---|---|
| `outputDataText()` / `outputDataPlotter()` (`console.h:280,454`) | Human-readable, `Serial`-hardcoded |
| `generateSensorsDataJson()` (`server.h:1122`) | Returns a pointer to a **global** `String` reused across calls; not reentrant, and one 25-field frame at 10 Hz wastes bandwidth |
| `writeDataRow()` (`data_logger.h:212`) | CSV into a function-static buffer; ships raw attitude without offsets |

BLE needs frames split by update rate, so it builds its own into its own buffers. This is
addition, not duplication — the existing three keep serving their existing transports
unchanged.

### 2.6 Spacecraft modes

`spacecraft_mode.h` defines `BOOT`, `NOMINAL`, `SAFE`, `LOW_POWER`, `PAYLOAD`, `DESKTOP`
with a single global and `setMode()`. Only `BOOT`, `NOMINAL` and `DESKTOP` carry behaviour
in v1; the rest are declared so the structure is ready.

**DESKTOP**: BLE on, telemetry on, **Wi-Fi off at boot and started on demand**, radio
untouched, tuned for long unattended runs.

Wi-Fi on demand needs a bounded `connectWiFiBounded(timeoutMs)` — the existing
`tryConnectWiFi()` is a `while (true)` that recurses into `setWiFi()` and blocks on Serial
forever (`MySat_main.ino:188-214`). The original stays for the serial path untouched.

### 2.7 Camera

`TAKE_PHOTO` captures and calls the existing `savePhoto()` (`camera.h:144`) to LittleFS;
the response characteristic returns `{id, size, ok}`. Retrieval uses `WIFI_ON` plus the
existing web interface.

Reasoning: a 60 KB XGA JPEG at realistic BLE throughput takes 12+ seconds, and the
existing capture path already peaks at ~250-300 KB of live heap. Pushing that through BLE
while Wi-Fi is up is the most likely way to destabilize the device. A QQVGA thumbnail over
BLE is deferred until throughput is measured on real hardware.

This requires factoring `capturePhotoToStorage()` out of `handleGetPhoto()` with a
**bounded** retry, replacing the unbounded `while (!fb)` loop for the new path.

---

## 3. Coexistence and power

The ESP32 has **one 2.4 GHz radio** shared between Wi-Fi and BLE by a time-division
scheduler. Both active means both degrade — longer scan/connection latency and occasional
missed notification intervals.

DESKTOP mode keeps Wi-Fi off by default, so in normal desk use BLE has the radio to
itself. Enabling Wi-Fi for photo retrieval accepts the contention for that window.

Known costs, stated rather than hidden:

- **Flash is the binding constraint.** NimBLE adds ~100-150 KB; Bluedroid would add
  500-700 KB. Hence NimBLE, and hence a custom partition table.
- **The ~100 ms MPU busy-wait every 500 ms will cause visible notification jitter.** BLE
  cannot transmit during it. Attitude at 10 Hz will not be perfectly even. Fixing it
  properly means restructuring `get_mpu_data()`, which is deferred — the jitter is
  absorbed by app-side SLERP smoothing instead.
- **Peak RAM during a photo with Wi-Fi and BLE all live is the highest-risk moment** in
  the whole system.
- Advertising at 100-250 ms is a deliberate trade of idle current for discovery latency.
  The kit sits on a powered desk; discovery speed matters more than microamps.

---

## 4. Repository structure

```
firmware/     ino/, libraries.zip, README.md, license.md   (upstream MySat, moved intact)
android/      Android application
docs/         ARCHITECTURE.md  BLE_PROTOCOL.md  COMMANDS.md
              TELEMETRY.md  TEST_PLAN.md  HARDWARE_NOTES.md
```

Moved with `git mv`, so upstream history is preserved and future diffs against
MySatKit-Firmware stay readable.

One inconsistency inherited from upstream, flagged for the repo owner:
`firmware/license.md` is the full **GPL v3** text, while `MySat_main.ino:11` declares
`license: Open Source (MIT)`. That difference is material if this project is
redistributed.

---

## 5. Related documents

- [BLE_PROTOCOL.md](BLE_PROTOCOL.md) — UUIDs, GATT layout, frame formats, security
- [COMMANDS.md](COMMANDS.md) — command set, arguments, errors, safety
- [TELEMETRY.md](TELEMETRY.md) — every field, unit, source, and what does not exist
- [HARDWARE_NOTES.md](HARDWARE_NOTES.md) — open questions needing the physical kit
- [TEST_PLAN.md](TEST_PLAN.md) — verification procedures

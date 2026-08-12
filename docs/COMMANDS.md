# SHAHAR-SAT 1 — Commands

The BLE command set, how it maps onto the existing MySat firmware, and which original
commands deliberately stay Serial-only.

Transport and framing are in [BLE_PROTOCOL.md §7](BLE_PROTOCOL.md).

---

## 1. BLE command set

| Command | Args | Auth | Confirm in app | Backing primitive |
|---|---|---|---|---|
| `PING` | — | — | no | — |
| `GET_STATUS` | — | — | no | Snapshot buffer |
| `DEPLOY_SOLAR` | — | ✔ | **yes** | `setStateMotor(true)` — `control.h:176` |
| `RETRACT_SOLAR` | — | ✔ | **yes** | `setStateMotor(false)` |
| `BLINK_LED` | — | ✔ | no | `startBlink()` — `control.h:123` |
| `LED_ON` | — | ✔ | no | `control_light(true)` — `control.h:100` |
| `LED_OFF` | — | ✔ | no | `control_light(false)` |
| `TAKE_PHOTO` | — | ✔ | no | `capturePhotoToStorage()` (new) |
| `ZERO_ATTITUDE` | — | ✔ | no | Sets `offset_roll/pitch/yaw` |
| `CALIBRATE_IMU` | — | ✔ | **yes** | `calibrateMPU()` — `position_sensor.h:160` |
| `SAFE_MODE` | — | ✔ | **yes** | `setMode(MODE_SAFE)` |
| `NOMINAL_MODE` | — | ✔ | no | `setMode(MODE_NOMINAL)` |
| `DESKTOP_MODE` | — | ✔ | no | `setMode(MODE_DESKTOP)` |
| `WIFI_ON` | — | ✔ | no | `connectWiFiBounded()` (new) + `initServer()` |
| `WIFI_OFF` | — | ✔ | no | `WiFi.disconnect(true)`, `WiFi.mode(WIFI_OFF)` |
| `STOP_LOGGING` | — | ✔ | no | `stopLogging()` — `data_logger.h:343` |
| `REBOOT` | — | ✔ | **yes** | `ESP.restart()` |

`✔` = requires the encrypted, authenticated link. Only `PING` and `GET_STATUS` work
without pairing, so the app can prove connectivity before the user pairs.

### Notes per command

**`PING`** — round-trip check. Returns `{"uptime_ms":…}`. The app measures latency from it.

**`GET_STATUS`** — forces an immediate notify on all four telemetry characteristics
instead of waiting for the next scheduled tick. Reads the existing snapshot; it does
**not** trigger a sensor poll.

**`DEPLOY_SOLAR` / `RETRACT_SOLAR`** — `setStateMotor()` returns `false` if called within
2200 ms of the last change (`control.h:178`), matching the Nano's servo power-gating
window. That return value is currently discarded by every caller in the original firmware;
the command bus honours it and returns `ERR_RATE_LIMITED`.

There is **no position feedback** — the I²C link to the Nano is write-only
(`MySat_Nano_ATmega328p.ino:65`). `ok:true` means *the command was accepted and sent*, not
that the panels moved. The servo takes ~1.6 s to sweep and is force-detached at 2200 ms.

**`TAKE_PHOTO`** — captures and saves to LittleFS via the existing `savePhoto()`
(`camera.h:144`), returning `{"id":7,"size":58234}`. The image is **not** sent over BLE;
see [ARCHITECTURE.md §2.7](ARCHITECTURE.md) for why. Retrieve it with `WIFI_ON` and the
web interface. Storage is a 10-photo ring (`camera.h:14`) — the oldest is deleted
automatically.

**`ZERO_ATTITUDE`** — sets the presentation offsets so the current physical orientation
reads as 0/0/0. This is the practical answer to yaw drift: re-zero when the model and the
hardware disagree.

> These offsets are **not persisted** in the original firmware — only gyro bias goes to
> `/cal.dat` (`position_sensor.h:205`). After a reboot `calibration.valid` loads `true`
> while the offsets are back to zero, so the attitude silently un-zeroes. Persisting them
> is a candidate fix; until then the app re-issues `ZERO_ATTITUDE` after a detected
> reboot.

**`CALIBRATE_IMU`** — **blocks the entire firmware for ~7 seconds** (`delay(2000)` plus
500 × `delay(10)`, `position_sensor.h:160-198`). Telemetry stops, the web server stops,
and BLE notifications stop for the duration. The command bus notifies
`{"ok":true,"msg":"Calibrating, ~7s"}` **before** starting, so the app can show a blocking
progress state. The satellite must be still and level.

**`SAFE_MODE`** — stops logging, retracts panels, keeps BLE up. Deliberately conservative
in v1.

**`WIFI_ON`** — uses a new bounded connect with a 15 s timeout. The original
`tryConnectWiFi()` is a `while (true)` that recurses into `setWiFi()` and blocks on Serial
forever (`MySat_main.ino:188-214`) and is unusable from BLE. Requires `/config.txt` to
already hold credentials — BLE cannot set them in v1, so the first Wi-Fi setup still uses
the `SetWIFI` serial command. Returns `ERR_NO_WIFI_CONFIG` if unconfigured.

**`REBOOT`** — responds first, waits ~100 ms for the notification to go out, then calls
`ESP.restart()`. Requires confirmation plus a fresh `seq`.

---

## 2. Error codes

| Code | Meaning |
|---|---|
| `ERR_BAD_JSON` | Payload is not valid JSON, or exceeds 200 bytes |
| `ERR_UNKNOWN_CMD` | `cmd` not recognized |
| `ERR_MISSING_SEQ` | No `seq` field |
| `ERR_DUPLICATE_SEQ` | `seq` equals the last accepted — replay guard |
| `ERR_NOT_AUTHENTICATED` | Command needs an encrypted link |
| `ERR_RATE_LIMITED` | Within the 2200 ms motor guard |
| `ERR_BUSY` | Command queue full |
| `ERR_SERIAL_ONLY` | Command exists but is Serial-interactive |
| `ERR_NO_CAMERA` | `init_status.camera_` is false |
| `ERR_NO_WIFI_CONFIG` | `WIFI_ON` with no stored credentials |
| `ERR_CAPTURE_FAILED` | Camera returned no framebuffer within the retry bound |
| `ERR_FS_FULL` | LittleFS write failed |
| `ERR_UNAVAILABLE` | Hardware absent per `init_status` |

---

## 3. Safety

Three independent layers, because UI-only guards are not enough:

1. **Confirmation dialog** for `DEPLOY_SOLAR`, `RETRACT_SOLAR`, `CALIBRATE_IMU`,
   `SAFE_MODE`, `REBOOT`. Named explicitly in the prompt — no generic *"Are you sure?"*.
2. **Sequence numbers** (`seq`). Compose recomposition can re-fire a side effect; a
   resent `DEPLOY_SOLAR` would drive the servo again. The firmware rejects a repeated
   `seq`, so the guarantee holds regardless of client bugs.
3. **Authentication.** Everything except `PING` and `GET_STATUS` needs the bonded,
   encrypted link.

App-side implementation rules:

- Send commands from the ViewModel, never from a composable body.
- Wrap every send in a one-shot event (`Channel` / `SharedFlow`), never derive it from
  recomposable state.
- Disable the button until the matching `seq` response arrives or a 5 s timeout expires.
- Never auto-retry a command. Failure surfaces to the user; the user decides.

---

## 4. Original serial commands

All 21 keep working exactly as before. Matching is case-insensitive
(`String::equalsIgnoreCase`), so `solardeploy` == `SolarDeploy`; the canonical spellings
are below.

### Shared with BLE — routed through `command_bus.h`

These five branches in `console.h` are rewritten to call `executeCommand()`, so there is
one implementation per action rather than two.

| Serial command | BLE equivalent |
|---|---|
| `SolarDeploy` | `DEPLOY_SOLAR` |
| `SolarRetract` | `RETRACT_SOLAR` |
| `SolarMove` | toggle via `DEPLOY_SOLAR`/`RETRACT_SOLAR` |
| `BlinkLed` | `BLINK_LED` |
| `TurnLed` | `LED_ON` / `LED_OFF` |

This also fixes a real bug: `TurnLed` currently calls `light_on()` (`console.h:154`),
which is the **HTTP handler** and ends with `server.send(200, "text/plain", "OK")`
executed with no HTTP client in flight (`server.h:1424-1430`).

### Serial-only — rejected over BLE with `ERR_SERIAL_ONLY`

| Command | Why |
|---|---|
| `ChangeTime` | 6 blocking prompts — `RTC.h:46-72` |
| `SetWIFI` | Blocking prompts for SSID and password |
| `SetCallSign` | Blocking prompt — `console.h:66-77` |
| `StartLogging` | Blocking prompt for period — `data_logger.h:309` |
| `SelectPlotterMode` | Blocking prompt — `console.h:37` |
| `SwitchTelemetry` | Enters plotter mode, which then blocks |
| `Calibrate` | Exposed as `CALIBRATE_IMU` instead (non-interactive) |
| `TurnConsole` | Serial-output concept only |
| `DebugModeOn` / `DebugModeOff` | Serial-output concept only |
| `SetRadio` | **No-op today** — the Nano's `RF_SET` case is empty (`MySat_Nano_ATmega328p.ino:99-101`) |
| `AuditFileSystem` | Multi-line Serial report |
| `ListLogFiles` | Multi-line Serial report |
| `SendEventLog` | Up to 100 lines |
| `StopLogging` | Exposed as `STOP_LOGGING` |
| `DeleteLogging` | Destructive; kept Serial-only in v1 |

Each blocks on an unbounded `while (!Serial.available()) {}`. Exposing them over BLE
without rewriting their parameter acquisition would let a phone hang the satellite until
someone plugs in a USB cable — so they are rejected rather than bridged. Adding argument
parsing (e.g. `StartLogging 5`) would make them transport-neutral; that is deliberately
out of scope for v1, since it means touching a console that works today.

---

## 5. Adding a command

1. Add the `CommandId` enum value and its `case` in `firmware/ino/MySat_main/command_bus.h`.
2. Add the name to the string table in the same file.
3. If it should also be a serial command, add the branch in `console.h` calling
   `executeCommand()`.
4. Add it to the app's `Command` sealed class.
5. Document it in this file.
6. Add a row to [TEST_PLAN.md](TEST_PLAN.md).

Handlers **must not** block. Anything longer than ~50 ms needs a state machine like
`updateBlinkStarLed()` (`control.h:130-151`) — `CALIBRATE_IMU` is the acknowledged
exception, and it warns before blocking.

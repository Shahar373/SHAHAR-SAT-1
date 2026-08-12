# SHAHAR-SAT 1 — Test Plan

Verification procedures for the BLE layer and Android app, on top of the existing MySat
firmware. Every test here exists to catch a specific failure mode identified during
exploration of the codebase — see [ARCHITECTURE.md](ARCHITECTURE.md) for why each hazard
exists.

Run the applicable section after each milestone (see the milestone table in the plan);
don't defer everything to the end.

---

## 0. Regression — original firmware must keep working

Run before touching anything, and again after every milestone. If any of these breaks,
the milestone is not done, regardless of what BLE feature landed.

| # | Check | Expected |
|---|---|---|
| 0.1 | All 21 serial commands (`console.h:131-278`) | Same behavior as before BLE was added |
| 0.2 | Web GUI loads at `/` | Renders, `/get_data` polls every 2 s |
| 0.3 | `/get_photo` | Returns a JPEG-in-JSON response |
| 0.4 | `SolarDeploy` / `SolarRetract` via serial | Servo moves, `stateMotor` persists across reboot |
| 0.5 | `StartLogging 5` then `StopLogging` | CSV rows appended, logger stops cleanly |
| 0.6 | HC-12 wiring | Untouched — no `Serial` writes added by BLE code |
| 0.7 | Boot with no `/config.txt` | Wi-Fi stays off, no hang |

---

## 1. Firmware build and resources

| # | Check | Expected | Reference |
|---|---|---|---|
| 1.1 | `arduino-cli compile --fqbn esp32:esp32:esp32cam:PartitionScheme=no_ota` | Succeeds, record flash % — **already passed once** (esp32:esp32@2.0.9 + NimBLE-Arduino 2.2.3, zero warnings); re-run against whatever core version you actually install, since versions drift | [ARCHITECTURE.md §1.9](ARCHITECTURE.md) |
| 1.2 | App partition usage | **Fail the milestone above 90%** | "No OTA (2MB APP/2MB SPIFFS)" scheme — already measured at 61% via `arduino-cli`, see firmware/README.md |
| 1.3 | `ESP.getFreeHeap()` before/after `bleInit()` | Documented delta, no crash | |
| 1.4 | `ESP.getMinFreeHeap()` over a 1 h idle run | No downward trend (leak) | [HARDWARE_NOTES.md §2](HARDWARE_NOTES.md) |
| 1.5 | PSRAM check | Logged at boot, matches [HARDWARE_NOTES.md §1](HARDWARE_NOTES.md) | |

---

## 2. BLE — advertising and discovery

Use **nRF Connect** for this whole section before any Android code exists — it validates
the firmware side independently.

| # | Check | Expected |
|---|---|---|
| 2.1 | Scan for `SHAHAR-SAT-1` | Appears within advertising interval (100-250 ms) |
| 2.2 | Inspect advertisement packet | Contains name + flags, ≤31 bytes |
| 2.3 | Inspect scan response | Contains the 128-bit service UUID |
| 2.4 | `ScanFilter` by service UUID (write a throwaway test) | Matches |
| 2.5 | Two phones scanning simultaneously | Both see the device; connecting from one doesn't hide it from the other's scan |

---

## 3. BLE — connection lifecycle

| # | Check | Expected |
|---|---|---|
| 3.1 | Connect from nRF Connect | Succeeds, advertising stops |
| 3.2 | Discover services | All 7 characteristics present with correct UUIDs, properties, descriptors |
| 3.3 | Request MTU 517 | Firmware grants up to its negotiated max; verify actual value |
| 3.4 | Subscribe to Attitude, Power, Environment, System | Notifications begin at documented rates |
| 3.5 | Disconnect (nRF Connect side) | Firmware detects it, advertising resumes within ~1 s |
| 3.6 | Disconnect (power off ESP32) | App-side: detected via supervision timeout, not a hang |
| 3.7 | Reconnect after disconnect | Previously bonded — no re-pairing prompt |
| 3.8 | Two connection attempts in quick succession | Second is rejected or queued cleanly, no crash |

---

## 4. BLE — GATT discovery and notifications

| # | Check | Expected |
|---|---|---|
| 4.1 | Read Attitude characteristic directly (no subscribe) | Valid JSON, matches last notify |
| 4.2 | Notification rate — Attitude | Fixed ~10 Hz, always — **not** adaptive; verify it does *not* slow down when the satellite is still (that would indicate an unintended regression, not a feature) |
| 4.3 | Notification rate — Power | ~2 Hz |
| 4.4 | Notification rate — Environment | ~1 Hz |
| 4.5 | Notification rate — System | On change, or every ≥5 s otherwise |
| 4.6 | Frame size vs negotiated MTU | Never exceeds ATT payload; check firmware log for any dropped-frame warning |
| 4.7 | Field omission | Rotate the IMU into an uncalibrated state — `r`/`p`/`y` absent, `cal:0` present, not `0.0` |
| 4.8 | Sensor physically disconnected (if safe to test) | Corresponding frame's `hw` flag and field omission behave correctly |

---

## 5. BLE — commands

| # | Command | Check | Expected |
|---|---|---|---|
| 5.1 | `PING` | Send, no auth | `ERR_NOT_AUTHENTICATED` before bonding; succeeds after |
| 5.2 | `GET_STATUS` | Send | Immediate notify on all 4 telemetry characteristics |
| 5.3 | `DEPLOY_SOLAR` | Send once | Servo moves, response `ok:true`, `mot:1` in next System frame |
| 5.4 | `DEPLOY_SOLAR` | Send twice within 2200 ms | Second returns `ERR_RATE_LIMITED` |
| 5.5 | `DEPLOY_SOLAR` | Resend same `seq` | `ERR_DUPLICATE_SEQ`, servo does **not** move again |
| 5.6 | `BLINK_LED` | Send | LED blinks per `updateBlinkStarLed()` timing, then restores prior state |
| 5.7 | `TAKE_PHOTO` | Send | Response `{id, size}`; file exists in `/get_photo_list` over Wi-Fi afterward |
| 5.8 | `CALIBRATE_IMU` | Send | Immediate `ok:true` response, then ~7 s of no telemetry, then resumes; satellite must be still |
| 5.9 | `ZERO_ATTITUDE` | Send while tilted | Next Attitude frame reads ~0/0/0 |
| 5.10 | `REBOOT` | Send | Response arrives before disconnect; ESP32 restarts; app detects via `up` reset |
| 5.11 | Malformed JSON | Write garbage bytes | `ERR_BAD_JSON`, connection stays alive |
| 5.12 | Payload > 200 B | Write oversized buffer | Rejected before parsing |
| 5.13 | Unknown `cmd` | Send `"cmd":"FOOBAR"` | `ERR_UNKNOWN_CMD` |
| 5.14 | Missing `seq` | Send without `seq` | `ERR_MISSING_SEQ` |
| 5.15 | Serial-only command over BLE | e.g. `ChangeTime` | `ERR_SERIAL_ONLY`, no hang |
| 5.16 | Command while unauthenticated | Any command except `PING`/`GET_STATUS` | `ERR_NOT_AUTHENTICATED` |
| 5.17 | `WIFI_ON` with no `/config.txt` | Send | `ERR_NO_WIFI_CONFIG` |
| 5.18 | `WIFI_ON` with valid config | Send | Connects within 15 s bound, `WIFI_UP` event fires |
| 5.19 | Command sent during `CALIBRATE_IMU` | Send `PING` mid-calibration | Queued or `ERR_BUSY`, no crash |

---

## 6. Security

| # | Check | Expected |
|---|---|---|
| 6.1 | First connection | Android prompts for the passkey |
| 6.2 | Correct passkey | Pairing succeeds, bond stored |
| 6.3 | Wrong passkey | Pairing fails, connection drops |
| 6.4 | Reconnect after bonding | No re-prompt |
| 6.5 | Telemetry read before pairing | Succeeds (open) |
| 6.6 | Command write before pairing | `ERR_NOT_AUTHENTICATED` |
| 6.7 | Bond survives ESP32 reboot | Reconnect without re-pairing |
| 6.8 | Bond survives phone reboot | Same |
| 6.9 | Remove bond in Android settings, reconnect | Re-pairing prompt appears again |

---

## 7. Android — permissions and connection UX

minSdk 31 — no Location permission should ever be requested.

| # | Check | Expected |
|---|---|---|
| 7.1 | Fresh install | Prompts `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`, never Location |
| 7.2 | Deny `BLUETOOTH_SCAN` | Explains why, offers Settings deep link, no crash |
| 7.3 | Deny `BLUETOOTH_CONNECT` | Same |
| 7.4 | Bluetooth adapter off | Prompt to enable, no scan attempt |
| 7.5 | Enable Bluetooth mid-prompt | Resumes automatically |
| 7.6 | No spacecraft in range | `Searching for spacecraft…` with visible retry, no infinite spinner |
| 7.7 | Connection timeout | Bounded exponential backoff — **verify it is not a tight loop** (watch logcat call frequency) |
| 7.8 | Auto-reconnect to last bonded device on app open | Happens without user action |
| 7.9 | App backgrounded and resumed while connected | Connection persists or cleanly re-establishes |
| 7.10 | Airplane mode toggled while connected | Detected, clean disconnect state shown |

---

## 8. Android — telemetry rendering

| # | Check | Expected |
|---|---|---|
| 8.1 | Dashboard cards populate | All fields from §2.1-2.6 of [TELEMETRY.md](TELEMETRY.md) shown correctly |
| 8.2 | Missing field (`cal:0`) | Renders `--`, not `0` |
| 8.3 | Battery % | Labelled "estimated" |
| 8.4 | Charging state | Labelled "inferred" |
| 8.5 | Yaw | Visibly labelled as relative/drifting somewhere in the UI |
| 8.6 | Connection indicator | `● CONNECTED` / `● DISCONNECTED`, updates within one missed notification window |
| 8.7 | Malformed/truncated frame | Dropped, parse-error counter increments (visible in Engineering screen), last good value retained |
| 8.8 | RSSI shown | Matches `readRemoteRssi()`, updates periodically |

---

## 9. Android — controls and safety

| # | Check | Expected |
|---|---|---|
| 9.1 | `DEPLOY PANELS` button | Confirmation dialog names the action explicitly |
| 9.2 | Rapid double-tap on `DEPLOY PANELS` | Only one command sent — verify via Engineering screen's TX counter |
| 9.3 | Rotate device during a pending command | No duplicate send from recomposition (this is the specific bug class `seq` guards against) |
| 9.4 | `REBOOT` | Confirmation dialog, then graceful disconnect handling |
| 9.5 | Button disabled while awaiting response | Re-enables on response or 5 s timeout |
| 9.6 | Command fails (`ERR_*`) | Error surfaced to user, not silently swallowed, no auto-retry |

---

## 10. 3D attitude visualization

| # | Check | Expected |
|---|---|---|
| 10.1 | Physically roll the satellite | Model rolls to match, correct direction |
| 10.2 | Physically pitch | Model pitches to match |
| 10.3 | Physically yaw | Model yaws to match; drift over a few minutes is visible and expected |
| 10.4 | Yaw crossing ±180° | No visible snap/flip in the model |
| 10.5 | Pitch near ±90° | No gimbal-lock jump |
| 10.6 | Notification jitter (real BLE, not simulated) | Model motion still reads as smooth — validates SLERP smoothing |
| 10.7 | `ZERO_ATTITUDE` while tilted | Model snaps to level, no discontinuous spin |
| 10.8 | Rapid disconnect/reconnect | Model holds last orientation, doesn't reset to identity or jump |

---

## 11. Debug/Engineering screen

| # | Check | Expected |
|---|---|---|
| 11.1 | Device address, RSSI | Correct and live |
| 11.2 | GATT services/characteristics list | Matches [BLE_PROTOCOL.md](BLE_PROTOCOL.md) exactly |
| 11.3 | Raw RX packets | Visible, matches notification content |
| 11.4 | Raw TX (commands sent) | Visible, matches what the ViewModel sent |
| 11.5 | Packet counters | Increment correctly, survive scroll/navigation |
| 11.6 | Last telemetry timestamp | Updates, goes stale visibly if notifications stop |
| 11.7 | MTU | Shows negotiated value |
| 11.8 | BLE error log | GATT error codes appear here, not just as a toast |

---

## 12. Coexistence and power

| # | Check | Expected |
|---|---|---|
| 12.1 | BLE connected, `WIFI_ON` sent | Wi-Fi comes up; measure BLE notification jitter during the transition |
| 12.2 | `TAKE_PHOTO` while BLE connected and Wi-Fi off | Succeeds; heap does not approach exhaustion (§1.4) |
| 12.3 | `TAKE_PHOTO` while BLE connected and Wi-Fi on | Same, this is the documented highest-risk moment — watch heap closely |
| 12.4a | DESKTOP mode at boot, no `/config.txt` (fresh device) | Wi-Fi stays off until `WIFI_ON` is sent — `setMode(MODE_DESKTOP)` itself never touches Wi-Fi; it's off only because nothing else started it |
| 12.4b | DESKTOP mode at boot, `/config.txt` says `useWiFi=Yes` (previously configured via serial `SetWIFI`) | Wi-Fi auto-connects during `setup()`, **before** `setMode(MODE_DESKTOP)` runs — this is unchanged original behavior, not overridden by DESKTOP mode. If this surprises you, that's the point of this row: DESKTOP mode is not a Wi-Fi killswitch for an already-configured device, only the default for a fresh one. See `docs/ARCHITECTURE.md` §2.6. |
| 12.5 | Advertising interval measured | Matches 100-250 ms target |

---

## 13. Remove Before Flight (RBF)

Electrical behavior is documented from the official MySat guide, not discovered here —
see `docs/ARCHITECTURE.md` §4 and `docs/HARDWARE_NOTES.md`. These checks confirm the
documented behavior against the actual kit, and specifically probe the one interaction
this project flagged as a real risk (RBF/power-loss during a flash write) rather than
fixed.

| # | Check | Expected |
|---|---|---|
| 13.1 | Insert RBF plug while powered via USB-C | Board loses power immediately (battery + 5V IN both cut) |
| 13.2 | Insert RBF plug while powered via the PROGRAMER connector | **Board stays powered** — this is the documented gap, confirm it's real and not guide inaccuracy |
| 13.3 | Insert-then-immediately-remove RBF (normal reset procedure) | Clean reboot; `event_log.txt` shows a paired SHUTDOWN/BOOT, no corruption |
| 13.4 | Remove RBF plug after insertion | Power returns; firmware boots normally |
| 13.5 | MH CD-42 button: press once | Module powers on |
| 13.6 | MH CD-42 button: press twice | Module powers off |
| 13.7 | Insert RBF while `StartLogging` is actively writing a CSV row | Check `/mdata_*.csv` and `/logger_state.txt` afterward for a truncated row or desync between them — this is the risk flagged in ARCHITECTURE.md §4, not previously tested |
| 13.8 | Insert RBF mid-write of `writeEventLog()` (hard to time exactly; repeat power-cycles a few times in a row is a reasonable proxy) | Check for an orphaned `/event_log.tmp` or a corrupted `/event_log.txt` |
| 13.9 | BLE bond survives an RBF power cycle | Reconnect without re-pairing — bonds live in NVS, which is flash, not RAM |

---

## 14. Long-duration soak

Run after Milestone 9. Sample `ESP.getFreeHeap()`, `ESP.getMinFreeHeap()`,
`LittleFS.usedBytes()`, and connection/disconnect count at fixed intervals throughout.

| # | Duration | Watch for |
|---|---|---|
| 13.1 | 30 min | Baseline — no crash, no leak trend, notification rates hold |
| 13.2 | 2 h | Heap trend, LittleFS growth from logging, any BLE stack error in Serial log |
| 13.3 | 8 h | Full soak — disconnects, memory leaks, ESP32 stability, Android app stability (does the app itself leak or ANR?), overall BLE link stability |

Record results in a simple table (timestamp, free heap, min free heap, LittleFS used,
disconnect count) — attach to the milestone's commit or PR description rather than to this
file, so this file stays a procedure, not a log.

---

## Adding a test

When a new command or telemetry field is added ([COMMANDS.md §5](COMMANDS.md)), add a row
to the relevant section above in the same commit — a feature without a corresponding test
row here is not considered done.

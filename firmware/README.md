<img src="https://drive.google.com/uc?export=download&id=10ScfFZb5kTdQQ1aUS-J6Ik5yzo1wd_EH" alt="MySat" width="150"/>

[MySat Webstore](https://www.mysatkit.com/)

# MySatKit-Firmware
This repository contains the main firmware for MySat Kit microcontrollers (ESP32-CAM and ATmega328P).  <br/><br/>
**Purpose:** Simulate the functionality of a 1U CubeSat nanosatellite.  
**Required software:** Arduino IDE 2.0+.

**Instructions:**
[MYSAT KIT GUIDE](https://docs.google.com/document/d/146EPTvLMzydpwUMsbJWAC3gcRO6yPDe3p8kIpxRUwU4/edit?tab=t.0) [(→ Microcontroller Setup Guide)](https://docs.google.com/document/d/146EPTvLMzydpwUMsbJWAC3gcRO6yPDe3p8kIpxRUwU4/edit?tab=t.mkgezpqxvo88#heading=h.d7als96if9cq)

**Repo structure:**  
- `ino` - contains Arduino projects
  - `MySat_main` - for ESP32-CAM
  - `MySat_Nano_ATmega328p` - for Nano board (ATmega328P)
- `libraries.zip` - archive with libs for ESP32 firmware

---

# SHAHAR-SAT 1 additions

This fork adds a BLE telemetry/command layer on top of the firmware
above — see [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for what
it does and why. Two build-setup changes it requires, beyond what's
already in `libraries.zip`:

**Extra library — NimBLE-Arduino (h2zero), not in `libraries.zip`.**
Chosen over the ESP32 core's built-in Bluedroid BLE stack because it
costs roughly 100-150KB of flash instead of 500-700KB for an equivalent
GATT server; see `docs/ARCHITECTURE.md` §1.9/§2.2. The Arduino Library
Manager's index isn't reachable from the environment this was verified
in, so it was added by cloning the repo straight into the libraries
folder instead:
```
git clone --branch 2.2.3 https://github.com/h2zero/NimBLE-Arduino.git
```
**Pin to a 2.x release** (2.2.3 is the version this was actually built
and tested against) — 1.x has a different callback API
(`NimBLEServerCallbacks`/`NimBLECharacteristicCallbacks` take a plain
`BLECharacteristic*` there, not `NimBLEConnInfo&`) and won't compile
against `ble_service.h` as written.

**Partition scheme — select "No OTA (2MB APP/2MB SPIFFS)"** from
Tools > Partition Scheme in the Arduino IDE. No custom partition file
needed: this board definition (AI-Thinker ESP32-CAM) has no "Custom"
option in its Partition Scheme menu in either of the two ESP32 core
versions checked (2.0.9 and the current 3.3.11) — confirmed by
inspecting `boards.txt` in both — so a `partitions.csv` placed in the
sketch folder is silently ignored for this specific board. "No OTA"
gives 2MB app / 2MB filesystem, comfortably covering both the compiled
image (1.28MB, ~61% of the app partition) and the ~1.46MB worst-case
LittleFS usage from `docs/ARCHITECTURE.md` §1.9.

**Build-verified**: this compiles cleanly (`arduino-cli compile --fqbn
esp32:esp32:esp32cam:PartitionScheme=no_ota`) against esp32:esp32@2.0.9
+ NimBLE-Arduino 2.2.3 + the libraries above, zero warnings from any of
the new or edited files. 1,285,805 bytes flash (61% of the 2MB app
partition), 61,804 bytes RAM (18%).

**Note on the core version**: the official MySat guide pins **2.0.17**, not 2.0.9.
Installing 2.0.17 specifically was attempted and blocked by an environment-specific
issue unrelated to this project's code — see `docs/HARDWARE_NOTES.md`'s "Resolved by
the official MySat guide" section for the exact reason (a `dfu-util` tool dependency
from a different, also-blocked package index; irrelevant to this board, which uploads
over serial). 2.0.9 and 2.0.17 are the same 2.0.x line, so the compile is expected to
be identical, but that is not yet directly confirmed — re-verify against 2.0.17 on a
machine with normal internet access before treating this as fully closed.

This does not confirm behavior on
real hardware (BLE pairing, actual timing, the PSRAM question in
`docs/HARDWARE_NOTES.md` #1) — only that the code is syntactically and
semantically valid C++ against the real toolchain and library APIs.
See [`../docs/TEST_PLAN.md`](../docs/TEST_PLAN.md) §1 for what's still
open.

---

# Release notes

## V.1.4

**Release date: 2026/06/22**  
**Changes**:
|||
|:-|--|
| New Features → | • implemented a system event logging with Web GUI export capability |
| | • added telemetry frame counting |
| | • added the ability to clear mission data csv files |
| UI Updates → | • added a mission data logging status indicator to the Web GUI |

**MySat boards support:**
> *full:* v.1.5.6+  
> *partial:* v.1.5.2+

## V.1.3

**Release date: 2026/04/06**  
**Changes**:
|||
|:-|--|
| New Features → | • implemented local photo storage (buffer for 10 images) |
| | • implemented Mission data logging to CSV files|
| | • added `BlinkLED` command for quick hardware connection testing |
| UI Updates → | • added support for displaying saved camera frames |
| | • integrated "Connection Status" indicator to monitor real-time data sync |
| | • revamped "Sunlight Trackers" widget with coordinate axes (X/Y) and sensor mapping |
| | • added "Battery" and "Solar panels" monitors to Web GUI |
| | • added log file download capability via the Web GUI |
| | • minor changes |
| Bug Fixes → | • minor bug fixes|

**MySat boards support:**
> *full:* v.1.5.6+  
> *partial:* v.1.5.2+

## V.1.2

**Release date:** 2026/04/02
**Changes**:
|||
|:-|--|
| New Features → | • integrated Signal LED system for real-time system status indication |
| | • added Indoor Air Quality (IAQ) index calculation based on the BME680 data |
| | • added option to set a custom callsign for the satellite |
| | • implemented `TurnConsole` command to toggle data broadcasting mode |
| | • implemented LittleFS file system for reliable data storage |
| UI Updates → | • added Arduino IDE Plotter mode for real-time data visualization |
| | • introduced Debug mode for extended system process monitoring |
| | • ensured Web GUI autonomy (offline operation without internet connection) |
| | • minor changes and bug fixes |
| Bug Fixes → | • eliminated latencies and lags in the Web GUI performance |

**MySat boards support:**
> *full:* v.1.5.6+  
> *partial:* v.1.5.2+

## V.1.1  

**Release date:** 2025/10/03   
**Changes**:
|||
|:-|--|
| New Features → | • added support for INA3221, voltage&current sensor|
|| • added rotation angle calculation using the MPUxxxx module|
|| • implemented software protection for servomotor|
| UI Updates → | • new Web GUI widget for the solar navigation system|
|| • displaying photo capture time in Web GUI|
|| • added console commands for controlling servomotor and StarLED |
|| • minor changes and bug fixes|
| Bug Fixes → | • ensured compatibility with v.1.5.5 boards (for HC-12 module)|

**MySat boards support:**
> *full:* v.1.5.3+  
> *partial:* v.1.5.2

## V.1.0.1  

**Release date:** 2025/05/13   
**Changes**:
|||
|:-|--|
| New Features → |• added Wi-Fi configuration function |
|| • added RTC configuration function|
| Bug Fixes → | • minor bug fixes|

**MySat boards support:**
> *full:* v.1.5.2 - v.1.5.4 (no newer!)

## V.1.0.0

**Release date:** 2025/01/18  
**Changes**:  
[ initial release ]

**MySat boards support:**
> *full:* v.1.5.2 - v.1.5.4 (no newer!)

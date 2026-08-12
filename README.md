# SHAHAR-SAT 1

Turning a MySat Space Fan Kit into an interactive desktop CubeSat model: power it on,
open an Android app, see live telemetry, send commands, and watch a 3D model track the
satellite's real orientation over Bluetooth Low Energy — no ground station, no RF dongle,
no USB, no serial terminal.

BLE is an **additional** transport layered onto the existing MySat firmware. Wi-Fi, the
web GUI, the serial console, the HC-12 radio wiring, the camera, logging and solar
deployment all keep working exactly as before.

## Repository structure

```
firmware/   MySat ESP32-CAM + Nano firmware (upstream MySatKit-Firmware, plus the BLE layer)
android/    SHAHAR-SAT Android app — Kotlin, Jetpack Compose, native BLE APIs
docs/       Architecture, protocol, command reference, telemetry reference, test plan
```

## Documentation

- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — how the existing firmware is built,
  where BLE plugs in, and why
- **[docs/BLE_PROTOCOL.md](docs/BLE_PROTOCOL.md)** — GATT service, UUIDs, frame formats,
  security
- **[docs/COMMANDS.md](docs/COMMANDS.md)** — the command set and safety model
- **[docs/TELEMETRY.md](docs/TELEMETRY.md)** — every telemetry field, its unit and source
  — and what the hardware does *not* provide
- **[docs/HARDWARE_NOTES.md](docs/HARDWARE_NOTES.md)** — open questions that need the
  physical kit to answer
- **[docs/TEST_PLAN.md](docs/TEST_PLAN.md)** — verification procedures

## Build toolchain

| Component | Version | Where it's pinned |
|---|---|---|
| JDK | 17+ (built/tested with 21) | Whatever runs `./gradlew` — AGP 8.7.x requires 17 minimum |
| Gradle | 8.9 | `android/gradle/wrapper/gradle-wrapper.properties` (via the wrapper — don't need a separate Gradle install) |
| Android Gradle Plugin | 8.7.2 | `android/build.gradle.kts` |
| Kotlin | 2.0.21 | `android/build.gradle.kts` |
| compileSdk / targetSdk | 35 | `android/app/build.gradle.kts` — capped at 35 because that's AGP 8.7.x's ceiling; do not bump to 36 without also bumping AGP |
| minSdk | 31 (Android 12+) | `android/app/build.gradle.kts` — deliberate, see docs/ARCHITECTURE.md's Android section |
| ESP32 core (Arduino) | 2.0.17 (per the official MySat guide) or 2.0.9 (what this repo's firmware was actually compiled against — see below) | Arduino IDE Boards Manager |
| NimBLE-Arduino | 2.2.3+ (2.x line required) | Installed manually, not in `libraries.zip` — see `firmware/README.md` |

**Android build commands:**
```
cd android
./gradlew testDebugUnitTest   # unit tests only, no device/emulator needed
./gradlew assembleDebug       # full debug APK
```
Neither has actually been run to completion in this repo's own development environment —
its network policy blocks `dl.google.com`, which both AGP and AndroidX resolve through
(`maven.google.com` redirects there too). Run them on a machine with normal internet
access; see "Status" below for what *has* been verified as a substitute.

**Firmware build command** (already run for real — see "Status"):
```
arduino-cli compile --fqbn esp32:esp32:esp32cam:PartitionScheme=no_ota \
  --libraries <path-to-libraries-folder-with-NimBLE-Arduino-added> \
  firmware/ino/MySat_main
```

## Status

Milestones 0–8 are implemented as code — repo restructured and documented; the firmware
BLE layer (NimBLE telemetry/commands/modes) and the Android app (BLE client, dashboard,
3D attitude view) are both written. See [ARCHITECTURE.md §Milestones](docs/ARCHITECTURE.md)
for the full sequence.

**The firmware compiles.** `arduino-cli` and the ESP32 core were installed and the sketch
was actually built — `esp32:esp32@2.0.9` + NimBLE-Arduino 2.2.3, `arduino-cli compile
--fqbn esp32:esp32:esp32cam:PartitionScheme=no_ota` — and it succeeds with zero warnings
from any new or edited file: 1,285,805 bytes flash (61% of the 2MB app partition),
61,804 bytes RAM (18%). That run caught and fixed two real bugs before anyone touched
real hardware: pinning NimBLE-Arduino to a 2.x release (1.x has an incompatible callback
API), and discovering that the AI-Thinker ESP32-CAM board has no "Custom" partition
option in its menu at all (in either ESP32 core version checked) — so the plan to ship a
custom `partitions.csv` was replaced with the board's built-in "No OTA (2MB APP/2MB
SPIFFS)" scheme, which fits comfortably. See `firmware/README.md` for the exact commands.

**Still not done, because it needs the physical kit:**
- Milestone 9 (long-duration soak) and every item in
  [HARDWARE_NOTES.md](docs/HARDWARE_NOTES.md) — starting with whether PSRAM is present,
  which decides whether the camera survives running alongside BLE at all.
- Everything a compiler can't check: real advertising, pairing, notification timing,
  actual sensor behavior. That's what [TEST_PLAN.md](docs/TEST_PLAN.md) §2 onward covers.

**The Android app itself has not been built** — this environment's outbound proxy
explicitly denies `dl.google.com` (confirmed via the proxy's own status endpoint, not a
transient failure — `maven.google.com` resolves but every path on it 301-redirects to the
same blocked `dl.google.com`), so the Android Gradle Plugin and AndroidX can't be
resolved here, unlike the firmware's ESP32 toolchain, which comes from Espressif/GitHub
infrastructure rather than Google's. The Gradle wrapper is included (`android/gradlew`),
so `./gradlew assembleDebug` should run on a machine with normal network access — that
first full build is Milestone 4's own verification step.

**But the two riskiest pieces of pure logic were independently verified anyway.** The
Euler→quaternion/SLERP math (`gl/Quaternion.kt`) and the telemetry frame parsing
(`data/TelemetryFrames.kt`, `data/Command.kt`) have no Android dependency at all — they're
plain Kotlin. Copied into a throwaway Kotlin/JVM Gradle project (Kotlin + kotlinx.serialization
from Maven Central, no Google Maven needed) and run for real: **all 19 unit tests pass**,
including the yaw-wrap SLERP case that's the actual reason quaternions were chosen over
filtering the three Euler angles independently. This doesn't prove the full app builds —
Compose, the BLE APIs, and Navigation are still unverified — but it does prove the math
and parsing this whole feature depends on are actually correct, not just plausible-looking.

## Firmware origin

`firmware/` starts from [MySatKit-Firmware](https://github.com/MySatKit/MySatKit-Firmware)
v1.4. See `firmware/README.md` for the upstream project's own documentation.

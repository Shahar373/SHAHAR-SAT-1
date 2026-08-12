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

## Status

Milestones 0–8 are implemented as code — repo restructured and documented; the firmware
BLE layer (NimBLE telemetry/commands/modes) and the Android app (BLE client, dashboard,
3D attitude view) are both written. See [ARCHITECTURE.md §Milestones](docs/ARCHITECTURE.md)
for the full sequence.

**Not yet done, because both require the physical kit:**
- Milestone 9 (long-duration soak) and every hardware-dependent item in
  [HARDWARE_NOTES.md](docs/HARDWARE_NOTES.md) — starting with whether PSRAM is present,
  which decides whether the camera survives running alongside BLE at all.
- **The firmware has not been compiled** against the real ESP32 toolchain — no
  `arduino-cli`/ESP32 core was available in the environment it was written in. First
  compile is the opening step of [TEST_PLAN.md §1](docs/TEST_PLAN.md).
- **The Android app has not been built** — this environment's outbound proxy explicitly
  denies `dl.google.com`, so the Android Gradle Plugin and AndroidX dependencies can't be
  resolved here. The Gradle wrapper is included (`android/gradlew`), so
  `./gradlew assembleDebug` / `./gradlew testDebugUnitTest` should run on a machine with
  normal network access — that first build is Milestone 4's own verification step. The two
  unit test suites (quaternion/SLERP math, telemetry frame parsing) were checked by hand
  against the protocol spec but have not actually been executed.

In short: the code is written and internally consistent with the documented design, but
**nothing has compiled or run yet** — treat the first real build, on hardware or with SDK
access, as the actual start of verification.

## Firmware origin

`firmware/` starts from [MySatKit-Firmware](https://github.com/MySatKit/MySatKit-Firmware)
v1.4. See `firmware/README.md` for the upstream project's own documentation.

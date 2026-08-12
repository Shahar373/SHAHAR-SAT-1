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

Milestone 0 — repository restructured, architecture documented. See
[ARCHITECTURE.md §Milestones](docs/ARCHITECTURE.md) for the full sequence from BLE
proof-of-concept through the Android app to long-duration soak testing.

## Firmware origin

`firmware/` starts from [MySatKit-Firmware](https://github.com/MySatKit/MySatKit-Firmware)
v1.4. See `firmware/README.md` for the upstream project's own documentation.

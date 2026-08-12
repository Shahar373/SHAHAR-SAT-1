# SHAHAR-SAT 1 — Requires Hardware Verification

Every open question in this project that source code alone cannot answer, in priority
order. None of these are guessed at elsewhere in the docs — where an answer isn't in the
firmware, it is listed here instead.

Work through this list once the kit is physically available, ideally before Milestone 1.

---

## 1. Is PSRAM present and enabled? — **highest priority**

`camera_config_t config;` in `firmware/ino/MySat_main/camera.h:27` is declared without an
initializer. Only 20 of its fields are assigned; `fb_location`, `grab_mode` and
`sccb_i2c_port` are left as indeterminate stack garbage. Whether the camera framebuffer
lands in PSRAM or internal DRAM is therefore not determined by the source — it depends on
whatever happened to be on the stack at that call.

This governs whether the camera can coexist with BLE + Wi-Fi at all, since
`handleGetPhoto()` peaks at roughly 250-300 KB of live heap for one photo
(`server.h:1318-1329`).

**How to check:** flash the unmodified firmware, connect over serial, run
`DebugModeOn`, then `AuditFileSystem`, and add a one-line probe —
`Serial.println(psramFound() ? "PSRAM: yes" : "PSRAM: no");` in `setup()` — before
touching anything else. If present, also print `ESP.getPsramSize()`.

**Consequence if absent:** `Milestone 0` must fix `camera_config_t` initialization
explicitly (`= {}` plus `config.fb_location = CAMERA_FB_IN_DRAM`) and either shrink
`FRAMESIZE_XGA` or disable the camera whenever BLE + Wi-Fi are both active.

---

## 2. Real free heap with BLE running

Only two heap probes exist in the firmware today, both debug-gated: `camera.h:154` and
`server.h:1210`. There is no `ESP.getMinFreeHeap()` call anywhere.

**How to check:** after Milestone 2 (telemetry characteristics live), run with
`DebugModeOn` for at least an hour with Wi-Fi off, then again with Wi-Fi on, logging
`ESP.getFreeHeap()` and `ESP.getMinFreeHeap()` every 30 s. Watch specifically for the
minimum, not the average — fragmentation shows there first.

**Consequence:** if minimum free heap drops below ~20 KB during normal operation, the
attitude notify rate or the JSON buffer sizes need to shrink.

---

## 3. Battery chemistry and voltage range

Nothing in the firmware states the cell chemistry. The only clue is a browser-side
heuristic, `server.h:970`: `if (bV > 4.0 && bC < -5.0)`, which is consistent with a single
Li-ion/LiPo cell but is not a firmware-declared spec.

**How to check:** find the physical battery — read its printed label (mAh, chemistry,
protection board) or the MySat kit documentation (the README defers all hardware detail to
an external Google Doc, `README.md:11`). Confirm nominal voltage (3.7 V?), full-charge
voltage (4.2 V?), and cutoff voltage.

**Consequence:** the app's estimated battery-percentage curve (§5 of
[TELEMETRY.md](TELEMETRY.md)) is a placeholder until this is confirmed. Do not ship a
percentage read-out without it — a wrong curve is worse than no percentage.

---

## 4. Is the IMU an MPU9250 or MPU6500?

The firmware never reads `WHO_AM_I` (register 0x75) and deliberately logs the vague
string `MPU****` (`console.h:320,332`, `sensors_data.h:69`). Both parts share register
layout and the 0x69 address (AD0 high); only the presence of an onboard AK8963
magnetometer differs.

**How to check:** add a temporary `Serial.println(readRegister(0x75), HEX);` — MPU9250
returns `0x71`, MPU6500 returns `0x70`. If MPU9250, a magnetometer physically exists on
the die but is unused by this firmware.

**Consequence:** if MPU9250 is confirmed, adding magnetometer support (enabling the I²C
bypass at `INT_PIN_CFG` 0x37, addressing AK8963 at 0x0C) becomes a real option for fixing
yaw drift in a later milestone. If MPU6500, that path is closed and yaw must stay
relative-only permanently.

---

## 5. Real BLE throughput and negotiated MTU

The protocol design in [BLE_PROTOCOL.md](BLE_PROTOCOL.md) targets a 244-byte usable ATT
payload (247-byte MTU, the common Android grant) and assumes ~800 B/s aggregate telemetry
fits comfortably. Neither has been measured against this hardware.

**How to check:** with Milestone 1-2 firmware running, use nRF Connect on the target
Android phone(s) to confirm the negotiated MTU, then log actual notification arrival
times against the nominal 10/2/1 Hz targets to see how much jitter the ~100 ms MPU
busy-wait (`position_sensor.h:146-150`) actually introduces.

**Consequence:** if jitter is worse than expected, the app-side SLERP smoothing window
needs tuning, or the sweep interval needs revisiting.

---

## 6. MySat carrier board revision

The README's support matrix (`README.md:36-38`) names only *full support: v.1.5.6+,
partial: v.1.5.2+* — it never states which revision this specific kit is.

**How to check:** look for a silkscreen revision marking on the carrier PCB.

**Consequence:** determines which console commands and pin mappings are safe to assume;
in particular, `README.md:95` notes v.1.5.5+ changed HC-12 compatibility.

---

## 7. Licensing inconsistency (not hardware, but needs a human decision)

`firmware/license.md` is the full GPL v3 text, while `firmware/ino/MySat_main/MySat_main.ino:11`
declares `license: Open Source (MIT) – github.com/mysatkit`. This is a genuine conflict
in the upstream repository, not something introduced here.

**Action:** if this project is ever shared or redistributed beyond personal use, resolve
which license actually governs the upstream code before doing so — this is a legal
question for whoever maintains MySatKit-Firmware, not something to guess at.

---

## Verification log

Fill in as each item is resolved, so the docs above can be updated from "requires
verification" to a stated fact with a date.

| # | Item | Verified | Date | Result |
|---|---|---|---|---|
| 1 | PSRAM present | ☐ | | |
| 2 | Free heap w/ BLE | ☐ | | |
| 3 | Battery chemistry | ☐ | | |
| 4 | IMU part number | ☐ | | |
| 5 | BLE throughput/MTU | ☐ | | |
| 6 | Carrier board rev | ☐ | | |

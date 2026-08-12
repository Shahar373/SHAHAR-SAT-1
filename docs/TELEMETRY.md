# SHAHAR-SAT 1 — Telemetry Reference

Every value the spacecraft can report, its unit, where it comes from in the firmware —
and, just as importantly, what the hardware **cannot** provide.

Wire format is in [BLE_PROTOCOL.md §6](BLE_PROTOCOL.md).

The guiding rule: **no invented telemetry.** Every field below is traced to a line of
firmware. Anything a user might reasonably expect but that does not exist is listed in
§4 rather than quietly filled with a plausible number.

---

## 1. Sensor inventory

| Subsystem | Part | Address | Driver |
|---|---|---|---|
| Environment | Bosch **BME680** | `0x77` | Bosch **BSEC** fusion library (`environment_sensor.h:6`) |
| IMU | InvenSense **MPU9250 or MPU6500** | `0x69` | Hand-written raw I²C (`position_sensor.h:7-12`) |
| Power | TI **INA3221**, 3-channel | `0x40` | `Beastdevices_INA3221` (`power_measure.h:6`) |
| Sun sensors | TI **ADS1015**, 4-channel 12-bit | `0x48` | `Adafruit_ADS1X15` (`ADC.h:6`) |
| Clock | Maxim **DS3231** | `0x68` | `Rtc by Makuna` (`RTC.h:6`) |
| Camera | OmniVision **OV2640** | — | `esp_camera`, XGA, quality 15 (`camera.h:46-50`) |
| Panels | Servo on ATmega328P | I²C `0x08` | Write-only (`control.h:153-163`) |

I²C bus: **SDA = GPIO15, SCL = GPIO13** (`MySat_main.ino:39-40`). No address conflicts.

The IMU part number **cannot be determined from source** — the firmware never reads
`WHO_AM_I` (0x75) and logs the deliberately vague string `MPU****`. See
[HARDWARE_NOTES.md](HARDWARE_NOTES.md).

---

## 2. Available telemetry

All values below are **already resident in RAM**, refreshed by the 500 ms sweep in
`loop()`. BLE reads the cached globals; it never triggers a sensor poll.

### 2.1 Attitude — `mpu_data` (`position_sensor.h:14-17`)

| Field | Key | Type | Unit | Range | Source |
|---|---|---|---|---|---|
| Roll (X) | `r` | float | degrees | [-180, 180] | `mpu_data.roll` — `:153` |
| Pitch (Y) | `p` | float | degrees | [-90, 90] | `mpu_data.pitch` — `:154` |
| Yaw (Z) | `y` | float | degrees | [-180, 180] | `mpu_data.yaw` — `:155` |
| Calibrated | `cal` | bool | — | — | `calibration.valid` — `:21` |

**How they are produced.** Sensor configuration (`position_sensor.h:41-50`): DLPF 44 Hz,
sample rate 200 Hz, gyro ±250 °/s (131 LSB/°/s), accel ±2 g (16384 LSB/g). Each call takes
20 samples paced to 200 Hz and runs a complementary filter with α = 0.95
(`position_sensor.h:128-144`):

```cpp
angle_x += gyro_x * dt;
angle_y += gyro_y * dt;
angle_z += gyro_z * dt;

float accel_pitch = atan2(-accel_x, sqrt(accel_y*accel_y + accel_z*accel_z)) * 180.0f/PI;
float accel_roll  = atan2(accel_y, accel_z) * 180.0f/PI;

angle_x = ALPHA * angle_x + (1.0f - ALPHA) * accel_roll;
angle_y = ALPHA * angle_y + (1.0f - ALPHA) * accel_pitch;
```

**Roll and pitch are accelerometer-corrected and do not drift** — gravity observes them.

**Yaw is the third line only.** No correction term is ever applied to `angle_z`, because
gravity cannot observe rotation about the gravity vector and there is no magnetometer.
Yaw is **pure gyro integration and drifts without bound**. Gyro bias calibration
(`calibrateMPU()`, `position_sensor.h:160-198`, 500 samples averaged, persisted to
`/cal.dat`) slows the drift; it does not remove it.

Practical consequence: expect a few degrees of yaw error per minute even at rest. The app
labels yaw *relative* and offers `ZERO_ATTITUDE`.

**Offsets.** `mpu_data` stores raw angles. Presentation offsets are applied inconsistently
in the original firmware — the web JSON subtracts them (`server.h:1169-1171`), the console
subtracts them (`console.h:323-325`), the CSV logger does not (`data_logger.h:249-251`).
**BLE follows the web/JSON convention.**

**Gating.** When `cal` is false the original JSON emits literal `0`, indistinguishable from
a genuine zero (`server.h:1168`). The BLE protocol omits the fields instead and ships
`cal` so the app can say *"uncalibrated"*.

### 2.2 Power — `ina_data` (`power_measure.h:10-16`)

| Field | Key | Type | Unit | Channel | Source |
|---|---|---|---|---|---|
| Battery voltage | `bv` | float | V | CH1 | measured — `:30` |
| Battery current | `bi` | float | mA | CH1 | measured — `:31` |
| Solar voltage | `sv` | float | V | CH2+CH3 | **computed mean** — `:33` |
| Left panel current | `sil` | float | mA | CH2 | measured — `:35` |
| Right panel current | `sir` | float | mA | CH3 | measured — `:36` |
| Solar power | `sp` | float | mW | — | derived: `sv × (sil + sir)` |

Shunts are 100 mΩ on all three channels (`power_measure.h:20`). The library returns volts
and amps; the firmware multiplies current by 1000 for mA.

**Sign convention: negative `bi` means current flowing *into* the battery — charging.**

`sv` is the arithmetic mean of the CH2 and CH3 bus voltages. **Per-panel solar voltage is
not retained** — the average overwrites it at `power_measure.h:33`.

Negative solar currents are clamped to 0 at the presentation layer only
(`console.h:351-354`); `ina_data` keeps the signed value. BLE ships the raw signed value.

### 2.3 Environment — `bme_data` (`environment_sensor.h:16-25`)

| Field | Key | Type | Unit | Source |
|---|---|---|---|---|
| Temperature | `tc` | float | °C | heat-compensated — `:96` |
| Humidity | `rh` | float | %RH | heat-compensated — `:97` |
| Pressure | `hpa` | float | hPa | Pa ÷ 100 — `:98` |
| Gas resistance | `gas` | float | kΩ | Ω ÷ 1000 — `:99` |
| IAQ index | `iaq` | float | 0-500 | `:94` |
| IAQ accuracy | `iaqa` | int | 0-3 | `:95` |

BSEC subscriptions (`environment_sensor.h:78-86`): IAQ,
heat-compensated temperature, heat-compensated humidity, raw pressure, raw gas.

**Humidity does exist** — the BME680 is the full environmental part, not a BMP280.
"Heat-compensated" means BSEC has removed the sensor's own self-heating from both
temperature and humidity.

IAQ scale (`server.h:1022-1031`): 0-50 excellent, 51-100 good, 101-150 lightly polluted,
151-200 moderately polluted, >200 heavily polluted.

**Warm-up is real: ~5 minutes** (`console.h:310`), and `iaq` is meaningless until
`iaqa >= 1`. BSEC calibration state is persisted to NVS namespace `bsec_state` and
restored at boot (`environment_sensor.h:29-52`), so warm-up is shorter after the first
run.

`bme_data.data_available` **latches true forever** once set (`:101`). It means "data has
been seen at least once", not "the sensor is healthy now".

### 2.4 Sun sensors — `ads_data` (`ADC.h:11-16`)

Shipped on the **Power** characteristic (`7a3e0002`) alongside the EPS fields — see
[BLE_PROTOCOL.md §6.2](BLE_PROTOCOL.md), not a separate characteristic.

| Field | Key | Channel | Direction | Body axis |
|---|---|---|---|---|
| `ph1` | `ph1` | A0 | Left | −Y |
| `ph2` | `ph2` | A1 | Back | −X |
| `ph3` | `ph3` | A2 | Right | +Y |
| `ph4` | `ph4` | A3 | Front | +X |

Directions come from the console labels (`console.h:336-345`), corroborated by the web
GUI's bearing table `const angles = [270, 180, 90, 0];` (`server.h:917`).

**Units: raw ADC counts, unscaled.** With the library default gain (±6.144 V) and a 4-bit
right shift, readings run ~0-2047 at roughly 3 mV/count. The firmware never converts to
volts or lux and no calibration curve exists, so **these must not be labelled lux**.

The sun-direction vector shown in the web GUI is computed in **browser JavaScript**
(`server.h:913-936`), not in firmware. The app computes its own from ph1-ph4.

### 2.5 Time — `rtc_data` (`RTC.h:10-17`)

Six integers: `year_`, `month_`, `day_`, `hour_`, `minute_`, `second_`. **No epoch, no
`time_t`, no ISO-8601 field** in the struct. BLE formats them as
`"YYYY-MM-DD HH:MM:SS"`.

**No timezone exists anywhere.** Time is whatever the user typed at the serial prompt
(`readUARTTime()`, `RTC.h:39-83`, validated to 2000-2100). Treat it as local wall-clock of
unspecified zone. `initRTC()` returns `Rtc.GetIsRunning()` (`RTC.h:19-22`), so unlike the
INA this is a genuine liveness check.

### 2.6 System state

| Field | Key | Source |
|---|---|---|
| Call sign | `cs` | `callSign` — `console.h:13`, default `"MYSAT"`, persisted to `/callsign.txt` |
| Firmware version | `fw` | `FIRMWARE_VERSION` — `console.h:7` |
| Uptime | `up` | `millis() / 1000` |
| Spacecraft mode | `mode` | `spacecraft_mode.h` (new) |
| Panels commanded | `mot` | `stateMotor` — `control.h:14`, EEPROM byte 0 |
| STAR LED | `led` | `stateLight` — `server.h:1422` |
| Logging state | `log` | `getLoggingState()` — `data_logger.h:38` |
| Free heap | `heap` | `ESP.getFreeHeap()` |
| Sensor presence | `hw` | `init_status` — `sensors_data.h:39-46` |
| Frame counter | `fc` | `frameCounter` — `console.h:14` |

Two health caveats:

- **`hw` flags are boot-time only** (`sensors_data.h:48-55`) and never re-evaluated. A
  sensor that fails later still reports present.
- **`hw.ina` is not a real check** — `initINA()` returns `true` unconditionally without
  probing the chip (`power_measure.h:18-22`). A missing INA3221 publishes zeros rather
  than being flagged absent.

---

## 3. Update rates

| Frame | BLE rate | Underlying source rate |
|---|---|---|
| Attitude | 10 Hz | 500 ms sweep — **interpolated between samples** |
| Power | 2 Hz | 500 ms sweep |
| Environment | 1 Hz | BME680 ~1 Hz internal |
| System | on change, ≥5 s | — |

**The 10 Hz attitude rate exceeds the 2 Hz sensor sweep.** This is deliberate: sending the
same value five times would be wasteful, so the firmware notifies at 10 Hz only while the
attitude is actually changing, and falls back to a 2 Hz keep-alive when the satellite is
still. Combined with app-side SLERP, this produces smooth motion without inventing data.

Smoothness ultimately comes from the app's interpolation, not from the sensor rate — the
IMU genuinely updates at 2 Hz as far as the firmware exposes it. Raising the sweep rate
would mean touching `SENSOR_INTERVAL` (`MySat_main.ino:70`) and paying the ~100 ms MPU
busy-wait more often, which would degrade the web server. Deferred.

---

## 4. Not available

Values a user might reasonably expect, and why each is absent. **None of these is faked.**

### Attitude

| Value | Status |
|---|---|
| **Magnetometer (mx/my/mz)** | **Absent.** Only accel+gyro are read (`position_sensor.h:105-112`). The AK8963 I²C bypass (`INT_PIN_CFG` 0x37) is never enabled and address `0x0C` is never addressed. The magnetometer block in `index.html:136-139` is dead code against fields the firmware never emits. |
| **Absolute / compass heading** | **Absent.** Follows from the above. |
| **Raw accelerometer (g)** | Computed as locals (`position_sensor.h:120-122`) then discarded. `struct mpu` has no accel fields. |
| **Raw gyroscope (°/s)** | Same — locals at `:124-126`, discarded. |
| **Quaternion / DMP** | **Absent.** No DMP init, no quaternion math. The app builds a quaternion from Euler angles itself. |
| **IMU die temperature** | Bytes 6-7 of the 14-byte burst are `TEMP_OUT` — fetched at `:105`, never decoded. |
| **Angular velocity** | Not stored. Could be differentiated app-side, but noisily. |

### Power

| Value | Status |
|---|---|
| **Battery percentage** | **Absent.** No SoC math, no lookup table, no coulomb counting. Estimated app-side and labelled *estimated*. Needs hardware verification of chemistry. |
| **Battery chemistry / voltage range** | **Undocumented in firmware.** The only hint is the JS threshold `bV > 4.0` (`server.h:970`), suggesting 1S Li-ion. |
| **Charging state** | **Not a firmware concept.** Browser-only heuristic: `bV > 4.0 && bC < -5.0` (`server.h:970-971`). Inferred app-side and labelled *inferred*. |
| **System / load current** | **Absent.** All three INA3221 channels are allocated. |
| **Per-panel solar voltage** | **Absent.** Averaged away at `power_measure.h:33`. |
| **Battery temperature** | **Absent.** No thermistor. |
| **Energy accumulated (mAh)** | **Absent.** Could be integrated app-side. |

### Mechanisms and other

| Value | Status |
|---|---|
| **Actual panel position** | **Absent.** Open-loop `bool` only. The Nano registers `Wire.onReceive` but never `onRequest` (`MySat_Nano_ATmega328p.ino:65`) — the ESP32 physically cannot query it. No limit switch, no encoder. |
| **Servo angle feedback** | **Absent** on the ESP32. The Nano knows `angle` (`:41`) and can time out early (`:137-140`), and never reports it back. |
| **Light in lux / irradiance** | **Absent.** ph1-ph4 are unscaled counts; no calibration curve. |
| **Sun vector** | Firmware does not compute it (browser-side only, `server.h:913-936`). The app computes its own. |
| **GPS / position / altitude / orbit** | **Absent.** No GPS hardware or code. |
| **Altitude from pressure** | Not computed; no sea-level reference constant exists. |
| **Radio (HC-12) status or RSSI** | **Absent.** No radio firmware at all. `RF_TURN`/`RF_SET` are empty cases (`MySat_Nano_ATmega328p.ino:99-101`). |
| **Timezone / UTC offset** | **Absent.** |
| **Runtime sensor health** | **Weak.** Boot-time only; `hw.ina` is not a real check. |
| **Reset reason / brownout** | **Absent.** No `esp_reset_reason()`, no watchdog, no brownout handling anywhere. |
| **BLE RSSI** | Not spacecraft telemetry — read locally by the app via `readRemoteRssi()`. |

### Adding any of these

Several are cheap firmware changes rather than hardware limits — storing raw accel/gyro
(3 lines each), decoding IMU temperature (2 lines), reading `WHO_AM_I`. They are excluded
from v1 to keep the firmware diff small and reviewable, not because they are hard. The
genuine hardware limits are: magnetometer, absolute heading, panel position feedback,
system current, per-panel solar voltage, and battery SoC.

---

## 5. Derived values

Computed by the app from available fields. Each is labelled in the UI as derived, never
presented as a measurement.

| Value | Formula | Caveat |
|---|---|---|
| Solar power | `sv × (sil + sir)` mW | Computed in firmware |
| Total solar current | `sil + sir` mA | Exact |
| Battery power | `bv × bi / 1000` mW | Exact; sign shows charge/discharge |
| Charging state | `bv > 4.0 && bi < -5.0` | **Inferred** — matches the web UI heuristic |
| Battery % | Voltage curve on `bv` | **Estimated** — needs chemistry verification |
| Sun direction | `atan2` over ph1-ph4 bearings | Uncalibrated raw counts |
| Panel illumination balance | `(sir - sil) / (sir + sil)` | Exact |
| Quaternion | Euler Z-Y-X → quaternion | Exact conversion of drifting yaw |

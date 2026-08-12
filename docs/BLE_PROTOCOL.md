# SHAHAR-SAT 1 — BLE Protocol

Wire specification for the link between the SHAHAR-SAT 1 spacecraft (ESP32-CAM, GATT
server / peripheral) and the SHAHAR-SAT Android app (GATT client / central).

Protocol version **1.0**. Frames carry a `v` field so the app can reject mismatches.

---

## 1. Identity

| Property | Value |
|---|---|
| BLE device name | `SHAHAR-SAT-1` |
| Role | Peripheral / GATT server |
| Stack | NimBLE-Arduino |
| Address type | Public (ESP32 factory MAC) |

**NimBLE, not Bluedroid.** Bluedroid costs roughly 500-700 KB of flash for an equivalent
GATT server; NimBLE is closer to 100-150 KB. The image already carries Wi-Fi, the web
server, the camera driver and the BSEC blob, so this is a fit constraint rather than a
preference. See [ARCHITECTURE.md §1.9](ARCHITECTURE.md).

---

## 2. UUIDs

Fixed for the life of the project. **Never regenerate them** — a changed UUID silently
breaks every installed app. They are defined once in
`firmware/ino/MySat_main/ble_config.h` and mirrored in
`android/.../ble/Uuids.kt`; the two must stay in sync.

All are well-formed RFC 4122 version-4 UUIDs sharing the base
`7a3e****-5b1f-4c9a-9d21-0e6f3a8b4c10`.

### Service

| Name | UUID |
|---|---|
| SHAHAR-SAT Telemetry & Control | `7a3e0000-5b1f-4c9a-9d21-0e6f3a8b4c10` |

### Characteristics

| Name | UUID | Properties | Security | Rate |
|---|---|---|---|---|
| Attitude | `7a3e0001-5b1f-4c9a-9d21-0e6f3a8b4c10` | Read, Notify | Open | 10 Hz |
| Power | `7a3e0002-5b1f-4c9a-9d21-0e6f3a8b4c10` | Read, Notify | Open | 2 Hz |
| Environment | `7a3e0003-5b1f-4c9a-9d21-0e6f3a8b4c10` | Read, Notify | Open | 1 Hz |
| System | `7a3e0004-5b1f-4c9a-9d21-0e6f3a8b4c10` | Read, Notify | Open | On change, ≥5 s |
| Command | `7a3e0010-5b1f-4c9a-9d21-0e6f3a8b4c10` | Write | **Encrypted + authenticated** | — |
| Response | `7a3e0011-5b1f-4c9a-9d21-0e6f3a8b4c10` | Notify | Open | Per command |
| Event | `7a3e0012-5b1f-4c9a-9d21-0e6f3a8b4c10` | Notify | Open | On event |

Each notify characteristic carries a standard Client Characteristic Configuration
Descriptor (`0x2902`) and a User Description descriptor (`0x2901`) with a human-readable
name, so the service is self-describing in nRF Connect.

---

## 3. Advertising

A 128-bit service UUID costs 18 bytes in an advertising payload. With the mandatory flags
field (3 bytes) and the complete local name `SHAHAR-SAT-1` (14 bytes), the total is 35
bytes — over the 31-byte limit.

So the two are split:

| Packet | Contents |
|---|---|
| Advertisement | Flags, complete local name `SHAHAR-SAT-1`, TX power |
| Scan response | Complete 128-bit service UUID |

Android's `ScanFilter.setServiceUuid()` matches against both packets, so filtering by
service UUID works normally — the app does not need to fall back to name matching. The app
**should** filter by service UUID rather than name, because a name is trivially spoofed
and users may rename devices.

| Parameter | Value | Reason |
|---|---|---|
| Advertising interval | 100-250 ms | Desk-powered; discovery latency matters more than idle current |
| Advertising type | Connectable, undirected | Standard |
| TX power | Default | Desk range is a few metres |

Advertising restarts automatically on disconnect.

---

## 4. Connection parameters

| Parameter | Requested | Note |
|---|---|---|
| Connection interval | 15-30 ms | Supports 10 Hz attitude with margin |
| Slave latency | 0 | Telemetry is continuous |
| Supervision timeout | 4 s | Fast disconnect detection |
| MTU | 517 requested | Android commonly grants 247 |

The app requests MTU immediately after service discovery and **must** handle being granted
less. Every frame is designed to fit the pessimistic case.

| MTU granted | ATT payload | Result |
|---|---|---|
| 517 | 514 B | Ample |
| 247 (typical Android) | 244 B | **Design target** — all frames fit |
| 23 (never negotiated) | 20 B | Frames truncate; app shows a protocol error |

**Expect notification jitter.** The firmware's IMU read busy-waits ~100 ms out of every
500 ms (`position_sensor.h:146-150`) and BLE cannot transmit during it, so 10 Hz attitude
is nominal, not isochronous. The app absorbs this with SLERP smoothing rather than
assuming even arrival.

---

## 5. Frame format

**JSON, UTF-8, no trailing newline, one frame per notification.**

### Why JSON

| Frame | Typical size | Rate | Throughput |
|---|---|---|---|
| Attitude | ~50 B | 10 Hz | ~500 B/s |
| Power | ~90 B | 2 Hz | ~180 B/s |
| Environment | ~85 B | 1 Hz | ~85 B/s |
| System | ~180 B | 0.2 Hz | ~36 B/s |
| **Total** | | | **~800 B/s** |

BLE at a 30 ms connection interval sustains several kB/s, so ~800 B/s leaves large margin.
Every frame fits a single 244-byte ATT payload, so there is **no fragmentation and no
reassembly logic on either side**.

This is a deliberate choice of simplicity over premature optimization. A packed binary
format would cut attitude to 13 bytes, but the saving is irrelevant at this data rate and
it would cost readability in nRF Connect, hand-debuggability, and schema flexibility.

**What makes JSON affordable is splitting by rate.** A single combined 25-field frame at
10 Hz would push ~2.5 kB/s and waste most of it re-sending environment data that changes
once a second.

Revisit binary only if a future milestone adds high-rate raw IMU streaming.

### Size discipline

Frame builders write into fixed `char` buffers (256 B) with `snprintf`. Before notifying,
the firmware checks the length against the negotiated MTU; oversize frames are dropped and
logged over Serial rather than truncated onto the air. The System frame is the only one
near the limit — `callsign` is capped at 16 characters.

### Field naming

Short keys, `snake_case`, stable across versions. Fields are **never renamed or
repurposed** — new fields are added, old ones are kept.

### Missing values

**A field whose sensor is absent or invalid is omitted entirely — never sent as `0`.**

The existing web JSON emits literal zeros for missing sensors (`server.h:1131-1134`),
which makes "sensor absent" indistinguishable from "genuinely zero". This protocol does
not repeat that. Each frame carries an explicit health flag, and the app renders an
omitted field as `--`, never as a number.

---

## 6. Telemetry frames

Units and provenance for every field are in [TELEMETRY.md](TELEMETRY.md).

### 6.1 Attitude — `7a3e0001`

```json
{"v":1,"t":128374,"r":12.24,"p":-3.41,"y":182.07,"cal":1}
```

| Key | Type | Unit | Meaning |
|---|---|---|---|
| `v` | int | — | Protocol version |
| `t` | int | ms | `millis()` at sample |
| `r` | float | degrees | Roll, body **X** |
| `p` | float | degrees | Pitch, body **Y** |
| `y` | float | degrees | Yaw, body **Z** |
| `cal` | int | — | `1` if `calibration.valid`, else `0` |

Angles are **offset-corrected** (`value - offset_*`), matching the web JSON convention
(`server.h:1169-1171`) rather than the CSV logger, which ships raw
(`data_logger.h:249-251`).

`r`, `p`, `y` are omitted entirely when `cal == 0` or the IMU is absent.

> **Yaw drifts.** It is pure gyro-Z integration (`position_sensor.h:135`) with no absolute
> reference — there is no magnetometer in this firmware. It is a *relative* heading that
> accumulates error indefinitely. The app must label it as such and offer
> `ZERO_ATTITUDE`. Roll and pitch are accelerometer-corrected by a complementary filter
> (α = 0.95) and do not drift.

**Body axes and rotation order** — the contract the 3D renderer implements:

```
        +Z (yaw axis, up through the top face)
         │
         │
         ●───── +Y (pitch axis, right)
        ╱
      +X (roll axis, forward — the +X face carries ph4 "front")
```

- Right-handed. Positive rotation is counter-clockwise looking down the positive axis.
- Euler order: **intrinsic Z-Y-X** — yaw, then pitch, then roll.
- Ranges: roll ∈ [-180, 180], pitch ∈ [-90, 90], yaw wrapped to [-180, 180]
  (`position_sensor.h:143-144`).
- Sun-sensor bearings, from `console.h:336-345`: ph4 = front (+X), ph2 = back (−X),
  ph3 = right (+Y), ph1 = left (−Y).

The app converts Euler → quaternion **on receipt** and SLERPs toward the target. Filtering
the three angles independently would break at the yaw ±180° wrap and degenerate near
pitch ±90°.

### 6.2 Power — `7a3e0002`

```json
{"v":1,"t":128374,"bv":3.91,"bi":-42.5,"sv":4.82,"sil":31.2,"sir":28.7,"sp":289.4}
```

| Key | Type | Unit | Meaning |
|---|---|---|---|
| `bv` | float | V | Battery voltage, INA3221 CH1 |
| `bi` | float | mA | Battery current — **negative = charging** |
| `sv` | float | V | Solar voltage, **mean of CH2 and CH3** |
| `sil` | float | mA | Left panel current, CH2 |
| `sir` | float | mA | Right panel current, CH3 |
| `sp` | float | mW | Solar power, computed `sv × (sil + sir)` |

`sp` is the only derived value; it is arithmetic on measured fields, not a new hardware
claim.

**Not present, because the hardware does not provide them:**

- **Battery percentage.** No SoC calculation, no chemistry constant anywhere in the
  firmware. The app estimates it from `bv` against a configurable 1S Li-ion curve and
  labels it *estimated*. The curve requires hardware verification.
- **Charging state.** Not a firmware concept. The app infers it exactly as the existing
  web UI does (`server.h:970`): `bv > 4.0 && bi < -5.0`, and labels it *inferred*.
- **System / load current.** All three INA3221 channels are allocated.
- **Per-panel solar voltage.** CH2 and CH3 are averaged in `power_measure.h:33` and the
  individual readings are discarded.

### 6.3 Environment — `7a3e0003`

```json
{"v":1,"t":128374,"tc":26.4,"rh":41.2,"hpa":1013.2,"gas":152.3,"iaq":78.5,"iaqa":2}
```

| Key | Type | Unit | Meaning |
|---|---|---|---|
| `tc` | float | °C | Temperature, heat-compensated |
| `rh` | float | %RH | Relative humidity, heat-compensated |
| `hpa` | float | hPa | Pressure |
| `gas` | float | kΩ | Gas resistance |
| `iaq` | float | 0-500 | Indoor air quality index |
| `iaqa` | int | 0-3 | IAQ accuracy: 0 low, 1 medium, 2 high, 3 verified |

The whole frame is omitted while `bme_data.data_available` is false. **BSEC needs ~5
minutes of warm-up** (`console.h:310`), and `iaq` is meaningless until `iaqa >= 1` — the
app hides the IAQ number until then, matching the web UI (`server.h:1001-1006`).

Note `data_available` latches true forever once set (`environment_sensor.h:101`). It means
*"data has been seen at least once"*, not *"the sensor is healthy now"*, and is not
presented as a liveness indicator.

### 6.4 System — `7a3e0004`

```json
{"v":1,"t":128374,"cs":"MYSAT","fw":"v.1.4","up":128,"mode":"DESKTOP",
 "mot":1,"led":0,"log":0,"heap":94512,"rtc":"2026-08-11 18:55:02",
 "hw":{"bme":1,"mpu":1,"ads":1,"ina":1,"rtc":1,"cam":1},"fc":2841}
```

| Key | Type | Meaning |
|---|---|---|
| `cs` | string | Call sign, ≤16 chars |
| `fw` | string | `FIRMWARE_VERSION` (`console.h:7`) |
| `up` | int | Uptime, seconds |
| `mode` | string | `BOOT`/`NOMINAL`/`SAFE`/`LOW_POWER`/`PAYLOAD`/`DESKTOP` |
| `mot` | int | Solar panels — **commanded** state, `1` = deploy |
| `led` | int | STAR LED state |
| `log` | int | 0 off, 1 active, 2 storage full |
| `heap` | int | `ESP.getFreeHeap()` bytes |
| `rtc` | string | `YYYY-MM-DD HH:MM:SS`, **local wall-clock, no timezone** |
| `hw` | object | Boot-time presence flags, `init_status` |
| `fc` | int | Frame counter |

Two caveats the app surfaces rather than hides:

- **`mot` is what was commanded, not what happened.** The I²C link to the Nano is
  write-only — it registers `onReceive` but never `onRequest`
  (`MySat_Nano_ATmega328p.ino:65`). There is no limit switch and no servo feedback. The UI
  says *"Deployed (commanded)"*. A useful cross-check is that deployed panels under light
  should show non-zero `sil`/`sir`.
- **`hw` flags are set once at boot** (`sensors_data.h:48-55`) and never re-evaluated.
  `hw.ina` is especially weak: `initINA()` returns `true` unconditionally without probing
  the chip (`power_measure.h:18-22`).

RSSI is **not** in this frame — it is a property of the phone's radio and is read locally
via `BluetoothGatt.readRemoteRssi()`.

---

## 7. Command channel

### 7.1 Request — write to `7a3e0010`

```json
{"cmd":"DEPLOY_SOLAR","seq":42}
```

| Key | Type | Required | Meaning |
|---|---|---|---|
| `cmd` | string | yes | Command name, see [COMMANDS.md](COMMANDS.md) |
| `seq` | int | yes | Monotonic counter, 1-65535, wraps |
| `args` | object | no | Command-specific arguments |

Write **with response** (`ATT_WRITE_REQ`), so the app gets transport-level confirmation
before the command has executed. Execution confirmation arrives separately on the Response
characteristic.

Rejected without executing: payload over 200 bytes, malformed JSON, unknown `cmd`, missing
`seq`, or a `seq` equal to the last accepted one.

### 7.2 Response — notify on `7a3e0011`

```json
{"seq":42,"cmd":"DEPLOY_SOLAR","ok":true,"msg":"Deploying solar panels"}
```

| Key | Type | Meaning |
|---|---|---|
| `seq` | int | Echoes the request — the app correlates on this |
| `cmd` | string | Echoes the request |
| `ok` | bool | Accepted and executed |
| `err` | string | Error code when `ok` is false |
| `msg` | string | Human-readable detail, ≤64 chars |
| `data` | object | Command-specific payload |

Error codes are listed in [COMMANDS.md](COMMANDS.md).

### 7.3 Replay protection

The firmware stores the last accepted `seq` per connection and **silently rejects a
repeat** with `ERR_DUPLICATE_SEQ`.

This exists because Jetpack Compose recomposition can re-fire a side effect, and a
resent `DEPLOY_SOLAR` would drive the servo again. Guarding in the UI alone is fragile —
the guard belongs at the protocol level, where it holds regardless of what the client
does. The counter resets on disconnect; the app starts each session at 1.

Separately, `setStateMotor()` enforces its own 2200 ms hardware rate limit
(`control.h:176-187`) matching the Nano's servo power-gating. A command inside that window
returns `ERR_RATE_LIMITED`, and the app shows *"too soon — wait a moment"* instead of
failing silently as the current firmware does.

### 7.4 Events — notify on `7a3e0012`

Unsolicited notifications for state changes the app did not initiate:

```json
{"t":128374,"ev":"MODE_CHANGED","detail":"NOMINAL->DESKTOP"}
```

Event types: `BOOT`, `MODE_CHANGED`, `PANEL_MOVED`, `PHOTO_TAKEN`, `WIFI_UP`, `WIFI_DOWN`,
`LOG_FULL`, `SENSOR_FAULT`.

**Debug output never goes here.** Serial keeps its `logDebug` stream; the Event
characteristic carries only discrete state changes. Firmware debug spam over BLE would
compete with telemetry for airtime.

---

## 8. Security

**Bonding with a static passkey**, per project decision.

| Setting | Value |
|---|---|
| Bonding | Enabled |
| MITM protection | Enabled |
| Secure Connections (LE SC) | Enabled |
| IO capability | `BLE_HS_IO_DISPLAY_ONLY` |
| Passkey | 6 digits, fixed in `ble_config.h` |

Only the **Command** characteristic requires encrypted + authenticated write. Telemetry
stays open for reading.

The reasoning: the threat is someone walking past the desk sending `DEPLOY_SOLAR` or
`REBOOT`, not eavesdropping on the temperature. Requiring pairing on telemetry would make
routine debugging with nRF Connect painful for no security gain, while leaving commands
unprotected would miss the actual threat.

Flow: first connection prompts Android to pair, the user enters the passkey from
`ble_config.h`, Android stores the bond, and subsequent connections are automatic with no
prompt. Bonds survive reboot on both sides.

The default passkey is committed in `ble_config.h` and **should be changed before the kit
sits somewhere public** — a value in a public repository protects nobody. Changing it
requires re-pairing (remove the bond in Android Bluetooth settings first).

`ERR_NOT_AUTHENTICATED` is returned if a command somehow arrives on an unencrypted link.

---

## 9. Error handling

### Firmware

| Situation | Behaviour |
|---|---|
| Malformed JSON in a command | `ERR_BAD_JSON`, connection stays up |
| Payload > 200 B | Rejected before parsing |
| Command queue full | `ERR_BUSY` |
| Frame exceeds MTU | Dropped, logged to Serial, never truncated onto the air |
| Client disconnects | Advertising restarts; `seq` counter resets |
| Sensor absent | Field omitted; `hw` flag reports it |

The firmware never blocks in a GATT callback and never calls `delay()` from BLE context.

### App

| Situation | Behaviour |
|---|---|
| Bluetooth disabled | Prompt to enable, no scan attempt |
| Permission denied | Explain which permission and why, offer Settings |
| Spacecraft not found | Keep scanning with a visible timer, offer manual retry |
| Connection timeout | Retry with bounded exponential backoff — **never a tight loop** |
| GATT failure (status ≠ 0) | Surface the numeric status on the Engineering screen |
| Unexpected disconnect | Show `DISCONNECTED`, auto-reconnect if previously bonded |
| Malformed frame | Drop it, increment a parse-error counter, keep the last good value |
| ESP32 reboot | Detected by `up` decreasing; app clears history and re-reads System |
| Missing field | Render `--`, never `0` |

The app must not crash on any of these. Frame parsing is total: unknown keys are ignored,
missing keys yield `null`, and type mismatches are caught per field.

---

## 10. Version history

| Version | Change |
|---|---|
| 1.0 | Initial protocol — 4 telemetry characteristics, command/response, events |

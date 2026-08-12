# SHAHAR-SAT 1 — Requires Hardware Verification

Every open question in this project that source code alone cannot answer, in priority
order. None of these are guessed at elsewhere in the docs — where an answer isn't in the
firmware, it is listed here instead.

Work through this list once the kit is physically available, ideally before Milestone 1.

---

## Resolved by the official MySat guide

The user supplied the official MySat Kit Guide (assembly + programming + operating
sections) partway through this project. It settled several items that were previously
open questions here — kept as a record of what's now a **documented fact** (cite: the
guide) versus what still needs a **meter or a serial print on real hardware**.

- **ESP32 module and PSRAM (was item #1, highest priority).** The guide specifies the
  onboard computer as *"ESP32-S microcontroller (ESP32-D0WDQ6), 32-bit, 2 cores, 240 MHz,
  < 3.5 MB data storage, 4.5 MB RAM."* A bare ESP32-D0WDQ6 die has 520 KB of internal
  SRAM — nowhere near 4.5 MB. The only way the guide's "4.5 MB RAM" figure makes sense is
  520 KB internal + a **4 MB (32 Mbit) external PSRAM chip**, which is exactly the
  marketing-round-number a module datasheet would use. This is strong evidence PSRAM is
  present, but it's guide marketing copy, not a datasheet page or a register read — **the
  camera.h code already handles either case at runtime** (`psramFound()` check added in
  the BLE layer commit), so nothing is blocked on this; it only means item #1 below is now
  "confirm" rather than "determine from scratch."
- **Partition scheme.** The guide's own upload instructions (Module 2, step 10) say
  verbatim: *"Choose the partition scheme with 2 MB for the file system... Tools >
  Partition Scheme: [...] > NO OTA (2 MB APP / 2 MB SPIFFS)."* This is exactly the scheme
  this project's firmware was switched to after `arduino-cli` testing showed the board has
  no "Custom" option — so the fix wasn't just empirically necessary, it's what MySat's own
  instructions already say to select. No further action needed here.
- **ESP32 board package version — attempted, blocked by an unrelated dependency.**
  The guide pins *"board package version 2.0.17"* (Boards Manager,
  `esp32 by Espressif Systems`); this project's firmware was actually compiled against
  2.0.9 (see `firmware/README.md`). Tried installing 2.0.17 specifically to close this
  gap: `arduino-cli core install esp32:esp32@2.0.17` fails during dependency resolution
  — inspecting the package index shows 2.0.17 added a tool dependency on `dfu-util`
  (DFU-mode USB flashing, for ESP32-S2/S3 native-USB upload) sourced from the *separate*
  default Arduino tool index (`downloads.arduino.cc`), which is blocked in this
  environment the same way Google's Maven was for Android. 2.0.9's tool dependencies are
  all self-contained under the `esp32` packager, which is why it installed cleanly.
  This is a real gap, not a shrug: `dfu-util` is irrelevant to the AI-Thinker ESP32-CAM
  (it uploads over serial via `esptool.py`, already verified working), and 2.0.9 and
  2.0.17 are both in the same 2.0.x line, so the actual compile is very likely identical
  — but that has not been checked directly (the only cross-version `boards.txt` diff done
  in this project was 2.0.9 vs. the much later 3.3.11, for the unrelated partition-scheme
  question in `ARCHITECTURE.md` §4-era work — that does not stand in for a 2.0.9-vs-2.0.17
  comparison). "Very likely identical" is not "verified." **Action item:** re-run
  `arduino-cli compile` against 2.0.17 specifically on a machine where
  `downloads.arduino.cc` is reachable, and pin CI to whichever version that confirms.
- **RBF (Remove Before Flight) electrical behavior.** Previously undocumented anywhere in
  this repo. Now fully specified — see `ARCHITECTURE.md` §7 for the complete writeup
  (inserting the plug opens the circuit and cuts both the battery and USB-C 5V input, but
  **does not** cut power delivered through the PROGRAMER connector; insert-then-remove is
  also the kit's documented normal reset procedure, used routinely during firmware
  upload). This is a resolved fact, not a hardware-verification item, but its
  *consequences* for BLE/SAFE_MODE — that neither is a substitute for it — are worth
  restating: see the RBF section in `ARCHITECTURE.md`.
- **INA3221 / SignalLED board-revision gating.** The guide states INA3221 is *"built into
  the MySat board (A) v.1.5.3+"* and SignalLED *"does not work with boards 1.5.3–1.5.5."*
  Feeds directly into item #6 below (carrier board revision) — narrows what a given
  revision can be expected to support, but the specific revision of *this* kit is still
  unread.
- **Solar servo wiring**, for completeness: Motor connector pin G = ground (brown), pin V
  = voltage (red), pin S = signal (yellow).

None of the above changes any code decision already made — `camera.h` was already written
to check `psramFound()` at runtime rather than assume, and the partition scheme was
already switched to "No OTA." It mainly means the corresponding items below shrink from
"unknown" to "confirm."

---

## 1. Is PSRAM present, and how much? — **highest priority, now "confirm" not "discover"**

`camera_config_t config;` in `firmware/ino/MySat_main/camera.h` already calls
`psramFound()` at init time and picks `FRAMESIZE_XGA`/PSRAM vs. `FRAMESIZE_SVGA`/DRAM
accordingly — so the firmware no longer *assumes* an answer either way. What's still open
is confirming the guide's implied "4 MB PSRAM" figure against the real chip, since
`handleGetPhoto()` peaks at roughly 250-300 KB of live heap for one photo
(`server.h:1318-1329`) and that number only comfortably fits with the ~4 MB the guide's
copy implies, not a smaller/absent PSRAM.

**How to check:** flash the firmware, connect over serial, run `DebugModeOn`, then look
for the `[CAM] PSRAM found` / `[CAM] No PSRAM detected` log line `init_camera()` now
prints, and add `Serial.println(ESP.getPsramSize());` to confirm the size matches ~4 MB.

**Consequence if absent or smaller than expected:** the DRAM/SVGA fallback path already
exists and will engage automatically — but re-check `handleGetPhoto()`'s ~3.4x heap
amplification against actual measured free heap (item #2) before trusting photo capture
under BLE + Wi-Fi load.

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
partial: v.1.5.2+* — it never states which revision this specific kit is. The official
guide adds two more revision-gated facts (see "Resolved by the official MySat guide"
above): INA3221 (power monitoring) is only built into board (A) from **v1.5.3+**, and the
SignalLED (NeoPixel status indicator) **does not work on boards v1.5.3–v1.5.5** — so a
board in that narrow window has power telemetry but a broken status LED.

**How to check:** look for a silkscreen revision marking on the carrier PCB.

**Consequence:** determines which console commands and pin mappings are safe to assume;
`README.md:95` notes v.1.5.5+ changed HC-12 compatibility, and the two items above narrow
it further — if INA3221 telemetry reads as all-zero, checking board revision is now the
first thing to try before suspecting a wiring or code fault.

---

## Day-of commissioning checklist

Everything above, in the order to actually work through it once the kit is in hand and
before Milestone 9's long-duration soak. Cheap/fast/blocking checks first; expensive or
long-running ones last. Each links back to its full writeup above.

1. **RBF electrical behavior** (§4 of `ARCHITECTURE.md`, `TEST_PLAN.md` §13) — confirm
   insert-via-USB-C cuts power, insert-via-PROGRAMER does not, before doing anything else
   that assumes RBF is a safe universal power-off.
2. **Carrier board revision** (item #6) — one silkscreen read, gates what to expect from
   items 3 and everything power/LED-related.
3. **PSRAM present, and how much** (item #1) — highest software-impact item; confirms or
   corrects the guide's implied ~4MB figure.
4. **Free heap, stock firmware** (baseline for item #2, before any BLE code runs at all).
5. **Free heap, after BLE init** (item #2) — with Wi-Fi both off and on.
6. **Firmware compiles against the officially pinned esp32 core 2.0.17** — resolve the
   `dfu-util` gap noted in "Resolved by the official MySat guide" above; this blocks
   trusting the CI pin as final.
7. **Exact IMU `WHO_AM_I`** (item #4) — cheap register read, decides whether magnetometer
   support is ever a real option.
8. **Battery model / chemistry / capacity / voltage limits** (item #3) — read the
   physical cell's label; blocks trusting any battery-% UI.
9. **HC-12 actual configuration** — the firmware never implements `RF_TURN`/`RF_SET`
   (see `ARCHITECTURE.md` §1.7); confirm the module's own DIP/AT-config state directly,
   since nothing in software can report it.
10. **BLE advertising** (`TEST_PLAN.md` §2) — name + service UUID visible in nRF Connect.
11. **Negotiated MTU** (item #5) — on the actual target Android phone(s).
12. **Notification timing / jitter** (item #5) — attitude at fixed 10Hz against the
    ~100ms MPU busy-wait's real-world effect.
13. **Solar servo behavior** (`TEST_PLAN.md` §5.3-5.5) — deploy/retract, rate-limit
    rejection, and honestly assess whether it's silent/audible/visible enough to serve as
    de facto position feedback despite the open-loop I²C link.
14. **Camera + BLE**, then **camera + Wi-Fi + BLE** (`TEST_PLAN.md` §12.2-12.3) — the
    documented highest-risk RAM moment; do these only after 3-5 above are answered.
15. **Long-duration stability** (`TEST_PLAN.md` §14, Milestone 9) — last, and only after
    everything above passes; 30 min → 2 h → 8 h.

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
| 1 | PSRAM present (~4MB implied by guide's "4.5MB RAM") | ☐ | | |
| 2 | Free heap w/ BLE | ☐ | | |
| 3 | Battery chemistry | ☐ | | |
| 4 | IMU part number | ☐ | | |
| 5 | BLE throughput/MTU | ☐ | | |
| 6 | Carrier board rev (also settles INA3221/SignalLED support) | ☐ | | |
| 7 | Firmware compiles against pinned esp32 core 2.0.17 (guide's version, currently only verified against 2.0.9) | ☐ | | |

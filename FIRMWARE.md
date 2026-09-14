# What the R99 firmware contains and does

A map of the ring's own firmware — the image that runs on the ring, not the Android apps in this
repository. It is the counterpart to [PROTOCOL.md](PROTOCOL.md) (what the ring says over
Bluetooth) and [COMMANDS.md](COMMANDS.md) (the command table): this is what is inside the thing
answering those commands.

Recovered on 2026-09-14 by decrypting and disassembling the vendor's own firmware image, and
cross-read against JieLi's open-source SDK, which this firmware is built from (see
[Reproducing this](#reproducing-this)). Claims are marked **verified** where they were read
directly from the image or the ring's own debug log; the rest is inference from strings and the
open SDK and should be treated as such. Firmware differs between R99 batches — this is `V2.32` of
the ring that reports itself as `R11M`.

## The short version

The ring is a **JieLi AC632N** Bluetooth SoC running a **single application built on JieLi's AC63
BT SDK**. It samples an optical (PPG) sensor and an accelerometer, turns those into heart rate,
blood oxygen, an estimated blood pressure, steps, and sleep stages using bundled algorithm blobs,
writes the results to a small on-chip **FatFS** flash store, and hands them out over Bluetooth
when asked via **JieLi's RCSP protocol**. There is no operating system in the general sense and no
user-reachable code beyond what the command table exposes; the running image cannot be read back
off the chip.

## Chip and platform — verified

- **SoC:** JieLi **AC632N** (the `BD19` family), named in the image header. A low-cost BLE-only
  part — no audio, unlike its AC69xx cousins.
- **CPU:** JieLi's own **`q32s`** core (a cut-down `pi32v2`), 32-bit little-endian. Not ARM; there
  is no off-the-shelf toolchain or decompiler for it, which is why only targeted disassembly is
  practical (see [Limits](#limits-of-this)).
- **SDK base:** built on **`fw-AC63_BT_SDK`** (JieLi's open-source AC63-series SDK, which lists
  AC632N as supported). The Bluetooth stack, RCSP profile, OTA, and FatFS are that SDK's code; the
  vendor's health application sits on top and is the only proprietary-only part.
- **On-chip ROM:** the Bluetooth controller (`H4_Controller`, `link_layer`, `btctrler`) and some
  crypto live in the AC632N mask ROM, not in the flashed image — so the image is not
  self-contained and cannot be fully reconstructed.

## Image layout — verified

The update file is a standard JieLi "new-firmware" image (`update.ufw`), chip-key `0x2F1B`, entry
point `0x1E00120`. Its JLFS partitions:

| File / area | What it is |
|---|---|
| `uboot.boot` | second-stage bootloader (SPL), LZ4-compressed |
| `isd_config.ini` | boot/flash config — SPI flash, UART pins (`UTTX PA02`/`UTRX PP00`), reset pin, `UPDATE_JUMP` |
| `app.bin` | **the application** — all of the code below, ~280 KB |
| `cfg_tool.bin` | factory/config tool blob |
| `p11_code.bin` | a small loader stub (`mnt/sdfile/res/p11_code.bin`) |
| `VM` | key-value settings store (goals, user info, monitor switches) |
| `PRCT`, `BTIF`, `EXIF`, `USERIF`, `USERSLEEP` | runtime/bond/interface regions |
| `EXT_RESERVED` | the record store — see [On-device storage](#on-device-storage--verified) |

## Hardware it drives — verified (from driver strings)

- **Optical sensor (PPG):** the image carries drivers for several parts and probes for whichever
  is fitted — `HX3605`/`HX330X` (Tianyihexin), `GH3018` (Goodix), `SC7R30`. This is the green/red/IR
  front end behind heart rate, blood oxygen, and blood-pressure estimation (`ppg G:%d R:%d IR:%d`).
- **Accelerometer:** `LSM6DSO` (ST), `QMA6100P`, or `SC7A2x`/`SC7A20X`/`SC7A21X` — drives steps,
  motion, and the sleep/wear state machine (`gs_task`, "GS Device name").
- **Capacitive touch:** an on-body `tp_wear` read used for wear detection (see below).
- **Charger/PMU:** `pm_is_charging`, `charge_end`, `vbat_percent`, `LowVoltage`/`Charging`/
  `FullyCharged` states.
- **Storage:** SPI NOR flash with a **FatFS** filesystem (`fatfs_record_history`).
- **RTC, UART, timers, PWM** for clock, debug log output, and the LED.

## Tasks / subsystems — verified (from task names)

- **`bphr_task`** — the vitals engine: opens the PPG, runs the HR/BP/SpO2 algorithms, gates a
  measurement on wear.
- **`gs_task`** — the "g-sensor" engine: steps, motion, and the sleep state machine.
- **`rcsp_task`** — the Bluetooth command/RCSP handler (see [BLE interface](#the-bluetooth-interface--verified)).
- **`misc_task`**, **`usr_systimer`** — housekeeping, the midnight tick, record rotation.
- **`btstack` / `btctrler` / `H4_Controller` / `link_layer`** — the JieLi Bluetooth stack.

## What it actually does

### Vitals sampling — verified
`bphr_task` drives the PPG through three bundled algorithm blobs, each stamped with its own
version in the image:
- **heart rate** — "hr algo", with automatic TIA-gain stepping (`++/-- TIA-GAIN`).
- **blood oxygen** — "spo2 algo v0.03", which also decides finger-vs-wrist (`Maybe is on finger
  now…` / `…on wrist now…`) and rejects noisy reads (`Red SNR too low`, `Pls. quiet!!!`).
- **blood pressure** — a "Snd-gen bio-assay algo v0.02" estimate from the pulse waveform, **not** a
  cuff measurement. The vendor library is `YCLIB` (`yclib rev: …`).

A spot measurement runs ~30 s then stops; periodic sampling is scheduled by `auto_monitor_check`
(see [PROTOCOL.md](PROTOCOL.md#automatic-readings-are-stored-never-pushed--verified)). Automatic
readings are **written to the store, never pushed** over Bluetooth.

### Steps and motion — verified
`gs_task` reads the accelerometer for a step count, distance and calories, resets the day's count
at its own (UTC) midnight (`steps-gs_clear_sport_data`), and feeds the sleep and wear logic.

### Wear detection — verified from firmware
Three independent mechanisms, none of which this firmware exposes cleanly over Bluetooth (see
PROTOCOL.md's finger-detection section): a **capacitive `tp_wear`** read that gates on-demand
measurements (`bphr_user_meas_open tp_wear fail`), a **PPG bio-proximity** check (`bio Prox ok`,
`BIO: Wear off!`, `Device wear on now!`), and the **sleep algorithm's** own wear tracking
(`Maybe not wear`). The only wear signal that leaks out is the `04 0E` measurement-result byte and
the charging state.

### Sleep staging — verified
A full on-device state machine in `gs_task`, not a phone-side computation. It moves between
`START → LIGHT → DEEP → REM → AWAKE → END`, logging every transition with timestamps
(`LIGHT->DEEP`, `DEEP->REM`, …), handles naps, and abandons a night when it decides the ring is
off (`Wear off goto START`) or the clock moved (`exit sleep because time change`). The staged night
is stored and handed over on request — the record format is decoded in
[PROTOCOL.md](PROTOCOL.md#stored-sleep--verified).

### Charging and power — verified
`auto_monitor_check` suppresses automatic sampling while `pm_is_charging`; the ring logs
`charge_end … vbat_percent` and tracks `LowVoltage`/`Charging`/`FullyCharged`. Battery level and
charge state are read via `GetDeviceInfo` (`02 00`), not pushed.

### On-device storage — verified
A **FatFS** record store under `EXT_RESERVED`, with separate rolling files for heart rate, blood
pressure, history/comprehensive, sport, sleep, log, reset and power
(`mnt/sdfile/EXT_RESERVED/USERBP`, `…/USERLOG`, `…/USERSPORT`, `…/SLEEPBACKUP`, `…/POWER`). Records
are appended (`fatfs_record_history,total=%d,cur=%d`) and rotated away when full — which is why the
history reads back short unless synced regularly. The firmware's own debug log is readable with
`GetDeviceLog` (`02 08`) and was the single most useful RE source
([PROTOCOL.md](PROTOCOL.md#the-rings-internal-log--verified)).

### The clock — verified, and buggy
The RTC keeps **UTC** and, on this unit, **stops ticking** — every later record inherits the last
time written, and the log shows `missing_midnight_tick!`. A factory reset returns it to
`2020-01-01`. Full write-up in [PROTOCOL.md](PROTOCOL.md#the-rings-clock-stops-and-every-later-record-inherits-the-stopped-time--verified).

### The Bluetooth interface — verified
`rcsp_task` runs two things over BLE:
- **The vendor command table** — the 329 `group/command` frames in [COMMANDS.md](COMMANDS.md),
  parsed by a big dispatch in `app.bin` (`Parse.hr`, `Parse.bp`, `Padding.sleep`, `Parse.log`,
  `Factory.reset`, …). This is the `be94xxxx` channel.
- **JieLi RCSP** on the `ae00` service (`ae01` write / `ae02` notify), used for OTA. RCSP is
  **auth-gated**: `rcsp_bluetooth.c` in the open SDK shows the ring routes every RCSP frame to
  `JL_rcsp_auth_recieve` until a `JL_rcsp_auth` handshake (device AuthKey + BLE link key, gated by
  `BT_CONNECTION_VERIFY`) completes — the `FE DC BA … "pass"` handshake PROTOCOL.md observed.

### Firmware update (OTA) — verified
The OTA group (`OTADownload`/`OtaSend`/`OtaBlock`) is JieLi's RCSP OTA over `ae01`/`ae02`, driven
from the phone by JieLi's `jl_bt_ota` library. It reboots into a loader at `MAC+1` mid-flash. See
[PROTOCOL.md](PROTOCOL.md#updating-the-firmware--jieli-rcsp-ota). (Vitals' own attempt is blocked
on the RCSP auth above — a known todo.)

### Factory reset — verified
`settingRestoreFactory` (`01 0E`, literal `"RSYS"`) wipes the record store and reboots
(`win_factory_reset`, `hand factory reset…`), returning the clock to the factory epoch.

## What it does *not* contain — verified
Applied against the ring's `GetDeviceSupportFunction` bitmap and confirmed by the absence of any
driver or algorithm strings: **no HRV, ECG, body temperature, respiratory rate, stress, blood
sugar, blood fat, or VO2 max.** Those bits are zero and no code backs them — rings in this class
are often marketed as having them. The temperature/HRV fields in the vendor's record structures
read as zero padding.

## Limits of this
- The image is **stripped** — no symbol names survive; functions are named here from log strings
  and behaviour, not from the binary.
- The `q32s` disassembler ([`ghidra-jieli`](https://github.com/kagaimiq/ghidra-jieli)) is early and
  incomplete, so decompilation to C is unreliable; this map is built from strings + targeted
  disassembly + the open SDK, **not** from a recompilable source tree, which is not recoverable.
- Code that calls into the AC632N mask ROM cannot be seen.

## Reproducing this
```
# Decrypt + unpack the vendor image (chip-key is read from isd_config.ini):
python3 fwunpack_newfw.py R11M-APP-DFU-KEY1-V2.32.ufw     # kagaimiq/jl-misctools
strings -n 4 <unpack>/files/app.bin                        # the readable surface

# Disassemble app.bin as q32s at base 0x1E00120 in Ghidra with the kagaimiq/ghidra-jieli module.
# Cross-reference framework behaviour against Jieli-Tech/fw-AC63_BT_SDK (open source, AC632N).
```
Images live at `staticpage.ycaviation.com/firmware/R11M-APP-DFU-KEY1-V<ver>.zip` (V2.18, 2.32,
2.33, 2.34 exist); the manifest is `R11M.plist`.

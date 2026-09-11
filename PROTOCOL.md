# R99 ring Bluetooth protocol

Recovered on 2026-08-03 from a ring reporting itself as `R99 8D43`, by capturing the vendor
app (`com.zhuoting.healthyucheng`, "SmartHealth") with the Android HCI snoop log and replaying
the frames from this app. Every claim marked **verified** was reproduced by sending our own
frame and observing the ring behave; anything else is inference and should be treated as such.

Ring firmware differs between R99 batches. Re-capture before trusting this on another ring.

## Frame format — verified

```
group | command | total length (uint16 LE) | payload | CRC (uint16 LE)
```

`total length` counts the entire frame, header and CRC included, so a frame with no payload is
6 bytes. **A frame whose length field is wrong does not get ignored — the firmware drops the
connection.** That is the one genuinely destructive mistake available here, and it is
recoverable by reconnecting.

The ring echoes `group` and `command` back in its reply, so requests and responses pair up
without needing sequence numbers.

### CRC — verified

CRC-16/CCITT-FALSE: polynomial `0x1021`, initial value `0xFFFF`, no input or output
reflection, no final XOR. Appended little-endian. Recovered by brute-forcing the parameter
space against 11 captured frames; the solution was unique and reproduces every captured frame
byte-for-byte.

```python
def crc16(buf):
    reg = 0xFFFF
    for b in buf:
        reg ^= b << 8
        for _ in range(8):
            reg = ((reg << 1) ^ 0x1021) & 0xFFFF if reg & 0x8000 else (reg << 1) & 0xFFFF
    return reg
```

## GATT map

| Handle | Characteristic | Role |
|---|---|---|
| `0x000C` | `be940001` | **command channel** — write frames here, replies indicate back |
| `0x0011` | `be940003` | ring-initiated data (live heart rate) |
| `0x001D` | `fea1` | 10-byte status frame, pushed every ~2 s |
| `0x006E` | `2a37` | standard SIG Heart Rate Measurement |
| `0x0082` / `0x0084` | `ae01` / `ae02` | vendor authentication handshake |

`2a38` (Body Sensor Location) reads `03` = Finger.

## Commands

Written to `be940001`. Payload excludes the header and CRC, which are computed.

| Group/cmd | Payload | Meaning | Status |
|---|---|---|---|
| `01 00` | `YY YY MM DD HH MM SS 00` | set clock, year uint16 LE | verified by matching wall clock — **destructive, see below** |
| `03 2F` | `01 <type>` | start measurement: type `00` heart rate, `01` blood pressure, `02` blood oxygen | **all three verified** |
| `03 2F` | `00 00` | stop measurement | inferred |
| `02 00` | `47 43` (`"GC"`) | `GetDeviceInfo`, 30-byte reply | captured, not decoded |
| `02 01` | `47 46` (`"GF"`) | `GetDeviceSupportFunction`, 66-byte capability bitmap | captured, not decoded |
| `02 0C` | none | `GetNowStep` — steps `[0..2]`, calories `[3..4]`, distance `[5..7]`, all LE | **verified** |
| `02 25` | none | `GetPowerStatistics`, 38-byte reply | captured, not decoded |
| `01 0C` | `<on> <minutes>` | `settingHeartMonitor` — periodic heart rate | **verified**, accepted |
| `01 26` | `<on> <minutes>` | `settingBloodOxygenModeMonitor` — periodic SpO2 | **verified**, accepted |
| `01 1C` | `<on> <minutes>` | `settingBloodPressureMonitor` — periodic BP | **refused** — replies `01 1C 07 00 FC CB 44`, not implemented on this firmware |
| `03 0E` | `<on>` | `AppControlTakePhoto` — arm the shutter gesture | **verified** |
| `01 02` | `<type> <goal uint32 LE> <2 more>` | `settingGoal` | from the SDK, untested |
| `01 03` | 4 bytes | `settingUserInfo` | from the SDK, untested |

Battery is **not** in `GetPowerStatistics`. The SDK reads it from the `GetDeviceInfo` (`02 00`)
reply: payload `[4]` is `deviceBatteryState` and `[5]` is `deviceBatteryValue`, which gives 98%
on this ring. The `64` byte in `GetPowerStatistics` is something else.

Without `settingHeartMonitor` the ring measures only when asked, which is why its history reads
back empty.

### Setting the clock clears the step count, not the readings

`SettingTime` (`01 00`) zeroes the day's steps. It leaves the stored readings alone — measured
either side of a write, and set out under "Setting the clock does not erase the readings"
below. This section previously said it wiped the history; that was wrong, and it is why Vitals
does not implement the command.

The ring is supposed to set its own clock at midnight. On this one it does not — see "The clock
does not tick" — so writing it is the only way to get a usable timestamp, at the cost of a step
count the ring clears at midnight anyway. The debugger still marks it `risky` so it prompts
before sending.
| `05 02`/`04`/`06`/`1A` | none | stored history: sport, sleep, heart, blood oxygen | verified as reachable; all returned zero records |

The two literal payloads `"GC"` and `"GF"` are copied from the vendor app. Queries the ring does
not implement answer with a single payload byte `FC` — on this ring that covers
`GetSleepStatus`, `GetMeasurementFunction` and `GetRingSizeAndColor`.

The full 329-command table, lifted from the vendor SDK, is in [COMMANDS.md](COMMANDS.md).

### Ring-initiated

| Group/cmd | Payload | Meaning | Status |
|---|---|---|---|
| `06 01` | `<bpm>` | live heart rate, one byte | **verified** — matches `2a37` reading for reading |
| `06 02` | `<percent>` | live blood oxygen, one byte | **verified** — 93–99% observed |
| `06 03` | `<systolic> <diastolic> …` | live blood pressure | **verified** — 115/75, 116/76 observed |
| `04 0E` | `<type> 01` | measurement complete, `type` as above; app acknowledges with `04 0E` + `00` | **verified** |
| `05 15` | six bytes a record: seconds-from-2000 uint32 LE, a spare byte, then bpm | stored heart rates, following a `05 06` query | **verified** — 25 records read back, including two taken overnight |
| `05 17` | eight bytes a record: the same timestamp, a spare byte, systolic, diastolic, then a third value | stored blood pressure, following `05 08` | **verified** — `80 E5 03 32 00 74 4C 4E` is `00:49:20  116/76` |
| `05 18` | twenty bytes a record: the same timestamp, then the percentage at byte 9 | stored blood oxygen, following `05 09` | **verified** — 93–99%, on timestamps matching the pressure records |
| `05 80` | varies | history block push, sent after the records | captured, not decoded |

`06 03` carries a third byte tracking close to the systolic value (`4B`, `4C`, `4E`) and then
eleven zero bytes. It is probably pulse, but that is unconfirmed, so this app prints only the
two values it is sure of and leaves the rest visible as hex.

Blood pressure from an optical ring is estimated from the pulse waveform rather than measured.
Treat it as a trend, not a reading, and never as a medical device.

### Automatic readings are stored, never pushed — verified

**The ring says nothing when it measures on its own.** With `settingHeartMonitor` (`01 0C`) set
to fifteen minutes, six hours of listening produced no `06 01` outside a measurement the app
itself started: an app that only subscribes sees a day of taps and nothing between them, however
faithfully the ring is sampling. The readings are all there, in the ring's own store, and only
come out when asked for with `05 06`:

```
SEND Health_HistoryHeart -> be940001: 05 06 06 00 83 20
  25 records stored              <- the 05 06 reply, a uint16 count
  2026-08-04 00:49:20  99 bpm    <- then the records, as 05 15 pushes
  2026-08-04 03:25:03  83 bpm
```

Nobody taps a ring at 03:25. Anything wanting the automatic readings has to ask for them; there
is nothing to wait for.

`2a37` is a trap on the way to finding this out. It re-notifies on the ring's ~90 s housekeeping
tick whether or not anything was measured, repeating the last number it holds: 82 bpm arrived
unchanged eighteen times in twenty-five minutes as `04 52`. Writing down every push fills the
day with one stale reading a minute and looks, at a glance, exactly like working automatic
sampling. Only a *changed* value there is evidence of a measurement.

Blood pressure comes back from `05 08` and blood oxygen from `05 09`, the whole-history query —
**not** from `05 1A`, `Health_HistoryBloodOxygen`, which this firmware write-acknowledges and
then never answers. `05 09` returns the vendor's twenty-byte comprehensive record, of which this
ring fills in the oxygen percentage and leaves every other field zero.

### Ask for a bigger MTU first — verified

**Without `requestMtu`, the history is invisible.** The default ATT payload is twenty bytes;
a day of stored readings comes back as one 150-byte frame. The ring answers the query — the
count arrives, because the count reply is small enough — and then the records themselves are
simply never delivered:

```
<-- 05 06 10 00 19 00 01 00 00 00 96 00 00 00     25 records, 150 bytes to follow
                                                   …and nothing follows
```

The vendor app and the debugger both negotiate up (`requestMtu(517)`, granted 185) before
reading anything, which is why this never showed there. A client that skips it sees a ring
reporting readings it will not hand over, and reads exactly like a ring that has recorded
nothing at all.

## Reading a heart rate — verified end to end

1. Connect to the ring (it is paired, so it does not advertise; connect by address).
2. Subscribe to `2a37` and `be940003`.
3. Write `03 2F 08 00 01 00 4F 1B` to `be940001`.
4. The ring acknowledges `03 2F 07 00 00 EE 99` and its LED begins flashing.
5. Readings arrive for ~30 s on both `2a37` and as `06 01` frames on `be940003`.
6. The ring sends `04 0E 08 00 00 01 FB 52` when finished.

**The `2a37` sensor-contact bit is unreliable on this firmware** — it reports "not detected"
even while returning genuine, varying readings. Treat a non-zero value as the signal, not the
contact flag. Note also that `2a37` holds its last value indefinitely when idle, so a plausible
number does not mean a fresh one; only trust readings during an active measurement.

## Steps — verified

`fea1` is the live activity counter, not a static frame. Its payload matches the reply to
`GetNowStep` field for field:

```
fea1        07 | 41 00 00 | 2F 00 00 | 04 00      pushed every ~2 s
GetNowStep     | 41 00 00 | 04 00 | 2F 00 00 …    on request
```

`0x41` = 65 steps, with distance and calorie counters alongside. Watching `fea1` is therefore
enough to track steps live without polling.

**The counter resets at the ring's midnight, which is 00:00 UTC** — the clock is set in UTC,
see "Send UTC, not local time". In British summer time that is 01:00, so the first hour of the
phone's day still carries yesterday's total. Vitals' own history shows the drop on three
consecutive nights, 5–7 September 2026, every time between 00:57 and 00:59 BST (`873 → 0`,
`78 → 0`, `1742 → 0`). A day's steps therefore cannot be "today's highest total minus
yesterday's last": after the reset that is zero until the wearer has out-walked yesterday. Sum
the rises between readings instead, and treat a drop as a reset.

## Firmware

The ring is a Nordic nRF5x running Nordic DFU (`no.nordicsemi.android.dfu` appears throughout
the vendor app). Vendor images are served from `https://staticpage.ycaviation.com/firmware/`.

**Firmware cannot be read off the ring.** The OTA group is `OTADownload`, `OtaSend` and
`OtaBlock` — all push. No read-out command exists anywhere in the 329-command table, Nordic DFU
is upload-only, and nRF52 parts ship with readback protection that also blocks SWD. Obtaining
the vendor's image from their server is possible; extracting the running image is not.

## What this ring actually supports — verified

`GetDeviceSupportFunction` (`02 01`, payload `"GF"`) returns a 60-byte bitmap. The vendor SDK
decodes it in `DataUnpack.saveDeviceSupportFunctionData`, one flag per bit, most significant
bit first, which maps 174 named flags onto the payload. Applied to this ring's reply
(`F9 09 00 00 00 00 0C D8 10 04 01 B2 B6 00 40 0F …`), 31 are set:

**Present:** StepCount, Sleep, RealData, FirmwareUpdate, HeartRate, Blood, BloodOxygen,
MoreSport, FactorySetting, BloodLevel, SkinColor, WeChatSport, TodayWeather, TomorrowWeather,
BloodPressureCalibration, ManualTakePhoto, RealExerciseData, TestHeart, TestBlood, TestSpo2,
WatchScreenBrightness, PauseExercise, BatteryInfoUpload, and the sport modes MountainClimbing,
Running, Riding, RopeSkipping, Walking, Yoga, Golf, Dance.

**Absent** — worth stating plainly, because rings in this class are often sold as though they
do these: HRV, ECG, body temperature, respiratory rate, stress/pressure, blood sugar, blood
fat, VO2 max, and every associated alarm. Those bits are zero. No protocol work will produce
that data; the firmware does not implement it.

## Stored history — verified

History queries take no arguments. The reply opens with a uint16 LE record count, and when
there is data the ring follows up by pushing `Health_HistoryBlock` (`05 80`) unprompted:

```
--> 05 06 06 00 83 20                                   Health_HistoryHeart
<-- 05 06 10 00 | 01 00 | 01 00 00 00 06 00 00 00       count = 1, then the record
<-- 05 80 0C 00 | 01 00 06 00 03 E8                     the block itself
```

`Health_HistorySport` and `Health_HistorySleep` answered `00 00` — zero records — on both this
app and the vendor's. The ring reports `Sleep` as a supported feature, so this is an empty
store rather than an unsupported query: nothing had been recorded yet. Sleep has since filled
in and is decoded below; sport is still empty.

Sport has stayed empty through every capture since, days with long walks in them included, and
the firmware's own log shows `Delete Sport Record` alongside the other housekeeping — the ring
records a session only for a sport mode it was explicitly put into, and rotates that away like
everything else. There is no event to subscribe to either: no flag in `GetDeviceSupportFunction`
recognises an activity, and the sport bits that are set are modes to enter, not things reported.
Vitals therefore finds workouts on the phone instead, from the cadence between step pushes — see
`WorkoutDetector` in the Vitals sources.

### Stored sleep — verified

`Health_HistorySleep` (`05 04`) answers with a count and then pushes the nights under `05 13`,
the same query-then-push shape as heart (`05 06` → `05 15`) and pressure (`05 08` → `05 17`).

It is the only history here that is **not fixed-width records**. Each night is a 20-byte header
carrying its own total size, then one 8-byte entry per stage:

| offset | size | field |
|---|---|---|
| 0 | 2 | unknown, `AF FA` in the capture |
| 2 | 2 | total size of this record, header included |
| 4 | 4 | night started, seconds from 2000-01-01 |
| 8 | 4 | night ended |
| 12 | 2 | deep-sleep count, or `FFFF` to mark the newer header |
| 14 | 2 | REM seconds if `FFFF`, otherwise the light-sleep count |
| 16 | 2 | deep total — seconds if `FFFF`, otherwise minutes |
| 18 | 2 | light total, same units as deep |
| 20… | 8 each | stage entries: type (1), started (4), length in seconds (3) |

Stage types: `F1` deep, `F2` light, `F3` REM, `F4` awake. `F4` is the SDK's own — `DataUnpack`
counts it as a waking — and the other three were pinned by arithmetic rather than by guessing:
summing the entries of each type reproduced the header's three totals exactly.

A night is bigger than one BLE frame and is **cut mid-entry**, so the frames must be joined
before parsing. The first night this ring gave up, on 2026-08-05:

```
--> 05 04 06 00 …                                      Health_HistorySleep
<-- 05 13 B6 00 AF FA DC 00 24 62 05 32 9F AA 05 32 …  first 176 bytes of a 220-byte record
<-- 05 13 32 00 32 E8 00 00 F2 74 93 05 32 B5 06 00 …  the remaining 44, starting mid-entry
<-- 05 80 0C 00 02 00 DC 00 00 8C 39 B1                2 packets, 220 bytes in total
```

Which decodes as 02:53:24 to 08:02:39 UTC, 25 stages, deep 43m, light 3h05m, REM 1h20m — and
the ring's own log describes the same night as `sleep size=284,items=1,packets==2` with
`forms: 33`, which is 20 + 33 × 8. Two independent descriptions of the same layout.

## The ring's internal log — verified

`GetDeviceLog` (`02 08`) streams the firmware's own debug log as plain ASCII, several entries
per frame. Nothing in the vendor app surfaces this. It gives up:

- the firmware version, `V2.32`, stamped on every line;
- **full sleep staging** — `LIGHT->DEEP`, `DEEP->REM`, `REM->LIGHT`, `LIGHT->WAKE`, with the
  duration of each stage;
- the on-device filesystem, `fatfs_record_history,total=28,cur=27` — the ring runs FatFS and
  keeps a rolling record store;
- housekeeping: `Delete BP Record`, `Delete Sport Record`, and `steps-gs_clear_sport_data`
  zeroing the step count at midnight.

That explains the empty history. The ring computes sleep staging perfectly well; it simply
rotates records away. `Health_HistorySleep` returning zero is a store that has been cleared,
not a missing feature.

Answering all 70 readable commands, 40 reply `FC` (not implemented). `GetDeviceMac` and
`GetDeviceName` reply `FE`, a different code, so they likely want an argument.

Turning on `settingHeartMonitor` took `Health_HistoryHeart` from one record to four, and a day
of it took the same query to twenty-five. That store is the only place the automatic readings
exist — see "Automatic readings are stored, never pushed" above.

### The ring's clock stops, and every later record inherits the stopped time — verified

A run of records sharing a timestamp is **not** a measurement session, which is what it first
looks like. It is the ring's clock having stopped: every reading taken afterwards is written
with the last time the clock knew.

Proved directly. At 23:50 a heart rate was measured on demand — the ring reported 86, 86, 87,
87 bpm live and finished normally — and the record it appended to the store was:

```
8D AC 04 32 00 57      87 bpm, stamped 13:58:37 UTC
```

Ten hours late, on the same timestamp as the nineteen records before it. The ring's own log ends
at `13:58:37 fatfs_record_history,total=37` with nothing after it, though writes plainly kept
happening. So the timestamps are decoded correctly — `13:53:27` and `13:58:37` in the firmware
log match the records exactly, the ring keeping UTC where the phone shows BST — and the clock
behind them simply stopped.

Which means:

- **Records sharing a timestamp must not be collapsed into one reading.** They are separate
  measurements that have lost their times, not samples of one measurement.
- **Automatic readings may well be happening on schedule.** They pile onto the frozen timestamp
  where they are indistinguishable from each other, which reads as a ring that stopped
  measuring at teatime.
- Nothing can be trusted about *when* a stored reading was taken once the clock has stopped.

**The clock does not tick. It holds whatever was last written to it.** Setting it and then
measuring at a known moment shows the record carrying the time of the *write*, not of the
reading:

| clock written | measurement taken | record stamped |
|---|---|---|
| 23:57:11 | 23:58–23:59 | 23:57:11 |
| 00:01:49 | 00:03:34 | 00:01:49 |
| — | 00:09:52 | 00:01:49 |

The last two are the decisive pair: two measurements six minutes apart, one timestamp between
them. The clock is not merely wrong, it is stopped.

**A factory reset fixes it.** After `01 0E` with `"RSYS"` and setting the clock again, a
measurement finishing at 00:40:29 stored `ED 34 05 32 00 5B` — stamped 00:40:29, to the second,
eleven minutes after the clock was written rather than inheriting it. The same run also stored
**one** record for a thirty-second measurement, not eighteen. So a stalled RTC is what made a
day of separate readings look like one crowded second, and the readings were probably arriving
on schedule the whole time. If stored timestamps ever bunch up again, suspect the clock before
concluding the ring has stopped measuring.

The ring's own log can be read back with `GetDeviceLog` (`02 08`) and is the only way to see
what the ring believes the time is — its entries are stamped from the same RTC, so a write
shows up there immediately (`23:01:49 exit on time change: 0`) and nothing appears afterwards
however much happens. There is no `GetTime` anywhere in the 329-command table.

So 13:58:37 was not when the ring stopped measuring — it was the last time anything set its
clock, and every reading since inherited it. On a ring in this state the stored timestamps
carry no information at all beyond "after the last clock write", and readings that pile onto
one second may be a full day of automatic sampling rather than a single burst.

The log shows the ring struggling with time by itself too: `missing_midnight_tick!`, and `exit
sleep because time change` five times over one night.

### Setting the clock does not erase the readings — verified

PROTOCOL used to state that `SettingTime` (`01 00`) clears the stored history, and Vitals
refuses to implement the command on that basis. Measured before and after a clock write:

| | before | after |
|---|---|---|
| steps | 1837 | **0** |
| stored heart records | 26 | **26** |

It clears the step counter — which the ring zeroes at midnight anyway — and leaves the reading
store untouched. The earlier claim conflated the two, on the evidence of
`steps-gs_clear_sport_data` in the firmware log, which is about steps and says nothing about
readings.

**Send UTC, not local time.** The ring keeps UTC: its log prints `13:58:37` for records that
read back as `14:58:37`. Writing local time puts every subsequent reading an hour into the
future for half the year, which looks plausible enough to miss — it was caught only by
measuring at a known moment and reading the record back.

`GetSensorSamplingInfo` (`02 15`), which would say what schedule the ring thinks it is on,
replies `FC`, not implemented.

## Refusal codes and required arguments

A one-byte payload is the ring saying no. `FC` means the firmware does not implement that
command; `FE` means it rejected the request, typically for a missing argument; `FB` came back
from `OpenFactory` (`08 09`) and appears to mean the mode is locked. `01` is a refusal too,
where `00` is success — the SDK documents exactly that pair for `settingRestoreFactory`, and it
is easy to misread as data.

Thirteen commands carry a fixed literal argument in the SDK, sent as ASCII:

| Command | Argument |
|---|---|
| `GetDeviceInfo` | `"GC"` |
| `GetDeviceSupportFunction`, `GetMeasurementFunction`, `GetAlgorithmicLicense`, `GetTerminalConf` | `"GF"` |
| `GetDeviceName` | `"GP"` |
| `GetDeviceUserConfig` | `"CF"` |
| `GetRealBloodOxygen` | `"IS"` |
| `GetCurrentAmbientLightIntensity` | `"JT"` |
| `GetCurrentAmbientTempAndHumidity` | `"KU"` |
| `GetSunGoldConf` | `"GC"` |
| `settingRestoreFactory` | `"RSYS"` |

Supplying the correct argument did **not** change any refusal: everything that answered `FC`
without it answers `FC` with it, so those really are absent from this firmware rather than
merely mis-called. `GetDeviceName` still answers `FE` even given `"GP"`.

`settingRestoreFactory` needing `"RSYS"` is a deliberate interlock — a factory wipe cannot be
sent by accident. **Verified end to end.** Without it the ring answers `01`, a refusal, to an
empty payload and to every guessed argument; with it:

```
--> 01 0E 0A 00 52 53 59 53 1B 83
<-- 01 0E 07 00 00                      success, then the link drops as the ring reboots
```

Its own log then reads `win_factory_reset 0003`, followed by entries dated `2020-01-01
00:00:00` — the RTC returns to the factory epoch, so the clock must be set again afterwards.
The bond survives; the ring reconnects on its own.

**This one really does erase the readings**, unlike `SettingTime`: `Health_HistoryHeart` went
from twenty-nine records to `no records stored`. Sync anything worth keeping to the phone first.
`2a37` is cleared too, so it stops rebroadcasting the value it was holding.

### Reading the debugger's log without screenshots

The debugger writes every frame it sends or receives, decoded and in hex, to
`files/protocol-log.txt`, so a whole session comes back as text:

```
adb shell run-as uk.co.r99companion cat files/protocol-log.txt | tail -40
```

Much faster than screenshotting the log view, and it catches what has scrolled away. `NOTIFY
2a37: 04 00 -> 0 bpm, NOT on the finger` is how to tell the ring is off the hand, which
otherwise looks like a ring that has stopped measuring.

**Read the SDK before guessing a payload.** Every argument above comes from the vendor's
`YCBTClient`, where each call is one line naming the command number and the exact bytes —
`settingRestoreFactory` is `sendSingleData2Device(270, new byte[]{82, 83, 89, 83})`, and 270 is
`0x010E`. Decompiled copies are on GitHub (`auroraphtgrp01/ble-sleeping`), or `jadx` the vendor
APK. Guessing costs hours and teaches nothing; the table is right there.

## Not yet decoded

- **The `05 80` block payload.** Shape is `01 00 06 00` then two varying bytes (`03 E8` here,
  `22 F8` in the vendor capture).
- **The third byte of `06 03`**, and the 30-byte `GetDeviceInfo` and 40-byte
  `GetPowerStatistics` replies beyond the battery percentage.
- **The `ae01`/`ae02` handshake.** Magic header `FE DC BA`, then 16-byte encrypted blobs, then
  literal ASCII `"pass"`. Not required for anything above — the command channel answered our
  frames without it.

## Privacy note

The vendor app uploads health data to `web-api.ycaviation.com` (`upheart`, `upblood`,
`upsleep`, `upsport`, `upHrv`, `upmac`). Nothing in this repository sends a reading anywhere. The
only network request either app makes is Vitals asking GitHub, once a day, whether a newer release
is out, and that can be switched off.

# R99 smart ring (Android)

Two Android apps for the generic **R99** health ring, which reports itself internally as
**R11M**. One takes the ring apart; the other wears it. They install side by side, and neither
has any network access at all.

The protocol was recovered by capture and replay rather than documentation. See
[PROTOCOL.md](PROTOCOL.md) for the wire format and what has been verified against hardware, and
[COMMANDS.md](COMMANDS.md) for the vendor SDK's full 329-command table.

## Two apps

|  | **R99 Ring Debugger** (`:app`) | **Vitals** (`:vitals`) |
|---|---|---|
| Package | `uk.co.r99companion` | `uk.co.r99vitals` |
| For | working out what the ring does | using what it does |
| Interface | one scrolling console, XML views | six tabs, Compose and Material 3 |
| Ring commands | all 329, searchable | the handful a health app needs |
| Runs when closed | no | yes, a foreground service holds the link |
| Keeps history | a frame log, shareable as a file | readings and workouts, on the phone |
| Health Connect | no | writes heart rate, SpO₂, blood pressure, steps |
| Finds the ring | scan, or a hardcoded address | pick once, then remembered |

The short version: **the debugger is the microscope, Vitals is the product.** Everything Vitals
does confidently, the debugger proved first.

### R99 Ring Debugger — the protocol console

A debugging tool, not a health app. It exists to drive the ring directly, read what it will tell
you, and show the wire traffic while it happens.

- connects straight to a paired ring by address, since a paired ring stops advertising;
- shows link state, firmware version, battery, and live sensor values as they arrive;
- triggers heart rate, blood oxygen and blood pressure measurements on demand;
- reads and writes ring settings: automatic monitoring, the clock, step goal, wearer details;
- exposes **all 329 SDK commands**, searchable, with the destructive ones marked and confirmed;
- decodes `GetDeviceSupportFunction` into the vendor SDK's named capability flags, so what the
  firmware actually implements can be read off directly;
- pulls the ring's own internal firmware log, which carries sleep staging, charge cycles and
  power events that no health app surfaces;
- logs every frame, decoded where known and raw where not, to screen and to a file.

Use it when a reading looks wrong, when a new ring revision turns up, or when something in
PROTOCOL.md needs proving again. Its output is evidence.

### Vitals — the everyday app

What you leave installed. It assumes the protocol is known and gets on with recording.

- **Today**, then a tab each for heart rate, SpO₂, blood pressure, steps and workouts;
- each vital tab carries its own chart, statistics and day-by-day history;
- steps are drawn as 24 hourly bars against a clock, and listed hour by hour underneath, each
  hour opening to show the quarter hours inside it;
- a settings page for who is wearing the ring — name, sex, age, height and weight, which the
  ring itself uses to work out distance and calories — plus the step goal and reading interval;
- metric or imperial, though the ring is always told metric;
- light and dark, following the system;
- a foreground service keeps the Bluetooth link open so readings arrive while the app is closed
  — the ring measures on its own schedule and pushes results, so the work is staying connected,
  not polling;
- sets the ring's automatic monitoring interval and a step goal;
- workouts record as whole sessions, with the sport, duration and heart curve kept intact rather
  than averaged into the day;
- every reading is written to a CSV in the app's own storage, because the ring's internal store
  is small and rotates records away as it fills — history cannot depend on the ring remembering;
- optionally hands readings to Health Connect, so other apps on the phone can use them.

Health Connect is on-device inter-process communication, not a network service. Vitals declares
no `INTERNET` permission at all, which is a stronger guarantee than promising not to use one.

## Build it

Both apps come out of one Gradle build. Either route produces the same APKs.

**Android Studio:** install the current stable release, choose **Open**, select this folder, and
accept the SDK components it offers.

**Command line:** needs a JDK 17 and an Android SDK with platform 37. Gradle fetches its own
distribution, AGP and build-tools. Point it at the SDK, then build:

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug              # both apps
./gradlew :vitals:assembleDebug      # or just one
```

```
app/build/outputs/apk/debug/app-debug.apk        the debugger
vitals/build/outputs/apk/debug/vitals-debug.apk  Vitals
```

Install with `adb install -r <apk>`. Different package names, so both can sit on the phone at
once.

Toolchain: Gradle 9.6.1, AGP 9.3.1 with its built-in Kotlin, `compileSdk` 37, `minSdk` 26.
`targetSdk` stays at 36 deliberately — moving it changes Bluetooth and foreground-service
runtime behaviour, which is not worth doing without the ring in hand to retest.

## Use it

A real Android phone, with developer mode and debugging enabled. Bluetooth LE cannot be properly
tested in the emulator. Charge the R99 and keep it beside the phone.

**Vitals:** run it, grant Bluetooth, pick your ring from the list — the strongest signal is
normally the one on your hand. It is remembered after that. Set a monitoring interval so the
ring measures on its own; without one it only measures when asked, which is why a fresh ring's
history reads back empty.

**Debugger:** run it, grant Bluetooth, tap **Find my R99 ring** and pick the ring. Leave it
connected for a minute so pushed packets accumulate, then use **Share protocol log**.

## Important limits

R99 rings are sold under several names and firmware revisions, and their Bluetooth protocol can
differ between batches. Re-capture with the debugger before trusting PROTOCOL.md on another
ring.

Blood pressure from an optical ring is estimated from the pulse waveform, not measured. Treat it
as a trend. Do not use any of these readings for medical decisions.

The ring does not implement HRV, ECG, temperature, respiratory rate, stress or blood sugar —
those capability bits are zero, whatever the listing said. No protocol work will produce that
data.

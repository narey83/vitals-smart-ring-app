# Changelog

One version covers the whole repository. Both apps are built from `r99.version` in
[`gradle.properties`](gradle.properties), and each release is tagged `v<version>`. See
"Versions and releases" in the [README](README.md#versions-and-releases).

## 0.2.0 — 11 September 2026

Steps now turn up when they should.

### Fixed

- **The day's step count read zero after the ring's midnight.** The ring resets its counter at
  00:00 UTC, which is 01:00 in British summer time. The app took the day's highest reading minus
  yesterday's last, so the tile, the notification and the Steps headline stayed at nothing until
  you had out-walked yesterday. A day is now the sum of what the counter rose by, with each reset
  counted as one. Health Connect gets the same fix.
- **The background collector could connect and never hear a step.** It sent its Bluetooth
  subscriptions on a timer without checking they took. They now go one at a time, and a watchdog
  subscribes again, or reconnects, when the step count goes quiet.
- **A dropped link could stay dropped for hours.** Reconnecting now waits in the Bluetooth
  controller rather than on a timer that stops while the phone sleeps.
- **Nothing collected after a phone restart or an app update** until the app was opened.

### Added

- The step count is asked for every five minutes, as well as listened for.
- The Steps tab says when nothing has been heard from the ring. The notification says when it
  is out of reach, and a quiet notification follows after two hours.
- A log of what happened to the Bluetooth link, in `files/link-log.txt`.
- A one-time request to be left off battery optimisation, and a Settings row to change it.
- **Update check.** Once a day Vitals asks GitHub whether a newer release is out, and says so in
  Settings and with a quiet notification. It can be switched off. This is the first version with
  the `INTERNET` permission, and the check is the only thing that uses it. No reading ever leaves
  the phone.
- The developer and the source repository in Settings → About.
- One version number for both apps, and this changelog.

## 0.1.0

The first version: the protocol debugger, and Vitals with heart rate, blood oxygen, blood
pressure, steps, sleep and workouts.

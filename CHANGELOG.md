# Changelog

One version covers the whole repository. Both apps are built from `r99.version` in
[`gradle.properties`](gradle.properties), and each release is tagged `v<version>`. See
"Versions and releases" in the [README](README.md#versions-and-releases).

## 0.2.2 — 11 September 2026

Setup asks for everything the app needs in one go.

### Added

- **One-time setup when the ring first connects**, one dialog after another: Network and Sensors
  on Android builds that make them permissions you grant (GrapheneOS, for one), then to be left
  off battery optimisation, then Health Connect ("Fitness and wellness"). Anything already
  granted, or not a permission on the phone, is skipped.
- **Check now** asks for the Network permission if it is off, and the update check reports
  "Network permission is off" rather than failing to find GitHub. That was why the first
  background check on a GrapheneOS phone came back "unable to resolve host".

### Changed

- Release downloads are named `vitals-<version>.apk` and `ring-debugger-<version>.apk`, and
  releases are titled "Vitals <version>". The 0.2.0 and 0.2.1 releases were renamed to match.
- The README and its graphics call the project Vitals Smart Ring App. They no longer claim the
  app has no internet permission, and the README explains why the update check needs it.

## 0.2.1 — 11 September 2026

The repository is now **vitals-smart-ring-app**. The app is still Vitals.

### Changed

- The update check and the Settings → About link use the new address,
  `github.com/narey83/vitals-smart-ring-app`. 0.2.0 still finds its updates through GitHub's
  redirect from the old name, and this is the release it will find.
- CI requires the signing key only where releases are published, so builds on the home Gitea
  pass without it.

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
- CI on GitHub Actions and on the home Gitea. It tests and builds both apps on every push, and a
  `v*` tag publishes the GitHub release with the APKs, signed with the key that makes them
  install as updates.

## 0.1.0

The first version: the protocol debugger, and Vitals with heart rate, blood oxygen, blood
pressure, steps, sleep and workouts.

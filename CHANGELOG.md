# Changelog

One version covers the whole repository. Both apps are built from `r99.version` in
[`gradle.properties`](gradle.properties), and each release is tagged `v<version>`. See
"Versions and releases" in the [README](README.md#versions-and-releases).

## Unreleased

### Changed

- **Finger on/off is detected faster.** The wear probe now quickens to every 15 seconds while the
  state is changing or just after connecting, and eases back to every 45 seconds once it holds, so
  taking the ring off or putting it on is noticed within a probe or two rather than up to a minute.
  A probe the link glitched on is retried straight away instead of after a full wait, and the probe
  is given a little longer to hear a slow finger, so an on-finger ring is never briefly misread as off.

### Fixed

- **Workout heart rate stays with the workout.** Readings taken during a workout were written
  into the day's readings as well, so a run's hour of high heart rates sat among the scheduled
  resting ones and pulled up the day's averages and trends. A workout's readings now go to the
  workout only, including any the ring stored during it, and the day's readings are only the
  scheduled ones and the ones you take. A workout also keeps its heart rate when scheduled heart
  readings are switched off, which used to drop it. Workout heart rate goes to Health Connect
  with its workout instead of among the day's readings.
- **Pace no longer carries on through a stop.** Stood at a crossing, the pace shown was the pace
  from before the stop, since nothing new arrived to replace it. It now clears after half a minute
  without moving.

- **A workout you start keeps recording with the app closed.** It used to live in the Workout
  screen's own memory, so it ended whenever Android reclaimed the screen, as it often does during a
  run with the phone in a pocket. The collector now runs it, as it already ran detected walks, and
  writes it down as it goes. A collector restarted mid-session picks the session up again, readings
  included, and starts the ring measuring again once it reconnects. This is groundwork for GPS routes,
  which need a session that outlives the screen.
- **Readings the ring took while out of reach arrive without opening the app.** The ring measures
  on its schedule and keeps the results in its own store until asked, and only the open app used to
  ask, so a long enough spell away could see them rotated off the ring first. The collector now asks
  on every reconnection and every half hour.
- **A reading stamped in the future no longer splinters the ones after it.** A record the ring
  stored while its clock ran ahead sorted after every live reading, so each new heart rate became a
  row of its own rather than settling into the last, several a second during a workout. Readings
  now settle against the newest one that has actually happened, and such a record is not shown as
  the latest.

### Added

- **Walks, runs and rides record their route** from the phone's own GPS (not Google's location
  service, so it works without Google apps). Location is asked for the first time one is started,
  and followed only while that workout is running. The route stays on the phone, in a file of its own.
- **Distance and pace, live on the workout's notification** ("1.24 km · 5:32 /km", or speed for a
  ride), in kilometres or miles as set. A phone standing still does not add distance: positions are
  averaged over a few seconds and count only once they have moved clearly past the GPS's own stated
  accuracy, and jumps nobody could make, such as reflections off buildings, are dropped. Until the
  GPS has a good enough fix it says "Waiting for GPS".
- **The Workout screen shows the route.** While a walk, run or ride is going, a card shows the
  distance, current and average pace (speed for a ride) and the shape of the route so far. Each
  finished session lists its distance and pace, and **Show route** opens the shape of the route
  drawn without a map, its splits per kilometre or mile, and moving time against the whole. A
  route can be deleted from there, with a second tap to be sure, keeping the session's distance.
- **Pace and heart rate on one timeline**, under a finished workout's route: when the pace
  dropped on a hill and the heart rate rose anyway. Heart readings in a workout now keep the time
  they were taken; workouts saved before this show the route and splits without the chart.
- **Share a route as GPX**, from **Show route** on a finished workout, to any app that opens one
  (OsmAnd, Strava and the like), which is how a route gets onto a real map. Nothing is sent
  unless you pick an app to send it to.
- **Workouts go to Health Connect**, with the other readings, when you send them: each as an
  exercise session of the right sport, with its route where one was kept and Health Connect's
  separate route permission is allowed, and its distance. Sending again replaces a workout rather
  than adding it twice.
- **Settings → Workouts chooses which sports record a route**: walks, runs and rides, all on
  unless turned off.
- **Setup asks for location**, on a page of its own before finding the ring, saying to choose
  "While using the app", which is all a route needs. It can be skipped; a walk started later asks
  again.

## 0.4.1 — 14 September 2026

The firmware update actually flashes now.

### Fixed

- **Ring firmware updates work end to end.** 0.4.0's flash stalled at the start: the ring will
  not talk to the update channel until an authentication handshake has run, which the update
  library skips by default (the vendor app relies on its main connection having authenticated
  already; Vitals connects fresh, so it must do the auth itself). Turning that on, plus reading
  the progress figure correctly and reconnecting to the ring's loader at the right address, makes
  the flash complete. Proven by re-flashing the installed version on a real ring.

### Added

- **Test update connection** and **Re-flash current version** (Settings → Ring firmware). The
  first runs the auth and reads the ring's info without writing anything — a safe rehearsal. The
  second re-flashes the version already installed, the least risky real write, for proving the
  update works before trusting a genuine upgrade. Both keep the same brick warning: ring on the
  charger, phone beside it.

## 0.4.0 — 14 September 2026

Vitals can update the ring's firmware.

### Added

- **Ring firmware updates**, in Settings → Ring firmware. Vitals checks the maker's server for a
  newer build for this ring, downloads it, and — behind a plain warning — flashes it over
  Bluetooth. The R99 is a JieLi AC632N, so the flash is driven by JieLi's own OTA library
  (bundled in `vitals/libs`) rather than hand-rolled; see [PROTOCOL.md](PROTOCOL.md)'s "Updating
  the firmware". Only the maker's static host is asked, and only when you tap Check.

  **The flash is experimental and not yet proven on hardware.** It rewrites the ring's own
  software, and a Bluetooth link dropped partway through — most likely at the ring's mid-flash
  reboot — can leave it unusable. Keep the ring on its charger and the phone beside it. Note that
  most rings will simply be told they are current: the maker publishes no general update for the
  R11M, only a build gated to specific rings.

### Fixed

- PROTOCOL.md and the README described the ring as a Nordic chip using Nordic DFU. It is a JieLi
  AC632N, and its update path is JieLi's authenticated RCSP OTA over the `ae01`/`ae02`
  characteristics — the "authentication handshake" those docs could not place. Recovered by
  disassembling the firmware; the docs are corrected and the update path documented.
- The JieLi update library would have added external-storage and task-reordering permissions to
  the app; these are stripped in the manifest, so the update feature does not widen what Vitals
  can do — the image is written only to the app's own cache.

## 0.3.0 — 14 September 2026

The ring measures on a timer whether or not it is on a finger, and while it sits in its charging
case, so Vitals now tells a reading of the wearer from one of the case or the air.

### Added

- **Readings pause while the ring is on the charger.** The collector asks every couple of
  minutes; going on or off the charger is written to the history and shown on the notification.
  Heart rate, blood oxygen and blood pressure measured while charging are dropped, and any the
  ring stored meanwhile are dropped when its history is read back. A reading you take yourself is
  always kept.
- **Readings pause while the ring is off your finger.** This firmware answers no wear-status
  command, but a measurement started off the finger is refused in about a second — the one
  on-finger signal it gives over BLE. The collector probes on it every minute off the
  charger and pauses the automatic readings when it comes back unworn. Recovered by
  disassembling the ring's firmware; see [PROTOCOL.md](PROTOCOL.md).
- **The Today page shows whether the ring is on a finger**, beside the battery: a fingerprint
  reading "on finger" or "off finger", hidden while charging or disconnected.

### Fixed

- The `04 0E` measurement-complete frame's second byte is a result code — `01` measured, `02`
  refused for want of a finger — not the literal `01` PROTOCOL.md once recorded from only ever
  having captured successful measurements. The debugger now decodes an off-finger abort as
  "refused — not on a finger", and PROTOCOL.md's finger-detection section is rewritten from the
  firmware rather than from the vendor SDK's command list alone.

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

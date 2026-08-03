# R99 Companion (Android)

A free, local-first starter companion for the generic **R99 / SmartHealth** health ring. It does not contain analytics, advertising, accounts, or a subscription.

## What this first version does

- asks only for Bluetooth permissions;
- scans for nearby Bluetooth Low Energy devices, closest first;
- connects to the selected result;
- shows the ring's Bluetooth GATT services, characteristics, and readable bytes on screen;
- subscribes to every notifying characteristic, so live packets the ring pushes are logged with timestamps.

The last two points are the purpose. R99 firmware does not publish a documented data protocol. The service map, the byte log, and the timing of the pushed packets are the evidence needed to build the decoder for heart rate, SpO2, sleep, steps, and battery without relying on SmartHealth or Ringlo.

## Build it

Either route produces the same APK.

**Android Studio:** install the current stable release, choose **Open**, select this folder, and accept the SDK components it offers.

**Command line:** needs a JDK 17 and an Android SDK (platform 35, build-tools 35.0.0). Point Gradle at the SDK, then build:

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use it

1. Connect a real Android phone with developer mode and USB debugging on. Bluetooth LE cannot be properly tested in the emulator.
2. Run the app. Charge the R99 and keep it beside the phone.
3. Tap **Find my R99 ring** and grant Bluetooth access.
4. Pick your ring from the list; the strongest signal is normally the one on your hand.
5. Leave it connected for a minute so pushed packets accumulate, then use **Share protocol log**.

## Important limits

The app can prove a device connection but cannot correctly label its raw health bytes until we inspect a capture. R99 versions are sold under several names and firmware revisions, so their Bluetooth protocol can differ. Do not use its readings for medical decisions.

## Next build step

With a service map from your particular ring, we can add a targeted protocol decoder, local history database, and an optional Health Connect export.

# R99 Companion (Android)

A free, local-first starter companion for the generic **R99 / SmartHealth** health ring. It does not contain analytics, advertising, accounts, or a subscription.

## What this first version does

- asks only for Bluetooth permissions;
- scans for nearby Bluetooth Low Energy devices;
- connects to the selected result;
- shows the ring's Bluetooth GATT services, characteristics, and readable bytes on screen.

The final point is intentional. R99 firmware does not publish a documented data protocol. The service map and byte log are the evidence needed to build the decoder for heart rate, SpO2, sleep, steps, and battery without relying on SmartHealth or Ringlo.

## Use it

1. Install the current stable Android Studio, then select **Open** and choose this folder.
2. Let Android Studio install the Android SDK/Gradle components it offers.
3. Connect an Android phone (developer mode + USB debugging) or choose a real Android device; Bluetooth LE cannot be properly tested in the emulator.
4. Run the app. Charge the R99 and keep it beside the phone.
5. Tap **Find my R99 ring** and grant Bluetooth access.
6. When it connects, copy or screenshot the **Bluetooth protocol log** and share it here.

## Important limits

The app can prove a device connection but cannot correctly label its raw health bytes until we inspect a capture. R99 versions are sold under several names and firmware revisions, so their Bluetooth protocol can differ. Do not use its readings for medical decisions.

## Next build step

With a service map from your particular ring, we can add a targeted protocol decoder, local history database, and an optional Health Connect export.

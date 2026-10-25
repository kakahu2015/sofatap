# HostTap Android TV / AOSP TV receiver (test build)

This module restores the retired TV receiver as a **development and device-qualification target**. It is not currently a Google Play release target.

## Compatibility target

The test build is intentionally broader than standard Google TV / Android TV:

- `android.software.leanback` is optional, not required.
- `android.hardware.type.television` is optional, not required.
- The app exposes both `LEANBACK_LAUNCHER` and ordinary `LAUNCHER` entries.
- A touchscreen is not required.
- Minimum Android API level: 23 (Android 6.0).
- Google Play services are not required by the receiver.

The goal is to allow side-loading on Google TV, standard Android TV, and China-market Android/AOSP televisions or boxes whose vendor firmware does not advertise the official TV feature flags. Installation/startup compatibility does **not** guarantee that every vendor firmware allows the same AccessibilityService behavior or that every third-party TV application accepts accessibility-driven actions.

API 23 is also a deliberate security floor: the receiver stores its persisted TLS/private pairing identity under an AES key generated in Android Keystore using `KeyGenParameterSpec`, which is available from Android 6. Lowering below API 23 would require a different at-rest key protection design rather than just changing `minSdk`.

Android 6/7 use the pre-Android-8 service start path and a notification without channels; Android 8+ keeps the foreground-service and notification-channel path. The updater also avoids Android-8-only unknown-source APIs on Android 6/7. CI lint is expected to reject any remaining unguarded API-level assumptions.

## Build

From `android/`:

```sh
./gradlew :receiver:assembleDebug
```

The development APK is written to:

```text
receiver/build/outputs/apk/debug/receiver-debug.apk
```

To compile the instrumentation APK and run JVM tests/lint as CI does:

```sh
./gradlew \
  :receiver:assembleDebug \
  :receiver:assembleDebugAndroidTest \
  :receiver:testDebugUnitTest \
  :receiver:lintDebug
```

## Install on a TV / box

Enable Android developer options and network/USB ADB for the test device, then:

```sh
adb install -r receiver/build/outputs/apk/debug/receiver-debug.apk
```

Open **HostTap/Sofatap** from the TV launcher. Standard Android TV launchers should see the Leanback entry; vendor/AOSP launchers can use the normal launcher entry.

If a vendor launcher hides side-loaded applications, launch the test build directly with ADB:

```sh
adb shell am start -n com.chekusu.sofatap.tv/.MainActivity
```

That launcher limitation is separate from whether the receiver itself can run on the device.

## Required device setup

The receiver can pair and start its network service without Accessibility permission, but native pointer/navigation control requires the user to enable the app's AccessibilityService in the TV settings. The app does not bypass that system approval.

On heavily customized China-market firmware the Accessibility settings page may be renamed, hidden, or guarded by an additional vendor permission. Record that as a device-specific compatibility result rather than weakening the receiver permission model.

If IME-based text testing is needed, enable/select the bundled HostTap/Sofatap input method through TV settings or ADB only on a test device.

## Test flow

1. Install and launch the receiver on the TV / box.
2. Confirm that the pairing QR screen appears.
3. Enable the AccessibilityService when native control testing is required.
4. Pair a compatible HostTap controller using the TV pairing QR code.
5. Verify connect/disconnect, pointer movement, click, scrolling/navigation and protected-screen behavior.
6. Test launcher, system settings and several third-party TV applications separately.
7. Record the TV brand/model, Android version/API level, whether the launcher entry is visible, whether Accessibility can be enabled, and which applications accept control.

Suggested China-market qualification matrix: Xiaomi/Redmi TV or Mi Box, TCL, Hisense, Skyworth/Coocaa, Konka and at least one generic AOSP box. Treat each firmware version as a separate result because vendor restrictions can change independently of Android API level.

## Practical compatibility tiers

- **Tier A:** Google TV / certified Android TV — expected baseline.
- **Tier B:** Android/AOSP TV with official TV flags omitted — install and launch through the ordinary launcher path.
- **Tier C:** heavily customized vendor firmware — side-load/ADB launch may work, while Accessibility or background-service policy can still block full control.

## Scope

Restoring this module does not re-enable it as a production/store-supported platform. The purpose of this branch is to make a broad range of Android-based TV hardware testable again and to collect compatibility evidence before deciding whether TV support should return as a product feature.

# HostTap Android TV / Google TV receiver (test build)

This module restores the retired Android TV receiver as a **development and device-qualification target**. It is not currently a Google Play release target.

## Compatibility target

The receiver targets the standard Android TV Leanback environment rather than a Google-TV-only API:

- `android.software.leanback` is required.
- The launcher entry uses `LEANBACK_LAUNCHER`.
- A touchscreen is not required.
- Minimum Android API level: 26.

This includes Google TV devices and other Android TV televisions/boxes that expose the Leanback TV framework. Vendor firmware may still differ in AccessibilityService behavior and in whether third-party applications accept synthesized actions.

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

Open **HostTap/Sofatap** from the TV launcher.

## Required device setup

The receiver can pair and start its network service without Accessibility permission, but native pointer/navigation control requires the user to enable the app's AccessibilityService in Android TV settings. The app does not bypass that system approval.

If IME-based text testing is needed, enable/select the bundled HostTap/Sofatap input method through Android TV settings or ADB only on a test device.

## Test flow

1. Install and launch the receiver on an Android TV / Google TV device.
2. Confirm that the pairing QR screen appears.
3. Enable the AccessibilityService when native control testing is required.
4. Pair a compatible HostTap controller using the TV pairing QR code.
5. Verify connect/disconnect, pointer movement, click, scrolling/navigation and protected-screen behavior.
6. Test several third-party TV applications separately; successful launcher/settings control does **not** prove that every vendor/player app accepts accessibility-driven input.

## Scope

Restoring this module does not re-enable it as a production/store-supported platform. The purpose of this branch is to make generic Android TV hardware testable again and to collect compatibility evidence before deciding whether TV support should return as a product feature.

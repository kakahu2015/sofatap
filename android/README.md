# Sofatap Android app

## HostTap phone controller

The HostTap Android phone app provides the computer remote and reviewed text composer. It follows the iOS remote screen's device header, touch surface, shortcut controls and expandable composer. QR scanning uses Android's camera permission flow; text drafts use the system keyboard selected on the device. This app does not include the KeyTrans translation keyboard.

From `android/`, build its APK with `./gradlew :hosttap:assembleDebug`. The APK is written to `hosttap/build/outputs/apk/debug/hosttap-debug.apk`.

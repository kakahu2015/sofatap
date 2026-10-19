# HostTap for Android

HostTap is the Android phone controller for the Windows, macOS and Linux computer receiver. The screen follows the iOS remote layout: paired-computer header, touch surface, shortcut bar and an expandable reviewed-text composer. Android's selected system keyboard is used for the local draft; this app does not install an input method or translate text.

## Build

From `android/`, build the APK with:

```sh
./gradlew :hosttap:assembleDebug
```

The APK is written to `hosttap/build/outputs/apk/debug/hosttap-debug.apk`.

## Pair and control

Open the computer's HostTap receiver and scan its pairing QR code, or paste its pairing link. Camera access is requested only when scanning. The link is encrypted with Android Keystore before it is saved. The WSS client checks the paired leaf certificate fingerprint, normal certificate validity dates and the certificate's host name before sending the bearer token.

Slide one finger on the touch surface to move the pointer and tap to click. Two fingers scroll the computer; three fingers scroll this page. Use the shortcut bar for computer keys. The settings button switches the directional controls between key presses and a held mouse-joystick mode. Text remains in a local draft until you review and send it.

An uncertain text send is blocked until you check the computer and explicitly allow another send. Text is never replayed during reconnection. Pairing credentials, draft text and delivery state remain on the phone.

# KeyTrans for Android

KeyTrans is an Android phone/tablet app and system keyboard. It ports the iOS `AIChatKeyboard` surface and translation flow, and includes the HostTap phone-to-computer controller. The Sofatap Android TV receiver remains a separate module in `:receiver`.

## Build

From `android/`, build the APK with:

```sh
./gradlew :keytrans:assembleDebug
```

The APK is written to `keytrans/build/outputs/apk/debug/keytrans-debug.apk`.

## Set up

1. Install KeyTrans and open it.
2. Enter the HTTPS endpoint, model, native language, other language and provider API key. The key is encrypted with a key held by Android Keystore; it is not included in logs or exported in Android backups.
3. Choose **Open keyboard settings**, enable KeyTrans, return, then choose **Select KeyTrans keyboard**.
4. Focus a text field and select **Translate** or **Type** in the keyboard toolbar.

The Translate panel translates clipboard text in either direction and shows the result before insertion. The Type panel keeps a local draft, supports direct insertion, and translates the draft from native language to the other language. Both panels cap requests at 1,500 Unicode code points. Translation requests use HTTPS and the configured OpenAI-compatible Chat Completions or Responses endpoint. The keyboard sends a request only after an explicit translate action, except when **Translate text before the cursor when opened** is enabled.

As on iOS, **Foreign→Native** is a preview action and **Native→Foreign** can be sent. Password fields are excluded from AI requests. Dictation, App Store purchase gates, translation tone profiles and language auto-detection are not part of this Android module.

## HostTap computer control

Open the **Computer** tab and scan the QR code shown by the Windows, macOS or Linux HostTap receiver. You can also paste its `sofatap://pair` link and choose **Save and connect**. Camera permission is requested only when scanning; QR decoding happens on-device, and only a valid HostTap pairing link is accepted. The link is encrypted with Android Keystore before it is saved. The WSS client checks the paired leaf certificate fingerprint, normal certificate validity dates and the certificate's host name before sending the bearer token.

The remote surface supports pointer movement, left/right click, two-finger scrolling, a held left-button drag, common computer keys and a review-before-send text draft. Draft text remains on the phone when disconnected. An uncertain send is blocked until the user checks the computer and explicitly allows another send; it is never replayed during reconnection. Live text-field synchronization and dictation are not included in this first Android controller.

After enabling the KeyTrans input method, select it while the HostTap draft field is focused to translate text in place, review the result, then explicitly send the draft to the computer.

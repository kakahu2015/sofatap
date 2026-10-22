# Sofatap Privacy Policy

Effective date: October 14, 2026

Sofatap is provided by CHEKUSU INC. This policy covers the Sofatap iPhone app, its desktop receiver and the experimental LG webOS preview described below. Sofatap lets you control a computer you pair with your phone. You do not need a Sofatap account.

## Your devices communicate directly

Mouse actions, keyboard commands and text travel directly between your iPhone and the paired computer using an encrypted connection with a pinned certificate. Sofatap does not provide a cloud relay, receive these inputs on a company server, or include advertising or third-party analytics SDKs.

The receiver uses your computer's operating-system input and accessibility interfaces. Text you enter in another application is then handled by that application and its services under their own policies. For example, submitting a search in a browser can send it to the search provider.

## Pairing and camera

The camera is used when you choose to scan the receiver's pairing QR code. Sofatap does not save or upload the camera images. The code contains the receiver's network address, a pairing credential and a certificate fingerprint. The app can save multiple pairings, their device names, local nicknames and the selected device identifier. These are stored in the iPhone Keychain with device-only, unlocked-device access. Local nicknames do not rename the computer or TV. The receiver stores its credentials locally using Windows account protection or restricted local files on Mac and Linux.

A pairing code grants control of the computer. Keep codes and pairing links private. Removing the pairing in the app deletes that phone's saved pairing. Quit the receiver to stop accepting connections. Removing a pairing from one phone does not revoke copies of that code held elsewhere.

## TV development preview

The experimental LG webOS preview connects directly to a TV you select on the home network. It stores saved TVs' addresses, approved certificate fingerprints and client keys in a separate device-only, unlocked-device Keychain collection. Pointer and navigation commands go to the TV over pinned encrypted connections; they are not sent to CHEKUSU. The preview does not collect TV screenshots or text, and has no TV microphone or keyboard feature. Remove a TV in the device-management sheet, or use **Forget TV** in its native pairing screen, to remove that phone's TV credentials. This removes local pairing only; the TV's own controller-authority settings are separate. Physical TV model qualification remains pending.

## Text and live editing

Sofatap can send typed or dictated text to the focused application. With a compatible receiver and supported field, opening the editor imports the field's complete text and selection to the phone. A receiver focus hint may open this editor automatically. Edits, deletions, selection changes and partial speech transcripts then synchronize to the computer. Protected and read-only fields cannot start live editing.

Imported live text is held in the phone's memory and is not automatically saved as a recovery draft. If you switch devices and explicitly choose to allow the retained text to be sent again, that text becomes a local review-and-send draft and is saved for recovery. Switching devices alone never sends the retained text to the new device. The receiver temporarily holds bounded text-bearing responses in connection memory to handle duplicate requests. Background focus detection sends only field-state metadata, not field contents or screenshots. Sofatap does not log field text or send it to a company server.

For unsupported fields or older receivers, the app keeps a separate review-and-send draft on the phone. This draft and delivery markers remain in local app preferences so text can survive an app switch or restart. In this mode, text is sent when you tap Send. Use Clear Draft while in the review-and-send editor to clear the local draft. In an active live editor, deleting text changes the computer's field instead.

## Microphone and speech recognition

The microphone starts only after you tap the microphone button and grant the required permissions. Sofatap's built-in dictation requires supported on-device speech recognition. Audio is processed on the iPhone; Sofatap does not record it to a file, upload it, or fall back silently to cloud recognition. The recognized text follows the live-editing or review-and-send behavior described above. Capture stops when you stop dictation, leave the app, or the recording is interrupted.

If on-device recognition is unavailable for your device or language, you can use the keyboard. iOS system dictation and third-party keyboards are separate services with their own permissions and data practices.

## Your choices

- Manage camera, microphone, speech and local-network permissions in iPhone Settings. Camera access is optional if you paste a complete pairing link. Microphone and speech access are optional if you type.
- Remove saved pairings from Sofatap's device-management sheet before removing the app if you want to delete their Keychain entries.
- Clear a saved fallback draft in its review-and-send editor. Deleting the app removes its local app preferences; operating-system backups are managed through your device settings.
- Quit the receiver or revoke its operating-system input permission to stop remote control.
- Sofatap has no hosted account or company-side archive of your remote-input text or microphone audio to delete.

## Support and external links

Support is available through the [Sofatap issue tracker](https://github.com/chekusu/sofatap/issues). An issue is public: do not include passwords, pairing codes, private text or other secrets. Information you choose to submit there is handled by GitHub under its own policies and is visible to project maintainers. Public issue threads remain in the tracker until edited or removed through GitHub's controls; you can request removal of information you posted by contacting the maintainers in that thread without reposting the sensitive information.

Opening a website or downloading a receiver from GitHub uses that provider's services and privacy policy. Apple may process App Store or TestFlight diagnostics and feedback under its own settings and terms. Sofatap does not add its own analytics or crash-upload service.

## Policy changes

We will update this page if Sofatap's data practices change. Future research features, including AI computer operation, are not part of the data handling described for this release.

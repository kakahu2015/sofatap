# Sofatap support

Use your iPhone to control a paired computer from the sofa. Sofatap needs an iPhone running iOS 17 or later, a desktop receiver, and a reachable home network. Your computer keeps displaying its picture on the monitor or TV.

For help, [open a Sofatap issue](https://github.com/chekusu/sofatap/issues/new). Include your iPhone/iOS version, computer operating system, receiver version, and the steps that failed. Do not post pairing QR codes, pairing links, passwords, private text or unredacted logs.

## Install the receiver

[Receiver 0.7.2 beta.1](https://github.com/chekusu/sofatap/releases/tag/v0.7.2-beta.1) improves pairing persistence when restarting the receiver or adding network adapters. **Update the phone to iPhone 1.0 (16) as well for automatic reconnection**; the QR format itself requires build 15 or newer. All five packages and matching SHA256 files are available below. See the [receiver release checks](../verification/receiver-0.7.2.md) and [automated reconnection record](../verification/reconnect-2026-10-15.md) for validation scope. Live editing remains limited to supported Windows/Mac fields; Linux and unsupported fields use review-and-send.

| Computer | Download | Installation guide |
| --- | --- | --- |
| Windows x64 | [Windows ZIP](https://github.com/chekusu/sofatap/releases/download/v0.7.2-beta.1/SofaTap-Windows-x64.zip) | [Windows setup](../WINDOWS-START.md). Extract the full package and run `SofaTap.exe`. Allow the receiver on a trusted private network when prompted; do not disable the firewall. Windows binaries are not Authenticode-signed. |
| Mac, macOS 15+ | [Apple Silicon ZIP](https://github.com/chekusu/sofatap/releases/download/v0.7.2-beta.1/SofaTap-macOS-arm64-notarized.zip) · [Intel ZIP](https://github.com/chekusu/sofatap/releases/download/v0.7.2-beta.1/SofaTap-macOS-x64-notarized.zip) | Both packages are signed and notarized. Place `Sofatap Receiver.app` in Applications, open it normally, and grant it Accessibility permission. [Mac permission guide](../MAC-START.md); older examples there use the historical app name. |
| Linux | [x64 archive](https://github.com/chekusu/sofatap/releases/download/v0.7.2-beta.1/SofaTap-Linux-x64.tar.gz) · [ARM64 archive](https://github.com/chekusu/sofatap/releases/download/v0.7.2-beta.1/SofaTap-Linux-arm64.tar.gz) | [Linux setup and dependencies](../../desktop/linux/README.md). X11 is the initial desktop path; Wayland is experimental and depends on the desktop's RemoteDesktop portal. |

iPhone **1.0 (16)** has passed external review and is available through the [public TestFlight invitation](https://testflight.apple.com/join/e6MuKx9S), with a limit of 10,000 testers. See the [current distribution record](../verification/testflight-1.0-16.md). The separate formal App Store submission for 1.0 (15) is still waiting for review. TestFlight installation of build 16 on the dedicated test iPhone is verified; final-build Windows restart and input acceptance remain pending. Earlier pairing/input acceptance remains documented under its actual versions in the [0.7.1 verification record](../verification/receiver-0.7.1.md) and [build 16 development checks](../verification/input-1.0-16.md).

The ordinary receiver requires an unlocked, interactive desktop. A Windows Remote Desktop session and the desktop shown on a TV can be different sessions. Sofatap does not unlock the computer or enter a Windows sign-in PIN in this release.

## Pair your iPhone

1. Open the receiver on the computer and choose a home-network address reachable by the phone.
2. Connect the iPhone to the same home network. A wired computer is fine; guest Wi-Fi client isolation may prevent access.
3. Tap the top-left device selector and choose **Scan QR Code** under **Add Device**, then scan the computer receiver code or paste the complete pairing link. The camera appears above the computer platform information.
4. Allow Local Network access on the phone. If it was denied, enable it for Sofatap in Settings.
5. Check the connection indicator, then look at the computer screen while controlling it.

Build 15 identifies the computer platform automatically and displays any platform/name supplied by the code. After the authenticated connection succeeds, keep the suggested name or save a new one. You can rename it later in My Devices; this changes only the name on your iPhone. LG webOS requires manual IP entry and approval on the TV and remains an experimental preview.

Receiver 0.7.1 beta.1 and newer include `platform` and `name` in their desktop codes. **iPhone 1.0 build 15 or newer is required**. If your phone cannot update yet, keep [receiver 0.7.0 beta.1](https://github.com/chekusu/sofatap/releases/tag/v0.7.0-beta.1); build 15 also accepts that older code format. Platform/name labels do not replace certificate and pairing-credential checks.

## Gestures and text

- Slide one finger to move the pointer.
- Tap once for a left click. Build 15 uses a double-tap anywhere for one right click. In build 16, double-tap the invisible left half for two left clicks, or the right half for two right clicks.
- Hold still for 0.3 seconds, then move to drag; lift your finger to release.
- Slide two fingers to drag content naturally. The strip inside the touchpad's right edge follows scrollbar direction: slide down to scroll down.
- Tap the keyboard icon to type, or the microphone to dictate where on-device recognition is supported.

A compatible receiver can open the keyboard when a supported text field gains focus. Live editing is available only for certain native Windows and Mac fields. It imports the field's text and synchronizes editing automatically. Unsupported fields, including many browser and custom controls, use review-and-send; Linux and older receivers use this mode. Sofatap does not append an extra Return after your text. Line breaks you include may be handled as Enter by the destination app.

If live input pauses after a focus change or a connection problem, check the computer and reopen the editor. Do not resend uncertain text before checking whether it already arrived. If the microphone is unavailable, check the permissions or use keyboard input; Sofatap does not silently switch to a cloud recognizer.

## Reconnect after restarting the receiver (build 16 + receiver 0.7.2)

Install iPhone 1.0 (16) and receiver 0.7.2 beta.1. Keep the saved computer selected and Sofatap open on the iPhone while reopening the receiver. The phone retries temporary failures with increasing delays of up to 30 seconds; no new QR scan should be needed when the receiver still has the original certificate and pairing credentials. Manual disconnection or switching devices stops these retries. Reconnection does not resend unconfirmed typing or mouse actions; inspect the destination before resuming an editor.

The new receiver preserves pairing when network addresses are only added, only removed, or temporarily unavailable. A real address replacement or certificate renewal can still require explicit pairing. Existing duplicate entries are preserved; use My Devices to remove an obsolete entry after checking which saved device connects. See the [automated reconnection verification](../verification/reconnect-2026-10-15.md). Physical iPhone-to-Windows restart acceptance remains pending.

Build 16 also adds a computer Backspace icon below the composer. It deletes at the computer's focused input position without changing the phone draft. Keyboard Backspace edits an unsent fallback draft locally; after clearing that draft, it can forward Backspace to the computer. Live editing continues to synchronize deletions. See the [scoped build 16 development checks](../verification/input-1.0-16.md).

## Privacy

[Read the Sofatap Privacy Policy](../../PRIVACY.md). Pairing codes and links are credentials; keep them on your own devices. Camera, microphone and speech permissions can be changed in iPhone Settings. Quit the desktop receiver to stop remote control.

## Managing paired devices (build 13+)

Tap the device name at the top left to switch between saved computers or TVs. Each row shows its name, type and address; the active target is marked explicitly. The ellipsis menu renames the device on this iPhone, disconnects it, or removes its local pairing. Removal does not revoke other phones. The top-right question mark opens the illustrated guide. Interface and permission prompts follow the device language, with the same [12 languages as the website](../APP-LANGUAGES.md).

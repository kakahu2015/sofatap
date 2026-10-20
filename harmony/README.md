# HostTap for HarmonyOS

Native HarmonyOS controller for the existing HostTap/Sofatap Windows, macOS and Linux receiver.

The HarmonyOS client supports:

- pair with a desktop by scanning its QR code with the camera;
- validate the pairing fields;
- save up to 20 computers, keeping bearer tokens in HarmonyOS Asset Store and device metadata in app preferences;
- connect over TLS and pin the desktop leaf certificate with the SHA-256 fingerprint from the pairing link;
- resolve DNS hostnames and connect to IPv4 or IPv6 endpoints;
- perform the WebSocket upgrade without an `Origin` header, matching the existing receiver security policy;
- receive protocol-v1 `hello`/ACK frames;
- reconnect to the selected computer while the app is active, with capped backoff; backgrounding closes the socket and foregrounding can reconnect;
- move the pointer with one-finger pan, drag after a long press, scroll with two fingers and right-click with a two-finger tap;
- left/right click and scroll up/down with buttons;
- send Enter, Backspace and Escape;
- review and send Unicode text;
- edit supported Windows and macOS text fields live, using the existing v1 `textBegin`/`textSync`/`textEnd` protocol;
- keep the foreground connection alive with `ping`.

QR scanning requests camera access only when the user taps **拍照扫描电脑二维码**. The app does not request microphone access or include speech or AI features.

## Open and build

Open the `harmony/` directory as a HarmonyOS project in a current DevEco Studio installation with HarmonyOS SDK 5.0.0 (API 12) or newer. The project currently targets HarmonyOS 6.0.0 (API 20) while keeping API 12 as the compatibility floor.

Run `hvigorw --mode module -p product=default -p module=entry@default -p buildMode=debug assembleHap --no-daemon` from this directory to build a HAP. Configure a normal debug signing profile in DevEco Studio to install it on a physical HarmonyOS phone on the same LAN as the desktop receiver.

The GitHub Actions workflow builds a HAP on Harmony source changes and manual dispatch. Main-branch and manual builds require all six repository Actions secrets: `HARMONYOS_SIGNING_P12_BASE64`, `HARMONYOS_SIGNING_CER_BASE64`, `HARMONYOS_SIGNING_PROFILE_BASE64`, `HARMONYOS_STORE_PASSWORD`, `HARMONYOS_KEY_ALIAS`, and `HARMONYOS_KEY_PASSWORD`. Use a HarmonyOS **debug** certificate and Profile for bundle name `org.kakahu.hosttap`, and register the target phone's UDID in that Profile. The workflow uploads only signed debug HAPs for sideloading; pull requests still compile without signing secrets and do not upload a HAP. The workflow does not store signing material in Git.

## Pairing test

1. Start the HostTap desktop receiver.
2. Keep its pairing QR code visible to the phone; the pairing data contains a bearer credential.
3. Tap **拍照扫描电脑二维码**, allow camera access, and scan the QR code.
4. After the status becomes **已连接**, use the touch surface, click/scroll buttons, reviewed-text field or supported live editor.

Saved computers can be selected from the pairing card. Their bearer tokens are stored as sensitive assets; no pairing data is sent to a cloud service.

## Transport security

The desktop receiver intentionally rejects browser-style WebSocket requests carrying an `Origin` header. HarmonyOS's high-level WebSocket client supplies an Origin field, so this client does not relax the receiver policy.

Instead it uses `TLSSocket` directly:

1. establish TLS without sending HostTap credentials;
2. obtain the peer leaf certificate;
3. hash the DER certificate with SHA-256;
4. compare it in constant time with the pairing fingerprint;
5. only after a successful match, send the WebSocket upgrade and bearer token over that same TLS connection;
6. implement RFC 6455 client masking/framing locally.

`SHA1` appears only for the RFC 6455 `Sec-WebSocket-Accept` calculation required by the WebSocket protocol. Host identity uses the SHA-256 certificate fingerprint.

A transport failure never replays input. Text timeouts/connection failures are surfaced as uncertain delivery so the user can inspect the computer before sending again.

## Validation limits

- Live Text is advertised only by compatible desktop receivers and only opens when the current desktop focus is a supported editable field. Unsupported fields retain review-and-send text. The receiver controls whether a field is eligible.
- App backgrounding closes the control socket; no background remote input is attempted.
- The implementation has not yet been built or validated on a physical HarmonyOS device. Confirm Scan Kit permission behavior, hostname/IPv6 connectivity, gesture arbitration, secure asset persistence and desktop Live Text behavior on the target device and receiver versions.

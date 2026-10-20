# HostTap for HarmonyOS (MVP)

Minimal native HarmonyOS controller for the existing HostTap/Sofatap Windows, macOS and Linux receiver.

This first cut intentionally targets the smallest useful flow instead of UI parity with iOS/Android:

- paste a desktop `sofatap://pair?...` link;
- validate the pairing fields;
- connect over TLS and pin the desktop leaf certificate with the SHA-256 fingerprint from the pairing link;
- perform the WebSocket upgrade without an `Origin` header, matching the existing receiver security policy;
- receive protocol-v1 `hello`/ACK frames;
- move the pointer with one-finger pan;
- left/right click;
- scroll up/down;
- send Enter, Backspace and Escape;
- review and send Unicode text;
- keep the foreground connection alive with `ping`.

QR scanning, saved-device persistence, automatic reconnect, live text editing, speech, advanced gestures and UI parity are deliberately deferred until the transport is verified on a physical HarmonyOS device.

## Open and build

Open the `harmony/` directory as a HarmonyOS project in a current DevEco Studio installation with HarmonyOS SDK 5.0.0 (API 12) or newer. The project currently targets HarmonyOS 6.0.0 (API 20) while keeping API 12 as the compatibility floor.

Configure a normal debug signing profile in DevEco Studio, select the `entry` module, then run it on a physical HarmonyOS phone on the same LAN as the desktop receiver.

## Pairing test

1. Start the HostTap desktop receiver.
2. Use its **Copy pairing link** action.
3. Transfer that link to the HarmonyOS phone without publishing it anywhere; it contains the bearer credential.
4. Paste it into the HarmonyOS HostTap pairing field and tap **连接电脑**.
5. After the status becomes **已连接**, use the touch surface, click/scroll buttons or reviewed-text field.

The HarmonyOS MVP currently accepts the receiver's advertised IPv4 host format plus ordinary DNS hostnames. IPv6 pairing is deferred.

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

## MVP limitations

- Pairing is paste-only; no Scan Kit integration yet.
- Pairings are kept only in memory for this first transport validation build.
- No automatic reconnect yet.
- No live-text extension yet; text uses the protocol-v1 review-and-send fallback.
- No background control. Leaving the page closes the connection.
- This source has not yet been certified by a physical-device compatibility matrix; validate against the intended HarmonyOS release and desktop receivers before store submission.

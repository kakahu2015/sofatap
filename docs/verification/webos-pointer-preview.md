# LG webOS pointer preview: software verification

Date: **2026-10-13**. This records development-source verification. It does not qualify a TV model, update the published TestFlight build, or establish physical iPhone-to-TV acceptance.

## Implemented scope

- Native iPhone pairing screen reached from the existing connection control. Manual selection accepts a canonical private IPv4 address, without a browser, subnet scanning or multicast entitlement.
- An initial TLS inspection sends no SSAP credentials. Explicit in-app trust precedes registration and the TV's own approval. Registration, pointer acquisition and pointer-channel opening are distinct steps; pairing credentials are saved only after the complete connection opens.
- Control and pointer connections use the selected host and the same SHA-256 leaf pin. A legacy `ws:3000` pointer advertisement is accepted only for a narrowly validated same-host path and is connected through **`wss:3001`**. There is no plaintext fallback; an unsupported secure pointer service fails.
- Client key and certificate pin use a separate device-only Keychain record. The public protocol requests only `CONTROL_MOUSE_AND_KEYBOARD`, without a copied vendor signed manifest.
- The existing touch surface routes movement, tap and two-finger scroll to the selected TV. Back and Home replace the unavailable TV text controls. TV mode has no desktop double-tap/right-click or long-press drag. Those capabilities remain unchanged for computers.
- Device switching cancels old touches and desktop input before selecting the new route. A TV failure never falls back to the computer. The selected device family is retained across app restarts. Backgrounding disconnects the TV; reconnect is explicit and does not replay actions. Queues are bounded, serialized and expire after 500 ms; an in-flight send has a 3-second deadline.
- The app exposes English and Simplified Chinese preview text. The native Xcode project includes the new files.

The initial trust flow is trust on first use on the selected home network, not manufacturer-authenticated identity. A successful pointer write is not a TV action acknowledgment. Capability claims still require visible results on a named physical model.

## Executed checks

| Check | Result and boundary |
| --- | --- |
| `WebOSTVProtocolTests.swift` | 151 checks passed: canonical private addresses; URL/path/port restrictions; request IDs; missing-ID registration compatibility only during registration; explicit errors; malformed/oversized messages; bounded motion; framing and credential-safe diagnostics. No network. |
| `bash ios/scripts/test-tv-controller.sh` | 74 checks passed: explicit trust, TV approval phases, persistence timing, both channel pins, stale callbacks, replacement sessions, queue ordering/expiry, suspension, published UI availability, side-effect-free demo mode and no replay. Injected sockets and storage; no real Keychain access. |
| `bash ios/scripts/test-tv-transport.sh` | Passed with actual TLS/WebSocket connections to temporary **127.0.0.1** fixtures: certificate inspection before HTTP/credentials; correct/incorrect pins; frame round-trip; ping; blocked redirect; binary/oversized messages; duplicate failures; close/reopen callback isolation. Ephemeral certificates and servers are removed afterward. |
| `TouchGestureTests.swift` | 63 checks passed, including separate desktop/TV semantics and cancellation before profile changes. |
| `bash ios/scripts/test-drag-release.sh` | 30 checks passed, including suspension of desktop input and reconnect while TV is selected. Recording transport only. |
| `bash ios/scripts/test-live-text.sh` | 57 recording-transport/UIKit checks passed for existing live text and selection. |
| `bash ios/scripts/test-draft-editor.sh` | 22 offscreen UIKit checks passed. |
| `bash ios/scripts/build-manual.sh device` | Native source compiled and linked for iOS arm64. Unprovisioned development artifact only; no phone installation. |
| Property lists, localization files and `git diff --check` | Passed. |

The transport fixture exposed a real issue: URLSession delivered a 65,537-byte string despite `maximumMessageSize = 65,536`. An explicit UTF-8 length guard now rejects the message before delivery, and the complete transport regression passes.

These checks opened no desktop windows, sent no desktop input, paired no TV and did not modify physical display topology. Receiver acceptance on the shared Mac remains subject to Yomado input isolation.

## Still required before a consumer TV release

1. Obtain the target TV's brand/model, firmware and system. Confirm its secure service and actual minimal manifest permissions. Some firmware may need different permissions or pairing behavior; no named TV is currently qualified.
2. Test visible pointer movement, click, scrolling, Back/Home, TV permission rejection/revocation, changed certificate, Wi-Fi interruption, foreground/background and reconnection on that model with the test iPhone. Record outcomes, not merely socket receipts.
3. Add and qualify native discovery (including Apple's multicast entitlement), DHCP/address changes and consumer onboarding. The current preview requires a private IPv4 address and stores one TV pairing.
4. Implement TV-specific IME/focus/text/dictation with explicit editor capability checks. There is no TV keyboard or speech input in this pointer preview, and no claim that PC live selection synchronization carries over.

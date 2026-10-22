# Sofatap roadmap

Updated October 16, 2026. This is the product direction, with acceptance goals rather than promised release dates.

**Feature delivery order:** improve free manual control on computers and qualify the LG webOS preview → paid voice automation on Windows/macOS/Linux → evaluate TV automation after a supported manual-control path is established. Existing desktop maintenance and release acceptance continue separately.

## Free: manual control of computers and TVs

The free product is an iPhone remote: the user moves, clicks, drags, scrolls and enters text. Basic voice-to-text remains part of this input experience. Dictating words into a field does not authorize an automated task.

1. **Current foundation — computers.** The iPhone app and receiver support manual control of Windows, macOS and supported Linux desktops. Text editing and input compatibility vary by platform and application; the [README](../README.md#platform-status) and [verification records](ACCEPTANCE.md) describe the actual scope. Keep improving these controls and their reliability.
2. **TV preview — LG webOS.** The iPhone can connect to a compatible LG TV over its local network using manual IP entry and on-TV approval. Continue physical model and firmware qualification before describing a model as supported. The implementation status is in the [webOS pointer preview record](verification/webos-pointer-preview.md).

For every TV family, publish the tested model/OS/app matrix and distinguish these capabilities:

| Capability | Acceptance boundary |
| --- | --- |
| Pointer control | The TV renders and moves a real pointer; clicking reaches the intended target. Verify scrolling and any drag support independently in actual TV apps. |
| Focus navigation | Direction keys move focus and confirm/back activate navigation. Label this mode as navigation; it does not satisfy the mouse-control goal. |
| Unsupported | Clearly report unavailable actions. A moving decoration on the phone is not evidence of a TV pointer. |

Before calling an LG TV supported, verify model and firmware, explicit on-TV pairing, reconnection, disconnect and pointer actions on the physical TV. Pairing alone does not prove pointer control. Record per-application acceptance and failure recovery in the [hardware checklist](ACCEPTANCE.md). Automatic discovery and TV text input are not part of the current preview.

## Paid: speak a goal and complete multiple steps

The paid capability is voice-directed automation: interpret a goal, observe the device, perform a sequence of actions, verify the outcome and handle follow-up instructions. The user can see progress, stop the task and take over manually. Starting an automation session must be explicit and separate from ordinary dictation.

1. **First automation scope — Windows, macOS and Linux computers.** Qualify each OS and supported desktop/application combination separately. Evaluate pi-durable, Cua Driver and DeepSeek through the [architecture research](research/natural-language-pro.md). The intended product runs independently of Codex; this integration is still research, not a shipped capability.
2. **Later automation scope — TVs.** Extend goal-driven tasks only after manual TV control and reliable interface observation are established for that platform. Navigating a menu, seeing its result and interacting with protected playback may have different limits; qualify each supported scenario.

Acceptance requires repeatable multi-step tasks with measured completion, latency, cost and intervention rates on each platform. Test interruptions, changed windows, reconnection, immediate stopping and manual takeover. Consequential actions need a clear approval boundary, and uncertain actions must not be blindly replayed after recovery. Shared-Mac tests must preserve the user's normal display and input session through verified Yomado isolation.

## Release boundary

The current iPhone release is configured as free, with no IAP or subscription. Free manual TV control and paid computer/TV automation describe future scope. Basic keyboard entry and voice-to-text remain free input features; fees are for goal-driven automation. No TV compatibility, automation success, subscription price or delivery date is announced by this roadmap.

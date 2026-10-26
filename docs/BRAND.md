# Sofatap brand guidance

The global product name is **Sofatap**: one word, with only the initial **S** capitalized. Lowercase `sofatap` is appropriate for URLs and technical identifiers. Keep the same spelling in English, Chinese and Japanese copy.

Apply this name consistently to app titles, permission descriptions, notifications, store listings, documentation, websites, illustrations, screenshots, promotional video titles, subtitles, end cards and narration scripts. Do not insert a space between sofa and tap or capitalize the internal t in public copy.

## Chinese-market name proposal

**沙发瘫** uses **瘫**, as in 瘫倒. It evokes relaxing on a sofa while controlling a computer with a phone, and its rhythm echoes Sofatap. Suggested presentation:

> 沙发瘫 · Sofatap  
> 躺在沙发，电脑在手。

This is the proposed Chinese name; the current app display name remains **Sofatap**. In Japanese, retain **Sofatap** rather than inventing a separate product name.

## Describe the product accurately

Sofatap turns an iPhone into a single-screen gesture mouse and voice/text input surface for a living-room computer. The TV displays the computer; the phone controls it. Windows, macOS and supported Linux desktops use native pairing windows. Connection uses the local network, not Bluetooth. Development version 0.5.0 adds an on-device speech button when supported, with keyboard/Typeless fallback and optional focused-field detection; Sofatap sends reviewed text when the user chooses Send. Earlier downloadable alpha versions retain their previous UI. See [interaction direction](INTERACTION.md).

Keep implementation and verification claims distinct. Concept illustrations are labeled as illustrations. Do not present generated UI as a screenshot, a passed hardware test, or an App Store listing. Carry current platform limits forward from the README and verification records.

## Current assets and languages

- The selected desktop receiver direction is **暮色薄荷 / Mint Calm**, chosen on 2026-10-12 for Windows, macOS and Linux. Follow the [shared receiver design](RECEIVER-DESIGN.md) for color, hierarchy and native controls. The concept image remains labeled as generated artwork; it is not evidence of a released or installed UI.

- [English README](../README.md) is the default; [简体中文](../README.zh-CN.md) and [日本語](../README.ja.md) provide equivalent versions.
- [Living-room hero](images/sofatap-living-room.png): generated with the built-in imagegen tool, visually reviewed, and shared by all three READMEs. [Exact generation prompt](brand/living-room-image-prompt.txt).
- Every non-iOS application core icon uses the dark rounded-square mint H from `android/hosttap/src/main/res/drawable/hosttap_mark.xml`. The Android receiver carries the same vector as `android/receiver/src/main/res/drawable/brand_mark.xml`; macOS and Windows use `desktop/*/Assets/AppIcon.png`, Windows embeds its generated ICO, HarmonyOS uses the matching foreground/background SVG layers, and Linux and Mac App Store packaging use the desktop H PNG. The website favicon uses the same mark. The former iOS project has been removed; iOS integration is maintained in KeyTrans. Previously signed/published binaries retain their original icon and must be upgraded using a newly built package.
- No promotional video is currently tracked in this repository. Future video work must follow the spelling above.

Keep stable code and compatibility identifiers such as existing `SofaTap` project/executable names, archive filenames, bundle IDs and credential paths. Literal filenames in immutable published archives and verbatim historical generation prompts retain their original spelling and must be labeled as historical. They are evidence and compatibility references, not reusable marketing copy. Never alter a signed release archive to change branding in place.

### Reproduce the current icon assets

The Android HostTap vector is the source of truth for H. Keep its two colors and geometry aligned across the Android receiver vector, macOS/Windows PNGs, Windows ICO, HarmonyOS SVGs and the website favicon.

macOS packaging converts its PNG to ICNS on each build. Regenerate the committed Windows ICO from the Windows H-mark PNG on macOS whenever it changes; this converts the complete mark into 16–256 pixel PNG frames. Windows builds use the committed ICO and do not require Swift or an image converter:

```sh
xcrun swift scripts/generate-windows-icon.swift desktop/windows/Assets/AppIcon.png desktop/windows/Assets/SofaTap.ico
```

macOS packaging generates `SofaTap.icns` from `desktop/macos/Assets/AppIcon.png` on every build. Linux packaging uses that same H PNG for its native window and desktop launcher. The former iOS app was moved into KeyTrans and is not maintained or built from this repository.

Windows lock-screen PIN input is under feasibility validation. The target includes manual iPhone control and optional Face ID-authorized saved PIN entry, without a Microsoft account password. The earlier password pilot is retired. Do not claim lock-screen input, PIN acceptance or Face ID unlock from pairing, compilation or pointer-only diagnostics. See [PIN control scope](FACE-ID-UNLOCK.md).

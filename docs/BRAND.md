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
- The iOS SofaTap surface and app icon use [the phone-on-sofa mark](../output/imagegen/sofatap-icons-2026-10-11/06-phone-sofa.png), created by combining the first sofa and second phone/cursor concepts at the user's request. `BrandMark.imageset/BrandMark.png` is an unchanged copy of the 1254-pixel source; `AppIcon.appiconset/AppIcon.png` is the same full composition resized to an opaque 1024-pixel square with `sips`. Its cursor belongs inside the logo; the touch surface does not display a separate pointer graphic. Android HostTap uses the dark rounded-square mint H in `android/hosttap/src/main/res/drawable/hosttap_mark.xml`; macOS and Windows HostTap Remote use the same H mark in their respective `desktop/*/Assets/AppIcon.png` files. Windows embeds its ICO in both the executable and the native pairing window. Linux keeps using the iOS AppIcon PNG for its native window and desktop launcher. Previously signed/published binaries retain their original icon and must be upgraded using a newly built package.
- No promotional video is currently tracked in this repository. Future video work must follow the spelling above.

Keep stable code and compatibility identifiers such as existing `SofaTap` project/executable names, archive filenames, bundle IDs and credential paths. Literal filenames in immutable published archives and verbatim historical generation prompts retain their original spelling and must be labeled as historical. They are evidence and compatibility references, not reusable marketing copy. Never alter a signed release archive to change branding in place.

### Reproduce the current icon assets

Copy `output/imagegen/sofatap-icons-2026-10-11/06-phone-sofa.png` unchanged to `ios/SofaTap/Assets.xcassets/BrandMark.imageset/BrandMark.png`. Resize only the complete square for the iOS App Store icon:

```sh
sips -z 1024 1024 output/imagegen/sofatap-icons-2026-10-11/06-phone-sofa.png --out ios/SofaTap/Assets.xcassets/AppIcon.appiconset/AppIcon.png
```

The Android vector above is the source for the committed macOS and Windows HostTap H-mark PNGs. macOS packaging converts its PNG to ICNS on each build. Regenerate the committed Windows ICO from the Windows H-mark PNG on macOS whenever it changes; this converts the complete mark into 16–256 pixel PNG frames. Windows builds use the committed ICO and do not require Swift or an image converter:

```sh
xcrun swift scripts/generate-windows-icon.swift desktop/windows/Assets/AppIcon.png desktop/windows/Assets/SofaTap.ico
```

macOS packaging generates `SofaTap.icns` from `desktop/macos/Assets/AppIcon.png` on every build; Linux packaging continues to copy the iOS AppIcon to `sofatap.png`.

The source and BrandMark share SHA-256 `bab56f458fe7b76832788c4926b04a3574b3c09249372282a86922c8cfa9df05`. The historical `ios/tools/GenerateIcon.swift` generates the superseded sofa-only symbol and requires an explicit legacy flag plus output path; it is not the source for current branding.

Windows lock-screen PIN input is under feasibility validation. The target includes manual iPhone control and optional Face ID-authorized saved PIN entry, without a Microsoft account password. The earlier password pilot is retired. Do not claim lock-screen input, PIN acceptance or Face ID unlock from pairing, compilation or pointer-only diagnostics. See [PIN control scope](FACE-ID-UNLOCK.md).

# Native app languages

> iOS 版本暂不开源。请从 App Store 下载 [KeyTrans](https://apps.apple.com/app/id6739558003)。

Sofatap follows the language selected for the app in iOS. English is the development language and fallback. The interface and all five system permission descriptions are localized in the same twelve languages as the official website.

| Website locale | iOS resources | Language |
| --- | --- | --- |
| `en` | `en.lproj` | English |
| `zh-CN` | `zh-Hans.lproj` | Simplified Chinese |
| `es` | `es.lproj` | Spanish |
| `fr` | `fr.lproj` | French |
| `de` | `de.lproj` | German |
| `pt-BR` | `pt-BR.lproj` | Brazilian Portuguese |
| `ja` | `ja.lproj` | Japanese |
| `ko` | `ko.lproj` | Korean |
| `ar` | `ar.lproj` | Arabic |
| `hi` | `hi.lproj` | Hindi |
| `ru` | `ru.lproj` | Russian |
| `id` | `id.lproj` | Indonesian |

The website language list was verified against [`scripts/locales.mjs` at `bb3ee731c3ef5240d354bad7e1722d9cf7bdb496`](https://github.com/chekusu/sofatap.app/blob/bb3ee731c3ef5240d354bad7e1722d9cf7bdb496/scripts/locales.mjs) on 2026-10-14. Simplified Chinese uses Apple's script-based resource identifier. Arabic uses native right-to-left text layout; physical touch gestures retain their defined directions. The right-edge scroll strip remains physically on the right.

The expanded resources contain 309 interface strings per language, including the device library, separate guide, keyboard states, error recovery, and experimental TV capability limits. Permission prompts cover camera, local network, microphone, speech recognition, and motion. Existing Chinese lookup keys remain stable for compatibility.

Interface translation does not promise on-device speech recognition for every language/device combination. Sofatap still checks `SFSpeechRecognizer.supportsOnDeviceRecognition`, shows a localized explanation when unavailable, and offers keyboard or third-party input. It does not send audio to a new translation service.

The former localization checker was removed with the iOS project.
# Windows receiver — Microsoft Store (MSIX) preparation

Updated October 18, 2026. **Unpublished submission draft.** The dual-architecture MSIX build is implemented and its layout is verified locally. No package has been uploaded to Partner Center, certified or published, and no install from the Store has been tested.

## Certification and signing on Windows

Windows has no equivalent of Apple's notarization service. Two separate mechanisms are often confused:

| Mechanism | What it does | Required for the Store? |
| --- | --- | --- |
| Package identity match | `Identity/Name`, `Identity/Publisher`, `PublisherDisplayName` and the declared capabilities must equal what Partner Center reserved | Yes — a mismatch is rejected before certification |
| Store certification | Automated malware/startup checks plus human policy review; Microsoft then re-signs the package with its own certificate | Yes — this replaces developer signing |
| Authenticode signing | `signtool` with a certificate chaining to the Microsoft Trusted Root Program; proves publisher identity outside the Store | No — only for sideload or direct download distribution |
| Self-signed certificate | Local trust only; every other machine shows an unknown publisher and refuses installation | No — testing only |

Therefore the Store submission is uploaded **unsigned**. Signing a Store package with a self-signed certificate is not "notarizing" it; it either has no effect after Microsoft re-signs, or it fails identity validation when the certificate subject does not match the reserved publisher.

Distribution paths compared:

| Path | Cost | SmartScreen behaviour | Notes |
| --- | --- | --- | --- |
| Store MSIX (this build) | Free developer account; Microsoft re-signs | No warning | Automatic updates, no certificate to renew |
| Azure Artifact Signing (formerly Trusted Signing) | About USD 9.99/month | Reputation builds over successive releases; early downloads may warn | Organizations in the USA, Canada, EU and UK; individual developers currently USA and Canada only |
| OV certificate from a CA | Roughly USD 150–300/year | Same as above; EV no longer bypasses SmartScreen instantly | Works worldwide, hardware token or cloud HSM |
| Self-signed | Free | Unknown publisher; installation blocked elsewhere | Dev and enterprise-managed trust only |

## Package identity

| Manifest field | Value used by this build | Source |
| --- | --- | --- |
| `Identity/Name` | `org.kakahu.hosttap` | Name reserved in Partner Center |
| `Identity/Publisher` | `CN=org.kakahu.hosttap` (placeholder) | **Must be copied from Partner Center**, typically `CN=<GUID>` or `CN=<16 alphanumeric characters>`; the Store rejects any other value |
| `Properties/PublisherDisplayName` | `HostTap Remote` (placeholder) | Must equal the publisher display name on the Partner Center account |
| `Properties/DisplayName` | `HostTap Remote` | Listing name is chosen separately in Partner Center |
| `Identity/Version` | From `updates/desktop-version.txt` → `0.8.0.0` | Every later submission must be strictly higher |
| `Application/Executable` | `hosttap.exe` | Receiver front end; the backend ships beside it as `SofaTapHost.exe` |
| `Identity/ProcessorArchitecture` | `x64` and `arm64` | Combined into one `.msixbundle` |
| Capabilities | `runFullTrust`, `internetClient`, `privateNetworkClientServer` | `runFullTrust` needs a written justification during submission |
| Minimum OS | `Windows.Desktop` 10.0.17763.0 | Required floor for full-trust MSIX |

`org.kakahu.hosttap` is only valid if that exact name is reserved for the account. The publisher string cannot be invented locally — it is issued by Microsoft when the developer account is created.

## Build

```powershell
# One-time: copy the example and paste the three Partner Center values.
Copy-Item packaging\windows-store\store-identity.json.example packaging\windows-store\store-identity.json
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build-msix.ps1
```

`packaging/windows-store/store-identity.json` is git-ignored because it contains the account-specific publisher string. Environment variables still take precedence over the file, which keeps CI usage unchanged. The step-by-step Chinese walkthrough is `docs/WINDOWS_STORE_SUBMISSION.zh-CN.md`.

Environment overrides (each store value also has a `store-identity.json` counterpart; environment variables win):

| Variable | Default | Purpose |
| --- | --- | --- |
| `SOFATAP_STORE_IDENTITY` | `org.kakahu.hosttap` | `Identity/Name` |
| `SOFATAP_STORE_PUBLISHER` | `CN=org.kakahu.hosttap` | `Identity/Publisher`; must come from Partner Center |
| `SOFATAP_STORE_PUBLISHER_DISPLAY_NAME` | `HostTap Remote` | `PublisherDisplayName` |
| `SOFATAP_STORE_DISPLAY_NAME` | `HostTap Remote` | Package display name |
| `SOFATAP_STORE_VERSION` | derived, `0.8.0.0` | Four-part version, each part 0–65535 |
| `SOFATAP_STORE_PFX`, `SOFATAP_STORE_PFX_PASSWORD` | unset | Sideload signing only; never used for the Store upload |
| `SOFATAP_DOTNET`, `SOFATAP_MAKEAPPX`, `SOFATAP_MAKEPRI`, `SOFATAP_SIGNTOOL` | `.tools/` | Tool overrides for CI |

The script publishes both architectures self-contained and single-file with `-p:SofatapMsix=true`, which defines `SOFATAP_MSIX` and disables the in-app updater and the startup shortcut UI. A packaged app is installed under `WindowsApps`, so those paths are read-only and the Store owns updates.

Outputs under `artifacts/msix/packages`:

| File | Purpose |
| --- | --- |
| `HostTap-Remote-x64.msix` | x64 package; sideload and diagnostics |
| `HostTap-Remote-arm64.msix` | arm64 package; sideload and diagnostics |
| `HostTap-Remote.msixbundle` | Both architectures, one install |
| `HostTap-Remote.msixupload` | ZIP of the bundle; the file uploaded to Partner Center |
| `*.sha256` | Detached hashes |

Each package is unpacked and checked for `AppxManifest.xml`, `hosttap.exe`, `SofaTapHost.exe` and `Assets\Square150x150Logo.png` before the script exits. The published executables are PE-verified for their target machine type (`0x8664` for x64, `0xAA64` for arm64).

## Runtime differences in the packaged build

| Behaviour | Unpackaged receiver | MSIX package |
| --- | --- | --- |
| Install location | User-chosen folder | `C:\Program Files\WindowsApps\...` (read-only) |
| Updates | In-app updater, `SOFATAP_MSIX` not defined | Store handles updates; updater code is compiled out |
| Start at login | `StartupRegistration` shortcut in the Startup folder | Hidden in packaged builds; no packaged startup task yet |
| Pairing credentials | `%LOCALAPPDATA%\SofaTap` | Redirected to the package container under `%LOCALAPPDATA%\Packages\org.kakahu.hosttap_*\LocalCache\Local\SofaTap` |
| Backend process | `SofaTapHost.exe` beside the front end | Unchanged, launched from the package directory |

Pairing state is therefore not shared between the unpackaged and Store builds. Switching between them requires a new QR pairing; the two builds must not be assumed to share credentials or a listening port.


## Local validation before upload

```powershell
# Confirm the identity the Store will compare against
Get-AppxPackageManifest .\artifacts\msix\work\x64\layout | Select-Object -ExpandProperty Package |
  ForEach-Object { $_.Identity; $_.Properties }

# Manifest-only install check on a developer-mode machine (no certificate required)
Add-AppxPackage -Register .\artifacts\msix\work\x64\layout\AppxManifest.xml
& "$env:LOCALAPPDATA\Microsoft\WindowsApps\hosttap.exe"
Get-AppxPackage org.kakahu.hosttap | Remove-AppxPackage

# Signature check, only for a sideload-signed package
signtool verify /pa /v .\artifacts\msix\packages\HostTap-Remote.msixbundle
```

Do not upload the `.msix` files individually and the bundle at the same time; a submission takes one upload package.

## Submission steps

1. Register at `storedeveloper.microsoft.com`, complete identity verification, and open **Apps & Games → Overview → New product → MSIX or PWA app** in Partner Center (the Chinese UI calls that section **应用和游戏**; older documentation calls it **Windows & Xbox**).
2. Reserve the name `org.kakahu.hosttap` (or the intended product name) and record `Package/Identity/Name`, `Package/Identity/Publisher` and the publisher display name verbatim.
3. Rebuild with `SOFATAP_STORE_PUBLISHER` and `SOFATAP_STORE_PUBLISHER_DISPLAY_NAME` set to those values. The placeholder warning from the script must disappear.
4. Upload `HostTap-Remote.msixupload`. Partner Center reads the manifest and shows the architectures in the bundle; both `x64` and `arm64` must be listed.
5. Complete the listing: category Utilities, screenshots at 1366×768 or larger, support contact, privacy policy URL (publish `PRIVACY.md`), and the age-rating questionnaire.
6. Justify `runFullTrust` twice: in **Submission options → Restricted capabilities** (mandatory field once a restricted capability is declared) using `packaging/windows-store/restricted-capability.txt`, and in the certification notes. The justification covers the packaged Win32 window, the `SofaTapHost.exe` child process and input injection into the signed-in session. Declare that no screen capture, unattended automation or lock-screen bypass is implemented.
7. Provide reviewer notes: no account is required; the receiver shows a QR code that only the companion iPhone app can consume; the reviewer can verify launch, window rendering and listening state without a phone. State that pointer, click, drag, scroll and text sending require the companion app.
8. Submit to certification. Automated checks plus human review typically take one to three days for a first submission.

## Known limits before submitting

- The publisher placeholder blocks the Store until the real value is supplied; the build still succeeds, by design, so sideload testing remains possible.
- Version `0.8.0.0` is valid but signals pre-release. Use `SOFATAP_STORE_VERSION=1.0.0.0` for the first public submission, then raise it for every update.
- No `x86` package. 32-bit Windows devices cannot install the bundle.
- No symbol package: crash reports will not resolve managed stacks. Adding `.msixsym` files to the upload is optional and not implemented.
- Start-at-login is hidden in packaged builds. A packaged `windows.startupTask` extension would be the supported replacement; it is not implemented, so Store users start the receiver manually.
- Display names in the manifest are English only. The in-app UI localizes through embedded string resources; Store listing localization is configured in Partner Center, not in the package.
- The Store version is not code-signed locally, so it cannot be installed by double-click. Use `-Register` for layout tests, or sign a separate sideload build with a test certificate.

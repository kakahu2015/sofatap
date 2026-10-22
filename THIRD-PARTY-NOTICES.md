# Third-party notices

Sofatap source is licensed under [MIT](LICENSE). The native iOS client uses Apple system frameworks and includes no third-party libraries. Its app icon and in-app mark use the image-generated phone-on-sofa artwork documented in [brand guidance](docs/BRAND.md); the historical source-drawn sofa icon is retained only as an explicitly invoked legacy generator.

The desktop receiver uses the following packages. Upstream license and notice texts are preserved in `licenses/`.

| Component | Version in the initial build | License documents | Upstream |
| --- | --- | --- | --- |
| QRCoder | 1.7.0 | [MIT](licenses/qrcoder/LICENSE.txt) | [QRCoder at the package source commit](https://github.com/Shane32/QRCoder/tree/2dc55ee03cb9abf66ffed7d683cbcce0a141b124) |
| System.Drawing.Common | 6.0.0 | [MIT](licenses/system.drawing.common/LICENSE.TXT), [notices](licenses/system.drawing.common/THIRD-PARTY-NOTICES.TXT) | [.NET runtime](https://github.com/dotnet/runtime) |
| Microsoft.Win32.SystemEvents | 6.0.0 | [MIT](licenses/microsoft.win32.systemevents/LICENSE.TXT), [notices](licenses/microsoft.win32.systemevents/THIRD-PARTY-NOTICES.TXT) | [.NET runtime](https://github.com/dotnet/runtime) |
| .NET runtime for Windows x64 | 10.0.12 | [license](licenses/microsoft.netcore.app.runtime.win-x64/LICENSE.TXT), [notices](licenses/microsoft.netcore.app.runtime.win-x64/THIRD-PARTY-NOTICES.TXT) | [.NET runtime](https://github.com/dotnet/runtime) |
| .NET app host for Windows x64 | 10.0.12 | [license](licenses/microsoft.netcore.app.host.win-x64/LICENSE.TXT), [notices](licenses/microsoft.netcore.app.host.win-x64/THIRD-PARTY-NOTICES.TXT) | [.NET runtime](https://github.com/dotnet/runtime) |
| ASP.NET Core runtime for Windows x64 | 10.0.12 | [license](licenses/microsoft.aspnetcore.app.runtime.win-x64/LICENSE.txt), [notices](licenses/microsoft.aspnetcore.app.runtime.win-x64/THIRD-PARTY-NOTICES.TXT) | [ASP.NET Core](https://github.com/dotnet/aspnetcore) |

QRCoder copyright is held by Raffael Herrmann (2013–2025) and Shane Krueger (2024–2025). Microsoft package notices retain the original copyright statements and notices for included components.

`scripts/build-windows.sh` also copies license documents from the exact resolved NuGet runtime and dependency packages into each Windows release. That release's `licenses/RESOLVED-PACKAGES.txt` records the versions used, which can differ from the initial-build snapshot above after SDK updates. The build fails if a shipped package lacks a license document.

`scripts/build-macos.sh` uses the same policy for macOS builds, including SDK-provided native apphost packs. Mac releases contain their own OS/architecture-specific .NET and ASP.NET Core licenses; consult each package’s `Contents/Resources/licenses/RESOLVED-PACKAGES.txt` for exact dependencies. The Windows runtime links in the initial-build table above describe that historical Windows snapshot, not the Mac distribution. The ILLink build tool is excluded because it is not part of the distributed receiver.

Development-only tools, including Xcode, .NET SDK, Python, `websockets` and GitHub Actions, are not bundled with Sofatap. They retain their respective upstream licenses.

The experimental Windows login provider follows Microsoft’s Credential Provider sample authentication-packing design. Its [attribution](desktop/windows-login/Provider/NOTICE.md) and [Microsoft MIT license](desktop/windows-login/Provider/Microsoft-License.txt) are retained alongside the source. The optional service also uses Microsoft.Extensions.Hosting.WindowsServices and System.Security.Cryptography.ProtectedData 10.0.0 (MIT); production packaging must collect notices for the exact resolved dependencies before redistribution. This component currently has a development-only installer.

The native LG webOS preview is an independent Swift implementation informed by the [LG ConnectSDK protocol sources](https://github.com/ConnectSDK/Connect-SDK-Android-Core/tree/2f7ebbc7eb909321ff41b2c6bcdf6b0881427579), licensed by their authors under Apache-2.0. No ConnectSDK library, source file, vendor signed manifest, signature or credential is bundled. Protocol references and compatibility limits are recorded in the [webOS pointer preview verification](docs/verification/webos-pointer-preview.md).

## HostTap Android phone controller

The phone APK includes the following runtime dependencies. License and notice texts are bundled under [`assets/licenses/`](android/hosttap/src/main/assets/licenses) in the APK.

| Component | Version | License and notices | Source |
| --- | --- | --- | --- |
| AndroidX Activity | 1.12.0 | [Apache-2.0](android/hosttap/src/main/assets/licenses/APACHE-2.0.txt) | [AndroidX](https://developer.android.com/jetpack/androidx) |
| AndroidX CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`) | 1.6.2 | [Apache-2.0](android/hosttap/src/main/assets/licenses/APACHE-2.0.txt) | [CameraX release documentation](https://developer.android.com/jetpack/androidx/releases/camera) |
| ZXing Core | 3.5.3 | [Apache-2.0](android/hosttap/src/main/assets/licenses/ZXING-LICENSE.txt), [notice](android/hosttap/src/main/assets/licenses/ZXING-NOTICE.txt) | [Versioned source](https://github.com/zxing/zxing/tree/zxing-3.5.3) |
| OkHttp and Okio | 4.12.0 / 3.6.0 | [Apache-2.0](android/hosttap/src/main/assets/licenses/OKHTTP-LICENSE.txt), [Okio license](android/hosttap/src/main/assets/licenses/OKIO-LICENSE.txt) | [OkHttp](https://github.com/square/okhttp/tree/parent-4.12.0) |

The QR scanner requests camera access only when opened. It decodes locally and passes only a valid HostTap pairing link into the existing encrypted storage and pinned TLS connection flow.

## Sparkle (macOS website receiver only)

The macOS website receiver bundles Sparkle 2.10.0, copyright the Sparkle contributors, under its BSD-style license. The exact upstream license is included in the application at `Contents/Resources/licenses/Sparkle-LICENSE.txt`. Sparkle and its helpers are not included in the Mac App Store basic build. Source and license: https://github.com/sparkle-project/Sparkle/tree/2.10.0

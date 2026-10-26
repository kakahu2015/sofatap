# Receiver updates

Packaged desktop receivers include native update controls. Installing an iPhone update does not update a computer receiver.

| Client | Check for updates | Automatic behavior | Installing |
| --- | --- | --- | --- |
| Windows | Right-click the Sofatap tray icon → Check for updates | Enabled by default; checks daily and downloads a verified package | Applies before the next receiver launch, or choose Install and Restart. Explicit restart asks before disconnecting the phone. Closing the window still hides to the tray. |
| macOS website app | Sofatap menu → Check for updates | Enabled by default; Sparkle checks daily and downloads signed updates | Installs through Sparkle on normal quit. Explicit Install and Relaunch waits until the phone disconnects. |
| Linux portable package | Native window → Check for updates | Enabled by default; checks daily and downloads a verified package | Applies before the next receiver launch, or choose Update and Restart while disconnected. A user-owned writable installation directory is required; system-managed installations need a manual update. |
| iOS | KeyTrans in the App Store | Managed by Apple and the user's system settings | iOS version is temporarily not open source. Download [KeyTrans](https://apps.apple.com/app/id6739558003). |
| Mac App Store basic build | Native menu opens App Store Updates | Managed by the App Store | The store build excludes Sparkle. Its store listing is a separate release process. |

The automatic option can be turned off and the choice persists across launches and upgrades. Manual checks remain available. Desktop stable releases do not move users onto a prerelease channel; the current public beta can take newer beta or stable releases.

## Integrity and data

Windows and Linux discovery uses the fixed official `chekusu/sofatap` repository. The updater selects the matching platform/architecture asset, requires the GitHub API's SHA-256 digest and exact byte count, and rejects malformed metadata, unexpected download hosts, incomplete downloads and version downgrades. Archive extraction rejects links and unsafe paths. This is verification against the official HTTPS release metadata; Windows binaries remain unsigned by Authenticode.

Windows uses a copied current updater executable, an installation-bound ticket, same-volume staging and a retained previous-directory backup. The replacement must report a valid backend state; failure stops that replacement and attempts to restore the old directory. User pairing data remains outside the installation directory. Unmanaged installation files are retained unless they conflict with the new package, in which case installation stops.

Linux validates the archive, package version/architecture, executable permissions, backend `--help` startup and Python syntax before replacement. It retains a previous-directory backup and rolls back a failed directory replacement. It does **not** yet supervise GTK or desktop-input health after a completed restart; keep the backup if the new app fails at runtime. Pairing/preferences stay in their original user data locations, and unmanaged package files are preserved or cause a conflict error. No sudo or package-manager settings are changed.

The macOS website build uses pinned Sparkle 2, signed appcasts and Ed25519 archive signatures, plus Developer ID signing and Apple notarization. The private update key stays in the release Mac's Keychain. See [publishing instructions](../scripts/macos/UPDATES.md).

Update checks send ordinary requests to GitHub (or the signed GitHub-hosted Mac feed), without pairing credentials, input content or a device identifier. Network/server failures keep the installed receiver usable. Checks/downloads are separate from successful installation; a built package is separate from a published release and from a tested physical-device upgrade.

## Website and publishing

The website should resolve the latest complete desktop release and keep the last known good links on lookup failures. Publish only complete, verified assets and validate every localized download page. The iOS version is temporarily not open source. Download [KeyTrans](https://apps.apple.com/app/id6739558003).

For each receiver release, update package versions, run regression/build checks, publish the verified artifacts, and then publish matching signed Mac feeds. Confirm anonymous downloads and production website links. Existing active phone connections must not be used as upgrade test targets without a scoped acceptance session.

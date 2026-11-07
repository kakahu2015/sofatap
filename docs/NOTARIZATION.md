# macOS Developer ID distribution

This pipeline creates the direct-download macOS ZIPs. A release is complete only after Apple returns `Accepted`, the ticket is stapled and validated, and Gatekeeper accepts the app. These checks are separate from physical input testing.

## Build from a clean revision

Commit the intended source first, then build from that clean revision. The macOS pairing screen includes iOS, Android, and HarmonyOS. The bundle identifier is read from `scripts/macos/distribution-config.json` and is shared by the build and notarization checks.

The build script discovers the valid Developer ID Application identity installed in the current Mac's Keychain. It stops if more than one eligible identity is installed; select one explicitly with `SOFATAP_SIGN_IDENTITY` in that case. It writes the identity and team identifier found from that local certificate to `BUILD-MANIFEST.json`. No company name or team identifier is copied from an old machine.

Set `SOFATAP_MAC_STAGING` to preserve a caller-owned copy of the exact signed app and build manifest for notarization:

```sh
git status --short
stage_dir="$(mktemp -d /tmp/hosttap-release.XXXXXX)"
SOFATAP_MAC_STAGING="$stage_dir" bash scripts/build-macos.sh arm64
bash scripts/notarize-macos.sh \
  --app "$stage_dir/HostTap-Remote-macOS-arm64/HostTap Remote.app" \
  --manifest "$stage_dir/HostTap-Remote-macOS-arm64/BUILD-MANIFEST.json" \
  --output-dir "$stage_dir/notarization-arm64"
```

Use `x64` for the Intel build. Each architecture needs its own build, staging directory, and notarization output directory. An ad-hoc build is useful for local development but cannot pass notarization.

Preflight is read-only. It checks the clean source revision, app and manifest hashes, bundle ID, installed signing identity and team, all nested code signatures, hardened runtime, secure timestamps, entitlements, Sparkle inventory, and the existing Apple API credential in the current login Keychain. The credential account is configured in `scripts/macos/distribution-config.json`; the helper reads it without printing or committing its private key.

## Submit and finish

Review the preflight report, then create one submission:

```sh
bash scripts/notarize-macos.sh \
  --app "$stage_dir/HostTap-Remote-macOS-arm64/HostTap Remote.app" \
  --manifest "$stage_dir/HostTap-Remote-macOS-arm64/BUILD-MANIFEST.json" \
  --output-dir "$stage_dir/notarization-arm64" \
  --submit
```

The script confirms the signed app's team against the installed local certificate, then creates the submission through Apple's Notary API and uploads the ZIP with the previously verified accelerated S3 single-request path. `uv` supplies the pinned, temporary Python dependencies; credentials stay in the login Keychain or process memory. The submission ID is saved locally before S3 transfer starts, so a transfer timeout can be inspected or resumed without blindly creating another submission. Status and history use Apple's `notarytool`. The default wait is 30 minutes with 15-second polling; `--timeout SECONDS` and `--poll-interval SECONDS` adjust those limits.

Only exact status `Accepted` permits stapling. The script staples a temporary app copy, validates the ticket, runs Gatekeeper assessment and strict code verification, then writes the final archive as `HostTap-Remote-macOS-arm64.zip` or `HostTap-Remote-macOS-x64.zip`. Each completed output directory contains the final ZIP, its SHA-256 file, the source build manifest, a local receipt, and verification logs. Keep the submission archive and receipt local; they are not website release assets.

If a submission times out or the upload outcome is uncertain, inspect the recorded receipt and Apple's `notarytool history` before retrying. When a submission ID is recorded, resume with the same app, manifest, output directory, source revision, and Keychain credential:

```sh
bash scripts/notarize-macos.sh \
  --app "$stage_dir/HostTap-Remote-macOS-arm64/HostTap Remote.app" \
  --manifest "$stage_dir/HostTap-Remote-macOS-arm64/BUILD-MANIFEST.json" \
  --output-dir "/tmp/hosttap-notarization/arm64" \
  --resume
```

For a rejected package, retrieve its log locally with `python3 scripts/macos/notarytool.py log SUBMISSION_ID /tmp/notary-log.json`, fix the issue, commit, rebuild, and create a new submission. Notary logs can include temporary authorization URLs and must not be committed or published.

References: [Apple notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution), [Apple notarization issues](https://developer.apple.com/documentation/security/resolving-common-notarization-issues), [.NET on macOS](https://learn.microsoft.com/en-us/dotnet/core/deploying/macos).

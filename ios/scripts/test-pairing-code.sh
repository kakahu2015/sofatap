#!/bin/bash
# Unified QR routing and metadata checks; no network, Keychain or OS input.
set -euo pipefail
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
PAIRING_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/sofatap-pairing-code-tests.XXXXXX")"
trap 'rm -rf "$PAIRING_STAGE"' EXIT
xcrun --sdk macosx swiftc -parse-as-library -swift-version 5 \
    -target "$(uname -m)-apple-macosx14.0" \
    "$REPO_ROOT/ios/SofaTap/Core/SavedDevice.swift" \
    "$REPO_ROOT/ios/SofaTap/Core/Pairing.swift" \
    "$REPO_ROOT/ios/SofaTap/Core/PairingCode.swift" \
    "$REPO_ROOT/tests/PairingCodeTests.swift" \
    -o "$PAIRING_STAGE/pairing-code-tests"
"$PAIRING_STAGE/pairing-code-tests"

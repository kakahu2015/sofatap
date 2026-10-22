#!/bin/bash
# Production collection logic with in-memory persistence; no real Keychain or input.
set -euo pipefail
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
DEVICE_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/sofatap-device-tests.XXXXXX")"
trap 'rm -rf "$DEVICE_STAGE"' EXIT
xcrun --sdk macosx swiftc -parse-as-library -swift-version 5 \
    -target "$(uname -m)-apple-macosx14.0" \
    "$REPO_ROOT/ios/SofaTap/Core/SavedDevice.swift" \
    "$REPO_ROOT/ios/SofaTap/Core/Pairing.swift" \
    "$REPO_ROOT/ios/SofaTap/Core/TV/WebOSTVProtocol.swift" \
    "$REPO_ROOT/ios/SofaTap/Core/TV/WebOSTVPairingStore.swift" \
    "$REPO_ROOT/tests/SavedDeviceTests.swift" \
    -o "$DEVICE_STAGE/saved-device-tests"
"$DEVICE_STAGE/saved-device-tests"

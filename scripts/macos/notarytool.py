#!/usr/bin/env python3
"""Run Apple's notarytool using the existing API key stored in macOS Keychain."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


CONFIG = json.loads((Path(__file__).with_name("distribution-config.json")).read_text())


def main():
    if len(sys.argv) < 2:
        raise SystemExit("Usage: notarytool.py <notarytool arguments>")
    keychain = subprocess.run(
        [
            "security", "find-generic-password",
            "-s", CONFIG["notaryKeychainService"],
            "-a", CONFIG["notaryKeychainAccount"],
            "-w",
        ],
        check=False, capture_output=True, text=True,
    )
    if keychain.returncode:
        raise SystemExit("Apple notary credentials are unavailable in this Mac's login Keychain.")
    try:
        credential = json.loads(keychain.stdout)
        private_key = credential["private_key_pem"]
        key_id = credential["key_id"]
    except (ValueError, KeyError, TypeError):
        raise SystemExit("The configured Apple API credential is incomplete; no credential data was printed.")
    if not isinstance(private_key, str) or not private_key.startswith("-----BEGIN PRIVATE KEY-----"):
        raise SystemExit("The configured Apple API credential has no valid private key; no credential data was printed.")
    if not isinstance(key_id, str) or not key_id:
        raise SystemExit("The configured Apple API credential has no key ID.")

    with tempfile.TemporaryDirectory(prefix="hosttap-notary-key-") as temporary:
        key_path = Path(temporary) / "AuthKey.p8"
        descriptor = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(private_key)
        command = ["xcrun", "notarytool", *sys.argv[1:], "--key", str(key_path), "--key-id", key_id]
        issuer = credential.get("issuer_id")
        if issuer:
            command.extend(["--issuer", issuer])
        result = subprocess.run(command)
        raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()

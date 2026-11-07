#!/usr/bin/env python3
"""Submit through Apple's historical API/S3 route; query with xcrun notarytool."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


CONFIG = json.loads((Path(__file__).with_name("distribution-config.json")).read_text())


def keychain_credential():
    result = subprocess.run(
        [
            "security", "find-generic-password",
            "-s", CONFIG["notaryKeychainService"],
            "-a", CONFIG["notaryKeychainAccount"],
            "-w",
        ],
        check=False, capture_output=True, text=True,
    )
    if result.returncode:
        raise RuntimeError("Apple notary credentials are unavailable in this Mac's login Keychain.")
    try:
        credential = json.loads(result.stdout)
        private_key = credential["private_key_pem"]
        key_id = credential["key_id"]
    except (ValueError, KeyError, TypeError):
        raise RuntimeError("The configured Apple API credential is incomplete; no credential data was printed.")
    if not isinstance(private_key, str) or not private_key.startswith("-----BEGIN PRIVATE KEY-----"):
        raise RuntimeError("The configured Apple API credential has no valid private key; no credential data was printed.")
    if not isinstance(key_id, str) or not key_id:
        raise RuntimeError("The configured Apple API credential has no key ID.")
    return credential


def run_notarytool(arguments):
    credential = keychain_credential()
    with tempfile.TemporaryDirectory(prefix="hosttap-notary-key-") as temporary:
        key_path = Path(temporary) / "AuthKey.p8"
        descriptor = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(credential["private_key_pem"])
        command = ["xcrun", "notarytool", *arguments, "--key", str(key_path), "--key-id", credential["key_id"]]
        if credential.get("issuer_id"):
            command.extend(["--issuer", credential["issuer_id"]])
        result = subprocess.run(command)
        raise SystemExit(result.returncode)


def submit_via_api(archive_path, display_name, submission_id_path):
    try:
        import boto3
        import jwt
        from boto3.s3.transfer import TransferConfig
        from botocore.config import Config
    except ImportError as error:
        raise RuntimeError("The temporary notarization environment is incomplete; no upload was started.") from error

    archive_input = Path(archive_path)
    id_input = Path(submission_id_path)
    if archive_input.is_symlink() or not archive_input.is_file():
        raise RuntimeError("Submission archive must be a regular local file.")
    if id_input.exists() or id_input.is_symlink():
        raise RuntimeError("A submission ID file already exists; inspect the existing Apple record before retrying.")
    if not id_input.parent.is_dir():
        raise RuntimeError("The notarization output directory is missing.")
    archive = archive_input.resolve()
    id_path = id_input.resolve()
    if archive.stat().st_size >= 64 * 1024 * 1024:
        raise RuntimeError("This accelerated single-request upload is intended for archives smaller than 64 MiB.")

    credential = keychain_credential()
    import hashlib
    digest = hashlib.sha256()
    with archive.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)

    import time
    now = int(time.time())
    claims = {"iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}
    if credential.get("issuer_id"):
        claims["iss"] = credential["issuer_id"]
    else:
        claims["sub"] = "user"
    token = jwt.encode(
        claims, credential["private_key_pem"], algorithm="ES256",
        headers={"kid": credential["key_id"], "typ": "JWT"},
    )
    if isinstance(token, bytes):
        token = token.decode("ascii")

    api_url = "https://appstoreconnect.apple.com/notary/v2/submissions"

    def request(path, body=None):
        command = [
            "curl", "--silent", "--show-error", "--fail-with-body",
            "--connect-timeout", "15", "--max-time", "120", "--config", "-",
            api_url + path,
        ]
        if body is not None:
            command.extend(["-H", "Content-Type: application/json", "--data", json.dumps(body)])
        authorization = "header = " + json.dumps("Authorization: Bearer " + token) + "\n"
        result = subprocess.run(command, input=authorization, capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(f"Apple Notary API request failed (curl exit {result.returncode}); response details withheld.")
        try:
            return json.loads(result.stdout)
        except ValueError as error:
            raise RuntimeError("Apple Notary API returned invalid JSON; response details withheld.") from error

    created = request("", {"submissionName": display_name, "sha256": digest.hexdigest()})
    data = created.get("data", {})
    submission_id = data.get("id")
    attributes = data.get("attributes", {})
    if not isinstance(submission_id, str) or not attributes:
        raise RuntimeError("Apple did not return a complete submission record; inspect history before retrying.")

    # Persist only the public submission ID before transferring bytes. If S3
    # times out, the caller can recover this exact request without resubmitting.
    descriptor = os.open(id_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(submission_id + "\n")
        output.flush()
        os.fsync(output.fileno())

    required = ("awsAccessKeyId", "awsSecretAccessKey", "awsSessionToken", "bucket", "object")
    if any(not attributes.get(key) for key in required):
        raise RuntimeError("Apple omitted temporary S3 upload credentials; their contents were withheld.")
    client = boto3.client(
        "s3", region_name="us-west-2",
        aws_access_key_id=attributes["awsAccessKeyId"],
        aws_secret_access_key=attributes["awsSecretAccessKey"],
        aws_session_token=attributes["awsSessionToken"],
        config=Config(
            connect_timeout=20, read_timeout=90,
            retries={"max_attempts": 3, "mode": "standard"},
            s3={"use_accelerate_endpoint": True},
        ),
    )
    transferred = 0
    reported = 0

    def progress(size):
        nonlocal transferred, reported
        transferred += size
        if transferred - reported >= 4 * 1024 * 1024:
            print(f"Upload read: {transferred / 1024 / 1024:.1f} MiB", file=sys.stderr, flush=True)
            reported = transferred

    try:
        client.upload_file(
            str(archive), attributes["bucket"], attributes["object"], Callback=progress,
            Config=TransferConfig(multipart_threshold=64 * 1024 * 1024, use_threads=False),
        )
    except Exception as error:
        raise RuntimeError(f"Apple S3 transfer failed ({type(error).__name__}); submission ID is retained locally.") from None

    print("S3 transfer completed.", file=sys.stderr, flush=True)
    finished = request("/" + submission_id)
    final_data = finished.get("data", {})
    final_id = final_data.get("id")
    status = final_data.get("attributes", {}).get("status")
    if final_id != submission_id or not isinstance(status, str):
        raise RuntimeError("Apple did not confirm this uploaded archive; inspect the recorded ID before retrying.")
    print(json.dumps({"id": submission_id, "status": status, "name": display_name}))


def main():
    arguments = sys.argv[1:]
    if not arguments:
        raise SystemExit("Usage: notarytool.py api-submit ARCHIVE DISPLAY_NAME SUBMISSION_ID_FILE | <notarytool query arguments>")

    if arguments[0] == "api-submit":
        if len(arguments) != 4:
            raise SystemExit("Usage: notarytool.py api-submit ARCHIVE DISPLAY_NAME SUBMISSION_ID_FILE")
        uv = shutil.which("uv")
        if not uv:
            raise SystemExit("uv is required for Apple's S3 uploader; install uv before submitting.")
        helper = str(Path(__file__).resolve())
        command = [
            uv, "run", "--no-project", "--python", "3.9",
            "--with", "PyJWT==2.15.1", "--with", "boto3==1.42.97", "--with", "cryptography==50.0.2",
            "python", helper, "api-submit-worker", *arguments[1:],
        ]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.stderr:
            for line in result.stderr.splitlines():
                if line.startswith("Upload read:") or line == "S3 transfer completed.":
                    print(line, file=sys.stderr, flush=True)
        if result.returncode:
            # The ID file, if created, records an Apple submission before S3 transfer.
            raise SystemExit("Apple API/S3 upload did not complete successfully; inspect the local receipt and ID before retrying.")
        print(result.stdout.strip())
        return

    if arguments[0] == "api-submit-worker":
        if len(arguments) != 4:
            raise SystemExit("Invalid internal uploader arguments.")
        try:
            submit_via_api(*arguments[1:])
        except Exception as error:
            raise SystemExit(str(error)) from None
        return

    if arguments[0] == "submit":
        raise SystemExit("Direct notarytool uploads are disabled; use the verified api-submit path.")
    run_notarytool(arguments)


if __name__ == "__main__":
    main()

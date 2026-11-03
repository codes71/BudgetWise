#!/usr/bin/env python3
"""Verify a signed friends APK and prepare local release files. Never publishes."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from typing import Any
from urllib.parse import urlsplit

APPLICATION_ID = "app.budgetwise"
REPOSITORY = "codes71/BudgetWise"
MAX_APK_BYTES = 150_000_000
MAX_MANIFEST_BYTES = 32_768
MAX_VERSION_CODE = 2_147_483_647


class ReleaseError(ValueError):
    """The APK or release description is not safe to publish."""


def _integer(value: Any, name: str, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ReleaseError(f"{name} must be an integer between {minimum} and {maximum}.")
    return value


def normalize_fingerprint(value: str) -> str:
    digest = value.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ReleaseError("Expected signing certificate must be a SHA-256 fingerprint.")
    return digest


def previous_version(manifest: dict[str, Any]) -> int:
    if not isinstance(manifest, dict):
        raise ReleaseError("Previous manifest must be a JSON object.")
    if (type(manifest.get("schemaVersion")) is not int or manifest.get("schemaVersion") != 1 or manifest.get("channel") != "friends"
            or manifest.get("applicationId") != APPLICATION_ID):
        raise ReleaseError("Previous manifest must use the BudgetWise friends schema.")
    if "release" in manifest:
        if manifest["release"] is None and "versionCode" not in manifest:
            return 0
        raise ReleaseError("Only the no-release sentinel may contain the release field.")
    return _integer(manifest.get("versionCode"), "Previous versionCode", 1, MAX_VERSION_CODE)


def release_names(version_name: str, version_code: int) -> tuple[str, str]:
    # Keep command arguments, URL segments and downloaded filenames unambiguous.
    if not re.fullmatch(r"[0-9][A-Za-z0-9._-]{0,63}", version_name):
        raise ReleaseError("versionName must start with a digit and contain only letters, digits, dot, underscore or hyphen.")
    if ".." in version_name:
        raise ReleaseError("versionName may not contain consecutive dots.")
    return f"android-friends-v{version_name}-{version_code}", f"BudgetWise-{version_name}-{version_code}.apk"


def validate_download_url(url: str) -> str:
    parts = urlsplit(url)
    if (parts.scheme != "https" or parts.netloc != "github.com" or parts.query or parts.fragment
            or not re.fullmatch(r"/codes71/BudgetWise/releases/download/android-friends-v[0-9][A-Za-z0-9._-]+/BudgetWise-[0-9][A-Za-z0-9._-]+\.apk", parts.path)
            or ".." in parts.path or "%" in url):
        raise ReleaseError("APK URL must be a direct HTTPS BudgetWise friends GitHub release asset URL.")
    return url


def inspect_badging(badging: str) -> dict[str, Any]:
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if not package or package.group(1) != APPLICATION_ID:
        raise ReleaseError("APK applicationId must be app.budgetwise.")
    try:
        version_code = int(package.group(2))
    except ValueError as error:
        raise ReleaseError("APK versionCode is not an integer.") from error
    _integer(version_code, "APK versionCode", 1, MAX_VERSION_CODE)
    release_names(package.group(3), version_code)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.M)
    if not sdk:
        raise ReleaseError("APK does not declare a minimum SDK.")
    if "application-debuggable" in badging:
        raise ReleaseError("Debuggable APKs must not be published to the friends channel.")
    if "uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'" not in badging:
        raise ReleaseError("Friends APK must declare its updater install permission; do not publish the Play release variant.")
    return {"versionCode": version_code, "versionName": package.group(3), "minSdk": int(sdk.group(1))}


def verify_certificate(signature: str, expected_fingerprint: str) -> str:
    fingerprint = normalize_fingerprint(expected_fingerprint)
    digests = {value.lower() for value in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature)}
    if digests != {fingerprint}:
        raise ReleaseError("APK signing certificate does not match the expected release certificate.")
    if re.search(r"certificate DN:.*(?:CN=Android Debug|O=Android,)", signature, re.I):
        raise ReleaseError("Android Debug signing certificates must not be published to the friends channel.")
    if not re.search(r"^Number of signers: 1$", signature, re.M):
        raise ReleaseError("Exactly one signing identity is required.")
    return fingerprint


def make_manifest(metadata: dict[str, Any], apk: Path, previous: dict[str, Any], notes: str) -> dict[str, Any]:
    code = _integer(metadata["versionCode"], "APK versionCode", 1, MAX_VERSION_CODE)
    if code <= previous_version(previous):
        raise ReleaseError("APK versionCode must be higher than the previous published version.")
    _integer(metadata["minSdk"], "APK minSdk", 26, 10_000)
    size = apk.stat().st_size
    _integer(size, "APK size", 1, MAX_APK_BYTES)
    if len(notes) > 4_000:
        raise ReleaseError("Release notes must be at most 4000 characters.")
    tag, asset = release_names(metadata["versionName"], code)
    url = validate_download_url(f"https://github.com/{REPOSITORY}/releases/download/{tag}/{asset}")
    manifest = {
        "schemaVersion": 1,
        "channel": "friends",
        "applicationId": APPLICATION_ID,
        **metadata,
        "apkUrl": url,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "sizeBytes": size,
        "notes": notes.strip(),
    }
    if len((json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()) > MAX_MANIFEST_BYTES:
        raise ReleaseError("Release manifest exceeds 32 KiB.")
    return manifest


def _run(command: list[str]) -> str:
    # No private key or password arguments are needed to verify a public APK.
    result = subprocess.run(command, check=False, capture_output=True, text=True, env=os.environ.copy())
    if result.returncode:
        raise ReleaseError(f"{Path(command[0]).name} failed; verify Android SDK tools and JAVA_HOME.")
    return result.stdout + result.stderr


def prepare(apk: Path, build_tools: Path, expected_fingerprint: str, previous_manifest: Path,
            output: Path, notes: str) -> dict[str, Any]:
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise ReleaseError("Supply an existing APK file.")
    _integer(apk.stat().st_size, "APK size", 1, MAX_APK_BYTES)
    signature = _run([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
    fingerprint = verify_certificate(signature, expected_fingerprint)
    alignment = _run([str(build_tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(apk)])
    badging = _run([str(build_tools / "aapt2"), "dump", "badging", str(apk)])
    metadata = inspect_badging(badging)
    previous = json.loads(previous_manifest.read_text())
    manifest = make_manifest(metadata, apk, previous, notes)
    tag, asset = release_names(metadata["versionName"], metadata["versionCode"])
    if output.exists():
        raise ReleaseError("Output directory already exists. Use a new directory; releases are not overwritten.")
    output.mkdir(parents=True)
    shutil.copy2(apk, output / asset)
    (output / "friends.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text(f"{manifest['sha256']}  {asset}\n")
    (output / "release-notes.md").write_text(notes.strip() + "\n")
    report = {"tag": tag, "asset": asset, "signingCertificateSha256": fingerprint,
              "signatureVerified": True, "alignmentVerified": True, "manifest": manifest}
    (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (output / "signature-verification.txt").write_text(signature)
    (output / "alignment-verification.txt").write_text(alignment)
    (output / "apk-badging.txt").write_text(badging)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--expected-certificate-sha256", required=True, help="Public release certificate fingerprint, never a password.")
    parser.add_argument("--previous-manifest", type=Path, required=True)
    parser.add_argument("--notes-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New output directory; existing directories are refused.")
    args = parser.parse_args()
    try:
        report = prepare(args.apk.resolve(), args.build_tools.resolve(), args.expected_certificate_sha256,
                         args.previous_manifest.resolve(), args.output.resolve(), args.notes_file.read_text())
    except (ReleaseError, OSError, json.JSONDecodeError) as error:
        parser.exit(1, f"Release preparation failed: {error}\n")
    print(json.dumps({"tag": report["tag"], "asset": report["asset"],
                      "output": str(args.output.resolve()), "published": False}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Local release guards. Never uploads, publishes, or reads a private key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "app.pocketpeek"
API_URL = "https://budgetwis3.vercel.app/api/v1/"
ANDROID = "{http://schemas.android.com/apk/res/android}"
MAX_CODE = 2_100_000_000


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def version_code(raw):
    require(bool(re.fullmatch(r"[1-9][0-9]{0,9}", str(raw))), "Version code must be a positive decimal integer.")
    code = int(raw)
    require(code <= MAX_CODE, "Version code exceeds the Play limit.")
    return code


def validate_inputs(env, actual_sha):
    sha = env.get("INPUT_SOURCE_SHA", "")
    require(bool(re.fullmatch(r"[0-9a-f]{40}", sha)), "Approve a full lowercase commit SHA.")
    require(env.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Only an approved manual dispatch can publish.")
    require(sha == env.get("GITHUB_SHA") == actual_sha, "Approved SHA, dispatch SHA and checkout differ.")
    require(env.get("INPUT_APPROVE_INTERNAL") == "true", "Explicit internal release approval is required.")
    origin = env.get("INPUT_APPROVED_BY")
    require(origin in {"owner", "agent"}, "Approval must identify owner or agent.")
    name = env.get("INPUT_VERSION_NAME", "")
    require(bool(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?", name)) and len(name) <= 64,
            "Version name must be an explicit safe semantic version.")
    notes = env.get("INPUT_RELEASE_NOTES", "").strip()
    require(0 < len(notes) <= 500 and "\x00" not in notes, "Release notes must contain 1 to 500 characters.")
    return {"sourceSha": sha, "versionCode": version_code(env.get("INPUT_VERSION_CODE", "")),
            "versionName": name, "approvedBy": origin, "actor": env.get("GITHUB_ACTOR", ""),
            "triggeringActor": env.get("GITHUB_TRIGGERING_ACTOR", ""),
            "runId": env.get("GITHUB_RUN_ID", ""), "runAttempt": env.get("GITHUB_RUN_ATTEMPT", ""),
            "package": PACKAGE, "track": "internal", "status": "completed", "releaseNotes": notes}


def fingerprint(raw):
    value = raw.replace(":", "").lower()
    require(bool(re.fullmatch(r"[0-9a-f]{64}", value)), "Expected upload certificate SHA-256 is required.")
    return value


def validate_config(env):
    for key in ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS",
                "ANDROID_KEY_PASSWORD", "PLAY_SERVICE_ACCOUNT_JSON"):
        require(bool(env.get(key, "").strip()), "Missing required release configuration: " + key)
    require(env.get("RELEASE_API_BASE_URL") == API_URL, "Release API must equal the approved canonical API URL.")
    return fingerprint(env.get("EXPECTED_UPLOAD_CERT_SHA256", ""))


def verify_manifest(xml, code, name):
    try:
        root = ET.fromstring(xml)
        require(root.tag == "manifest" and root.get("package") == PACKAGE, "Wrong AAB package.")
        require(root.get(ANDROID + "versionCode") == str(code) and root.get(ANDROID + "versionName") == name,
                "AAB version differs from approved version.")
        sdk = root.find("uses-sdk")
        require(sdk is not None and sdk.get(ANDROID + "minSdkVersion") == "26"
                and int(sdk.get(ANDROID + "targetSdkVersion", "0")) >= 36, "Unexpected SDK contract.")
        app = root.find("application")
        require(app is not None and app.get(ANDROID + "debuggable", "false") == "false", "Debuggable AAB refused.")
        require(app.get(ANDROID + "usesCleartextTraffic", "false") == "false", "Cleartext release refused.")
        permissions = {item.get(ANDROID + "name") for item in root if item.tag.startswith("uses-permission")}
        require("android.permission.REQUEST_INSTALL_PACKAGES" not in permissions, "Friends installer permission refused.")
        for item in app.findall("provider"):
            require(not item.get(ANDROID + "authorities", "").endswith(".update-files")
                    and "UpdateFileProvider" not in item.get(ANDROID + "name", ""), "Friends updater provider refused.")
    except (ET.ParseError, ValueError) as exc:
        raise ReleaseError("Invalid bundle manifest.") from exc


def verify_bundle_config(config):
    optimizations = config.get("optimizations", {})
    native = optimizations.get("uncompressNativeLibraries", {})
    require(native.get("enabled") is True and native.get("alignment") == "PAGE_ALIGNMENT_16K",
            "Bundle must request uncompressed 16 KB native libraries.")
    dimensions = optimizations.get("splitsConfig", {}).get("splitDimension", [])
    require(any(d.get("value") == "LANGUAGE" and d.get("negate") is True for d in dimensions),
            "Language splitting would drop the in-app language resources.")


def verify_elf(data):
    require(len(data) >= 64 and data[:6] == b"\x7fELF\x02\x01", "Native library is not little-endian ELF64.")
    try:
        require(struct.unpack_from("<H", data, 18)[0] == 183, "Native library is not ARM64.")
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        require(size >= 56 and count > 0 and offset + size * count <= len(data), "Invalid native program headers.")
        loads = 0
        for index in range(count):
            start = offset + index * size
            if struct.unpack_from("<I", data, start)[0] != 1:
                continue
            loads += 1
            file_offset, address = struct.unpack_from("<QQ", data, start + 8)
            alignment = struct.unpack_from("<Q", data, start + 48)[0]
            require(alignment >= 16384 and (file_offset - address) % 16384 == 0,
                    "Native LOAD segment is not 16 KB compatible.")
        require(loads > 0, "Native library has no LOAD segments.")
    except struct.error as exc:
        raise ReleaseError("Truncated native library.") from exc


def verify_archive(aab):
    try:
        with zipfile.ZipFile(aab) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), "Duplicate bundle archive entries refused.")
            dex = [n for n in names if re.fullmatch(r"base/dex/classes[0-9]*\.dex", n)]
            require(bool(dex), "AAB has no base DEX.")
            urls = set()
            for name in dex:
                urls.update(re.findall(rb"https?://[^\x00\s]+/api/v1/", archive.read(name)))
            require(urls == {API_URL.encode()}, "Compiled API URL differs from the canonical API.")
            native = [n for n in names if n.endswith(".so")]
            require(bool(native), "AAB has no native libraries.")
            for name in native:
                require(name.startswith("base/lib/arm64-v8a/"), "Unexpected native ABI or module.")
                verify_elf(archive.read(name))
            return {"nativeLibraries": len(native), "nativeElf16KB": True, "apiBaseUrl": API_URL}
    except (zipfile.BadZipFile, KeyError) as exc:
        raise ReleaseError("Invalid bundle archive.") from exc


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, env={**os.environ, "LC_ALL": "C"})
    require(result.returncode == 0, "Release verification tool failed: " + Path(args[0]).name)
    return result.stdout + result.stderr


def verify_bundle(aab, mapping, bundletool, approved, expected_cert):
    require(aab.is_file() and mapping.is_file() and mapping.stat().st_size > 0, "AAB and nonempty R8 mapping are required.")
    signature = command(["jarsigner", "-verify", str(aab)])
    require("jar verified." in signature and "unsigned entries" not in signature.lower(), "Bundle is not fully signed.")
    cert = command(["keytool", "-printcert", "-jarfile", str(aab)])
    certificates = {fingerprint(v) for v in re.findall(r"SHA256:\s*([0-9A-Fa-f:]+)", cert)}
    require(certificates == {fingerprint(expected_cert)} and "android debug" not in cert.lower(), "Unexpected AAB signer.")
    command(["java", "-jar", str(bundletool), "validate", "--bundle=" + str(aab)])
    manifest = command(["java", "-jar", str(bundletool), "dump", "manifest", "--bundle=" + str(aab)])
    verify_manifest(manifest, approved["versionCode"], approved["versionName"])
    config = command(["java", "-jar", str(bundletool), "dump", "config", "--bundle=" + str(aab)])
    verify_bundle_config(json.loads(config))
    details = verify_archive(aab)
    return {**approved, **details, "aabSha256": hashlib.sha256(aab.read_bytes()).hexdigest(),
            "mappingSha256": hashlib.sha256(mapping.read_bytes()).hexdigest(),
            "uploadCertificateSha256": fingerprint(expected_cert), "verified": True}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["inputs", "config", "bundle"])
    parser.add_argument("--receipt", type=Path, default=Path("release/approval.json"))
    parser.add_argument("--aab", type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--bundletool", type=Path)
    args = parser.parse_args()
    try:
        if args.mode == "config":
            validate_config(os.environ)
            print("Required release configuration is present.")
            return 0
        sha = command(["git", "rev-parse", "HEAD"]).strip()
        approved = validate_inputs(os.environ, sha)
        if args.mode == "inputs":
            write_json(args.receipt, approved)
            notes = args.receipt.parent / "whatsnew" / "whatsnew-en-US"
            notes.parent.mkdir(parents=True, exist_ok=True)
            notes.write_text(approved["releaseNotes"], encoding="utf-8")
        else:
            require(all([args.aab, args.mapping, args.bundletool]), "Bundle verification paths are required.")
            require(os.environ.get("RELEASE_API_BASE_URL") == API_URL, "Unexpected release API configuration.")
            receipt = verify_bundle(args.aab, args.mapping, args.bundletool, approved,
                                    os.environ.get("EXPECTED_UPLOAD_CERT_SHA256", ""))
            write_json(args.receipt, receipt)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
                    output.write("aab_sha256=" + receipt["aabSha256"] + "\n")
        print("Release guard passed: " + args.mode)
        return 0
    except (ReleaseError, OSError, ValueError) as exc:
        # Tools and configuration can contain secrets: do not print raw subprocess output.
        print("Release guard failed: " + (str(exc) if isinstance(exc, ReleaseError) else type(exc).__name__), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

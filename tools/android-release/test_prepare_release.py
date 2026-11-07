import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("prepare_release", Path(__file__).with_name("prepare_release.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

CERT = "a" * 64
SIGNATURE = f"Verifies\nNumber of signers: 1\nV2 Signer: certificate DN: CN=BudgetWise, O=BudgetWise\nV2 Signer: certificate SHA-256 digest: {CERT}\n"
BADGING = "package: name='app.pocketpeek' versionCode='4' versionName='0.3.0'\nminSdkVersion:'26'\ntargetSdkVersion:'36'\nuses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'\n"
EMPTY = {"schemaVersion": 1, "channel": "friends", "applicationId": "app.pocketpeek", "release": None}


class ReleasePreparationTest(unittest.TestCase):
    def test_empty_manifest_starts_at_zero_but_invalid_identity_is_not_a_reset(self):
        self.assertEqual(0, release.previous_version(EMPTY))
        for invalid in [dict(EMPTY, applicationId="other"), dict(EMPTY, applicationId="app.budgetwise"),
                        dict(EMPTY, schemaVersion=True),
                        dict(EMPTY, release={}), dict(EMPTY, versionCode=2)]:
            with self.subTest(invalid=invalid), self.assertRaises(release.ReleaseError):
                release.previous_version(invalid)

    def test_rejects_wrong_package_and_debuggable_binary(self):
        for value in [BADGING.replace("app.pocketpeek", "app.other"),
                      BADGING.replace("app.pocketpeek", "app.budgetwise"), BADGING + "application-debuggable\n",
                      BADGING.replace("uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'\n", "")]:
            with self.subTest(value=value), self.assertRaises(release.ReleaseError):
                release.inspect_badging(value)

    def test_only_expected_single_non_debug_signing_identity_is_accepted(self):
        self.assertEqual(CERT, release.verify_certificate(SIGNATURE, ":".join(["aa"] * 32)))
        for signature in [SIGNATURE.replace(CERT, "b" * 64),
                          SIGNATURE.replace("CN=BudgetWise", "CN=Android Debug"),
                          SIGNATURE.replace("Number of signers: 1", "Number of signers: 2"),
                          SIGNATURE.replace("Number of signers: 1", "Number of signers: 10"),
                          SIGNATURE + "V3 Signer: certificate SHA-256 digest: " + "b" * 64]:
            with self.subTest(signature=signature), self.assertRaises(release.ReleaseError):
                release.verify_certificate(signature, CERT)

    def test_url_allowlist_rejects_redirector_and_encoded_paths(self):
        good = "https://github.com/codes71/BudgetWise/releases/download/pocket-peek-android-friends-v0.3.0-4/PocketPeek-0.3.0-4.apk"
        self.assertEqual(good, release.validate_download_url(good))
        for value in [good.replace("https:", "http:"), good.replace("github.com", "github.com.evil.test"),
                      good.replace("codes71", "attacker"), good + "?next=https://evil.test", good + "#fragment",
                      good.replace("0.3.0-4/", "%2e%2e/"), good.replace("github.com", "user@github.com"),
                      good.replace("pocket-peek-android-friends-v", "android-friends-v").replace("PocketPeek-", "BudgetWise-"),
                      good.replace("github.com", "github.com:443")]:
            with self.subTest(value=value), self.assertRaises(release.ReleaseError):
                release.validate_download_url(value)

    def test_manifest_uses_inspected_version_hash_and_monotonic_code(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "input.apk"
            apk.write_bytes(b"verified test artifact")
            metadata = release.inspect_badging(BADGING)
            manifest = release.make_manifest(metadata, apk, EMPTY, "  A new feature.  ")
            self.assertNotIn("release", manifest)
            self.assertEqual(4, manifest["versionCode"])
            self.assertEqual("app.pocketpeek", manifest["applicationId"])
            self.assertEqual(apk.stat().st_size, manifest["sizeBytes"])
            self.assertEqual("A new feature.", manifest["notes"])
            self.assertTrue(manifest["apkUrl"].endswith("pocket-peek-android-friends-v0.3.0-4/PocketPeek-0.3.0-4.apk"))
            for old_code in [4, 5]:
                with self.subTest(old_code=old_code), self.assertRaises(release.ReleaseError):
                    release.make_manifest(metadata, apk, dict(manifest, versionCode=old_code), "")

    def test_empty_oversized_and_unsafe_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "input.apk"
            apk.write_bytes(b"")
            with self.assertRaises(release.ReleaseError):
                release.make_manifest(release.inspect_badging(BADGING), apk, EMPTY, "")
            apk.write_bytes(b"x")
            with mock.patch.object(release, "MAX_APK_BYTES", 0), self.assertRaises(release.ReleaseError):
                release.make_manifest(release.inspect_badging(BADGING), apk, EMPTY, "")
            for name in ["../../other", "0.2.0/evil", "0.2.0\nNAME=bad", "0.2.0?foo", "0..2"]:
                with self.subTest(name=name), self.assertRaises(release.ReleaseError):
                    release.release_names(name, 2)
            with self.assertRaises(release.ReleaseError):
                release.make_manifest(release.inspect_badging(BADGING), apk, EMPTY, "x" * 4001)

    def test_preparation_copies_verified_bytes_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "input.apk"
            apk.write_bytes(b"verified APK bytes")
            previous = root / "previous.json"
            previous.write_text(json.dumps(EMPTY))
            out = root / "release"
            with mock.patch.object(release, "_run", side_effect=[SIGNATURE, "Verification successful\n", BADGING]):
                result = release.prepare(apk, root, CERT, previous, out, "App updates.")
            self.assertEqual(apk.read_bytes(), (out / result["asset"]).read_bytes())
            self.assertEqual("PocketPeek-0.3.0-4.apk", result["asset"])
            self.assertEqual(result["manifest"], json.loads((out / "pocket-peek-android-friends.json").read_text()))
            self.assertFalse((out / "friends.json").exists())
            self.assertEqual(result["manifest"]["sha256"], (out / "SHA256SUMS.txt").read_text().split()[0])
            with mock.patch.object(release, "_run", side_effect=[SIGNATURE, "Verification successful\n", BADGING]), self.assertRaises(release.ReleaseError):
                release.prepare(apk, root, CERT, previous, out, "App updates.")

    def test_failed_signature_never_creates_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / "input.apk"
            apk.write_bytes(b"invalid APK")
            with mock.patch.object(release, "_run", side_effect=release.ReleaseError("Bad signature")), self.assertRaises(release.ReleaseError):
                release.prepare(apk, root, CERT, root / "previous.json", root / "release", "")
            self.assertFalse((root / "release").exists())


if __name__ == "__main__":
    unittest.main()

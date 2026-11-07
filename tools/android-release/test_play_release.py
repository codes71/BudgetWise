import copy
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zipfile
import yaml

SPEC = importlib.util.spec_from_file_location("play_release", Path(__file__).with_name("play_release.py"))
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = "a" * 40
CERT = "b" * 64
ENV = {"INPUT_SOURCE_SHA": SHA, "GITHUB_SHA": SHA, "GITHUB_EVENT_NAME": "workflow_dispatch",
       "INPUT_APPROVE_INTERNAL": "true", "INPUT_APPROVED_BY": "agent", "INPUT_VERSION_CODE": "5",
       "INPUT_VERSION_NAME": "0.3.1", "INPUT_RELEASE_NOTES": "Fix sync.", "GITHUB_ACTOR": "codes71"}
CONFIG = {key: "synthetic" for key in ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD",
          "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD", "PLAY_SERVICE_ACCOUNT_JSON")}
CONFIG.update(RELEASE_API_BASE_URL=release.API_URL, EXPECTED_UPLOAD_CERT_SHA256=CERT)
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.pocketpeek"
 android:versionCode="5" android:versionName="0.3.1"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
 <application android:debuggable="false"/></manifest>'''
BUNDLE_CONFIG = {"optimizations": {"uncompressNativeLibraries": {"enabled": True, "alignment": "PAGE_ALIGNMENT_16K"},
                  "splitsConfig": {"splitDimension": [{"value": "LANGUAGE", "negate": True}]}}}


def elf(alignment=16384, address=0):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, 183)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<QQ", data, 72, 0, address)
    struct.pack_into("<Q", data, 112, alignment)
    return bytes(data)


class ApprovalTest(unittest.TestCase):
    def test_either_owner_or_agent_can_approve_without_human_gate(self):
        for origin in ["owner", "agent"]:
            result = release.validate_inputs(dict(ENV, INPUT_APPROVED_BY=origin), SHA)
            self.assertEqual(origin, result["approvedBy"])
            self.assertEqual(("internal", "completed"), (result["track"], result["status"]))

    def test_sha_drift_at_dispatch_or_checkout_is_refused(self):
        for env, checkout in [(dict(ENV, INPUT_SOURCE_SHA="b"*40), SHA),
                              (dict(ENV, GITHUB_SHA="b"*40), SHA), (ENV, "b"*40)]:
            with self.subTest(checkout=checkout), self.assertRaises(release.ReleaseError):
                release.validate_inputs(env, checkout)

    def test_implicit_approval_automatic_events_and_missing_metadata_are_refused(self):
        for key, invalid in [("INPUT_APPROVE_INTERNAL", "false"), ("INPUT_APPROVE_INTERNAL", ""),
                             ("GITHUB_EVENT_NAME", "push"), ("GITHUB_EVENT_NAME", "workflow_run"),
                             ("INPUT_APPROVED_BY", "someone"), ("INPUT_SOURCE_SHA", "short")]:
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.validate_inputs(dict(ENV, **{key: invalid}), SHA)

    def test_unsafe_versions_and_notes_are_rejected(self):
        for key, invalid in [("INPUT_VERSION_CODE", "0"), ("INPUT_VERSION_CODE", "2100000001"),
                             ("INPUT_VERSION_CODE", "5\nBAD=1"), ("INPUT_VERSION_NAME", "$(id)"),
                             ("INPUT_VERSION_NAME", "0.3.1\nBAD=1"), ("INPUT_RELEASE_NOTES", " "),
                             ("INPUT_RELEASE_NOTES", "a"*501), ("INPUT_RELEASE_NOTES", "a\x00b")]:
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.validate_inputs(dict(ENV, **{key: invalid}), SHA)

    def test_multiline_notes_are_data_and_approval_records_actor(self):
        result = release.validate_inputs(dict(ENV, INPUT_RELEASE_NOTES="Line one\nLine two"), SHA)
        self.assertEqual("Line one\nLine two", result["releaseNotes"])
        self.assertEqual("codes71", result["actor"])

    def test_missing_config_fails_instead_of_skipping(self):
        self.assertEqual(CERT, release.validate_config(CONFIG))
        for key in CONFIG:
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.validate_config(dict(CONFIG, **{key: ""}))
        with self.assertRaises(release.ReleaseError):
            release.validate_config(dict(CONFIG, RELEASE_API_BASE_URL="https://api.budgetwise.invalid/api/v1/"))
        with self.assertRaises(release.ReleaseError):
            release.fingerprint("not-a-certificate")


class BundleTest(unittest.TestCase):
    def test_manifest_identity_version_and_updater(self):
        release.verify_manifest(MANIFEST, 5, "0.3.1")
        for value in [MANIFEST.replace("app.pocketpeek", "app.budgetwise"),
                      MANIFEST.replace('versionCode="5"', 'versionCode="4"'),
                      MANIFEST.replace('versionName="0.3.1"', 'versionName="0.3.0"'),
                      MANIFEST.replace('debuggable="false"', 'debuggable="true"'),
                      MANIFEST.replace('targetSdkVersion="36"', 'targetSdkVersion="35"'),
                      MANIFEST.replace('minSdkVersion="26"', 'minSdkVersion="27"'),
                      MANIFEST.replace('<application', '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><application'),
                      MANIFEST.replace('/></manifest>', '><provider android:name=".updates.UpdateFileProvider" android:authorities="app.pocketpeek.update-files"/></application></manifest>')]:
            with self.subTest(value=value), self.assertRaises(release.ReleaseError):
                release.verify_manifest(value, 5, "0.3.1")

    def test_bundle_language_resources_and_native_16kb_contract(self):
        release.verify_bundle_config(BUNDLE_CONFIG)
        for key in ["splitsConfig", "uncompressNativeLibraries"]:
            config = copy.deepcopy(BUNDLE_CONFIG)
            del config["optimizations"][key]
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.verify_bundle_config(config)

    def test_native_elf_alignment_and_offset_validation(self):
        release.verify_elf(elf())
        for data in [b"bad", elf(4096), elf(address=1), elf()[:80]]:
            with self.subTest(data=data), self.assertRaises(release.ReleaseError):
                release.verify_elf(data)

    def test_compiled_api_and_native_archive_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "app.aab"
            for api, abi, passes in [(release.API_URL, "arm64-v8a", True),
                                     ("https://api.budgetwise.invalid/api/v1/", "arm64-v8a", False),
                                     (release.API_URL, "x86_64", False)]:
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("base/dex/classes.dex", b"dex\x00" + api.encode() + b"\x00")
                    archive.writestr("base/lib/" + abi + "/test.so", elf())
                if passes:
                    self.assertTrue(release.verify_archive(path)["nativeElf16KB"])
                else:
                    with self.assertRaises(release.ReleaseError):
                        release.verify_archive(path)


    def test_signature_failures_stop_before_bundletool_or_archive_processing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aab, mapping = root / "app.aab", root / "mapping.txt"
            aab.write_bytes(b"synthetic")
            mapping.write_text("mapping")
            for signature, certificate in [("jar is unsigned.", ""),
                    ("jar verified. Warning: unsigned entries", ""),
                    ("jar verified.", "SHA256: " + "c" * 64),
                    ("jar verified.", "Owner: CN=Android Debug\nSHA256: " + CERT)]:
                with self.subTest(signature=signature, certificate=certificate):
                    with mock.patch.object(release, "command", side_effect=[signature, certificate]) as tool:
                        with self.assertRaises(release.ReleaseError):
                            release.verify_bundle(aab, mapping, root / "tool.jar",
                                {"versionCode": 5, "versionName": "0.3.1"}, CERT)
                        self.assertLessEqual(tool.call_count, 2)

    def test_verified_receipt_binds_exact_bundle_mapping_and_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            aab, mapping = root / "app.aab", root / "mapping.txt"
            mapping.write_text("mapping")
            with zipfile.ZipFile(aab, "w") as archive:
                archive.writestr("base/dex/classes.dex", release.API_URL.encode() + b"\x00")
                archive.writestr("base/lib/arm64-v8a/test.so", elf())
            outputs = ["jar verified.", "SHA256: " + CERT, "Bundle valid", MANIFEST, json.dumps(BUNDLE_CONFIG)]
            with mock.patch.object(release, "command", side_effect=outputs):
                receipt = release.verify_bundle(aab, mapping, root / "tool.jar",
                    {"sourceSha": SHA, "versionCode": 5, "versionName": "0.3.1"}, CERT)
            self.assertTrue(receipt["verified"])
            self.assertEqual(release.hashlib.sha256(aab.read_bytes()).hexdigest(), receipt["aabSha256"])
            self.assertEqual(release.hashlib.sha256(mapping.read_bytes()).hexdigest(), receipt["mappingSha256"])
            self.assertEqual(CERT, receipt["uploadCertificateSha256"])


class WorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[2]
        cls.workflow = yaml.load((root / ".github/workflows/android-release.yml").read_text(), Loader=yaml.BaseLoader)

    def test_only_explicit_dispatch_can_start_release(self):
        self.assertEqual({"workflow_dispatch"}, set(self.workflow["on"]))
        inputs = self.workflow["on"]["workflow_dispatch"]["inputs"]
        self.assertEqual("false", inputs["approve_internal"]["default"])
        self.assertEqual(["owner", "agent"], inputs["approved_by"]["options"])
        for name in ["source_sha", "version_code", "version_name", "release_notes", "approve_internal", "approved_by"]:
            self.assertEqual("true", inputs[name]["required"])

    def test_failed_or_cancelled_verification_prevents_publish(self):
        publish = self.workflow["jobs"]["publish"]
        self.assertEqual("verify", publish["needs"])
        self.assertNotIn("if", publish)
        for job in self.workflow["jobs"].values():
            self.assertNotIn("environment", job)
            checkout = next(s for s in job["steps"] if s.get("uses", "").startswith("actions/checkout@"))
            self.assertEqual("${{ github.sha }}", checkout["with"]["ref"])
            self.assertEqual("false", checkout["with"]["persist-credentials"])
        checks = [s.get("run", "") for s in self.workflow["jobs"]["verify"]["steps"]]
        self.assertTrue(any(":app:testDebugUnitTest" in c and ":app:lintRelease" in c for c in checks))
        self.assertNotIn("secrets.", json.dumps(self.workflow["jobs"]["verify"]))

    def test_publisher_is_internal_only_and_uses_verified_archived_bundle(self):
        steps = self.workflow["jobs"]["publish"]["steps"]
        upload = next(s for s in steps if s.get("id") == "upload")
        self.assertEqual("app.pocketpeek", upload["with"]["packageName"])
        self.assertEqual("internal", upload["with"]["track"])
        self.assertEqual("completed", upload["with"]["status"])
        bundle = next(s for s in steps if s.get("id") == "bundle")
        archive = next(s for s in steps if s.get("name") == "Archive the exact verified candidate before publishing")
        self.assertLess(steps.index(bundle), steps.index(archive))
        self.assertLess(steps.index(archive), steps.index(upload))
        readback = next(s for s in steps if "--mode readback" in s.get("run", ""))
        self.assertGreater(steps.index(readback), steps.index(upload))
        self.assertEqual("${{ steps.bundle.outputs.aab_sha256 }}", readback["env"]["AAB_SHA256"])
        self.assertEqual(1, sum("upload-google-play@" in s.get("uses", "") for s in steps))
        self.assertEqual("always()", steps[-1]["if"])

    def test_shared_test_callers_install_the_parser_dependency(self):
        root = Path(__file__).resolve().parents[2]
        for name in ['android-ci.yml', 'android-friends-release.yml', 'android-release.yml']:
            workflow = yaml.load((root / '.github/workflows' / name).read_text(), Loader=yaml.BaseLoader)
            for job in workflow['jobs'].values():
                for step in job['steps']:
                    script = step.get('run', '')
                    if 'unittest discover -s tools/android-release' in script:
                        self.assertIn('pip install -r tools/android-release/requirements-test.txt', script)
                        self.assertTrue(any(s.get('uses', '').startswith('actions/setup-python@') for s in job['steps']))

    def test_input_data_is_not_interpolated_into_shell(self):
        for job in self.workflow["jobs"].values():
            for step in job["steps"]:
                self.assertNotIn("${{ inputs.", step.get("run", ""))


if __name__ == "__main__":
    unittest.main()

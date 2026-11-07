import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("play_api", Path(__file__).with_name("play_api.py"))
play = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(play)
HASH = "a" * 64
INTERNAL = {"track": "internal", "releases": [{"status": "completed", "versionCodes": ["4"]}]}


class FakeResponse:
    def __init__(self, payload=None, status=200):
        self.status_code = status
        self.payload = payload

    def json(self):
        return copy.deepcopy(self.payload)


class FakeSession:
    def __init__(self, *, bundles=None, apks=None, tracks=None, statuses=None, errors=None, track_sequence=None):
        self.payloads = {"bundles": {"bundles": bundles if bundles is not None else [{"versionCode": 4, "sha256": HASH}]},
                         "apks": {"apks": apks if apks is not None else []},
                         "tracks": {"tracks": tracks if tracks is not None else [copy.deepcopy(INTERNAL)]}}
        self.statuses = statuses or {}
        self.errors = errors or {}
        self.track_sequence = track_sequence
        self.calls = []
        self.edits = 0
        self.discarded = []
        self.closed = False

    def request(self, method, url, **kwargs):
        self.calls.append((method, url, kwargs))
        if not url.startswith(play.API_ROOT):
            raise AssertionError("Wrong endpoint")
        suffix = url[len(play.API_ROOT):]
        key = suffix.rsplit("/", 1)[-1]
        if method == "POST" and not suffix:
            self.edits += 1
            return FakeResponse({"id": "inspection-" + str(self.edits)})
        if method == "DELETE":
            self.discarded.append(suffix)
            return FakeResponse(status=self.statuses.get("DELETE", 204))
        if key in self.errors:
            raise RuntimeError(self.errors[key])
        if key in self.statuses:
            return FakeResponse({"error": "do-not-print-private-response"}, self.statuses[key])
        if key == "tracks" and self.track_sequence:
            return FakeResponse({"tracks": self.track_sequence[min(self.edits - 1, len(self.track_sequence) - 1)]})
        return FakeResponse(self.payloads[key])

    def close(self):
        self.closed = True


class PlayInspectionTest(unittest.TestCase):
    def assert_discarded(self, session, count=1):
        self.assertEqual(count, len(session.discarded))
        self.assertEqual("DELETE", session.calls[-1][0])
        for method, url, kwargs in session.calls:
            self.assertTrue(url.startswith(play.API_ROOT))
            self.assertNotIn(":commit", url)
            self.assertNotIn("/upload/", url)
            self.assertIn(method, ("GET", "POST", "DELETE"))
            self.assertFalse(kwargs["allow_redirects"])
            self.assertEqual(play.TIMEOUT, kwargs["timeout"])
            if method == "POST":
                self.assertEqual({}, kwargs["json"])
                self.assertEqual(play.API_ROOT, url)

    def test_preflight_new_code_reads_all_inventory_and_discards_edit(self):
        session = FakeSession()
        receipt = play.preflight(session, "5")
        self.assertTrue(receipt["verified"])
        self.assertEqual(4, receipt["highestExistingVersionCode"])
        self.assertEqual("app.pocketpeek", receipt["applicationId"])
        self.assertEqual("internal", receipt["track"])
        self.assertFalse(receipt["editCommitted"])
        self.assert_discarded(session)
        self.assertEqual(["bundles", "apks", "tracks"], [url.rsplit("/", 1)[-1] for method, url, _ in session.calls if method == "GET"])

    def test_preflight_empty_app_accepts_first_code(self):
        session = FakeSession(bundles=[], tracks=[])
        self.assertEqual(0, play.preflight(session, 1)["highestExistingVersionCode"])
        self.assert_discarded(session)

    def test_preflight_rejects_reused_and_lower_codes(self):
        for code in [3, 4]:
            with self.subTest(code=code):
                session = FakeSession()
                with self.assertRaises(play.PlayApiError):
                    play.preflight(session, code)
                self.assert_discarded(session)

    def test_preflight_checks_unattached_bundles_apks_and_other_tracks(self):
        sessions = [FakeSession(bundles=[{"versionCode": 8}]),
                    FakeSession(apks=[{"versionCode": 8}]),
                    FakeSession(tracks=[INTERNAL, {"track": "closed", "releases": [{"status": "draft", "versionCodes": ["8"]}]}])]
        for session in sessions:
            with self.subTest(inventory=session.payloads), self.assertRaises(play.PlayApiError):
                play.preflight(session, 5)
            self.assert_discarded(session)

    def test_preflight_refuses_unfinished_internal_release(self):
        for status in ["draft", "halted", "inProgress", None, "unknown"]:
            session = FakeSession(tracks=[{"track": "internal", "releases": [{"status": status, "versionCodes": ["4"]}]}])
            with self.subTest(status=status), self.assertRaises(play.PlayApiError):
                play.preflight(session, 5)
            self.assert_discarded(session)

    def test_preflight_malformed_inventory_fails_and_discards(self):
        for key, bad in [("bundles", {"bundles": "private"}), ("apks", {"apks": [None]}),
                         ("tracks", {"tracks": [{"track": "internal", "releases": [{"status": "completed"}]}]})]:
            session = FakeSession()
            session.payloads[key] = bad
            with self.subTest(key=key), self.assertRaises(play.PlayApiError):
                play.preflight(session, 5)
            self.assert_discarded(session)

    def test_transport_errors_and_response_errors_are_sanitized(self):
        for session in [FakeSession(errors={"bundles": "SECRET private key"}), FakeSession(statuses={"bundles": 403})]:
            with self.assertRaises(play.PlayApiError) as error:
                play.preflight(session, 5)
            self.assertNotIn("SECRET", str(error.exception))
            self.assertNotIn("do-not-print", str(error.exception))
            self.assert_discarded(session)

    def test_failed_cleanup_does_not_claim_success(self):
        session = FakeSession(statuses={"DELETE": 500})
        with self.assertRaisesRegex(play.PlayApiError, "Unable to discard"):
            play.preflight(session, 5)
        self.assert_discarded(session)

    def test_readback_verifies_exact_code_status_and_bundle_hash(self):
        session = FakeSession()
        result = play.readback(session, 4, HASH.upper(), attempts=1)
        self.assertEqual(HASH, result["aabSha256"])
        self.assertEqual("completed", result["status"])
        self.assertFalse(result["testerDeliveryVerified"])
        self.assertEqual(1, result["readbackAttempts"])
        self.assert_discarded(session)

    def test_readback_different_bundle_hash_is_not_retried(self):
        session = FakeSession(bundles=[{"versionCode": 4, "sha256": "b" * 64}])
        sleeper = mock.Mock()
        with self.assertRaisesRegex(play.PlayApiError, "SHA-256 differs"):
            play.readback(session, 4, HASH, sleep=sleeper)
        sleeper.assert_not_called()
        self.assert_discarded(session)

    def test_readback_wrong_track_is_not_accepted(self):
        session = FakeSession(tracks=[{"track": "production", "releases": INTERNAL["releases"]}])
        with self.assertRaises(play.ReadbackPending):
            play.readback(session, 4, HASH, attempts=1)
        self.assert_discarded(session)

    def test_readback_noncompleted_statuses_are_not_accepted(self):
        for status in ["draft", "halted", "inProgress", None]:
            session = FakeSession(tracks=[{"track": "internal", "releases": [{"status": status, "versionCodes": ["4"]}]}])
            with self.subTest(status=status), self.assertRaises(play.ReadbackPending):
                play.readback(session, 4, HASH, attempts=1)
            self.assert_discarded(session)

    def test_readback_extra_version_and_missing_bundle_are_not_accepted(self):
        sessions = [FakeSession(tracks=[{"track": "internal", "releases": [{"status": "completed", "versionCodes": ["4", "3"]}]}]),
                    FakeSession(bundles=[])]
        for session in sessions:
            with self.assertRaises(play.ReadbackPending):
                play.readback(session, 4, HASH, attempts=1)
            self.assert_discarded(session)

    def test_readback_retries_boundedly_with_a_new_discarded_edit_each_time(self):
        session = FakeSession(tracks=[])
        sleeper = mock.Mock()
        with self.assertRaises(play.ReadbackPending):
            play.readback(session, 4, HASH, sleep=sleeper)
        self.assertEqual([mock.call(5)] * 5, sleeper.call_args_list)
        self.assert_discarded(session, 6)

    def test_readback_can_observe_eventual_publication(self):
        session = FakeSession(track_sequence=[[], [INTERNAL]])
        sleeper = mock.Mock()
        result = play.readback(session, 4, HASH, sleep=sleeper)
        self.assertEqual(2, result["readbackAttempts"])
        sleeper.assert_called_once_with(5)
        self.assert_discarded(session, 2)

    def test_duplicate_internal_track_fails_closed(self):
        session = FakeSession(tracks=[INTERNAL, INTERNAL])
        with self.assertRaisesRegex(play.PlayApiError, "duplicate internal"):
            play.preflight(session, 5)
        self.assert_discarded(session)

    def test_invalid_inputs_do_not_create_an_edit(self):
        for value in [0, -1, 2_100_000_001, True, 1.0, "5\n", "", "1;touch evil"]:
            session = FakeSession()
            with self.subTest(value=value), self.assertRaises(play.PlayApiError):
                play.preflight(session, value)
            self.assertEqual([], session.calls)
        session = FakeSession()
        with self.assertRaises(play.PlayApiError):
            play.readback(session, 4, "invalid")
        self.assertEqual([], session.calls)

    def test_no_update_or_commit_operation_can_be_requested(self):
        session = FakeSession()
        for method, suffix in [("PUT", "/inspection-1/tracks/internal"), ("POST", "/inspection-1:commit"),
                               ("GET", "/inspection-1/tracks/internal"), ("POST", "/inspection-1/bundles")]:
            with self.subTest(method=method, suffix=suffix), self.assertRaises(play.PlayApiError):
                play._request(session, method, suffix)
        self.assertEqual([], session.calls)

    def test_authentication_configuration_errors_are_sanitized(self):
        for env in [{}, {"PLAY_SERVICE_ACCOUNT_JSON": "SECRET not json"},
                    {"PLAY_SERVICE_ACCOUNT_JSON": json.dumps({"type": "service_account", "token_uri": "https://evil.test/SECRET"})}]:
            with self.subTest(env=env), self.assertRaises(play.PlayApiError) as error:
                play.authorized_session_from_env(env)
            self.assertNotIn("SECRET", str(error.exception))
            self.assertNotIn("evil.test", str(error.exception))

    def test_cli_writes_only_sanitized_receipt_and_closes_session(self):
        session = FakeSession()
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            with mock.patch.object(play, "authorized_session_from_env", return_value=session), contextlib.redirect_stdout(io.StringIO()) as output:
                code = play.main(["--mode", "preflight", "--version-code", "5", "--receipt", str(receipt)])
            self.assertEqual(0, code)
            self.assertTrue(json.loads(receipt.read_text())["verified"])
            self.assertNotIn("private", output.getvalue())
            self.assertTrue(session.closed)

    def test_cli_error_receipt_never_contains_raw_response_or_transport_error(self):
        session = FakeSession(errors={"bundles": "SECRET should never appear"})
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            with mock.patch.object(play, "authorized_session_from_env", return_value=session), contextlib.redirect_stdout(io.StringIO()) as output:
                code = play.main(["--mode", "preflight", "--version-code", "5", "--receipt", str(receipt)])
            self.assertEqual(1, code)
            self.assertFalse(json.loads(receipt.read_text())["verified"])
            self.assertNotIn("SECRET", receipt.read_text() + output.getvalue())
            self.assertTrue(session.closed)
            self.assert_discarded(session)

    def test_cli_does_not_overwrite_existing_receipts(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            receipt.write_text("retained receipt")
            with mock.patch.object(play, "authorized_session_from_env", return_value=FakeSession()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, play.main(["--mode", "preflight", "--version-code", "5", "--receipt", str(receipt)]))
            self.assertEqual("retained receipt", receipt.read_text())


    def test_cli_readback_requires_hash_before_authentication(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            with mock.patch.object(play, "authorized_session_from_env") as auth, contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, play.main(["--mode", "readback", "--version-code", "4", "--receipt", str(receipt)]))
            auth.assert_not_called()
            self.assertFalse(json.loads(receipt.read_text())["verified"])

    def test_cli_readback_success_keeps_device_delivery_separate(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            with mock.patch.object(play, "authorized_session_from_env", return_value=FakeSession()), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, play.main(["--mode", "readback", "--version-code", "4", "--aab-sha256", HASH, "--receipt", str(receipt)]))
            result = json.loads(receipt.read_text())
            self.assertTrue(result["verified"])
            self.assertFalse(result["testerDeliveryVerified"])

    def test_cli_rejects_existing_receipt_before_authentication(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            receipt.write_text("existing")
            with mock.patch.object(play, "authorized_session_from_env") as auth, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, play.main(["--mode", "preflight", "--version-code", "5", "--receipt", str(receipt)]))
            auth.assert_not_called()
            self.assertEqual("existing", receipt.read_text())

    def test_cli_client_cleanup_errors_are_sanitized(self):
        session = FakeSession()
        session.close = mock.Mock(side_effect=RuntimeError("SECRET transport detail"))
        with tempfile.TemporaryDirectory() as temp:
            receipt = Path(temp) / "receipt.json"
            with mock.patch.object(play, "authorized_session_from_env", return_value=session), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(1, play.main(["--mode", "preflight", "--version-code", "5", "--receipt", str(receipt)]))
            self.assertNotIn("SECRET", output.getvalue() + receipt.read_text())
            self.assertFalse(json.loads(receipt.read_text())["verified"])

if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Verify Pocket Peek Play release state with disposable, never-committed edits.

This helper cannot upload a bundle, update a track, or commit an edit. Authentication
uses Google's official client and is loaded only by the CLI, never by unit tests.
"""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import sys
import time

APPLICATION_ID = "app.pocketpeek"
TRACK = "internal"
API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/app.pocketpeek/edits"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
MAX_VERSION_CODE = 2_100_000_000
TIMEOUT = (10, 30)


class PlayApiError(ValueError):
    """A sanitized operational error safe for release logs and receipts."""


class ReadbackPending(PlayApiError):
    """The expected completed internal release is not visible yet."""


def version_code(value):
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise PlayApiError("A valid positive Android version code is required.")
    if len(str(value)) > 10 or not re.fullmatch(r"[0-9]+", str(value)):
        raise PlayApiError("A valid positive Android version code is required.")
    code = int(value)
    if not 1 <= code <= MAX_VERSION_CODE:
        raise PlayApiError("Android version code is outside the supported range.")
    return code


def aab_sha256(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise PlayApiError("The expected AAB SHA-256 must be 64 hexadecimal characters.")
    return value.lower()


def _request(session, method, suffix=""):
    # Only create/discard an inspection edit or read its inventory. No other
    # endpoint or method is expressible through this helper.
    allowed = ((method == "POST" and suffix == "")
               or (method == "DELETE" and re.fullmatch(r"/[A-Za-z0-9_-]+", suffix))
               or (method == "GET" and re.fullmatch(r"/[A-Za-z0-9_-]+/(bundles|apks|tracks)", suffix)))
    if not allowed:
        raise PlayApiError("Unsupported Play inspection operation.")
    try:
        kwargs = {"timeout": TIMEOUT, "allow_redirects": False}
        if method == "POST":
            kwargs["json"] = {}
        response = session.request(method, API_ROOT + suffix, **kwargs)
    except Exception:
        raise PlayApiError("Google Play inspection transport failed.") from None
    if not isinstance(response.status_code, int) or not 200 <= response.status_code < 300:
        status = response.status_code if isinstance(response.status_code, int) else "unknown"
        raise PlayApiError(f"Google Play inspection failed (HTTP {status}).")
    if method == "DELETE":
        return {}
    try:
        payload = response.json()
    except Exception:
        raise PlayApiError("Google Play returned an invalid inspection response.") from None
    if not isinstance(payload, dict):
        raise PlayApiError("Google Play returned an invalid inspection response.")
    return payload


@contextmanager
def _inspection_edit(session):
    created = _request(session, "POST")
    edit_id = created.get("id")
    if not isinstance(edit_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", edit_id):
        raise PlayApiError("Google Play returned an invalid inspection edit identifier.")
    try:
        yield "/" + edit_id
    finally:
        try:
            _request(session, "DELETE", "/" + edit_id)
        except PlayApiError:
            raise PlayApiError("Unable to discard the inspection edit; no edit was committed.") from None


def _rows(payload, key):
    rows = payload.get(key, [])
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise PlayApiError("Google Play returned malformed release inventory.")
    return rows


def _internal_releases(tracks):
    internal = [track for track in tracks if track.get("track") == TRACK]
    if len(internal) > 1:
        raise PlayApiError("Google Play returned duplicate internal tracks.")
    return _rows(internal[0], "releases") if internal else []


def _release_codes(release):
    values = release.get("versionCodes", [])
    if not isinstance(values, list) or not values:
        raise PlayApiError("Google Play returned malformed release version codes.")
    return [version_code(value) for value in values]


def _receipt(mode, code):
    return {"schemaVersion": 1, "mode": mode, "applicationId": APPLICATION_ID,
            "track": TRACK, "versionCode": code, "verified": True,
            "inspectionEditDiscarded": True, "editCommitted": False}


def preflight(session, requested_code):
    """Require a globally new version code and no unfinished internal release."""
    requested_code = version_code(requested_code)
    with _inspection_edit(session) as edit:
        bundles = _rows(_request(session, "GET", edit + "/bundles"), "bundles")
        apks = _rows(_request(session, "GET", edit + "/apks"), "apks")
        tracks = _rows(_request(session, "GET", edit + "/tracks"), "tracks")
        for release in _internal_releases(tracks):
            if release.get("status") != "completed":
                raise PlayApiError("The internal track contains an unfinished release; inspect it before publishing.")
        existing = [version_code(item.get("versionCode")) for item in bundles + apks]
        for track in tracks:
            for release in _rows(track, "releases"):
                existing.extend(_release_codes(release))
        highest = max(existing, default=0)
        if requested_code <= highest:
            raise PlayApiError("Requested version code must exceed every existing Play version code.")
    result = _receipt("preflight", requested_code)
    result["highestExistingVersionCode"] = highest
    return result


def _readback_once(session, requested_code, expected_sha256):
    with _inspection_edit(session) as edit:
        bundles = _rows(_request(session, "GET", edit + "/bundles"), "bundles")
        tracks = _rows(_request(session, "GET", edit + "/tracks"), "tracks")
        releases = _internal_releases(tracks)
        if len(releases) != 1 or releases[0].get("status") != "completed":
            raise ReadbackPending("The expected completed internal release is not visible; inspect publication and review status.")
        if _release_codes(releases[0]) != [requested_code]:
            raise ReadbackPending("The expected completed internal version is not visible yet.")
        uploaded = [bundle for bundle in bundles if version_code(bundle.get("versionCode")) == requested_code]
        if len(uploaded) != 1:
            raise ReadbackPending("The expected uploaded bundle is not visible yet.")
        if aab_sha256(uploaded[0].get("sha256")) != expected_sha256:
            raise PlayApiError("Published internal bundle SHA-256 differs from the verified AAB.")
    result = _receipt("readback", requested_code)
    result.update({"status": "completed", "aabSha256": expected_sha256,
                   "testerDeliveryVerified": False})
    return result


def readback(session, requested_code, expected_sha256, *, attempts=6, sleep=time.sleep):
    """Bound eventual-consistency waiting; installed-device delivery is separate."""
    requested_code = version_code(requested_code)
    expected_sha256 = aab_sha256(expected_sha256)
    if isinstance(attempts, bool) or not isinstance(attempts, int) or not 1 <= attempts <= 6:
        raise PlayApiError("Readback retry count must be between one and six.")
    for attempt in range(attempts):
        try:
            result = _readback_once(session, requested_code, expected_sha256)
            result["readbackAttempts"] = attempt + 1
            return result
        except ReadbackPending:
            if attempt + 1 == attempts:
                raise
            sleep(5)
    raise PlayApiError("Internal readback did not complete.")


def authorized_session_from_env(environ=None):
    """Create the official scoped client without exposing credential diagnostics."""
    environ = os.environ if environ is None else environ
    raw = environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if not raw:
        raise PlayApiError("PLAY_SERVICE_ACCOUNT_JSON is required for Play inspection.")
    try:
        info = json.loads(raw)
        if (not isinstance(info, dict) or info.get("type") != "service_account"
                or info.get("token_uri") != "https://oauth2.googleapis.com/token"):
            raise ValueError()
        from google.auth.transport.requests import AuthorizedSession
        from google.oauth2 import service_account
        credentials = service_account.Credentials.from_service_account_info(info, scopes=[SCOPE])
        return AuthorizedSession(credentials, refresh_timeout=30)
    except Exception:
        raise PlayApiError("Unable to initialize the official Google Play authentication client.") from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("preflight", "readback"))
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--aab-sha256")
    parser.add_argument("--receipt", required=True, type=Path)
    args = parser.parse_args(argv)
    if args.receipt.exists() or args.receipt.is_symlink():
        print("The inspection receipt already exists; choose a fresh path.", file=sys.stderr)
        return 1
    result = {"schemaVersion": 1, "mode": args.mode, "applicationId": APPLICATION_ID,
              "track": TRACK, "verified": False, "editCommitted": False}
    session = None
    exit_code = 1
    try:
        code = version_code(args.version_code)
        result["versionCode"] = code
        if args.mode == "readback":
            expected = aab_sha256(args.aab_sha256)
        elif args.aab_sha256 is not None:
            raise PlayApiError("AAB SHA-256 is accepted only in readback mode.")
        session = authorized_session_from_env()
        result = (preflight(session, code) if args.mode == "preflight"
                  else readback(session, code, expected))
        exit_code = 0
    except PlayApiError as error:
        result["error"] = str(error)
    finally:
        if session is not None:
            try:
                session.close()
            except Exception:
                result = {"schemaVersion": 1, "mode": args.mode, "applicationId": APPLICATION_ID,
                          "track": TRACK, "verified": False, "editCommitted": False,
                          "error": "Unable to close the Google Play inspection client."}
                exit_code = 1
    try:
        with args.receipt.open("x", encoding="utf-8") as receipt:
            json.dump(result, receipt, indent=2, sort_keys=True)
            receipt.write("\n")
    except OSError:
        print("Unable to create the inspection receipt; existing files are not overwritten.", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())

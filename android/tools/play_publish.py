#!/usr/bin/env python3
"""Sends a release bundle to a Google Play testing track, after the CI built it.

Two steps, run by android.yml on main:

  upload   uploads the bundle (and its R8 mapping) and releases its version code
           to the internal testing track, in one edit;
  promote  releases the same version code, already uploaded, to another track
           (the closed testing track), once the screen tour and the emulator
           smoke test passed. Nothing is rebuilt between the tracks.

Standard library only. The access token comes from Workload Identity Federation
(google-github-actions/auth in the workflow): no key is stored.

Environment:
  PLAY_ACCESS_TOKEN  OAuth access token with the androidpublisher scope
  PACKAGE_NAME       app.encave
  VERSION_CODE       the versionCode of the bundle (BUILD_NUMBER in CI)
  BUNDLE_PATH        upload: the signed .aab
  MAPPING_PATH       upload: R8 mapping.txt (optional)
  TRACK              default "internal" for upload; required for promote
  RELEASE_NOTES_EN   release notes in English, at most 500 characters (default: the
  RELEASE_NOTES_FR   build number and the latest commit subjects, like the TestFlight
                     "What to Test" of apple.yml; in French with the same subjects)
  USER_FRACTION      staged rollout share, for example 0.1 (production only; not
                     used by the CI: public release stays off)
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"

# Seconds to wait before each new try when Google Play is busy (429), fails on its
# side (5xx) or cannot be reached.
RETRY_DELAYS = (5, 15, 30, 60, 120)
NOTES_LIMIT = 500


def notice(message):
    print(f"::notice::{message}")


def warn(message):
    print(f"::warning::{message}")


class APIError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:800]}")
        self.status = status
        self.body = body


def call(method, url, body=None, query=None, data=None, content_type="application/json", timeout=60):
    if query:
        url += "?" + urllib.parse.urlencode(query)
    if body is not None:
        data = json.dumps(body).encode()
    for attempt in range(len(RETRY_DELAYS) + 1):
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {os.environ['PLAY_ACCESS_TOKEN']}", "Content-Type": content_type})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                text = response.read().decode()
                return json.loads(text) if text else {}
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")
            if (error.code != 429 and error.code < 500) or attempt == len(RETRY_DELAYS):
                raise APIError(error.code, detail) from None
            reason = f"HTTP {error.code}"
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == len(RETRY_DELAYS):
                raise
            reason = str(getattr(error, "reason", error))
        delay = RETRY_DELAYS[attempt]
        print(f"{method} {url.split('?')[0]}: {reason}, trying again in {delay} s", file=sys.stderr)
        time.sleep(delay)


def explain(error, package):
    """A clear message for the errors expected before the one-time setup is finished."""
    body = error.body.lower()
    if error.status == 404 or "package not found" in body:
        return (f"Google Play does not know {package} yet: create the app in the Play Console and upload the "
                "first bundle by hand (docs/RELEASING.md, one-time setup).")
    if error.status in (401, 403) and "permission" in body:
        return ("The service account has no access to the app: invite it in the Play Console (Users and "
                "permissions) with the release permissions (docs/RELEASING.md, one-time setup).")
    if "draft app" in body:
        return ("The app is still a draft: roll out the first release to internal testing by hand in the "
                "Play Console (docs/RELEASING.md, one-time setup); later releases are made by the CI.")
    return None


def latest_changes():
    try:
        return subprocess.run(["git", "log", "--no-merges", "-n", "8", "--format=- %s"],
                              capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def notes(version_code):
    changes = latest_changes()
    defaults = {
        "en-US": f"Beta build {version_code}." + (f" Latest changes:\n{changes}" if changes else ""),
        "fr-FR": f"Version bêta {version_code}." + (f" Derniers changements (en anglais) :\n{changes}" if changes else ""),
    }
    released = []
    for language, variable in (("en-US", "RELEASE_NOTES_EN"), ("fr-FR", "RELEASE_NOTES_FR")):
        text = os.environ.get(variable, "").strip() or defaults[language]
        if text:
            if len(text) > NOTES_LIMIT:
                text = text[:NOTES_LIMIT - 2].rsplit("\n", 1)[0] + "\n…"
            released.append({"language": language, "text": text})
    return released


def release(version_code):
    entry = {"versionCodes": [str(version_code)], "status": "completed"}
    fraction = os.environ.get("USER_FRACTION", "").strip()
    if fraction:
        entry["status"] = "inProgress"
        entry["userFraction"] = float(fraction)
    release_notes = notes(version_code)
    if release_notes:
        entry["releaseNotes"] = release_notes
    return entry


def set_track(base, edit, track, version_code):
    try:
        call("PUT", f"{base}/edits/{edit}/tracks/{track}", {"track": track, "releases": [release(version_code)]})
    except APIError as error:
        if error.status in (400, 404) and "track" in error.body.lower():
            tracks = call("GET", f"{base}/edits/{edit}/tracks").get("tracks", [])
            names = ", ".join(t["track"] for t in tracks) or "none"
            sys.exit(f"Google Play refused the track \"{track}\" ({error}). Tracks of the app: {names}. "
                     "Set the PLAY_CLOSED_TRACK repository variable to one of them.")
        raise


def commit(base, edit):
    try:
        call("POST", f"{base}/edits/{edit}:commit")
    except APIError as error:
        if error.status == 400 and "changesnotsentforreview" in error.body.lower():
            call("POST", f"{base}/edits/{edit}:commit", query={"changesNotSentForReview": "true"})
            warn("Google Play kept these changes until they are sent for review: open the Play Console "
                 "(Publishing overview) and send them.")
            return
        raise


def upload(base, edit, package, version_code):
    path = os.environ["BUNDLE_PATH"]
    with open(path, "rb") as bundle:
        data = bundle.read()
    print(f"Uploading {path} ({len(data) // 1024} KiB)")
    try:
        uploaded = call("POST", f"{UPLOAD}/{package}/edits/{edit}/bundles", query={"uploadType": "media"},
                        data=data, content_type="application/octet-stream", timeout=600)
        if str(uploaded.get("versionCode")) != str(version_code):
            sys.exit(f"Google Play read the version code {uploaded.get('versionCode')}, expected {version_code}.")
        print(f"Bundle {version_code} uploaded (SHA-256 {uploaded.get('sha256', '?')}).")
    except APIError as error:
        # A re-run of the job, or a try that timed out but went through: the bundle is there.
        if "already" not in error.body.lower():
            raise
        print(f"Bundle {version_code} was already uploaded.")

    mapping = os.environ.get("MAPPING_PATH", "")
    if mapping and os.path.isfile(mapping):
        with open(mapping, "rb") as file:
            try:
                call("POST", f"{UPLOAD}/{package}/edits/{edit}/apks/{version_code}/deobfuscationFiles/proguard",
                     query={"uploadType": "media"}, data=file.read(), content_type="application/octet-stream",
                     timeout=600)
                print("R8 mapping uploaded (readable stack traces in Android vitals).")
            except APIError as error:
                # Not worth stopping the release: the mapping is also kept as a CI artifact.
                warn(f"The R8 mapping was not uploaded ({error}); it is in the encave-release-mapping artifact.")


def main():
    step = sys.argv[1] if len(sys.argv) > 1 else ""
    if step not in ("upload", "promote"):
        sys.exit("Usage: play_publish.py upload|promote")
    package = os.environ.get("PACKAGE_NAME", "app.encave")
    version_code = int(os.environ["VERSION_CODE"])
    track = os.environ.get("TRACK", "").strip() or ("internal" if step == "upload" else "")
    if not track:
        sys.exit("promote needs TRACK, the closed testing track (for example alpha).")
    base = f"{API}/{package}"

    try:
        edit = call("POST", f"{base}/edits", {})["id"]
        if step == "upload":
            upload(base, edit, package, version_code)
        set_track(base, edit, track, version_code)
        commit(base, edit)
    except APIError as error:
        message = explain(error, package)
        if message:
            sys.exit(f"{message}\n{error}")
        raise
    notice(f"Version code {version_code} released to the Google Play track \"{track}\".")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Sends a release bundle to a Google Play testing track, after the CI built it.

Steps, run by android.yml on main:

  upload   uploads the bundle (and its R8 mapping) and releases its version code
           to the internal testing track, in one edit;
  promote  releases the same version code, already uploaded, to another track
           (the closed testing track), once the screen tour and the emulator
           smoke test passed. Nothing is rebuilt between the tracks. With
           LISTING_JSON set, the store listing is updated in the same edit, so
           Google reviews the release and the listing together;
  listing  only the store listing (texts and images): play-listing.yml, or by hand;
  data-safety  the Data safety form, from the CSV of DATA_SAFETY_CSV
           (docs/store/play-data-safety.csv; applications.dataSafety, no edit).

Play Console permissions of the service account (Users and permissions), per step:
upload and promote need "Release apps to testing tracks", listing "Manage store
presence", data-safety "Manage policy declarations".

The listing: texts from docs/store/play.json (title, short and full description
per language) and the images made by play_listing_images.py. Unchanged texts and
images are left alone (compared with what Google Play has), so a run that changes
nothing sends nothing for review.

Standard library only. The access token comes from Workload Identity Federation
(google-github-actions/auth in the workflow): no key is stored.

Environment:
  PLAY_ACCESS_TOKEN  OAuth access token with the androidpublisher scope
  PACKAGE_NAME       markeddoe.com.encave
  VERSION_CODE       the versionCode of the bundle (BUILD_NUMBER in CI)
  BUNDLE_PATH        upload: the signed .aab
  MAPPING_PATH       upload: R8 mapping.txt (optional)
  TRACK              default "internal" for upload; required for promote
  RELEASE_NOTES_EN   release notes in English, at most 500 characters (default: the
  RELEASE_NOTES_FR   build number and the latest commit subjects, like the TestFlight
                     "What to Test" of apple.yml; in French with the same subjects)
  RELEASE_STATUS     "draft": the release is prepared, and rolled out from the Play
                     Console (needed while the app itself is still a draft)
  USER_FRACTION      staged rollout share, for example 0.1 (production only; not
                     used by the CI: public release stays off)
  LISTING_JSON       promote, listing: the listing texts (docs/store/play.json)
  LISTING_IMAGES     promote, listing: the folder of play_listing_images.py,
                     <language>/<imageType>/NN.png (optional: texts only without it)
  DATA_SAFETY_CSV    data-safety: the Data safety answers, in the Play Console's
                     CSV format (export: App content › Data safety › Export to CSV)
"""

import hashlib

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
LISTING_LIMITS = {"title": 30, "shortDescription": 80, "fullDescription": 4000}
PERMISSIONS = {
    "tracks": "Release apps to testing tracks",
    "listing": "Manage store presence",
    "data-safety": "Manage policy declarations",
}
# What the step is doing, for the error message when a permission is missing.
doing = "tracks"
IMAGE_TYPES = ("icon", "featureGraphic", "phoneScreenshots", "sevenInchScreenshots", "tenInchScreenshots")


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
    if error.status in (401, 403):
        return (f"The service account lacks the Play Console permission \"{PERMISSIONS[doing]}\" for this app: "
                "Users and permissions › the service account › App permissions › Encave, tick it, Apply "
                "(docs/RELEASING.md, one-time setup).")
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


def release(version_code, draft=False):
    entry = {"versionCodes": [str(version_code)], "status": "completed"}
    fraction = os.environ.get("USER_FRACTION", "").strip()
    if draft or os.environ.get("RELEASE_STATUS", "").strip() == "draft":
        # A draft app (forms not finished) only takes draft releases: rolled out from the Console.
        entry["status"] = "draft"
    elif fraction:
        entry["status"] = "inProgress"
        entry["userFraction"] = float(fraction)
    release_notes = notes(version_code)
    if release_notes:
        entry["releaseNotes"] = release_notes
    return entry


def draft_only(error):
    """Google's answer while the app itself is a draft (App content not finished)."""
    return error.status == 400 and "status draft" in error.body.lower()


def set_track(base, edit, track, version_code, draft=False):
    try:
        call("PUT", f"{base}/edits/{edit}/tracks/{track}",
             {"track": track, "releases": [release(version_code, draft)]})
    except APIError as error:
        if draft_only(error):
            raise
        if error.status in (400, 404) and "track" in error.body.lower():
            tracks = call("GET", f"{base}/edits/{edit}/tracks").get("tracks", [])
            names = ", ".join(t["track"] for t in tracks) or "none"
            sys.exit(f"Google Play refused the track \"{track}\" ({error}). Tracks of the app: {names}. "
                     "Set the PLAY_CLOSED_TRACK repository variable to one of them (or to none: no closed "
                     "testing).")
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


def read_listing(path):
    with open(path, encoding="utf-8") as file:
        data = json.load(file)
    listings = data.get("listings", {})
    if not listings:
        sys.exit(f"{path} has no listings.")
    for language, texts in listings.items():
        for field, limit in LISTING_LIMITS.items():
            text = texts.get(field, "")
            if not text.strip():
                sys.exit(f"{path}: {language} has no {field}.")
            if len(text) > limit:
                sys.exit(f"{path}: {language} {field} has {len(text)} characters, Google Play takes {limit}.")
    return data


def check_details(base, edit, default_language):
    """Contact details are left as they are; says what Google needs before a review."""
    details = call("GET", f"{base}/edits/{edit}/details")
    if details.get("defaultLanguage") and details["defaultLanguage"] != default_language:
        warn(f"The default store language is {details['defaultLanguage']} in the Play Console, "
             f"{default_language} in docs/store/play.json: the listing of both languages is sent, "
             "but check which one Google shows by default.")
    if not details.get("contactEmail"):
        warn("No contact email in the store listing: Google needs one before a review. Set it in the Play "
             "Console: Grow users › Store presence › Store settings › Store listing contact details.")


def update_texts(base, edit, language, texts):
    try:
        current = call("GET", f"{base}/edits/{edit}/listings/{language}")
    except APIError as error:
        if error.status != 404:
            raise
        current = {}
    wanted = {field: texts[field] for field in LISTING_LIMITS}
    if all(current.get(field, "") == value for field, value in wanted.items()):
        print(f"{language}: texts unchanged.")
        return False
    body = {"language": language, **wanted}
    if current.get("video"):
        body["video"] = current["video"]  # listings.update replaces the listing: keep a video set by hand
    call("PUT", f"{base}/edits/{edit}/listings/{language}", body)
    print(f"{language}: texts updated.")
    return True


def update_images(base, edit, package, language, image_type, files):
    url = f"{base}/edits/{edit}/listings/{language}/{image_type}"
    blobs = []
    for path in files:
        with open(path, "rb") as file:
            blobs.append(file.read())
    wanted = [hashlib.sha256(blob).hexdigest() for blob in blobs]
    current = [image.get("sha256", "") for image in call("GET", url).get("images", [])]
    if current == wanted:
        print(f"{language} {image_type}: {len(files)} unchanged.")
        return False
    call("DELETE", url)  # edits.images.deleteall
    for blob in blobs:
        call("POST", f"{UPLOAD}/{package}/edits/{edit}/listings/{language}/{image_type}",
             query={"uploadType": "media"}, data=blob, content_type="image/png", timeout=300)
    print(f"{language} {image_type}: {len(files)} uploaded (replacing {len(current)}).")
    return True


def update_listing(base, edit, package):
    """The store listing in this edit; True when something changed."""
    global doing
    doing = "listing"
    data = read_listing(os.environ["LISTING_JSON"])
    check_details(base, edit, data.get("defaultLanguage", "fr-FR"))
    changed = False
    for language, texts in data["listings"].items():
        changed |= update_texts(base, edit, language, texts)
    images = os.environ.get("LISTING_IMAGES", "").strip()
    if not images:
        print("No LISTING_IMAGES: images left as they are.")
        return changed
    for language in data["listings"]:
        for image_type in IMAGE_TYPES:
            folder = os.path.join(images, language, image_type)
            files = sorted(os.path.join(folder, name) for name in os.listdir(folder)
                           if name.endswith(".png")) if os.path.isdir(folder) else []
            if files:
                changed |= update_images(base, edit, package, language, image_type, files)
    return changed


def data_safety(base):
    global doing
    doing = "data-safety"
    path = os.environ.get("DATA_SAFETY_CSV", "").strip()
    if not path:
        sys.exit("data-safety needs DATA_SAFETY_CSV (docs/store/play-data-safety.csv).")
    with open(path, encoding="utf-8") as file:
        labels = file.read()
    if not labels.startswith("Question ID"):
        sys.exit(f"{path} is not a Data safety CSV of the Play Console (first column: Question ID).")
    # No edit and nothing to compare with: the API replaces the whole form, the same answers are harmless.
    call("POST", f"{base}/dataSafety", {"safetyLabels": labels})
    notice("Google Play Data safety form sent (docs/store/play-data-safety.csv).")


def main():
    step = sys.argv[1] if len(sys.argv) > 1 else ""
    if step not in ("upload", "promote", "listing", "data-safety"):
        sys.exit("Usage: play_publish.py upload|promote|listing|data-safety")
    package = os.environ.get("PACKAGE_NAME", "markeddoe.com.encave")
    base = f"{API}/{package}"
    if step == "data-safety":
        try:
            data_safety(base)
        except APIError as error:
            sys.exit(f"{explain(error, package) or 'Google Play refused the Data safety form.'}\n{error}")
        return
    if step == "listing":
        if not os.environ.get("LISTING_JSON"):
            sys.exit("listing needs LISTING_JSON (docs/store/play.json).")
        version_code, track = None, ""
    else:
        version_code = int(os.environ["VERSION_CODE"])
        track = os.environ.get("TRACK", "").strip() or ("internal" if step == "upload" else "")
        if not track:
            sys.exit("promote needs TRACK, the closed testing track (for example alpha).")

    try:
        edit = call("POST", f"{base}/edits", {})["id"]
        listing_changed = drafted = False
        if step == "upload":
            upload(base, edit, package, version_code)
        if step != "upload" and os.environ.get("LISTING_JSON"):
            listing_changed = update_listing(base, edit, package)
        if step == "listing" and not listing_changed:
            call("DELETE", f"{base}/edits/{edit}")
            notice("The Google Play store listing is up to date: nothing sent.")
            return
        if track:
            global doing
            doing = "tracks"
            try:
                set_track(base, edit, track, version_code)
                commit(base, edit)
            except APIError as error:
                if not draft_only(error):
                    raise
                # The app is still a draft: it only takes draft releases, rolled out by hand.
                set_track(base, edit, track, version_code, draft=True)
                commit(base, edit)
                warn(f"The app is still a draft in Google Play, so version code {version_code} was saved as a "
                     f"draft release of the track \"{track}\": roll it out from the Play Console (Test and "
                     "release › the track › Edit release › Review › Roll out). Finish the App content forms "
                     "(Policy and programs › App content) to end the draft state; later releases then go out "
                     "by themselves.")
                drafted = True
        else:
            commit(base, edit)
    except APIError as error:
        message = explain(error, package)
        if message:
            sys.exit(f"{message}\n{error}")
        raise
    if listing_changed:
        notice("Google Play store listing updated (texts and images of docs/store/play.json).")
    if track and not drafted:
        notice(f"Version code {version_code} released to the Google Play track \"{track}\".")


if __name__ == "__main__":
    main()

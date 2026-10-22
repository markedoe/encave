#!/usr/bin/env python3
"""Gives a TestFlight build to the external testers, after the CI uploaded it.

For each platform (iPhone/iPad, then Mac): waits until App Store Connect has
processed the build, sets its "What to Test", adds it to every external
TestFlight group of the app and submits it to Beta App Review (Apple usually
approves later builds of the same version without a full review).

Standard library only; the token is signed with the openssl command line tool.

Environment:
  ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER_ID  App Store Connect API key (.p8 file)
  BUILD_NUMBER                             the CFBundleVersion uploaded by the CI
  BUNDLE_ID                                app.encave
  WHATS_NEW                                text for "What to Test" (optional)
  PLATFORMS                                default "IOS,MAC_OS"
  WAIT_MINUTES                             default 45

Without an external group it warns and stops: create one in App Store Connect
(TestFlight > External Testing) and the next build goes to it.
"""

import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com/v1"


def warn(message):
    print(f"::warning::{message}")


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(der):
    """ECDSA signature from openssl (DER sequence of r and s) to the JWT form (r || s)."""
    def integer(at):
        assert der[at] == 0x02
        length = der[at + 1]
        value = der[at + 2:at + 2 + length]
        return value.lstrip(b"\0").rjust(32, b"\0"), at + 2 + length

    assert der[0] == 0x30
    at = 3 if der[1] & 0x80 else 2
    r, at = integer(at)
    s, _ = integer(at)
    return r + s


def token():
    now = int(time.time())
    header = {"alg": "ES256", "kid": os.environ["ASC_KEY_ID"], "typ": "JWT"}
    payload = {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(payload).encode())}"
    der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", os.environ["ASC_KEY_PATH"]],
                         input=signing_input.encode(), capture_output=True, check=True).stdout
    return f"{signing_input}.{b64url(der_to_raw(der))}"


class APIError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status
        self.body = body


# Seconds to wait before each new try when App Store Connect is busy (429), fails on
# its side (5xx) or cannot be reached: it sometimes answers 500 to a plain GET.
RETRY_DELAYS = (5, 15, 30, 60, 120)


def call(method, path, body=None, query=None):
    url = f"{API}{path}"
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(len(RETRY_DELAYS) + 1):
        # A new token for each try: a signed token is only valid for a few minutes.
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {token()}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
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
        print(f"{method} {path}: {reason}, trying again in {delay} s", file=sys.stderr)
        time.sleep(delay)


def wait_for_build(app_id, number, platform, minutes):
    deadline = time.time() + minutes * 60
    while True:
        builds = call("GET", "/builds", query={
            "filter[app]": app_id, "filter[version]": number,
            "filter[preReleaseVersion.platform]": platform, "limit": 1})["data"]
        state = builds[0]["attributes"]["processingState"] if builds else "NOT_FOUND"
        print(f"{platform} build {number}: {state}")
        if state == "VALID":
            return builds[0]["id"]
        if state in ("FAILED", "INVALID"):
            sys.exit(f"App Store Connect could not process the {platform} build {number} ({state}).")
        if time.time() > deadline:
            sys.exit(f"The {platform} build {number} was still not processed after {minutes} minutes.")
        time.sleep(30)


def set_whats_new(build_id, text):
    existing = call("GET", f"/builds/{build_id}/betaBuildLocalizations")["data"]
    if not existing:
        try:
            call("POST", "/betaBuildLocalizations", {"data": {
                "type": "betaBuildLocalizations",
                "attributes": {"locale": "en-US", "whatsNew": text},
                "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
            return
        except APIError as error:
            # Made by a try that timed out but went through: updated below instead.
            if error.status != 409:
                raise
            existing = call("GET", f"/builds/{build_id}/betaBuildLocalizations")["data"]
    for localization in existing:
        call("PATCH", f"/betaBuildLocalizations/{localization['id']}", {"data": {
            "type": "betaBuildLocalizations", "id": localization["id"], "attributes": {"whatsNew": text}}})


def main():
    number = os.environ["BUILD_NUMBER"]
    bundle = os.environ.get("BUNDLE_ID", "app.encave")
    platforms = [p.strip() for p in os.environ.get("PLATFORMS", "IOS,MAC_OS").split(",") if p.strip()]
    minutes = int(os.environ.get("WAIT_MINUTES", "45"))
    whats_new = os.environ.get("WHATS_NEW", "").strip()[:4000]

    apps = call("GET", "/apps", query={"filter[bundleId]": bundle, "limit": 1})["data"]
    if not apps:
        sys.exit(f"No app with the bundle ID {bundle} in App Store Connect.")
    app_id = apps[0]["id"]

    groups = call("GET", "/betaGroups", query={"filter[app]": app_id, "filter[isInternalGroup]": "false", "limit": 50})["data"]
    if not groups:
        warn("No external TestFlight group: create one in App Store Connect (TestFlight > External Testing); "
             "the next build will be given to it.")
        return
    print("External groups:", ", ".join(g["attributes"]["name"] for g in groups))

    for platform in platforms:
        build_id = wait_for_build(app_id, number, platform, minutes)
        if whats_new:
            set_whats_new(build_id, whats_new)
        for group in groups:
            try:
                call("POST", f"/betaGroups/{group['id']}/relationships/builds",
                     {"data": [{"type": "builds", "id": build_id}]})
            except APIError as error:
                # Already in the group (also when a try that timed out went through): done.
                if error.status != 409 and "already" not in error.body.lower():
                    raise
                print(f"{platform} build {number} was already in {group['attributes']['name']}.")
        try:
            call("POST", "/betaAppReviewSubmissions", {"data": {
                "type": "betaAppReviewSubmissions",
                "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}})
            print(f"{platform} build {number}: added to the external groups and submitted to Beta App Review.")
        except APIError as error:
            # Already submitted, another build of this version waiting for review, or the
            # Test Information not filled in yet: the build is in the groups, and testers
            # get it once a build of this version is approved.
            if error.status in (409, 422):
                warn(f"{platform} build {number} is in the external groups, but Beta App Review said: {error.body[:400]}")
            else:
                raise


if __name__ == "__main__":
    main()

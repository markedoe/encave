#!/usr/bin/env python3
"""App Store "Header and Search Results" image, through the App Store Connect API.

MODE=inspect (default) only reads: the App Store versions and their
localizations, the asset library, the placement groups Apple accepts for the
header and the search results, and the images already in the library.

MODE=upload puts one universal image (5244 x 2950 PNG, no transparency) per
localization in the app's asset library and places it both as the product page
header and as the search results asset of the version being prepared. It does
not submit anything for review: the images go to review with that version.

Environment: ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER_ID, BUNDLE_ID, MODE,
IMAGES ("fr-FR=docs/store/header-fr.png,en-US=docs/store/header-en.png").
"""

import hashlib
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "apple", "tools"))
from testflight_testers import APIError, call  # noqa: E402

TYPES = ("PRODUCT_PAGE_HEADER_ASSET", "APP_STORE_SEARCH_RESULTS_ASSET")
EDITABLE = ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED")


def groups_by_type():
    data = call("GET", "/appAssetLibraryRefData", query={"filter[placementTypes]": ",".join(TYPES)})["data"]
    found = {t: [] for t in TYPES}
    for datum in data:
        for feature in datum["attributes"].get("features") or []:
            for policy in feature.get("placementPolicies") or []:
                if policy.get("placementType") in found:
                    for limit in policy.get("groupLimits") or []:
                        for group in limit.get("groupIds") or []:
                            if group not in found[policy["placementType"]]:
                                found[policy["placementType"]].append(group)
        for group in datum["attributes"].get("placementProfileGroups") or []:
            print(f"  profile group {group.get('placementProfileGroupId')}: {group.get('platform')} {group.get('displayClassId')}")
    return found


def version_to_edit(app_id):
    versions = call("GET", f"/apps/{app_id}/appStoreVersions",
                    query={"filter[platform]": "IOS", "limit": 10})["data"]
    for v in versions:
        a = v["attributes"]
        print(f"  version {a.get('versionString')} {a.get('platform')}: {a.get('appStoreState') or a.get('appVersionState')}")
    for v in versions:
        state = v["attributes"].get("appVersionState") or v["attributes"].get("appStoreState")
        if state in EDITABLE:
            return v
    return None


def upload(library_id, path, locale):
    data = open(path, "rb").read()
    name = f"encave-header-{locale}.png"
    reservation = call("POST", "/appAssetLibraryImages", {"data": {
        "type": "appAssetLibraryImages",
        "attributes": {"fileName": name, "fileSize": len(data), "category": "CREATIVE_ASSETS",
                       "referenceName": f"Encave header {locale} {hashlib.md5(data).hexdigest()[:8]}"},
        "relationships": {"assetLibrary": {"data": {"type": "appAssetLibraries", "id": library_id}}}}})["data"]
    for op in reservation["attributes"].get("uploadOperations") or []:
        chunk = data[op["offset"]:op["offset"] + op["length"]]
        request = urllib.request.Request(op["url"], data=chunk, method=op["method"])
        for header in op.get("requestHeaders") or []:
            request.add_header(header["name"], header["value"])
        with urllib.request.urlopen(request, timeout=120) as response:
            response.read()
    image_id = reservation["id"]
    call("PATCH", f"/appAssetLibraryImages/{image_id}", {"data": {
        "type": "appAssetLibraryImages", "id": image_id, "attributes": {"uploaded": True}}})
    for _ in range(60):
        attributes = call("GET", f"/appAssetLibraryImages/{image_id}")["data"]["attributes"]
        state = attributes.get("state")
        if state not in ("AWAITING_UPLOAD", "UPLOAD_COMPLETE", "COMPLETE"):
            break
        time.sleep(10)
    print(f"  {name}: image {image_id}, {state}")
    if state == "FAILED":
        sys.exit(f"Apple could not process {name}: {attributes.get('stateDetails')}")
    return image_id


def main():
    mode = os.environ.get("MODE", "inspect")
    bundle = os.environ.get("BUNDLE_ID", "app.encave")
    app_id = call("GET", "/apps", query={"filter[bundleId]": bundle, "limit": 1})["data"][0]["id"]
    library = call("GET", f"/apps/{app_id}/assetLibrary")["data"]
    print(f"Asset library: {library['id']}")
    images = call("GET", f"/appAssetLibraries/{library['id']}/images", query={"limit": 50})["data"]
    for image in images:
        a = image["attributes"]
        print(f"  library image {image['id']}: {a.get('fileName')} {a.get('state')} {a.get('referenceName')}")

    print("Placement groups:")
    groups = groups_by_type()
    for kind, ids in groups.items():
        print(f"  {kind}: {', '.join(ids) or 'none'}")

    print("Versions:")
    version = version_to_edit(app_id)
    if not version:
        sys.exit("No App Store version being prepared: create one in App Store Connect first.")
    localizations = call("GET", f"/appStoreVersions/{version['id']}/appStoreVersionLocalizations",
                         query={"limit": 50})["data"]
    by_locale = {loc["attributes"]["locale"]: loc["id"] for loc in localizations}
    print(f"Editing version {version['attributes'].get('versionString')}; localizations: {', '.join(by_locale)}")
    if mode != "upload":
        return

    wanted = dict(item.split("=", 1) for item in os.environ["IMAGES"].split(","))
    for locale, path in wanted.items():
        localization = by_locale.get(locale) or next(
            (i for l, i in by_locale.items() if l.split("-")[0] == locale.split("-")[0]), None)
        if not localization:
            print(f"::warning::No {locale} localization on this version; {path} skipped.")
            continue
        image_id = upload(library["id"], path, locale)
        for kind in TYPES:
            for group in groups[kind] or [None]:
                attributes = {"placementType": kind}
                if group:
                    attributes["placementGroup"] = group
                try:
                    call("POST", "/appAssetLibraryPlacements", {"data": {
                        "type": "appAssetLibraryPlacements", "attributes": attributes,
                        "relationships": {
                            "image": {"data": {"type": "appAssetLibraryImages", "id": image_id}},
                            "appStoreVersionLocalization": {"data": {
                                "type": "appStoreVersionLocalizations", "id": localization}}}}})
                    print(f"  {locale}: placed as {kind} ({group or 'default group'})")
                except APIError as error:
                    print(f"::warning::{locale} {kind} {group}: {error}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""App Store listing details, through the App Store Connect API.

MODE=inspect (default) prints the app information (name, subtitle, privacy
policy URL per language), the age rating answers and the texts of the App Store
version being prepared, per language.

MODE=apply sets what docs/store/appstore.json gives, and nothing else:
  privacyPolicyUrl                 every app information language
  ageRating                        the age rating questionnaire (API attribute names)
  appInfo.<locale>                 name, subtitle (the language is added if missing)
  version.<locale>                 description, keywords, promotionalText,
                                   supportUrl, marketingUrl, whatsNew (the language
                                   is added to the version if missing)
Nothing is submitted for review.

Environment: ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER_ID, BUNDLE_ID, MODE, STORE_INFO.
"""

import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "apple", "tools"))
from testflight_testers import APIError, call  # noqa: E402

EDITABLE_VERSION = ("PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED", "METADATA_REJECTED")
VERSION_FIELDS = ("description", "keywords", "promotionalText", "supportUrl", "marketingUrl", "whatsNew")


def short(value, limit=160):
    text = "" if value is None else str(value).replace("\n", " / ")
    return text if len(text) <= limit else text[:limit] + "…"


def editable_app_info(app_id):
    infos = call("GET", f"/apps/{app_id}/appInfos")["data"]
    for info in infos:
        print(f"App information {info['id']}: {info['attributes'].get('appStoreState') or info['attributes'].get('state')}")
    # The one being prepared is the one that is not live yet.
    for info in infos:
        state = info["attributes"].get("state") or info["attributes"].get("appStoreState")
        if state not in ("READY_FOR_DISTRIBUTION", "READY_FOR_SALE"):
            return info
    return infos[0]


def editable_version(app_id):
    versions = call("GET", f"/apps/{app_id}/appStoreVersions", query={"filter[platform]": "IOS"})["data"]
    for v in versions:
        if v["attributes"].get("appVersionState") in EDITABLE_VERSION:
            return v
    sys.exit("No App Store version being prepared.")


def main():
    mode = os.environ.get("MODE", "inspect")
    app_id = call("GET", "/apps", query={"filter[bundleId]": os.environ.get("BUNDLE_ID", "app.encave"),
                                         "limit": 1})["data"][0]["id"]
    info = editable_app_info(app_id)
    info_locs = {l["attributes"]["locale"]: l for l in call("GET", f"/appInfos/{info['id']}/appInfoLocalizations")["data"]}
    rating = call("GET", f"/appInfos/{info['id']}/ageRatingDeclaration")["data"]
    version = editable_version(app_id)
    version_locs = {l["attributes"]["locale"]: l for l in
                    call("GET", f"/appStoreVersions/{version['id']}/appStoreVersionLocalizations")["data"]}

    print("\n## App information")
    for locale, loc in info_locs.items():
        a = loc["attributes"]
        print(f"- {locale}: name={a.get('name')!r} subtitle={a.get('subtitle')!r} privacyPolicyUrl={a.get('privacyPolicyUrl')!r}")
    print("\n## Age rating")
    print(json.dumps({k: v for k, v in rating["attributes"].items() if v is not None}, sort_keys=True))
    print(f"\n## Version {version['attributes'].get('versionString')}")
    for locale, loc in version_locs.items():
        print(f"- {locale}:")
        for field in VERSION_FIELDS:
            print(f"    {field}: {short(loc['attributes'].get(field))}")
    if mode != "apply":
        return

    wanted = json.load(open(os.environ.get("STORE_INFO", "docs/store/appstore.json")))
    print("\n## Applying")

    for locale, fields in (wanted.get("appInfo") or {}).items():
        attributes = {k: v for k, v in fields.items() if v is not None}
        if wanted.get("privacyPolicyUrl"):
            attributes["privacyPolicyUrl"] = wanted["privacyPolicyUrl"]
        if locale in info_locs:
            call("PATCH", f"/appInfoLocalizations/{info_locs[locale]['id']}", {"data": {
                "type": "appInfoLocalizations", "id": info_locs[locale]["id"], "attributes": attributes}})
        else:
            created = call("POST", "/appInfoLocalizations", {"data": {
                "type": "appInfoLocalizations", "attributes": {"locale": locale, **attributes},
                "relationships": {"appInfo": {"data": {"type": "appInfos", "id": info["id"]}}}}})["data"]
            info_locs[locale] = created
        print(f"- app information {locale}: {', '.join(attributes)}")
    if wanted.get("privacyPolicyUrl"):
        for locale, loc in info_locs.items():
            if locale not in (wanted.get("appInfo") or {}):
                call("PATCH", f"/appInfoLocalizations/{loc['id']}", {"data": {
                    "type": "appInfoLocalizations", "id": loc["id"],
                    "attributes": {"privacyPolicyUrl": wanted["privacyPolicyUrl"]}}})
                print(f"- app information {locale}: privacyPolicyUrl")

    if wanted.get("ageRating"):
        try:
            call("PATCH", f"/ageRatingDeclarations/{rating['id']}", {"data": {
                "type": "ageRatingDeclarations", "id": rating["id"], "attributes": wanted["ageRating"]}})
            print("- age rating: set")
        except APIError as error:
            print(f"::warning::Age rating not set: {error}")

    for locale, fields in (wanted.get("version") or {}).items():
        attributes = {k: v for k, v in fields.items() if k in VERSION_FIELDS and v is not None}
        try:
            if locale in version_locs:
                call("PATCH", f"/appStoreVersionLocalizations/{version_locs[locale]['id']}", {"data": {
                    "type": "appStoreVersionLocalizations", "id": version_locs[locale]["id"],
                    "attributes": attributes}})
            else:
                call("POST", "/appStoreVersionLocalizations", {"data": {
                    "type": "appStoreVersionLocalizations", "attributes": {"locale": locale, **attributes},
                    "relationships": {"appStoreVersion": {"data": {"type": "appStoreVersions", "id": version["id"]}}}}})
            print(f"- version {locale}: {', '.join(attributes)}")
        except APIError as error:
            print(f"::warning::Version {locale} not updated: {error}")


if __name__ == "__main__":
    main()

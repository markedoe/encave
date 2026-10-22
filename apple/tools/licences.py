#!/usr/bin/env python3
"""Acknowledgements for the Apple app: Encave's licence, then each dependency with its licence.

Writes apple/Encave/Resources/Licences.json from Packages/CaveKit/Package.resolved.
  python3 apple/tools/licences.py           # write the file
  python3 apple/tools/licences.py --check   # fail if it is out of date or a dependency is unknown
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
RESOLVED = ROOT / "Packages" / "CaveKit" / "Package.resolved"
OUTPUT = ROOT / "Encave" / "Resources" / "Licences.json"

# Each package of Package.resolved: its name, licence and copyright, from its LICENSE and NOTICE files.
KNOWN = {
    "swift-crypto": ("Swift Crypto", "Apache-2.0", "Copyright 2019 The SwiftCrypto Project"),
    "swift-asn1": ("Swift ASN.1", "Apache-2.0", "Copyright 2022 The SwiftASN1 Project"),
}

LICENCE_NAMES = {
    "AGPL-3.0": "GNU Affero General Public License v3.0",
    "Apache-2.0": "Apache License 2.0",
    "MIT": "MIT License",
}


def licence_text(identifier):
    path = REPO / "LICENSE" if identifier == "AGPL-3.0" else REPO / "licences" / f"{identifier}.txt"
    return path.read_text().strip() + "\n"


def build():
    pins = json.loads(RESOLVED.read_text())["pins"]
    unknown = [pin["identity"] for pin in pins if pin["identity"] not in KNOWN]
    if unknown:
        raise SystemExit(f"no licence entry for {', '.join(unknown)}: add it to KNOWN in apple/tools/licences.py")
    dependencies = []
    for pin in sorted(pins, key=lambda p: KNOWN[p["identity"]][0].lower()):
        name, licence, copyright = KNOWN[pin["identity"]]
        dependencies.append({
            "name": name,
            "version": pin["state"].get("version", pin["state"].get("revision", "")[:12]),
            "url": pin["location"].removesuffix(".git"),
            "licence": licence,
            "copyright": copyright,
        })
    used = ["AGPL-3.0"] + sorted({d["licence"] for d in dependencies})
    data = {
        "dependencies": dependencies,
        "licences": {identifier: {"name": LICENCE_NAMES[identifier], "text": licence_text(identifier)} for identifier in used},
    }
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    text = build()
    if "--check" in sys.argv:
        if not OUTPUT.exists() or OUTPUT.read_text() != text:
            print(f"{OUTPUT.name} is not up to date: run python3 apple/tools/licences.py")
            sys.exit(1)
    else:
        OUTPUT.write_text(text)
        print(f"Wrote {OUTPUT.relative_to(REPO)}")

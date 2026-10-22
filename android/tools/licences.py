#!/usr/bin/env python3
"""Acknowledgements for the Android app: each library in the release build with its licence.

Reads the app's release runtime classpath from Gradle and writes
android/app/src/main/res/raw/licences.json (shown in Settings › About › Licences).
  python3 android/tools/licences.py           # write the file
  python3 android/tools/licences.py --check   # fail if it is out of date or a library is unknown
  ... --from deps.txt                         # read saved output of ":app:dependencies" instead
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parent.parent
REPO = ANDROID.parent
OUTPUT = ANDROID / "app" / "src" / "main" / "res" / "raw" / "licences.json"

# Maven group (or its start) -> project, licence, home page, copyright. Checked against each
# project's published POM and licence file; the first match wins.
PROJECTS = [
    ("androidx.", "Android Jetpack (AndroidX)", "Apache-2.0", "https://developer.android.com/jetpack/androidx",
     "Copyright The Android Open Source Project"),
    ("org.jetbrains.kotlinx", "Kotlin libraries (kotlinx)", "Apache-2.0", "https://github.com/Kotlin",
     "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"),
    ("org.jetbrains.kotlin", "Kotlin standard library", "Apache-2.0", "https://kotlinlang.org",
     "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors"),
    ("org.jetbrains", "JetBrains Java Annotations", "Apache-2.0", "https://github.com/JetBrains/java-annotations",
     "Copyright JetBrains s.r.o."),
    ("com.google.zxing", "ZXing", "Apache-2.0", "https://github.com/zxing/zxing", "Copyright ZXing authors"),
    ("io.github.zxing-cpp", "zxing-cpp", "Apache-2.0", "https://github.com/zxing-cpp/zxing-cpp",
     "Copyright Axel Waggershauser and zxing-cpp contributors"),
    ("com.squareup.okhttp3", "OkHttp", "Apache-2.0", "https://square.github.io/okhttp/", "Copyright Square, Inc."),
    ("com.squareup.okio", "Okio", "Apache-2.0", "https://square.github.io/okio/", "Copyright Square, Inc."),
    ("com.google.guava", "Guava", "Apache-2.0", "https://github.com/google/guava", "Copyright The Guava Authors"),
    ("com.google.dagger", "Dagger", "Apache-2.0", "https://dagger.dev", "Copyright The Dagger Authors"),
    ("com.google.auto.value", "AutoValue annotations", "Apache-2.0", "https://github.com/google/auto",
     "Copyright Google LLC"),
    ("com.google.errorprone", "Error Prone annotations", "Apache-2.0", "https://errorprone.info",
     "Copyright The Error Prone Authors"),
    ("com.google.j2objc", "J2ObjC annotations", "Apache-2.0", "https://github.com/google/j2objc",
     "Copyright Google Inc."),
    ("com.google.code.findbugs", "JSR 305 annotations", "Apache-2.0", "https://github.com/findbugsproject/findbugs",
     "Copyright FindBugs authors"),
    ("org.checkerframework", "Checker Framework qualifiers", "MIT", "https://checkerframework.org",
     "Copyright 2004-present by the Checker Framework developers"),
    ("org.jspecify", "JSpecify", "Apache-2.0", "https://jspecify.dev", "Copyright The JSpecify Authors"),
    ("javax.inject", "javax.inject", "Apache-2.0", "https://github.com/javax-inject/javax-inject",
     "Copyright The JSR-330 Expert Group"),
    ("jakarta.inject", "Jakarta Dependency Injection", "Apache-2.0", "https://github.com/jakartaee/inject",
     "Copyright Eclipse Foundation and contributors"),
]

LICENCE_NAMES = {
    "AGPL-3.0": "GNU Affero General Public License v3.0",
    "Apache-2.0": "Apache License 2.0",
    "MIT": "MIT License",
}

COORDINATE = re.compile(r"[+\\]--- ([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\s]+)(?: -> ([^\s]+))?")


def dependencies_text(argv):
    if "--from" in argv:
        return Path(argv[argv.index("--from") + 1]).read_text()
    gradlew = ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return subprocess.run(
        [str(gradlew), "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"],
        cwd=ANDROID, check=True, capture_output=True, text=True,
    ).stdout


def project_for(group):
    for prefix, *info in PROJECTS:
        # "androidx." covers every androidx group; other entries a group and its subgroups.
        if group.startswith(prefix) if prefix.endswith(".") else group == prefix or group.startswith(prefix + "."):
            return prefix, info
    return None


def licence_text(identifier):
    path = REPO / "LICENSE" if identifier == "AGPL-3.0" else REPO / "licences" / f"{identifier}.txt"
    return path.read_text().strip() + "\n"


def build(text):
    artifacts = {}
    for match in COORDINATE.finditer(text):
        group, name, version, resolved = match.groups()
        line_end = text.find("\n", match.end())
        if text[match.end():line_end].strip() == "(c)":
            continue  # a constraint, not a library
        artifacts[(group, name)] = resolved or version
    if not artifacts:
        raise SystemExit("no dependencies found in the Gradle output")
    unknown = sorted({group for group, _ in artifacts if project_for(group) is None})
    if unknown:
        raise SystemExit(f"no licence entry for {', '.join(unknown)}: add it to PROJECTS in android/tools/licences.py")
    projects = {}
    for (group, name), version in sorted(artifacts.items()):
        prefix, (title, licence, url, copyright) = project_for(group)
        entry = projects.setdefault(prefix, {"name": title, "url": url, "licence": licence, "copyright": copyright, "artifacts": []})
        entry["artifacts"].append(f"{group}:{name}:{version}")
    dependencies = sorted(projects.values(), key=lambda p: p["name"].lower())
    used = ["AGPL-3.0"] + sorted({d["licence"] for d in dependencies})
    data = {
        "dependencies": dependencies,
        "licences": {identifier: {"name": LICENCE_NAMES[identifier], "text": licence_text(identifier)} for identifier in used},
    }
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    output = build(dependencies_text(sys.argv))
    if "--check" in sys.argv:
        if not OUTPUT.exists() or OUTPUT.read_text() != output:
            print(f"{OUTPUT.name} is not up to date: run python3 android/tools/licences.py")
            sys.exit(1)
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(output)
        print(f"Wrote {OUTPUT.relative_to(REPO)}")

#!/usr/bin/env python3
"""Smoke test of a release build on a running emulator, through adb.

Installs the APK, starts the app, loads the sample cellar and opens each of the
four tabs, in English then in French (per-app language). Fails when a tab cannot
be found, the app stops, or the crash log mentions it. Screenshots of each tab
go to the output folder.

Standard library only; uses adb, uiautomator and screencap on the device.

Usage: emulator_smoke.py APK OUTPUT_DIR
"""

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "app.encave"
ACTIVITY = f"{PACKAGE}/.MainActivity"

# The four tabs and the sample cellar, as android/app/src/main/res/values*/strings.xml
# give them (nav_*, action_load_sample, cellar_sample_title, action_remove_sample).
LANGUAGES = {
    "en-US": {
        "tabs": ["Cellar", "Storage", "Tonight", "Journal"],
        "load_sample": "Load sample cellar",
        "sample_loaded": ["You are exploring the sample cellar", "Remove sample"],
    },
    "fr-FR": {
        "tabs": ["Cave", "Rangements", "Ce soir", "Journal"],
        "load_sample": "Charger une cave d’exemple",
        "sample_loaded": ["Vous explorez la cave d’exemple", "Retirer l’exemple"],
    },
}


def adb(*args, check=True, binary=False):
    result = subprocess.run(["adb", *args], capture_output=True, check=False, timeout=120)
    if check and result.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
    return result.stdout if binary else result.stdout.decode(errors="replace")


def shell(command, check=True):
    return adb("shell", command, check=check)


def nodes():
    """Every node on screen: (text, content description, centre, selected)."""
    for _ in range(5):
        out = shell("uiautomator dump /sdcard/window.xml", check=False)
        if "dumped to" in out:
            xml = shell("cat /sdcard/window.xml")
            try:
                root = ET.fromstring(xml[xml.index("<"):])
            except (ValueError, ET.ParseError):
                time.sleep(1)
                continue
            found = []
            for node in root.iter("node"):
                bounds = re.findall(r"\d+", node.get("bounds", ""))
                if len(bounds) != 4:
                    continue
                x1, y1, x2, y2 = map(int, bounds)
                found.append((node.get("text", "").strip(), node.get("content-desc", "").strip(),
                              ((x1 + x2) // 2, (y1 + y2) // 2), node.get("selected") == "true"))
            return found
        time.sleep(1)
    sys.exit("uiautomator could not read the screen.")


def check_running():
    if not shell(f"pidof {PACKAGE}", check=False).strip():
        crash = adb("logcat", "-d", "-b", "crash")
        sys.exit(f"The app is no longer running.\n{crash[-6000:]}")


def find(labels, timeout=30):
    """The bottom-most node whose text or description is one of the labels (tab bar first)."""
    deadline = time.time() + timeout
    while True:
        matches = [n for n in nodes() if n[0] in labels or n[1] in labels]
        if matches:
            return max(matches, key=lambda n: n[2][1])
        check_running()
        if time.time() > deadline:
            return None
        time.sleep(1)


def tap(node):
    x, y = node[2]
    shell(f"input tap {x} {y}")


def screenshot(out, name):
    (out / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def start(language):
    shell(f"am force-stop {PACKAGE}")
    shell(f"cmd locale set-app-locales {PACKAGE} --locales {language}")
    shell(f"am start -W -n {ACTIVITY}")
    time.sleep(2)
    check_running()


def load_sample(strings, out):
    if find(strings["sample_loaded"], timeout=5):
        return
    button = find([strings["load_sample"]])
    if not button:
        screenshot(out, "no-sample-button")
        sys.exit(f"No \"{strings['load_sample']}\" button on the empty Cellar.")
    tap(button)
    # The confirmation dialog repeats the label on its button.
    time.sleep(1)
    tap(find([strings["load_sample"]]) or sys.exit("No confirmation for the sample cellar."))
    if not find(strings["sample_loaded"]):
        screenshot(out, "sample-not-loaded")
        sys.exit("The sample cellar did not appear in the Cellar.")
    print("Sample cellar loaded.")


def main():
    if len(sys.argv) != 3:
        sys.exit("Usage: emulator_smoke.py APK OUTPUT_DIR")
    apk, out = sys.argv[1], Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    adb("install", "-r", apk)
    adb("logcat", "-c", "-b", "all", check=False)
    # No system dialog over the app when it asks (reminders).
    shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)

    for language, strings in LANGUAGES.items():
        start(language)
        first = find(strings["tabs"][:1], timeout=60)
        if not first:
            screenshot(out, f"{language}-start")
            sys.exit(f"{language}: the {strings['tabs'][0]} tab did not appear.")
        load_sample(strings, out)
        for index, label in enumerate(strings["tabs"], start=1):
            node = find([label])
            if not node:
                screenshot(out, f"{language}-missing-{index}")
                sys.exit(f"{language}: no \"{label}\" tab.")
            tap(node)
            time.sleep(2)
            check_running()
            if len(nodes()) < 8:
                screenshot(out, f"{language}-blank-{index}")
                sys.exit(f"{language}: the {label} tab looks empty.")
            screenshot(out, f"{language}-{index}-{label.replace(' ', '-').lower()}")
            print(f"{language}: {label} opened.")

    crash = adb("logcat", "-d", "-b", "crash")
    if PACKAGE in crash:
        sys.exit(f"The crash log mentions the app:\n{crash[-6000:]}")
    print("Smoke test passed: the four tabs open in English and French.")


if __name__ == "__main__":
    main()

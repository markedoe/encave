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

PACKAGE = "markeddoe.com.encave"  # applicationId; the classes stay in app.encave
ACTIVITY = f"{PACKAGE}/app.encave.MainActivity"

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


# System dialogs of an app that stopped responding or crashed: "<app> isn't responding",
# "<app> has stopped", "<app> keeps stopping" (either apostrophe).
SYSTEM_DIALOG = re.compile(r"^(?P<app>.+?) (isn['’]t responding|has stopped|keeps stopping)$")
# The button that closes each kind of dialog, in order of preference.
ANR_BUTTONS = ["Wait", "Close app", "Close", "OK"]
CRASH_BUTTONS = ["Close app", "Close", "OK"]


def system_dialog(found):
    """The title and the button to tap of a system dialog of another app, or None.
    Exits when the dialog is about Encave itself."""
    for node in found:
        match = SYSTEM_DIALOG.match(node[0])
        if not match:
            continue
        title = node[0]
        if "Encave" in match.group("app"):
            sys.exit(f"Encave stopped or is not responding: {title}")
        buttons = ANR_BUTTONS if "responding" in title else CRASH_BUTTONS
        for label in buttons:
            button = [n for n in found if n[0] == label]
            if button:
                return title, button[0]
    return None


def dismiss_other_anr(found):
    """On a slow CI emulator another app (often the launcher) can stop responding or crash,
    and its system dialog then covers Encave: close it. Encave's own dialog still fails."""
    dialog = system_dialog(found)
    if not dialog:
        return False
    print(f"Dismissed a system dialog: {dialog[0]}")
    tap(dialog[1])
    time.sleep(1)
    return True


def screen():
    """The nodes on screen, once any system dialog of another app is closed."""
    found = nodes()
    if dismiss_other_anr(found):
        found = nodes()
    return found


def find(labels, timeout=30):
    """The bottom-most node whose text or description is one of the labels (tab bar first)."""
    deadline = time.time() + timeout
    while True:
        found = screen()
        matches = [n for n in found if n[0] in labels or n[1] in labels]
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
    # The confirmation dialog repeats the label on its button. Another app's system dialog
    # can take a tap meant for it (closed by screen()): try again, at most three times.
    for _ in range(3):
        time.sleep(1)
        tap(find([strings["load_sample"]]) or sys.exit("No confirmation for the sample cellar."))
        if find(strings["sample_loaded"], timeout=15):
            break
    else:
        screenshot(out, "sample-not-loaded")
        sys.exit("The sample cellar did not appear in the Cellar.")
    print("Sample cellar loaded.")


# Home screen apps of the emulator images CI uses.
LAUNCHERS = ("com.google.android.apps.nexuslauncher", "com.android.launcher3")


def main():
    if len(sys.argv) != 3:
        sys.exit("Usage: emulator_smoke.py APK OUTPUT_DIR")
    # Every line reaches the log at once, also when the job times out.
    sys.stdout.reconfigure(line_buffering=True)
    apk, out = sys.argv[1], Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    # The launcher is the app most often not responding on a busy CI emulator, and its
    # dialog covers Encave: the test does not need it, so it is turned off (only on this
    # throwaway emulator). Background ANR dialogs are hidden too.
    shell("input keyevent KEYCODE_WAKEUP", check=False)
    for launcher in LAUNCHERS:
        print(f"Turning off {launcher}: {shell(f'pm disable-user --user 0 {launcher}', check=False).strip()}")
        shell(f"am force-stop {launcher}", check=False)
    shell("settings put secure anr_show_background 0", check=False)
    # Other apps' "isn't responding" and crash dialogs are not shown at all (system setting
    # of the throwaway emulator); Encave's own crashes still fail through the crash log.
    shell("settings put global hide_error_dialogs 1", check=False)
    time.sleep(5)
    adb("install", "-r", apk)
    adb("logcat", "-c", "-b", "all", check=False)

    for language, strings in LANGUAGES.items():
        # A fresh app in each language: the sample cellar is loaded in that language, not kept
        # from the run before (its names, places and notes are written when it is loaded).
        shell(f"pm clear {PACKAGE}")
        # No system dialog over the app when it asks (reminders); pm clear took the grant back.
        shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)
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
            if len(screen()) < 8:
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

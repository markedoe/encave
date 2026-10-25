"""Unit tests of emulator_smoke.py with fake screens (no emulator): python3 -m unittest discover -s android/tools"""

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emulator_smoke  # noqa: E402


def node(text, y=100):
    return (text, "", (50, y), False)


def dialog(title, *buttons):
    return [node(title, 500)] + [node(b, 600 + i) for i, b in enumerate(buttons)]


TABS = [node("Cellar", 2000), node("Storage", 2000), node("Tonight", 2000), node("Journal", 2000)]


class SystemDialogTest(unittest.TestCase):
    def setUp(self):
        self.taps = []
        patches = [mock.patch.object(emulator_smoke, "tap", self.taps.append),
                   mock.patch.object(emulator_smoke.time, "sleep", lambda _: None),
                   mock.patch.object(emulator_smoke, "check_running", lambda: None)]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def test_not_responding_both_apostrophes(self):
        for title in ("Pixel Launcher isn't responding", "Pixel Launcher isn’t responding"):
            self.taps.clear()
            self.assertTrue(emulator_smoke.dismiss_other_anr(dialog(title, "Close app", "Wait")))
            self.assertEqual([n[0] for n in self.taps], ["Wait"])

    def test_other_app_stopped(self):
        for title, buttons, tapped in (("System UI has stopped", ["OK"], "OK"),
                                       ("Gmail keeps stopping", ["App info", "Close app"], "Close app"),
                                       ("Maps has stopped", ["Close"], "Close")):
            self.taps.clear()
            self.assertTrue(emulator_smoke.dismiss_other_anr(dialog(title, *buttons)))
            self.assertEqual([n[0] for n in self.taps], [tapped])

    def test_encave_dialog_exits(self):
        for title in ("Encave isn't responding", "Encave isn’t responding", "Encave has stopped",
                      "Encave keeps stopping"):
            with self.assertRaises(SystemExit):
                emulator_smoke.dismiss_other_anr(dialog(title, "Close app", "Wait"))
        self.assertEqual(self.taps, [])

    def test_no_dialog(self):
        self.assertFalse(emulator_smoke.dismiss_other_anr(TABS + [node("You are exploring the sample cellar")]))
        self.assertFalse(emulator_smoke.dismiss_other_anr([node("Launcher isn't responding")]))  # no button yet
        self.assertEqual(self.taps, [])

    def test_find_closes_the_dialog_first(self):
        screens = iter([dialog("Pixel Launcher isn’t responding", "Close app", "Wait"), TABS])
        with mock.patch.object(emulator_smoke, "nodes", lambda: next(screens)):
            found = emulator_smoke.find(["Tonight"], timeout=0)
        self.assertEqual(found[0], "Tonight")
        self.assertEqual([n[0] for n in self.taps], ["Wait"])

    def test_find_exits_on_encave_dialog(self):
        with mock.patch.object(emulator_smoke, "nodes", lambda: dialog("Encave isn’t responding", "Wait") + TABS):
            with self.assertRaises(SystemExit):
                emulator_smoke.find(["Tonight"], timeout=0)


if __name__ == "__main__":
    unittest.main()

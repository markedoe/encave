"""Unit tests of play_publish.py that need no network: python3 -m unittest discover -s android/tools"""

import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play_publish  # noqa: E402


def track(*releases):
    return {"track": "internal", "releases": [{"status": s, "versionCodes": codes} for s, codes in releases]}


class NewerReleaseTest(unittest.TestCase):
    def test_empty_track(self):
        self.assertIsNone(play_publish.newer_release({}, 4761))
        self.assertIsNone(play_publish.newer_release(track(), 4761))

    def test_older_or_same_release(self):
        self.assertIsNone(play_publish.newer_release(track(("completed", ["4751"])), 4761))
        self.assertIsNone(play_publish.newer_release(track(("completed", ["4761"])), 4761))

    def test_newer_release(self):
        self.assertEqual(play_publish.newer_release(track(("completed", ["4771"])), 4761), 4771)
        self.assertEqual(play_publish.newer_release(track(("draft", ["4771"])), 4761), 4771)
        self.assertEqual(play_publish.newer_release(
            track(("completed", ["4751"]), ("inProgress", ["4781", "4782"])), 4761), 4782)

    def test_halted_release_is_ignored(self):
        self.assertIsNone(play_publish.newer_release(track(("halted", ["4771"])), 4761))


class OutOfOrderTest(unittest.TestCase):
    """A run that finishes after a newer one leaves the track alone."""

    def run_step(self, step, current, **extra):
        calls = []

        def fake_call(method, url, body=None, **kwargs):
            calls.append((method, url.rsplit("/applications/", 1)[-1]))
            if method == "POST" and url.endswith("/edits"):
                return {"id": "e1"}
            if method == "GET" and "/tracks/" in url:
                return current
            return {}

        env = {"PLAY_ACCESS_TOKEN": "t", "PACKAGE_NAME": "p", "VERSION_CODE": "4761", "TRACK": "internal",
               "BUNDLE_PATH": os.devnull, **extra}
        with mock.patch.object(play_publish, "call", fake_call), mock.patch.dict(os.environ, env, clear=True), \
                mock.patch.object(sys, "argv", ["play_publish.py", step]), mock.patch("builtins.print"):
            play_publish.main()
        return calls

    def test_skips_when_newer(self):
        for step in ("upload", "promote"):
            calls = self.run_step(step, track(("completed", ["4771"])))
            self.assertEqual(calls, [("POST", "p/edits"), ("GET", "p/edits/e1/tracks/internal"),
                                     ("DELETE", "p/edits/e1")], step)

    def test_allow_older(self):
        calls = self.run_step("promote", track(("completed", ["4771"])), ALLOW_OLDER="true")
        self.assertIn(("PUT", "p/edits/e1/tracks/internal"), calls)

    def test_releases_when_newest(self):
        calls = self.run_step("promote", track(("completed", ["4751"])))
        self.assertIn(("PUT", "p/edits/e1/tracks/internal"), calls)
        self.assertIn(("POST", "p/edits/e1:commit"), calls)


if __name__ == "__main__":
    unittest.main()

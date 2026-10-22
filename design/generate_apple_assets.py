#!/usr/bin/env python3
"""Generates the Apple asset catalog colors from design/tokens.json.

Usage: python3 design/generate_apple_assets.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOKENS = ROOT / "design" / "tokens.json"
ASSETS = ROOT / "apple" / "Encave" / "Resources" / "Assets.xcassets"

# token name -> asset name used in Swift (Color("WineRed")); accent uses Xcode's AccentColor.
NAMES = {
    "accent": "AccentColor",
    "wine-red": "WineRed",
    "wine-white": "WineWhite",
    "wine-rose": "WineRose",
    "wine-sparkling": "WineSparkling",
    "wine-dessert": "WineDessert",
    "wine-fortified": "WineFortified",
    "wine-orange": "WineOrange",
    "warning": "Warning",
}


def components(hex_color: str) -> dict:
    h = hex_color.lstrip("#")
    return {
        "red": f"0x{h[0:2]}",
        "green": f"0x{h[2:4]}",
        "blue": f"0x{h[4:6]}",
        "alpha": "1.000",
    }


def colorset(light: str, dark: str) -> dict:
    return {
        "colors": [
            {"color": {"color-space": "srgb", "components": components(light)}, "idiom": "universal"},
            {
                "appearances": [{"appearance": "luminosity", "value": "dark"}],
                "color": {"color-space": "srgb", "components": components(dark)},
                "idiom": "universal",
            },
        ],
        "info": {"author": "xcode", "version": 1},
    }


def main() -> None:
    tokens = json.loads(TOKENS.read_text())["color"]
    for token, asset in NAMES.items():
        value = tokens[token]["$value"]
        folder = ASSETS / f"{asset}.colorset"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "Contents.json").write_text(json.dumps(colorset(value["light"], value["dark"]), indent=2) + "\n")
    (ASSETS / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    print(f"Wrote {len(NAMES)} colors to {ASSETS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

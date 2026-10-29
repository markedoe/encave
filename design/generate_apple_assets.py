#!/usr/bin/env python3
"""Generates the Apple asset catalog colors from design/tokens.json.

Usage: python3 design/generate_apple_assets.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOKENS = ROOT / "design" / "tokens.json"
ASSETS = ROOT / "apple" / "Encave" / "Resources" / "Assets.xcassets"
# The widget extension's own catalog: only the colors the widgets use (no app icon).
WIDGET_ASSETS = ROOT / "apple" / "EncaveWidgets" / "Resources" / "Assets.xcassets"
WIDGET_COLORS = ["AccentColor", "MaturityDrinkSoon", "MaturityReady", "MaturityPastPeak", "MaturityKeep"]

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
    "maturity-drink-soon": "MaturityDrinkSoon",
    "maturity-ready": "MaturityReady",
    "maturity-past-peak": "MaturityPastPeak",
    "maturity-keep": "MaturityKeep",
    "warning": "Warning",
    "success": "Success",
    "error": "Error",
    "action": "Action",
    "destructive": "Destructive",
    "neutral": "Neutral",
    "on-tint": "OnTint",
    "ink-on-light": "InkOnLight",
    "ink-on-dark": "InkOnDark",
    "mac-sidebar-selection": "MacSidebarSelection",
    "tile-background": "TileBackground",
    "tile-text": "TileText",
    "studio-base": "StudioBase",
    "studio-candle": "StudioCandle",
    "studio-red": "StudioRed",
    "studio-white": "StudioWhite",
    "studio-rose": "StudioRose",
    "studio-sparkling": "StudioSparkling",
    "studio-dessert": "StudioDessert",
    "studio-fortified": "StudioFortified",
    "studio-orange": "StudioOrange",
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
    for catalog, names in ((ASSETS, list(NAMES.values())), (WIDGET_ASSETS, WIDGET_COLORS)):
        for token, asset in NAMES.items():
            if asset not in names:
                continue
            value = tokens[token]["$value"]
            folder = catalog / f"{asset}.colorset"
            folder.mkdir(parents=True, exist_ok=True)
            (folder / "Contents.json").write_text(json.dumps(colorset(value["light"], value["dark"]), indent=2) + "\n")
        (catalog / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
        print(f"Wrote {len(names)} colors to {catalog.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

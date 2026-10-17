#!/usr/bin/env python3
"""Draws the app icon (wine glass on burgundy) for iOS and macOS.

iOS: one 1024 px RGB image (no alpha, required by App Store Connect).
macOS: rounded-square artwork on a transparent canvas, all required sizes.
Usage: python3 design/generate_app_icon.py
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "apple" / "Encave" / "Resources" / "Assets.xcassets" / "AppIcon.appiconset"
SS = 4  # supersampling factor
TOP, BOTTOM = (166, 38, 70), (96, 16, 38)


def gradient(size: int) -> Image.Image:
    img = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        t = y / (size - 1)
        draw.line([(0, y), (size, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    return img


def glass(size: int) -> Image.Image:
    """White wine glass with a wine line, on transparent background."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    s = size / 1024
    white = (255, 255, 255, 255)
    # Bowl: lower half of an ellipse plus straight rim.
    bowl = [(512 - 190) * s, 230 * s, (512 + 190) * s, 640 * s]
    d.chord(bowl, start=0, end=180, fill=white)
    d.rectangle([bowl[0], 230 * s, bowl[2], 435 * s], fill=white)
    # Wine inside the bowl.
    wine = [(512 - 160) * s, 260 * s, (512 + 160) * s, 610 * s]
    d.chord(wine, start=0, end=180, fill=(140, 28, 52, 255))
    d.rectangle([wine[0], 400 * s, wine[2], 436 * s], fill=(140, 28, 52, 255))
    # Stem and foot.
    d.rectangle([(512 - 18) * s, 630 * s, (512 + 18) * s, 800 * s], fill=white)
    d.rounded_rectangle([(512 - 150) * s, 790 * s, (512 + 150) * s, 830 * s], radius=20 * s, fill=white)
    return layer


def artwork(size: int) -> Image.Image:
    big = size * SS
    img = gradient(big).convert("RGBA")
    g = glass(big)
    shadow = Image.new("RGBA", g.size, (0, 0, 0, 0))
    shadow.paste((40, 0, 10, 110), mask=g.split()[3])
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(10 * SS)), (0, int(8 * SS * size / 1024)))
    img.alpha_composite(g)
    return img.resize((size, size), Image.LANCZOS)


def mac_icon(size: int) -> Image.Image:
    """Apple's macOS grid: 824/1024 rounded square, ~22.5% corner radius."""
    big = 1024 * SS
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    inner = round(824 * SS)
    art = artwork(1024).resize((inner, inner), Image.LANCZOS)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, inner - 1, inner - 1], radius=round(inner * 0.225), fill=255)
    offset = (big - inner) // 2
    canvas.paste(art, (offset, offset), mask)
    return canvas.resize((size, size), Image.LANCZOS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    images = []

    ios = artwork(1024).convert("RGB")  # no alpha channel
    ios.save(OUT / "icon-ios-1024.png", optimize=True)
    images.append({"filename": "icon-ios-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"})

    for points in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            px = points * scale
            name = f"icon-mac-{points}@{scale}x.png"
            mac_icon(px).save(OUT / name, optimize=True)
            images.append({"filename": name, "idiom": "mac", "scale": f"{scale}x", "size": f"{points}x{points}"})

    (OUT / "Contents.json").write_text(json.dumps({"images": images, "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
    print(f"Wrote {len(images)} icons to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Makes the Google Play store listing images from the Android screen tour.

Usage: python3 android/tools/play_listing_images.py <screens folder> <output folder>

<screens folder> is the android-screens artifact of android.yml (ScreenTourTest, sample
cellar only): phone/<lang>-NN-<screen>.png (411x891 dp at xxhdpi) and tablet/... (1280x800 dp
landscape). The screens and captions come from docs/store/play.json.

Writes, for fr-FR and en-US, <output>/<language>/<imageType>/NN.png, the layout read by
play_publish.py:
  icon                  512x512, from design/generate_app_icon.py, no alpha
  featureGraphic        1024x500, the style of docs/store/make_header.py
  phoneScreenshots      1290x2580 at most 2:1 (the raw phone capture is 2.17:1, which
                        Play refuses): the screen in a frame under its caption
  sevenInchScreenshots  2560x1600, the tablet captures as they are
  tenInchScreenshots    the same files
Play's rules (each side 320 to 3840 px, at most 2:1, 24-bit PNG) are checked at the end.
Output is the same, byte for byte, for the same input: play_publish.py skips unchanged
images. Needs Pillow and the Inter Display font (Ubuntu: python3-pil fonts-inter).
"""

import glob
import json
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "design"))
import generate_app_icon  # noqa: E402  (the app icon artwork)

LISTING = json.loads((ROOT / "docs" / "store" / "play.json").read_text())
TOKENS = json.loads((ROOT / "design" / "tokens.json").read_text())
LANGUAGES = {"fr-FR": "fr", "en-US": "en"}

PHONE_W, PHONE_H = 1290, 2580
FEATURE_W, FEATURE_H = 1024, 500


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# The brand accent (design/tokens.json) at the bottom, a darker shade of it at the top:
# the colors of the App Store header images.
ACCENT = rgb(TOKENS["color"]["accent"]["$value"]["light"])
TOP = tuple(round(c * 0.52) for c in ACCENT)
GLOW = tuple(min(255, round(c * 1.4 + 20)) for c in ACCENT)
WHITE, SOFT = (255, 255, 255), (255, 233, 238)


def find_font(style):
    for path in sorted(glob.glob("/usr/share/fonts/**/*.[ot]tf", recursive=True)):
        if os.path.basename(path).lower().startswith(style.lower() + "."):
            return path
    for fallback in ("DejaVuSans-Bold" if "Bold" in style else "DejaVuSans",):
        for path in glob.glob(f"/usr/share/fonts/**/{fallback}.ttf", recursive=True):
            print(f"::warning::Font {style} not found, using {fallback} (install fonts-inter).")
            return path
    sys.exit(f"Font {style} not found: install fonts-inter.")


BOLD, SEMI = find_font("InterDisplay-Bold"), find_font("InterDisplay-SemiBold")


def background(width, height):
    img = Image.new("RGB", (width, height))
    d = ImageDraw.Draw(img)
    for y in range(height):
        t = y / (height - 1)
        d.line([(0, y), (width, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, ACCENT)))
    glow = Image.new("L", (width, height), 0)
    ImageDraw.Draw(glow).ellipse([width * 0.3, height * 0.15, width * 1.1, height * 0.95], fill=80)
    glow = glow.filter(ImageFilter.GaussianBlur(min(width, height) * 0.15))
    img.paste(Image.new("RGB", (width, height), GLOW), (0, 0), glow)
    return img


def framed(shot, height):
    """The screen, scaled to height, in a dark rounded frame with a soft shadow (RGBA)."""
    shot = shot.convert("RGB")
    width = round(shot.width * height / shot.height)
    shot = shot.resize((width, height), Image.LANCZOS)
    bezel, radius = round(height * 0.018), round(height * 0.05)
    fw, fh = width + 2 * bezel, height + 2 * bezel
    pad = round(height * 0.06)
    out = Image.new("RGBA", (fw + 2 * pad, fh + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("L", out.size, 0)
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + pad // 3, pad + fw, pad + fh + pad // 3],
                                             radius=radius + bezel, fill=110)
    out.paste((0, 0, 0, 255), (0, 0), shadow.filter(ImageFilter.GaussianBlur(pad // 2)))
    frame = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, fw - 1, fh - 1], radius=radius + bezel, fill=(18, 16, 17, 255))
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, height - 1], radius=radius, fill=255)
    frame.paste(shot, (bezel, bezel), mask)
    out.alpha_composite(frame, (pad, pad))
    return out, pad


def wrap(draw, text, font, width):
    lines, line = [], ""
    for word in text.split(" "):
        candidate = f"{line} {word}".strip()
        if line and draw.textlength(candidate, font=font) > width:
            lines.append(line)
            line = word
        else:
            line = candidate
    return lines + [line]


def phone_screenshot(shot, caption):
    img = background(PHONE_W, PHONE_H).convert("RGBA")
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(BOLD, 96)
    lines = wrap(d, caption, font, PHONE_W - 200)
    y = 190 if len(lines) == 1 else 140
    for line in lines:
        d.text((PHONE_W // 2, y), line, font=font, fill=WHITE, anchor="mt")
        y += 118
    frame, pad = framed(shot, PHONE_H - 520 - 60)
    img.alpha_composite(frame, ((PHONE_W - frame.width) // 2, 470 - pad))
    return img.convert("RGB")


def grapes(img, x0, y0, r):
    """A discreet bunch of grapes, as on the App Store header."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    c = (255, 255, 255, 16)
    for i, n in enumerate([5, 4, 4, 3, 2, 1]):
        for j in range(n):
            x, y = x0 + (j - (n - 1) / 2) * r * 1.9, y0 + 2.7 * r + i * r * 1.65
            d.ellipse([x - r, y - r, x + r, y + r], fill=c)
    d.line([(x0, y0 + 1.8 * r), (x0 + r / 2, y0 - r * 0.6)], fill=c, width=max(1, round(r / 3)))
    img.alpha_composite(layer)


def feature_graphic(shot, headline):
    img = background(FEATURE_W, FEATURE_H).convert("RGBA")
    grapes(img, 960, 18, 16)
    # A phone on the right, running off the bottom edge.
    frame, pad = framed(shot, 620)
    img.alpha_composite(frame, (FEATURE_W - frame.width - 30 + pad, 70 - pad))
    d = ImageDraw.Draw(img)
    x = 64
    icon = generate_app_icon.artwork(110)
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 109, 109], radius=25, fill=255)
    img.paste(icon, (x, 72), mask)
    d.text((x + 132, 127), "Encave", font=ImageFont.truetype(SEMI, 64), fill=WHITE, anchor="lm")
    y = 236
    for line in headline:
        d.text((x, y), line, font=ImageFont.truetype(BOLD, 58), fill=SOFT if y > 236 else WHITE)
        y += 72
    return img.convert("RGB")


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)


def check(path, image_type):
    with Image.open(path) as im:
        w, h = im.size
        if im.mode != "RGB":
            sys.exit(f"{path}: {im.mode}, Play wants 24-bit PNG without alpha")
    if image_type == "icon" and (w, h) != (512, 512):
        sys.exit(f"{path}: {w}x{h}, the icon must be 512x512")
    if image_type == "featureGraphic" and (w, h) != (1024, 500):
        sys.exit(f"{path}: {w}x{h}, the feature graphic must be 1024x500")
    if image_type.endswith("Screenshots") and not (320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)):
        sys.exit(f"{path}: {w}x{h}, Play wants each side 320..3840 px and at most 2:1")


def capture(screens, device, prefix, screen):
    """The tour's file for a screen, whatever its number: phone/en-01-cellar.png."""
    found = sorted((screens / device).glob(f"{prefix}-[0-9][0-9]-{screen}.png"))
    return found[0] if found else None


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    screens, out = Path(sys.argv[1]), Path(sys.argv[2])
    icon = generate_app_icon.artwork(512).convert("RGB")
    for language, prefix in LANGUAGES.items():
        base = out / language
        save(icon, base / "icon" / "01.png")
        cellar = capture(screens, "phone", prefix, LISTING["screenshots"][0]["screen"])
        if cellar is None:
            sys.exit(f"No phone screenshot of {LISTING['screenshots'][0]['screen']} in {screens}.")
        with Image.open(cellar) as shot:
            save(feature_graphic(shot, LISTING["featureGraphic"][language]), base / "featureGraphic" / "01.png")
        count = {"phone": 0, "tablet": 0}
        for item in LISTING["screenshots"]:
            for device in count:
                source = capture(screens, device, prefix, item["screen"])
                if source is None:
                    print(f"::warning::No {device}/{prefix}-NN-{item['screen']}.png: left out of the listing.")
                    continue
                count[device] += 1
                name = f"{count[device]:02d}.png"
                with Image.open(source) as shot:
                    if device == "phone":
                        save(phone_screenshot(shot, item[language]), base / "phoneScreenshots" / name)
                    else:
                        tablet = shot.convert("RGB")
                        save(tablet, base / "sevenInchScreenshots" / name)
                        save(tablet, base / "tenInchScreenshots" / name)
        if count["phone"] < 2:
            sys.exit(f"{language}: {count['phone']} phone screenshots, Play needs at least 2.")
        if count["phone"] > 8 or count["tablet"] > 8:
            sys.exit(f"{language}: more than 8 screenshots of a kind, Play takes 8.")
    for path in sorted(out.glob("*/*/*.png")):
        check(path, path.parent.name)
    print(f"Listing images in {out}: {len(list(out.glob('*/*/*.png')))} files.")


if __name__ == "__main__":
    main()

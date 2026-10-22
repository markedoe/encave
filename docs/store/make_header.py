"""Universal App Store header / search results image (5244 x 2950, 16:9, no alpha).

Usage: python3 make_header.py <screens folder> <app icon png> <fr|en> <output.png>
The screens folder is the "screens" artifact of the Apple screen tour (sample
cellar), with iPhone/<lang>-01-cellar.png, -26-tonight.png and -06-wine.png.
Needs Pillow and the Inter Display font.
"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 5244, 2950
BASE = sys.argv[1]          # folder with iPhone/<lang>-*.png
ICON = sys.argv[2]
LANG = sys.argv[3]
OUT = sys.argv[4]

TEXT = {
    "fr": ("Encave", ["Votre cave à vin,", "bien rangée."],
           ["Bouteilles, casiers, ce soir, journal.", "Privée, chiffrée de bout en bout."]),
    "en": ("Encave", ["Your wine cellar,", "in order."],
           ["Bottles, racks, tonight, journal.", "Private, end-to-end encrypted."]),
}
SCREENS = ["01-cellar", "26-tonight", "06-wine"]


def font(name, size):
    return ImageFont.truetype(name, size)


def gradient():
    top, bottom = (74, 12, 30), (142, 30, 58)
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        d.line([(0, y), (W, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse([2300, 300, 5000, 2700], fill=90)
    glow = glow.filter(ImageFilter.GaussianBlur(420))
    img.paste(Image.new("RGB", (W, H), (200, 80, 105)), (0, 0), glow)
    return img


def grapes(img):
    """A discreet bunch of grapes with its stem and a tendril, drawn (no copied art)."""
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    c = (255, 255, 255, 16)
    r = 62
    top_x, top_y = 4880, 330
    rows = [5, 4, 4, 3, 2, 1]
    for i, n in enumerate(rows):
        for j in range(n):
            x = top_x + (j - (n - 1) / 2) * r * 1.9
            y = top_y + 170 + i * r * 1.65
            d.ellipse([x - r, y - r, x + r, y + r], fill=c)
    d.line([(top_x, top_y + 110), (top_x + 30, top_y - 40)], fill=c, width=22)
    d.arc([top_x + 20, top_y - 120, top_x + 220, top_y + 40], 180, 360, fill=c, width=14)
    d.arc([top_x + 120, top_y - 90, top_x + 260, top_y + 40], 0, 200, fill=c, width=12)
    img.paste(layer, (0, 0), layer)


def phone(screen_path, height):
    shot = Image.open(screen_path).convert("RGB")
    sw = int(shot.width * height / shot.height)
    shot = shot.resize((sw, height), Image.LANCZOS)
    bezel = int(height * 0.022)
    radius = int(height * 0.075)
    fw, fh = sw + 2 * bezel, height + 2 * bezel
    frame = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle([0, 0, fw - 1, fh - 1], radius=radius + bezel, fill=(18, 16, 17, 255))
    mask = Image.new("L", (sw, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw - 1, height - 1], radius=radius, fill=255)
    frame.paste(shot, (bezel, bezel), mask)
    return frame


def shadowed(canvas, frame, x, y):
    sh = Image.new("RGBA", (frame.width + 400, frame.height + 400), (0, 0, 0, 0))
    alpha = frame.split()[3].point(lambda a: int(a * 0.45))
    sh.paste((0, 0, 0, 255), (200, 240), alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(70))
    canvas.paste(sh, (x - 200, y - 200), sh)
    canvas.paste(frame, (x, y), frame)


def main():
    title, head, sub = TEXT[LANG]
    img = gradient().convert("RGBA")
    grapes(img)

    # Phones, right side: back left, back right, front centre.
    side_h, front_h = 1640, 1980
    left = phone(f"{BASE}/iPhone/{LANG}-{SCREENS[2]}.png", side_h)
    right = phone(f"{BASE}/iPhone/{LANG}-{SCREENS[1]}.png", side_h)
    front = phone(f"{BASE}/iPhone/{LANG}-{SCREENS[0]}.png", front_h)
    cx = 3700
    shadowed(img, left, cx - 470 - left.width // 2 - 210, (H - left.height) // 2 + 60)
    shadowed(img, right, cx + 470 - right.width // 2 + 210, (H - right.height) // 2 + 60)
    shadowed(img, front, cx - front.width // 2, (H - front.height) // 2)

    # Text, left side.
    d = ImageDraw.Draw(img)
    x = 600
    icon = Image.open(ICON).convert("RGBA").resize((300, 300), Image.LANCZOS)
    m = Image.new("L", icon.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, 299, 299], radius=68, fill=255)
    y = 820
    img.paste(icon, (x, y), m)
    return img, (x, y, title, head, sub)


if __name__ == "__main__":
    img, (x, y, title, head, sub) = main()
    d = ImageDraw.Draw(img)
    fonts = "/usr/share/fonts"
    import glob
    def find(style):
        for f in glob.glob(f"{fonts}/**/*.ttf", recursive=True) + glob.glob(f"{fonts}/**/*.otf", recursive=True):
            if f.split("/")[-1].lower().startswith(style.lower()):
                return f
        raise SystemExit(f"font {style} not found")
    bold = find("InterDisplay-Bold")
    semi = find("InterDisplay-SemiBold")
    reg = find("InterDisplay-Regular")
    d.text((x + 360, y + 150), title, font=ImageFont.truetype(semi, 190), fill=(255, 255, 255), anchor="lm")
    ty = y + 470
    for line in head:
        d.text((x, ty), line, font=ImageFont.truetype(bold, 200), fill=(255, 255, 255))
        ty += 245
    ty += 80
    for line in sub:
        d.text((x, ty), line, font=ImageFont.truetype(reg, 104), fill=(255, 233, 238))
        ty += 140
    img.convert("RGB").save(OUT, "PNG", optimize=True)
    print(OUT, Image.open(OUT).size, Image.open(OUT).mode)

import argparse
import hashlib
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SIZE = (1600, 1067)
THEMES = {
    "lake": ((112, 151, 166), (236, 223, 188), 91),
    "sunset": ((111, 105, 147), (247, 168, 118), 92),
    "forest": ((91, 127, 120), (205, 215, 178), 93),
    "city": ((36, 48, 76), (156, 120, 132), 94),
    "flowers": ((126, 171, 174), (240, 221, 181), 95),
    "dog": ((128, 166, 157), (239, 218, 174), 96),
    "coffee": ((169, 151, 130), (231, 215, 185), 97),
    "snow": ((101, 143, 168), (220, 231, 227), 98),
}


def artwork(theme):
    top, bottom, seed = THEMES[theme]
    rng = random.Random(seed)
    width, height = SIZE
    image = Image.new("RGB", SIZE)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = min(1, y / (height * 0.72))
        color = tuple(round(a * (1 - t) + b * t) for a, b in zip(top, bottom))
        draw.line((0, y, width, y), fill=color)
    sun = Image.new("RGBA", SIZE)
    light = ImageDraw.Draw(sun)
    light.ellipse((1030, 130, 1230, 330), fill=(255, 227, 177, 125))
    image = Image.alpha_composite(image.convert("RGBA"), sun.filter(ImageFilter.GaussianBlur(38))).convert("RGB")
    draw = ImageDraw.Draw(image)
    draw.ellipse((1080, 180, 1180, 280), fill=(254, 235, 198))
    for layer in range(5):
        base = height * (0.47 + layer * 0.075)
        phase = rng.uniform(0, 7)
        points = [(x, base - height * (0.11 - layer * 0.012) * (1 + math.sin(x / width * 8 + phase) + 0.3 * math.sin(x / width * 21))) for x in range(-20, width + 21, 10)]
        color = tuple(round(c * (0.87 - layer * 0.11)) for c in bottom)
        draw.polygon(points + [(width, height), (0, height)], fill=color)
    if theme in {"lake", "sunset"}:
        horizon = 650
        reflected = image.crop((0, 240, width, horizon)).transpose(Image.Transpose.FLIP_TOP_BOTTOM).resize((width, height - horizon))
        image.paste(reflected.filter(ImageFilter.GaussianBlur(3)), (0, horizon))
        draw = ImageDraw.Draw(image)
        for _ in range(360):
            y = rng.randrange(horizon, height)
            x = rng.randrange(width)
            draw.line((x, y, x + rng.randrange(12, 110), y), fill=(185, 186, 167), width=1)
    if theme == "forest":
        for _ in range(100):
            x = rng.randrange(-50, width + 50)
            y = rng.randrange(650, height + 100)
            h = rng.randrange(160, 440)
            color = rng.choice([(38, 79, 68), (46, 90, 75), (58, 103, 83)])
            draw.line((x, y, x, y - h), fill=(70, 76, 56), width=8)
            for k in range(5):
                cy = y - h + k * h * 0.13
                spread = 15 + k * 15
                draw.polygon([(x, cy - 35), (x - spread, cy + 90), (x + spread, cy + 90)], fill=color)
    if theme == "city":
        for x in range(-20, width, 70):
            y = rng.randrange(360, 700)
            draw.rectangle((x, y, x + 60, height), fill=rng.choice([(35, 48, 67), (46, 58, 75), (54, 63, 81)]))
            for yy in range(y + 20, height - 20, 35):
                for xx in range(x + 10, x + 55, 18):
                    if rng.random() < 0.6:
                        draw.rectangle((xx, yy, xx + 6, yy + 12), fill=(222, 185, 120))
    if theme == "flowers":
        for _ in range(400):
            x, y = rng.randrange(width), rng.randrange(640, height)
            radius = round(4 + 14 * (y - 640) / (height - 640))
            draw.line((x, y, x, y + radius * 4), fill=(83, 112, 67), width=2)
            color = rng.choice([(219, 154, 149), (239, 211, 158), (186, 162, 187)])
            for angle in range(0, 360, 72):
                cx, cy = x + radius * math.cos(math.radians(angle)), y + radius * math.sin(math.radians(angle))
                draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color)
            draw.ellipse((x - radius / 2, y - radius / 2, x + radius / 2, y + radius / 2), fill=(142, 110, 64))
    if theme == "dog":
        draw.ellipse((510, 590, 1110, 1130), fill=(190, 143, 88))
        draw.ellipse((535, 340, 1065, 850), fill=(215, 171, 111))
        draw.ellipse((475, 355, 660, 790), fill=(169, 118, 66))
        draw.ellipse((940, 355, 1125, 790), fill=(169, 118, 66))
        draw.ellipse((610, 560, 990, 860), fill=(235, 204, 153))
        for x in [690, 890]:
            draw.ellipse((x - 17, 518, x + 17, 552), fill=(54, 48, 39))
        draw.ellipse((760, 625, 840, 683), fill=(61, 49, 39))
        draw.arc((730, 650, 870, 745), 10, 170, fill=(91, 66, 44), width=5)
    if theme == "coffee":
        draw.rectangle((0, 690, width, height), fill=(129, 103, 78))
        draw.ellipse((430, 720, 1170, 1000), fill=(208, 193, 162))
        draw.ellipse((1000, 520, 1230, 800), outline=(236, 223, 194), width=43)
        draw.rounded_rectangle((530, 500, 1080, 875), radius=105, fill=(234, 222, 194))
        draw.ellipse((530, 455, 1080, 665), fill=(245, 231, 200))
        draw.ellipse((570, 485, 1040, 625), fill=(99, 65, 43))
        draw.arc((660, 505, 950, 600), 10, 320, fill=(209, 168, 117), width=12)
    if theme == "snow":
        for cx, peak, span in [(350, 270, 420), (930, 140, 530), (1450, 360, 360)]:
            draw.polygon([(cx - span, 880), (cx, peak), (cx + span, 880)], fill=(116, 142, 153))
            draw.polygon([(cx - span * 0.36, peak + 240), (cx, peak), (cx + span * 0.32, peak + 210), (cx + 35, peak + 160), (cx - 40, peak + 225)], fill=(235, 242, 234))
        draw.polygon([(0, 910), (480, 810), (1050, 930), (1600, 805), (1600, height), (0, height)], fill=(220, 232, 225))
    return image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    import io
    for theme in THEMES:
        buffer = io.BytesIO()
        artwork(theme).save(buffer, format="JPEG", quality=92, subsampling=0)
        data = buffer.getvalue()
        for directory in ["ios/PhotoPilot/Resources/Demo", "android/app/src/main/assets/demo"]:
            path = ROOT / directory / f"demo_{theme}.jpg"
            if args.check:
                if path.read_bytes() != data:
                    raise SystemExit(f"Stale demo artwork: {path.relative_to(ROOT)}")
            else:
                path.write_bytes(data)
        print(theme, hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
